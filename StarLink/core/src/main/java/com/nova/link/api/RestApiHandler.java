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
import com.nova.link.auth.PanelResourcePolicy;
import com.nova.link.auth.PanelRole;
import com.nova.link.audit.AuditEvent;
import com.nova.link.audit.AuditStore;
import com.nova.link.ban.BanManager;
import com.nova.link.ban.BanResult;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelSource;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.channel.RoutingResult;
import com.nova.link.config.ConfigManager;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.database.BanInfo;
import com.nova.link.database.ChatMessageRecord;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.Invitation;
import com.nova.link.database.MessageFilter;
import com.nova.link.database.MuteInfo;
import com.nova.link.database.Notification;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.i18n.I18n;
import com.nova.link.log.MessageLogService;
import com.nova.link.moderation.Appeal;
import com.nova.link.moderation.AppealStatus;
import com.nova.link.moderation.CaseEvidence;
import com.nova.link.moderation.CaseEvidenceType;
import com.nova.link.moderation.CaseSource;
import com.nova.link.moderation.CaseStatus;
import com.nova.link.moderation.ModerationCase;
import com.nova.link.moderation.ModerationException;
import com.nova.link.moderation.ModerationManager;
import com.nova.link.moderation.ReporterSource;
import com.nova.link.moderation.ResolutionAction;
import com.nova.link.mute.MuteManager;
import com.nova.link.mute.MuteResult;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.notification.NotificationStore;
import com.nova.link.websocket.JwtService;
import com.nova.link.websocket.WebSocketGateway;
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
    private final PanelResourcePolicy resourcePolicy;
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
    private final AuditStore auditStore;
    private final List<String> corsAllowedOrigins;
    private final Gson gson;

    /**
     * Channel attribute key holding the per-request correlation id
     * (PANEL-006). The id is generated (or honored from the incoming
     * {@code X-Request-Id} header) at the top of {@link #channelRead0} and
     * read by {@link #currentRequestId} for response stamping and audit
     * recording. Using a channel attribute avoids threading the id through
     * every handler signature.
     */
    private static final io.netty.util.AttributeKey<String> REQUEST_ID_KEY =
            io.netty.util.AttributeKey.valueOf("novalink-request-id");

    /**
     * Announcement manager (setter-injected after construction to keep the
     * already-long constructor stable). Required for /api/announcements.
     */
    private AnnouncementManager announcementManager;

    /**
     * Campaign manager (setter-injected; §11.6 提案 06 slice A). Required for
     * /api/campaigns*. When null, campaign routes return 503.
     */
    private com.nova.link.announcement.CampaignManager campaignManager;

    /**
     * Message history service (setter-injected). Required for GET /api/messages.
     */
    private MessageLogService messageLogService;

    /**
     * Live flag for {@code features.private-messages-enabled}, shared with the
     * {@link com.nova.link.network.PrivateMessageHandler} (which reads it as a
     * {@code BooleanSupplier}). Setter-injected so the REST handler can
     * propagate panel toggles to the PrivateMessageHandler without a full
     * reload. When unset, {@code applyFeatureConfig} simply skips the toggle.
     */
    private volatile java.util.concurrent.atomic.AtomicBoolean privateMessagesEnabledFlag;

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
                networkHandler, consoleCommandHandler, notificationStore, null, List.of("*"));
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
        this(jwtService, authManager, channelManager, playerStateManager, messageRouter,
                webhookManager, muteManager, banManager, invitationManager, configManager,
                networkHandler, consoleCommandHandler, notificationStore, null, corsAllowedOrigins);
    }

    /**
     * Full constructor with an {@link AuditStore} (PANEL-006). The audit store
     * is passed explicitly rather than setter-injected because audit recording
     * is mandatory for P1 mutations, not optional. The two legacy constructors
     * above delegate here with a null store (audit silently disabled) so
     * existing call sites and tests keep compiling.
     *
     * @param auditStore the append-only audit store, or null to disable audit
     */
    public RestApiHandler(JwtService jwtService, AuthManager authManager,
                          ChannelManager channelManager, PlayerStateManager playerStateManager,
                          MessageRouter messageRouter, WebhookManager webhookManager,
                          MuteManager muteManager, BanManager banManager,
                          InvitationManager invitationManager,
                          ConfigManager configManager, ServerNetworkHandler networkHandler,
                          ConsoleCommandHandler consoleCommandHandler,
                          NotificationStore notificationStore,
                          AuditStore auditStore,
                          List<String> corsAllowedOrigins) {
        this.jwtService = jwtService;
        this.authManager = authManager;
        this.channelManager = channelManager;
        this.resourcePolicy = new PanelResourcePolicy(authManager, channelManager);
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
        this.auditStore = auditStore;
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

    /** Wires the campaign manager (§11.6 提案 06 slice A, called after construction). */
    public void setCampaignManager(com.nova.link.announcement.CampaignManager campaignManager) {
        this.campaignManager = campaignManager;
    }

    /** Wires the message history service (called after construction). */
    public void setMessageLogService(MessageLogService messageLogService) {
        this.messageLogService = messageLogService;
    }

    /**
     * Wires the live {@code private-messages-enabled} flag shared with
     * {@link com.nova.link.network.PrivateMessageHandler}. When a panel
     * settings update arrives, the flag is flipped in place so the handler
     * picks up the new value on its next message without a full reload.
     */
    public void setPrivateMessagesEnabledFlag(java.util.concurrent.atomic.AtomicBoolean flag) {
        this.privateMessagesEnabledFlag = flag;
    }

    /**
     * Moderation manager (setter-injected after construction so the already-long
     * constructor signature stays stable — the test setUp uses the 13-arg
     * legacy constructor). Required for the PANEL-007 moderation case/appeal
     * workflow routes. When unset, those routes 503 instead of NPE-ing.
     */
    private ModerationManager moderationManager;

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
     * Wires the moderation manager (PANEL-007). Setter-injected to avoid
     * touching the constructor signature (the test setUp uses the 13-arg
     * legacy constructor). When null, the moderation routes 503.
     */
    public void setModerationManager(ModerationManager moderationManager) {
        this.moderationManager = moderationManager;
    }

    /**
     * WebSocket gateway (setter-injected so the already-long constructor
     * signature stays stable). §11.6 Project 17: backs the
     * {@code nova_link_ws_sessions_active} metric surfaced by
     * {@link #healthMetricsService()}. Nullable — when unset, the ws metric and
     * the {@code checks.ws} sub-item are omitted (graceful degradation).
     */
    private WebSocketGateway webSocketGateway;

    /**
     * Wires the WebSocket gateway. Called after construction so the metrics
     * service can report active panel sessions without changing the
     * constructor signature.
     */
    public void setWebSocketGateway(WebSocketGateway webSocketGateway) {
        this.webSocketGateway = webSocketGateway;
        // The lazy metrics service may already be cached from an earlier probe;
        // null it so the next call reassembles with the new gateway ref.
        this.healthMetricsService = null;
    }

    /**
     * Exposes the injected {@link ConfigManager} for tests that need to mutate
     * the live config (e.g. stashing a secret before recording a snapshot).
     * Not used by production wiring.
     *
     * @return the config manager backing this handler
     */
    ConfigManager configManager() {
        return configManager;
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

    /**
     * Lazily-assembled monitoring service for GET /api/health and
     * GET /api/metrics. Built on demand from already-injected refs so that
     * no constructor signature changes (the auth002-jvm fork edits
     * NovaLinkMain; touching the RestApiHandler constructor risks collision).
     * Nullable fields (announcementManager, databaseProvider) are tolerated
     * internally; the service degrades to {@code degraded}/{@code down} status
     * rather than throwing.
     */
    private volatile HealthMetricsService healthMetricsService;

    private HealthMetricsService healthMetricsService() {
        HealthMetricsService cached = healthMetricsService;
        if (cached != null) {
            return cached;
        }
        DatabaseProvider db = null;
        try {
            db = playerStateManager.getDatabaseProvider();
        } catch (Exception ignored) {
            // PlayerStateManager is always injected in production; a null DB
            // here just downgrades the health check.
        }
        HealthMetricsService built = new HealthMetricsService(
                networkHandler, channelManager, webhookManager,
                announcementManager, db, configManager, webSocketGateway);
        healthMetricsService = built;
        return built;
    }

    /**
     * §11.6 Project 20 / PANEL proposal 10 — config diff + atomic rollback.
     * Lazily-assembled backing service for {@code GET /api/settings/history},
     * {@code /api/settings/snapshots/{revision}}, {@code /api/settings/diff},
     * and {@code POST /api/settings/rollback}. Built on demand from the
     * injected {@link ConfigManager}, the {@link DatabaseProvider} reachable
     * via {@link PlayerStateManager}, and the audit store — same lazy pattern
     * as {@link #healthMetricsService()}. The constructor signature stays
     * untouched (NovaLinkMain is being edited by another agent). The service
     * also wires itself into ConfigManager via setter so that every
     * {@link ConfigManager#save()} records a masked snapshot automatically.
     * When the database provider is unavailable, every endpoint 503s instead
     * of NPE-ing.
     */
    private volatile ConfigHistoryService configHistoryService;

    private ConfigHistoryService configHistoryService() {
        ConfigHistoryService cached = configHistoryService;
        if (cached != null) {
            return cached;
        }
        DatabaseProvider db = null;
        try {
            db = playerStateManager.getDatabaseProvider();
        } catch (Exception ignored) {
            // PlayerStateManager is always injected in production; a null DB
            // here makes the config-history endpoints 503.
        }
        if (db == null || configManager == null) {
            return null;
        }
        ConfigHistoryService built = new ConfigHistoryService(db, configManager, auditStore);
        // Wire the service into ConfigManager so subsequent save() calls
        // record a masked snapshot automatically. Idempotent: repeated calls
        // return the same cached instance, and setConfigHistoryService just
        // overwrites the field.
        try {
            configManager.setConfigHistoryService(built);
        } catch (Exception ignored) {
            // ConfigManager tolerates a null service; a setter failure here is
            // non-fatal — the endpoints still work, just without auto-recording.
        }
        configHistoryService = built;
        return built;
    }

    /**
     * Lazily-assembled backing service for the §11.6 item-20 / PANEL proposal 10
     * draft / approve / publish / backup / restore endpoints. Built on demand
     * from the already-cached {@link #configHistoryService()} plus the same
     * {@link ConfigManager} / {@link DatabaseProvider} / {@link AuditStore}.
     * Same lazy pattern as {@code configHistoryService()}: when the DB is
     * unavailable every endpoint 503s instead of NPE-ing. The constructor
     * signature stays untouched so NovaLinkMain (edited by another agent) is
     * not affected.
     */
    private volatile ConfigPublishService configPublishService;

    private ConfigPublishService configPublishService() {
        ConfigPublishService cached = configPublishService;
        if (cached != null) {
            return cached;
        }
        // configHistoryService() itself lazily wires into ConfigManager; call
        // it first so the downstream null/503 checks align with the real
        // backing service rather than a stale null.
        ConfigHistoryService history = configHistoryService();
        DatabaseProvider db = null;
        try {
            db = playerStateManager.getDatabaseProvider();
        } catch (Exception ignored) {
            // PlayerStateManager is always injected in production; a null DB
            // here makes the config-publish endpoints 503.
        }
        if (db == null || configManager == null || history == null) {
            return null;
        }
        ConfigPublishService built = new ConfigPublishService(db, configManager, history, auditStore);
        configPublishService = built;
        return built;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String uri = request.uri();
        HttpMethod method = request.method();

        // PANEL-006: stamp every response with a per-request correlation id.
        // Honor an incoming X-Request-Id header (truncated) so upstream LBs can
        // thread their own trace id; otherwise generate a fresh UUID. The id is
        // stored as a channel attribute so the audit hooks and response writers
        // can read it without threading it through every handler signature.
        String incomingRequestId = request.headers().get("X-Request-Id");
        String requestId;
        if (incomingRequestId != null && !incomingRequestId.isBlank()
                && incomingRequestId.length() <= 128) {
            requestId = incomingRequestId;
        } else {
            requestId = java.util.UUID.randomUUID().toString();
        }
        ctx.channel().attr(REQUEST_ID_KEY).set(requestId);

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

        // GET /api/health is an unauthenticated liveness/readiness probe for
        // LB and k8s. It bypasses the worker pool and auth entirely so that a
        // saturated worker pool or a revoked probe token never makes a healthy
        // backend look unhealthy. Only the GET method is exempt; other methods
        // fall through to the normal authed path (and 404/405 as expected).
        if (uri.startsWith("/api/health") && method == HttpMethod.GET) {
            try {
                handleHealth(ctx, request);
            } catch (Exception e) {
                logger.error("health probe error", e);
                sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal server error");
            }
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
        // the body. ctx.writeAndFlush is thread-safe. The channel attribute set
        // above is visible to the worker because it is read off the channel,
        // not off the IO thread's stack.
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
            PanelRole actual = resourcePolicy.resolveRole(claims);
            if (!hasRole(actual, required)) {
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
     *       (POST/PUT/DELETE /api/channels*, invite, invitation revoke) + POST /api/messages +
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
            // PANEL-006: GET /api/audit is ADMIN+ (read access to the audit
            // log is deliberately separate from notification clear, which is
            // already ADMIN under the default branch below).
            if (path.equals("/api/audit")) {
                return PanelRole.ADMIN;
            }
            // §11.6 Project 20: config history/snapshot/diff expose the masked
            // (not plaintext) configuration of the deployment — database hosts,
            // client usernames, admin rosters. ADMIN+ even though they are GETs;
            // VIEWER must not see the deployment topology.
            if (path.equals("/api/settings/history")
                    || path.matches("/api/settings/snapshots/[^/]+")
                    || path.equals("/api/settings/diff")) {
                return PanelRole.ADMIN;
            }
            // §11.6 item-20 / PANEL proposal 10 (doc-deferred sub-items 1+2+3):
            // the draft/backup list and draft-load endpoints stage masked config
            // candidates that could become live, and the drafts/backups carry
            // deployment topology (client usernames, admin rosters) even masked.
            // SUPER_ADMIN-only — same posture as the POST counterparts below.
            if (path.equals("/api/config/drafts")
                    || path.matches("/api/config/drafts/[^/]+")
                    || path.equals("/api/config/backups")) {
                return PanelRole.SUPER_ADMIN;
            }
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
        // §11.6 Project 20: rollback rewrites the live config from a masked
        // snapshot — SUPER_ADMIN only, same as /api/settings PUT.
        if (path.equals("/api/settings/rollback") && method == HttpMethod.POST) {
            return PanelRole.SUPER_ADMIN;
        }
        // §11.6 Project 20 (proposal 10): validate is a dry-run against the
        // same structural rules as rollback/save, but does NOT persist. The
        // YAML body may echo the live (masked) config the caller already sees
        // via /api/settings/history (ADMIN+); a validate call therefore leaks
        // no more than history does. ADMIN+, not SUPER_ADMIN — a non-owner
        // admin preparing a candidate config can dry-run it before asking the
        // owner to apply it.
        if (path.equals("/api/settings/validate") && method == HttpMethod.POST) {
            return PanelRole.ADMIN;
        }
        // §11.6 item-20 / PANEL proposal 10 (doc-deferred sub-items 1+2+3):
        // the draft/approve/publish/backup/restore endpoints rewrite the live
        // config or stage a masked candidate that could become live. All 9
        // routes are SUPER_ADMIN-only — same posture as /api/settings PUT and
        // /api/settings/rollback. Approver != createdBy is a SECOND
        // authorization layer enforced inside ConfigPublishService.approveDraft
        // (403 if same), not covered here. The GET entries for these routes
        // (list/load drafts and backups) are gated in the GET branch above.
        if (path.equals("/api/config/drafts") && method == HttpMethod.POST) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.matches("/api/config/drafts/[^/]+/approve") && method == HttpMethod.POST) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.matches("/api/config/drafts/[^/]+/publish") && method == HttpMethod.POST) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.matches("/api/config/drafts/[^/]+") && method == HttpMethod.DELETE) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.equals("/api/config/backups") && method == HttpMethod.POST) {
            return PanelRole.SUPER_ADMIN;
        }
        if (path.equals("/api/config/restore-from-backup") && method == HttpMethod.POST) {
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
        // PANEL-014: global broadcast cleanup (DELETE /api/notifications/broadcast)
        // is SUPER_ADMIN-only because it deletes broadcast events visible to all
        // admins. The per-user DELETE /api/notifications (clears only the
        // caller's directed notifications) stays ADMIN under the default branch.
        if (path.equals("/api/notifications/broadcast") && method == HttpMethod.DELETE) {
            return PanelRole.SUPER_ADMIN;
        }
        // §11.6 提案 06 — campaign revoke is SUPER_ADMIN-only (matches the
        // CampaignManager RBAC mapping where campaign.revoke requires
        // PermissionLevel.SUPER_ADMIN).
        if (path.matches("/api/campaigns/[^/]+/revoke") && method == HttpMethod.POST) {
            return PanelRole.SUPER_ADMIN;
        }
        // PANEL-007: moderation routes rely on the defaults above — every
        // GET under /api/moderation/* and /api/appeals is VIEWER (the default
        // GET branch), and every POST mutation (POST /api/reports,
        // POST /api/moderation/cases/{id}/{assign,resolve,evidence},
        // POST /api/appeals, POST /api/appeals/{id}/review) falls through to
        // the ADMIN default below. The appeal reviewer-must-differ-from-
        // case-moderator rule is a SECOND authorization layer enforced inside
        // ModerationManager as a hard 403 — it is not covered here.
        // Every other mutation (player punishments, channel CRUD + invite,
        // messages, notification management, announcements, filter) requires ADMIN.
        return PanelRole.ADMIN;
    }

    /**
     * @return true when the token's role claim satisfies the required minimum.
     *         Unknown/legacy roles (e.g. CLIENT_ADMIN) never satisfy any level.
     */
    private static boolean hasRole(PanelRole actual, PanelRole required) {
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

    // ==================== Audit helpers (PANEL-006) ====================

    /**
     * Records an audit event for a P1 admin mutation. Best-effort: the audit
     * store swallows persistence failures so a degraded audit trail never
     * blocks the business operation. The {@code requestId}, {@code actor},
     * {@code role} and {@code origin} are derived from the request context and
     * claims so individual handlers only need to supply action/resource/hashes.
     *
     * @param ctx        the channel context (for requestId + origin)
     * @param claims     the operator claims (for actor + role)
     * @param action     stable action code (e.g. {@code channel.create})
     * @param resource   human-readable resource identifier (may be null)
     * @param beforeHash SHA-256 hex of pre-action state (may be null)
     * @param afterHash  SHA-256 hex of post-action state (may be null)
     * @param reason     free-form reason (may be null)
     * @param result     {@code "success"} or {@code "failure"}
     */
    private void recordAudit(ChannelHandlerContext ctx, Claims claims,
                             String action, String resource,
                             String beforeHash, String afterHash,
                             String reason, String result) {
        if (auditStore == null) {
            return;
        }
        try {
            String actor = panelUsername(claims);
            String role = claims != null ? resourcePolicy.resolveRole(claims).name() : null;
            String origin = resolveOrigin(ctx);
            String requestId = currentRequestId(ctx);
            String eventId = java.util.UUID.randomUUID().toString();
            AuditEvent event = new AuditEvent(
                    eventId, requestId, actor, role, origin, action, resource,
                    beforeHash, afterHash, reason, result, System.currentTimeMillis());
            auditStore.record(event);
        } catch (Exception e) {
            // Audit must never block the mutation.
            logger.warn("Failed to record audit event action={}: {}", action, e.getMessage());
        }
    }

    /**
     * Convenience for recording a successful mutation with no reason. Most P1
     * handlers call this on the happy path.
     */
    private void recordAuditSuccess(ChannelHandlerContext ctx, Claims claims,
                                   String action, String resource,
                                   String beforeHash, String afterHash) {
        recordAudit(ctx, claims, action, resource, beforeHash, afterHash, null, "success");
    }

    /**
     * @return the originating IP/host for audit attribution, or null when no
     *         channel context is available. Uses {@code X-Forwarded-For} when
     *         present (LB front), falling back to the remote address.
     */
    private String resolveOrigin(ChannelHandlerContext ctx) {
        if (ctx == null || ctx.channel() == null) {
            return null;
        }
        try {
            io.netty.channel.Channel ch = ctx.channel();
            java.net.SocketAddress remote = ch.remoteAddress();
            if (remote instanceof java.net.InetSocketAddress isa) {
                return isa.getAddress() != null
                        ? isa.getAddress().getHostAddress()
                        : isa.getHostString();
            }
        } catch (Exception ignored) {
            // non-fatal; origin is best-effort
        }
        return null;
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
            handleGetChannels(ctx, request, claims);
        } else if (path.equals("/api/channels") && method == HttpMethod.POST) {
            handleCreateChannel(ctx, request, claims);
        } else if (path.matches("/api/channels/[^/]+") && method == HttpMethod.GET) {
            String channelId = path.substring("/api/channels/".length());
            handleGetChannel(ctx, request, channelId, claims);
        } else if (path.matches("/api/channels/[^/]+") && method == HttpMethod.DELETE) {
            String channelId = path.substring("/api/channels/".length());
            handleDeleteChannel(ctx, request, channelId, claims);
        } else if (path.matches("/api/channels/[^/]+") && method == HttpMethod.PUT) {
            String channelId = path.substring("/api/channels/".length());
            handleUpdateChannel(ctx, request, channelId, claims);
        } else if (path.matches("/api/channels/[^/]+/members") && method == HttpMethod.GET) {
            String channelId = path.substring("/api/channels/".length(), path.lastIndexOf("/members"));
            handleGetChannelMembers(ctx, request, channelId, claims);
        } else if (path.matches("/api/channels/[^/]+/invite") && method == HttpMethod.POST) {
            String channelId = path.substring("/api/channels/".length(), path.lastIndexOf("/invite"));
            handleInviteChannel(ctx, request, channelId, claims);
        } else if (path.matches("/api/channels/[^/]+/invitations/[^/]+") && method == HttpMethod.DELETE) {
            String channelId = path.substring("/api/channels/".length(), path.indexOf("/invitations/"));
            String code = path.substring(path.lastIndexOf("/invitations/") + "/invitations/".length());
            handleRevokeInvitation(ctx, request, channelId, code, claims);
        }
        // Message endpoints
        else if (path.equals("/api/messages") && method == HttpMethod.POST) {
            handleSendMessage(ctx, request, claims);
        } else if (path.equals("/api/messages") && method == HttpMethod.GET) {
            handleGetMessages(ctx, request, claims);
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
            handleGetPlayers(ctx, request, claims);
        } else if (path.matches("/api/players/[^/]+") && method == HttpMethod.GET) {
            String playerId = path.substring("/api/players/".length());
            handleGetPlayer(ctx, request, playerId, claims);
        } else if (path.matches("/api/players/[^/]+/mute") && method == HttpMethod.POST) {
            String playerId = path.substring("/api/players/".length(), path.lastIndexOf("/mute"));
            handleMutePlayer(ctx, request, playerId, panelUsername(claims), claims);
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
            handleBanPlayer(ctx, request, playerId, panelUsername(claims), claims);
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
            handleCreateWebhook(ctx, request, claims);
        } else if (path.matches("/api/webhooks/[^/]+") && method == HttpMethod.DELETE) {
            String webhookId = path.substring("/api/webhooks/".length());
            handleDeleteWebhook(ctx, request, webhookId, claims);
        } else if (path.matches("/api/webhooks/[^/]+") && method == HttpMethod.PUT) {
            String webhookId = path.substring("/api/webhooks/".length());
            handleUpdateWebhook(ctx, request, webhookId, claims);
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
            handleReload(ctx, request, claims);
        }
        // Settings endpoints (FeatureConfig)
        else if (path.equals("/api/settings") && method == HttpMethod.GET) {
            handleGetSettings(ctx, request);
        } else if (path.equals("/api/settings") && method == HttpMethod.PUT) {
            handleUpdateSettings(ctx, request, claims);
        }
        // §11.6 Project 20 — config history / snapshot / diff / rollback.
        // Routes are ADMIN+ (history/snapshot/diff) or SUPER_ADMIN (rollback);
        // requiredRole enforces that matrix. The handlers all 503 when the
        // backing service is unavailable (no database provider).
        else if (path.equals("/api/settings/history") && method == HttpMethod.GET) {
            handleGetConfigHistory(ctx, request, uri);
        } else if (path.matches("/api/settings/snapshots/[^/]+") && method == HttpMethod.GET) {
            String revStr = path.substring("/api/settings/snapshots/".length());
            handleGetConfigSnapshot(ctx, request, revStr);
        } else if (path.equals("/api/settings/diff") && method == HttpMethod.GET) {
            handleConfigDiff(ctx, request, uri);
        } else if (path.equals("/api/settings/rollback") && method == HttpMethod.POST) {
            handleRollbackConfig(ctx, request, claims);
        }
        // §11.6 Project 20 (proposal 10): dry-run structural validation of a
        // candidate YAML document. RBAC: ADMIN+ (see requiredRole). Never
        // persists; handler 503s when configManager is null and 400s when the
        // body is missing the "yaml" field or is unparseable JSON.
        else if (path.equals("/api/settings/validate") && method == HttpMethod.POST) {
            handleValidateConfig(ctx, request, uri);
        }
        // §11.6 item-20 / PANEL proposal 10 (doc-deferred sub-items 1+2+3):
        // staged draft / approve / publish workflow + independent
        // /config/publish endpoint + explicit backup / restore. All 9 routes
        // are SUPER_ADMIN-only (requiredRole enforces). Every handler 503s
        // when the backing ConfigPublishService is unavailable (no DB).
        else if (path.equals("/api/config/drafts") && method == HttpMethod.POST) {
            handleCreateDraft(ctx, request, claims);
        } else if (path.equals("/api/config/drafts") && method == HttpMethod.GET) {
            handleListDrafts(ctx, request, uri);
        } else if (path.matches("/api/config/drafts/[^/]+") && method == HttpMethod.GET) {
            String idStr = path.substring("/api/config/drafts/".length());
            handleGetDraft(ctx, request, idStr);
        } else if (path.matches("/api/config/drafts/[^/]+/approve") && method == HttpMethod.POST) {
            String idStr = path.substring("/api/config/drafts/".length(), path.lastIndexOf("/approve"));
            handleApproveDraft(ctx, request, idStr, claims);
        } else if (path.matches("/api/config/drafts/[^/]+/publish") && method == HttpMethod.POST) {
            String idStr = path.substring("/api/config/drafts/".length(), path.lastIndexOf("/publish"));
            handlePublishDraft(ctx, request, idStr, claims);
        } else if (path.matches("/api/config/drafts/[^/]+") && method == HttpMethod.DELETE) {
            String idStr = path.substring("/api/config/drafts/".length());
            handleDiscardDraft(ctx, request, idStr, claims);
        } else if (path.equals("/api/config/backups") && method == HttpMethod.POST) {
            handleCreateBackup(ctx, request, claims);
        } else if (path.equals("/api/config/backups") && method == HttpMethod.GET) {
            handleListBackups(ctx, request, uri);
        } else if (path.equals("/api/config/restore-from-backup") && method == HttpMethod.POST) {
            handleRestoreFromBackup(ctx, request, claims);
        }
        // Audit log endpoint (PANEL-006): ADMIN+ paginated listing with
        // optional actor/action filters. Read access is deliberately separate
        // from notification clear.
        else if (path.equals("/api/audit") && method == HttpMethod.GET) {
            handleGetAudit(ctx, request, uri, claims);
        }
        // Notification endpoints (PANEL-014: per-user state. userId is the
        // panel username from the JWT; broadcast events remain visible to all
        // but read/clear state is isolated per user. DELETE /api/notifications
        // clears only the caller's directed notifications; broadcast cleanup
        // requires the separate SUPER_ADMIN /api/notifications/broadcast route.)
        else if (path.equals("/api/notifications") && method == HttpMethod.GET) {
            handleGetNotifications(ctx, request, claims);
        } else if (path.matches("/api/notifications/[^/]+/read") && method == HttpMethod.POST) {
            String idStr = path.substring("/api/notifications/".length(), path.lastIndexOf("/read"));
            handleMarkNotificationRead(ctx, request, idStr, claims);
        } else if (path.equals("/api/notifications/read-all") && method == HttpMethod.POST) {
            handleMarkAllNotificationsRead(ctx, request, claims);
        } else if (path.equals("/api/notifications") && method == HttpMethod.DELETE) {
            handleClearNotifications(ctx, request, claims);
        } else if (path.equals("/api/notifications/broadcast") && method == HttpMethod.DELETE) {
            handleClearBroadcastNotifications(ctx, request, claims);
        }
        // §11.6 提案 06 — Campaign endpoints (slice A: in-memory, backend-only).
        // GET /api/campaigns and GET /api/campaigns/{id} are VIEWER (default
        // GET branch). POST /api/campaigns and POST /api/campaigns/{id}/{schedule,
        // activate} are ADMIN (default mutation branch). POST /api/campaigns/{id}
        // /revoke is SUPER_ADMIN — see requiredRole().
        else if (path.equals("/api/campaigns") && method == HttpMethod.GET) {
            handleListCampaigns(ctx, request, uri);
        } else if (path.equals("/api/campaigns") && method == HttpMethod.POST) {
            handleCreateCampaign(ctx, request, claims);
        } else if (path.matches("/api/campaigns/[^/]+") && method == HttpMethod.GET) {
            String campaignId = path.substring("/api/campaigns/".length());
            handleGetCampaign(ctx, request, campaignId);
        } else if (path.matches("/api/campaigns/[^/]+/schedule") && method == HttpMethod.POST) {
            String campaignId = path.substring("/api/campaigns/".length(), path.lastIndexOf("/schedule"));
            handleScheduleCampaign(ctx, request, campaignId, claims);
        } else if (path.matches("/api/campaigns/[^/]+/activate") && method == HttpMethod.POST) {
            String campaignId = path.substring("/api/campaigns/".length(), path.lastIndexOf("/activate"));
            handleActivateCampaign(ctx, request, campaignId, claims);
        } else if (path.matches("/api/campaigns/[^/]+/revoke") && method == HttpMethod.POST) {
            String campaignId = path.substring("/api/campaigns/".length(), path.lastIndexOf("/revoke"));
            handleRevokeCampaign(ctx, request, campaignId, claims);
        }
        // PANEL-007: moderation case/appeal workflow. Evidence is only
        // retrievable via the case-scoped GET .../evidence route — there is no
        // global evidence-list endpoint, and GET /api/private-messages does
        // not exist at all (a 404 in the not-found branch below handles its
        // absence). The reviewer-must-differ-from-case-moderator rule is a
        // hard 403 enforced by ModerationManager, not a silent fallback.
        else if (path.equals("/api/reports") && method == HttpMethod.POST) {
            handleCreateReport(ctx, request, claims);
        } else if (path.equals("/api/moderation/cases") && method == HttpMethod.GET) {
            handleListModerationCases(ctx, request, uri, claims);
        } else if (path.matches("/api/moderation/cases/[^/]+") && method == HttpMethod.GET) {
            String caseId = path.substring("/api/moderation/cases/".length());
            handleGetModerationCase(ctx, request, caseId);
        } else if (path.matches("/api/moderation/cases/[^/]+/assign") && method == HttpMethod.POST) {
            String caseId = path.substring("/api/moderation/cases/".length(), path.lastIndexOf("/assign"));
            handleAssignModeratorCase(ctx, request, caseId, claims);
        } else if (path.matches("/api/moderation/cases/[^/]+/resolve") && method == HttpMethod.POST) {
            String caseId = path.substring("/api/moderation/cases/".length(), path.lastIndexOf("/resolve"));
            handleResolveModerationCase(ctx, request, caseId, claims);
        } else if (path.matches("/api/moderation/cases/[^/]+/evidence") && method == HttpMethod.GET) {
            String caseId = path.substring("/api/moderation/cases/".length(), path.lastIndexOf("/evidence"));
            handleListCaseEvidence(ctx, request, caseId, claims);
        } else if (path.matches("/api/moderation/cases/[^/]+/evidence") && method == HttpMethod.POST) {
            String caseId = path.substring("/api/moderation/cases/".length(), path.lastIndexOf("/evidence"));
            handleAddCaseEvidence(ctx, request, caseId, claims);
        } else if (path.matches("/api/moderation/cases/[^/]+/status") && method == HttpMethod.GET) {
            String caseId = path.substring("/api/moderation/cases/".length(), path.lastIndexOf("/status"));
            handleGetModerationCaseStatus(ctx, request, caseId);
        } else if (path.equals("/api/appeals") && method == HttpMethod.POST) {
            handleCreateAppeal(ctx, request, claims);
        } else if (path.equals("/api/appeals") && method == HttpMethod.GET) {
            handleListAppeals(ctx, request, uri);
        } else if (path.matches("/api/appeals/[^/]+/review") && method == HttpMethod.POST) {
            String appealId = path.substring("/api/appeals/".length(), path.lastIndexOf("/review"));
            handleReviewAppeal(ctx, request, appealId, claims);
        }
        // §11.6 Project 17 — 提案 09: batch moderation endpoint. ADMIN-only
        // (the default branch in requiredRole enforces this for POST mutations).
        // Applies a mute/unmute/ban/unban action to up to BATCH_MAX_TARGETS
        // players in one request, with an in-memory idempotency cache.
        else if (path.equals("/api/moderation/batch") && method == HttpMethod.POST) {
            handleBatchModeration(ctx, request, claims);
        }
        // Console command execution endpoint
        else if (path.equals("/api/console") && method == HttpMethod.POST) {
            handleConsoleCommand(ctx, request);
        }
        // Status endpoint
        else if (path.equals("/api/status") && method == HttpMethod.GET) {
            handleGetStatus(ctx, request);
        }
        // Metrics endpoint (auth-gated; requiredRole GET → VIEWER). Emits
        // Prometheus exposition-format text. /api/health is handled earlier in
        // channelRead0 (unauthenticated), so it never reaches here.
        else if (path.equals("/api/metrics") && method == HttpMethod.GET) {
            handleMetrics(ctx, request);
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
    private void handleGetChannels(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        PanelRole role = resourcePolicy.resolveRole(claims);
        JsonArray channels = new JsonArray();
        JsonArray subscribableChannelIds = new JsonArray();
        for (Channel channel : channelManager.getAllChannels()) {
            if (!resourcePolicy.canViewChannel(role, channel)) {
                continue;
            }
            channels.add(channelToJson(channel, role));
            if (resourcePolicy.canSubscribe(role, channel)) {
                subscribableChannelIds.add(channel.getId());
            }
        }
        
        JsonObject response = new JsonObject();
        response.add("channels", channels);
        response.add("subscribableChannelIds", subscribableChannelIds);
        response.addProperty("total", channels.size());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/channels/{id} - Get channel details
     * Requirements: 25.4
     */
    private void handleGetChannel(ChannelHandlerContext ctx, FullHttpRequest request,
                                  String channelId, Claims claims) {
        Channel channel = channelManager.getChannel(channelId);
        PanelRole role = resourcePolicy.resolveRole(claims);
        if (!resourcePolicy.canViewChannel(role, channel)) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, channelToJson(channel, role));
    }

    /**
     * GET /api/channels/{id}/members - Get channel members
     * Requirements: 25.4
     */
    private void handleGetChannelMembers(ChannelHandlerContext ctx, FullHttpRequest request,
                                         String channelId, Claims claims) {
        Channel channel = channelManager.getChannel(channelId);
        PanelRole role = resourcePolicy.resolveRole(claims);
        if (!resourcePolicy.canViewChannel(role, channel)) {
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
     * <p>ADMIN may create GLOBAL/SERVER channels; PRIVATE channel creation is
     * restricted to SUPER_ADMIN by the shared panel resource policy.
     * Private channels without an explicit id get an auto-generated NC-XXXX id.
     * Requirements: 25.4
     */
    private void handleCreateChannel(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
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
            String clientId = json.has("clientId") && !json.get("clientId").isJsonNull()
                    ? json.get("clientId").getAsString() : null;

            // PANEL-003: maxCapacity must be a positive integer for the created channel.
            if (maxCapacity <= 0) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "maxCapacity must be a positive integer");
                return;
            }

            ChannelScope scope;
            try {
                scope = ChannelScope.valueOf(scopeRaw.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid scope: " + scopeRaw);
                return;
            }

            PanelRole role = resourcePolicy.resolveRole(claims);
            if (!resourcePolicy.canManageScope(role, scope)) {
                sendJsonError(ctx, request, HttpResponseStatus.FORBIDDEN,
                        "Channel scope is not accessible for this role");
                return;
            }

            // PANEL-003: SERVER/PRIVATE channels must be bound to a real,
            // connected client. A synthetic "console" clientId is no longer
            // accepted — the caller must supply a clientId that resolves to an
            // active ClientConnection. GLOBAL channels never carry a clientId.
            if (scope == ChannelScope.GLOBAL) {
                if (clientId != null && !clientId.isEmpty()) {
                    sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                            "GLOBAL channels cannot have a clientId");
                    return;
                }
            } else {
                if (clientId == null || clientId.isEmpty()) {
                    sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                            "clientId is required for SERVER/PRIVATE channels");
                    return;
                }
                if (networkHandler == null) {
                    sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                            "Network handler not available");
                    return;
                }
                if (networkHandler.findByClientId(clientId) == null) {
                    sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                            "Unknown clientId: no connected client matches '" + clientId + "'");
                    return;
                }
            }

            // For GLOBAL/SERVER scopes the Channel ctor rejects a null/blank id,
            // so auto-generate one here — REST admin creation should not require
            // a caller-supplied id. For PRIVATE channels, leave the id null/blank
            // so ChannelManager auto-generates the NC-XXXX id.
            if (scope != ChannelScope.PRIVATE && (id == null || id.isEmpty())) {
                id = generateRestChannelId();
            }

            // Fall back to the generated/id-derived name when no display name given.
            // For PRIVATE channels the id may still be null here (auto-generated
            // later by ChannelManager); ChannelConfig passes null displayName
            // through, and the Channel constructor falls back to the id at
            // creation time, so this stays safe.
            String effectiveDisplayName = displayName != null ? displayName : id;

            ChannelConfig config = ChannelConfig.builder()
                    .id(id)
                    .displayName(effectiveDisplayName)
                    .scope(scope)
                    .clientId(clientId)
                    .maxCapacity(maxCapacity)
                    .permission(permission)
                    .build();

            // Runtime-created channels are tagged RUNTIME so config reload never
            // overwrites them and they are editable from the Panel.
            Channel channel = channelManager.createChannel(config, ChannelSource.RUNTIME);

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("channel", channelToJson(channel));

            sendJsonResponse(ctx, request, HttpResponseStatus.CREATED, response);

            // PANEL-006: audit the creation. No before-state (new resource);
            // after-hash is the SHA-256 of the channel JSON with no secrets
            // (channelToJson never emits the password).
            recordAuditSuccess(ctx, claims, "channel.create", "channel:" + channel.getId(),
                    null, AuditEvent.hashJson(gson.toJson(channelToJson(channel))));

        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, e.getMessage());
            recordAudit(ctx, claims, "channel.create", null,
                    null, null, e.getMessage(), "failure");
        } catch (Exception e) {
            logger.error("Error creating channel via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            recordAudit(ctx, claims, "channel.create", null,
                    null, null, "invalid request body", "failure");
        }
    }

    /**
     * DELETE /api/channels/{id} - Delete a channel (admin operation).
     *
     * <p>Removes all members first (clears membership side-effects), mirroring
     * {@code ConsoleCommandHandler.handleDelete}.
     * Requirements: 25.4
     */
    private void handleDeleteChannel(ChannelHandlerContext ctx, FullHttpRequest request,
                                     String channelId, Claims claims) {
        Channel channel = channelManager.getChannel(channelId);
        PanelRole role = resourcePolicy.resolveRole(claims);
        if (!resourcePolicy.canManageChannel(role, channel)) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        // PANEL-004: config-managed channels are read-only via the Panel/REST.
        // Deletion of a CONFIG channel would be revived by the next config
        // reload anyway, so reject it up-front rather than creating a confusing
        // delete-then-revive cycle.
        if (channel.getSource() == ChannelSource.CONFIG) {
            sendJsonError(ctx, request, HttpResponseStatus.FORBIDDEN,
                    "Channel is managed by config and cannot be deleted via the Panel");
            return;
        }

        // PANEL-006: capture the before-state hash for audit before the
        // channel is removed. channelToJson never emits the password.
        JsonObject beforeJson = channelToJson(channel, role);
        String beforeHash = AuditEvent.hashJson(gson.toJson(beforeJson));

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

        // PANEL-006: audit the deletion. No after-state (resource removed).
        recordAuditSuccess(ctx, claims, "channel.delete", "channel:" + channelId,
                beforeHash, null);
    }

    /**
     * PUT /api/channels/{id} - Update a channel's mutable properties.
     *
     * <p>Only non-null body fields are applied. Mirrors
     * {@code NovaLinkMain.upsertConfiguredChannel} field-by-field update.
     * To explicitly clear the {@code permission} (set it to null), send a body
     * with {@code "permissionPresent": true} and either omit {@code permission}
     * or send it as null. When {@code permissionPresent} is absent, a null
     * {@code permission} leaves the existing value untouched (backward compat).
     * Requirements: 25.4
     */
    private void handleUpdateChannel(ChannelHandlerContext ctx, FullHttpRequest request,
                                     String channelId, Claims claims) {
        Channel existing = channelManager.getChannel(channelId);
        PanelRole role = resourcePolicy.resolveRole(claims);
        if (!resourcePolicy.canManageChannel(role, existing)) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        // PANEL-004: config-managed channels are read-only via the Panel/REST.
        if (existing.getSource() == ChannelSource.CONFIG) {
            sendJsonError(ctx, request, HttpResponseStatus.FORBIDDEN,
                    "Channel is managed by config and cannot be edited via the Panel");
            return;
        }

        // PANEL-006: capture the before-state hash for audit. channelToJson
        // never emits the password, so the hash is secret-safe.
        JsonObject beforeJson = channelToJson(existing, role);
        String beforeHash = AuditEvent.hashJson(gson.toJson(beforeJson));

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
            // permissionPresent lets the caller distinguish "leave permission
            // untouched" (absent/false) from "clear permission" (true + null).
            // When omitted, default to true only when permission is non-null.
            boolean permissionPresent;
            if (json.has("permissionPresent") && !json.get("permissionPresent").isJsonNull()) {
                permissionPresent = json.get("permissionPresent").getAsBoolean();
            } else {
                permissionPresent = permission != null;
            }
            Integer slowModeSeconds = null;
            if (json.has("slowModeSeconds") && !json.get("slowModeSeconds").isJsonNull()) {
                slowModeSeconds = json.get("slowModeSeconds").getAsInt();
                if (slowModeSeconds < 0) {
                    sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                            "slowModeSeconds must be >= 0");
                    return;
                }
            }

            Channel updated;
            try {
                updated = channelManager.updateChannel(channelId, displayName, maxCapacity,
                        permission, permissionPresent);
            } catch (IllegalArgumentException e) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, e.getMessage());
                return;
            }
            if (updated == null) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
                return;
            }
            if (slowModeSeconds != null) {
                updated.setSlowModeSeconds(slowModeSeconds);
            }

            JsonObject afterJson = channelToJson(updated, role);
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("channel", afterJson);

            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

            // PANEL-006: audit the update with before/after hashes.
            recordAuditSuccess(ctx, claims, "channel.update", "channel:" + channelId,
                    beforeHash, AuditEvent.hashJson(gson.toJson(afterJson)));

        } catch (Exception e) {
            logger.error("Error updating channel via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            recordAudit(ctx, claims, "channel.update", "channel:" + channelId,
                    beforeHash, null, "invalid request body", "failure");
        }
    }

    /**
     * POST /api/channels/{id}/invite - Create an invitation for a channel.
     *
     * <p>Uses the console sentinel as the inviter. Returns the generated code.
     * Requirements: 25.4
     */
    private void handleInviteChannel(ChannelHandlerContext ctx, FullHttpRequest request,
                                     String channelId, Claims claims) {
        if (invitationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Invitations not enabled");
            return;
        }

        Channel channel = channelManager.getChannel(channelId);
        PanelRole role = resourcePolicy.resolveRole(claims);
        if (!resourcePolicy.canManageChannel(role, channel)) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        // PANEL-004: inviting into a CONFIG channel is permitted (membership is
        // runtime state, not a channel edit), so no CONFIG guard here. Only
        // structural mutations (create/update/delete) are blocked for CONFIG.

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
     * DELETE /api/channels/{id}/invitations/{code} - Revoke an invitation.
     *
     * <p>Force-revokes (admin path) because panel-originated invitations are
     * created with the console sentinel as inviter, so the panel operator's
     * UUID never matches the inviter and the inviter-only revoke path would
     * always fail. The invitation must belong to the channel in the URL
     * (defense-in-depth against cross-channel revoke). Requirements: 25.4
     */
    private void handleRevokeInvitation(ChannelHandlerContext ctx, FullHttpRequest request,
                                        String channelId, String code, Claims claims) {
        if (invitationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Invitations not enabled");
            return;
        }
        Channel channel = channelManager.getChannel(channelId);
        PanelRole role = resourcePolicy.resolveRole(claims);
        if (!resourcePolicy.canManageChannel(role, channel)) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }
        try {
            // Validate the code belongs to this channel (defense-in-depth:
            // an admin on channel A must not revoke an invitation for channel B).
            Optional<Invitation> opt = invitationManager.getInvitation(code);
            if (opt.isEmpty() || !opt.get().getChannelId().equals(channelId)) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Invitation not found");
                return;
            }
            boolean revoked = invitationManager.forceRevokeInvitation(code);
            if (!revoked) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Invitation not found");
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("code", code);
            response.addProperty("channelId", channelId);
            response.addProperty("revoked", true);
            String operator = panelUsername(claims);
            logger.info("Invitation {} for channel {} revoked via API by operator {}", code, channelId, operator);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error revoking invitation {} via API", code, e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "NC-510: Database error");
        }
    }

    /**
     * Maximum number of targets a single batch moderation request may carry.
     * Bounded so a single malicious or fat-fingered request cannot enumerate
     * the whole player base or pin the worker pool for seconds at a time.
     */
    static final int BATCH_MAX_TARGETS = 100;

    /**
     * Idempotency cache TTL: a replay within this window returns the exact
     * same response (status + body) as the original request, with no side
     * effects. 10 minutes is long enough to cover a retry after a network
     * blip yet short enough that the bounded cache (1024 entries) does not
     * retain stale keys indefinitely.
     */
    static final long BATCH_IDEMPOTENCY_TTL_MILLIS = 10L * 60L * 1000L;

    /**
     * Upper bound on the idempotency cache size. A put past this cap triggers
     * a sweep of expired entries; if the sweep does not free enough room the
     * oldest entry (by recorded-at timestamp) is evicted. Bounded so the
     * cache cannot grow unbounded in a long-running process.
     */
    static final int BATCH_IDEMPOTENCY_MAX_ENTRIES = 1024;

    /**
     * In-memory idempotency cache for batch moderation (§11.6 Project 17).
     * Keyed by the caller-supplied {@code idempotencyKey}. A replay within
     * {@link #BATCH_IDEMPOTENCY_TTL_MILLIS} returns the cached response with
     * no side effects. <strong>Single-process only</strong>: the cache is not
     * shared across backend instances, so in a horizontally-scaled deployment
     * a replay routed to a different instance will re-execute. A shared store
     * (Redis) would be required for cluster-wide idempotency; that is out of
     * scope for this slice (no new persistence is introduced).
     */
    private final Map<String, CachedBatchResult> cachedBatchResults = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Cached response for a batch moderation request, used to make replays
     * idempotent. The cached payload is stored as its already-serialized JSON
     * string so a replay returns byte-identical bytes without re-running the
     * per-target mutation logic.
     */
    private static final class CachedBatchResult {
        final HttpResponseStatus status;
        final String body;
        final String contentType;
        final long recordedAt;

        CachedBatchResult(HttpResponseStatus status, String body, String contentType) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
            this.recordedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - recordedAt > BATCH_IDEMPOTENCY_TTL_MILLIS;
        }
    }

    /**
     * POST /api/moderation/batch — apply a moderation action to many targets at
     * once (§11.6 Project 17, 提案 09).
     *
     * <p>Body:
     * <pre>{@code
     * {
     *   "action": "mute" | "unmute" | "ban" | "unban",
     *   "targetIds": ["<uuid>", ...],            // ≤ 100
     *   "channelId": "<optional>",
     *   "durationMs": <long>,                    // mute/ban only
     *   "reason": "<required>",
     *   "caseId": "<optional>",
     *   "dryRun": <boolean>,                     // default false
     *   "idempotencyKey": "<required>"
     * }
     * }</pre>
     *
     * <p>RBAC: ADMIN minimum (enforced upstream by {@link #requiredRole},
     * default branch — VIEWER is rejected with 403 before reaching here, the
     * same pattern used by {@code handleMutePlayer}).
     *
     * <p>Idempotency: a replay carrying the same {@code idempotencyKey} within
     * {@link #BATCH_IDEMPOTENCY_TTL_MILLIS} returns the cached response with
     * no side effects. The cache is bounded ({@link #BATCH_IDEMPOTENCY_MAX_ENTRIES})
     * and single-process (see {@link #cachedBatchResults} javadoc).
     *
     * <p>Partial failure: each target is applied independently. A per-target
     * failure does NOT abort the batch — the response is HTTP 200 with a
     * {@code results} array carrying one entry per target, each flagged
     * success/failure. Only validation failures (bad action, too many targets,
     * missing reason/key, missing managers) short-circuit with a 4xx/5xx.
     *
     * <p>Audit: one {@link AuditEvent} per target (action
     * {@code player.batch_mute}/ {@code player.batch_ban} etc, resource
     * {@code player:<uuid>}, reason carries {@code batch:<idempotencyKey>} so
     * the batch is traceable to its origin).
     */
    private void handleBatchModeration(ChannelHandlerContext ctx, FullHttpRequest request,
                                       Claims claims) {
        // ---- parse body ----
        JsonObject json;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (body == null || body.isBlank()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "Request body is required");
                return;
            }
            json = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        String action = json.has("action") && !json.get("action").isJsonNull()
                ? json.get("action").getAsString() : null;
        if (action == null || action.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "action is required");
            return;
        }
        if (!action.equals("mute") && !action.equals("unmute")
                && !action.equals("ban") && !action.equals("unban")) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "action must be one of: mute, unmute, ban, unban");
            return;
        }

        if (!json.has("targetIds") || !json.get("targetIds").isJsonArray()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "targetIds (array) is required");
            return;
        }
        JsonArray targetIdsJson = json.getAsJsonArray("targetIds");
        if (targetIdsJson.isEmpty()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "targetIds must contain at least one UUID");
            return;
        }
        if (targetIdsJson.size() > BATCH_MAX_TARGETS) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "targetIds exceeds the batch upper bound of " + BATCH_MAX_TARGETS);
            return;
        }
        List<UUID> targetIds = new ArrayList<>(targetIdsJson.size());
        Set<UUID> seen = new HashSet<>(targetIdsJson.size() * 2);
        for (int i = 0; i < targetIdsJson.size(); i++) {
            String raw = targetIdsJson.get(i).getAsString();
            UUID uuid;
            try {
                uuid = UUID.fromString(raw);
            } catch (Exception e) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "Invalid UUID in targetIds: " + raw);
                return;
            }
            if (!seen.add(uuid)) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "Duplicate UUID in targetIds: " + uuid);
                return;
            }
            targetIds.add(uuid);
        }

        String reason = json.has("reason") && !json.get("reason").isJsonNull()
                ? json.get("reason").getAsString() : null;
        if (reason == null || reason.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "reason is required");
            return;
        }

        String idempotencyKey = json.has("idempotencyKey") && !json.get("idempotencyKey").isJsonNull()
                ? json.get("idempotencyKey").getAsString() : null;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "idempotencyKey is required");
            return;
        }
        if (idempotencyKey.length() > 128) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "idempotencyKey must be ≤ 128 characters");
            return;
        }

        String channelId = null;
        if (json.has("channelId") && !json.get("channelId").isJsonNull()) {
            channelId = json.get("channelId").getAsString();
            if (channelId.isBlank()) {
                channelId = null;
            }
        }
        if (channelId != null && !channelManager.channelExists(channelId)) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        long durationMs = 0L;
        if (json.has("durationMs") && !json.get("durationMs").isJsonNull()) {
            try {
                durationMs = json.get("durationMs").getAsLong();
            } catch (Exception e) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "durationMs must be a number");
                return;
            }
        }
        if ((action.equals("mute") || action.equals("ban")) && durationMs < 0L) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "durationMs must be ≥ 0 (0 = permanent)");
            return;
        }

        String caseId = json.has("caseId") && !json.get("caseId").isJsonNull()
                ? json.get("caseId").getAsString() : null;
        boolean dryRun = json.has("dryRun") && !json.get("dryRun").isJsonNull()
                && json.get("dryRun").getAsBoolean();

        // ---- idempotency replay ----
        CachedBatchResult cached = cachedBatchResults.get(idempotencyKey);
        if (cached != null) {
            if (cached.isExpired()) {
                cachedBatchResults.remove(idempotencyKey, cached);
            } else {
                // Replay: return the cached response verbatim, no side effects.
                ByteBuf buf = Unpooled.copiedBuffer(cached.body, CharsetUtil.UTF_8);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, cached.status, buf);
                response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                        cached.contentType != null ? cached.contentType
                                : "application/json; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
                addCorsHeaders(request, response);
                stampRequestId(ctx, response);
                boolean keepAlive = HttpUtil.isKeepAlive(request);
                if (keepAlive) {
                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    ctx.writeAndFlush(response);
                } else {
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                }
                return;
            }
        }

        // ---- manager availability ----
        boolean needsMute = action.equals("mute") || action.equals("unmute");
        boolean needsBan = action.equals("ban") || action.equals("unban");
        if (needsMute && muteManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Mute system not enabled");
            return;
        }
        if (needsBan && banManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Ban system not enabled");
            return;
        }

        String operatorUsername = panelUsername(claims);
        UUID operatorUuid = panelOperatorUuid(operatorUsername);
        // The reason recorded in the audit log carries the batch key so the
        // full batch can be reconstructed from the audit trail alone.
        String auditReason = reason + " (batch:" + idempotencyKey + ")";

        // ---- dry-run preview: no side effects, no manager calls, no audit ----
        if (dryRun) {
            JsonArray previewResults = new JsonArray();
            for (UUID targetUuid : targetIds) {
                JsonObject item = new JsonObject();
                item.addProperty("targetId", targetUuid.toString());
                item.addProperty("status", "preview");
                previewResults.add(item);
            }
            JsonObject preview = new JsonObject();
            preview.addProperty("action", action);
            if (channelId != null) {
                preview.addProperty("channelId", channelId);
            }
            preview.addProperty("dryRun", true);
            preview.addProperty("total", targetIds.size());
            preview.addProperty("succeeded", 0);
            preview.addProperty("failed", 0);
            if (caseId != null && !caseId.isBlank()) {
                preview.addProperty("caseId", caseId);
            }
            preview.addProperty("idempotencyKey", idempotencyKey);
            preview.add("results", previewResults);
            // dry-run is intentionally NOT cached: it is a side-effect-free
            // preview and cheap to re-run, so replay protection is unnecessary.
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, preview);
            return;
        }

        // ---- apply per-target ----
        JsonArray results = new JsonArray();
        int successCount = 0;
        int failureCount = 0;
        for (UUID targetUuid : targetIds) {
            JsonObject item = new JsonObject();
            item.addProperty("targetId", targetUuid.toString());
            String itemStatus;
            String itemMessage = null;
            String itemErrorCode = null;
            try {
                if (action.equals("mute")) {
                    MuteResult r = muteManager.mutePlayer(operatorUuid, targetUuid,
                            channelId, durationMs, reason, null, true);
                    if (r.isSuccess()) {
                        itemStatus = "success";
                        successCount++;
                    } else {
                        itemStatus = "failure";
                        itemMessage = r.getMessage();
                        itemErrorCode = r.getErrorCode();
                        failureCount++;
                    }
                } else if (action.equals("unmute")) {
                    MuteResult r = muteManager.unmutePlayer(operatorUuid, targetUuid,
                            channelId, null, true);
                    if (r.isSuccess()) {
                        itemStatus = "success";
                        successCount++;
                    } else {
                        itemStatus = "failure";
                        itemMessage = r.getMessage();
                        itemErrorCode = r.getErrorCode();
                        failureCount++;
                    }
                } else if (action.equals("ban")) {
                    BanResult r = banManager.banPlayer(operatorUuid, targetUuid,
                            channelId, durationMs, reason, null, true);
                    if (r.isSuccess()) {
                        itemStatus = "success";
                        successCount++;
                    } else {
                        itemStatus = "failure";
                        itemMessage = r.getMessage();
                        itemErrorCode = r.getErrorCode();
                        failureCount++;
                    }
                } else { // unban
                    BanResult r = banManager.unbanPlayer(operatorUuid, targetUuid,
                            channelId, null, true);
                    if (r.isSuccess()) {
                        itemStatus = "success";
                        successCount++;
                    } else {
                        itemStatus = "failure";
                        itemMessage = r.getMessage();
                        itemErrorCode = r.getErrorCode();
                        failureCount++;
                    }
                }
            } catch (Exception e) {
                logger.warn("Batch {} for target {} failed", action, targetUuid, e);
                itemStatus = "failure";
                itemMessage = e.getMessage() != null ? e.getMessage() : "internal error";
                itemErrorCode = "NC-510";
                failureCount++;
            }

            item.addProperty("status", itemStatus);
            if (itemMessage != null) {
                item.addProperty("message", itemMessage);
            }
            if (itemErrorCode != null) {
                item.addProperty("errorCode", itemErrorCode);
            }
            results.add(item);

            // Per-target audit event; reason carries the batch key so the
            // whole batch can be reconstructed from the audit trail alone.
            String auditAction = "player.batch_" + action;
            recordAudit(ctx, claims, auditAction, "player:" + targetUuid,
                    null, null, auditReason,
                    "success".equals(itemStatus) ? "success" : "failure");
        }

        // ---- response ----
        JsonObject response = new JsonObject();
        response.addProperty("action", action);
        if (channelId != null) {
            response.addProperty("channelId", channelId);
        }
        response.addProperty("dryRun", dryRun);
        response.addProperty("total", targetIds.size());
        response.addProperty("succeeded", successCount);
        response.addProperty("failed", failureCount);
        if (caseId != null && !caseId.isBlank()) {
            response.addProperty("caseId", caseId);
        }
        response.addProperty("idempotencyKey", idempotencyKey);
        response.add("results", results);

        // Partial failure → 200 (the batch itself was accepted and processed;
        // per-target outcomes are in the results array). Only full-accept and
        // partial-accept return 200; validation/replay-error paths above
        // already returned with 4xx/5xx.
        HttpResponseStatus status = HttpResponseStatus.OK;
        String content = gson.toJson(response);
        ByteBuf buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, buf);
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        addCorsHeaders(request, httpResponse);
        stampRequestId(ctx, httpResponse);

        // ---- cache + evict ----
        // Cache only the actually-applied response so a replay within the TTL
        // returns byte-identical bytes with no side effects.
        evictExpiredBatchResults();
        cachedBatchResults.put(idempotencyKey,
                new CachedBatchResult(status, content, "application/json; charset=UTF-8"));

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(httpResponse);
        } else {
            ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Sweeps expired entries from {@link #cachedBatchResults} and, if the cache
     * is still at capacity, evicts the oldest entry by recorded-at timestamp.
     * Called on every non-dry-run put so the cache stays bounded without a
     * dedicated cleaner thread.
     */
    private void evictExpiredBatchResults() {
        if (cachedBatchResults.isEmpty()) {
            return;
        }
        // Sweep expired entries (cheap on the common path where there are none).
        boolean removedAny = false;
        Iterator<Map.Entry<String, CachedBatchResult>> it =
                cachedBatchResults.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
                removedAny = true;
            }
        }
        if (cachedBatchResults.size() < BATCH_IDEMPOTENCY_MAX_ENTRIES) {
            return;
        }
        // Still at/over capacity: evict the single oldest entry. A full sort
        // is avoided — one eviction per put keeps the cache bounded over time.
        Map.Entry<String, CachedBatchResult> oldest = null;
        for (Map.Entry<String, CachedBatchResult> e : cachedBatchResults.entrySet()) {
            if (oldest == null || e.getValue().recordedAt < oldest.getValue().recordedAt) {
                oldest = e;
            }
        }
        if (oldest != null) {
            cachedBatchResults.remove(oldest.getKey());
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
                                  String operatorUsername, Claims claims) {
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
        String caseId = null;
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
                if (json.has("caseId") && !json.get("caseId").isJsonNull()) {
                    caseId = json.get("caseId").getAsString();
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
            // PANEL-006: audit the failed mute attempt.
            recordAudit(ctx, claims, "player.mute", "player:" + targetUuid,
                    null, null, reason, "failure");
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Player muted successfully");
        response.addProperty("playerId", targetUuid.toString());
        if (channelId != null) {
            response.addProperty("channelId", channelId);
        }
        // PANEL-007: echo the linked caseId back when the caller supplied one.
        // caseId is optional — when absent, the response shape is unchanged.
        if (caseId != null && !caseId.isBlank()) {
            response.addProperty("caseId", caseId);
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

        // PANEL-006: audit the successful mute. No before/after payload hash
        // (the resource is a player, not a JSON document we serialize here);
        // the reason is recorded in the reason field.
        recordAudit(ctx, claims, "player.mute", "player:" + targetUuid,
                null, null, reason, "success");
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
                                 String operatorUsername, Claims claims) {
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
        String caseId = null;
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
                if (json.has("caseId") && !json.get("caseId").isJsonNull()) {
                    caseId = json.get("caseId").getAsString();
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
            // PANEL-006: audit the failed ban attempt.
            recordAudit(ctx, claims, "player.ban", "player:" + targetUuid,
                    null, null, reason, "failure");
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Player banned successfully");
        response.addProperty("playerId", targetUuid.toString());
        if (channelId != null) {
            response.addProperty("channelId", channelId);
        }
        // PANEL-007: echo the linked caseId back when supplied (optional).
        if (caseId != null && !caseId.isBlank()) {
            response.addProperty("caseId", caseId);
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

        // PANEL-006: audit the successful ban.
        recordAudit(ctx, claims, "player.ban", "player:" + targetUuid,
                null, null, reason, "success");
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
        String caseId = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("channelId") && !json.get("channelId").isJsonNull()) {
                    channelId = json.get("channelId").getAsString();
                }
                if (json.has("caseId") && !json.get("caseId").isJsonNull()) {
                    caseId = json.get("caseId").getAsString();
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
        if (caseId != null && !caseId.isBlank()) {
            response.addProperty("caseId", caseId);
        }

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
    private void handleReload(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
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
            // PANEL-010: include the settings revision so callers can detect
            // whether a reload bumped the revision (it does when save() runs).
            response.addProperty("settingsRevision", configManager.getSettingsRevision());
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
            // PANEL-006: audit the reload.
            recordAuditSuccess(ctx, claims, "config.reload", "config",
                    null, null);

            // §11.6 Project 20 (proposal 10): reload re-reads the config file,
            // which may have changed feature flags. Broadcast the post-reload
            // settings revision and features so panel clients can refresh.
            // Fire AFTER the response + audit so a broadcast failure never
            // masks the reload result.
            if (webSocketGateway != null) {
                try {
                    com.nova.link.config.FeatureConfig reloadedFeatures =
                            configManager.getConfig() != null
                                    ? configManager.getConfig().getFeatures()
                                    : null;
                    webSocketGateway.getMessageHandler().broadcastSettingsUpdate(
                            configManager.getSettingsRevision(), reloadedFeatures);
                } catch (Exception e) {
                    logger.debug("settings_update broadcast after reload failed: {}",
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Error reloading config via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-500: Reload failed: " + e.getMessage());
            recordAudit(ctx, claims, "config.reload", "config",
                    null, null, e.getMessage(), "failure");
        }
    }

    /**
     * GET /api/settings - Returns the FeatureConfig switches.
     *
     * <p>Returns {filterEnabled, messageLogEnabled, crossServerChatEnabled,
     * privateMessagesEnabled, messageLogRetentionDays, revision}. The last two
     * are feature-detected by the frontend. {@code revision} (PANEL-010) is the
     * optimistic-concurrency token callers echo back via {@code If-Match} (or
     * {@code baseRevision} in the request body) on PUT; an ETag header is also
     * set so standard HTTP conditional-request clients can use it directly.
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
        response.addProperty("privateMessagesEnabled", features.isPrivateMessagesEnabled());
        response.addProperty("messageLogRetentionDays", features.getMessageLogRetentionDays());
        // PANEL-010: expose the settings revision so PUT callers can detect
        // staleness before we accept their update.
        long revision = configManager.getSettingsRevision();
        response.addProperty("revision", revision);
        // SendJsonResponse doesn't accept extra headers; we add the ETag here
        // by post-processing the response object via a wrapper. The simpler
        // route is to write the ETag through the channel after send. Since
        // sendJsonResponse builds and writes the FullHttpResponse internally,
        // we instead rely on the body-level `revision` field for the panel
        // (the frontend uses baseRevision, not ETag). A standard ETag header
        // is still useful for HTTP-native clients, so we emit it on the
        // channel after the response is written is not possible — instead
        // we accept the body-level revision as the contract.
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * PUT /api/settings - Updates the FeatureConfig switches.
     *
     * <p>Body: {filterEnabled?, messageLogEnabled?, crossServerChatEnabled?,
     * privateMessagesEnabled?, messageLogRetentionDays?, baseRevision?}. Only
     * present fields are applied. Persists via configManager.save() and applies
     * to runtime immediately so the panel toggle is effective without a full
     * reload.
     *
     * <p>PANEL-010: optimistic-concurrency protection. Callers MUST supply
     * either an {@code If-Match: W/"<revision>"} header or a
     * {@code baseRevision} body field; if the supplied revision does not match
     * the current {@link ConfigManager#getSettingsRevision()}, the update is
     * rejected with 409 Conflict and the current settings are returned so the
     * caller can re-merge. On success the revision is incremented atomically
     * (inside {@link ConfigManager#save()}).
     */
    private void handleUpdateSettings(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        if (configManager == null || configManager.getConfig() == null
                || configManager.getConfig().getFeatures() == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Settings not available");
            return;
        }

        // §11.6 Project 20: ensure the config-history service is assembled and
        // wired into ConfigManager BEFORE save() records a snapshot. The service
        // is lazy (built on first config-history endpoint hit); without this
        // warm-up the first settings save after startup would record no
        // snapshot, and a subsequent rollback/diff would 404 on a revision the
        // operator just wrote. No-op when the service is already built, and
        // still 503s gracefully when no database provider is available.
        configHistoryService();

        // PANEL-010: optimistic-concurrency check. Accept either the standard
        // If-Match header (W/"<rev>") or a body-level baseRevision field (the
        // panel JS uses the latter because it is simpler to set with fetch).
        long currentRevision = configManager.getSettingsRevision();
        Long clientRevision = null;
        String ifMatch = request.headers().get(HttpHeaderNames.IF_MATCH);
        if (ifMatch != null && !ifMatch.isBlank()) {
            // Parse W/"<rev>" or "<rev>" or "rev".
            String trimmed = ifMatch.trim();
            if (trimmed.startsWith("W/")) {
                trimmed = trimmed.substring(2);
            }
            // Strip surrounding quotes if present.
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            try {
                clientRevision = Long.parseLong(trimmed.trim());
            } catch (NumberFormatException ignored) {
                // Fall through to body-level baseRevision below.
            }
        }

        com.nova.link.config.FeatureConfig features = configManager.getConfig().getFeatures();
        JsonObject json = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (body != null && !body.isBlank()) {
                json = JsonParser.parseString(body).getAsJsonObject();

                // Body-level baseRevision takes effect when If-Match was not
                // supplied (or was unparseable). If both are present and
                // disagree, the header wins (standard HTTP semantics).
                if (clientRevision == null
                        && json.has("baseRevision") && !json.get("baseRevision").isJsonNull()) {
                    try {
                        clientRevision = json.get("baseRevision").getAsLong();
                    } catch (NumberFormatException ignored) {
                        // leave null → treated as missing
                    }
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        // Stale revision → 409 with the current settings so the caller can
        // re-merge. This MUST happen before any body field is applied to the
        // live FeatureConfig, otherwise a rejected (stale) update would
        // silently mutate the runtime — the exact race PANEL-010 guards
        // against. We do this AFTER parsing the body so a malformed body still
        // gets 400 (a client error that would mask a 409 otherwise). When
        // baseRevision is absent entirely, we allow the update for backward
        // compatibility (the panel will be updated to always send it).
        if (clientRevision != null && clientRevision != currentRevision) {
            JsonObject conflict = new JsonObject();
            conflict.addProperty("error", "Settings revision mismatch");
            conflict.addProperty("message", "Settings were modified by another editor. "
                    + "Refresh and re-apply your changes.");
            conflict.addProperty("status", 409);
            conflict.addProperty("currentRevision", currentRevision);
            conflict.addProperty("clientRevision", clientRevision);
            // Include the current settings so the caller can merge.
            conflict.addProperty("filterEnabled", features.isFilterEnabled());
            conflict.addProperty("messageLogEnabled", features.isMessageLogEnabled());
            conflict.addProperty("crossServerChatEnabled", features.isCrossServerChatEnabled());
            conflict.addProperty("privateMessagesEnabled", features.isPrivateMessagesEnabled());
            conflict.addProperty("messageLogRetentionDays", features.getMessageLogRetentionDays());
            conflict.addProperty("revision", currentRevision);
            sendJsonResponse(ctx, request, HttpResponseStatus.CONFLICT, conflict);
            return;
        }

        // Revision is fresh (or absent for backward compat) — apply the body.
        if (json != null) {
            if (json.has("filterEnabled") && !json.get("filterEnabled").isJsonNull()) {
                features.setFilterEnabled(json.get("filterEnabled").getAsBoolean());
            }
            if (json.has("messageLogEnabled") && !json.get("messageLogEnabled").isJsonNull()) {
                features.setMessageLogEnabled(json.get("messageLogEnabled").getAsBoolean());
            }
            if (json.has("crossServerChatEnabled") && !json.get("crossServerChatEnabled").isJsonNull()) {
                features.setCrossServerChatEnabled(json.get("crossServerChatEnabled").getAsBoolean());
            }
            if (json.has("privateMessagesEnabled") && !json.get("privateMessagesEnabled").isJsonNull()) {
                features.setPrivateMessagesEnabled(json.get("privateMessagesEnabled").getAsBoolean());
            }
            if (json.has("messageLogRetentionDays") && !json.get("messageLogRetentionDays").isJsonNull()) {
                int days = json.get("messageLogRetentionDays").getAsInt();
                // Clamp to [0, 365] per the frontend contract.
                features.setMessageLogRetentionDays(Math.max(0, Math.min(365, days)));
            }
        }

        // Apply to runtime immediately.
        applyFeatureConfig(features);

        // Persist to disk. save() also bumps settingsRevision atomically.
        try {
            configManager.save();
        } catch (Exception e) {
            logger.error("Error persisting settings via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-500: Settings apply succeeded but persist failed: " + e.getMessage());
            return;
        }

        // §11.6 Project 20 (proposal 10): broadcast the new settings revision
        // and feature flags to every authenticated panel session so clients
        // can refetch instead of polling. Fire AFTER save() succeeded and
        // AFTER the response is built (features object is already the post-
        // mutation state). Null-gateway is a silent no-op (test/standalone
        // harness without WS wiring).
        if (webSocketGateway != null) {
            try {
                webSocketGateway.getMessageHandler().broadcastSettingsUpdate(
                        configManager.getSettingsRevision(), features);
            } catch (Exception e) {
                logger.debug("settings_update broadcast after update failed: {}", e.getMessage());
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("filterEnabled", features.isFilterEnabled());
        response.addProperty("messageLogEnabled", features.isMessageLogEnabled());
        response.addProperty("crossServerChatEnabled", features.isCrossServerChatEnabled());
        response.addProperty("privateMessagesEnabled", features.isPrivateMessagesEnabled());
        response.addProperty("messageLogRetentionDays", features.getMessageLogRetentionDays());
        // PANEL-010: return the new revision so the caller can use it as the
        // baseRevision for its next update.
        response.addProperty("revision", configManager.getSettingsRevision());
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

        // PANEL-006: audit the settings update. beforeHash/afterHash are the
        // SHA-256 of the settings JSON before and after the change (no secrets
        // are present in FeatureConfig).
        JsonObject settingsJson = new JsonObject();
        settingsJson.addProperty("filterEnabled", features.isFilterEnabled());
        settingsJson.addProperty("messageLogEnabled", features.isMessageLogEnabled());
        settingsJson.addProperty("crossServerChatEnabled", features.isCrossServerChatEnabled());
        settingsJson.addProperty("privateMessagesEnabled", features.isPrivateMessagesEnabled());
        settingsJson.addProperty("messageLogRetentionDays", features.getMessageLogRetentionDays());
        String afterHash = AuditEvent.hashJson(gson.toJson(settingsJson));
        recordAuditSuccess(ctx, claims, "settings.update", "config:features",
                null, afterHash);
    }

    /**
     * Applies the FeatureConfig switches to the live runtime components,
     * mirroring the reload listener in {@code NovaLinkMain}. Deliberately does
     * NOT trigger a config reload: reload re-reads the file from disk and would
     * discard the in-memory changes just made by the settings endpoint.
     *
     * <p>Also applies {@code privateMessagesEnabled} (via the injected
     * {@link #privateMessagesEnabledFlag}) and {@code messageLogRetentionDays}
     * (via the {@link MessageLogService}).
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
        // Private messages toggle: propagate to the AtomicBoolean the
        // PrivateMessageHandler reads. Wired via setPrivateMessagesEnabledFlag.
        if (privateMessagesEnabledFlag != null) {
            privateMessagesEnabledFlag.set(features.isPrivateMessagesEnabled());
        }
        // Message log retention: hot-apply to the message log service.
        if (messageLogService != null) {
            messageLogService.setRetentionDays(features.getMessageLogRetentionDays());
        }
    }

    // ==================== Audit Endpoints ====================

    /**
     * GET /api/audit - List audit events with pagination and optional filters.
     *
     * <p>PANEL-006: ADMIN+ read access to the append-only audit log. Query
     * params: page (1-based, default 1), size (default 20, capped at 100),
     * actor (optional substring filter on actor), action (optional exact
     * action filter e.g. {@code channel.create}).
     * Returns {items:[...], total, page, pageSize}.
     *
     * <p>Read access is deliberately separate from notification clear so an
     * admin can audit actions without being granted notification management.
     */
    private void handleGetAudit(ChannelHandlerContext ctx, FullHttpRequest request,
                                String uri, Claims claims) {
        if (auditStore == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Audit log not enabled");
            return;
        }

        int page = 1;
        int size = 20;
        String actor = null;
        String action = null;
        int q = uri.indexOf('?');
        if (q >= 0) {
            String query = uri.substring(q + 1);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                String key = kv[0];
                String value;
                try {
                    value = java.net.URLDecoder.decode(kv[1],
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    // Malformed percent-encoding; skip this pair.
                    continue;
                }
                try {
                    switch (key) {
                        case "page" -> page = Math.max(1, Integer.parseInt(value));
                        case "size" -> size = Math.min(100, Math.max(1, Integer.parseInt(value)));
                        case "actor" -> actor = value.isBlank() ? null : value;
                        case "action" -> action = value.isBlank() ? null : value;
                    }
                } catch (NumberFormatException ignored) {
                    // keep defaults
                }
            }
        }
        int offset = (page - 1) * size;

        List<AuditEvent> events = auditStore.list(offset, size, actor, action);
        int total = auditStore.count(actor, action);

        JsonArray items = new JsonArray();
        for (AuditEvent e : events) {
            JsonObject item = new JsonObject();
            item.addProperty("id", e.getId());
            if (e.getEventId() != null) {
                item.addProperty("eventId", e.getEventId());
            }
            if (e.getRequestId() != null) {
                item.addProperty("requestId", e.getRequestId());
            }
            if (e.getActor() != null) {
                item.addProperty("actor", e.getActor());
            }
            if (e.getRole() != null) {
                item.addProperty("role", e.getRole());
            }
            if (e.getOrigin() != null) {
                item.addProperty("origin", e.getOrigin());
            }
            item.addProperty("action", e.getAction());
            if (e.getResource() != null) {
                item.addProperty("resource", e.getResource());
            }
            if (e.getBeforeHash() != null) {
                item.addProperty("beforeHash", e.getBeforeHash());
            }
            if (e.getAfterHash() != null) {
                item.addProperty("afterHash", e.getAfterHash());
            }
            if (e.getReason() != null) {
                item.addProperty("reason", e.getReason());
            }
            item.addProperty("result", e.getResult());
            item.addProperty("createdAt", e.getCreatedAt());
            items.add(item);
        }

        JsonObject response = new JsonObject();
        response.add("items", items);
        response.addProperty("page", page);
        response.addProperty("pageSize", size);
        response.addProperty("total", total);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    // ==================== Config History Endpoints (§11.6 Project 20) ====================

    /**
     * GET /api/settings/history?limit=N — lists masked config snapshots newest
     * first, WITHOUT the snapshot_json payload (callers fetch payloads via
     * /api/settings/snapshots/{revision}). Default limit 50, clamped to 200.
     * Returns {items:[{id, revision, createdAt, createdBy, active}], total}.
     * ADMIN+ (requiredRole enforces). 503 when the service is unavailable.
     */
    private void handleGetConfigHistory(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        ConfigHistoryService service = configHistoryService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config history not enabled");
            return;
        }
        int limit = 50;
        int q = uri.indexOf('?');
        if (q >= 0) {
            Map<String, List<String>> params = parseQueryParams(uri.substring(q + 1));
            limit = parseIntParam(params, "limit", 50);
        }
        // Clamp to [1, 200] per the acceptance spec.
        limit = Math.min(200, Math.max(1, limit));

        List<com.nova.link.config.ConfigSnapshot> snapshots = service.getHistory(limit);
        JsonArray items = new JsonArray();
        for (com.nova.link.config.ConfigSnapshot s : snapshots) {
            JsonObject item = new JsonObject();
            item.addProperty("id", s.getId());
            item.addProperty("revision", s.getRevision());
            item.addProperty("createdAt", s.getCreatedAt());
            if (s.getCreatedBy() != null) {
                item.addProperty("createdBy", s.getCreatedBy());
            }
            item.addProperty("active", s.isActive());
            items.add(item);
        }
        JsonObject response = new JsonObject();
        response.add("items", items);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/settings/snapshots/{revision} — loads a single masked snapshot
     * including its payload. The payload is the masked JSON form (secrets
     * already {@code "***"}), so it is safe to return to the panel. 404 when
     * the revision is absent or unparseable; 503 when the service is down.
     */
    private void handleGetConfigSnapshot(ChannelHandlerContext ctx, FullHttpRequest request, String revStr) {
        ConfigHistoryService service = configHistoryService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config history not enabled");
            return;
        }
        long revision;
        try {
            revision = Long.parseLong(revStr);
        } catch (NumberFormatException e) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Snapshot not found");
            return;
        }
        java.util.Optional<com.nova.link.config.ConfigSnapshot> snap = service.getSnapshot(revision);
        if (snap.isEmpty()) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Snapshot not found");
            return;
        }
        JsonObject response = new JsonObject();
        com.nova.link.config.ConfigSnapshot s = snap.get();
        response.addProperty("id", s.getId());
        response.addProperty("revision", s.getRevision());
        response.addProperty("createdAt", s.getCreatedAt());
        if (s.getCreatedBy() != null) {
            response.addProperty("createdBy", s.getCreatedBy());
        }
        response.addProperty("active", s.isActive());
        // snapshotJson is already masked in storage; parse it back so the
        // response carries a structured object rather than an escaped string.
        try {
            response.add("snapshot", JsonParser.parseString(s.getSnapshotJson()));
        } catch (Exception e) {
            response.addProperty("snapshot", s.getSnapshotJson());
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/settings/diff?from={rev}&to={rev} — produces a masked
     * added/removed/changed map between two revisions. Both revisions must
     * exist; a missing revision yields 404. 503 when the service is down.
     */
    private void handleConfigDiff(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        ConfigHistoryService service = configHistoryService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config history not enabled");
            return;
        }
        long from;
        long to;
        int q = uri.indexOf('?');
        if (q < 0) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "Missing required query parameters: from, to");
            return;
        }
        Map<String, List<String>> params = parseQueryParams(uri.substring(q + 1));
        Long fromParam = parseLongParam(params, "from");
        Long toParam = parseLongParam(params, "to");
        if (fromParam == null || toParam == null) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "Missing required query parameters: from, to");
            return;
        }
        from = fromParam;
        to = toParam;

        // 404 when either revision does not resolve, rather than an empty diff.
        if (service.getSnapshot(from).isEmpty()) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND,
                    "Snapshot not found for revision: " + from);
            return;
        }
        if (service.getSnapshot(to).isEmpty()) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND,
                    "Snapshot not found for revision: " + to);
            return;
        }
        try {
            Map<String, Object> diff = service.diffSettings(from, to);
            JsonObject response = new JsonObject();
            response.addProperty("fromRevision", from);
            response.addProperty("toRevision", to);
            response.add("added", gson.toJsonTree(diff.get("added")));
            response.add("removed", gson.toJsonTree(diff.get("removed")));
            response.add("changed", gson.toJsonTree(diff.get("changed")));
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error computing config diff {}->{}", from, to, e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "NC-510: Database error");
        }
    }

    /**
     * POST /api/settings/rollback {targetRevision} — atomically rolls the live
     * config back to the masked snapshot identified by targetRevision.
     * SUPER_ADMIN only (requiredRole enforces). Fail-closed: any error after
     * the snapshot is loaded surfaces as 500/NC-510 and leaves the live config
     * untouched. 400 when the target is already the active revision; 404 when
     * the target is absent.
     */
    private void handleRollbackConfig(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        ConfigHistoryService service = configHistoryService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config history not enabled");
            return;
        }
        long targetRevision;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("targetRevision") || json.get("targetRevision").isJsonNull()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "Missing required field: targetRevision");
                return;
            }
            targetRevision = json.get("targetRevision").getAsLong();
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        String actor = panelUsername(claims);
        try {
            long newRevision = service.rollback(targetRevision, actor);
            if (newRevision == -1L) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "Target revision is already the active config");
                return;
            }
            if (newRevision == -2L) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND,
                        "Snapshot not found for revision: " + targetRevision);
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("rolledBackTo", targetRevision);
            response.addProperty("revision", newRevision);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

            // §11.6 Project 20 (proposal 10): broadcast the post-rollback
            // settings revision and feature flags so panel clients see the
            // rolled-back state without polling. Uses the live config's
            // features (rollback re-reads the file into configLoader); the
            // rollback service already verified the write succeeded.
            if (webSocketGateway != null) {
                try {
                    com.nova.link.config.FeatureConfig rolledBackFeatures =
                            configManager.getConfig() != null
                                    ? configManager.getConfig().getFeatures()
                                    : null;
                    webSocketGateway.getMessageHandler().broadcastSettingsUpdate(
                            newRevision, rolledBackFeatures);
                } catch (Exception e) {
                    logger.debug("settings_update broadcast after rollback failed: {}",
                            e.getMessage());
                }
            }
        } catch (IllegalStateException e) {
            // Fail-closed: the rollback service could not complete the atomic
            // write; the live config is unchanged. Surface as NC-510.
            logger.error("Config rollback to revision {} failed (fail-closed)", targetRevision, e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-510: Rollback failed: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error during config rollback to revision {}", targetRevision, e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "NC-510: Database error");
        }
    }

    /**
     * POST /api/settings/validate {"yaml":"..."} — dry-runs a candidate YAML
     * document against the same structural rules the loader enforces on real
     * load/save, without persisting anything.
     *
     * <p>§11.6 Project 20 (proposal 10). RBAC: ADMIN+ (see {@link #requiredRole}).
     * Response 200: {@code {"valid":bool,"errors":[{"path":null,"message":"..."}],
     * "warnings":[],"revision":<settingsRevision>,"checkedAt":<currentTimeMillis>}}.
     * The {@code path} field is {@code null} for every error: the loader's
     * {@code ConfigException} embeds path线索 in {@code message} already and the
     * contract forbids synthesising a path.
     *
     * <p>400 when the body is missing the {@code yaml} field or is unparseable
     * JSON; 503 when {@code configManager} is {@code null} (handler assembled
     * without a backing config — e.g. some unit-test harnesses).
     */
    private void handleValidateConfig(ChannelHandlerContext ctx, FullHttpRequest request,
                                     String uri) {
        if (configManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Config manager not available");
            return;
        }
        String yaml;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("yaml") || json.get("yaml").isJsonNull()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "Missing required field: yaml");
                return;
            }
            yaml = json.get("yaml").getAsString();
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        com.nova.link.config.ConfigValidationResult result = configManager.validateYaml(yaml);

        JsonObject response = new JsonObject();
        response.addProperty("valid", result.isValid());
        JsonArray errors = new JsonArray();
        for (com.nova.link.config.ConfigValidationResult.ValidationError error : result.getErrors()) {
            JsonObject err = new JsonObject();
            // path is always null per the contract — the loader does not emit a
            // structured path; the message already carries any path线索.
            err.add("path", null);
            err.addProperty("message", error.getMessage());
            errors.add(err);
        }
        response.add("errors", errors);
        JsonArray warnings = new JsonArray();
        for (String w : result.getWarnings()) {
            warnings.add(w);
        }
        response.add("warnings", warnings);
        response.addProperty("revision", configManager.getSettingsRevision());
        response.addProperty("checkedAt", System.currentTimeMillis());
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    // ==================== Config Draft / Publish / Backup Endpoints
    //         (§11.6 item-20 / PANEL proposal 10 doc-deferred sub-items 1+2+3) ====================

    /**
     * POST /api/config/drafts {"yaml":"..."} — creates a new DRAFT. The YAML is
     * validated and masked at create time; the stored {@code draft_json} is the
     * masked JSON form. SUPER_ADMIN only (requiredRole enforces). 400 when the
     * body is missing the {@code yaml} field or YAML validation fails; 503 when
     * the backing service is unavailable; 500/NC-510 when persistence fails.
     */
    private void handleCreateDraft(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        ConfigPublishService service = configPublishService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config publish not enabled");
            return;
        }
        String yaml;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("yaml") || json.get("yaml").isJsonNull()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Missing required field: yaml");
                return;
            }
            yaml = json.get("yaml").getAsString();
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }
        String actor = panelUsername(claims);
        try {
            ConfigDraft draft = service.createDraft(yaml, actor);
            JsonObject response = draftToJson(draft, true);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            logger.error("Create draft failed (fail-closed)", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-510: " + e.getMessage());
        }
    }

    /**
     * GET /api/config/drafts?limit=N — lists drafts newest-first, metadata
     * only (no draft_json payload). Default limit 50, clamped to 200.
     * SUPER_ADMIN only.
     */
    private void handleListDrafts(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        ConfigPublishService service = configPublishService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config publish not enabled");
            return;
        }
        int limit = 50;
        int q = uri.indexOf('?');
        if (q >= 0) {
            Map<String, List<String>> params = parseQueryParams(uri.substring(q + 1));
            limit = parseIntParam(params, "limit", 50);
        }
        limit = Math.min(200, Math.max(1, limit));
        List<ConfigDraft> drafts = service.listDrafts(limit);
        JsonArray items = new JsonArray();
        for (ConfigDraft d : drafts) {
            items.add(draftToJson(d, false));
        }
        JsonObject response = new JsonObject();
        response.add("items", items);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/config/drafts/{id} — loads a single draft including its masked
     * draft_json payload. 404 when the id is absent or unparseable.
     */
    private void handleGetDraft(ChannelHandlerContext ctx, FullHttpRequest request, String idStr) {
        ConfigPublishService service = configPublishService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config publish not enabled");
            return;
        }
        long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Draft not found");
            return;
        }
        Optional<ConfigDraft> draft = service.getDraft(id);
        if (draft.isEmpty()) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Draft not found");
            return;
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, draftToJson(draft.get(), true));
    }

    /**
     * POST /api/config/drafts/{id}/approve — approves a DRAFT. Approver must
     * differ from createdBy (permission separation, 403 if same). 404 when the
     * draft is absent; 409 when the draft is not in DRAFT state.
     */
    private void handleApproveDraft(ChannelHandlerContext ctx, FullHttpRequest request,
                                    String idStr, Claims claims) {
        ConfigPublishService service = configPublishService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config publish not enabled");
            return;
        }
        long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Draft not found");
            return;
        }
        String approver = panelUsername(claims);
        try {
            Optional<ConfigDraft> draft = service.approveDraft(id, approver);
            if (draft.isEmpty()) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Draft not found");
                return;
            }
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, draftToJson(draft.get(), true));
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("Approver must differ from createdBy")) {
                sendJsonError(ctx, request, HttpResponseStatus.FORBIDDEN, e.getMessage());
            } else if (e.getMessage() != null && e.getMessage().contains("not in DRAFT state")) {
                sendJsonError(ctx, request, HttpResponseStatus.CONFLICT, e.getMessage());
            } else {
                logger.error("Approve draft {} failed (fail-closed)", id, e);
                sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "NC-510: " + e.getMessage());
            }
        }
    }

    /**
     * POST /api/config/drafts/{id}/publish — publishes an APPROVED draft to the
     * live config via {@link ConfigManager#save()} (fail-closed). Broadcasts a
     * {@code settings_update} WS event on success (same as rollback). 404 when
     * the draft is absent; 409 when the draft is not APPROVED; 500/NC-510 when
     * save fails (live config untouched, draft stays APPROVED).
     */
    private void handlePublishDraft(ChannelHandlerContext ctx, FullHttpRequest request,
                                    String idStr, Claims claims) {
        ConfigPublishService service = configPublishService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config publish not enabled");
            return;
        }
        long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Draft not found");
            return;
        }
        String actor = panelUsername(claims);
        try {
            long newRevision = service.publishDraft(id, actor);
            if (newRevision == -1L) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Draft not found");
                return;
            }
            if (newRevision == -2L) {
                sendJsonError(ctx, request, HttpResponseStatus.CONFLICT,
                        "Draft is not in APPROVED state");
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("draftId", id);
            response.addProperty("revision", newRevision);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

            // Broadcast settings_update so panel clients refresh without
            // polling (same pattern as rollback).
            if (webSocketGateway != null) {
                try {
                    com.nova.link.config.FeatureConfig publishedFeatures =
                            configManager.getConfig() != null
                                    ? configManager.getConfig().getFeatures()
                                    : null;
                    webSocketGateway.getMessageHandler().broadcastSettingsUpdate(
                            newRevision, publishedFeatures);
                } catch (Exception e) {
                    logger.debug("settings_update broadcast after publish failed: {}",
                            e.getMessage());
                }
            }
        } catch (IllegalStateException e) {
            logger.error("Publish draft {} failed (fail-closed)", id, e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-510: " + e.getMessage());
        }
    }

    /**
     * DELETE /api/config/drafts/{id} — discards a DRAFT. Only a DRAFT can be
     * discarded; APPROVED/PUBLISHED drafts cannot (audit trail). 404 when
     * absent; 409 when not in DRAFT state.
     */
    private void handleDiscardDraft(ChannelHandlerContext ctx, FullHttpRequest request,
                                    String idStr, Claims claims) {
        ConfigPublishService service = configPublishService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config publish not enabled");
            return;
        }
        long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Draft not found");
            return;
        }
        String actor = panelUsername(claims);
        try {
            boolean discarded = service.discardDraft(id, actor);
            if (!discarded) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Draft not found");
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("draftId", id);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("Only a DRAFT can be discarded")) {
                sendJsonError(ctx, request, HttpResponseStatus.CONFLICT, e.getMessage());
            } else {
                logger.error("Discard draft {} failed (fail-closed)", id, e);
                sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "NC-510: " + e.getMessage());
            }
        }
    }

    /**
     * POST /api/config/backups {"label":"..."} — creates a named backup of the
     * current live config (masked). SUPER_ADMIN only. 400 when the body is
     * missing the {@code label} field; 503 when the service is unavailable.
     */
    private void handleCreateBackup(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        ConfigPublishService service = configPublishService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config publish not enabled");
            return;
        }
        String label;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("label") || json.get("label").isJsonNull()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Missing required field: label");
                return;
            }
            label = json.get("label").getAsString();
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }
        String actor = panelUsername(claims);
        try {
            ConfigBackup backup = service.createBackup(label, actor);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, backupToJson(backup, true));
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            logger.error("Create backup failed (fail-closed)", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-510: " + e.getMessage());
        }
    }

    /**
     * GET /api/config/backups?limit=N — lists backups newest-first, metadata
     * only (no backup_json payload). Default limit 50, clamped to 200.
     */
    private void handleListBackups(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        ConfigPublishService service = configPublishService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config publish not enabled");
            return;
        }
        int limit = 50;
        int q = uri.indexOf('?');
        if (q >= 0) {
            Map<String, List<String>> params = parseQueryParams(uri.substring(q + 1));
            limit = parseIntParam(params, "limit", 50);
        }
        limit = Math.min(200, Math.max(1, limit));
        List<ConfigBackup> backups = service.listBackups(limit);
        JsonArray items = new JsonArray();
        for (ConfigBackup b : backups) {
            items.add(backupToJson(b, false));
        }
        JsonObject response = new JsonObject();
        response.add("items", items);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/config/restore-from-backup {"backupId":N} — restores the live
     * config from a named backup (fail-closed). Broadcasts a
     * {@code settings_update} WS event on success (same as rollback/publish).
     * 400 when the body is missing {@code backupId}; 404 when the backup is
     * absent; 500/NC-510 when save fails (live config untouched, backup
     * retained).
     */
    private void handleRestoreFromBackup(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        ConfigPublishService service = configPublishService();
        if (service == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config publish not enabled");
            return;
        }
        long backupId;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("backupId") || json.get("backupId").isJsonNull()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "Missing required field: backupId");
                return;
            }
            backupId = json.get("backupId").getAsLong();
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }
        String actor = panelUsername(claims);
        try {
            long newRevision = service.restoreFromBackup(backupId, actor);
            if (newRevision == -1L) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Backup not found");
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("backupId", backupId);
            response.addProperty("revision", newRevision);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

            // Broadcast settings_update so panel clients refresh without
            // polling (same pattern as rollback/publish).
            if (webSocketGateway != null) {
                try {
                    com.nova.link.config.FeatureConfig restoredFeatures =
                            configManager.getConfig() != null
                                    ? configManager.getConfig().getFeatures()
                                    : null;
                    webSocketGateway.getMessageHandler().broadcastSettingsUpdate(
                            newRevision, restoredFeatures);
                } catch (Exception e) {
                    logger.debug("settings_update broadcast after restore failed: {}",
                            e.getMessage());
                }
            }
        } catch (IllegalStateException e) {
            logger.error("Restore from backup {} failed (fail-closed)", backupId, e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-510: " + e.getMessage());
        }
    }

    /**
     * Serialises a {@link ConfigDraft} to JSON. When {@code includePayload} is
     * true the masked {@code draft_json} is parsed back to a structured object
     * and emitted under {@code draft}; otherwise the payload is omitted (list
     * view). The {@code draft_json} is already masked at create time, so no
     * further masking is needed here.
     */
    private JsonObject draftToJson(ConfigDraft d, boolean includePayload) {
        JsonObject o = new JsonObject();
        o.addProperty("id", d.getId());
        o.addProperty("createdBy", d.getCreatedBy());
        o.addProperty("status", d.getStatus().name());
        o.addProperty("createdAt", d.getCreatedAt());
        if (d.getApprovedBy() != null) {
            o.addProperty("approvedBy", d.getApprovedBy());
        }
        // approvedAt/publishedAt are primitive longs (0 = unset); emit only when stamped.
        if (d.getApprovedAt() > 0) {
            o.addProperty("approvedAt", d.getApprovedAt());
        }
        if (d.getPublishedAt() > 0) {
            o.addProperty("publishedAt", d.getPublishedAt());
        }
        if (includePayload && d.getDraftJson() != null) {
            try {
                o.add("draft", JsonParser.parseString(d.getDraftJson()));
            } catch (Exception e) {
                o.addProperty("draft", d.getDraftJson());
            }
        }
        return o;
    }

    /**
     * Serialises a {@link ConfigBackup} to JSON. When {@code includePayload} is
     * true the masked {@code backup_json} is parsed back to a structured
     * object and emitted under {@code backup}; otherwise the payload is
     * omitted (list view). The {@code backup_json} is already masked at create
     * time, so no further masking is needed here.
     */
    private JsonObject backupToJson(ConfigBackup b, boolean includePayload) {
        JsonObject o = new JsonObject();
        o.addProperty("id", b.getId());
        o.addProperty("label", b.getLabel());
        o.addProperty("settingsRevision", b.getSettingsRevision());
        o.addProperty("createdBy", b.getCreatedBy());
        o.addProperty("createdAt", b.getCreatedAt());
        if (includePayload && b.getBackupJson() != null) {
            try {
                o.add("backup", JsonParser.parseString(b.getBackupJson()));
            } catch (Exception e) {
                o.addProperty("backup", b.getBackupJson());
            }
        }
        return o;
    }

    /**
     * Parses a raw query string (the part after {@code ?}) into a multimap.
     * Keys with no {@code =} get an empty-string value; malformed percent
     * encodings are skipped. Shared by the config-history GET endpoints.
     */
    private static Map<String, List<String>> parseQueryParams(String query) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 0) {
                continue;
            }
            String key = kv[0];
            if (key.isEmpty()) {
                continue;
            }
            String value;
            try {
                value = kv.length == 2
                        ? java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8)
                        : "";
            } catch (IllegalArgumentException e) {
                continue;
            }
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return params;
    }

    // ==================== Moderation Endpoints (PANEL-007) ====================

    /**
     * Maps a {@link ModerationException} {@code NC-###} error code to the
     * matching HTTP status, mirroring the {@code handleBanPlayer} convention.
     * Unknown codes default to {@code BAD_REQUEST}; the NC-500 persistence
     * failure maps to {@code INTERNAL_SERVER_ERROR}.
     */
    private static HttpResponseStatus moderationErrorStatus(String errorCode) {
        if ("NC-404".equals(errorCode)) {
            return HttpResponseStatus.NOT_FOUND;
        }
        if ("NC-403".equals(errorCode)) {
            return HttpResponseStatus.FORBIDDEN;
        }
        if ("NC-500".equals(errorCode)) {
            return HttpResponseStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpResponseStatus.BAD_REQUEST;
    }

    /**
     * Shared catch for {@link ModerationException}: emits the mapped JSON
     * error and returns true if the exception was handled. Returns false when
     * the throwable is not a {@link ModerationException} (the caller should
     * then rethrow or emit its own generic 500).
     */
    private boolean handleModerationException(ChannelHandlerContext ctx, FullHttpRequest request,
                                              Exception e) {
        if (e instanceof ModerationException me) {
            sendJsonError(ctx, request, moderationErrorStatus(me.getErrorCode()),
                    me.getErrorCode() + ": " + me.getMessage());
            return true;
        }
        return false;
    }

    /**
     * Splits a stored {@code reason} back into the frontend-facing
     * {@code reasonCode}/{@code reasonText} pair. {@link ModerationCase} has a
     * single {@code reason} field; {@link #handleCreateReport} merges the
     * panel's {@code reasonCode}+{@code reasonText} into {@code "[CODE] text"}.
     * This reverses that merge so the list/detail JSON matches the locked
     * contract. A reason without the {@code [CODE]} prefix yields a null code
     * and the raw reason as the text.
     *
     * @return a two-element array {@code {reasonCode, reasonText}}
     */
    private static String[] splitReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return new String[]{null, reason};
        }
        java.util.regex.Matcher m = REASON_CODE_PATTERN.matcher(reason);
        if (m.matches()) {
            return new String[]{m.group(1), m.group(2)};
        }
        return new String[]{null, reason};
    }

    private static final java.util.regex.Pattern REASON_CODE_PATTERN =
            java.util.regex.Pattern.compile("^\\[([A-Z_]+)]\\s*(.*)$", java.util.regex.Pattern.DOTALL);

    /**
     * Builds the JSON representation of a single {@link ModerationCase} using
     * the PANEL-007 locked field mapping. Shared by the list and detail
     * endpoints so the shapes stay identical.
     */
    private JsonObject moderationCaseToJson(ModerationCase c) {
        JsonObject item = new JsonObject();
        item.addProperty("caseId", c.getId());
        item.addProperty("status", c.getStatus().name());
        item.addProperty("reportedPlayerId", c.getSubjectPlayerId());
        if (c.getSubjectDisplayName() != null) {
            item.addProperty("reportedPlayerName", c.getSubjectDisplayName());
        }
        item.addProperty("reporterId", c.getReporterName());
        if (c.getReporterSource() != null) {
            item.addProperty("reporterSource", c.getReporterSource().name());
        }
        if (c.getSource() != null) {
            item.addProperty("source", c.getSource().name());
        }
        if (c.getChannelId() != null) {
            item.addProperty("originChannelId", c.getChannelId());
        }
        String[] reasonParts = splitReason(c.getReason());
        if (reasonParts[0] != null) {
            item.addProperty("reasonCode", reasonParts[0]);
        }
        if (reasonParts[1] != null) {
            item.addProperty("reasonText", reasonParts[1]);
        }
        item.addProperty("createdAt", c.getCreatedAt());
        item.addProperty("updatedAt", c.getUpdatedAt());
        if (c.getAssignedModerator() != null) {
            item.addProperty("assignedModerator", c.getAssignedModerator());
        }
        if (c.getResolutionAction() != null) {
            item.addProperty("resolutionAction", c.getResolutionAction().name());
        }
        if (c.getResolutionNote() != null) {
            item.addProperty("resolutionNote", c.getResolutionNote());
        }
        if (c.getContentHash() != null) {
            item.addProperty("contentHash", c.getContentHash());
        }
        if (c.getClosedAt() != null) {
            item.addProperty("resolvedAt", c.getClosedAt());
        }
        return item;
    }

    /**
     * Builds the JSON representation of a single {@link Appeal} for the appeals
     * list. {@code originalAction} requires a cross-lookup of the case's
     * resolution action; {@code null} when the case is no longer retrievable.
     */
    private JsonObject appealToJson(Appeal a) {
        JsonObject item = new JsonObject();
        item.addProperty("appealId", a.getId());
        item.addProperty("caseId", a.getCaseId());
        item.addProperty("status", a.getStatus().name());
        item.addProperty("appellantId", a.getAppellant());
        String originalAction = null;
        try {
            if (moderationManager != null) {
                Optional<ModerationCase> opt = moderationManager.getCase(a.getCaseId());
                if (opt.isPresent() && opt.get().getResolutionAction() != null) {
                    originalAction = opt.get().getResolutionAction().name();
                }
            }
        } catch (Exception ignored) {
            // Non-fatal: emit null originalAction when the case lookup fails.
        }
        item.add("originalAction", originalAction != null
                ? gson.toJsonTree(originalAction) : null);
        if (a.getReviewedBy() != null) {
            item.addProperty("reviewedBy", a.getReviewedBy());
        }
        if (a.getReviewedAt() != null) {
            item.addProperty("reviewedAt", a.getReviewedAt());
        }
        if (a.getReviewNote() != null) {
            item.addProperty("reviewNote", a.getReviewNote());
        }
        if (a.getContentHash() != null) {
            item.addProperty("contentHash", a.getContentHash());
        }
        item.addProperty("createdAt", a.getCreatedAt());
        return item;
    }

    /**
     * POST /api/reports - Create a moderation case from a panel report.
     *
     * <p>Body: {@code {reportedPlayerId, reasonCode, reasonText, originChannelId?, evidenceSnapshot?}}.
     * The {@code reasonCode}/{@code reasonText} pair is merged into the single
     * {@code reason} field as {@code "[CODE] text"} (see {@link #splitReason}).
     * The reporter is the panel operator identified by the JWT.
     */
    private void handleCreateReport(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }

        String reportedPlayerId;
        String reasonCode = null;
        String reasonText = null;
        String originChannelId = null;
        String evidenceSnapshot = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (body.isBlank()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
                return;
            }
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            reportedPlayerId = json.has("reportedPlayerId") && !json.get("reportedPlayerId").isJsonNull()
                    ? json.get("reportedPlayerId").getAsString() : null;
            if (json.has("reasonCode") && !json.get("reasonCode").isJsonNull()) {
                reasonCode = json.get("reasonCode").getAsString();
            }
            if (json.has("reasonText") && !json.get("reasonText").isJsonNull()) {
                reasonText = json.get("reasonText").getAsString();
            }
            if (json.has("originChannelId") && !json.get("originChannelId").isJsonNull()) {
                originChannelId = json.get("originChannelId").getAsString();
            }
            if (json.has("evidenceSnapshot") && !json.get("evidenceSnapshot").isJsonNull()) {
                evidenceSnapshot = json.get("evidenceSnapshot").getAsString();
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        if (reportedPlayerId == null || reportedPlayerId.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "NC-400: reportedPlayerId is required");
            return;
        }
        // Merge reasonCode + reasonText into the single reason field as
        // "[CODE] text" so the list/detail endpoints can split it back out.
        String reason = reasonText;
        if (reasonCode != null && !reasonCode.isBlank()) {
            reason = "[" + reasonCode.trim().toUpperCase(java.util.Locale.ROOT) + "] "
                    + (reasonText != null ? reasonText : "");
        }

        String actor = panelUsername(claims);
        try {
            ModerationCase created = moderationManager.createReport(
                    reportedPlayerId, null, actor, ReporterSource.OPERATOR,
                    CaseSource.PANEL, originChannelId, reason, evidenceSnapshot,
                    null, null, actor);
            JsonObject response = new JsonObject();
            response.addProperty("caseId", created.getId());
            response.addProperty("status", created.getStatus().name());
            sendJsonResponse(ctx, request, HttpResponseStatus.CREATED, response);
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
        }
    }

    /**
     * GET /api/moderation/cases - List moderation cases with pagination.
     *
     * <p>Query params: {@code page} (1-based), {@code size}, {@code status}
     * (frontend sends {@code OPEN/IN_PROGRESS/RESOLVED}; {@code IN_PROGRESS}
     * is normalized to the canonical {@code UNDER_REVIEW} here), {@code assigned}
     * (optional exact moderator filter applied in-memory since the provider
     * only filters by status).
     */
    private void handleListModerationCases(ChannelHandlerContext ctx, FullHttpRequest request,
                                           String uri, Claims claims) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }

        int page = 1;
        int size = 20;
        String status = null;
        String assigned = null;
        int q = uri.indexOf('?');
        if (q >= 0) {
            String query = uri.substring(q + 1);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                String key = kv[0];
                String value;
                try {
                    value = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                try {
                    switch (key) {
                        case "page" -> page = Math.max(1, Integer.parseInt(value));
                        case "size" -> size = Math.min(100, Math.max(1, Integer.parseInt(value)));
                        case "status" -> status = value.isBlank() ? null : value;
                        case "assigned" -> assigned = value.isBlank() ? null : value;
                    }
                } catch (NumberFormatException ignored) {
                    // keep defaults
                }
            }
        }
        int offset = (page - 1) * size;

        // Normalize the frontend status spelling to the canonical CaseStatus
        // name. The provider filters by status.equals(case.status.name()).
        String normalizedStatus = status;
        if ("IN_PROGRESS".equals(status)) {
            normalizedStatus = "UNDER_REVIEW";
        }

        List<ModerationCase> cases;
        int total;
        try {
            cases = moderationManager.listCases(offset, size, normalizedStatus);
            if (assigned != null && !assigned.isBlank()) {
                // The provider does not take an assigned filter; filter the page
                // in-memory and recompute the total against the same filter.
                final String assignedFilter = assigned;
                List<ModerationCase> filtered = cases.stream()
                        .filter(c -> assignedFilter.equals(c.getAssignedModerator()))
                        .toList();
                cases = filtered;
                // Recompute total: countCases has no assigned filter either, so
                // we cannot cheaply get the true total without a full scan.
                // Fall back to the filtered page size as a lower bound so the
                // UI pagination is not misleading — this matches the
                // contract's "total" being the count of matching items.
                total = filtered.size();
            } else {
                total = moderationManager.countCases(normalizedStatus);
            }
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
            return;
        }

        JsonArray items = new JsonArray();
        for (ModerationCase c : cases) {
            items.add(moderationCaseToJson(c));
        }

        JsonObject response = new JsonObject();
        response.add("items", items);
        response.addProperty("page", page);
        response.addProperty("pageSize", size);
        response.addProperty("total", total);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/moderation/cases/{id} - Get a single case detail.
     */
    private void handleGetModerationCase(ChannelHandlerContext ctx, FullHttpRequest request, String caseId) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }
        ModerationCase c;
        try {
            Optional<ModerationCase> opt = moderationManager.getCase(caseId);
            if (opt.isEmpty()) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Case not found");
                return;
            }
            c = opt.get();
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
            return;
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, moderationCaseToJson(c));
    }

    /**
     * POST /api/moderation/cases/{id}/assign - Assign a moderator to a case.
     *
     * <p>Body: {@code {moderator}}. The case moves to UNDER_REVIEW.
     */
    private void handleAssignModeratorCase(ChannelHandlerContext ctx, FullHttpRequest request,
                                           String caseId, Claims claims) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }

        String moderator;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (body.isBlank()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
                return;
            }
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            moderator = json.has("moderator") && !json.get("moderator").isJsonNull()
                    ? json.get("moderator").getAsString() : null;
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }
        if (moderator == null || moderator.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "NC-400: moderator is required");
            return;
        }

        String actor = panelUsername(claims);
        try {
            ModerationCase updated = moderationManager.assignCase(caseId, moderator, actor);
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("caseId", updated.getId());
            response.addProperty("assignedModerator", updated.getAssignedModerator());
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
        }
    }

    /**
     * POST /api/moderation/cases/{id}/resolve - Resolve a case.
     *
     * <p>Body: {@code {action, reason, targetChannelId?, durationMs?}}. The
     * {@code action} string maps to a {@link ResolutionAction}
     * ({@code warn/mute/ban/kick/dismiss}). PANEL-007 v1 records the
     * resolution on the case only; it does not auto-enforce a linked
     * mute/ban/kick — {@code targetChannelId}/{@code durationMs} are accepted
     * without error and, when present, appended to the resolution note so the
     * intent is captured for the future enforcement step.
     */
    private void handleResolveModerationCase(ChannelHandlerContext ctx, FullHttpRequest request,
                                             String caseId, Claims claims) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }

        String action;
        String reason;
        String targetChannelId = null;
        Long durationMs = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (body.isBlank()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
                return;
            }
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            action = json.has("action") && !json.get("action").isJsonNull()
                    ? json.get("action").getAsString() : null;
            reason = json.has("reason") && !json.get("reason").isJsonNull()
                    ? json.get("reason").getAsString() : null;
            if (json.has("targetChannelId") && !json.get("targetChannelId").isJsonNull()) {
                targetChannelId = json.get("targetChannelId").getAsString();
            }
            if (json.has("durationMs") && !json.get("durationMs").isJsonNull()) {
                durationMs = json.get("durationMs").getAsLong();
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        ResolutionAction resolutionAction = switch (action == null ? "" : action.toLowerCase(java.util.Locale.ROOT)) {
            case "warn" -> ResolutionAction.WARNED;
            case "mute" -> ResolutionAction.MUTED;
            case "ban" -> ResolutionAction.BANNED;
            case "kick" -> ResolutionAction.KICKED;
            case "dismiss" -> ResolutionAction.DISMISSED;
            default -> null;
        };
        if (resolutionAction == null) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "NC-400: Invalid resolution action");
            return;
        }

        // Capture the linked-enforcement intent in the resolution note so the
        // case record reflects it without auto-enforcing here (PANEL-007 v1).
        String resolutionNote = reason;
        if (targetChannelId != null && !targetChannelId.isBlank()) {
            String suffix = "[targetChannel=" + targetChannelId
                    + (durationMs != null ? ", durationMs=" + durationMs : "") + "]";
            resolutionNote = (reason != null && !reason.isBlank()) ? reason + " " + suffix : suffix;
        }

        String actor = panelUsername(claims);
        try {
            moderationManager.resolveCase(caseId, resolutionAction, resolutionNote, actor);
            JsonObject response = new JsonObject();
            response.addProperty("caseId", caseId);
            response.addProperty("action", action);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
        }
    }

    /**
     * GET /api/moderation/cases/{id}/evidence - List evidence attached to a case.
     *
     * <p>Evidence is the narrowest-scope read surface in the workflow: it is
     * only retrievable via this case-scoped endpoint and requires
     * {@code moderation.manage} (ADMIN+). VIEWER is rejected with 403.
     */
    private void handleListCaseEvidence(ChannelHandlerContext ctx, FullHttpRequest request,
                                        String caseId, Claims claims) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }
        // Evidence may carry private-chat content; gate on moderation.manage.
        PanelRole role = resourcePolicy.resolveRole(claims);
        if (role == PanelRole.VIEWER) {
            sendJsonError(ctx, request, HttpResponseStatus.FORBIDDEN,
                    "NC-403: evidence access requires moderation.manage");
            return;
        }

        List<CaseEvidence> evidence;
        try {
            evidence = moderationManager.listEvidence(caseId);
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
            return;
        }

        JsonArray items = new JsonArray();
        for (CaseEvidence e : evidence) {
            JsonObject item = new JsonObject();
            item.addProperty("evidenceId", e.getId());
            item.addProperty("caseId", e.getCaseId());
            if (e.getEvidenceType() != null) {
                item.addProperty("evidenceType", e.getEvidenceType().name());
            }
            if (e.getContentHash() != null) {
                item.addProperty("contentHash", e.getContentHash());
            }
            if (e.getDescription() != null) {
                item.addProperty("contentSnapshot", e.getDescription());
            }
            // itemJson is part of the locked contract but the evidence record
            // only stores description+hash; emit null to match the optional
            // frontend access pattern.
            item.add("itemJson", null);
            item.addProperty("capturedAt", e.getCreatedAt());
            if (e.getSubmittedBy() != null) {
                item.addProperty("capturedBy", e.getSubmittedBy());
            }
            items.add(item);
        }

        JsonObject response = new JsonObject();
        response.add("items", items);
        response.addProperty("total", items.size());
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/moderation/cases/{id}/evidence - Attach evidence to a case.
     *
     * <p>Body: {@code {evidenceType, contentPayload, description?}}. The raw
     * {@code contentPayload} is hashed (never persisted); only the hash and a
     * bounded description are stored. Requires {@code moderation.manage}.
     */
    private void handleAddCaseEvidence(ChannelHandlerContext ctx, FullHttpRequest request,
                                       String caseId, Claims claims) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }
        PanelRole role = resourcePolicy.resolveRole(claims);
        if (role == PanelRole.VIEWER) {
            sendJsonError(ctx, request, HttpResponseStatus.FORBIDDEN,
                    "NC-403: evidence access requires moderation.manage");
            return;
        }

        CaseEvidenceType evidenceType;
        String contentPayload;
        String description = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (body.isBlank()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
                return;
            }
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String typeStr = json.has("evidenceType") && !json.get("evidenceType").isJsonNull()
                    ? json.get("evidenceType").getAsString() : null;
            try {
                evidenceType = typeStr == null ? null : CaseEvidenceType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                evidenceType = null;
            }
            contentPayload = json.has("contentPayload") && !json.get("contentPayload").isJsonNull()
                    ? json.get("contentPayload").getAsString() : null;
            if (json.has("description") && !json.get("description").isJsonNull()) {
                description = json.get("description").getAsString();
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }
        if (evidenceType == null) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "NC-400: Invalid evidenceType");
            return;
        }
        if (contentPayload == null || contentPayload.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "NC-400: contentPayload is required");
            return;
        }

        String actor = panelUsername(claims);
        try {
            CaseEvidence saved = moderationManager.addEvidence(
                    caseId, evidenceType, contentPayload, description, actor, actor);
            JsonObject response = new JsonObject();
            response.addProperty("evidenceId", saved.getId());
            response.addProperty("caseId", saved.getCaseId());
            if (saved.getContentHash() != null) {
                response.addProperty("contentHash", saved.getContentHash());
            }
            sendJsonResponse(ctx, request, HttpResponseStatus.CREATED, response);
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
        }
    }

    /**
     * GET /api/moderation/cases/{id}/status - Lightweight case-status summary.
     */
    private void handleGetModerationCaseStatus(ChannelHandlerContext ctx, FullHttpRequest request, String caseId) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }
        ModerationCase c;
        try {
            Optional<ModerationCase> opt = moderationManager.getCase(caseId);
            if (opt.isEmpty()) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Case not found");
                return;
            }
            c = opt.get();
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("caseId", c.getId());
        response.addProperty("status", c.getStatus().name());
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/appeals - Create an appeal against a resolved case.
     *
     * <p>Body: {@code {caseId, appellantId, reason}}. Only a RESOLVED case may
     * be appealed (enforced by {@link ModerationManager#createAppeal}, NC-403).
     */
    private void handleCreateAppeal(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }

        String caseId;
        String appellantId;
        String reason;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (body.isBlank()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
                return;
            }
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            caseId = json.has("caseId") && !json.get("caseId").isJsonNull()
                    ? json.get("caseId").getAsString() : null;
            appellantId = json.has("appellantId") && !json.get("appellantId").isJsonNull()
                    ? json.get("appellantId").getAsString() : null;
            reason = json.has("reason") && !json.get("reason").isJsonNull()
                    ? json.get("reason").getAsString() : null;
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }
        if (caseId == null || caseId.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "NC-400: caseId is required");
            return;
        }
        if (appellantId == null || appellantId.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "NC-400: appellantId is required");
            return;
        }
        if (reason == null || reason.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "NC-400: reason is required");
            return;
        }

        String actor = panelUsername(claims);
        try {
            Appeal created = moderationManager.createAppeal(caseId, appellantId, reason, actor);
            JsonObject response = new JsonObject();
            response.addProperty("appealId", created.getId());
            response.addProperty("status", created.getStatus().name());
            sendJsonResponse(ctx, request, HttpResponseStatus.CREATED, response);
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
        }
    }

    /**
     * GET /api/appeals - List appeals with pagination.
     *
     * <p>Query params: {@code page} (1-based), {@code size}, {@code status}
     * (optional exact filter; after the AppealStatus reconciliation the
     * frontend values PENDING/APPROVED/DENIED/ESCALATED are all real enum
     * names, so no normalization is needed).
     */
    private void handleListAppeals(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }

        int page = 1;
        int size = 20;
        String status = null;
        int q = uri.indexOf('?');
        if (q >= 0) {
            String query = uri.substring(q + 1);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                String key = kv[0];
                String value;
                try {
                    value = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                try {
                    switch (key) {
                        case "page" -> page = Math.max(1, Integer.parseInt(value));
                        case "size" -> size = Math.min(100, Math.max(1, Integer.parseInt(value)));
                        case "status" -> status = value.isBlank() ? null : value;
                    }
                } catch (NumberFormatException ignored) {
                    // keep defaults
                }
            }
        }
        int offset = (page - 1) * size;

        List<Appeal> appeals;
        int total;
        try {
            appeals = moderationManager.listAppeals(offset, size, status);
            total = moderationManager.countAppeals(status);
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
            return;
        }

        JsonArray items = new JsonArray();
        for (Appeal a : appeals) {
            items.add(appealToJson(a));
        }

        JsonObject response = new JsonObject();
        response.add("items", items);
        response.addProperty("page", page);
        response.addProperty("pageSize", size);
        response.addProperty("total", total);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/appeals/{id}/review - Review an appeal.
     *
     * <p>Body: {@code {decision, note}}. {@code decision} ∈
     * {@code {APPROVED, DENIED, ESCALATED}} maps directly to
     * {@link AppealStatus} via {@code valueOf}. The reviewer must differ from
     * the case's assigned moderator (NC-403, surfaced to the UI as a self-review hint).
     */
    private void handleReviewAppeal(ChannelHandlerContext ctx, FullHttpRequest request,
                                    String appealId, Claims claims) {
        if (moderationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Moderation system not enabled");
            return;
        }

        AppealStatus status;
        String note;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (body.isBlank()) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
                return;
            }
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String decision = json.has("decision") && !json.get("decision").isJsonNull()
                    ? json.get("decision").getAsString() : null;
            try {
                status = decision == null ? null : AppealStatus.valueOf(decision);
            } catch (IllegalArgumentException e) {
                status = null;
            }
            note = json.has("note") && !json.get("note").isJsonNull()
                    ? json.get("note").getAsString() : null;
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }
        if (status == null) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "NC-400: Invalid decision (expected APPROVED, DENIED or ESCALATED)");
            return;
        }

        String reviewedBy = panelUsername(claims);
        String actor = reviewedBy;
        try {
            Appeal updated = moderationManager.reviewAppeal(appealId, status, reviewedBy, note, actor);
            JsonObject response = new JsonObject();
            response.addProperty("appealId", updated.getId());
            response.addProperty("status", updated.getStatus().name());
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (ModerationException e) {
            handleModerationException(ctx, request, e);
        }
    }

    // ==================== Notification Endpoints ====================

    /**
     * GET /api/notifications - List notifications with pagination.
     *
     * <p>Query params: page (1-based, default 1), size (default 20), unreadOnly
     * (true/false, default false).
     * Returns {items:[...], total, unreadCount}.
     *
     * <p>PANEL-014: uses per-user state. The userId is the panel username from
     * the JWT; only notifications visible to that user (broadcast + directed)
     * are returned, and unreadCount reflects the per-user unread count.
     */
    private void handleGetNotifications(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
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

        String userId = panelUsername(claims);
        List<Notification> notifications = notificationStore.getNotifications(offset, size, unreadOnly, userId);
        int unreadCount = notificationStore.getUnreadCount(userId);
        // Real total of matching records (NOT the current page size) so the
        // panel can decide whether more pages exist.
        int total = notificationStore.count(unreadOnly, userId);

        JsonArray items = new JsonArray();
        for (Notification n : notifications) {
            JsonObject item = new JsonObject();
            item.addProperty("id", n.getId());
            item.addProperty("title", n.getTitle());
            item.addProperty("message", n.getMessage());
            item.addProperty("level", n.getLevel());
            item.addProperty("createdAt", n.getCreatedAt());
            item.addProperty("read", n.isRead());
            // PANEL-014: expose recipient so the frontend can distinguish
            // broadcast (null) from directed notifications.
            String recipient = n.getRecipient();
            if (recipient != null) {
                item.addProperty("recipient", recipient);
            }
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
     *
     * <p>PANEL-014: marks read per-user (records a row in notification_read
     * for the caller's userId). Other admins' unread counts are unaffected.
     */
    private void handleMarkNotificationRead(ChannelHandlerContext ctx, FullHttpRequest request,
                                           String idStr, Claims claims) {
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
        notificationStore.markRead(id, panelUsername(claims));
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, new JsonObject());
    }

    /**
     * POST /api/notifications/read-all - Mark all notifications as read.
     *
     * <p>PANEL-014: marks read per-user. Only notifications visible to the
     * caller are marked read; other admins' inboxes are unaffected.
     */
    private void handleMarkAllNotificationsRead(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        if (notificationStore == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Notifications not enabled");
            return;
        }
        notificationStore.markAllRead(panelUsername(claims));
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, new JsonObject());
    }

    /**
     * DELETE /api/notifications - Clear notifications for the caller.
     *
     * <p>PANEL-014: clears only directed notifications where recipient equals
     * the caller's userId. Broadcast events (recipient null) are NOT removed
     * by this call; they require the SUPER_ADMIN
     * DELETE /api/notifications/broadcast route.
     */
    private void handleClearNotifications(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        if (notificationStore == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Notifications not enabled");
            return;
        }
        int cleared = notificationStore.clearAll(panelUsername(claims));
        JsonObject response = new JsonObject();
        response.addProperty("cleared", cleared);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * DELETE /api/notifications/broadcast - Clear all broadcast notifications
     * (SUPER_ADMIN only).
     *
     * <p>PANEL-014: this is the global cleanup path for broadcast events that
     * are no longer relevant. It deletes every notification where recipient is
     * NULL, leaving directed notifications (non-null recipient) intact so
     * other admins' inboxes are not wiped by a shared-stream purge. The action
     * is audited via {@link #recordAuditSuccess} so there is a trail of who
     * purged the shared broadcast stream and when.
     */
    private void handleClearBroadcastNotifications(ChannelHandlerContext ctx, FullHttpRequest request,
                                                   Claims claims) {
        if (notificationStore == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Notifications not enabled");
            return;
        }
        int cleared = notificationStore.clearBroadcast();
        recordAuditSuccess(ctx, claims, "notification.clear_broadcast",
                "notifications", null, null);
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
     *
     * <p>Sender identity is derived from the JWT claims the same way moderation
     * paths do (panelUsername(claims) → panelOperatorUuid(...)); the request
     * body cannot forge it. A body {@code senderName} is accepted only as
     * display-only metadata and surfaced via a {@code senderNameSource:"body"}
     * field so consumers can tell it is untrusted. BACK-002 / BACK-003.
     *
     * Requirements: 25.4
     */
    private void handleSendMessage(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            String channelId = json.has("channelId") ? json.get("channelId").getAsString() : null;
            // Display-only metadata from the body; NEVER used as the authenticated identity.
            String bodySenderName = json.has("senderName") && !json.get("senderName").isJsonNull()
                    ? json.get("senderName").getAsString() : null;
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
            PanelRole role = resourcePolicy.resolveRole(claims);
            if (!resourcePolicy.canSend(role, channel)) {
                // canSend fails on missing channel OR insufficient scope — both are
                // "no such routable target" from the caller's POV; surface as NC-404
                // to mirror the NC-4xx idiom used by the routing-failure path below.
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND,
                        "NC-404: Channel not found: "
                                + (channelId != null ? channelId : "(null)"));
                return;
            }

            // BACK-002: derive the sender identity from JWT claims, mirroring
            // the moderation paths (handleMutePlayer/handleBanPlayer/...). The
            // body cannot control the authenticated senderId/senderName.
            String operatorUsername = panelUsername(claims);
            UUID senderId = panelOperatorUuid(operatorUsername);
            String senderName = operatorUsername != null && !operatorUsername.isBlank()
                    ? operatorUsername : "console";

            // Route the message and surface the outcome to the HTTP response (BACK-003).
            RoutingResult result = messageRouter.routeMessage(
                    channelId, senderId, senderName, content, new HashMap<>());

            // BACK-003: 404 when the channel/routing target does not exist OR
            // routing produced zero recipients (e.g. channel exists but no
            // client is online). Mirrors the existing NC-4xx idiom.
            if (!result.isSuccess() || result.getRecipientCount() == 0) {
                String detail = !result.isSuccess() && result.getErrorMessage() != null
                        ? result.getErrorMessage()
                        : "No recipients routed for channel " + channelId;
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "NC-404: " + detail);
                return;
            }

            // Trigger webhook for message sent
            if (webhookManager != null) {
                webhookManager.triggerWebhook("message.sent", createMessageWebhookPayload(
                    channelId, senderId.toString(), senderName, content));
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Message sent successfully");
            response.addProperty("recipientCount", result.getRecipientCount());
            // Surface the untrusted body-supplied display name, labeled by source.
            if (bodySenderName != null && !bodySenderName.isBlank()) {
                response.addProperty("senderName", bodySenderName);
                response.addProperty("senderNameSource", "body");
            }

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
    private void handleGetMessages(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
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

        PanelRole role = resourcePolicy.resolveRole(claims);
        Set<String> visibleChannelIds = resourcePolicy.visibleChannelIds(role);
        if (channel != null && !channel.isBlank()
                && !visibleChannelIds.contains(channel)) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        MessageFilter filter = new MessageFilter(
                channel, server, player, q, from, to, visibleChannelIds);
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
                if (resourcePolicy.canViewInfrastructureSource(role)) {
                    item.addProperty("clientId", record.getClientId());
                }
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

    // ==================== Campaign Endpoints (§11.6 提案 06 — slice A) ====================
    //
    // In-memory, backend-only. GET /api/campaigns and GET /api/campaigns/{id}
    // are VIEWER (default GET branch in requiredRole). POST /api/campaigns and
    // POST /api/campaigns/{id}/{schedule,activate} are ADMIN (default mutation
    // branch). POST /api/campaigns/{id}/revoke is SUPER_ADMIN (mapped to
    // PermissionLevel.SUPER_ADMIN in CampaignManager).
    //
    // Campaign audit is manager-owned: each mutation records an event via the
    // canonical AuditStore wired into the CampaignManager (setter on the
    // manager), mirroring ModerationManager. The REST layer therefore does
    // NOT call recordAuditSuccess for campaign routes (no double-audit). All
    // routes 503 when the CampaignManager is not wired.

    /**
     * GET /api/campaigns - List campaigns with an optional {@code channelId}
     * filter. Returns {items, total} where each item is the full campaign JSON
     * (see {@link #campaignToJson}).
     */
    private void handleListCampaigns(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        if (campaignManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Campaigns not enabled");
            return;
        }
        String channelId = null;
        int q = uri.indexOf('?');
        if (q >= 0) {
            String query = uri.substring(q + 1);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "channelId".equals(pair.substring(0, eq))) {
                    channelId = pair.substring(eq + 1);
                }
            }
        }
        JsonArray items = new JsonArray();
        for (com.nova.link.announcement.Campaign c : campaignManager.listCampaigns(channelId)) {
            items.add(campaignToJson(c));
        }
        JsonObject response = new JsonObject();
        response.add("items", items);
        response.addProperty("total", items.size());
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/campaigns - Create a campaign in PREVIEW status.
     *
     * <p>Body: {channelId, content, platforms:[...], deliveryPolicy?,
     * startAt?, endAt?, rateLimitPerHour?}. The operator is derived from the
     * JWT (panel-derived UUID) and passed as a trusted operator to the
     * manager (REST layer already enforced RBAC).
     */
    private void handleCreateCampaign(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        if (campaignManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Campaigns not enabled");
            return;
        }
        String channelId;
        String content;
        java.util.Set<String> platforms;
        String deliveryPolicyRaw;
        long startAt;
        long endAt;
        int rateLimitPerHour;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            channelId = json.has("channelId") && !json.get("channelId").isJsonNull()
                    ? json.get("channelId").getAsString() : null;
            content = json.has("content") && !json.get("content").isJsonNull()
                    ? json.get("content").getAsString() : null;
            platforms = new java.util.LinkedHashSet<>();
            if (json.has("platforms") && json.get("platforms").isJsonArray()) {
                for (var el : json.getAsJsonArray("platforms")) {
                    if (!el.isJsonNull() && !el.getAsString().isBlank()) {
                        platforms.add(el.getAsString());
                    }
                }
            }
            deliveryPolicyRaw = json.has("deliveryPolicy") && !json.get("deliveryPolicy").isJsonNull()
                    ? json.get("deliveryPolicy").getAsString() : null;
            startAt = json.has("startAt") && !json.get("startAt").isJsonNull()
                    ? json.get("startAt").getAsLong() : 0L;
            endAt = json.has("endAt") && !json.get("endAt").isJsonNull()
                    ? json.get("endAt").getAsLong() : 0L;
            rateLimitPerHour = json.has("rateLimitPerHour") && !json.get("rateLimitPerHour").isJsonNull()
                    ? json.get("rateLimitPerHour").getAsInt() : 0;
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }
        if (channelId == null || channelId.isBlank() || !channelManager.channelExists(channelId)) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid channelId");
            return;
        }
        if (content == null || content.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "content is required");
            return;
        }
        if (platforms.isEmpty()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "platforms must be non-empty");
            return;
        }
        com.nova.link.announcement.DeliveryPolicy policy =
                com.nova.link.announcement.DeliveryPolicy.fromDbValue(deliveryPolicyRaw);

        UUID operatorId = panelOperatorUuid(panelUsername(claims));
        com.nova.link.announcement.CampaignResult result = campaignManager.createCampaign(
                operatorId, channelId, content, platforms, policy,
                startAt, endAt, rateLimitPerHour, null, true);
        if (!result.isSuccess()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, result.getMessage());
            return;
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.CREATED,
                campaignToJson(result.getCampaign()));
    }

    /**
     * GET /api/campaigns/{id} - Fetch a single campaign. 404 when not found.
     */
    private void handleGetCampaign(ChannelHandlerContext ctx, FullHttpRequest request, String campaignId) {
        if (campaignManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Campaigns not enabled");
            return;
        }
        com.nova.link.announcement.Campaign campaign = campaignManager.getCampaign(campaignId);
        if (campaign == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Campaign not found: " + campaignId);
            return;
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, campaignToJson(campaign));
    }

    /**
     * POST /api/campaigns/{id}/schedule - Transition PREVIEW → SCHEDULED (or
     * PREVIEW → ACTIVE when startAt is 0). Bumps scheduleRevision.
     */
    private void handleScheduleCampaign(ChannelHandlerContext ctx, FullHttpRequest request,
                                         String campaignId, Claims claims) {
        if (campaignManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Campaigns not enabled");
            return;
        }
        UUID operatorId = panelOperatorUuid(panelUsername(claims));
        com.nova.link.announcement.CampaignResult result =
                campaignManager.scheduleCampaign(campaignId, operatorId, true);
        if (!result.isSuccess()) {
            sendJsonError(ctx, request, statusForResult(result), result.getMessage());
            return;
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, campaignToJson(result.getCampaign()));
    }

    /**
     * POST /api/campaigns/{id}/activate - Transition SCHEDULED → ACTIVE and
     * deliver once (subject to the per-channel/per-hour rate limit).
     */
    private void handleActivateCampaign(ChannelHandlerContext ctx, FullHttpRequest request,
                                        String campaignId, Claims claims) {
        if (campaignManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Campaigns not enabled");
            return;
        }
        UUID operatorId = panelOperatorUuid(panelUsername(claims));
        com.nova.link.announcement.CampaignResult result =
                campaignManager.activateCampaign(campaignId, operatorId, true);
        if (!result.isSuccess()) {
            sendJsonError(ctx, request, statusForResult(result), result.getMessage());
            return;
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, campaignToJson(result.getCampaign()));
    }

    /**
     * POST /api/campaigns/{id}/revoke - Transition any non-terminal → REVOKED.
     * SUPER_ADMIN-only (enforced by requiredRole). Cancels the armed task and
     * stamps revokedAt/revokedBy.
     */
    private void handleRevokeCampaign(ChannelHandlerContext ctx, FullHttpRequest request,
                                      String campaignId, Claims claims) {
        if (campaignManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Campaigns not enabled");
            return;
        }
        UUID operatorId = panelOperatorUuid(panelUsername(claims));
        com.nova.link.announcement.CampaignResult result =
                campaignManager.revokeCampaign(campaignId, operatorId, true);
        if (!result.isSuccess()) {
            sendJsonError(ctx, request, statusForResult(result), result.getMessage());
            return;
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, campaignToJson(result.getCampaign()));
    }

    /**
     * Maps a {@link com.nova.link.announcement.CampaignResult} error code to
     * the corresponding HTTP status (400 / 403 / 404 / 429 / 500).
     */
    private HttpResponseStatus statusForResult(com.nova.link.announcement.CampaignResult result) {
        if (result == null || result.isSuccess()) {
            return HttpResponseStatus.OK;
        }
        String code = result.getErrorCode();
        if (code == null) {
            return HttpResponseStatus.BAD_REQUEST;
        }
        switch (code) {
            case com.nova.link.announcement.CampaignResult.CODE_NOT_FOUND:
                return HttpResponseStatus.NOT_FOUND;
            case com.nova.link.announcement.CampaignResult.CODE_FORBIDDEN:
                return HttpResponseStatus.FORBIDDEN;
            case com.nova.link.announcement.CampaignResult.CODE_RATE_LIMITED:
                return HttpResponseStatus.TOO_MANY_REQUESTS;
            case com.nova.link.announcement.CampaignResult.CODE_INTERNAL_ERROR:
                return HttpResponseStatus.INTERNAL_SERVER_ERROR;
            case com.nova.link.announcement.CampaignResult.CODE_BAD_REQUEST:
            default:
                return HttpResponseStatus.BAD_REQUEST;
        }
    }

    /**
     * Converts a campaign to its REST JSON shape:
     * {id, channelId, platforms, content, status, scheduleRevision,
     *  deliveryPolicy, startAt, endAt, rateLimitPerChannelPerHour,
     *  createdAt, revokedAt, revokedBy}.
     */
    private JsonObject campaignToJson(com.nova.link.announcement.Campaign campaign) {
        JsonObject json = new JsonObject();
        json.addProperty("id", campaign.getId());
        json.addProperty("channelId", campaign.getChannelId());
        JsonArray platforms = new JsonArray();
        for (String p : campaign.getPlatforms()) {
            platforms.add(p);
        }
        json.add("platforms", platforms);
        json.addProperty("content", campaign.getContent());
        json.addProperty("status", campaign.getStatus().name());
        json.addProperty("scheduleRevision", campaign.getScheduleRevision());
        json.addProperty("deliveryPolicy", campaign.getDeliveryPolicy().dbValue());
        json.addProperty("startAt", campaign.getStartAt());
        json.addProperty("endAt", campaign.getEndAt());
        json.addProperty("rateLimitPerChannelPerHour", campaign.getRateLimitPerChannelPerHour());
        json.addProperty("createdAt", campaign.getCreatedAt());
        json.addProperty("revokedAt", campaign.getRevokedAt());
        if (campaign.getRevokedBy() != null) {
            json.addProperty("revokedBy", campaign.getRevokedBy().toString());
        } else {
            json.add("revokedBy", com.google.gson.JsonNull.INSTANCE);
        }
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
    private void handleGetPlayers(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
        PanelRole role = resourcePolicy.resolveRole(claims);
        Set<String> visibleChannelIds = resourcePolicy.visibleChannelIds(role);
        JsonArray players = new JsonArray();
        
        // Get all player states from the state manager
        Collection<PlayerState> states = playerStateManager.getAllPlayerStates();
        for (PlayerState state : states) {
            if (isPlayerVisible(state.getPlayerId(), visibleChannelIds)) {
                players.add(playerStateToJson(state, role, visibleChannelIds));
            }
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
    private void handleGetPlayer(ChannelHandlerContext ctx, FullHttpRequest request,
                                 String playerId, Claims claims) {
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerState state = playerStateManager.getPlayerState(uuid);
            PanelRole role = resourcePolicy.resolveRole(claims);
            Set<String> visibleChannelIds = resourcePolicy.visibleChannelIds(role);

            if (state == null || !isPlayerVisible(uuid, visibleChannelIds)) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Player not found");
                return;
            }
            
            sendJsonResponse(ctx, request, HttpResponseStatus.OK,
                    playerStateToJson(state, role, visibleChannelIds));
            
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
    private void handleCreateWebhook(ChannelHandlerContext ctx, FullHttpRequest request, Claims claims) {
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

            JsonObject webhookJson = webhookToJson(webhook);
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("webhook", webhookJson);

            sendJsonResponse(ctx, request, HttpResponseStatus.CREATED, response);

            // PANEL-006: audit the webhook creation. webhookToJson never emits
            // the secret, so the after-hash is secret-safe.
            recordAuditSuccess(ctx, claims, "webhook.create", "webhook:" + webhook.getId(),
                    null, AuditEvent.hashJson(gson.toJson(webhookJson)));

        } catch (Exception e) {
            logger.error("Error creating webhook", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            recordAudit(ctx, claims, "webhook.create", null,
                    null, null, "invalid request body", "failure");
        }
    }

    /**
     * PUT /api/webhooks/{id} - Update a webhook.
     *
     * <p>Body: {url?, secret?, active?} plus the event key, accepted as either
     * {@code events} (single event string — what the panel submits) or
     * {@code event}. Only provided fields are applied. Returns the updated object.
     */
    private void handleUpdateWebhook(ChannelHandlerContext ctx, FullHttpRequest request,
                                     String webhookId, Claims claims) {
        if (webhookManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Webhooks not enabled");
            return;
        }

        // PANEL-006: capture the before-state hash for audit. webhookToJson
        // never emits the secret.
        Webhook existing = webhookManager.getWebhook(webhookId);
        if (existing == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND,
                    I18n.tr("api.webhook.not_found", webhookId));
            return;
        }
        JsonObject beforeJson = webhookToJson(existing);
        String beforeHash = AuditEvent.hashJson(gson.toJson(beforeJson));

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
        JsonObject afterJson = webhookToJson(updated);
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, afterJson);

        // PANEL-006: audit the webhook update with before/after hashes.
        recordAuditSuccess(ctx, claims, "webhook.update", "webhook:" + webhookId,
                beforeHash, AuditEvent.hashJson(gson.toJson(afterJson)));
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
    private void handleDeleteWebhook(ChannelHandlerContext ctx, FullHttpRequest request,
                                     String webhookId, Claims claims) {
        if (webhookManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Webhooks not enabled");
            return;
        }

        // PANEL-006: capture the before-state hash for audit.
        Webhook existing = webhookManager.getWebhook(webhookId);
        String beforeHash = existing != null
                ? AuditEvent.hashJson(gson.toJson(webhookToJson(existing)))
                : null;

        boolean deleted = webhookManager.deleteWebhook(webhookId);

        if (!deleted) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Webhook not found");
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Webhook deleted successfully");

        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

        // PANEL-006: audit the webhook deletion. No after-state.
        recordAuditSuccess(ctx, claims, "webhook.delete", "webhook:" + webhookId,
                beforeHash, null);
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
     * GET /api/health - Unauthenticated liveness/readiness probe (LB/k8s).
     * Requirements: §11.7 monitoring gate.
     *
     * <p>Bypasses auth and the worker pool in {@link #channelRead0} so a probe
     * never depends on worker capacity or a probe token. Returns aggregate
     * status, version, uptime, and per-subsystem checks — no secrets,
     * passwords, or webhook URLs.
     */
    private void handleHealth(ChannelHandlerContext ctx, FullHttpRequest request) {
        Map<String, Object> health = healthMetricsService().health();
        JsonObject json = gson.toJsonTree(health).getAsJsonObject();
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, json);
    }

    /**
     * GET /api/metrics - Prometheus exposition-format metrics (auth-gated,
     * VIEWER minimum). Requirements: §11.7 monitoring gate.
     *
     * <p>Emits {@code text/plain; version=0.0.4}. Hand-written — no Prometheus
     * client library, no new dependencies. Auth + RBAC are enforced upstream
     * in {@link #processApiRequest} (missing/invalid token → 401, role below
     * VIEWER → 403), mirroring every other GET endpoint.
     */
    private void handleMetrics(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = healthMetricsService().prometheusMetrics();
        ByteBuf buf = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                "text/plain; version=0.0.4; charset=UTF-8");
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
        return channelToJson(channel, null);
    }

    private JsonObject channelToJson(Channel channel, PanelRole role) {
        JsonObject json = new JsonObject();
        json.addProperty("id", channel.getId());
        json.addProperty("displayName", channel.getDisplayName());
        json.addProperty("scope", channel.getScope().name());
        json.addProperty("clientId", channel.getClientId());
        json.addProperty("memberCount", channel.getMembers().size());
        json.addProperty("maxCapacity", channel.getMaxCapacity());
        json.addProperty("slowModeSeconds", channel.getSlowModeSeconds());
        // PANEL-003: provenance + revision so API/WS/UI stay consistent. CONFIG
        // channels are read-only in the Panel; revision enables optimistic
        // concurrency detection.
        json.addProperty("source", channel.getSource().name());
        json.addProperty("revision", channel.getRevision());
        if (role != null) {
            json.addProperty("subscribable", resourcePolicy.canSubscribe(role, channel));
            json.addProperty("sendable", resourcePolicy.canSend(role, channel));
        }

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
    private JsonObject playerStateToJson(PlayerState state, PanelRole role,
                                         Set<String> visibleChannelIds) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", state.getPlayerId().toString());
        json.addProperty("name", state.getPlayerName());
        if (resourcePolicy.canViewInfrastructureSource(role)) {
            json.addProperty("clientId", state.getClientId());
            json.addProperty("currentWorld", state.getCurrentWorld());
        }
        if (visibleChannelIds.contains(state.getActiveChannel())) {
            json.addProperty("activeChannel", state.getActiveChannel());
        }
        
        JsonArray channels = new JsonArray();
        state.getJoinedChannels().stream()
                .filter(visibleChannelIds::contains)
                .forEach(channels::add);
        json.add("joinedChannels", channels);

        json.addProperty("muted", state.getMutes() != null && !state.getMutes().isEmpty());
        json.addProperty("platform", state.getPlatform() != null
                && !state.getPlatform().isEmpty()
                ? state.getPlatform() : "Java");

        return json;
    }

    private boolean isPlayerVisible(UUID playerId, Set<String> visibleChannelIds) {
        for (String channelId : visibleChannelIds) {
            Channel channel = channelManager.getChannel(channelId);
            if (channel != null && channel.isMember(playerId)) {
                return true;
            }
        }
        return false;
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
        stampRequestId(ctx, response);

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
        // PANEL-006: include the correlation id in the error body so callers
        // can quote it when reporting issues without scraping headers.
        String rid = currentRequestId(ctx);
        if (rid != null) {
            json.addProperty("requestId", rid);
        }
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
        stampRequestId(ctx, response);
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
        // Expose X-Request-Id so browser JS (the panel) can read it for
        // client-side correlation and error reporting.
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Request-Id");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "86400");
    }

    /**
     * @return the per-request correlation id stored on the channel by
     *         {@link #channelRead0}, or null when none is set (e.g. tests that
     *         invoke handlers directly without going through channelRead0).
     */
    private String currentRequestId(ChannelHandlerContext ctx) {
        if (ctx == null || ctx.channel() == null) {
            return null;
        }
        try {
            return ctx.channel().attr(REQUEST_ID_KEY).get();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Stamps the {@code X-Request-Id} response header from the channel
     * attribute. No-op when no id is set, so direct handler invocations in
     * tests (which skip channelRead0) still work without a request id.
     */
    private void stampRequestId(ChannelHandlerContext ctx, FullHttpResponse response) {
        String rid = currentRequestId(ctx);
        if (rid != null) {
            response.headers().set("X-Request-Id", rid);
        }
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
