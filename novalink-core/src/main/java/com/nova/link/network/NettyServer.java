package com.nova.link.network;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.codec.PacketDecoder;
import com.nova.chat.common.protocol.codec.PacketEncoder;
import com.nova.chat.common.protocol.codec.Varint21FrameDecoder;
import com.nova.chat.common.protocol.codec.Varint21LengthFieldPrepender;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Netty-based TCP server for NovaLink backend.
 * Handles client connections using NovaProtocol.
 * 
 * Requirements: 1.1, 1.2 - Client authentication and connection handling
 */
public class NettyServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    private final String bindAddress;
    private final int port;
    private final int workerThreads;
    private final PacketRegistry packetRegistry;
    private final ServerNetworkHandler networkHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    /**
     * Creates a new NettyServer instance.
     *
     * @param bindAddress    the address to bind to
     * @param port           the port to listen on
     * @param workerThreads  the number of worker threads
     * @param networkHandler the handler for processing packets
     */
    public NettyServer(String bindAddress, int port, int workerThreads, ServerNetworkHandler networkHandler) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.workerThreads = workerThreads;
        this.networkHandler = networkHandler;
        this.packetRegistry = NovaProtocol.createRegistry();
    }

    /**
     * Starts the server asynchronously.
     *
     * @return a CompletableFuture that completes when the server is started
     */
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> future = new CompletableFuture<>();

        if (running) {
            future.completeExceptionally(new IllegalStateException("Server is already running"));
            return future;
        }

        // Create boss group (accepts connections) with 1 thread
        bossGroup = new NioEventLoopGroup(1);
        // Create worker group (handles I/O) with configured threads
        workerGroup = new NioEventLoopGroup(workerThreads);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    // TCP_NODELAY: Disable Nagle's algorithm for lower latency
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // SO_KEEPALIVE: Enable TCP keep-alive for connection health
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    // SO_REUSEADDR: Allow address reuse for quick restart
                    .option(ChannelOption.SO_REUSEADDR, true)
                    // Connection backlog
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            
                            // Frame decoders/encoders for packet boundary detection
                            pipeline.addLast("frameDecoder", new Varint21FrameDecoder());
                            pipeline.addLast("framePrepender", new Varint21LengthFieldPrepender());
                            
                            // Packet codec
                            pipeline.addLast("packetDecoder", new PacketDecoder(packetRegistry));
                            pipeline.addLast("packetEncoder", new PacketEncoder(packetRegistry));
                            
                            // Business logic handler
                            pipeline.addLast("handler", new ServerChannelHandler(networkHandler));
                        }
                    });

            // Bind and start accepting connections
            ChannelFuture bindFuture = bootstrap.bind(new InetSocketAddress(bindAddress, port));
            bindFuture.addListener((ChannelFutureListener) channelFuture -> {
                if (channelFuture.isSuccess()) {
                    serverChannel = channelFuture.channel();
                    running = true;
                    logger.info("NovaLink server started on {}:{}", bindAddress, port);
                    future.complete(null);
                } else {
                    logger.error("Failed to bind server to {}:{}", bindAddress, port, channelFuture.cause());
                    shutdown();
                    future.completeExceptionally(channelFuture.cause());
                }
            });

        } catch (Exception e) {
            logger.error("Failed to start server", e);
            shutdown();
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Shuts down the server gracefully.
     *
     * @return a CompletableFuture that completes when the server is shut down
     */
    public CompletableFuture<Void> shutdown() {
        CompletableFuture<Void> future = new CompletableFuture<>();

        if (!running && bossGroup == null && workerGroup == null) {
            future.complete(null);
            return future;
        }

        running = false;
        logger.info("Shutting down NovaLink server...");

        // Close server channel first
        if (serverChannel != null) {
            serverChannel.close().addListener(f -> {
                shutdownEventLoops(future);
            });
        } else {
            shutdownEventLoops(future);
        }

        return future;
    }

    private void shutdownEventLoops(CompletableFuture<Void> future) {
        CompletableFuture<Void> bossFuture = new CompletableFuture<>();
        CompletableFuture<Void> workerFuture = new CompletableFuture<>();

        if (bossGroup != null) {
            bossGroup.shutdownGracefully().addListener(f -> {
                bossGroup = null;
                bossFuture.complete(null);
            });
        } else {
            bossFuture.complete(null);
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully().addListener(f -> {
                workerGroup = null;
                workerFuture.complete(null);
            });
        } else {
            workerFuture.complete(null);
        }

        CompletableFuture.allOf(bossFuture, workerFuture).whenComplete((v, ex) -> {
            logger.info("NovaLink server shut down successfully");
            future.complete(null);
        });
    }

    /**
     * Checks if the server is running.
     *
     * @return true if the server is running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Gets the bind address.
     *
     * @return the bind address
     */
    public String getBindAddress() {
        return bindAddress;
    }

    /**
     * Gets the port.
     *
     * @return the port
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets the actually bound port.
     *
     * <p>When the server is constructed with port {@code 0}, the OS assigns
     * an ephemeral free port; this returns that real port by reading it from
     * the bound server channel. Returns the configured port (which may be 0)
     * if the server is not yet running or the channel has no local address.
     *
     * @return the actually bound port, or the configured port if not bound
     */
    public int getBoundPort() {
        if (serverChannel != null && serverChannel.localAddress() instanceof InetSocketAddress addr) {
            return addr.getPort();
        }
        return port;
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
