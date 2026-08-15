package com.nova.link.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.announcement.Announcement;
import com.nova.link.announcement.AnnouncementManager;
import com.nova.link.announcement.AnnouncementResult;
import com.nova.link.announcement.AnnouncementType;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.PanelRole;
import com.nova.link.ban.BanManager;
import com.nova.link.ban.BanResult;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.config.ConfigManager;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.database.BanInfo;
import com.nova.link.database.ChatMessageRecord;
import com.nova.link.database.Invitation;
import com.nova.link.database.MessageFilter;
import com.nova.link.database.MuteInfo;
import com.nova.link.database.Notification;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.i18n.I18n;
import com.nova.link.log.MessageLogService;
import com.nova.link.mute.MuteManager;
import com.nova.link.mute.MuteResult;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.notification.NotificationStore;
import com.nova.link.websocket.JwtService;
import io.jsonwebtoken.Claims;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * REST API handler for NovaLink external integration.
 * Provides HTTP endpoints for external systems to interact with NovaLink.
 *
 * <p>Marked {@link ChannelHandler.Sharable @Sharable} because a single instance is
 * shared across every connection's pipeline (see {@code WebSocketServer.initChannel}).
 * The handler holds no per-channel state — only immutable service dependencies — so
 * sharing it is safe. Without {@code @Sharable}, Netty rejects the second connection
 * with {@code ChannelPipelineException: ... is not a @Sharable handler}.
 *
 * Requirements: 25.4 - REST API for external integration
 * Requirements: 25.5 - Webhook support
 */
