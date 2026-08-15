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
import java.util.function.Supplier;

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

    /**
     * Set by {@link #disconnect()} and cleared by an explicit {@link #connect(String, int)}.
     * A reconnect callback scheduled before the shutdown checks this flag and returns
     * without connecting, so an intentional shutdown cannot be "revived" by a timer.
     */
    private volatile boolean shutdown;

    /**
     * Guards the connect flow: only the thread that wins the CAS may create the
     * {@link EventLoopGroup} and start the bootstrap. Losers join the in-flight
     * attempt via {@link #authFuture} instead of leaking a second group.
     */
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    /** Test hook: lets unit tests count/stub event loop group creation. */
    private volatile Supplier<EventLoopGroup> eventLoopGroupFactory = NioEventLoopGroup::new;

    private final Map<Class<? extends Packet>, Consumer<Packet>> packetHandlers =
            new ConcurrentHashMap<>();

    /**
     * In-flight authentication future. Initialized atomically via CAS so that
     * a concurrent caller that loses the connect race joins the winner's
     * future instead of overwriting it with its own (never-completed) future.
     */
    private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>> authFuture =
            new java.util.concurrent.atomic.AtomicReference<>();
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
     *
     * <p>An explicit call re-arms the client after a previous {@link #disconnect()}
     * (clears the shutdown flag). Thread-safe: if a connect flow is already in
     * flight, concurrent callers do not start a second flow (which would leak the
     * first {@link EventLoopGroup}); they receive the in-flight attempt's future
     * instead.
     */
    public CompletableFuture<Boolean> connect(String host, int port) {
        shutdown = false;
        return doConnect(host, port);
    }

    /**
     * Connect flow shared by the public API and the reconnect timer. Unlike
     * {@link #connect(String, int)}, does not clear the shutdown flag, so a
     * reconnect callback firing after {@link #disconnect()} stays down.
     */
    private CompletableFuture<Boolean> doConnect(String host, int port) {
        if (shutdown) {
            return CompletableFuture.completedFuture(false);
        }
        if (connected.get()) {
            return CompletableFuture.completedFuture(true);
        }

        Objects.requireNonNull(host, "host");

        // Atomically claim the authFuture slot. The winner proceeds to build
        // the connection; losers return the winner's future instead of
        // overwriting it (which would leave the winner's future never completed
        // and the loser returning its own never-completed future).
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        CompletableFuture<Boolean> existing = authFuture.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        if (!authFuture.compareAndSet(null, future) && !authFuture.compareAndSet(existing, future)) {
            // Another thread won the CAS between our read and write; join it.
            CompletableFuture<Boolean> winner = authFuture.get();
            return winner != null ? winner : future;
        }

        if (!connecting.compareAndSet(false, true)) {
            // Another connect flow is in flight; join it instead of starting
            // a second one (which would leak a second EventLoopGroup).
            // Clear our authFuture claim so the winner's stays authoritative.
            authFuture.compareAndSet(future, null);
            CompletableFuture<Boolean> inFlight = authFuture.get();
            return inFlight != null ? inFlight : future;
        }

        this.lastHost = host;
        this.lastPort = port;

        try {
            workerGroup = eventLoopGroupFactory.get();

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

            bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    if (shutdown) {
                        // disconnect() happened while the TCP dial was in flight.
                        f.channel().close();
                        connecting.set(false);
                        completeAuth(false);
                        // If workerGroup was assigned after disconnect() read it
                        // (null), it would leak. Close it here so the event-loop
                        // threads are always released.
                        EventLoopGroup group = workerGroup;
                        if (group != null && !group.isShutdown()) {
                            group.shutdownGracefully();
                        }
                        workerGroup = null;
                        return;
                    }
                    channel = f.channel();
                    connected.set(true);
                    connecting.set(false);
                    reconnectAttempts.set(0);
                    logger.debug("TCP connection established, sending handshake...");
                    sendHandshake();
                } else {
                    String msg = f.cause() != null ? f.cause().getMessage() : "unknown";
                    logger.warn("Failed to connect to NovaLink: " + msg);
                    connecting.set(false);
                    completeAuth(false);
                    if (shutdown) {
                        // disconnect() happened while the TCP dial was in flight.
                        EventLoopGroup group = workerGroup;
                        if (group != null && !group.isShutdown()) {
                            group.shutdownGracefully();
                        }
                        workerGroup = null;
                        return;
                    }
                    scheduleReconnect();
                }
            });
        } catch (RuntimeException e) {
            connecting.set(false);
            throw e;
        }

        return future;
    }

    /**
     * Connects using host/port from {@link ClientConnectionConfig}.
     */
    public CompletableFuture<Boolean> connect() {
        return connect(connectionConfig.getHost(), connectionConfig.getPort());
    }

    /**
     * Explicit shutdown; cancels reconnect for this session and does not reschedule.
     * A reconnect callback already handed to the {@link SchedulerBridge} will still
     * fire, but it observes the shutdown flag and returns without connecting.
     * A later explicit {@link #connect(String, int)} re-arms the client.
     */
    public void disconnect() {
        shutdown = true;
        reconnecting.set(false);
        authenticated.set(false);

        // Release any caller blocked in connect() before nulling the channel,
        // so a handshake-time shutdown cannot leave authFuture incomplete.
        completeAuth(false);

        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            // Async close: never block on the event loop. If disconnect() is
            // invoked from a Netty handler, syncUninterruptibly() would deadlock
            // waiting for a close future that can only complete on this thread.
            ch.close().addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    logger.warn("Error closing channel: " + future.cause().getMessage());
                }
            });
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

    /** Test hook: replaces how {@link #doConnect} creates its {@link EventLoopGroup}. */
    void setEventLoopGroupFactory(Supplier<EventLoopGroup> factory) {
        this.eventLoopGroupFactory = Objects.requireNonNull(factory, "factory");
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
            try {
                handler.accept(packet);
            } catch (Throwable t) {
                // A faulty platform handler must not tear down the connection:
                // an escaping exception would reach exceptionCaught -> ctx.close()
                // and put the client into a receive->crash->reconnect loop.
                logger.error(
                        "Packet handler for " + packet.getClass().getSimpleName()
                                + " threw " + t.getClass().getName() + ": " + t.getMessage()
                );
            }
        } else {
            logger.debug("No handler registered for packet: " + packet.getClass().getSimpleName());
        }
    }

    /**
     * Called when the TCP connection is lost unexpectedly.
     *
     * <p>Completes any in-flight {@code authFuture} with {@code false} so a caller
     * blocked in {@link #connect(String, int)} is released if the connection
     * dropped after TCP connect but before {@code HandshakeResponsePacket} arrived.
     * {@link #completeAuth(boolean)} is idempotent, so this is safe even if the
     * future was already completed.
     */
    public void onDisconnect() {
        connected.set(false);
        authenticated.set(false);
        completeAuth(false);

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
        if (shutdown) {
            return;
        }
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

            if (shutdown) {
                // disconnect() was called after this task was scheduled; stay down.
                return;
            }

            EventLoopGroup group = workerGroup;
            if (group != null && !group.isShutdown()) {
                group.shutdownGracefully();
            }
            workerGroup = null;

            String host = lastHost != null ? lastHost : connectionConfig.getHost();
            int port = lastPort > 0 ? lastPort : connectionConfig.getPort();
            doConnect(host, port);
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
        CompletableFuture<Boolean> future = authFuture.get();
        if (future != null && !future.isDone()) {
            future.complete(success);
        }
    }
}
