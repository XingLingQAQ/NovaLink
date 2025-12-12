package com.nova.chat.pnx.network;

import cn.nukkit.scheduler.AsyncTask;
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
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.config.NovaChatConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Netty-based network client for connecting to NovaLink backend.
 * Reuses novachat-common protocol implementation for compatibility.
 * 
 * Requirements: 28.3, 28.5
 */
public class NetworkClient {

    private final NovaChatPNX plugin;
    private final NovaChatConfig config;
    
    @Getter
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
    public NetworkClient(NovaChatPNX plugin, NovaChatConfig config) {
        this.plugin = plugin;
        this.config = config;
        // Use novachat-common protocol registry
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
                    
                    // Frame codecs (from novachat-common)
                    pipeline.addLast("frameDecoder", new Varint21FrameDecoder());
                    pipeline.addLast("framePrepender", new Varint21LengthFieldPrepender());
                    
                    // Packet codecs (from novachat-common)
                    pipeline.addLast("packetDecoder", new PacketDecoder(packetRegistry));
                    pipeline.addLast("packetEncoder", new PacketEncoder(packetRegistry));
                    
                    // Handler
                    pipeline.addLast("handler", new ClientChannelHandler(NetworkClient.this, plugin));
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
                plugin.getLogger().warning("Failed to connect to NovaLink: " + future.cause().getMessage());
                authFuture.complete(false);
                scheduleReconnect();
            }
        });
        
        return authFuture;
    }

    /**
     * Disconnect from the backend server.
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
     * Send a packet to the backend server.
     *
     * @param packet The packet to send
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
            plugin.getLogger().warning("Lost connection to NovaLink backend");
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
        
        // Schedule reconnection using Nukkit's scheduler
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            reconnecting.set(false);
            
            // Cleanup old resources
            if (workerGroup != null && !workerGroup.isShutdown()) {
                workerGroup.shutdownGracefully();
            }
            
            // Attempt reconnection asynchronously
            plugin.getServer().getScheduler().scheduleAsyncTask(plugin, new AsyncTask() {
                @Override
                public void onRun() {
                    connect(config.getBackendHost(), config.getBackendPort());
                }
            });
        }, delay * 20); // Convert seconds to ticks
    }

    /**
     * Sends the handshake packet for authentication.
     */
    private void sendHandshake() {
        String passwordHash = hashPassword(config.getBackendPassword());
        
        HandshakePacket handshake = new HandshakePacket(
            NovaProtocol.PROTOCOL_VERSION,
            config.getBackendUsername(),
            passwordHash,
            PlatformType.POWERNUKKITX  // Use POWERNUKKITX platform type
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
                    plugin.getLogger().error("Please check your username and password in config.yml");
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
}
