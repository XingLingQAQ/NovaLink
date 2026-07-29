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
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Multi-client simulator for integration testing.
 * Allows creating and managing multiple simulated NovaChat clients
 * to test concurrent connections and message routing.
 * 
 * Requirements: 24.2 - Enable simulating multiple client connections
 */
public class MultiClientSimulator {

    private static final Logger logger = LoggerFactory.getLogger(MultiClientSimulator.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private final String serverHost;
    private final int serverPort;
    private final List<SimulatedClient> clients = new CopyOnWriteArrayList<>();
    private final EventLoopGroup sharedWorkerGroup;

    /**
     * Creates a multi-client simulator.
     * 
     * @param serverHost the server host to connect to
     * @param serverPort the server port to connect to
     */
    public MultiClientSimulator(String serverHost, int serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.sharedWorkerGroup = new NioEventLoopGroup(4);
    }

    /**
     * Creates a new simulated client.
     * 
     * @param clientId the client ID
     * @param platform the platform type
     * @return the created client
     */
    public SimulatedClient createClient(String clientId, PlatformType platform) {
        SimulatedClient client = new SimulatedClient(
            serverHost, serverPort, clientId, platform, sharedWorkerGroup
        );
        clients.add(client);
        return client;
    }

    /**
     * Creates multiple clients with the same platform type.
     * 
     * @param count the number of clients to create
     * @param platform the platform type
     * @param clientIdPrefix the prefix for client IDs
     * @return list of created clients
     */
    public List<SimulatedClient> createClients(int count, PlatformType platform, String clientIdPrefix) {
        List<SimulatedClient> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SimulatedClient client = createClient(clientIdPrefix + "_" + i, platform);
            created.add(client);
        }
        return created;
    }

    /**
     * Connects all clients.
     * 
     * @return CompletableFuture that completes when all clients are connected
     */
    public CompletableFuture<Void> connectAll() {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (SimulatedClient client : clients) {
            futures.add(client.connect());
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Authenticates all clients with their respective passwords.
     * 
     * @param passwordProvider function that provides password for each client ID
     * @return CompletableFuture that completes when all clients are authenticated
     */
    public CompletableFuture<List<HandshakeResponsePacket>> authenticateAll(
            java.util.function.Function<String, String> passwordProvider) {
        List<CompletableFuture<HandshakeResponsePacket>> futures = new ArrayList<>();
        for (SimulatedClient client : clients) {
            String password = passwordProvider.apply(client.getClientId());
            futures.add(client.authenticate(password));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                List<HandshakeResponsePacket> responses = new ArrayList<>();
                for (CompletableFuture<HandshakeResponsePacket> future : futures) {
                    try {
                        responses.add(future.get());
                    } catch (Exception e) {
                        responses.add(null);
                    }
                }
                return responses;
            });
    }

    /**
     * Disconnects all clients.
     */
    public void disconnectAll() {
        for (SimulatedClient client : clients) {
            client.disconnect();
        }
    }

    /**
     * Shuts down the simulator and releases resources.
     */
    public void shutdown() {
        disconnectAll();
        clients.clear();
        if (!sharedWorkerGroup.isShutdown()) {
            sharedWorkerGroup.shutdownGracefully();
        }
    }

    /**
     * Gets all clients.
     */
    public List<SimulatedClient> getClients() {
        return Collections.unmodifiableList(clients);
    }

    /**
     * Gets the number of connected clients.
     */
    public int getConnectedCount() {
        return (int) clients.stream().filter(SimulatedClient::isConnected).count();
    }

    /**
     * Gets the number of authenticated clients.
     */
    public int getAuthenticatedCount() {
        return (int) clients.stream().filter(SimulatedClient::isAuthenticated).count();
    }

    /**
     * Simulated NovaChat client for testing.
     */
    public static class SimulatedClient {
        private final String host;
        private final int port;
        private final String clientId;
        private final PlatformType platform;
        private final PacketRegistry packetRegistry;
        private final EventLoopGroup workerGroup;

        private Channel channel;
        private volatile boolean connected = false;
        private volatile boolean authenticated = false;

        private final BlockingQueue<Packet> receivedPackets = new LinkedBlockingQueue<>();
        private final Map<Class<? extends Packet>, Consumer<Packet>> handlers = new ConcurrentHashMap<>();
        private CompletableFuture<HandshakeResponsePacket> authFuture;

        public SimulatedClient(String host, int port, String clientId, 
                              PlatformType platform, EventLoopGroup workerGroup) {
            this.host = host;
            this.port = port;
            this.clientId = clientId;
            this.platform = platform;
            this.workerGroup = workerGroup;
            this.packetRegistry = NovaProtocol.createRegistry();
        }

        /**
         * Connects to the server.
         */
        public CompletableFuture<Boolean> connect() {
            CompletableFuture<Boolean> future = new CompletableFuture<>();

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
                        pipeline.addLast("handler", new ClientHandler(SimulatedClient.this));
                    }
                });

            bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    channel = f.channel();
                    connected = true;
                    logger.debug("Client {} connected to {}:{}", clientId, host, port);
                    future.complete(true);
                } else {
                    logger.error("Client {} failed to connect", clientId, f.cause());
                    future.complete(false);
                }
            });

            return future;
        }

        /**
         * Authenticates with the server.
         */
        public CompletableFuture<HandshakeResponsePacket> authenticate(String password) {
            authFuture = new CompletableFuture<>();

            HandshakePacket handshake = new HandshakePacket(
                NovaProtocol.PROTOCOL_VERSION,
                clientId,
                EmbeddedNovaLinkServer.hashPassword(password),
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
         * Sends a chat message.
         */
        public void sendChatMessage(UUID playerId, String playerName, String channelId, String content) {
            ChatMessagePacket message = new ChatMessagePacket(
                playerId, playerName, clientId, channelId, content
            );
            sendPacket(message);
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
                    receivedPackets.offer(packet);
                }
            }
            throw new TimeoutException("Timeout waiting for packet: " + packetClass.getSimpleName());
        }

        /**
         * Waits for any packet.
         */
        public Packet waitForAnyPacket(long timeout, TimeUnit unit)
                throws InterruptedException, TimeoutException {
            Packet packet = receivedPackets.poll(timeout, unit);
            if (packet == null) {
                throw new TimeoutException("Timeout waiting for any packet");
            }
            return packet;
        }

        /**
         * Clears received packets queue.
         */
        public void clearReceivedPackets() {
            receivedPackets.clear();
        }

        /**
         * Gets all received packets without waiting.
         */
        public List<Packet> drainReceivedPackets() {
            List<Packet> packets = new ArrayList<>();
            receivedPackets.drainTo(packets);
            return packets;
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

        public String getClientId() {
            return clientId;
        }

        public PlatformType getPlatform() {
            return platform;
        }

        public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
            handlers.put(packetClass, (Consumer<Packet>) handler);
        }
    }

    /**
     * Netty handler for simulated client.
     */
    private static class ClientHandler extends SimpleChannelInboundHandler<Packet> {
        private final SimulatedClient client;

        public ClientHandler(SimulatedClient client) {
            this.client = client;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
            client.handlePacket(packet);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Client {} error", client.getClientId(), cause);
            ctx.close();
        }
    }
}
