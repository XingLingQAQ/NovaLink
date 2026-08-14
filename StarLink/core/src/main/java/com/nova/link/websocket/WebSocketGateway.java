package com.nova.link.websocket;

import com.nova.link.api.RestApiHandler;
import com.nova.link.api.WebhookManager;
import com.nova.link.auth.AuthManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.config.ConfigManager;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.notification.NotificationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket Gateway for NovaLink web panel.
 * Provides a unified interface for WebSocket server management and real-time data streaming.
 * 
 * Requirements: 24.1, 24.2, 24.4
 */
public class WebSocketGateway {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketGateway.class);
    
    /** Status update interval in seconds */
    private static final int STATUS_UPDATE_INTERVAL = 30;

    private final String bindAddress;
    private final int port;
    private final String secretKey;
    
    private final JwtService jwtService;
    private final WebSocketMessageHandler messageHandler;
    private final HttpAuthHandler httpAuthHandler;
    private final RestApiHandler restApiHandler;
    private final WebSocketServer webSocketServer;
    
    private ScheduledExecutorService statusScheduler;
    private volatile boolean running = false;

    /**
     * Creates a new WebSocket gateway.
     *
     * @param bindAddress    the address to bind to
     * @param port           the port to listen on
     * @param secretKey      the secret key for JWT signing
     * @param authManager    the authentication manager
     * @param channelManager the channel manager
     * @param networkHandler the network handler
     */
    public WebSocketGateway(String bindAddress, int port, String secretKey,
                            AuthManager authManager, ChannelManager channelManager,
                            PlayerStateManager playerStateManager, MessageRouter messageRouter,
                            WebhookManager webhookManager,
                            ServerNetworkHandler networkHandler,
                            MuteManager muteManager, BanManager banManager,
                            InvitationManager invitationManager,
                            ConfigManager configManager, ConsoleCommandHandler consoleCommandHandler,
                            NotificationStore notificationStore) {
        this(bindAddress, port, secretKey, authManager, channelManager, playerStateManager,
                messageRouter, webhookManager, networkHandler, muteManager, banManager,
                invitationManager, configManager, consoleCommandHandler, notificationStore,
                java.util.List.of("*"));
    }

    public WebSocketGateway(String bindAddress, int port, String secretKey,
                            AuthManager authManager, ChannelManager channelManager,
                            PlayerStateManager playerStateManager, MessageRouter messageRouter,
                            WebhookManager webhookManager,
                            ServerNetworkHandler networkHandler,
                            MuteManager muteManager, BanManager banManager,
                            InvitationManager invitationManager,
                            ConfigManager configManager, ConsoleCommandHandler consoleCommandHandler,
                            NotificationStore notificationStore,
                            java.util.List<String> corsAllowedOrigins) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.secretKey = secretKey;

        // Initialize JWT service
        this.jwtService = new JwtService(secretKey);

        // Initialize message handler
        this.messageHandler = new WebSocketMessageHandler(
                jwtService, authManager, channelManager, networkHandler, playerStateManager);

        // Initialize HTTP auth handler
        this.httpAuthHandler = new HttpAuthHandler(jwtService, authManager, corsAllowedOrigins);

        // Initialize REST API handler (optional, but recommended for web panel integrations)
        this.restApiHandler = new RestApiHandler(
                jwtService,
                authManager,
                channelManager,
                playerStateManager,
                messageRouter,
                webhookManager,
                muteManager,
                banManager,
                invitationManager,
                configManager,
                networkHandler,
                consoleCommandHandler,
                notificationStore,
                corsAllowedOrigins
        );

        // Initialize WebSocket server
        this.webSocketServer = new WebSocketServer(
                bindAddress, port, messageHandler, httpAuthHandler, restApiHandler);
    }

    /**
     * Exposes the REST handler so NovaLinkMain can inject setter-based
     * dependencies (AnnouncementManager, MessageLogService) after construction.
     */
    public RestApiHandler getRestApiHandler() {
        return restApiHandler;
    }

    /**
     * Exposes the auth handler so NovaLinkMain can inject the shared REST
     * worker executor after construction.
     */
    public HttpAuthHandler getHttpAuthHandler() {
        return httpAuthHandler;
    }

    /**
     * Starts the WebSocket gateway.
     *
     * @return a CompletableFuture that completes when the gateway is started
     */
    public CompletableFuture<Void> start() {
        if (running) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("WebSocket gateway is already running"));
        }

        return webSocketServer.start().thenRun(() -> {
            running = true;
            
            // Start periodic status updates
            statusScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WebSocket-Status-Scheduler");
                t.setDaemon(true);
                return t;
            });
            
            statusScheduler.scheduleAtFixedRate(() -> {
                try {
                    if (messageHandler.getSessionCount() > 0) {
                        messageHandler.broadcastServerStatus();
                        messageHandler.broadcastChannelUpdate();
                    }
                } catch (Exception e) {
                    logger.error("Error broadcasting status update", e);
                }
            }, STATUS_UPDATE_INTERVAL, STATUS_UPDATE_INTERVAL, TimeUnit.SECONDS);
            
            logger.info("WebSocket gateway started on {}:{}", bindAddress, port);
        });
    }

    /**
     * Stops the WebSocket gateway.
     *
     * @return a CompletableFuture that completes when the gateway is stopped
     */
    public CompletableFuture<Void> shutdown() {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }

        running = false;
        
        // Stop status scheduler
        if (statusScheduler != null) {
            statusScheduler.shutdown();
            try {
                if (!statusScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    statusScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                statusScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        return webSocketServer.shutdown().thenRun(() -> {
            logger.info("WebSocket gateway stopped");
        });
    }

    /**
     * Broadcasts a chat message to subscribed web panel clients.
     * Requirements: 24.2
     *
     * @param channelId   the channel ID
     * @param senderId    the sender UUID
     * @param senderName  the sender name
     * @param content     the message content
     */
    public void broadcastChatMessage(String channelId, String senderId, String senderName, String content) {
        if (running) {
            messageHandler.broadcastChatMessage(channelId, senderId, senderName, content);
        }
    }

    /**
     * Broadcasts a server status update to all authenticated web panel clients.
     * Requirements: 24.2
     */
    public void broadcastServerStatus() {
        if (running) {
            messageHandler.broadcastServerStatus();
        }
    }

    /**
     * Broadcasts a channel update to all authenticated web panel clients.
     * Requirements: 24.2
     */
    public void broadcastChannelUpdate() {
        if (running) {
            messageHandler.broadcastChannelUpdate();
        }
    }

    /**
     * Broadcasts a player update to all authenticated web panel clients.
     * Triggered on player join/leave for real-time presence in the panel.
     */
    public void broadcastPlayerUpdate() {
        if (running) {
            messageHandler.broadcastPlayerUpdate();
        }
    }

    /**
     * Sends a notification to all authenticated web panel clients.
     *
     * @param title   the notification title
     * @param message the notification message
     * @param level   the notification level (info, warning, error)
     */
    public void broadcastNotification(String title, String message, String level) {
        if (running) {
            messageHandler.broadcastNotification(title, message, level);
        }
    }

    /**
     * Generates a JWT token for a user.
     *
     * @param userId   the user ID
     * @param username the username
     * @param role     the user role
     * @return the JWT token
     */
    public String generateToken(String userId, String username, String role) {
        return jwtService.generateToken(userId, username, role);
    }

    /**
     * Checks if the gateway is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Gets the number of connected web panel sessions.
     *
     * @return the session count
     */
    public int getSessionCount() {
        return messageHandler.getSessionCount();
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
     * Gets the JWT service.
     *
     * @return the JWT service
     */
    public JwtService getJwtService() {
        return jwtService;
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
     * Creates a WebSocket broadcaster for integration with MessageRouter.
     * Requirements: 24.2
     *
     * @return the WebSocket broadcaster
     */
    public MessageRouter.WebSocketBroadcaster createBroadcaster() {
        return this::broadcastChatMessage;
    }
}
