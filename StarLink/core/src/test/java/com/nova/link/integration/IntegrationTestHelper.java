package com.nova.link.integration;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.codec.PacketDecoder;
import com.nova.chat.common.protocol.codec.PacketEncoder;
import com.nova.chat.common.protocol.codec.Varint21FrameDecoder;
import com.nova.chat.common.protocol.codec.Varint21LengthFieldPrepender;
import com.nova.chat.common.protocol.packets.*;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.ClientCredentials;
import com.nova.link.auth.NonceCache;
import com.nova.link.channel.ChannelManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.NettyServer;
import com.nova.link.network.PacketHandler;
import com.nova.link.network.ServerNetworkHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Helper class for integration tests between NovaChat plugins and NovaLink backend.
 * Provides utilities for starting test servers and simulating client connections.
 * 
 * Requirements: 23.1-23.9 - Plugin and backend integration testing
 */
public class IntegrationTestHelper {

    private static final int DEFAULT_PORT = 18888;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int TIMEOUT_SECONDS = 10;

    private NettyServer server;
    private ServerNetworkHandler networkHandler;
    private AuthManager authManager;
    private ChannelManager channelManager;
    private NonceCache nonceCache;
    private final List<TestClient> clients = new ArrayList<>();
    private int currentPort = DEFAULT_PORT;

    /**
     * Starts a test NovaLink server with default configuration.
     */
    public void startServer() throws Exception {
        startServer(DEFAULT_PORT);
    }

    /**
     * Starts a test NovaLink server on the specified port.
     */
    public void startServer(int port) throws Exception {
        startServer(port, null);
    }

