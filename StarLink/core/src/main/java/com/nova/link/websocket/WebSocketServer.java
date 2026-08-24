package com.nova.link.websocket;

import com.nova.link.api.RestApiHandler;
import com.nova.link.config.TlsConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket server for NovaLink web panel connections.
 * Uses Netty for high-performance WebSocket handling.
 * 
 * Requirements: 24.1 - WebSocket gateway for web panel
 */
public class WebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);
    
    /** WebSocket path */
    private static final String WEBSOCKET_PATH = "/ws";
    
    /** Max frame size: 64KB */
    private static final int MAX_FRAME_SIZE = 65536;
    
    /** Max content length for HTTP aggregator: 64KB */
    private static final int MAX_CONTENT_LENGTH = 65536;
    
    /** Idle timeout in seconds */
    private static final int IDLE_TIMEOUT_SECONDS = 60;

    private final String bindAddress;
    private final int port;
    private final WebSocketMessageHandler messageHandler;
    private final HttpAuthHandler httpAuthHandler;
    private final RestApiHandler restApiHandler;
    /**
     * PANEL-011 / AUTH-002: built once at construction from {@link TlsConfig}.
     * When non-null an {@link SslHandler} is prepended at the HEAD of every
     * accepted channel's pipeline so the WebSocket upgrade and the REST/auth
     * HTTP traffic run inside TLS. {@code null} = plaintext (which the
     * {@code InsecureModeGate} blocks at startup unless explicitly opted in).
     */
    private final SslContext sslContext;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    /**
     * Creates a new WebSocket server (plaintext legacy constructor).
     *
     * @param bindAddress     the address to bind to
     * @param port            the port to listen on
     * @param messageHandler  the message handler
     * @param httpAuthHandler the HTTP authentication handler
     * @param restApiHandler  the REST API handler (optional)
     */
    public WebSocketServer(String bindAddress, int port, WebSocketMessageHandler messageHandler,
                           HttpAuthHandler httpAuthHandler, RestApiHandler restApiHandler) {
        this(bindAddress, port, messageHandler, httpAuthHandler, restApiHandler, null);
    }

    /**
     * Creates a new WebSocket server with optional TLS (PANEL-011 / AUTH-002).
     *
     * <p>When {@code tls} is non-null and {@link TlsConfig#isConfigured()} the
     * server builds a Netty {@link SslContext} from the configured cert/key
     * files and prepends an {@link SslHandler} at the HEAD of every accepted
     * channel pipeline, mirroring {@link com.nova.link.network.NettyServer}.
     * If {@code tls} is non-null but missing its cert/key, the constructor
     * throws {@link IllegalStateException} — fail-closed rather than silently
     * degrading to plaintext. {@code tls == null} keeps the legacy plaintext
     * behaviour (the caller is still subject to {@code InsecureModeGate}).
     *
     * @param bindAddress     the address to bind to
     * @param port            the port to listen on
     * @param messageHandler  the message handler
     * @param httpAuthHandler the HTTP authentication handler
     * @param restApiHandler  the REST API handler (optional)
     * @param tls             optional TLS configuration; {@code null} for plaintext
     */
    public WebSocketServer(String bindAddress, int port, WebSocketMessageHandler messageHandler,
                           HttpAuthHandler httpAuthHandler, RestApiHandler restApiHandler,
                           TlsConfig tls) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.messageHandler = messageHandler;
        this.httpAuthHandler = httpAuthHandler;
        this.restApiHandler = restApiHandler;
        this.sslContext = buildSslContext(tls);
    }

    /**
     * Builds the {@link SslContext} from {@link TlsConfig}, or returns
     * {@code null} when TLS is not configured. Throws on a misconfigured TLS
     * block so the server fails to start rather than running plaintext. Mirrors
     * {@link com.nova.link.network.NettyServer#buildSslContext}.
     */
    private static SslContext buildSslContext(TlsConfig tls) {
        if (tls == null || !tls.isConfigured()) {
            return null;
        }
        File certChain = new File(tls.getCertChainFile());
        File privateKey = new File(tls.getPrivateKeyFile());
        if (!certChain.isFile()) {
            throw new IllegalStateException(
                    "AUTH-002: server.tls.cert-chain-file not found or not a file: " + certChain);
        }
        if (!privateKey.isFile()) {
            throw new IllegalStateException(
                    "AUTH-002: server.tls.private-key-file not found or not a file: " + privateKey);
        }
        try {
            SslContextBuilder builder = SslContextBuilder.forServer(certChain, privateKey);
            if (tls.isMutualTls() && tls.getCaCertFile() != null && !tls.getCaCertFile().isBlank()) {
                File caCert = new File(tls.getCaCertFile());
                if (!caCert.isFile()) {
                    throw new IllegalStateException(
                            "AUTH-002: server.tls.ca-cert-file not found or not a file: " + caCert);
                }
                builder.trustManager(caCert).clientAuth(ClientAuth.REQUIRE);
            }
            return builder.build();
        } catch (SSLException e) {
            throw new IllegalStateException("AUTH-002: failed to build WebSocket/REST SslContext", e);
        }
    }

    /**
     * Starts the WebSocket server.
     *
     * @return a CompletableFuture that completes when the server is started
     */
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> future = new CompletableFuture<>();

        if (running) {
            future.completeExceptionally(new IllegalStateException("WebSocket server is already running"));
            return future;
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();

                            // PANEL-011 / AUTH-002: TLS is the outermost transport.
                            // The SslHandler must sit at the HEAD of the pipeline so
                            // every subsequent handler (HTTP codec, REST, auth, WS
                            // upgrade) sees decrypted bytes and every outbound write is
                            // encrypted on the way out. When mutualTls is configured the
                            // SslContext already has ClientAuth.REQUIRE wired, so the
                            // handshake simply fails for clients without a trusted cert.
                            if (sslContext != null) {
                                SslHandler sslHandler = sslContext.newHandler(ch.alloc());
                                pipeline.addLast("ssl", sslHandler);
                            }

                            // Idle state handler for connection timeout
                            pipeline.addLast("idleStateHandler",
                                    new IdleStateHandler(IDLE_TIMEOUT_SECONDS, 0, 0, TimeUnit.SECONDS));
                            
                            // HTTP codec for WebSocket handshake
                            pipeline.addLast("httpCodec", new HttpServerCodec());
                            
                            // HTTP aggregator for full HTTP requests
                            pipeline.addLast("httpAggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                            
                            // REST API handler (for /api/* endpoints)
                            if (restApiHandler != null) {
                                pipeline.addLast("restApi", restApiHandler);
                            }

                            // HTTP auth handler (for /api/auth/* endpoints)
                            pipeline.addLast("httpAuth", httpAuthHandler);
                            
                            // WebSocket compression
                            pipeline.addLast("wsCompression", new WebSocketServerCompressionHandler());
                            
                            // WebSocket protocol handler
                            pipeline.addLast("wsProtocol", 
                                    new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true, MAX_FRAME_SIZE));
                            
                            // Custom WebSocket handler
                            pipeline.addLast("wsHandler", new WebSocketChannelHandler(messageHandler));
                        }
                    });

            ChannelFuture bindFuture = bootstrap.bind(new InetSocketAddress(bindAddress, port));
            bindFuture.addListener((ChannelFutureListener) channelFuture -> {
                if (channelFuture.isSuccess()) {
                    serverChannel = channelFuture.channel();
                    running = true;
                    logger.info("WebSocket server started on {}:{}{}", bindAddress, port, WEBSOCKET_PATH);
                    future.complete(null);
                } else {
                    logger.error("Failed to bind WebSocket server to {}:{}", bindAddress, port, channelFuture.cause());
                    shutdown();
                    future.completeExceptionally(channelFuture.cause());
                }
            });

        } catch (Exception e) {
            logger.error("Failed to start WebSocket server", e);
            shutdown();
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Shuts down the WebSocket server.
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
        logger.info("Shutting down WebSocket server...");

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
            logger.info("WebSocket server shut down successfully");
            future.complete(null);
        });
    }

    /**
     * Checks if the server is running.
     *
     * @return true if running
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
     * Gets the WebSocket path.
     *
     * @return the WebSocket path
     */
    public String getWebSocketPath() {
        return WEBSOCKET_PATH;
    }

    /**
     * Gets the message handler.
     *
     * @return the message handler
     */
    public WebSocketMessageHandler getMessageHandler() {
        return messageHandler;
    }

    /**
     * PANEL-011 / AUTH-002: whether TLS is configured for this server. Exposed
     * for tests so the fail-closed wiring (SslContext built at construction,
     * SslHandler prepended at pipeline HEAD) can be asserted without a real
     * bind. Production callers should not branch on this — the gate is
     * {@link com.nova.link.network.InsecureModeGate}, invoked by the caller
     * before {@link #start()}.
     *
     * @return {@code true} when an {@link SslContext} was built from the
     *         configured {@link TlsConfig}; {@code false} for plaintext
     */
    public boolean isSslConfigured() {
        return sslContext != null;
    }
}
