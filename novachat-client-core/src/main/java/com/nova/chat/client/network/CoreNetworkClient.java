package com.nova.chat.client.network;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.codec.PacketDecoder;
import com.nova.chat.common.protocol.codec.PacketEncoder;
import com.nova.chat.common.protocol.codec.Varint21FrameDecoder;
import com.nova.chat.common.protocol.codec.Varint21LengthFieldPrepender;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Shared Netty client transport for NovaChat plugins.
 *
 * <p>Owns connection lifecycle, handshake/keepalive defaults, packet-handler map,
 * and reconnect scheduling via {@link SchedulerBridge}. Platform modules inject
 * scheduler + logger + credentials config and keep player/UX concerns local.
 *
 * <p><strong>Architecture B:</strong> plugin-only. Never imported by
 * {@code novalink-core}.
 *
 * <p>Threading: public methods are safe to call from any thread. Inbound handlers
 * run on the Netty event loop unless a platform registers wrappers that hop
 * threads (e.g. Folia).
 */
public final class CoreNetworkClient {

    private final ClientConnectionConfig connectionConfig;
    private final PlatformType platformType;
    private final SchedulerBridge scheduler;
    private final ClientLogger logger;
    /** Shown in NC-401 hints (e.g. {@code config.yml}, {@code config.toml}). */
    private final String credentialsConfigFile;
    /** Optional username rewrite (e.g. instance suffix); identity by default. */
    private final Function<String, String> usernameTransformer;
    /** Minecraft server version reported in the handshake (e.g. "1.20.4"); "" if unknown. */
    private final String serverVersion;

    private final PacketRegistry packetRegistry;
    private final ReconnectPolicy reconnectPolicy;

    /**
     * Correlates in-flight {@link ChannelActionPacket}s to their originating
     * player so the asynchronous {@code ChannelActionResponsePacket} can be
     * resolved later. Owned here so every {@code sendPacket} path — including
     * the platform facades that delegate straight to {@link #sendPacket} —
     * tracks channel-action context by construction (coupling #3 single entry).
     */
    private final ChannelResponseTracker channelResponseTracker = new ChannelResponseTracker();

    private EventLoopGroup workerGroup;
    private volatile Channel channel;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private final Map<Class<? extends Packet>, Consumer<Packet>> packetHandlers =
            new ConcurrentHashMap<>();

    private volatile CompletableFuture<Boolean> authFuture;
    private volatile String lastHost;
    private volatile int lastPort;

    /**
     * Creates a core client using the connection config's host/port for reconnect
     * and a default credentials-file hint of {@code config.yml}.
     */
    public CoreNetworkClient(
            ClientConnectionConfig connectionConfig,
            PlatformType platformType,
            SchedulerBridge scheduler,
            ClientLogger logger
    ) {
        this(connectionConfig, platformType, scheduler, logger, "config.yml", Function.identity(), "");
    }

    /**
     * Full constructor.
     *
     * @param connectionConfig       host/port/credentials/timeouts/reconnect policy source
     * @param platformType           advertised in the handshake
     * @param scheduler              platform scheduler (seconds-based)
     * @param logger                 platform logger
     * @param credentialsConfigFile  file name mentioned in NC-401 errors
     * @param usernameTransformer    rewrite handshake username (e.g. {@code user@node})
     */
    public CoreNetworkClient(
            ClientConnectionConfig connectionConfig,
            PlatformType platformType,
            SchedulerBridge scheduler,
            ClientLogger logger,
            String credentialsConfigFile,
            Function<String, String> usernameTransformer
    ) {
        this(connectionConfig, platformType, scheduler, logger, credentialsConfigFile, usernameTransformer, "");
    }

    /**
     * Full constructor with server version.
     *
     * @param connectionConfig       host/port/credentials/timeouts/reconnect policy source
     * @param platformType           advertised in the handshake
     * @param scheduler              platform scheduler (seconds-based)
     * @param logger                 platform logger
     * @param credentialsConfigFile  file name mentioned in NC-401 errors
     * @param usernameTransformer    rewrite handshake username (e.g. {@code user@node})
     * @param serverVersion          Minecraft server version sent in the handshake; null/blank → ""
     */
    public CoreNetworkClient(
            ClientConnectionConfig connectionConfig,
            PlatformType platformType,
            SchedulerBridge scheduler,
            ClientLogger logger,
            String credentialsConfigFile,
            Function<String, String> usernameTransformer,
            String serverVersion
    ) {
        this.connectionConfig = Objects.requireNonNull(connectionConfig, "connectionConfig");
        this.platformType = Objects.requireNonNull(platformType, "platformType");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.credentialsConfigFile = credentialsConfigFile == null || credentialsConfigFile.isBlank()
                ? "config.yml"
                : credentialsConfigFile;
        this.usernameTransformer = usernameTransformer != null
                ? usernameTransformer
                : Function.identity();
        this.serverVersion = serverVersion != null ? serverVersion : "";
        this.packetRegistry = NovaProtocol.createRegistry();
        this.reconnectPolicy = connectionConfig.toReconnectPolicy();
        this.lastHost = connectionConfig.getHost();
        this.lastPort = connectionConfig.getPort();
        registerDefaultHandlers();
    }