@ChannelHandler.Sharable
public class RestApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(RestApiHandler.class);

    /** Sentinel operator UUID for admin-originated REST actions (console sentinel). */
    private static final UUID CONSOLE_SENTINEL = new UUID(0L, 0L);

    /**
     * Console commands that MAY be executed via the REST console endpoint
     * (whitelist). Everything else — notably {@code stop}/{@code shutdown}
     * (halt the backend) and {@code spy} (starts live chat monitoring) — is
     * reserved for the real backend console and rejected with 403.
     * Keep in sync with {@link ConsoleCommandHandler#dispatch}.
     */
    private static final Set<String> CONSOLE_WHITELIST = Set.of(
            "help", "?", "status", "players", "clients", "channels", "channel",
            "mute", "unmute", "mutes", "ban", "unban", "bans", "kick",
            "announce", "title", "reload", "spies", "create", "delete");

    private final JwtService jwtService;
    private final AuthManager authManager;
    private final ChannelManager channelManager;
    private final PlayerStateManager playerStateManager;
    private final MessageRouter messageRouter;
    private final WebhookManager webhookManager;
    private final MuteManager muteManager;
    private final BanManager banManager;
    private final InvitationManager invitationManager;
    private final ConfigManager configManager;
    private final ServerNetworkHandler networkHandler;
    private final ConsoleCommandHandler consoleCommandHandler;
    private final NotificationStore notificationStore;
    private final List<String> corsAllowedOrigins;
    private final Gson gson;

    /**
     * Announcement manager (setter-injected after construction to keep the
     * already-long constructor stable). Required for /api/announcements.
     */
    private AnnouncementManager announcementManager;

    /**
     * Message history service (setter-injected). Required for GET /api/messages.
     */
    private MessageLogService messageLogService;

    /**
     * Runtime map of panel operator UUIDs (stable, name-derived) to panel
     * usernames, populated whenever a panel moderation action runs. Used to
     * resolve the operator column in GET /api/mutes and /api/bans; the config
     * (super-admins + panel-users) is consulted as well so attribution
     * survives restarts.
     */
    private final Map<UUID, String> panelOperatorNames = new java.util.concurrent.ConcurrentHashMap<>();

    /** Backward-compatible constructor: CORS allows all origins ("*"). */
    public RestApiHandler(JwtService jwtService, AuthManager authManager,
                          ChannelManager channelManager, PlayerStateManager playerStateManager,
                          MessageRouter messageRouter, WebhookManager webhookManager,
                          MuteManager muteManager, BanManager banManager,
                          InvitationManager invitationManager,
                          ConfigManager configManager, ServerNetworkHandler networkHandler,
                          ConsoleCommandHandler consoleCommandHandler,
                          NotificationStore notificationStore) {
        this(jwtService, authManager, channelManager, playerStateManager, messageRouter,
                webhookManager, muteManager, banManager, invitationManager, configManager,
                networkHandler, consoleCommandHandler, notificationStore, List.of("*"));
    }

    public RestApiHandler(JwtService jwtService, AuthManager authManager,
                          ChannelManager channelManager, PlayerStateManager playerStateManager,
                          MessageRouter messageRouter, WebhookManager webhookManager,
                          MuteManager muteManager, BanManager banManager,
                          InvitationManager invitationManager,
                          ConfigManager configManager, ServerNetworkHandler networkHandler,
                          ConsoleCommandHandler consoleCommandHandler,
                          NotificationStore notificationStore,
                          List<String> corsAllowedOrigins) {
        this.jwtService = jwtService;
        this.authManager = authManager;
        this.channelManager = channelManager;
        this.playerStateManager = playerStateManager;
        this.messageRouter = messageRouter;
        this.webhookManager = webhookManager;
        this.muteManager = muteManager;
        this.banManager = banManager;
        this.invitationManager = invitationManager;
        this.configManager = configManager;
        this.networkHandler = networkHandler;
        this.consoleCommandHandler = consoleCommandHandler;
        this.notificationStore = notificationStore;
        this.corsAllowedOrigins = (corsAllowedOrigins != null && !corsAllowedOrigins.isEmpty())
                ? List.copyOf(corsAllowedOrigins)
                : List.of("*");
        // serializeNulls: contract fields like webhook.lastTriggered and
        // announcement.cron must be emitted as explicit JSON null, not omitted.
        this.gson = new com.google.gson.GsonBuilder().serializeNulls().create();
    }

    /** Wires the announcement manager (called after construction). */
    public void setAnnouncementManager(AnnouncementManager announcementManager) {
        this.announcementManager = announcementManager;
    }

    /** Wires the message history service (called after construction). */
    public void setMessageLogService(MessageLogService messageLogService) {
        this.messageLogService = messageLogService;
    }

    /**
     * Dedicated worker pool for request processing (auth check, business
     * logic, response writing). {@code null} (the default) keeps everything
     * on the calling thread — the mode used by unit tests, which invoke
     * handler methods synchronously ("directExecutor" semantics).
     * Production wiring injects a fixed pool created via {@link #newWorkerPool}.
     */
    private volatile java.util.concurrent.Executor workerExecutor;

    /** Injects the worker executor (package-visible for tests, used by NovaLinkMain). */
    public void setWorkerExecutor(java.util.concurrent.Executor workerExecutor) {
        this.workerExecutor = workerExecutor;
    }

    /**
     * Creates the fixed-size REST worker pool. The queue is bounded so that a
     * flood of slow requests fails fast with 503 instead of accumulating
     * unbounded memory.
     *
     * @param threads pool size (min 1)
     * @return a fixed thread pool with a bounded queue and abort policy
     */
    public static java.util.concurrent.ExecutorService newWorkerPool(int threads) {
        int size = Math.max(1, threads);
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(1);
        return new java.util.concurrent.ThreadPoolExecutor(size, size,
                0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(size * 32),
                r -> {
                    Thread t = new Thread(r, "NovaLink-RestWorker-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String uri = request.uri();
        HttpMethod method = request.method();
        
        // Handle CORS preflight (cheap; stays on the IO thread)
        if (method == HttpMethod.OPTIONS) {
            sendCorsResponse(ctx, request);
            return;
        }
        
        // Check if this is an API request
        if (!uri.startsWith("/api/")) {
            // Pass to next handler (WebSocket, etc.)
            ctx.fireChannelRead(request.retain());
            return;
        }

        // Auth endpoints are handled by HttpAuthHandler further down the
        // pipeline (which offloads its own blocking work). fireChannelRead must
        // stay on the IO thread to preserve pipeline ordering.
        if (uri.startsWith("/api/auth/")) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        java.util.concurrent.Executor executor = workerExecutor;
        if (executor == null) {
            processApiRequest(ctx, request, uri, method);
            return;
        }

        // Offload auth validation + business logic + response writing to the
        // worker pool. The request must be retained: SimpleChannelInboundHandler
        // releases it when channelRead0 returns, while the worker still needs
        // the body. ctx.writeAndFlush is thread-safe.
        request.retain();
        try {
            executor.execute(() -> {
                try {
                    processApiRequest(ctx, request, uri, method);
                } finally {
                    request.release();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            request.release();
            logger.warn("REST worker pool saturated; rejecting {} {}", method, uri);
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    I18n.tr("api.error.server_busy"));
        }
    }

    /**
     * Full request processing after routing dispatch: authentication, RBAC,
     * business logic and response writing. Runs on the worker pool in
     * production, or inline when no executor is configured (tests).
     */
    private void processApiRequest(ChannelHandlerContext ctx, FullHttpRequest request,
                                   String uri, HttpMethod method) {
        try {
            String authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION);
            Claims claims = validateAuth(authHeader);
            if (claims == null) {
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED,
                        I18n.tr("api.error.unauthorized"));
                return;
            }
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf("?")) : uri;
            PanelRole required = requiredRole(path, method);
            if (!hasRole(claims, required)) {
                sendJsonError(ctx, request, HttpResponseStatus.FORBIDDEN,
                        I18n.tr("api.error.forbidden_role", required.name()));
                return;
            }
            routeRequest(ctx, request, uri, method, claims);
        } catch (Exception e) {
            logger.error("API error for {} {}", method, uri, e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        }
    }

    /**
     * RBAC permission matrix (front/back-end contract; role order
     * {@code VIEWER < ADMIN < SUPER_ADMIN}):
     * <ul>
     *   <li><b>VIEWER</b>: every GET endpoint (+ read-only WS), including
     *       GET /api/messages, GET /api/announcements, GET /api/filter and
     *       GET /api/webhooks.</li>
     *   <li><b>ADMIN</b>: VIEWER + player punishments
     *       (POST /api/players/{uuid}/mute|unmute|kick|ban|unban) + channel CRUD
     *       (POST/PUT/DELETE /api/channels*, invite) + POST /api/messages +
     *       notification management (read/read-all/DELETE) +
     *       announcement management (POST/PUT/DELETE /api/announcements*) +
     *       PUT /api/filter.</li>
     *   <li><b>SUPER_ADMIN</b>: ADMIN + POST /api/console +
     *       DELETE /api/clients/{id} + POST /api/reload + PUT /api/settings +
     *       webhook management (POST/DELETE /api/webhooks*,
     *       PUT /api/webhooks/{id}, POST /api/webhooks/{id}/test).</li>
     * </ul>
     * {@code /api/auth/*} stays unauthenticated and never reaches this method.
     *
     * @return the minimum role required for the given path + method
     */
    static PanelRole requiredRole(String path, HttpMethod method) {
        if (method == HttpMethod.GET) {
            return PanelRole.VIEWER;
        }
        // SUPER_ADMIN-only mutations
        if (path.equals("/api/console") && method == HttpMethod.POST) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.matches("/api/clients/[^/]+") && method == HttpMethod.DELETE) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.equals("/api/reload") && method == HttpMethod.POST) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.equals("/api/settings") && method == HttpMethod.PUT) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.equals("/api/webhooks") && method == HttpMethod.POST) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.matches("/api/webhooks/[^/]+") && (method == HttpMethod.DELETE || method == HttpMethod.PUT)) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.matches("/api/webhooks/[^/]+/test") && method == HttpMethod.POST) {
            return PanelRole.SUPER_ADMIN;
        }
        // Every other mutation (player punishments, channel CRUD + invite,
        // messages, notification management, announcements, filter) requires ADMIN.
        return PanelRole.ADMIN;
    }

    /**
     * @return true when the token's role claim satisfies the required minimum.
     *         Unknown/legacy roles (e.g. CLIENT_ADMIN) never satisfy any level.
     */
    private static boolean hasRole(Claims claims, PanelRole required) {
        PanelRole actual = PanelRole.fromString(claims.get("role", String.class));
        return actual != null && actual.atLeast(required);
    }

    /**
     * @return the panel username carried in the JWT, or null
     */
    private static String panelUsername(Claims claims) {
        return claims != null ? claims.get("username", String.class) : null;
    }

    /**
     * Stable, name-derived operator UUID for panel-originated moderation
     * actions: {@code UUID.nameUUIDFromBytes("panel:" + username)}. The same
     * panel account always maps to the same UUID, so GET /api/mutes|bans can
     * attribute operations across restarts. Falls back to the console sentinel
     * when no username is available (should not happen for valid tokens).
     */
    private UUID panelOperatorUuid(String username) {
        if (username == null || username.isBlank()) {
            return CONSOLE_SENTINEL;
        }
        UUID uuid = UUID.nameUUIDFromBytes(("panel:" + username)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        panelOperatorNames.put(uuid, username);
        return uuid;
    }

    /**
     * Resolves the human-readable operator name for moderation listings:
     * console sentinel → "console"; panel-derived UUIDs → panel username
     * (runtime map first, then the configured panel accounts); otherwise the
     * player name or null.
     */
    private String resolveOperatorName(UUID operatorId) {
        if (operatorId == null) {
            return null;
        }
        if (operatorId.getMostSignificantBits() == 0L && operatorId.getLeastSignificantBits() == 0L) {
            return "console";
        }
        String runtime = panelOperatorNames.get(operatorId);
        if (runtime != null) {
            return runtime;
        }
        // Config-derived reverse lookup (covers panel operations before restart).
        try {
            if (configManager != null && configManager.getConfig() != null) {
                com.nova.link.config.NovaLinkConfig cfg = configManager.getConfig();
                if (cfg.getSuperAdmins() != null) {
                    for (com.nova.link.auth.SuperAdminCredentials admin : cfg.getSuperAdmins()) {
                        String username = admin.getUsername() != null && !admin.getUsername().isBlank()
                                ? admin.getUsername()
                                : (admin.getUuid() != null ? admin.getUuid().toString() : null);
                        if (username != null && panelOperatorUuidQuiet(username).equals(operatorId)) {
                            panelOperatorNames.put(operatorId, username);
                            return username;
                        }
                    }
                }
                if (cfg.getPanelUsers() != null) {
                    for (com.nova.link.config.PanelUserConfig user : cfg.getPanelUsers()) {
                        if (user.getUsername() != null
                                && panelOperatorUuidQuiet(user.getUsername()).equals(operatorId)) {
                            panelOperatorNames.put(operatorId, user.getUsername());
                            return user.getUsername();
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve operator name for {}: {}", operatorId, e.getMessage());
        }
        return resolvePlayerName(operatorId);
    }

    /** Like {@link #panelOperatorUuid} but without mutating the runtime map. */
    private static UUID panelOperatorUuidQuiet(String username) {
        return UUID.nameUUIDFromBytes(("panel:" + username)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Routes API requests to appropriate handlers. The caller has already
     * authenticated the request and enforced the RBAC matrix; {@code claims}
     * carries the operator identity for attribution (null only for /api/auth/*).
     */
    private void routeRequest(ChannelHandlerContext ctx, FullHttpRequest request,
                              String uri, HttpMethod method, Claims claims) {
        // Remove query parameters for routing
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf("?")) : uri;

        // Channel endpoints
        if (path.equals("/api/channels") && method == HttpMethod.GET) {
            handleGetChannels(ctx, request);
        } else if (path.equals("/api/channels") && method == HttpMethod.POST) {
            handleCreateChannel(ctx, request);
        } else if (path.matches("/api/channels/[^/]+") && method == HttpMethod.GET) {
            String channelId = path.substring("/api/channels/".length());
            handleGetChannel(ctx, request, channelId);
        } else if (path.matches("/api/channels/[^/]+") && method == HttpMethod.DELETE) {
            String channelId = path.substring("/api/channels/".length());
            handleDeleteChannel(ctx, request, channelId);
        } else if (path.matches("/api/channels/[^/]+") && method == HttpMethod.PUT) {
            String channelId = path.substring("/api/channels/".length());
            handleUpdateChannel(ctx, request, channelId);
        } else if (path.matches("/api/channels/[^/]+/members") && method == HttpMethod.GET) {
            String channelId = path.substring("/api/channels/".length(), path.lastIndexOf("/members"));
            handleGetChannelMembers(ctx, request, channelId);
        } else if (path.matches("/api/channels/[^/]+/invite") && method == HttpMethod.POST) {
            String channelId = path.substring("/api/channels/".length(), path.lastIndexOf("/invite"));
            handleInviteChannel(ctx, request, channelId);
        }
        // Message endpoints
        else if (path.equals("/api/messages") && method == HttpMethod.POST) {
            handleSendMessage(ctx, request);
        } else if (path.equals("/api/messages") && method == HttpMethod.GET) {
            handleGetMessages(ctx, request);
        }
        // Announcement endpoints
        else if (path.equals("/api/announcements") && method == HttpMethod.GET) {
            handleGetAnnouncements(ctx, request);
        } else if (path.equals("/api/announcements") && method == HttpMethod.POST) {
            handleCreateAnnouncement(ctx, request, claims);
        } else if (path.matches("/api/announcements/[^/]+") && method == HttpMethod.PUT) {
            String announcementId = path.substring("/api/announcements/".length());
            handleUpdateAnnouncement(ctx, request, announcementId);
        } else if (path.matches("/api/announcements/[^/]+") && method == HttpMethod.DELETE) {
            String announcementId = path.substring("/api/announcements/".length());
            handleDeleteAnnouncement(ctx, request, announcementId);
        }
        // Sensitive-word filter endpoints
        else if (path.equals("/api/filter") && method == HttpMethod.GET) {
            handleGetFilter(ctx, request);
        } else if (path.equals("/api/filter") && method == HttpMethod.PUT) {
            handleUpdateFilter(ctx, request);
        }
        // Player endpoints
        else if (path.equals("/api/players") && method == HttpMethod.GET) {
            handleGetPlayers(ctx, request);
        } else if (path.matches("/api/players/[^/]+") && method == HttpMethod.GET) {
            String playerId = path.substring("/api/players/".length());
            handleGetPlayer(ctx, request, playerId);
        } else if (path.matches("/api/players/[^/]+/mute") && method == HttpMethod.POST) {
            String playerId = path.substring("/api/players/".length(), path.lastIndexOf("/mute"));
            handleMutePlayer(ctx, request, playerId, panelUsername(claims));
        } else if (path.matches("/api/players/[^/]+/unmute") && method == HttpMethod.POST) {
            String playerId = path.substring("/api/players/".length(), path.lastIndexOf("/unmute"));
            handleUnmutePlayer(ctx, request, playerId, panelUsername(claims));
        } else if (path.matches("/api/players/[^/]+/kick") && method == HttpMethod.POST) {
            String playerId = path.substring("/api/players/".length(), path.lastIndexOf("/kick"));
            handleKickPlayer(ctx, request, playerId, panelUsername(claims));
        }
        // Ban endpoints
        else if (path.matches("/api/players/[^/]+/ban") && method == HttpMethod.POST) {
            String playerId = path.substring("/api/players/".length(), path.lastIndexOf("/ban"));
            handleBanPlayer(ctx, request, playerId, panelUsername(claims));
        } else if (path.matches("/api/players/[^/]+/unban") && method == HttpMethod.POST) {
            String playerId = path.substring("/api/players/".length(), path.lastIndexOf("/unban"));
            handleUnbanPlayer(ctx, request, playerId, panelUsername(claims));
        }
        // Mutes listing endpoint
        else if (path.equals("/api/mutes") && method == HttpMethod.GET) {
            handleGetMutes(ctx, request);
        }
        // Bans listing endpoint
        else if (path.equals("/api/bans") && method == HttpMethod.GET) {
            handleGetBans(ctx, request);
        }
        // Webhook endpoints
        else if (path.equals("/api/webhooks") && method == HttpMethod.GET) {
            handleGetWebhooks(ctx, request);
        } else if (path.equals("/api/webhooks") && method == HttpMethod.POST) {
            handleCreateWebhook(ctx, request);
        } else if (path.matches("/api/webhooks/[^/]+") && method == HttpMethod.DELETE) {
            String webhookId = path.substring("/api/webhooks/".length());
            handleDeleteWebhook(ctx, request, webhookId);
        } else if (path.matches("/api/webhooks/[^/]+") && method == HttpMethod.PUT) {
            String webhookId = path.substring("/api/webhooks/".length());
            handleUpdateWebhook(ctx, request, webhookId);
        } else if (path.matches("/api/webhooks/[^/]+/test") && method == HttpMethod.POST) {
            String webhookId = path.substring("/api/webhooks/".length(), path.lastIndexOf("/test"));
            handleTestWebhook(ctx, request, webhookId);
        }
        // Client disconnect endpoint
        else if (path.matches("/api/clients/[^/]+") && method == HttpMethod.DELETE) {
            String clientId = path.substring("/api/clients/".length());
            handleDisconnectClient(ctx, request, clientId);
        }
        // Config reload endpoint
        else if (path.equals("/api/reload") && method == HttpMethod.POST) {
            handleReload(ctx, request);
        }
        // Settings endpoints (FeatureConfig)
        else if (path.equals("/api/settings") && method == HttpMethod.GET) {
            handleGetSettings(ctx, request);
        } else if (path.equals("/api/settings") && method == HttpMethod.PUT) {
            handleUpdateSettings(ctx, request);
        }
        // Notification endpoints
        else if (path.equals("/api/notifications") && method == HttpMethod.GET) {
            handleGetNotifications(ctx, request);
        } else if (path.matches("/api/notifications/[^/]+/read") && method == HttpMethod.POST) {
            String idStr = path.substring("/api/notifications/".length(), path.lastIndexOf("/read"));
            handleMarkNotificationRead(ctx, request, idStr);
        } else if (path.equals("/api/notifications/read-all") && method == HttpMethod.POST) {
            handleMarkAllNotificationsRead(ctx, request);
        } else if (path.equals("/api/notifications") && method == HttpMethod.DELETE) {
            handleClearNotifications(ctx, request);
        }
        // Console command execution endpoint
        else if (path.equals("/api/console") && method == HttpMethod.POST) {
            handleConsoleCommand(ctx, request);
        }
        // Status endpoint
        else if (path.equals("/api/status") && method == HttpMethod.GET) {
            handleGetStatus(ctx, request);
        }
        // Auth endpoints (handled by HttpAuthHandler, but we pass through)
        else if (path.startsWith("/api/auth/")) {
            ctx.fireChannelRead(request.retain());
        }
        // Not found
        else {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Endpoint not found");
        }
    }

    /**
     * Validates the authorization header.
     *
     * @return the verified token claims, or null when the header is missing,
     *         the token is invalid/revoked, a refresh token, or lacks the
     *         expected identity claims
     */
    private Claims validateAuth(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        Claims claims = jwtService.validateToken(token);
        if (claims == null) {
            return null;
        }
        // Refresh tokens must NOT be accepted as API bearer tokens.
        String tokenType = claims.get("type", String.class);
        if ("refresh".equals(tokenType)) {
            return null;
        }
        // Basic claim sanity (helps reject tokens from other issuers/tools).
        String subject = claims.getSubject();
        String username = claims.get("username", String.class);
        String role = claims.get("role", String.class);
        boolean sane = subject != null && !subject.isBlank()
                && username != null && !username.isBlank()
                && role != null && !role.isBlank();
        return sane ? claims : null;
    }

    /**
     * GET /api/channels - List all channels
     * Requirements: 25.4
     */
    private void handleGetChannels(ChannelHandlerContext ctx, FullHttpRequest request) {
        JsonArray channels = new JsonArray();
        for (Channel channel : channelManager.getAllChannels()) {
            channels.add(channelToJson(channel));
        }
        
        JsonObject response = new JsonObject();
        response.add("channels", channels);
        response.addProperty("total", channels.size());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/channels/{id} - Get channel details
     * Requirements: 25.4
     */
    private void handleGetChannel(ChannelHandlerContext ctx, FullHttpRequest request, String channelId) {
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, channelToJson(channel));
    }

    /**
     * GET /api/channels/{id}/members - Get channel members
     * Requirements: 25.4
     */
    private void handleGetChannelMembers(ChannelHandlerContext ctx, FullHttpRequest request, String channelId) {
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }
        
        JsonArray members = new JsonArray();
        for (UUID memberId : channel.getMembers()) {
            JsonObject member = new JsonObject();
            member.addProperty("uuid", memberId.toString());
            
            // Try to get player name from state manager
            PlayerState state = playerStateManager.getPlayerState(memberId);
            if (state != null) {
                member.addProperty("name", state.getPlayerName());
            }
            members.add(member);
        }
        
        JsonObject response = new JsonObject();
        response.add("members", members);
        response.addProperty("total", members.size());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/channels - Create a new channel (admin operation).
     *
     * <p>REST channel creation is a super-admin operation and is not restricted
     * to private channels (unlike {@code ChannelActionHandler.handleCreate}).
     * Private channels without an explicit id get an auto-generated NC-XXXX id.
     * Requirements: 25.4
     */
    private void handleCreateChannel(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            String displayName = json.has("displayName") && !json.get("displayName").isJsonNull()
                    ? json.get("displayName").getAsString() : null;
            String scopeRaw = json.has("scope") && !json.get("scope").isJsonNull()
                    ? json.get("scope").getAsString() : "global";
            String id = json.has("id") && !json.get("id").isJsonNull()
                    ? json.get("id").getAsString() : null;
            int maxCapacity = json.has("maxCapacity") && !json.get("maxCapacity").isJsonNull()
                    ? json.get("maxCapacity").getAsInt() : 100;
            String permission = json.has("permission") && !json.get("permission").isJsonNull()
                    ? json.get("permission").getAsString() : null;

            ChannelScope scope;
            try {
                scope = ChannelScope.valueOf(scopeRaw.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid scope: " + scopeRaw);
                return;
            }

            // PRIVATE channels require a clientId; for REST admin creation of a
            // private channel, bind it to a synthetic "console" client (mirrors
            // ConsoleCommandHandler.handleCreate private branch). For PRIVATE
            // channels, ChannelManager.createChannel auto-generates an NC-XXXX id
            // when none is supplied. For GLOBAL/SERVER scopes the Channel ctor
            // rejects a null id, so auto-generate one here as well — REST admin
            // creation should not require a caller-supplied id for any scope.
            String clientId = null;
            if (scope == ChannelScope.PRIVATE) {
                clientId = "console";
            } else if (id == null || id.isEmpty()) {
                id = generateRestChannelId();
            }

            // Fall back to the generated/id-derived name when no display name given.
            String effectiveDisplayName = displayName != null ? displayName : id;

            ChannelConfig config = ChannelConfig.builder()
                    .id(id)
                    .displayName(effectiveDisplayName)
                    .scope(scope)
                    .clientId(clientId)
                    .maxCapacity(maxCapacity)
                    .permission(permission)
                    .build();

            Channel channel = channelManager.createChannel(config);

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("channel", channelToJson(channel));

            sendJsonResponse(ctx, request, HttpResponseStatus.CREATED, response);

        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            logger.error("Error creating channel via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        }
    }

    /**
     * DELETE /api/channels/{id} - Delete a channel (admin operation).
     *
     * <p>Removes all members first (clears membership side-effects), mirroring
     * {@code ConsoleCommandHandler.handleDelete}.
     * Requirements: 25.4
     */
    private void handleDeleteChannel(ChannelHandlerContext ctx, FullHttpRequest request, String channelId) {
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        // Remove all members first (clear membership side-effects).
        for (UUID m : new java.util.ArrayList<>(channelManager.getChannelMembers(channelId))) {
            channelManager.removeMember(channelId, m);
            try {
                playerStateManager.leaveChannel(m, channelId);
            } catch (Exception ignored) {
                // non-fatal
            }
        }

        boolean deleted = channelManager.deleteChannel(channelId);
        if (!deleted) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Channel deleted successfully");
        response.addProperty("channelId", channelId);

        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * PUT /api/channels/{id} - Update a channel's mutable properties.
     *
     * <p>Only non-null body fields are applied. Mirrors
     * {@code NovaLinkMain.upsertConfiguredChannel} field-by-field update.
     * Requirements: 25.4
     */
    private void handleUpdateChannel(ChannelHandlerContext ctx, FullHttpRequest request, String channelId) {
        Channel existing = channelManager.getChannel(channelId);
        if (existing == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            String displayName = json.has("displayName") && !json.get("displayName").isJsonNull()
                    ? json.get("displayName").getAsString() : null;
            Integer maxCapacity = null;
            if (json.has("maxCapacity") && !json.get("maxCapacity").isJsonNull()) {
                maxCapacity = json.get("maxCapacity").getAsInt();
            }
            String permission = json.has("permission") && !json.get("permission").isJsonNull()
                    ? json.get("permission").getAsString() : null;
            Integer slowModeSeconds = null;
            if (json.has("slowModeSeconds") && !json.get("slowModeSeconds").isJsonNull()) {
                slowModeSeconds = json.get("slowModeSeconds").getAsInt();
                if (slowModeSeconds < 0) {
                    sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                            "slowModeSeconds must be >= 0");
                    return;
                }
            }

            Channel updated = channelManager.updateChannel(channelId, displayName, maxCapacity, permission);
            if (updated == null) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
                return;
            }
            if (slowModeSeconds != null) {
                updated.setSlowModeSeconds(slowModeSeconds);
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("channel", channelToJson(updated));

            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

        } catch (Exception e) {
            logger.error("Error updating channel via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        }
    }

    /**
     * POST /api/channels/{id}/invite - Create an invitation for a channel.
     *
     * <p>Uses the console sentinel as the inviter. Returns the generated code.
     * Requirements: 25.4
     */
    private void handleInviteChannel(ChannelHandlerContext ctx, FullHttpRequest request, String channelId) {
        if (invitationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Invitations not enabled");
            return;
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        long ttlMillis = InvitationManager.DEFAULT_TTL_MILLIS;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("ttlMillis") && !json.get("ttlMillis").isJsonNull()) {
                    ttlMillis = json.get("ttlMillis").getAsLong();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        try {
            Invitation invitation = invitationManager.createInvitation(channelId, CONSOLE_SENTINEL, ttlMillis);
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("code", invitation.getCode());
            response.addProperty("channelId", invitation.getChannelId());
            response.addProperty("expireTime", invitation.getExpireTime());
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            logger.error("Error creating invitation via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "NC-510: Database error");
        }
    }

    /**
     * POST /api/players/{uuid}/mute - Mute a player.
     *
     * <p>The operator is the panel account from the JWT (stable name-derived
     * UUID, RBAC pre-authorized via the trusted-operator path) so panel
     * moderation is attributable. Mirrors {@code ConsoleCommandHandler.handleMute}.
     * Requirements: 25.4
     */
    private void handleMutePlayer(ChannelHandlerContext ctx, FullHttpRequest request, String playerId,
                                  String operatorUsername) {
        if (muteManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Mute system not enabled");
            return;
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(playerId);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        String channelId = null;
        long durationMs = 0;
        String reason = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("channelId") && !json.get("channelId").isJsonNull()) {
                    channelId = json.get("channelId").getAsString();
                }
                if (json.has("durationMs") && !json.get("durationMs").isJsonNull()) {
                    durationMs = json.get("durationMs").getAsLong();
                }
                if (json.has("reason") && !json.get("reason").isJsonNull()) {
                    reason = json.get("reason").getAsString();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        if (channelId != null && !channelManager.channelExists(channelId)) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        MuteResult result = muteManager.mutePlayer(panelOperatorUuid(operatorUsername), targetUuid,
                channelId, durationMs, reason, null, true);
        if (!result.isSuccess()) {
            HttpResponseStatus status = "NC-404".equals(result.getErrorCode())
                    ? HttpResponseStatus.NOT_FOUND
                    : ("NC-403".equals(result.getErrorCode())
                        ? HttpResponseStatus.FORBIDDEN
                        : HttpResponseStatus.BAD_REQUEST);
            sendJsonError(ctx, request, status,
                    result.getErrorCode() != null ? result.getErrorCode() + ": " + result.getMessage()
                            : result.getMessage());
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Player muted successfully");
        response.addProperty("playerId", targetUuid.toString());
        if (channelId != null) {
            response.addProperty("channelId", channelId);
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/players/{uuid}/unmute - Unmute a player.
     *
     * <p>Operator attribution mirrors {@code handleMutePlayer}. Mirrors
     * {@code ConsoleCommandHandler.handleUnmute}.
     * Requirements: 25.4
     */
    private void handleUnmutePlayer(ChannelHandlerContext ctx, FullHttpRequest request, String playerId,
                                    String operatorUsername) {
        if (muteManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Mute system not enabled");
            return;
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(playerId);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        String channelId = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("channelId") && !json.get("channelId").isJsonNull()) {
                    channelId = json.get("channelId").getAsString();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        MuteResult result = muteManager.unmutePlayer(panelOperatorUuid(operatorUsername), targetUuid,
                channelId, null, true);
        if (!result.isSuccess()) {
            HttpResponseStatus status = "NC-404".equals(result.getErrorCode())
                    ? HttpResponseStatus.NOT_FOUND
                    : ("NC-403".equals(result.getErrorCode())
                        ? HttpResponseStatus.FORBIDDEN
                        : HttpResponseStatus.BAD_REQUEST);
            sendJsonError(ctx, request, status,
                    result.getErrorCode() != null ? result.getErrorCode() + ": " + result.getMessage()
                            : result.getMessage());
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Player unmuted successfully");
        response.addProperty("playerId", targetUuid.toString());
        if (channelId != null) {
            response.addProperty("channelId", channelId);
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/mutes - List all active mutes.
     *
     * <p>Iterates the MuteManager cache (warmed from the database at startup),
     * so mutes of offline players are visible and can be lifted from the panel.
     * Requirements: 25.4
     */
    private void handleGetMutes(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (muteManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Mute system not enabled");
            return;
        }

        JsonArray mutes = new JsonArray();
        for (Map.Entry<UUID, List<MuteInfo>> playerEntry : muteManager.getAllActiveMutes().entrySet()) {
            UUID playerId = playerEntry.getKey();
            String playerName = resolvePlayerName(playerId);
            for (MuteInfo m : playerEntry.getValue()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("playerId", playerId.toString());
                entry.addProperty("playerName", playerName != null ? playerName : playerId.toString());
                entry.addProperty("channelId", m.getChannelId() != null ? m.getChannelId() : "(global)");
                entry.addProperty("reason", m.getReason());
                entry.addProperty("expireTime", m.getExpireTime());
                entry.addProperty("remainingMs", m.getRemainingTime());
                entry.addProperty("permanent", m.isPermanent());
                if (m.getOperatorId() != null) {
                    entry.addProperty("operatorId", m.getOperatorId().toString());
                    String operatorName = resolveOperatorName(m.getOperatorId());
                    entry.addProperty("operator", operatorName != null
                            ? operatorName : m.getOperatorId().toString());
                }
                mutes.add(entry);
            }
        }

        JsonObject response = new JsonObject();
        response.add("mutes", mutes);
        response.addProperty("total", mutes.size());

        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * Resolves a player's display name: online cache first, then a read-only
     * database lookup for offline players (does not pollute the online cache).
     *
     * @return the player name, or null when unknown
     */
    private String resolvePlayerName(UUID playerId) {
        if (playerStateManager == null || playerId == null) {
            return null;
        }
        PlayerState cached = playerStateManager.getPlayerState(playerId);
        if (cached != null && cached.getPlayerName() != null) {
            return cached.getPlayerName();
        }
        try {
            if (playerStateManager.getDatabaseProvider() != null) {
                return playerStateManager.getDatabaseProvider().loadPlayerState(playerId)
                        .map(PlayerState::getPlayerName)
                        .orElse(null);
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve name for player {}: {}", playerId, e.getMessage());
        }
        return null;
    }

    /**
     * POST /api/players/{uuid}/ban - Ban a player.
     *
     * <p>Operator attribution mirrors {@code handleMutePlayer}.
     * Body: {channelId?, durationMs, reason}
     */
    private void handleBanPlayer(ChannelHandlerContext ctx, FullHttpRequest request, String playerId,
                                 String operatorUsername) {
        if (banManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Ban system not enabled");
            return;
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(playerId);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        String channelId = null;
        long durationMs = 0;
        String reason = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("channelId") && !json.get("channelId").isJsonNull()) {
                    channelId = json.get("channelId").getAsString();
                }
                if (json.has("durationMs") && !json.get("durationMs").isJsonNull()) {
                    durationMs = json.get("durationMs").getAsLong();
                }
                if (json.has("reason") && !json.get("reason").isJsonNull()) {
                    reason = json.get("reason").getAsString();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        if (channelId != null && !channelManager.channelExists(channelId)) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        BanResult result = banManager.banPlayer(panelOperatorUuid(operatorUsername), targetUuid,
                channelId, durationMs, reason, null, true);
        if (!result.isSuccess()) {
            HttpResponseStatus status = "NC-404".equals(result.getErrorCode())
                    ? HttpResponseStatus.NOT_FOUND
                    : ("NC-403".equals(result.getErrorCode())
                        ? HttpResponseStatus.FORBIDDEN
                        : HttpResponseStatus.BAD_REQUEST);
            sendJsonError(ctx, request, status,
                    result.getErrorCode() != null ? result.getErrorCode() + ": " + result.getMessage()
                            : result.getMessage());
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Player banned successfully");
        response.addProperty("playerId", targetUuid.toString());
        if (channelId != null) {
            response.addProperty("channelId", channelId);
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/players/{uuid}/unban - Unban a player.
     *
     * <p>Operator attribution mirrors {@code handleUnmutePlayer}.
     * Body: {channelId?}
     */
    private void handleUnbanPlayer(ChannelHandlerContext ctx, FullHttpRequest request, String playerId,
                                   String operatorUsername) {
        if (banManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Ban system not enabled");
            return;
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(playerId);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        String channelId = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("channelId") && !json.get("channelId").isJsonNull()) {
                    channelId = json.get("channelId").getAsString();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        BanResult result = banManager.unbanPlayer(panelOperatorUuid(operatorUsername), targetUuid,
                channelId, null, true);
        if (!result.isSuccess()) {
            HttpResponseStatus status = "NC-404".equals(result.getErrorCode())
                    ? HttpResponseStatus.NOT_FOUND
                    : ("NC-403".equals(result.getErrorCode())
                        ? HttpResponseStatus.FORBIDDEN
                        : HttpResponseStatus.BAD_REQUEST);
            sendJsonError(ctx, request, status,
                    result.getErrorCode() != null ? result.getErrorCode() + ": " + result.getMessage()
                            : result.getMessage());
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Player unbanned successfully");
        response.addProperty("playerId", targetUuid.toString());
        if (channelId != null) {
            response.addProperty("channelId", channelId);
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/bans - List all active bans.
     *
     * <p>Iterates the BanManager cache (warmed from the database at startup),
     * so bans of offline players are visible and can be lifted from the panel.
     *
     * <p>Returns a DIRECT JSON array (not wrapped in {bans:...}) to match the
     * frontend contract: the panel checks {@code Array.isArray(bansRes)} and
     * would render an empty list if this were wrapped. Each entry groups a
     * player's bans into a nested {@code bans} array.
     */
    private void handleGetBans(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (banManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Ban system not enabled");
            return;
        }

        JsonArray bans = new JsonArray();
        for (Map.Entry<UUID, List<BanInfo>> playerEntry : banManager.getAllActiveBans().entrySet()) {
            List<BanInfo> playerBans = playerEntry.getValue();
            if (playerBans.isEmpty()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("playerId", playerEntry.getKey().toString());
            String playerName = resolvePlayerName(playerEntry.getKey());
            if (playerName != null) {
                entry.addProperty("name", playerName);
            }
            JsonArray playerBanList = new JsonArray();
            for (BanInfo b : playerBans) {
                JsonObject banObj = new JsonObject();
                // channelId literal null means a global ban — emit JSON null.
                if (b.getChannelId() != null) {
                    banObj.addProperty("channelId", b.getChannelId());
                } else {
                    banObj.add("channelId", com.google.gson.JsonNull.INSTANCE);
                }
                banObj.addProperty("expireTime", b.getExpireTime());
                banObj.addProperty("reason", b.getReason());
                if (b.getOperatorId() != null) {
                    banObj.addProperty("operatorId", b.getOperatorId().toString());
                    String operatorName = resolveOperatorName(b.getOperatorId());
                    if (operatorName != null) {
                        banObj.addProperty("operatorName", operatorName);
                    }
                } else {
                    banObj.add("operatorId", com.google.gson.JsonNull.INSTANCE);
                }
                banObj.addProperty("createdAt", b.getCreatedAt());
                playerBanList.add(banObj);
            }
            entry.add("bans", playerBanList);
            bans.add(entry);
        }

        // Direct array — no outer wrapper. Matches frontend contract.
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, bans);
    }

    /**
     * POST /api/players/{uuid}/kick - Kick a player from a channel.
     *
     * <p>Mirrors {@code ChannelActionHandler.handleKick}: remove member + update
     * player state. The panel RBAC matrix already authorized the caller; the
     * operator username is recorded in the kick notification for attribution.
     * Requirements: 25.4
     */
    private void handleKickPlayer(ChannelHandlerContext ctx, FullHttpRequest request, String playerId,
                                  String operatorUsername) {
        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(playerId);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        String channelId = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("channelId") && !json.get("channelId").isJsonNull()) {
                    channelId = json.get("channelId").getAsString();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        if (channelId == null || channelId.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "channelId is required");
            return;
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        if (!channel.isMember(targetUuid)) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "NC-433: Target is not in channel");
            return;
        }

        channelManager.removeMember(channelId, targetUuid);
        try {
            playerStateManager.leaveChannel(targetUuid, channelId);
        } catch (Exception e) {
            logger.debug("Failed to update player state after kick for {}: {}", targetUuid, e.getMessage());
        }

        if (notificationStore != null) {
            try {
                String operatorSuffix = operatorUsername != null && !operatorUsername.isBlank()
                        ? " by panel:" + operatorUsername
                        : "";
                notificationStore.createNotification(
                        "Player Kicked",
                        "Player " + targetUuid + " kicked from " + channelId + operatorSuffix,
                        "warning");
            } catch (Exception e) {
                logger.debug("Failed to create kick notification: {}", e.getMessage());
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Player kicked");
        response.addProperty("playerId", targetUuid.toString());
        response.addProperty("channelId", channelId);

        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * DELETE /api/clients/{clientId} - Disconnect a game-server TCP client.
     *
     * <p>Finds the connection by clientId and closes it. WS panel sessions are
     * not tracked by clientId here, so only game-server TCP clients are affected.
     * Requirements: 25.4
     */
    private void handleDisconnectClient(ChannelHandlerContext ctx, FullHttpRequest request, String clientId) {
        if (networkHandler == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Network handler not available");
            return;
        }

        ClientConnection connection = networkHandler.findByClientId(clientId);
        if (connection == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Client not found");
            return;
        }

        connection.close();
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Client disconnect initiated");
        response.addProperty("clientId", clientId);

        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/reload - Trigger a configuration reload.
     *
     * <p>Mirrors {@code ConsoleCommandHandler.handleReload}.
     * Requirements: 25.4
     */
    private void handleReload(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (configManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config manager not available");
            return;
        }

        try {
            configManager.triggerReload();
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Configuration reloaded successfully");
            response.addProperty("reloadCount", configManager.getReloadCount());
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error reloading config via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-500: Reload failed: " + e.getMessage());
        }
    }

    /**
     * GET /api/settings - Returns the FeatureConfig switches.
     *
     * <p>Returns {filterEnabled, messageLogEnabled, crossServerChatEnabled}.
     */
    private void handleGetSettings(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (configManager == null || configManager.getConfig() == null
                || configManager.getConfig().getFeatures() == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Settings not available");
            return;
        }
        com.nova.link.config.FeatureConfig features = configManager.getConfig().getFeatures();
        JsonObject response = new JsonObject();
        response.addProperty("filterEnabled", features.isFilterEnabled());
        response.addProperty("messageLogEnabled", features.isMessageLogEnabled());
        response.addProperty("crossServerChatEnabled", features.isCrossServerChatEnabled());
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * PUT /api/settings - Updates the FeatureConfig switches.
     *
     * <p>Body: {filterEnabled?, messageLogEnabled?, crossServerChatEnabled?}.
     * Only present fields are applied. Persists via configManager.save() and
     * applies to runtime immediately so the panel toggle is effective without
     * a full reload.
     */
    private void handleUpdateSettings(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (configManager == null || configManager.getConfig() == null
                || configManager.getConfig().getFeatures() == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Settings not available");
            return;
        }

        com.nova.link.config.FeatureConfig features = configManager.getConfig().getFeatures();
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (body != null && !body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("filterEnabled") && !json.get("filterEnabled").isJsonNull()) {
                    features.setFilterEnabled(json.get("filterEnabled").getAsBoolean());
                }
                if (json.has("messageLogEnabled") && !json.get("messageLogEnabled").isJsonNull()) {
                    features.setMessageLogEnabled(json.get("messageLogEnabled").getAsBoolean());
                }
                if (json.has("crossServerChatEnabled") && !json.get("crossServerChatEnabled").isJsonNull()) {
                    features.setCrossServerChatEnabled(json.get("crossServerChatEnabled").getAsBoolean());
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        // Apply to runtime immediately.
        applyFeatureConfig(features);

        // Persist to disk.
        try {
            configManager.save();
        } catch (Exception e) {
            logger.error("Error persisting settings via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-500: Settings apply succeeded but persist failed: " + e.getMessage());
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("filterEnabled", features.isFilterEnabled());
        response.addProperty("messageLogEnabled", features.isMessageLogEnabled());
        response.addProperty("crossServerChatEnabled", features.isCrossServerChatEnabled());
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * Applies the FeatureConfig switches to the live runtime components,
     * mirroring the reload listener in {@code NovaLinkMain}. Deliberately does
     * NOT trigger a config reload: reload re-reads the file from disk and would
     * discard the in-memory changes just made by the settings endpoint.
     */
    private void applyFeatureConfig(com.nova.link.config.FeatureConfig features) {
        if (features == null || messageRouter == null) {
            return;
        }
        com.nova.link.channel.MessagePipeline pipeline = messageRouter.getPipeline();
        pipeline.setCrossServerChatEnabled(features.isCrossServerChatEnabled());
        pipeline.setMessageLogEnabled(features.isMessageLogEnabled());
        com.nova.link.filter.SensitiveWordFilter filter = pipeline.getSensitiveWordFilter();
        if (filter != null) {
            filter.setEnabled(features.isFilterEnabled());
        }
    }

    // ==================== Notification Endpoints ====================

    /**
     * GET /api/notifications - List notifications with pagination.
     *
     * <p>Query params: page (1-based, default 1), size (default 20), unreadOnly
     * (true/false, default false).
     * Returns {items:[...], total, unreadCount}.
     */
    private void handleGetNotifications(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (notificationStore == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Notifications not enabled");
            return;
        }

        String uri = request.uri();
        int page = 1;
        int size = 20;
        boolean unreadOnly = false;
        int q = uri.indexOf('?');
        if (q >= 0) {
            String query = uri.substring(q + 1);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) continue;
                String key = kv[0];
                String value = kv[1];
                try {
                    switch (key) {
                        case "page" -> page = Math.max(1, Integer.parseInt(value));
                        case "size" -> size = Math.max(1, Integer.parseInt(value));
                        case "unreadOnly" -> unreadOnly = Boolean.parseBoolean(value);
                    }
                } catch (NumberFormatException ignored) {
                    // keep defaults
                }
            }
        }
        int offset = (page - 1) * size;

        List<Notification> notifications = notificationStore.getNotifications(offset, size, unreadOnly);
        int unreadCount = notificationStore.getUnreadCount();
        // Real total of matching records (NOT the current page size) so the
        // panel can decide whether more pages exist.
        int total = notificationStore.count(unreadOnly);

        JsonArray items = new JsonArray();
        for (Notification n : notifications) {
            JsonObject item = new JsonObject();
            item.addProperty("id", n.getId());
            item.addProperty("title", n.getTitle());
            item.addProperty("message", n.getMessage());
            item.addProperty("level", n.getLevel());
            item.addProperty("createdAt", n.getCreatedAt());
            item.addProperty("read", n.isRead());
            items.add(item);
        }

        JsonObject response = new JsonObject();
        response.add("items", items);
        response.addProperty("page", page);
        response.addProperty("pageSize", size);
        response.addProperty("total", total);
        response.addProperty("unreadCount", unreadCount);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/notifications/{id}/read - Mark a single notification as read.
     */
    private void handleMarkNotificationRead(ChannelHandlerContext ctx, FullHttpRequest request, String idStr) {
        if (notificationStore == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Notifications not enabled");
            return;
        }
        long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid notification id");
            return;
        }
        notificationStore.markRead(id);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, new JsonObject());
    }

    /**
     * POST /api/notifications/read-all - Mark all notifications as read.
     */
    private void handleMarkAllNotificationsRead(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (notificationStore == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Notifications not enabled");
            return;
        }
        notificationStore.markAllRead();
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, new JsonObject());
    }

    /**
     * DELETE /api/notifications - Clear all notifications.
     */
    private void handleClearNotifications(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (notificationStore == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Notifications not enabled");
            return;
        }
        int cleared = notificationStore.clearAll();
        JsonObject response = new JsonObject();
        response.addProperty("cleared", cleared);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/console - Execute a console command remotely (SUPER_ADMIN only,
     * enforced by the RBAC matrix).
     *
     * <p>Delegates to {@link ConsoleCommandHandler#dispatch}. Only commands on
     * the {@link #CONSOLE_WHITELIST} are allowed; anything else (e.g.
     * {@code stop}/{@code shutdown}/{@code spy}) is rejected with 403.
     * Requirements: 25.4
     */
    private void handleConsoleCommand(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (consoleCommandHandler == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Console handler not available");
            return;
        }

        String command;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            command = json.has("command") && !json.get("command").isJsonNull()
                    ? json.get("command").getAsString() : null;
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        if (command == null || command.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "command is required");
            return;
        }

        // Whitelist: only the safe command subset may run via REST. Commands
        // like stop/shutdown (halt the backend) or spy (live chat monitoring)
        // are reserved for the real backend console.
        String trimmed = command.trim();
        String firstToken = trimmed.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        if (!CONSOLE_WHITELIST.contains(firstToken)) {
            sendJsonError(ctx, request, HttpResponseStatus.FORBIDDEN,
                    I18n.tr("api.console.command_not_allowed", firstToken));
            return;
        }

        String output = consoleCommandHandler.dispatch(trimmed);
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("output", output != null ? output : "");
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/messages - Send a message to a channel
     * Requirements: 25.4
     */
    private void handleSendMessage(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            
            String channelId = json.has("channelId") ? json.get("channelId").getAsString() : null;
            String senderName = json.has("senderName") ? json.get("senderName").getAsString() : "API";
            String content = json.has("content") ? json.get("content").getAsString() : null;

            if (content == null || content.isBlank()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Content is required");
                return;
            }

            if (channelId == null) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Missing channelId or content");
                return;
            }
            
            Channel channel = channelManager.getChannel(channelId);
            if (channel == null) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
                return;
            }
            
            // Create a system UUID for API messages
            UUID senderId = new UUID(0, 0);
            
            // Route the message
            messageRouter.routeMessage(channelId, senderId, senderName, content, new HashMap<>());
            
            // Trigger webhook for message sent
            if (webhookManager != null) {
                webhookManager.triggerWebhook("message.sent", createMessageWebhookPayload(
                    channelId, senderId.toString(), senderName, content));
            }
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Message sent successfully");
            
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
            
        } catch (Exception e) {
            logger.error("Error sending message via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        }
    }

    /**
     * GET /api/messages - Query persisted message history with pagination.
     *
     * <p>Query params (all optional): page (1-based, default 1), size (default
     * 50, max 200), channel (exact channelId), server (exact clientId), player
     * (substring on senderName), q (substring on content), from/to (inclusive
     * epoch-millis bounds).
     * Returns {items:[{id, channelId, senderId, senderName, clientId, content,
     * timestamp}], page, pageSize, total}.
     */
    private void handleGetMessages(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (messageLogService == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    I18n.tr("api.messages.unavailable"));
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        Map<String, List<String>> params = decoder.parameters();

        int page = parseIntParam(params, "page", 1);
        if (page < 1) {
            page = 1;
        }
        int size = parseIntParam(params, "size", 50);
        size = Math.min(200, Math.max(1, size));

        String channel = firstParam(params, "channel");
        String server = firstParam(params, "server");
        String player = firstParam(params, "player");
        String q = firstParam(params, "q");
        Long from = parseLongParam(params, "from");
        Long to = parseLongParam(params, "to");

        MessageFilter filter = new MessageFilter(channel, server, player, q, from, to);
        int offset = (page - 1) * size;

        try {
            List<ChatMessageRecord> records = messageLogService.search(filter, offset, size);
            int total = messageLogService.count(filter);

            JsonArray items = new JsonArray();
            for (ChatMessageRecord record : records) {
                JsonObject item = new JsonObject();
                item.addProperty("id", record.getId());
                item.addProperty("channelId", record.getChannelId());
                item.addProperty("senderId", record.getSenderId());
                item.addProperty("senderName", record.getSenderName());
                item.addProperty("clientId", record.getClientId());
                item.addProperty("content", record.getContent());
                item.addProperty("timestamp", record.getTimestamp());
                items.add(item);
            }

            JsonObject response = new JsonObject();
            response.add("items", items);
            response.addProperty("page", page);
            response.addProperty("pageSize", size);
            response.addProperty("total", total);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error querying message history", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-510: Database error");
        }
    }

    private static String firstParam(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    private static int parseIntParam(Map<String, List<String>> params, String key, int defaultValue) {
        String value = firstParam(params, key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Long parseLongParam(Map<String, List<String>> params, String key) {
        String value = firstParam(params, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== Announcement Endpoints ====================

    /**
     * GET /api/announcements - List persisted JOIN/CRON announcements.
     *
     * <p>Returns {items:[{id, type, channelId, content, cron, enabled,
     * createdAt}], total}. INSTANT announcements are fire-and-forget and never
     * listed.
     */
    private void handleGetAnnouncements(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (announcementManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Announcements not enabled");
            return;
        }

        List<Announcement> announcements = new ArrayList<>();
        for (Announcement announcement : announcementManager.getAllAnnouncements()) {
            if (announcement.getType() == AnnouncementType.JOIN
                    || announcement.getType() == AnnouncementType.SCHEDULED) {
                announcements.add(announcement);
            }
        }
        announcements.sort(Comparator.comparingLong(Announcement::getCreatedAt));

        JsonArray items = new JsonArray();
        for (Announcement announcement : announcements) {
            items.add(announcementToJson(announcement));
        }

        JsonObject response = new JsonObject();
        response.add("items", items);
        response.addProperty("total", items.size());
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/announcements - Create/send an announcement.
     *
     * <p>Body: {type:"INSTANT"|"JOIN"|"CRON", channelId, content, cron?}.
     * INSTANT is sent immediately through the pipeline and returns {sent:true}
     * without persisting; JOIN/CRON are persisted + scheduled and return the
     * full object. channelId must exist; CRON requires a parseable cron
     * expression (400 + i18n otherwise).
     */
    private void handleCreateAnnouncement(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        if (announcementManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Announcements not enabled");
            return;
        }

        String typeRaw;
        String channelId;
        String content;
        String cron;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            typeRaw = json.has("type") && !json.get("type").isJsonNull()
                    ? json.get("type").getAsString() : null;
            channelId = json.has("channelId") && !json.get("channelId").isJsonNull()
                    ? json.get("channelId").getAsString() : null;
            content = json.has("content") && !json.get("content").isJsonNull()
                    ? json.get("content").getAsString() : null;
            cron = json.has("cron") && !json.get("cron").isJsonNull()
                    ? json.get("cron").getAsString() : null;
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        AnnouncementType type = AnnouncementType.fromDbValue(typeRaw);
        if (type == null) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    I18n.tr("api.announcement.invalid_type", String.valueOf(typeRaw)));
            return;
        }
        if (channelId == null || channelId.isBlank() || !channelManager.channelExists(channelId)) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    I18n.tr("api.announcement.channel_not_found", String.valueOf(channelId)));
            return;
        }
        if (content == null || content.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    I18n.tr("api.announcement.content_required"));
            return;
        }

        UUID operatorId = panelOperatorUuid(panelUsername(claims));

        if (type == AnnouncementType.IMMEDIATE) {
            AnnouncementResult result = announcementManager.sendImmediateAnnouncement(
                    operatorId, channelId, content, null, true);
            if (!result.isSuccess()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, result.getMessage());
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("sent", true);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
            return;
        }

        AnnouncementResult result;
        if (type == AnnouncementType.SCHEDULED) {
            if (cron == null || cron.isBlank()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        I18n.tr("api.announcement.cron_required"));
                return;
            }
            result = announcementManager.createScheduledAnnouncement(
                    operatorId, channelId, content, cron, null, true);
            if (!result.isSuccess()) {
                // Only the cron parse can fail here (validation done above).
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        I18n.tr("api.announcement.invalid_cron", cron));
                return;
            }
        } else {
            result = announcementManager.createJoinAnnouncement(
                    operatorId, channelId, content, null, true);
            if (!result.isSuccess()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, result.getMessage());
                return;
            }
        }

        sendJsonResponse(ctx, request, HttpResponseStatus.CREATED,
                announcementToJson(result.getAnnouncement()));
    }

    /**
     * PUT /api/announcements/{id} - Enable/disable a persisted announcement.
     *
     * <p>Body: {enabled:boolean}. Returns the updated object. Disabling a CRON
     * announcement cancels its scheduled task; enabling re-schedules it.
     */
    private void handleUpdateAnnouncement(ChannelHandlerContext ctx, FullHttpRequest request,
                                          String announcementId) {
        if (announcementManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Announcements not enabled");
            return;
        }

        Boolean enabled = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("enabled") && !json.get("enabled").isJsonNull()) {
                    enabled = json.get("enabled").getAsBoolean();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }
        if (enabled == null) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    I18n.tr("api.announcement.enabled_required"));
            return;
        }

        AnnouncementResult result = announcementManager.setAnnouncementEnabled(announcementId, enabled);
        if (!result.isSuccess()) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND,
                    I18n.tr("api.announcement.not_found", announcementId));
            return;
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK,
                announcementToJson(result.getAnnouncement()));
    }

    /**
     * DELETE /api/announcements/{id} - Delete a persisted announcement.
     */
    private void handleDeleteAnnouncement(ChannelHandlerContext ctx, FullHttpRequest request,
                                          String announcementId) {
        if (announcementManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Announcements not enabled");
            return;
        }

        AnnouncementResult result = announcementManager.deleteAnnouncement(
                CONSOLE_SENTINEL, announcementId, null, true);
        if (!result.isSuccess()) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND,
                    I18n.tr("api.announcement.not_found", announcementId));
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * Converts an announcement to its REST JSON shape
     * {id, type, channelId, content, cron, enabled, createdAt} where type is
     * the external value (JOIN/CRON).
     */
    private JsonObject announcementToJson(Announcement announcement) {
        JsonObject json = new JsonObject();
        json.addProperty("id", announcement.getId());
        json.addProperty("type", announcement.getType().dbValue());
        json.addProperty("channelId", announcement.getChannelId());
        json.addProperty("content", announcement.getContent());
        if (announcement.getCronExpression() != null) {
            json.addProperty("cron", announcement.getCronExpression());
        } else {
            json.add("cron", com.google.gson.JsonNull.INSTANCE);
        }
        json.addProperty("enabled", announcement.isEnabled());
        json.addProperty("createdAt", announcement.getCreatedAt());
        return json;
    }

    // ==================== Sensitive-Word Filter Endpoints ====================

    /**
     * GET /api/filter - Returns the filter state:
     * {enabled, words:[...], patterns:[...]} where words/patterns are the
     * custom (panel-managed) lists only, not the built-in word list.
     */
    private void handleGetFilter(ChannelHandlerContext ctx, FullHttpRequest request) {
        com.nova.link.filter.SensitiveWordFilter filter = resolveSensitiveWordFilter();
        if (filter == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Filter not available");
            return;
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, filterStateToJson(filter));
    }

    /**
     * PUT /api/filter - Updates the filter wholesale.
     *
     * <p>Body: {enabled?, words?, patterns?}. Provided fields fully replace
     * the current value (arrays are swapped, enabled overwrites). Every regex
     * is validated individually — an invalid one returns 400 identifying the
     * offending pattern. Applies to the runtime filter, then persists via
     * configManager.save() without triggering a disk reload (same pattern as
     * PUT /api/settings). Returns the updated full state.
     */
    private void handleUpdateFilter(ChannelHandlerContext ctx, FullHttpRequest request) {
        com.nova.link.filter.SensitiveWordFilter filter = resolveSensitiveWordFilter();
        if (filter == null || configManager == null || configManager.getConfig() == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Filter not available");
            return;
        }

        Boolean enabled = null;
        List<String> words = null;
        List<String> patterns = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (body != null && !body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("enabled") && !json.get("enabled").isJsonNull()) {
                    enabled = json.get("enabled").getAsBoolean();
                }
                if (json.has("words") && json.get("words").isJsonArray()) {
                    words = new ArrayList<>();
                    for (com.google.gson.JsonElement el : json.getAsJsonArray("words")) {
                        String word = el.getAsString();
                        if (word != null && !word.isBlank()) {
                            words.add(word.trim());
                        }
                    }
                }
                if (json.has("patterns") && json.get("patterns").isJsonArray()) {
                    patterns = new ArrayList<>();
                    for (com.google.gson.JsonElement el : json.getAsJsonArray("patterns")) {
                        String pattern = el.getAsString();
                        if (pattern != null && !pattern.isBlank()) {
                            patterns.add(pattern);
                        }
                    }
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        // Validate every regex individually before touching any state so the
        // response can identify the offending entry.
        if (patterns != null) {
            for (String pattern : patterns) {
                try {
                    java.util.regex.Pattern.compile(pattern);
                } catch (java.util.regex.PatternSyntaxException e) {
                    sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                            I18n.tr("api.filter.invalid_pattern", pattern, e.getDescription()));
                    return;
                }
            }
        }

        // Apply to the runtime filter.
        if (words != null) {
            filter.setCustomWords(words);
        }
        if (patterns != null) {
            filter.setCustomPatterns(patterns);
        }
        if (enabled != null) {
            filter.setEnabled(enabled);
        }

        // Mirror into the in-memory config, then persist (no disk reload).
        com.nova.link.config.NovaLinkConfig config = configManager.getConfig();
        if (words != null) {
            config.getFilter().setWords(new ArrayList<>(words));
        }
        if (patterns != null) {
            config.getFilter().setPatterns(new ArrayList<>(patterns));
        }
        if (enabled != null && config.getFeatures() != null) {
            config.getFeatures().setFilterEnabled(enabled);
        }
        try {
            configManager.save();
        } catch (Exception e) {
            logger.error("Error persisting filter config via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-500: Filter apply succeeded but persist failed: " + e.getMessage());
            return;
        }

        sendJsonResponse(ctx, request, HttpResponseStatus.OK, filterStateToJson(filter));
    }

    /** The live filter wired into the message pipeline (may be null in tests). */
    private com.nova.link.filter.SensitiveWordFilter resolveSensitiveWordFilter() {
        return messageRouter != null ? messageRouter.getPipeline().getSensitiveWordFilter() : null;
    }

    /** Full filter state {enabled, words, patterns} for GET/PUT responses. */
    private JsonObject filterStateToJson(com.nova.link.filter.SensitiveWordFilter filter) {
        JsonObject response = new JsonObject();
        response.addProperty("enabled", filter.isEnabled());
        JsonArray words = new JsonArray();
        for (String word : filter.getCustomWords()) {
            words.add(word);
        }
        response.add("words", words);
        JsonArray patterns = new JsonArray();
        for (String pattern : filter.getCustomPatterns()) {
            patterns.add(pattern);
        }
        response.add("patterns", patterns);
        return response;
    }

    /**
     * GET /api/players - List online players
     * Requirements: 25.4
     */
    private void handleGetPlayers(ChannelHandlerContext ctx, FullHttpRequest request) {
        JsonArray players = new JsonArray();
        
        // Get all player states from the state manager
        Collection<PlayerState> states = playerStateManager.getAllPlayerStates();
        for (PlayerState state : states) {
            players.add(playerStateToJson(state));
        }
        
        JsonObject response = new JsonObject();
        response.add("players", players);
        response.addProperty("total", players.size());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/players/{uuid} - Get player details
     * Requirements: 25.4
     */
    private void handleGetPlayer(ChannelHandlerContext ctx, FullHttpRequest request, String playerId) {
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerState state = playerStateManager.getPlayerState(uuid);
            
            if (state == null) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Player not found");
                return;
            }
            
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, playerStateToJson(state));
            
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid UUID format");
        }
    }

    /**
     * GET /api/webhooks - List all webhooks
     * Requirements: 25.5
     */
    private void handleGetWebhooks(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (webhookManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Webhooks not enabled");
            return;
        }
        
        JsonArray webhooks = new JsonArray();
        for (Webhook webhook : webhookManager.getAllWebhooks()) {
            webhooks.add(webhookToJson(webhook));
        }
        
        JsonObject response = new JsonObject();
        response.add("webhooks", webhooks);
        response.addProperty("total", webhooks.size());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/webhooks - Create a new webhook
     * Requirements: 25.5
     */
    private void handleCreateWebhook(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (webhookManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Webhooks not enabled");
            return;
        }
        
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            
            String url = json.has("url") ? json.get("url").getAsString() : null;
            String event = json.has("event") ? json.get("event").getAsString() : null;
            String secret = json.has("secret") ? json.get("secret").getAsString() : null;
            
            if (url == null || event == null) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Missing url or event");
                return;
            }
            
            Webhook webhook = webhookManager.createWebhook(url, event, secret);
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("webhook", webhookToJson(webhook));
            
            sendJsonResponse(ctx, request, HttpResponseStatus.CREATED, response);
            
        } catch (Exception e) {
            logger.error("Error creating webhook", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        }
    }

    /**
     * PUT /api/webhooks/{id} - Update a webhook.
     *
     * <p>Body: {url?, secret?, active?} plus the event key, accepted as either
     * {@code events} (single event string — what the panel submits) or
     * {@code event}. Only provided fields are applied. Returns the updated object.
     */
    private void handleUpdateWebhook(ChannelHandlerContext ctx, FullHttpRequest request, String webhookId) {
        if (webhookManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Webhooks not enabled");
            return;
        }

        String url = null;
        String event = null;
        String secret = null;
        Boolean active = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("url") && !json.get("url").isJsonNull()) {
                    url = json.get("url").getAsString();
                }
                // The panel submits the event under the `events` key (single
                // string value); `event` is accepted too for API consumers.
                if (json.has("events") && !json.get("events").isJsonNull()) {
                    event = json.get("events").getAsString();
                } else if (json.has("event") && !json.get("event").isJsonNull()) {
                    event = json.get("event").getAsString();
                }
                if (json.has("secret") && !json.get("secret").isJsonNull()) {
                    secret = json.get("secret").getAsString();
                }
                if (json.has("active") && !json.get("active").isJsonNull()) {
                    active = json.get("active").getAsBoolean();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        Webhook updated = webhookManager.updateWebhook(webhookId, url, event, secret, active);
        if (updated == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND,
                    I18n.tr("api.webhook.not_found", webhookId));
            return;
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, webhookToJson(updated));
    }

    /**
     * POST /api/webhooks/{id}/test - Synchronously deliver a test payload
     * (event=test, no retries, 5s timeout).
     *
     * <p>Returns {success:boolean, statusCode?, error?} with HTTP 200 either way.
     */
    private void handleTestWebhook(ChannelHandlerContext ctx, FullHttpRequest request, String webhookId) {
        if (webhookManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Webhooks not enabled");
            return;
        }

        Webhook webhook = webhookManager.getWebhook(webhookId);
        if (webhook == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND,
                    I18n.tr("api.webhook.not_found", webhookId));
            return;
        }

        WebhookManager.TestResult result = webhookManager.sendTest(webhook);
        JsonObject response = new JsonObject();
        response.addProperty("success", result.isSuccess());
        if (result.getStatusCode() != null) {
            response.addProperty("statusCode", result.getStatusCode());
        }
        if (result.getError() != null) {
            response.addProperty("error", result.getError());
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * DELETE /api/webhooks/{id} - Delete a webhook
     * Requirements: 25.5
     */
    private void handleDeleteWebhook(ChannelHandlerContext ctx, FullHttpRequest request, String webhookId) {
        if (webhookManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Webhooks not enabled");
            return;
        }
        
        boolean deleted = webhookManager.deleteWebhook(webhookId);
        
        if (!deleted) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Webhook not found");
            return;
        }
        
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Webhook deleted successfully");
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/status - Get server status
     * Requirements: 25.4
     */
    private void handleGetStatus(ChannelHandlerContext ctx, FullHttpRequest request) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "online");
        response.addProperty("version", "1.0.0");
        response.addProperty("channelCount", channelManager.getChannelCount());
        response.addProperty("playerCount", playerStateManager.getAllPlayerStates().size());
        response.addProperty("timestamp", System.currentTimeMillis());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * Generates a unique channel id for REST-created GLOBAL/SERVER channels when
     * the caller did not supply one. Format: {@code ch-XXXX} (4 hex chars),
     * distinct from the {@code NC-XXXX} prefix reserved for private channels.
     * Retries until the id is free (bounded).
     */
    private String generateRestChannelId() {
        java.security.SecureRandom rng = new java.security.SecureRandom();
        String chars = "0123456789ABCDEF";
        for (int attempts = 0; attempts < 1000; attempts++) {
            StringBuilder sb = new StringBuilder("ch-");
            for (int i = 0; i < 4; i++) {
                sb.append(chars.charAt(rng.nextInt(chars.length())));
            }
            String candidate = sb.toString();
            if (!channelManager.channelExists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate unique REST channel id after 1000 attempts");
    }

    /**
     * Converts a Channel to JSON.
     */
    private JsonObject channelToJson(Channel channel) {
        JsonObject json = new JsonObject();
        json.addProperty("id", channel.getId());
        json.addProperty("displayName", channel.getDisplayName());
        json.addProperty("scope", channel.getScope().name());
        json.addProperty("clientId", channel.getClientId());
        json.addProperty("memberCount", channel.getMembers().size());
        json.addProperty("maxCapacity", channel.getMaxCapacity());
        json.addProperty("slowModeSeconds", channel.getSlowModeSeconds());
        
        if (channel.getPermission() != null) {
            json.addProperty("permission", channel.getPermission());
        }
        
        if (channel.getAllowedWorlds() != null && !channel.getAllowedWorlds().isEmpty()) {
            JsonArray worlds = new JsonArray();
            channel.getAllowedWorlds().forEach(worlds::add);
            json.add("allowedWorlds", worlds);
        }
        
        return json;
    }

    /**
     * Converts a PlayerState to JSON.
     */
    private JsonObject playerStateToJson(PlayerState state) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", state.getPlayerId().toString());
        json.addProperty("name", state.getPlayerName());
        json.addProperty("clientId", state.getClientId());
        json.addProperty("currentWorld", state.getCurrentWorld());
        json.addProperty("activeChannel", state.getActiveChannel());
        
        JsonArray channels = new JsonArray();
        state.getJoinedChannels().forEach(channels::add);
        json.add("joinedChannels", channels);

        json.addProperty("muted", state.getMutes() != null && !state.getMutes().isEmpty());
        json.addProperty("platform", state.getPlatform() != null
                && !state.getPlatform().isEmpty()
                ? state.getPlatform() : "Java");

        return json;
    }

    /**
     * Converts a Webhook to JSON. {@code lastTriggered} is epoch millis or
     * null when the webhook has never fired.
     */
    private JsonObject webhookToJson(Webhook webhook) {
        JsonObject json = new JsonObject();
        json.addProperty("id", webhook.getId());
        json.addProperty("url", webhook.getUrl());
        json.addProperty("event", webhook.getEvent());
        json.addProperty("active", webhook.isActive());
        json.addProperty("createdAt", webhook.getCreatedAt());
        if (webhook.getLastTriggered() > 0) {
            json.addProperty("lastTriggered", webhook.getLastTriggered());
        } else {
            json.add("lastTriggered", com.google.gson.JsonNull.INSTANCE);
        }
        return json;
    }

    /**
     * Creates a webhook payload for message events.
     */
    private JsonObject createMessageWebhookPayload(String channelId, String senderId, 
                                                    String senderName, String content) {
        JsonObject payload = new JsonObject();
        payload.addProperty("channelId", channelId);
        payload.addProperty("senderId", senderId);
        payload.addProperty("senderName", senderName);
        payload.addProperty("content", content);
        payload.addProperty("timestamp", System.currentTimeMillis());
        return payload;
    }

    /**
     * Sends a JSON response.
     */
    private void sendJsonResponse(ChannelHandlerContext ctx, FullHttpRequest request,
                                  HttpResponseStatus status, JsonObject json) {
        sendJsonResponse(ctx, request, status, (com.google.gson.JsonElement) json);
    }

    /**
     * Sends a JSON response for any JsonElement (object or array). Used by
     * endpoints whose contract requires a bare top-level array (e.g. GET /api/bans).
     */
    private void sendJsonResponse(ChannelHandlerContext ctx, FullHttpRequest request,
                                  HttpResponseStatus status, com.google.gson.JsonElement json) {
        String content = gson.toJson(json);
        ByteBuf buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, buf);

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        addCorsHeaders(request, response);

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Sends a JSON error response.
     */
    private void sendJsonError(ChannelHandlerContext ctx, FullHttpRequest request,
                               HttpResponseStatus status, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        // Keep `message` for frontend compatibility; `error` kept for backward compatibility.
        json.addProperty("message", message);
        json.addProperty("status", status.code());
        sendJsonResponse(ctx, request, status, json);
    }

    /**
     * Sends a CORS preflight response.
     */
    private void sendCorsResponse(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        addCorsHeaders(request, response);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Adds CORS headers to the response according to the configured origin
     * whitelist: {@code ["*"]} (default) keeps the legacy allow-all behavior;
     * an explicit origin list echoes the request Origin only when it matches —
     * non-matching (or absent) origins get no CORS headers at all.
     */
    private void addCorsHeaders(FullHttpRequest request, FullHttpResponse response) {
        String allowedOrigin = resolveCorsOrigin(corsAllowedOrigins,
                request != null ? request.headers().get(HttpHeaderNames.ORIGIN) : null);
        if (allowedOrigin == null) {
            return;
        }
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin);
        if (!"*".equals(allowedOrigin)) {
            response.headers().add(HttpHeaderNames.VARY, "Origin");
        }
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, 
                "Content-Type, Authorization");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "86400");
    }

    /**
     * Resolves the value for {@code Access-Control-Allow-Origin}: {@code "*"}
     * when the whitelist allows all; the echoed request origin when it matches
     * an entry (case-insensitive, ignoring a trailing slash); null when no CORS
     * headers should be emitted.
     */
    public static String resolveCorsOrigin(List<String> allowedOrigins, String requestOrigin) {
        if (allowedOrigins == null || allowedOrigins.isEmpty() || allowedOrigins.contains("*")) {
            return "*";
        }
        if (requestOrigin == null || requestOrigin.isBlank()) {
            return null;
        }
        String normalizedRequest = normalizeOrigin(requestOrigin);
        for (String allowed : allowedOrigins) {
            if (allowed != null && normalizeOrigin(allowed).equalsIgnoreCase(normalizedRequest)) {
                return requestOrigin;
            }
        }
        return null;
    }

    private static String normalizeOrigin(String origin) {
        String trimmed = origin.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("REST API handler error", cause);
        ctx.close();
    }
}
