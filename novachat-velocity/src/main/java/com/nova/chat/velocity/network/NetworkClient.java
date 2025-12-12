package com.nova.chat.velocity.network;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.codec.PacketDecoder;
import com.nova.chat.common.protocol.codec.PacketEncoder;
import com.nova.chat.common.protocol.codec.Varint21FrameDecoder;
import com.nova.chat.common.protocol.codec.Varint21LengthFieldPrepender;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Netty-based network client for connecting to NovaLink backend.
 * Implements automatic reconnection logic and packet handling.
 * 
 * Requirements: 1.1, 1.4
 */
public class NetworkClient {

    private final NovaChatVelocity plugin;
    private final NovaChatConfig config;
    private final PacketRegistry packetRegistry;
    
    private EventLoopGroup workerGroup;
    private Channel channel;
    
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int MAX_RECONNECT_DELAY = 30; // seconds
    
    /** Packet handlers by packet class */
    private final Map<Class<? extends Packet>, Consumer<Packet>> packetHandlers = new ConcurrentHashMap<>();
    
    /** Pending authentication future */
    private CompletableFuture<Boolean> authFuture;

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public NetworkClient(NovaChatVelocity plugin, NovaChatConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.packetRegistry = NovaProtocol.createRegistry();
        
        // Register default packet handlers
        registerDefaultHandlers();
    }

