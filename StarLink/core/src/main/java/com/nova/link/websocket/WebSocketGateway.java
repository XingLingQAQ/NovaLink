package com.nova.link.websocket;

import com.nova.link.api.RestApiHandler;
import com.nova.link.api.WebhookManager;
import com.nova.link.audit.AuditStore;
import com.nova.link.auth.AuthManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.config.ConfigManager;
import com.nova.link.config.TlsConfig;
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
     * Lifecycle state machine. {@link #running} was previously set only AFTER
     * the WebSocket server finished binding, so a {@link #shutdown()} issued
     * while {@link #start()} was still in flight returned immediately and the
     * listener that came up moments later was never stopped.
     *
     * <p>States: {@code NEW} → {@code STARTING} → {@code RUNNING} →
     * {@code STOPPING} → {@code STOPPED}. {@code STOPPING} is sticky: a start
     * that completes while a stop is in progress shuts its listener down
     * instead of publishing it.
     */
    private enum State { NEW, STARTING, RUNNING, STOPPING, STOPPED }

    private final Object lifecycleLock = new Object();
    private State state = State.NEW;

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
                java.util.List.of("*"), null, null);
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
        this(bindAddress, port, secretKey, authManager, channelManager, playerStateManager,
                messageRouter, webhookManager, networkHandler, muteManager, banManager,
                invitationManager, configManager, consoleCommandHandler, notificationStore,
                corsAllowedOrigins, null, null);
    }

    /**
     * Creates a new WebSocket gateway with optional TLS (PANEL-011 / AUTH-002).
     *
     * <p>{@code tls} is forwarded to the underlying {@link WebSocketServer} so the
     * WebSocket/REST port wraps its traffic in TLS when configured, mirroring the
     * TCP listener. The caller is still responsible for invoking
     * {@code InsecureModeGate.requireTlsOrExplicitInsecure} before bind so a
     * plaintext WS/REST port only starts under an explicit insecure opt-in.
     *
     * @param tls optional TLS configuration; {@code null} for plaintext
     */
    public WebSocketGateway(String bindAddress, int port, String secretKey,
                            AuthManager authManager, ChannelManager channelManager,
                            PlayerStateManager playerStateManager, MessageRouter messageRouter,
                            WebhookManager webhookManager,
                            ServerNetworkHandler networkHandler,
                            MuteManager muteManager, BanManager banManager,
                            InvitationManager invitationManager,
                            ConfigManager configManager, ConsoleCommandHandler consoleCommandHandler,
                            NotificationStore notificationStore,
                            java.util.List<String> corsAllowedOrigins,
                            TlsConfig tls) {
        this(bindAddress, port, secretKey, authManager, channelManager, playerStateManager,
                messageRouter, webhookManager, networkHandler, muteManager, banManager,
                invitationManager, configManager, consoleCommandHandler, notificationStore,
                corsAllowedOrigins, tls, null);
    }

    /**
     * Full constructor with an {@link AuditStore} (PANEL-006). The audit store is
     * forwarded to {@link RestApiHandler} so every P1 admin mutation is recorded.
     * The legacy constructors above delegate here with a null store (audit
     * silently disabled) so existing call sites keep compiling.
     *
     * @param auditStore the append-only audit store, or null to disable audit
     */
    public WebSocketGateway(String bindAddress, int port, String secretKey,
                            AuthManager authManager, ChannelManager channelManager,
                            PlayerStateManager playerStateManager, MessageRouter messageRouter,
                            WebhookManager webhookManager,
                            ServerNetworkHandler networkHandler,
                            MuteManager muteManager, BanManager banManager,
                            InvitationManager invitationManager,
                            ConfigManager configManager, ConsoleCommandHandler consoleCommandHandler,
                            NotificationStore notificationStore,
                            java.util.List<String> corsAllowedOrigins,
                            TlsConfig tls,
                            AuditStore auditStore) {
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
                auditStore,
                corsAllowedOrigins
        );

        // Initialize WebSocket server (PANEL-011: forward TLS so the WS/REST
        // port is encrypted when configured, mirroring the TCP listener).
        this.webSocketServer = new WebSocketServer(
                bindAddress, port, messageHandler, httpAuthHandler, restApiHandler, tls);
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
        synchronized (lifecycleLock) {
            if (state == State.STARTING || state == State.RUNNING) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("WebSocket gateway is already running"));
            }
            if (state == State.STOPPING || state == State.STOPPED) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("WebSocket gateway is stopped"));
            }
            state = State.STARTING;
        }

        return webSocketServer.start().handle((ignored, error) -> {
            if (error != null) {
                synchronized (lifecycleLock) {
                    state = State.STOPPED;
                }
                throw error instanceof java.util.concurrent.CompletionException
                        ? (java.util.concurrent.CompletionException) error
                        : new java.util.concurrent.CompletionException(error);
            }

            synchronized (lifecycleLock) {
                if (state == State.STOPPING) {
                    // stop() was called while the bind was in flight. The
                    // listener must not be published; tear it down here.
                    logger.info("WebSocket gateway start completed during shutdown; stopping it");
                    webSocketServer.shutdown().join();
                    state = State.STOPPED;
                    return null;
                }
                running = true;
                startStatusScheduler();
                state = State.RUNNING;
            }

            logger.info("WebSocket gateway started on {}:{}", bindAddress, port);
            return null;
        });
    }

    /** Schedules the periodic panel status broadcast (daemon thread). */
    private void startStatusScheduler() {
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
    }

    /**
     * Stops the WebSocket gateway. Idempotent; works even if {@link #start()}
     * is still in flight (the start completion path then stops the listener
     * it just bound).
     *
     * @return a CompletableFuture that completes when the gateway is stopped
     */
    public CompletableFuture<Void> shutdown() {
        synchronized (lifecycleLock) {
            if (state == State.STOPPING || state == State.STOPPED) {
                return CompletableFuture.completedFuture(null);
            }
            boolean neverStarted = state == State.NEW;
            state = State.STOPPING;
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
                statusScheduler = null;
            }

            if (neverStarted) {
                state = State.STOPPED;
                return CompletableFuture.completedFuture(null);
            }
        }

        return webSocketServer.shutdown().thenRun(() -> {
            synchronized (lifecycleLock) {
                state = State.STOPPED;
            }
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
     * Sends a directed notification to the single authenticated session whose
     * username matches {@code recipient}. PANEL-014: per-user WS delivery so
     * a notification addressed to one admin does not leak to every other
     * authenticated session. A null/blank recipient falls back to a broadcast
     * inside the message handler.
     *
     * @param recipient the recipient username (panel username), or null/blank
     *                  to broadcast
     * @param title     the notification title
     * @param message   the notification message
     * @param level     the notification level (info, warning, error)
     */
    public void sendDirectedNotification(String recipient, String title, String message, String level) {
        if (running) {
            messageHandler.sendDirectedNotification(recipient, title, message, level);
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
     * Gets the actually bound port (the configured port may be {@code 0} for
     * an OS-assigned ephemeral port).
     *
     * @return the bound port, or the configured port when not bound
     */
    public int getBoundPort() {
        return webSocketServer.getBoundPort();
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
