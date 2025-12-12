package com.nova.link.integration;

import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.*;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.ClientCredentials;
import com.nova.link.channel.ChannelManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.NettyServer;
import com.nova.link.network.PacketHandler;
import com.nova.link.network.ServerNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Embedded NovaLink server for integration testing.
 * Provides a fully functional NovaLink backend that can be started and stopped
 * within test cases.
 * 
 * Requirements: 24.1 - Enable starting embedded NovaLink server for integration tests
 */
public class EmbeddedNovaLinkServer {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedNovaLinkServer.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final String host;
    private final int port;
    private final int workerThreads;

    private NettyServer server;
    private ServerNetworkHandler networkHandler;
    private AuthManager authManager;
    private ChannelManager channelManager;

    private volatile boolean running = false;

    /**
     * Creates an embedded NovaLink server with default settings.
     * 
     * @param port the port to listen on
     */
    public EmbeddedNovaLinkServer(int port) {
        this("127.0.0.1", port, 2);
    }

    /**
     * Creates an embedded NovaLink server with custom settings.
     * 
     * @param host the host to bind to
     * @param port the port to listen on
     * @param workerThreads the number of worker threads
     */
    public EmbeddedNovaLinkServer(String host, int port, int workerThreads) {
        this.host = host;
        this.port = port;
        this.workerThreads = workerThreads;
    }

    /**
     * Starts the embedded server.
     * 
     * @return CompletableFuture that completes when server is started
     */
    public CompletableFuture<Void> start() {
        if (running) {
            return CompletableFuture.completedFuture(null);
        }

        logger.info("Starting embedded NovaLink server on {}:{}", host, port);

        // Initialize managers
        authManager = new AuthManager();
        channelManager = new ChannelManager();

        // Create network handler
        networkHandler = new ServerNetworkHandler(workerThreads, false);

        // Register packet handlers
        registerPacketHandlers();

        // Create and start server
        server = new NettyServer(host, port, workerThreads, networkHandler);

        return server.start().thenRun(() -> {
            running = true;
            logger.info("Embedded NovaLink server started successfully");
        });
    }

    /**
     * Starts the server and waits for it to be ready.
     * 
     * @throws Exception if server fails to start
     */
    public void startAndWait() throws Exception {
        start().get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the embedded server.
     * 
     * @return CompletableFuture that completes when server is stopped
     */
    public CompletableFuture<Void> stop() {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }

        logger.info("Stopping embedded NovaLink server");

        running = false;

        CompletableFuture<Void> future = new CompletableFuture<>();

        // Shutdown network handler
        if (networkHandler != null) {
            networkHandler.shutdown();
        }

        // Stop server
        if (server != null) {
            server.shutdown().whenComplete((v, ex) -> {
                logger.info("Embedded NovaLink server stopped");
                future.complete(null);
            });
        } else {
            future.complete(null);
        }

        return future;
    }

    /**
     * Stops the server and waits for it to be fully stopped.
     * 
     * @throws Exception if server fails to stop
     */
    public void stopAndWait() throws Exception {
        stop().get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Registers a client with credentials.
     * 
     * @param clientId the client ID
     * @param password the plain text password
     */
    public void registerClient(String clientId, String password) {
        String passwordHash = hashPassword(password);
        ClientCredentials credentials = new ClientCredentials(clientId, passwordHash);
        authManager.registerClient(credentials);
        logger.debug("Registered client: {}", clientId);
    }

    /**
     * Checks if the server is running.
     */
    public boolean isRunning() {
        return running && server != null && server.isRunning();
    }

    /**
     * Gets the server host.
     */
    public String getHost() {
        return host;
    }

    /**
     * Gets the server port.
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets the auth manager.
     */
    public AuthManager getAuthManager() {
        return authManager;
    }

    /**
     * Gets the channel manager.
     */
    public ChannelManager getChannelManager() {
        return channelManager;
    }

    /**
     * Gets the network handler.
     */
    public ServerNetworkHandler getNetworkHandler() {
        return networkHandler;
    }

    /**
     * Gets the number of connected clients.
     */
    public int getConnectedClientCount() {
        return networkHandler != null ? networkHandler.getConnectionCount() : 0;
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
                    logger.warn("Protocol version mismatch from client {}: client={}, server={}", 
                        packet.getClientId(), clientProtocolVersion, 
                        com.nova.chat.common.protocol.NovaProtocol.PROTOCOL_VERSION);
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
                    logger.debug("Client authenticated: {}", packet.getClientId());
                } else {
                    response = HandshakeResponsePacket.failure("NC-401", "Invalid credentials");
                    logger.debug("Authentication failed for client: {}", packet.getClientId());
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
                    logger.debug("Ignoring message from unauthenticated client");
                    return;
                }

                // Broadcast to all authenticated clients
                networkHandler.broadcastAuthenticated(packet);
                logger.debug("Broadcast message from {} to channel {}", 
                    packet.getSenderName(), packet.getChannelId());
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

                // Process channel action
                ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                    true, packet.getAction(), packet.getChannelId(),
                    "", "Action completed successfully"
                );
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response);
                logger.debug("Processed channel action {} for channel {}", 
                    packet.getAction(), packet.getChannelId());
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

        // Admin action handler
        networkHandler.registerHandler(AdminActionPacket.class, new PacketHandler<AdminActionPacket>() {
            @Override
            public void handle(ClientConnection connection, AdminActionPacket packet) {
                if (!connection.isAuthenticated()) {
                    AdminActionResponsePacket response = AdminActionResponsePacket.failure(
                        packet.getAction(), "NC-401", "Not authenticated"
                    );
                    response.setRequestId(packet.getRequestId());
                    connection.sendPacket(response);
                    return;
                }

                // Process admin action
                AdminActionResponsePacket response = AdminActionResponsePacket.success(
                    packet.getAction(), "Admin action completed"
                );
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response);
                logger.debug("Processed admin action: {}", packet.getAction());
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
}