    /**
     * Starts a test NovaLink server on the specified port with optional TLS
     * (AUTH-002). When {@code tls} is non-null and configured, the underlying
     * {@link NettyServer} prepends an {@code SslHandler} at the HEAD of every
     * accepted channel so the 3-packet challenge-response handshake runs inside
     * an encrypted channel.
     *
     * @param port the port to listen on
     * @param tls  optional TLS configuration; {@code null} for plaintext
     */
    public void startServer(int port, com.nova.link.config.TlsConfig tls) throws Exception {
        this.currentPort = port;

        // Create managers
        authManager = new AuthManager();
        channelManager = new ChannelManager();

        // AUTH-002: pending-challenge cache for the 3-packet challenge-response
        // handshake. One per test server; entries self-expire (30s TTL).
        nonceCache = new NonceCache();

        // Create network handler with synchronous processing for testing
        networkHandler = new ServerNetworkHandler(1, false);

        // Register packet handlers
        registerPacketHandlers();

        // Start server. The 6-arg constructor wires the optional TlsContext;
        // passing null keeps the legacy plaintext path identical to the old
        // 4-arg overload (idle timeout = LEGACY_IDLE_TIMEOUT_SECONDS = 90s).
        server = new NettyServer(DEFAULT_HOST, port, 1, 90, networkHandler, tls);
        server.start().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the test server and all clients.
     */
    public void stopServer() throws Exception {
        // Close all clients
        for (TestClient client : clients) {
            client.disconnect();
        }
        clients.clear();
        
        // Shutdown network handler
        if (networkHandler != null) {
            networkHandler.shutdown();
        }
        
        // Stop server
        if (server != null) {
            server.shutdown().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Registers a test client with the server.
     */
    public void registerClient(String clientId, String password) {
        ClientCredentials credentials = new ClientCredentials(clientId, hashPassword(password));
        authManager.registerClient(credentials);
    }

    /**
     * Creates a test client that simulates a NovaChat plugin connection.
     */
    public TestClient createClient(PlatformType platform) {
        TestClient client = new TestClient(DEFAULT_HOST, currentPort, platform);
        clients.add(client);
        return client;
    }

    /**
     * Gets the auth manager for test configuration.
     */
    public AuthManager getAuthManager() {
        return authManager;
    }

    /**
     * Gets the channel manager for test configuration.
     */
    public ChannelManager getChannelManager() {
        return channelManager;
    }

    /**
     * Gets the network handler for test verification.
     */
    public ServerNetworkHandler getNetworkHandler() {
        return networkHandler;
    }

    private void registerPacketHandlers() {
        // ==================== AUTH-002 challenge-response handshake ====================
        // 3-packet dance: HandshakeInit (0x15) → HandshakeChallenge (0x16) →
        // HandshakeAuthenticate (0x17). The legacy 0x01 handler is kept below
        // for compile compatibility; live clients use the new path.
        networkHandler.registerHandler(HandshakeInitPacket.class, new PacketHandler<HandshakeInitPacket>() {
            @Override
            public void handle(ClientConnection connection, HandshakeInitPacket packet) {
                int clientProtocolVersion = packet.getProtocolVersion();
                if (clientProtocolVersion != NovaProtocol.PROTOCOL_VERSION) {
                    HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                        "NC-420",
                        String.format("Protocol version mismatch: client=%d, server=%d. Please update your client.",
                            clientProtocolVersion, NovaProtocol.PROTOCOL_VERSION)
                    );
                    response.setRequestId(packet.getRequestId());
                    sendResponseAndClose(connection, response);
                    return;
                }

                String clientId = packet.getClientId();
                String clientNonce = packet.getClientNonce();
                if (clientId == null || clientId.isEmpty() || clientNonce == null || clientNonce.isEmpty()) {
                    HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                        "NC-401", "Invalid handshake init");
                    response.setRequestId(packet.getRequestId());
                    sendResponseAndClose(connection, response);
                    return;
                }

                connection.setPlatform(packet.getPlatform());
                connection.setServerVersion(packet.getServerVersion());

                String serverNonce = generateNonceHex();
                nonceCache.put(clientId, clientNonce, serverNonce);

                HandshakeChallengePacket challenge = new HandshakeChallengePacket(serverNonce);
                challenge.setRequestId(packet.getRequestId());
                connection.sendPacket(challenge);
            }
        });

        networkHandler.registerHandler(HandshakeAuthenticatePacket.class, new PacketHandler<HandshakeAuthenticatePacket>() {
            @Override
            public void handle(ClientConnection connection, HandshakeAuthenticatePacket packet) {
                com.nova.link.auth.AuthResult authResult = authManager.authenticateChallenge(
                    packet.getClientId(),
                    packet.getClientNonce(),
                    packet.getHmac(),
                    nonceCache,
                    connection.getRemoteAddress()
                );

                HandshakeResponsePacket response;
                if (authResult.isSuccess()) {
                    if (!networkHandler.activateAuthenticated(connection, packet.getClientId())) {
                        return;
                    }
                    response = HandshakeResponsePacket.success("Authentication successful");
                } else {
                    response = HandshakeResponsePacket.failure(
                        authResult.getErrorCode() != null ? authResult.getErrorCode() : "NC-401",
                        authResult.getMessage() != null ? authResult.getMessage() : "Invalid credentials"
                    );
                }
                response.setRequestId(packet.getRequestId());
                if (authResult.isSuccess()) {
                    connection.sendPacket(response);
                } else {
                    sendResponseAndClose(connection, response);
                }
            }
        });

        // ==================== Legacy handshake handler (0x01) ====================
        // Kept for compile compatibility; live clients use the challenge-response
        // path above. Old v2 clients that still send 0x01 are rejected with NC-420.
        networkHandler.registerHandler(HandshakePacket.class, new PacketHandler<HandshakePacket>() {
            @Override
            public void handle(ClientConnection connection, HandshakePacket packet) {
                int clientProtocolVersion = packet.getProtocolVersion();
                if (clientProtocolVersion != NovaProtocol.PROTOCOL_VERSION) {
                    HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                        "NC-420",
                        String.format("Protocol version mismatch: client=%d, server=%d. Please update your client.",
                            clientProtocolVersion, NovaProtocol.PROTOCOL_VERSION)
                    );
                    response.setRequestId(packet.getRequestId());
                    sendResponseAndClose(connection, response);
                    return;
                }

                HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                    "NC-420",
                    "Legacy handshake is no longer supported. Please update your client to use the challenge-response handshake."
                );
                response.setRequestId(packet.getRequestId());
                sendResponseAndClose(connection, response);
            }
        });

        // Chat message handler
        networkHandler.registerHandler(ChatMessagePacket.class, new PacketHandler<ChatMessagePacket>() {
            @Override
            public void handle(ClientConnection connection, ChatMessagePacket packet) {
                if (!connection.isAuthenticated()) {
                    return;
                }
                // Broadcast to all authenticated clients
                networkHandler.broadcastAuthenticated(packet);
            }
        });

