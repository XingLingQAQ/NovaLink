package com.nova.link.integration;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.packets.*;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.AuthResult;
import com.nova.link.auth.ClientCredentials;
import com.nova.link.auth.ClientPermissionRegistry;
import com.nova.link.auth.NonceCache;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.AdminActionHandler;
import com.nova.link.network.ChannelActionHandler;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.NettyServer;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.spy.SpyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Embedded NovaLink server for integration testing.
 * Mirrors production wiring from {@code NovaLinkMain} for chat routing,
 * channel actions, mute/filter, and admin actions.
 *
 * Requirements: 24.1 - Enable starting embedded NovaLink server for integration tests
 */
public class EmbeddedNovaLinkServer {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedNovaLinkServer.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** Common channels pre-created so tests can join/route without extra setup. */
    private static final String[] DEFAULT_GLOBAL_CHANNELS = {
            "global", "local", "staff", "vip",
            "test-channel", "private-channel", "data-channel",
            "new-channel", "temp-channel", "invited-channel"
    };

    private final String host;
    private final int port;
    private final int workerThreads;

    private NettyServer server;
    private ServerNetworkHandler networkHandler;
    private AuthManager authManager;
    private ChannelManager channelManager;
    private MemoryProvider databaseProvider;
    private PlayerStateManager playerStateManager;
    private PermissionManager permissionManager;
    private ClientPermissionRegistry clientPermissionRegistry;
    private PrivateChannelManager privateChannelManager;
    private InvitationManager invitationManager;
    private MuteManager muteManager;
    private SensitiveWordFilter sensitiveWordFilter;
    private MessageRouter messageRouter;
    private ChannelActionHandler channelActionHandler;
    private AdminActionHandler adminActionHandler;
    private SpyManager spyManager;
    private NonceCache nonceCache;

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

        // Auth + permissions (mirrors NovaLinkMain bootstrap)
        authManager = new AuthManager();
        permissionManager = new PermissionManager();
        clientPermissionRegistry = new ClientPermissionRegistry();
        // Tests register clients dynamically; grant wildcard on handshake instead.
        clientPermissionRegistry.setAllowWhenUnregistered(false);

        // In-memory persistence stack
        databaseProvider = new MemoryProvider();
        try {
            databaseProvider.initialize();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Failed to initialize MemoryProvider", e));
        }
        playerStateManager = new PlayerStateManager(databaseProvider);

        // Core managers
        channelManager = new ChannelManager();
        privateChannelManager = new PrivateChannelManager(channelManager);
        invitationManager = new InvitationManager(databaseProvider, channelManager);
        muteManager = new MuteManager(databaseProvider, permissionManager, channelManager);
        muteManager.initialize();
        sensitiveWordFilter = new SensitiveWordFilter();

        // Default channels used by integration suites
        createDefaultChannels();

        // Network + routing
        networkHandler = new ServerNetworkHandler(workerThreads, false);
        messageRouter = new MessageRouter(channelManager, networkHandler);
        messageRouter.setMuteManager(muteManager);
        messageRouter.setSensitiveWordFilter(sensitiveWordFilter);
        messageRouter.setPermissionChecker(clientPermissionRegistry.asChecker());

        networkHandler.setDisconnectListener((connection, activeGenerationEnded) -> {
            String clientId = connection != null ? connection.getClientId() : null;
            if (activeGenerationEnded && clientId != null && !clientId.isBlank()) {
                clientPermissionRegistry.clearClient(clientId);
            }
        });

        spyManager = new SpyManager(permissionManager, channelManager, networkHandler);
        messageRouter.setSpyManager(spyManager);

        adminActionHandler = new AdminActionHandler(permissionManager);
        adminActionHandler.setSpyManager(spyManager);
        adminActionHandler.setChannelManager(channelManager);
        adminActionHandler.setNetworkHandler(networkHandler);
        adminActionHandler.setMessageRouter(messageRouter);
        adminActionHandler.setIpBanManager(authManager.getIpBanManager());

        channelActionHandler = new ChannelActionHandler(
                channelManager,
                playerStateManager,
                databaseProvider,
                privateChannelManager,
                invitationManager,
                permissionManager,
                muteManager
        );

        // AUTH-002: pending-challenge cache for the 3-packet challenge-response
        // handshake. One per embedded server; entries self-expire (30s TTL).
        nonceCache = new NonceCache();

        registerPacketHandlers();

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

        try {
            if (muteManager != null) {
                muteManager.shutdown();
            }
        } catch (Exception e) {
            logger.debug("Error shutting down MuteManager: {}", e.getMessage());
        }