    // --- public API (mirrors historical NetworkClient surface) ---

    /**
     * Connects to the backend. Completes when handshake succeeds or fails (not on TCP alone).
     */
    public CompletableFuture<Boolean> connect(String host, int port) {
        if (connected.get()) {
            return CompletableFuture.completedFuture(true);
        }

        Objects.requireNonNull(host, "host");
        this.lastHost = host;
        this.lastPort = port;

        authFuture = new CompletableFuture<>();
        workerGroup = new NioEventLoopGroup();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionConfig.getConnectTimeoutMs())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("frameDecoder", new Varint21FrameDecoder());
                        pipeline.addLast("framePrepender", new Varint21LengthFieldPrepender());
                        pipeline.addLast("packetDecoder", new PacketDecoder(packetRegistry));
                        pipeline.addLast("packetEncoder", new PacketEncoder(packetRegistry));
                        pipeline.addLast("handler", new CoreClientChannelHandler(CoreNetworkClient.this));
                    }
                });

        logger.debug("Connecting to NovaLink backend at " + host + ":" + port);

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                channel = future.channel();
                connected.set(true);
                reconnectAttempts.set(0);
                logger.debug("TCP connection established, sending handshake...");
                sendHandshake();
            } else {
                String msg = future.cause() != null ? future.cause().getMessage() : "unknown";
                logger.warn("Failed to connect to NovaLink: " + msg);
                completeAuth(false);
                scheduleReconnect();
            }
        });

        return authFuture;
    }

    /**
     * Connects using host/port from {@link ClientConnectionConfig}.
     */
    public CompletableFuture<Boolean> connect() {
        return connect(connectionConfig.getHost(), connectionConfig.getPort());
    }

    /**
     * Explicit shutdown; cancels reconnect budget for this session and does not reschedule.
     */
    public void disconnect() {
        reconnecting.set(false);
        authenticated.set(false);

        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            ch.close().syncUninterruptibly();
        }
        channel = null;
        connected.set(false);

        EventLoopGroup group = workerGroup;
        if (group != null && !group.isShutdown()) {
            group.shutdownGracefully();
        }
        workerGroup = null;

        logger.debug("Disconnected from NovaLink backend");
    }

    /**
     * Sends a packet to the backend on the active channel. Before any
     * {@link ChannelActionPacket} hits the wire, records it with the shared
     * {@link ChannelResponseTracker} (after an opportunistic expired-entry
     * cleanup) so the asynchronous {@code ChannelActionResponsePacket} can be
     * correlated back to its originating player. If the channel is inactive the
     * packet is dropped with a debug log and no exception is raised.
     *
     * <p>This is the single entry point for outgoing channel-action correlation
     * (coupling #3); platform facades that delegate here must not replicate the
     * track/cleanup shell.
     *
     * @param packet the packet to send (not null)
     */
    public void sendPacket(Packet packet) {
        // Coupling #3 single-entry contract: every outgoing ChannelActionPacket
        // is recorded for asynchronous response correlation before it hits the
        // wire. Platform facades no longer replicate this if/track shell.
        if (packet instanceof ChannelActionPacket) {
            channelResponseTracker.cleanupExpired();
            channelResponseTracker.track((ChannelActionPacket) packet);
        }
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(packet);
            logger.debug("Sent packet: " + packet.getClass().getSimpleName());
        } else {
            logger.debug("Cannot send packet: not connected");
        }
    }

    /**
     * @return the shared {@link ChannelResponseTracker} that correlates
     *         in-flight channel-action requests to players; platform
     *         {@code ChannelActionResponsePacket} handlers consume from it.
     */
    public ChannelResponseTracker getChannelResponseTracker() {
        return channelResponseTracker;
    }

    @SuppressWarnings("unchecked")
    public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
        Objects.requireNonNull(packetClass, "packetClass");
        Objects.requireNonNull(handler, "handler");
        packetHandlers.put(packetClass, (Consumer<Packet>) handler);
    }

    public boolean isConnected() {
        Channel ch = channel;
        return connected.get() && ch != null && ch.isActive();
    }

    public boolean isAuthenticated() {
        return authenticated.get();
    }

    public PacketRegistry getPacketRegistry() {
        return packetRegistry;
    }

    public ClientConnectionConfig getConnectionConfig() {
        return connectionConfig;
    }

    public PlatformType getPlatformType() {
        return platformType;
    }

    public ReconnectPolicy getReconnectPolicy() {
        return reconnectPolicy;
    }

    /** Resets reconnect attempt counter (e.g. after {@code /nc reload}). */
    public void resetReconnectBudget() {
        reconnectAttempts.set(0);
    }

    // --- package / internal API used by channel handler + tests ---

    ClientLogger logger() {
        return logger;
    }

    /**
     * Dispatches an inbound packet to the registered handler, if any.
     */
    public void handlePacket(Packet packet) {
        if (packet == null) {
            return;
        }
        logger.debug("Received packet: " + packet.getClass().getSimpleName());

        Consumer<Packet> handler = packetHandlers.get(packet.getClass());
        if (handler != null) {
            handler.accept(packet);
        } else {
            logger.debug("No handler registered for packet: " + packet.getClass().getSimpleName());
        }
    }

    /**
     * Called when the TCP connection is lost unexpectedly.
     */
    public void onDisconnect() {
        connected.set(false);
        authenticated.set(false);

        if (!reconnecting.get()) {
            logger.warn("Lost connection to NovaLink backend");
            scheduleReconnect();
        }
    }

    /**
     * Builds the handshake packet that would be sent after TCP connect.
     * Package-visible pure helper for unit tests.
     */
    HandshakePacket buildHandshakePacket() {
        String passwordHash = PasswordHasher.sha256Hex(connectionConfig.getPassword());
        String username = usernameTransformer.apply(connectionConfig.getUsername());
        return new HandshakePacket(
                NovaProtocol.PROTOCOL_VERSION,
                username,
                passwordHash,
                platformType,
                serverVersion
        );
    }

    /**
     * Pure reconnect decision for the next attempt number after increment.
     * Package-visible for unit tests (no scheduling side effects).
     */
    ReconnectPolicy.Decision evaluateReconnect(int attemptNumber) {
        return reconnectPolicy.nextAttempt(attemptNumber);
    }

    // --- private lifecycle ---

    private void scheduleReconnect() {
        if (!connectionConfig.isAutoReconnect()) {
            return;
        }
        if (reconnecting.get()) {
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        ReconnectPolicy.Decision decision = reconnectPolicy.nextAttempt(attempts);
        if (!decision.shouldRetry()) {
            logger.error(
                    "Max reconnection attempts reached. Please check backend status and use /nc reload to retry."
            );
            reconnectAttempts.set(0);
            return;
        }

        reconnecting.set(true);
        int delay = decision.delaySeconds();
        int maxAttempts = reconnectPolicy.maxAttempts();

        logger.info(
                "Reconnecting to NovaLink in " + delay + " seconds (attempt "
                        + attempts + "/" + maxAttempts + ")"
        );

        // Platform scheduler owns delay units; policy only supplies delay seconds.
        scheduler.runLater(() -> {
            reconnecting.set(false);

            EventLoopGroup group = workerGroup;
            if (group != null && !group.isShutdown()) {
                group.shutdownGracefully();
            }
            workerGroup = null;

            String host = lastHost != null ? lastHost : connectionConfig.getHost();
            int port = lastPort > 0 ? lastPort : connectionConfig.getPort();
            connect(host, port);
        }, delay);
    }

    private void sendHandshake() {
        sendPacket(buildHandshakePacket());
    }

    private void registerDefaultHandlers() {
        registerHandler(HandshakeResponsePacket.class, this::handleHandshakeResponse);
        registerHandler(KeepAlivePacket.class, this::handleKeepAlive);
    }

    private void handleHandshakeResponse(HandshakeResponsePacket response) {
        if (response.isSuccess()) {
            authenticated.set(true);
            logger.info("Successfully authenticated with NovaLink backend");
            completeAuth(true);
            return;
        }

        authenticated.set(false);
        logger.error(
                "Authentication failed: " + response.getErrorCode() + " - " + response.getMessage()
        );
        completeAuth(false);

        String code = response.getErrorCode();
        if ("NC-401".equals(code)) {
            logger.error(
                    "Please check your username and password in " + credentialsConfigFile
            );
        } else if ("NC-420".equals(code)) {
            logger.error("=================================================");
            logger.error("PROTOCOL VERSION MISMATCH!");
            logger.error(
                    "Your NovaChat plugin version is incompatible with the NovaLink backend."
            );
            logger.error(
                    "Please update your plugin to match the backend protocol version."
            );
            logger.error(
                    "Current plugin protocol version: " + NovaProtocol.PROTOCOL_VERSION
            );
            logger.error("=================================================");
        }
    }

    private void handleKeepAlive(KeepAlivePacket packet) {
        KeepAlivePacket response = new KeepAlivePacket(packet.getTimestamp());
        response.setRequestId(packet.getRequestId());
        sendPacket(response);
    }

    private void completeAuth(boolean success) {
        CompletableFuture<Boolean> future = authFuture;
        if (future != null && !future.isDone()) {
            future.complete(success);
        }
    }
}