        // Channel action handler
        networkHandler.registerHandler(ChannelActionPacket.class, new PacketHandler<ChannelActionPacket>() {
            @Override
            public void handle(ClientConnection connection, ChannelActionPacket packet) {
                if (!connection.isAuthenticated()) {
                    ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                        false, packet.getAction(), packet.getChannelId(),
                        "NC-401", "Not authenticated"
                    );
                    response.setRequestId(packet.getRequestId());
                    connection.sendPacket(response);
                    return;
                }

                ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                    true, packet.getAction(), packet.getChannelId(),
                    "", "Action completed"
                );
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response);
            }
        });

        // Keep-alive handler
        networkHandler.registerHandler(KeepAlivePacket.class, new PacketHandler<KeepAlivePacket>() {
            @Override
            public void handle(ClientConnection connection, KeepAlivePacket packet) {
                KeepAlivePacket response = new KeepAlivePacket(packet.getTimestamp());
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response);
            }
        });
    }

    /**
     * Hashes a password using SHA-256.
     */
    private static void sendResponseAndClose(ClientConnection connection, HandshakeResponsePacket response) {
        connection.sendPacket(response).whenComplete((ignored, error) -> connection.close());
    }

    /**
     * Generates a fresh server nonce for the AUTH-002 challenge-response
     * handshake: 16 cryptographically-random bytes, lowercase-hex-encoded
     * (32 characters). Also used for the client nonce in the test client.
     */
    private static String generateNonceHex() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) {
            String h = Integer.toHexString(0xff & b);
            if (h.length() == 1) {
                hex.append('0');
            }
            hex.append(h);
        }
        return hex.toString();
    }

    /**
     * Computes the AUTH-002 challenge-response HMAC.
     * {@code key = utf8(passwordHash)}, {@code message = utf8(serverNonce + clientNonce)},
     * output is lowercase-hex HMAC-SHA-256. Mirrors the production
     * {@code AuthManager.computeChallengeHmac} / client {@code ChallengeHmac.compute}
     * so the test client produces the exact bytes the server expects.
     */
    private static String computeChallengeHmac(String passwordHash, String serverNonce, String clientNonce) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    passwordHash.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal((serverNonce + clientNonce).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hmacBytes.length * 2);
            for (byte b : hmacBytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new RuntimeException("Failed to compute challenge HMAC", e);
        }
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Test client that simulates a NovaChat plugin connection.
     */
    public static class TestClient {
        private final String host;
        private final int port;
        private final PlatformType platform;
        private final PacketRegistry packetRegistry;
        /** AUTH-002: optional client SslContext; {@code null} = plaintext. */
        private io.netty.handler.ssl.SslContext sslContext;

        private EventLoopGroup workerGroup;
        private Channel channel;
        private volatile boolean connected = false;
        private volatile boolean authenticated = false;

        private final Map<Class<? extends Packet>, Consumer<Packet>> handlers = new ConcurrentHashMap<>();
        private final BlockingQueue<Packet> receivedPackets = new LinkedBlockingQueue<>();
        private CompletableFuture<HandshakeResponsePacket> authFuture;

        public TestClient(String host, int port, PlatformType platform) {
            this.host = host;
            this.port = port;
            this.platform = platform;
            this.packetRegistry = NovaProtocol.createRegistry();
        }

        /**
         * AUTH-002: installs an SslContext so the next {@link #connect()}
         * prepends an {@code SslHandler} at the pipeline HEAD (mirrors
         * {@code CoreNetworkClient.buildClientSslContext}). When {@code null},
         * the client connects in plaintext.
         *
         * @param sslContext the client SSL context, or {@code null} for plaintext
         */
        public void setSslContext(io.netty.handler.ssl.SslContext sslContext) {
            this.sslContext = sslContext;
        }

        /**
         * Connects to the server.
         */
        public CompletableFuture<Boolean> connect() {
            CompletableFuture<Boolean> future = new CompletableFuture<>();

            workerGroup = new NioEventLoopGroup();
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // AUTH-002: TLS is the outermost transport. When configured,
                        // the SslHandler must sit at the HEAD so the frame decoder
                        // and packet codec see decrypted bytes. The host/port are
                        // passed to newHandler so SNI / endpoint identification can
                        // be applied by the SSL engine.
                        if (sslContext != null) {
                            pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(), host, port));
                        }
                        pipeline.addLast("frameDecoder", new Varint21FrameDecoder());
                        pipeline.addLast("framePrepender", new Varint21LengthFieldPrepender());
                        pipeline.addLast("packetDecoder", new PacketDecoder(packetRegistry));
                        pipeline.addLast("packetEncoder", new PacketEncoder(packetRegistry));
                        pipeline.addLast("handler", new TestClientHandler(TestClient.this));
                    }
                });

            bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    channel = f.channel();
                    connected = true;
                    future.complete(true);
                } else {
                    future.complete(false);
                }
            });

            return future;
        }

        /**
         * Authenticates with the server via the AUTH-002 3-packet
         * challenge-response handshake:
         * <ol>
         *   <li>send {@link HandshakeInitPacket} with a fresh client nonce,</li>
         *   <li>await {@link HandshakeChallengePacket} (registered below),</li>
         *   <li>compute {@code HMAC-SHA256(sha256(password), serverNonce+clientNonce)}
         *       and send {@link HandshakeAuthenticatePacket},</li>
         *   <li>await {@link HandshakeResponsePacket} (completes the returned future).</li>
         * </ol>
         */
        public CompletableFuture<HandshakeResponsePacket> authenticate(String clientId, String password) {
            authFuture = new CompletableFuture<>();

            final String clientNonce = generateNonceHex();
            final String passwordHash = hashPassword(password);

            // One-shot challenge handler: when the server nonce arrives, compute
            // the HMAC and send the authenticate packet. Registered before the
            // init is sent so the challenge cannot arrive before we listen.
            registerHandler(HandshakeChallengePacket.class, (HandshakeChallengePacket challenge) -> {
                String serverNonce = challenge.getServerNonce();
                String hmac = computeChallengeHmac(passwordHash, serverNonce, clientNonce);
                HandshakeAuthenticatePacket auth = new HandshakeAuthenticatePacket(
                        clientId, clientNonce, hmac);
                sendPacket(auth);
            });

            HandshakeInitPacket init = new HandshakeInitPacket(
                    NovaProtocol.PROTOCOL_VERSION,
                    clientId,
                    platform,
                    "",
                    clientNonce
            );
            sendPacket(init);

            return authFuture;
        }

        /**
         * Sends a packet to the server.
         */
        public void sendPacket(Packet packet) {
            if (channel != null && channel.isActive()) {
                channel.writeAndFlush(packet);
            }
        }

        /**
         * Waits for a specific packet type.
         */
        @SuppressWarnings("unchecked")
        public <T extends Packet> T waitForPacket(Class<T> packetClass, long timeout, TimeUnit unit) 
                throws InterruptedException, TimeoutException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            
            while (System.currentTimeMillis() < deadline) {
                Packet packet = receivedPackets.poll(100, TimeUnit.MILLISECONDS);
                if (packet != null && packetClass.isInstance(packet)) {
                    return (T) packet;
                } else if (packet != null) {
                    // Put back non-matching packets
                    receivedPackets.offer(packet);
                }
            }
            throw new TimeoutException("Timeout waiting for packet: " + packetClass.getSimpleName());
        }

        /**
         * Disconnects from the server.
         */
        public void disconnect() {
            connected = false;
            authenticated = false;
            
            if (channel != null && channel.isActive()) {
                channel.close().syncUninterruptibly();
            }
            
            if (workerGroup != null && !workerGroup.isShutdown()) {
                workerGroup.shutdownGracefully();
            }
        }

        void handlePacket(Packet packet) {
            receivedPackets.offer(packet);
            
            if (packet instanceof HandshakeResponsePacket response) {
                authenticated = response.isSuccess();
                if (authFuture != null && !authFuture.isDone()) {
                    authFuture.complete(response);
                }
            }
            
            Consumer<Packet> handler = handlers.get(packet.getClass());
            if (handler != null) {
                handler.accept(packet);
            }
        }

        public boolean isConnected() {
            return connected && channel != null && channel.isActive();
        }

        public boolean isAuthenticated() {
            return authenticated;
        }

        public PlatformType getPlatform() {
            return platform;
        }

        public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
            handlers.put(packetClass, (Consumer<Packet>) handler);
        }
    }

    /**
     * Netty handler for test client.
     */
    private static class TestClientHandler extends SimpleChannelInboundHandler<Packet> {
        private final TestClient client;

        public TestClientHandler(TestClient client) {
            this.client = client;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
            client.handlePacket(packet);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