        try {
            if (playerStateManager != null) {
                playerStateManager.saveAllDirty();
                playerStateManager.clearCache(false);
            }
        } catch (Exception e) {
            logger.debug("Error flushing player state: {}", e.getMessage());
        }

        if (networkHandler != null) {
            networkHandler.shutdown();
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        if (server != null) {
            server.shutdown().whenComplete((v, ex) -> {
                try {
                    if (databaseProvider != null) {
                        databaseProvider.shutdown();
                    }
                } catch (Exception e) {
                    logger.debug("Error shutting down MemoryProvider: {}", e.getMessage());
                }
                logger.info("Embedded NovaLink server stopped");
                future.complete(null);
            });
        } else {
            try {
                if (databaseProvider != null) {
                    databaseProvider.shutdown();
                }
            } catch (Exception e) {
                logger.debug("Error shutting down MemoryProvider: {}", e.getMessage());
            }
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
     * Gets the message router.
     */
    public MessageRouter getMessageRouter() {
        return messageRouter;
    }

    /**
     * Gets the mute manager.
     */
    public MuteManager getMuteManager() {
        return muteManager;
    }

    /**
     * Gets the permission manager (player-level hierarchy).
     */
    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    /**
     * Gets the client permission registry (game-server GLOBAL grants).
     */
    public ClientPermissionRegistry getClientPermissionRegistry() {
        return clientPermissionRegistry;
    }

    /**
     * Gets the channel action handler.
     */
    public ChannelActionHandler getChannelActionHandler() {
        return channelActionHandler;
    }

    /**
     * Gets the admin action handler.
     */
    public AdminActionHandler getAdminActionHandler() {
        return adminActionHandler;
    }

    /**
     * Gets the number of connected clients.
     */
    public int getConnectedClientCount() {
        return networkHandler != null ? networkHandler.getConnectionCount() : 0;
    }

    private void createDefaultChannels() {
        for (String channelId : DEFAULT_GLOBAL_CHANNELS) {
            try {
                if (channelManager.channelExists(channelId)) {
                    continue;
                }
                channelManager.createChannel(ChannelConfig.builder()
                        .id(channelId)
                        .displayName(channelId)
                        .scope(ChannelScope.GLOBAL)
                        .maxCapacity(1000)
                        .build());
            } catch (Exception e) {
                logger.warn("Failed to create default channel '{}': {}", channelId, e.getMessage());
            }
        }
        logger.debug("Default GLOBAL channels ready: {}", (Object) DEFAULT_GLOBAL_CHANNELS);
    }

    private void registerPacketHandlers() {
        // ==================== AUTH-002 challenge-response handshake ====================
        // Replaces the replayable static-hash HandshakePacket (0x01) flow with a
        // 3-packet dance: HandshakeInit (0x15) → HandshakeChallenge (0x16) →
        // HandshakeAuthenticate (0x17). The legacy 0x01 handler below is kept
        // for compile compatibility; live clients use the new path.
        networkHandler.registerHandler(HandshakeInitPacket.class, (connection, packet) -> {
            int clientProtocolVersion = packet.getProtocolVersion();
            if (clientProtocolVersion != NovaProtocol.PROTOCOL_VERSION) {
                HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                        "NC-420",
                        String.format(
                                "Protocol version mismatch: client=%d, server=%d. Please update your client.",
                                clientProtocolVersion,
                                NovaProtocol.PROTOCOL_VERSION
                        )
                );
                response.setRequestId(packet.getRequestId());
                sendResponseAndClose(connection, response);
                logger.warn("Protocol version mismatch from client {}: client={}, server={}",
                        packet.getClientId(), clientProtocolVersion, NovaProtocol.PROTOCOL_VERSION);
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
        });

        networkHandler.registerHandler(HandshakeAuthenticatePacket.class, (connection, packet) -> {
            String clientId = packet.getClientId();
            String clientNonce = packet.getClientNonce();
            String hmac = packet.getHmac();

            AuthResult authResult = authManager.authenticateChallenge(
                    clientId,
                    clientNonce,
                    hmac,
                    nonceCache,
                    connection.getRemoteAddress()
            );

            HandshakeResponsePacket response;
            if (authResult.isSuccess()) {
                if (!networkHandler.activateAuthenticated(
                        connection,
                        clientId,
                        () -> clientPermissionRegistry.grant(
                                clientId, ClientPermissionRegistry.WILDCARD))) {
                    return;
                }
                response = HandshakeResponsePacket.success("Authentication successful");
                logger.debug("Client authenticated: {}", clientId);
            } else {
                response = HandshakeResponsePacket.failure(
                        authResult.getErrorCode() != null ? authResult.getErrorCode() : "NC-401",
                        authResult.getMessage() != null ? authResult.getMessage() : "Invalid credentials"
                );
                logger.debug("Challenge authentication failed for client: {}", clientId);
            }
            response.setRequestId(packet.getRequestId());
            if (authResult.isSuccess()) {
                connection.sendPacket(response);
            } else {
                sendResponseAndClose(connection, response);
            }
        });

        // ==================== Legacy handshake handler (0x01) ====================
        // Kept for compile compatibility; live clients use the challenge-response
        // path above. Old v2 clients that still send 0x01 are rejected with NC-420.
        networkHandler.registerHandler(HandshakePacket.class, (connection, packet) -> {
            int clientProtocolVersion = packet.getProtocolVersion();
            if (clientProtocolVersion != NovaProtocol.PROTOCOL_VERSION) {
                HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                        "NC-420",
                        String.format(
                                "Protocol version mismatch: client=%d, server=%d. Please update your client.",
                                clientProtocolVersion,
                                NovaProtocol.PROTOCOL_VERSION
                        )
                );
                response.setRequestId(packet.getRequestId());
                sendResponseAndClose(connection, response);
                logger.warn("Protocol version mismatch from client {}: client={}, server={}",
                        packet.getClientId(), clientProtocolVersion, NovaProtocol.PROTOCOL_VERSION);
                return;
            }

            // Legacy static-hash auth is no longer accepted: the protocol bumped
            // to v3 and the challenge-response path is now mandatory.
            HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                    "NC-420",
                    "Legacy handshake is no longer supported. Please update your client to use the challenge-response handshake."
            );
            response.setRequestId(packet.getRequestId());
            sendResponseAndClose(connection, response);
            logger.warn("Rejected legacy HandshakePacket (0x01) from client {}; protocol now requires challenge-response",
                    packet.getClientId());
        });

        // Chat message handler — production path (not raw broadcast)
        networkHandler.registerHandler(ChatMessagePacket.class, (connection, packet) -> {
            if (!connection.isAuthenticated()) {
                logger.debug("Ignoring message from unauthenticated client");
                return;
            }

            String senderClientId = connection.getClientId();
            packet.setClientId(senderClientId);

            Channel channel = channelManager.getChannel(packet.getChannelId());
            if (channel == null) {
                logger.debug("Dropping message for unknown channel '{}' from client '{}'",
                        packet.getChannelId(), senderClientId);
                return;
            }

            if (!messageRouter.canSendToChannel(channel, senderClientId)) {
                logger.warn("Blocked cross-client message injection: senderClient={}, channel={}, channelOwner={}",
                        senderClientId, channel.getId(), channel.getClientId());
                return;
            }

            messageRouter.routeToChannel(channel, packet);
            logger.debug("Routed message from {} to channel {}",
                    packet.getSenderName(), packet.getChannelId());
        });

        // Channel action handler — real ChannelActionHandler
        networkHandler.registerHandler(ChannelActionPacket.class, (connection, packet) -> {
            ChannelActionResponsePacket response = channelActionHandler.handle(connection, packet);
            response.setRequestId(packet.getRequestId());
            connection.sendPacket(response);
            logger.debug("Processed channel action {} for channel {} success={}",
                    packet.getAction(), packet.getChannelId(), response.isSuccess());
        });

        // Keep-alive handler
        networkHandler.registerHandler(KeepAlivePacket.class, (connection, packet) -> {
            KeepAlivePacket response = new KeepAlivePacket(packet.getTimestamp());
            response.setRequestId(packet.getRequestId());
            connection.sendPacket(response);
        });

        // Admin action handler — real AdminActionHandler
        networkHandler.registerHandler(AdminActionPacket.class, (connection, packet) -> {
            if (!connection.isAuthenticated()) {
                AdminActionResponsePacket response = AdminActionResponsePacket.failure(
                        packet.getAction(), "NC-401", "Not authenticated"
                );
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response);
                return;
            }

            AdminActionResponsePacket response = adminActionHandler.handle(packet, connection.getRemoteAddress());
            response.setRequestId(packet.getRequestId());
            connection.sendPacket(response);
            logger.debug("Processed admin action: {} success={}", packet.getAction(), response.isSuccess());
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
     * (32 characters). Matches the production {@code NovaLinkMain} helper.
     */
    /**
     * Generates a fresh nonce for the AUTH-002 challenge-response handshake:
     * 16 cryptographically-random bytes, lowercase-hex-encoded (32 characters).
     * Used for both server-side challenges and client-side nonces in tests.
     */
    static String generateNonceHex() {
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