    /**
     * Connects to the NovaLink backend.
     *
     * @param host the backend host
     * @param port the backend port
     * @return a future that completes with true if connection and authentication succeed
     */
    public CompletableFuture<Boolean> connect(String host, int port) {
        if (connected.get()) {
            return CompletableFuture.completedFuture(true);
        }
        
        authFuture = new CompletableFuture<>();
        
        workerGroup = new NioEventLoopGroup();
        
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    
                    // Frame codecs
                    pipeline.addLast("frameDecoder", new Varint21FrameDecoder());
                    pipeline.addLast("framePrepender", new Varint21LengthFieldPrepender());
                    
                    // Packet codecs
                    pipeline.addLast("packetDecoder", new PacketDecoder(packetRegistry));
                    pipeline.addLast("packetEncoder", new PacketEncoder(packetRegistry));
                    
                    // Handler
                    pipeline.addLast("handler", new ClientChannelHandler(NetworkClient.this));
                }
            });
        
        plugin.debug("Connecting to NovaLink backend at " + host + ":" + port);
        
        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                channel = future.channel();
                connected.set(true);
                reconnectAttempts.set(0);
                plugin.debug("TCP connection established, sending handshake...");
                
                // Send handshake packet
                sendHandshake();
            } else {
                plugin.getLogger().warn("Failed to connect to NovaLink: " + future.cause().getMessage());
                authFuture.complete(false);
                scheduleReconnect();
            }
        });
        
        return authFuture;
    }

    /**
     * Disconnects from the backend.
     */
    public void disconnect() {
        reconnecting.set(false);
        authenticated.set(false);
        
        if (channel != null && channel.isActive()) {
            channel.close().syncUninterruptibly();
            channel = null;
        }
        
        connected.set(false);
        
        if (workerGroup != null && !workerGroup.isShutdown()) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        
        plugin.debug("Disconnected from NovaLink backend");
    }

    /**
     * Sends a packet to the backend.
     *
     * @param packet the packet to send
     */
    public void sendPacket(Packet packet) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(packet);
            plugin.debug("Sent packet: " + packet.getClass().getSimpleName());
        } else {
            plugin.debug("Cannot send packet: not connected");
        }
    }

    /**
     * Registers a packet handler.
     *
     * @param packetClass the packet class to handle
     * @param handler the handler function
     * @param <T> the packet type
     */
    @SuppressWarnings("unchecked")
    public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
        packetHandlers.put(packetClass, (Consumer<Packet>) handler);
    }

    /**
     * Handles an incoming packet.
     *
     * @param packet the received packet
     */
    void handlePacket(Packet packet) {
        plugin.debug("Received packet: " + packet.getClass().getSimpleName());
        
        Consumer<Packet> handler = packetHandlers.get(packet.getClass());
        if (handler != null) {
            handler.accept(packet);
        } else {
            plugin.debug("No handler registered for packet: " + packet.getClass().getSimpleName());
        }
    }

    /**
     * Called when the connection is lost.
     */
    void onDisconnect() {
        connected.set(false);
        authenticated.set(false);
        
        if (!reconnecting.get()) {
            plugin.getLogger().warn("Lost connection to NovaLink backend");
            scheduleReconnect();
        }
    }

    /**
     * Schedules a reconnection attempt with exponential backoff.
     */
    private void scheduleReconnect() {
        if (reconnecting.get()) {
            return;
        }
        
        int attempts = reconnectAttempts.incrementAndGet();
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            plugin.getLogger().error("Max reconnection attempts reached. Please check backend status and use /nc reload to retry.");
            reconnectAttempts.set(0);
            return;
        }
        
        reconnecting.set(true);
        
        // Exponential backoff: 1s, 2s, 4s, 8s, ... up to MAX_RECONNECT_DELAY
        int delay = Math.min((int) Math.pow(2, attempts - 1), MAX_RECONNECT_DELAY);
        
        plugin.getLogger().info("Reconnecting to NovaLink in " + delay + " seconds (attempt " + attempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
        
        // Schedule reconnection using Velocity's scheduler
        plugin.getServer().getScheduler()
            .buildTask(plugin, () -> {
                reconnecting.set(false);
                
                // Cleanup old resources
                if (workerGroup != null && !workerGroup.isShutdown()) {
                    workerGroup.shutdownGracefully();
                }
                
                // Attempt reconnection
                connect(config.getBackendHost(), config.getBackendPort());
            })
            .delay(delay, TimeUnit.SECONDS)
            .schedule();
    }

    /**
     * Sends the handshake packet for authentication.
     */
    private void sendHandshake() {
        String passwordHash = hashPassword(config.getPassword());
        
        HandshakePacket handshake = new HandshakePacket(
            NovaProtocol.PROTOCOL_VERSION,
            config.getUsername(),
            passwordHash,
            PlatformType.VELOCITY
        );
        
        sendPacket(handshake);
    }

    /**
     * Registers default packet handlers.
     */
    private void registerDefaultHandlers() {
        // Handle handshake response
        registerHandler(HandshakeResponsePacket.class, this::handleHandshakeResponse);
        
        // Handle keep-alive
        registerHandler(KeepAlivePacket.class, this::handleKeepAlive);
    }

    /**
     * Handles the handshake response packet.
     * Requirements: 27.3 - Outputs clear error message when version is incompatible
     */
    private void handleHandshakeResponse(HandshakeResponsePacket response) {
        if (response.isSuccess()) {
            authenticated.set(true);
            plugin.getLogger().info("Successfully authenticated with NovaLink backend");
            
            if (authFuture != null && !authFuture.isDone()) {
                authFuture.complete(true);
            }
        } else {
            authenticated.set(false);
            plugin.getLogger().error("Authentication failed: " + response.getErrorCode() + " - " + response.getMessage());
            
            if (authFuture != null && !authFuture.isDone()) {
                authFuture.complete(false);
            }
            
            // Handle specific error codes with clear messages
            switch (response.getErrorCode()) {
                case "NC-401":
                    plugin.getLogger().error("Please check your username and password in config.toml");
                    break;
                case "NC-420":
                    plugin.getLogger().error("=================================================");
                    plugin.getLogger().error("PROTOCOL VERSION MISMATCH!");
                    plugin.getLogger().error("Your NovaChat plugin version is incompatible with the NovaLink backend.");
                    plugin.getLogger().error("Please update your plugin to match the backend protocol version.");
                    plugin.getLogger().error("Current plugin protocol version: " + NovaProtocol.PROTOCOL_VERSION);
                    plugin.getLogger().error("=================================================");
                    break;
                default:
                    // Generic error handling
                    break;
            }
        }
    }

    /**
     * Handles keep-alive packets by responding immediately.
     */
    private void handleKeepAlive(KeepAlivePacket packet) {
        // Echo back the keep-alive
        KeepAlivePacket response = new KeepAlivePacket(packet.getTimestamp());
        response.setRequestId(packet.getRequestId());
        sendPacket(response);
    }

    /**
     * Hashes a password using SHA-256.
     *
     * @param password the password to hash
     * @return the hex-encoded hash
     */
    private String hashPassword(String password) {
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
     * Checks if the client is connected.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return connected.get() && channel != null && channel.isActive();
    }

    /**
     * Checks if the client is authenticated.
     *
     * @return true if authenticated
     */
    public boolean isAuthenticated() {
        return authenticated.get();
    }

    /**
     * Gets the packet registry.
     *
     * @return the packet registry
     */
    public PacketRegistry getPacketRegistry() {
        return packetRegistry;
    }
}
