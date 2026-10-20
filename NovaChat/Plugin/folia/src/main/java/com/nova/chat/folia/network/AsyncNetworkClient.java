package com.nova.chat.folia.network;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Folia async network client facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the Folia plugin.
 * Netty bootstrap, handshake, keepalive, handler map, and reconnect policy live
 * in client-core; this class only supplies Folia scheduler/logger adapters and
 * the two Folia-specific safety hops:
 * <ul>
 *   <li><b>Async connect</b> — {@link CoreNetworkClient#connect} runs the Netty
 *       bootstrap on the caller thread. Folia region threads must never block on
 *       a bootstrap, so {@link #connect(String, int)} hops through the async
 *       scheduler before delegating.</li>
 *   <li><b>Handler dispatch off the Netty thread</b> — {@link CoreNetworkClient}
 *       runs registered packet handlers on the Netty event loop. The facade wraps
 *       each user-registered handler so it hops to the async scheduler, matching
 *       the historical Folia behavior.</li>
 * </ul>
 *
 * <p>Threading: public methods are safe to call from any thread, including Folia
 * region threads.
 */
public class AsyncNetworkClient extends AbstractPlatformNetworkClient {

    private final FoliaSchedulerAdapter scheduler;

    /**
     * Correlates an in-flight {@link AdminActionPacket} with the sender it
     * belongs to, so the asynchronous {@code AdminActionResponsePacket} can be
     * rendered back to that sender (player, or the console sentinel).
     *
     * <p>Admin actions are outside the shared {@link
     * com.nova.chat.client.network.ChannelResponseTracker}, which only tracks
     * {@code ChannelActionPacket}s, so the correlation lives here — the Folia
     * counterpart of Bukkit's {@code NetworkClient#pendingAdminRequests}.
     * Concurrent because a packet is stamped from whichever region/async thread
     * ran the command while the response is consumed on the handler hop.
     */
    private final ConcurrentMap<UUID, UUID> pendingAdminRequests = new ConcurrentHashMap<>();

    /**
     * Creates a new AsyncNetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     * @param scheduler the Folia scheduler adapter
     */
    public AsyncNetworkClient(NovaChatFolia plugin, NovaChatConfig config, FoliaSchedulerAdapter scheduler) {
        this.scheduler = scheduler;
        SchedulerBridge bridge = new FoliaSchedulerBridge(scheduler);
        ClientLogger logger = new FoliaClientLogger(plugin);
        String serverVersion = plugin.getServer().getVersion();
        initCore(
                config.toClientConnectionConfig(),
                PlatformType.FOLIA,
                bridge,
                logger,
                "config.yml",
                Function.identity(),
                serverVersion
        );
    }

    /**
     * Connects to the NovaLink backend asynchronously.
     *
     * <p>The Netty bootstrap is dispatched via Folia's async scheduler so it never
     * runs on a region thread. The returned future completes when the handshake
     * succeeds or fails (not on TCP alone).
     *
     * @param host the backend host
     * @param port the backend port
     * @return a future that completes with true if connection and authentication succeed
     */
    @Override
    public CompletableFuture<Boolean> connect(String host, int port) {
        // CoreNetworkClient.connect (client-core line 130) runs the Netty bootstrap
        // on the caller thread. Hop to the async scheduler so a region thread never
        // blocks on bootstrap.connect(). The auth future comes from core.connect, so
        // wire it into the returned future once the async task runs.
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        scheduler.runAsync(() -> {
            try {
                core().connect(host, port).whenComplete((v, t) -> {
                    if (t != null) {
                        result.completeExceptionally(t);
                    } else {
                        result.complete(v);
                    }
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    /**
     * Sends a packet to the backend. For {@link AdminActionPacket}s this records
     * the {@code requestId -> senderId} correlation before the wire send, so the
     * asynchronous {@code AdminActionResponsePacket} can be routed back to the
     * originating sender. Everything else delegates straight to the core, which
     * owns channel-action correlation.
     *
     * @param packet the packet to send
     */
    @Override
    public void sendPacket(Packet packet) {
        if (packet instanceof AdminActionPacket adminActionPacket
                && adminActionPacket.getPlayerId() != null
                && adminActionPacket.getRequestId() != null) {
            pendingAdminRequests.put(adminActionPacket.getRequestId(),
                    adminActionPacket.getPlayerId());
        }
        super.sendPacket(packet);
    }

    /**
     * Removes and returns the sender id recorded for an admin-action request id.
     *
     * <p>Removal (not just lookup) is deliberate: a response is rendered exactly
     * once, and an unsolicited response therefore reads as "no pending request".
     *
     * @param requestId the response's request id
     * @return the originating sender id, or {@code null} when nothing is pending
     */
    public UUID consumePendingAdminRequest(UUID requestId) {
        if (requestId == null) {
            return null;
        }
        return pendingAdminRequests.remove(requestId);
    }

    /**
     * @return the number of in-flight admin actions awaiting a response; for
     *         diagnostics and tests
     */
    public int pendingAdminRequestCount() {
        return pendingAdminRequests.size();
    }

    /**
     * Registers a packet handler. The handler is dispatched off the Netty event
     * loop via Folia's async scheduler to keep region threads safe.
     *
     * @param packetClass the packet class to handle
     * @param handler the handler function
     * @param <T> the packet type
     */
    @SuppressWarnings("unchecked")
    @Override
    // TODO(dedup): same thread-jump wrap as MOD/common NetworkClient#registerHandler
    // (line 80), but this hops to Folia's async scheduler, while MOD/common hops to
    // the MC server thread via platform.execute (sync). Different thread guarantees
    // — not unified. AbstractPlatformNetworkClient#registerHandler is the no-hop
    // default; both subclasses override it with platform-specific semantics. Do not
    // merge.
    public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
        core().registerHandler(packetClass, packet -> scheduler.runAsync(() -> handler.accept((T) packet)));
    }

    /**
     * Gets the Folia scheduler adapter.
     *
     * @return the scheduler adapter
     */
    public FoliaSchedulerAdapter getScheduler() {
        return scheduler;
    }

    /**
     * Folia scheduler bridge: delegates to the existing {@link FoliaSchedulerAdapter}.
     * {@link SchedulerBridge#runAsync(Runnable)} maps to
     * {@link FoliaSchedulerAdapter#runAsync(Runnable)}; {@link SchedulerBridge#runLater}
     * converts seconds to ticks (seconds * 20) for
     * {@link FoliaSchedulerAdapter#runAsyncDelayed(Runnable, long)}.
     */
    static final class FoliaSchedulerBridge implements SchedulerBridge {
        private final FoliaSchedulerAdapter scheduler;

        FoliaSchedulerBridge(FoliaSchedulerAdapter scheduler) {
            this.scheduler = scheduler;
        }

        @Override
        public void runAsync(Runnable task) {
            scheduler.runAsync(task);
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            scheduler.runAsyncDelayed(task, delaySeconds * 20L);
        }
    }

    /**
     * Maps the Bukkit JUL logger + plugin debug gate onto {@link ClientLogger}.
     */
    static final class FoliaClientLogger implements ClientLogger {
        private final NovaChatFolia plugin;

        FoliaClientLogger(NovaChatFolia plugin) {
            this.plugin = plugin;
        }

        @Override
        public void info(String message) {
            plugin.getLogger().info(message);
        }

        @Override
        public void warn(String message) {
            plugin.getLogger().warning(message);
        }

        @Override
        public void debug(String message) {
            plugin.debug(message);
        }

        @Override
        public void error(String message) {
            plugin.getLogger().severe(message);
        }

        @Override
        public void error(String message, Throwable cause) {
            if (cause == null) {
                plugin.getLogger().severe(message);
            } else {
                plugin.getLogger().log(Level.SEVERE, message, cause);
            }
        }
    }
}
