package com.nova.chat.mod.network;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.codec.Varint21FrameDecoder;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty-based implementation of NetworkClient
 * Handles TCP connection, packet encoding/decoding, and reconnection logic
 */
public class NettyNetworkClient implements NetworkClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(NettyNetworkClient.class);
    
    private final EventLoopGroup eventLoopGroup;
    private final PacketRegistry packetRegistry;
    private final List<PacketHandler> handlers = new ArrayList<>();
    
    private Channel channel;
    private ConnectionStatus status = ConnectionStatus.DISCONNECTED;
    private String host;
    private int port;
    private String clientId;
    private String passwordHash;
    
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final int maxReconnectAttempts = 10;
    private int reconnectDelay = 5;
    private ScheduledFuture<?> reconnectTask;
    
    public NettyNetworkClient() {
        this.eventLoopGroup = new NioEventLoopGroup();
        this.packetRegistry = NovaProtocol.createRegistry();
    }
    
    @Override
    public CompletableFuture<Boolean> connect(String host, int port) {
        this.host = host;
        this.port = port;
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        if (status == ConnectionStatus.CONNECTED) {
            future.complete(true);
            return future;
        }
        
        setStatus(ConnectionStatus.CONNECTING);
        
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        
                        // Add frame decoder
                        pipeline.addLast("frameDecoder", new Varint21FrameDecoder());
                        
                        // Add packet decoder
                        pipeline.addLast("packetDecoder", new PacketDecoder(packetRegistry));
                        
                        // Add packet encoder
                        pipeline.addLast("packetEncoder", new PacketEncoder());
                        
                        // Add handler
                        pipeline.addLast("handler", new ClientChannelHandler(NettyNetworkClient.this, future));
                    }
                });
        
        bootstrap.connect(host, port).addListener((ChannelFuture connectFuture) -> {
            if (!connectFuture.isSuccess()) {
                LOGGER.error("Failed to connect to {}:{}", host, port, connectFuture.cause());
                setStatus(ConnectionStatus.ERROR);
                future.complete(false);
                scheduleReconnect();
            }
        });
        
        return future;
    }
    
    @Override
    public void disconnect() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
        
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        
        setStatus(ConnectionStatus.DISCONNECTED);
        reconnectAttempts.set(0);
    }
    
    @Override
    public void sendChatMessage(UUID playerId, String playerName, String channelId, String message) {
        if (!isConnected()) {
            LOGGER.warn("Cannot send chat message: not connected");
            return;
        }
        
        ChatMessagePacket packet = new ChatMessagePacket();
        packet.setSenderId(playerId);
        packet.setSenderName(playerName);
        packet.setClientId(clientId);
        packet.setChannelId(channelId);
        packet.setContent(message);
        
        sendPacket(packet);
    }
    
    @Override
    public boolean isConnected() {
        return status == ConnectionStatus.CONNECTED && channel != null && channel.isActive();
    }
    
    @Override
    public ConnectionStatus getStatus() {
        return status;
    }
    
    @Override
    public void registerPacketHandler(PacketHandler handler) {
        handlers.add(handler);
    }
    
    /**
     * Send a packet to the server
     * @param packet the packet to send
     */
    public void sendPacket(Packet packet) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(packet);
        } else {
            LOGGER.warn("Cannot send packet: channel not active");
        }
    }
    
    /**
     * Handle incoming packet
     * @param packet the received packet
     */
    public void handlePacket(Packet packet) {
        for (PacketHandler handler : handlers) {
            try {
                handler.handlePacket(packet);
            } catch (Exception e) {
                LOGGER.error("Error handling packet", e);
            }
        }
    }
    
    /**
     * Set connection status
     * @param status the new status
     */
    private void setStatus(ConnectionStatus status) {
        this.status = status;
        LOGGER.debug("Connection status changed to: {}", status.getDisplayName());
    }
    
    /**
     * Schedule reconnection attempt
     */
    private void scheduleReconnect() {
        if (reconnectAttempts.get() >= maxReconnectAttempts) {
            LOGGER.error("Max reconnection attempts reached");
            setStatus(ConnectionStatus.ERROR);
            return;
        }
        
        int attempt = reconnectAttempts.incrementAndGet();
        long delay = (long) reconnectDelay * (1 << Math.min(attempt - 1, 5)); // Exponential backoff, capped at 2^5
        
        LOGGER.info("Scheduling reconnection attempt {} in {} seconds", attempt, delay);
        setStatus(ConnectionStatus.RECONNECTING);
        
        reconnectTask = eventLoopGroup.schedule(() -> {
            LOGGER.info("Attempting to reconnect (attempt {}/{})", attempt, maxReconnectAttempts);
            connect(host, port);
        }, delay, TimeUnit.SECONDS);
    }
    
    /**
     * Called when connection is established
     */
    public void onConnected(Channel channel) {
        this.channel = channel;
        setStatus(ConnectionStatus.CONNECTED);
        reconnectAttempts.set(0);
        LOGGER.info("Connected to {}:{}", host, port);
    }
    
    /**
     * Called when connection is closed
     */
    public void onDisconnected() {
        if (channel != null) {
            channel = null;
        }
        setStatus(ConnectionStatus.DISCONNECTED);
        LOGGER.info("Disconnected from {}:{}", host, port);
        scheduleReconnect();
    }
    
    /**
     * Called when an error occurs
     */
    public void onError(Throwable cause) {
        LOGGER.error("Network error", cause);
        setStatus(ConnectionStatus.ERROR);
        if (channel != null) {
            channel.close();
        }
        scheduleReconnect();
    }
    
    /**
     * Packet decoder
     */
    private static class PacketDecoder extends ByteToMessageDecoder {
        private final PacketRegistry registry;
        
        PacketDecoder(PacketRegistry registry) {
            this.registry = registry;
        }
        
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < 1) {
                return;
            }
            
            int packetId = in.readUnsignedByte();
            Packet packet = registry.createPacket(packetId);
            
            if (packet != null) {
                packet.decode(in);
                out.add(packet);
            } else {
                LOGGER.warn("Unknown packet ID: {}", packetId);
            }
        }
    }
    
    /**
     * Packet encoder
     */
    private static class PacketEncoder extends MessageToByteEncoder<Packet> {
        @Override
        protected void encode(ChannelHandlerContext ctx, Packet msg, ByteBuf out) {
            msg.encode(out);
        }
    }
    
    /**
     * Client channel handler
     */
    private static class ClientChannelHandler extends SimpleChannelInboundHandler<Packet> {
        private final NettyNetworkClient client;
        private final CompletableFuture<Boolean> connectFuture;
        
        ClientChannelHandler(NettyNetworkClient client, CompletableFuture<Boolean> connectFuture) {
            this.client = client;
            this.connectFuture = connectFuture;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            client.onConnected(ctx.channel());
            connectFuture.complete(true);
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            client.onDisconnected();
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Packet msg) {
            client.handlePacket(msg);
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            client.onError(cause);
            ctx.close();
        }
    }
}
