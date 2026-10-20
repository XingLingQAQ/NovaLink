package com.nova.chat.mod.network;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.platform.Platform;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Mod NetworkClient facade over the shared {@code CoreNetworkClient} (Architecture B).
 *
 * <p>Mirrors the velocity/bungee/nukkit/folia/pnx/sponge facades: the Netty transport,
 * handshake, keepalive, handler map and reconnect policy live in client-core; this
 * class only supplies a {@link SchedulerBridge} and {@link ClientLogger} backed by the
 * mod {@link Platform} abstraction so the same facade works across all four mod
 * loaders (fabric / forge / neoforge / quilt) without per-loader network code.
 *
 * <p>Public surface is the {@link AbstractPlatformNetworkClient} API
 * (connect / disconnect / sendPacket / registerHandler / isConnected /
 * isAuthenticated / getChannelResponseTracker / getPacketRegistry).
 */
public class NetworkClient extends AbstractPlatformNetworkClient {

    private final Platform platform;

    /**
     * Creates a NetworkClient for the mod common layer.
     *
     * @param platform the mod platform abstraction (scheduler + logger + version)
     * @param config   the mod configuration (backend host/port/credentials)
     */
    public NetworkClient(Platform platform, ModConfig config) {
        this.platform = Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(config, "config");
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new PlatformSchedulerBridge(platform);
        ClientLogger logger = new PlatformClientLogger(platform);
        String serverVersion = platform.getServerVersion();
        initCore(
                connectionConfig,
                platform.getPlatformType().toCommon(),
                scheduler,
                logger,
                "novachat.yml",
                Function.identity(),
                serverVersion != null ? serverVersion : ""
        );
    }

    /**
     * In-flight admin-action requests mapped to the sender that issued them, so
     * the asynchronous {@link com.nova.chat.common.protocol.packets.AdminActionResponsePacket}
     * can be routed back. Mirrors the Folia {@code AsyncNetworkClient} map and
     * Bukkit's {@code pendingAdminRequests}.
     */
    private final java.util.concurrent.ConcurrentMap<java.util.UUID, java.util.UUID>
            pendingAdminRequests = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Sends a packet. For an
     * {@link com.nova.chat.common.protocol.packets.AdminActionPacket} the
     * {@code requestId -> senderId} correlation is recorded before the wire
     * send so the response can be rendered to whoever issued the command.
     *
     * <p>Channel-action correlation stays inside the shared
     * {@link com.nova.chat.client.network.CoreNetworkClient}.
     */
    @Override
    public void sendPacket(Packet packet) {
        if (packet instanceof com.nova.chat.common.protocol.packets.AdminActionPacket admin
                && admin.getPlayerId() != null && admin.getRequestId() != null) {
            pendingAdminRequests.put(admin.getRequestId(), admin.getPlayerId());
        }
        super.sendPacket(packet);
    }

    /**
     * Removes and returns the sender recorded for an admin-action request id.
     * Removal is deliberate: a response is rendered exactly once, so a second
     * or unsolicited response reads as "no pending request".
     *
     * @param requestId the response's request id
     * @return the originating sender id, or null when nothing is pending
     */
    public java.util.UUID consumePendingAdminRequest(java.util.UUID requestId) {
        if (requestId == null) {
            return null;
        }
        return pendingAdminRequests.remove(requestId);
    }

    /**
     * @return the number of admin actions awaiting a response, for diagnostics
     */
    public int pendingAdminRequestCount() {
        return pendingAdminRequests.size();
    }

    /**
     * @return the platform this client is bridging
     */
    public Platform getPlatform() {
        return platform;
    }

    /**
     * PLAT-001: wraps every registered handler so it dispatches on the
     * Minecraft server thread instead of the Netty event loop. MC server APIs
     * ({@code getPlayerList()}, {@code sendSystemMessage},
     * {@code broadcastSystemMessage}, dimension lookup) are not safe off the
     * server thread; this hop mirrors the Folia
     * {@code AsyncNetworkClient#registerHandler} wrap, but targets the server
     * thread via {@link Platform#execute} rather than an async scheduler.
     *
     * <p>If a test stubs {@link Platform#execute} to run the runnable inline
     * (the common case for unit tests that capture the handler Consumer and
     * invoke it synchronously), the wrapped handler still runs to completion
     * on the test thread, preserving existing assertions.
     */
    @SuppressWarnings("unchecked")
    @Override
    // TODO(dedup): same thread-jump wrap as Folia AsyncNetworkClient#registerHandler
    // (line 111), but this hops to the MC server thread via platform.execute (sync),
    // while Folia hops to its async scheduler. Different thread guarantees — not
    // unified. AbstractPlatformNetworkClient#registerHandler is the no-hop default;
    // both subclasses override it with platform-specific semantics. Do not merge.
    public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
        core().registerHandler(packetClass, packet -> platform.execute(() -> handler.accept((T) packet)));
    }

    /**
     * Scheduler adapter that delegates async/delayed execution to the platform
     * (each mod loader implements {@link Platform#runAsync} / {@link Platform#runLater}
     * against its own thread model).
     */
    static final class PlatformSchedulerBridge implements SchedulerBridge {
        private final Platform platform;

        PlatformSchedulerBridge(Platform platform) {
            this.platform = platform;
        }

        @Override
        public void runAsync(Runnable task) {
            platform.runAsync(task);
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            platform.runLater(task, delaySeconds);
        }
    }

    /**
     * Logger adapter that routes shared-client log lines through the platform logger
     * so each mod loader's SLF4J/log4j setup captures them.
     */
    static final class PlatformClientLogger implements ClientLogger {
        private final Platform platform;

        PlatformClientLogger(Platform platform) {
            this.platform = platform;
        }

        @Override
        public void info(String message) {
            platform.logInfo(message);
        }

        @Override
        public void warn(String message) {
            platform.logWarn(message);
        }

        @Override
        public void debug(String message) {
            platform.logDebug(message);
        }

        @Override
        public void error(String message) {
            platform.logError(message);
        }

        @Override
        public void error(String message, Throwable cause) {
            platform.logError(message, cause);
        }
    }
}
