package com.nova.chat.client.network;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Optional abstract base for a platform {@code NetworkClient} facade.
 *
 * <p>Architecture B already centralises the Netty transport, handshake/keepalive,
 * handler map and reconnect policy in {@link CoreNetworkClient}. Every platform
 * plugin still carries a thin facade that (a) constructs a {@link CoreNetworkClient}
 * with platform-specific scheduler/logger/version inputs and (b) forwards the
 * historical public API to it. This base absorbs the forwarding boilerplate so the
 * seven platform facades shrink to "construct the core + register platform-specific
 * inbound handlers".
 *
 * <p>Usage: a platform facade extends this class, calls
 * {@link #initCore(ClientConnectionConfig, PlatformType, SchedulerBridge, ClientLogger, String, Function, String)}
 * from its own constructor (which is free to take whatever platform-specific args
 * it needs), and overrides {@link #sendPacket(Packet)}, {@link #connect(String, int)}
 * or {@link #registerHandler(Class, Consumer)} only when it must add a platform
 * hop (Folia) or pre-send bookkeeping (Bukkit pending-request tracking).
 *
 * <p>The public method signatures are identical to the historical per-platform
 * {@code NetworkClient} surface, so callers (plugin bootstrap, chat listeners,
 * commands) do not change.
 *
 * <p>Threading: as with {@link CoreNetworkClient}, public methods are safe to call
 * from any thread. Inbound handlers run on the Netty event loop unless a platform
 * overrides {@link #registerHandler} to hop threads.
 */
public abstract class AbstractPlatformNetworkClient {

    /** The shared transport; set once by {@link #initCore}. */
    private CoreNetworkClient core;

    /**
     * Constructs and stores the {@link CoreNetworkClient} that this facade forwards
     * to. Must be called exactly once from the subclass constructor. Platform
     * subclasses supply the platform-specific {@link SchedulerBridge},
     * {@link ClientLogger}, {@link PlatformType}, credentials-file hint and server
     * version here; everything else is owned by the core.
     *
     * @param connectionConfig      host/port/credentials/timeouts/reconnect source
     * @param platformType          advertised in the handshake
     * @param scheduler             platform scheduler (seconds-based)
     * @param logger                platform logger
     * @param credentialsConfigFile file name mentioned in NC-401 errors
     * @param usernameTransformer   rewrite handshake username (use
     *                              {@link Function#identity()} when no rewrite)
     * @param serverVersion         Minecraft server version sent in the handshake;
     *                              null/blank → ""
     */
    protected void initCore(
            ClientConnectionConfig connectionConfig,
            PlatformType platformType,
            SchedulerBridge scheduler,
            ClientLogger logger,
            String credentialsConfigFile,
            Function<String, String> usernameTransformer,
            String serverVersion
    ) {
        if (this.core != null) {
            throw new IllegalStateException("CoreNetworkClient already initialized");
        }
        this.core = new CoreNetworkClient(
                connectionConfig,
                platformType,
                scheduler,
                logger,
                credentialsConfigFile,
                usernameTransformer,
                serverVersion
        );
    }

    /**
     * The shared transport. Package-visible for the deprecated platform channel
     * handler shims and advanced adapters.
     */
    protected final CoreNetworkClient core() {
        if (core == null) {
            throw new IllegalStateException("initCore not called yet");
        }
        return core;
    }

    // --- forwarding API (historical NetworkClient surface) ---

    /**
     * Connects to the NovaLink backend. Completes when handshake succeeds or fails.
     *
     * <p>Default delegates straight to {@link CoreNetworkClient#connect}. Platforms
     * that must not run the Netty bootstrap on the caller thread (Folia region
     * threads) override this to hop to an async scheduler first.
     */
    public CompletableFuture<Boolean> connect(String host, int port) {
        return core().connect(host, port);
    }

    /** Explicit shutdown; cancels reconnect budget and does not reschedule. */
    public void disconnect() {
        core().disconnect();
    }

    /**
     * Sends a packet to the backend. Channel-action correlation tracking is handled
     * inside {@link CoreNetworkClient#sendPacket} (single-entry contract).
     *
     * <p>Default delegates to the core. Platforms that need to record per-player
     * pending-request context before the wire send override this and call
     * {@code super.sendPacket(packet)} after their bookkeeping.
     */
    public void sendPacket(Packet packet) {
        core().sendPacket(packet);
    }

    /**
     * @return the tracker mapping in-flight channel-action request ids to players,
     *         used by the platform's {@code ChannelActionResponsePacket} handler
     */
    public ChannelResponseTracker getChannelResponseTracker() {
        return core().getChannelResponseTracker();
    }

    /**
     * Registers a packet handler.
     *
     * <p>Default delegates to the core, which dispatches on the Netty event loop.
     * Platforms that must run handlers off the Netty thread (Folia) override this
     * to wrap the handler in an async hop.
     */
    public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
        core().registerHandler(packetClass, handler);
    }

    /** @return true if the TCP connection is active */
    public boolean isConnected() {
        return core().isConnected();
    }

    /** @return true if the handshake authenticated successfully */
    public boolean isAuthenticated() {
        return core().isAuthenticated();
    }

    /** @return the shared packet registry */
    public PacketRegistry getPacketRegistry() {
        return core().getPacketRegistry();
    }
}
