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
        this.currentPort = port;
        
        // Create managers
        authManager = new AuthManager();
        channelManager = new ChannelManager();
        
        // Create network handler with synchronous processing for testing
        networkHandler = new ServerNetworkHandler(1, false);
        
        // Register packet handlers
        registerPacketHandlers();
        
        // Start server
        server = new NettyServer(DEFAULT_HOST, port, 1, networkHandler);
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
        // Handshake handler
        networkHandler.registerHandler(HandshakePacket.class, new PacketHandler<HandshakePacket>() {
            @Override
            public void handle(ClientConnection connection, HandshakePacket packet) {
                // Validate protocol version first (Requirements: 27.4)
                int clientProtocolVersion = packet.getProtocolVersion();
                if (clientProtocolVersion != com.nova.chat.common.protocol.NovaProtocol.PROTOCOL_VERSION) {
                    HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                        "NC-420", 
                        String.format("Protocol version mismatch: client=%d, server=%d. Please update your client.", 
                            clientProtocolVersion, 
                            com.nova.chat.common.protocol.NovaProtocol.PROTOCOL_VERSION)
                    );
                    response.setRequestId(packet.getRequestId());
                    connection.sendPacket(response);
                    return;
                }

                com.nova.link.auth.AuthResult authResult = authManager.authenticate(
                    packet.getClientId(), 
                    packet.getPasswordHash(),
                    connection.getRemoteAddress()
                );
                
                HandshakeResponsePacket response;
                if (authResult.isSuccess()) {
                    connection.setAuthenticated(true);
                    connection.setClientId(packet.getClientId());
                    response = HandshakeResponsePacket.success("Authentication successful");
                } else {
                    response = HandshakeResponsePacket.failure("NC-401", "Invalid credentials");
                }
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response);
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
         * Authenticates with the server.
         */
        public CompletableFuture<HandshakeResponsePacket> authenticate(String clientId, String password) {
            authFuture = new CompletableFuture<>();
            
            HandshakePacket handshake = new HandshakePacket(
                NovaProtocol.PROTOCOL_VERSION,
                clientId,
                hashPassword(password),
                platform
            );
            sendPacket(handshake);
            
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
