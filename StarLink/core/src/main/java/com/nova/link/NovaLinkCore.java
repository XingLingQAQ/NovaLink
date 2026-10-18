package com.nova.link;
import com.nova.chat.common.NovaConstants;

import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.*;
import com.nova.link.auth.*;
import com.nova.link.auth.ClientPermissionRegistry;
import com.nova.link.api.WebhookManager;
import com.nova.link.announcement.AnnouncementManager;
import com.nova.link.announcement.CampaignManager;
import com.nova.link.ban.BanManager;
import com.nova.link.chat.MentionResolver;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelSource;
import com.nova.link.channel.MessageRouter;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.channel.InvitationManager;
import com.nova.link.console.BackendContext;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.config.*;
import com.nova.link.database.*;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.i18n.I18n;
import com.nova.link.i18n.LocaleResolver;
import com.nova.link.log.ChatLogger;
import com.nova.link.log.MessageLogService;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.NettyServer;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.AdminActionHandler;
import com.nova.link.network.ChannelActionHandler;
import com.nova.link.network.ItemDisplayHandler;
import com.nova.link.network.KeepAliveHandler;
import com.nova.link.network.PrivateMessageHandler;
import com.nova.link.network.RateLimiter;
import com.nova.link.network.InsecureModeGate;
import com.nova.link.audit.AuditStore;
import com.nova.link.moderation.ModerationManager;
import com.nova.link.notification.NotificationStore;
import com.nova.link.spy.SpyManager;
import com.nova.link.websocket.JwtSecretResolver;
import com.nova.link.websocket.WebSocketGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Embeddable NovaLink backend core.
 *
 * <p>Owns the entire lifecycle of a NovaLink backend instance: configuration
 * loading, manager wiring, TCP + WebSocket/REST listener startup, and an
 * ordered, idempotent shutdown. {@code start(Path)} returns as soon as the
 * listeners are bound — it never blocks on a console loop — so the core can be
 * embedded in a host JVM (Bukkit / Velocity / Bungee / Fabric plugin) and
 * started/stopped repeatedly.
 *
 * <p>Deliberately self-contained: this class does NOT register JVM shutdown
 * hooks (that is {@link NovaLinkMain}'s job, and a hook per reload would pin
 * the old class loader) and does NOT touch
 * {@code com.nova.link.console.BackendConsole} (the console owns the host's
 * stdin / system terminal, which a plugin must never take over).
 *
 * <p>All paths resolve against the {@code dataDir} passed to
 * {@link #start(Path)}: {@code <dataDir>/novalink.yml}, the SQLite file, the
 * JWT secret and relative TLS files. Nothing depends on the JVM's current
 * working directory, which in a plugin is the host server's root.
 */
public final class NovaLinkCore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(NovaLinkCore.class);

    /** Upper bound for the whole {@link #stop()} sequence. */
    private static final long STOP_TIMEOUT_MILLIS = 15_000L;

    /**
     * Runtime holder for all managers/handlers. Published only after every
     * component has started; cleared by {@link #stop()}.
     */
    private volatile BackendContext context;

    /**
     * Components started so far, in start order. Pushed as each one comes up
     * and drained in reverse on both rollback and normal shutdown — so a
     * startup that fails midway leaves no threads, watchers or pools behind.
     * Mutated only while holding {@link #lifecycleLock}; the concurrent deque
     * is a safety net for the rollback path running on the starting thread.
     */
    private final Deque<AutoCloseable> started = new ConcurrentLinkedDeque<>();

    /**
     * Listener teardown, kept out of {@link #started} so the ordered shutdown
     * can close it first and never close it twice. Null until the listeners
     * were started (or attempted).
     */
    private volatile AutoCloseable listenerCloser;

    /** Database teardown, closed last (after the player-state flush). */
    private volatile AutoCloseable databaseCloser;

    /** Serializes start/stop so they never interleave. */
    private final Object lifecycleLock = new Object();

    /**
     * Owns KeepAlive emission and inbound-echo recognition for the current
     * instance. Created per start so a restarted core never inherits stale
     * flood-guard state; looked up by the static handler wiring below.
     */
    private volatile KeepAliveHandler keepAliveHandler;

    private volatile boolean running;
    private volatile long startedAtMillis;
    private Path dataDir;

    /** Convenience for embedders: start from a directory containing novalink.yml. */
    public void start(Path dataDir) throws Exception {
        start(dataDir, null);
    }

    /**
     * Starts the backend against {@code <dataDir>/novalink.yml}.
     *
     * <p>The configuration is always read from disk (never supplied in memory):
     * the panel save / publish / rollback flows and the file watcher all write
     * back to this file, so an in-memory config object would silently disable
     * them.
     *
     * @param dataDir       directory holding {@code novalink.yml} and the
     *                      relative data files
     * @param bindOverrides optional bind-address / TCP port / WS port overrides
     *                      (used by tests); {@code null} keeps the config values
     * @throws Exception when startup fails. Every component that had already
     *                   started is shut down in reverse order before the
     *                   exception propagates, and {@link #stop()} remains safe
     *                   to call afterwards.
     */
    public void start(Path dataDir, BindOverrides bindOverrides) throws Exception {
        synchronized (lifecycleLock) {
            if (running) {
                throw new IllegalStateException("NovaLink core is already running");
            }
            if (dataDir == null) {
                throw new IllegalArgumentException("dataDir must not be null");
            }
            this.dataDir = dataDir.toAbsolutePath();
            started.clear();

            try {
                doStart(this.dataDir, bindOverrides);
                running = true;
                startedAtMillis = System.currentTimeMillis();
                BackendContext ctx = context;
                if (ctx != null && ctx.getWebSocketGateway() != null) {
                    // Uptime is measured from this start, not from JVM uptime:
                    // an embedded host has been running long before we start.
                    ctx.getWebSocketGateway().getRestApiHandler()
                            .setBackendStartedAtMillis(startedAtMillis);
                }
            } catch (Exception e) {
                rollback();
                throw e;
            }
        }
    }

    /** @return {@code true} between a successful {@link #start} and {@link #stop}. */
    public boolean isRunning() {
        return running;
    }

    /** @return the runtime context, or {@code null} when stopped / never started. */
    public BackendContext getContext() {
        return context;
    }

    /** @return epoch millis of the successful start, or 0 when not running. */
    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    /** @return the directory passed to {@link #start}, or {@code null}. */
    public Path getDataDir() {
        return dataDir;
    }

    /**
     * Stops the backend. Idempotent and safe to call after a failed start or
     * from multiple threads: the first caller performs the shutdown, the rest
     * return immediately.
     *
     * <p>Order is fixed: close the ingress (TCP + WS listeners, in parallel) →
     * stop the worker pools and await them → stop schedulers/managers → close
     * the database last. The whole sequence is bounded by
     * {@value #STOP_TIMEOUT_MILLIS} ms.
     */
    public void stop() {
        synchronized (lifecycleLock) {
            if (!running && started.isEmpty() && context == null) {
                return;
            }
            long begin = System.nanoTime();
            running = false;

            BackendContext ctx = context;
            if (ctx != null) {
                shutdownComponents(ctx);
            }
            rollbackRemaining();

            context = null;
            listenerCloser = null;
            databaseCloser = null;
            keepAliveHandler = null;
            startedAtMillis = 0L;
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;
            logger.info("NovaLink core stopped in {} ms", elapsedMs);
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ==================== startup ====================

    private void doStart(Path dataDir, BindOverrides bindOverrides) throws Exception {
        logger.info("Starting NovaLink Backend Server...");
        logger.info("NovaLink v1.0.0-SNAPSHOT (protocol v{})", NovaProtocol.PROTOCOL_VERSION);

        Files.createDirectories(dataDir);
        Path configPath = resolveConfigPath(dataDir);
        ConfigManager configManager = new ConfigManager(configPath);

        // External lang override dir: <dataDir>/lang/<locale>.properties.
        // I18n merges external on top of classpath bundles (external wins).
        I18n.setExternalLangDir(dataDir);
        // Production code never invalidated the bundle cache, so an external
        // language dir changed between two embedded start() calls kept serving
        // the previously-loaded translations.
        I18n.invalidate();

        NovaLinkConfig config;
        try {
            config = configManager.load();
        } catch (ConfigException e) {
            logger.error("Failed to load configuration from {}: {}", configPath, e.getMessage(), e);
            throw e;
        }

        if ("change-me-in-production".equals(config.getServer().getSecretKey())) {
            logger.warn("Server secret-key is still the default value. Please change it in {}", configPath);
        }

        // Apply the configured backend console locale so all console / backend
        // user-facing text resolves in the operator's language (zh_CN default).
        Locale backendLocale = LocaleResolver.parseOrDefault(config.getServer().getLocale(), LocaleResolver.ROOT_LOCALE);
        I18n.setDefaultLocale(backendLocale);
        logger.info("Backend console locale: {}", backendLocale);

        // Initialize authentication
        IpBanManager ipBanManager = new IpBanManager(
                IpBanManager.DEFAULT_MAX_FAILURES,
                config.getSecurity().getIpBanDuration() * 1000L
        );
        AuthManager authManager = new AuthManager(ipBanManager);
        registerClients(authManager, config);

        // Client-node GLOBAL channel permission registry (defense-in-depth for staff/VIP fan-out).
        // allowWhenUnregistered=true keeps legacy deployments working until clients authenticate
        // and receive explicit grants (wildcard or configured list).
        ClientPermissionRegistry clientPermissionRegistry = new ClientPermissionRegistry();
        clientPermissionRegistry.setAllowWhenUnregistered(true);
        Map<String, List<String>> clientPermissionBootstrap = buildClientPermissionBootstrap(config);

        // Initialize permission system (super admins are UUID-based for in-game admin auth)
        PermissionManager permissionManager = new PermissionManager();
        if (config.getSuperAdmins() != null) {
            for (SuperAdminCredentials admin : config.getSuperAdmins()) {
                try {
                    permissionManager.registerSuperAdmin(admin);
                } catch (Exception e) {
                    logger.warn("Failed to register super admin: {}", admin, e);
                }

                // Also expose super-admins as web-panel login accounts.
                // The web-panel login username is the optional human-readable username,
                // falling back to the UUID string when no username is configured (backward compatible).
                // This enables SUPER_ADMIN role in the web panel.
                try {
                    if (admin != null && admin.getUuid() != null && admin.getPasswordHash() != null) {
                        authManager.registerSuperAdmin(admin.resolveWebLoginUsername(), admin.getPasswordHash());
                    }
                } catch (Exception e) {
                    logger.warn("Failed to register super admin for web-panel auth: {}", admin, e);
                }
            }
        }

        // Register panel-users (web-panel ADMIN/VIEWER accounts). These live in
        // a credential pool separate from game-server clients: game-server
        // credentials can never log into the web panel.
        if (config.getPanelUsers() != null) {
            for (com.nova.link.config.PanelUserConfig panelUser : config.getPanelUsers()) {
                try {
                    PanelRole role = PanelRole.fromString(panelUser.getRole());
                    if (role == null || role == PanelRole.SUPER_ADMIN) {
                        logger.warn("Skipping panel-user '{}' with invalid role '{}'",
                                panelUser.getUsername(), panelUser.getRole());
                        continue;
                    }
                    authManager.registerPanelUser(new PanelUserCredentials(
                            panelUser.getUsername(), panelUser.getPasswordHash(), role));
                } catch (Exception e) {
                    logger.warn("Failed to register panel user: {}", panelUser, e);
                }
            }
        }

        // Initialize database. The SQLite file (and any relative TLS material)
        // is resolved against dataDir, not the JVM working directory.
        DatabaseProvider databaseProvider = createDatabaseProvider(config, dataDir);
        try {
            databaseProvider.initialize();
        } catch (DatabaseException e) {
            logger.error("Failed to initialize database provider {}: {}", databaseProvider.getProviderType(), e.getMessage(), e);
            throw e;
        }
        // Everything from here on is unwindable: components are pushed onto
        // `started` as soon as they own a thread/pool/watcher. The database is
        // tracked separately so it is always closed LAST.
        databaseCloser = closeable("database", databaseProvider::shutdown);

        // Core managers
        ChannelManager channelManager = new ChannelManager();
        PlayerStateManager playerStateManager = new PlayerStateManager(databaseProvider);
        WebhookManager webhookManager = new WebhookManager();
        // Webhooks persist in the schema v5 webhooks table: write-through on
        // create/update/delete, restored here so they survive restarts.
        webhookManager.setDatabaseProvider(databaseProvider);
        webhookManager.loadPersistedWebhooks();
        started.push(closeable("webhooks", webhookManager::shutdown));
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);
        InvitationManager invitationManager = new InvitationManager(databaseProvider, channelManager);
        MuteManager muteManager = new MuteManager(databaseProvider, permissionManager, channelManager);
        muteManager.initialize();
        started.push(closeable("mutes", muteManager::shutdown));
        BanManager banManager = new BanManager(databaseProvider, permissionManager, channelManager);
        banManager.initialize();
        started.push(closeable("bans", banManager::shutdown));
        // Warm the moderation caches from the database so persisted mutes/bans
        // survive a backend restart (isMuted/isBanned only consult the cache).
        muteManager.loadAllMutes();
        banManager.loadAllBans();
        SensitiveWordFilter sensitiveWordFilter = new SensitiveWordFilter();
        ChatLogger chatLogger = new ChatLogger();

        // Announcement manager (requirement 14.x): immediate / join-triggered /
        // cron-scheduled announcements. The sender callback publishes via the
        // message router's trusted path so announcements reach channel members
        // across all connected game servers. notificationStore is wired later
        // (after it is created); the scheduler is started here.
        AnnouncementManager announcementManager = new AnnouncementManager(permissionManager, channelManager);
        announcementManager.setDatabaseProvider(databaseProvider);
        announcementManager.initialize();
        started.push(closeable("announcements", announcementManager::shutdown));

        loadConfiguredChannels(channelManager, config);
        loadPersistedChannels(channelManager, databaseProvider);
        bootstrapPrivateChannelAdmins(permissionManager, channelManager);

        // Network + routing
        int workerThreads = config.getServer().getWorkerThreads();
        ServerNetworkHandler networkHandler = new ServerNetworkHandler(workerThreads);
        started.push(closeable("network-handler", networkHandler::shutdown));
        // KeepAlive owner shared by the write-idle send path (NettyServer) and
        // the inbound handler below, so both consult the same loop guard and
        // per-connection flood budget.
        keepAliveHandler = new KeepAliveHandler();
        MessageRouter messageRouter = new MessageRouter(channelManager, networkHandler);
        messageRouter.setMuteManager(muteManager);
        messageRouter.setBanManager(banManager);
        messageRouter.setSensitiveWordFilter(sensitiveWordFilter);
        messageRouter.setChatLogger(chatLogger);
        messageRouter.setPermissionChecker(clientPermissionRegistry.asChecker());
        // Slow-mode admin exemption + bounded timestamp map cleanup.
        messageRouter.setPermissionManager(permissionManager);
        messageRouter.getPipeline().getSlowModeTracker().startCleanupTask();
        started.push(closeable("slow-mode-tracker",
                messageRouter.getPipeline().getSlowModeTracker()::shutdown));

        // Per-connection token bucket shared by ChatMessage and ItemDisplay
        // ingress (server.rate-limit; messages-per-second=0 disables it).
        RateLimiter rateLimiter = new RateLimiter(
                config.getServer().getRateLimitMessagesPerSecond(),
                config.getServer().getRateLimitBurst());

        // Message persistence: async write-behind + hourly retention cleanup.
        MessageLogService messageLogService = new MessageLogService(databaseProvider,
                config.getFeatures().getMessageLogRetentionDays());
        messageLogService.initialize();
        started.push(closeable("message-log", messageLogService::shutdown));
        messageRouter.setMessageLogService(messageLogService);

        // Apply FeatureConfig switches and the custom filter lists at startup
        // (the reload listener below only covers subsequent config changes).
        // privateMessagesEnabled is a live flag consumed by PrivateMessageHandler.
        java.util.concurrent.atomic.AtomicBoolean privateMessagesEnabled =
                new java.util.concurrent.atomic.AtomicBoolean(
                        config.getFeatures().isPrivateMessagesEnabled());
        sensitiveWordFilter.setEnabled(config.getFeatures().isFilterEnabled());
        messageRouter.getPipeline().setCrossServerChatEnabled(
                config.getFeatures().isCrossServerChatEnabled());
        messageRouter.getPipeline().setMessageLogEnabled(config.getFeatures().isMessageLogEnabled());
        // §11.6 Proposal 05 sub-slice: wire backend @mention emit. MentionResolver
        // reuses NovaChat/common's MentionNotifier (no independent parser). Nullable
        // setter + mentionsEnabled (default true) means Stage 7b in MessagePipeline
        // fires for cross-server recipients. FeatureConfig has no mentionsEnabled
        // toggle yet, so this is hardcoded on; a future FeatureConfig field can drive
        // a reload-listener flip.
        messageRouter.getPipeline().setMentionResolver(
                new MentionResolver(
                        new MentionNotifier(),
                        playerStateManager,
                        networkHandler,
                        // §11.6 item-18 Part C: fail-open ignore + mention-pref
                        // lookups. DatabaseProvider methods throw checked
                        // DatabaseException, but the FI is non-throwing, so the
                        // lambdas swallow and degrade: ignore-failure → false
                        // (don't suppress the mention), pref-failure → true
                        // (mention still sent). databaseProvider is effectively
                        // final (assigned once at line 222).
                        (src, tgt) -> { try { return databaseProvider.isIgnored(src, tgt); } catch (Exception e) { return false; } },
                        id -> { try { return databaseProvider.getNotificationPreference(id).isMentionsEnabled(); } catch (Exception e) { return true; } }
                ));
        applyFilterConfig(sensitiveWordFilter, config.getFilter());

        // Clear grants when a game server disconnects so reconnect gets a fresh bootstrap.
        // The full listener (permission cleanup + disconnect notification) is wired after
        // the NotificationStore is created further below.

        // Enable config hot-reload broadcasting via the network layer.
        // The watcher polls once a second and calls back into every manager:
        // if it outlives this core it keeps reloading a dead object graph in
        // the host JVM, so it is the first thing rolled back on failure.
        configManager.setNetworkHandler(networkHandler);
        configManager.startWatching();
        started.push(closeable("config-watcher", configManager::stopWatching));
        configManager.addReloadListener(newConfig -> {
            applyConfiguredChannels(channelManager, newConfig);
            bootstrapPrivateChannelAdmins(permissionManager, channelManager);
            // Apply FeatureConfig switches (§3.7) so config changes hot-apply to runtime.
            sensitiveWordFilter.setEnabled(newConfig.getFeatures().isFilterEnabled());
            messageRouter.getPipeline().setCrossServerChatEnabled(
                    newConfig.getFeatures().isCrossServerChatEnabled());
            messageRouter.getPipeline().setMessageLogEnabled(
                    newConfig.getFeatures().isMessageLogEnabled());
            messageLogService.setRetentionDays(
                    newConfig.getFeatures().getMessageLogRetentionDays());
            privateMessagesEnabled.set(newConfig.getFeatures().isPrivateMessagesEnabled());
            // Re-apply the custom sensitive-word lists from the filter section.
            applyFilterConfig(sensitiveWordFilter, newConfig.getFilter());
        });

        SpyManager spyManager = new SpyManager(permissionManager, channelManager, networkHandler);
        messageRouter.setSpyManager(spyManager);

        AdminActionHandler adminActionHandler = new AdminActionHandler(permissionManager);
        adminActionHandler.setSpyManager(spyManager);
        adminActionHandler.setChannelManager(channelManager);
        adminActionHandler.setNetworkHandler(networkHandler);
        adminActionHandler.setMessageRouter(messageRouter);
        adminActionHandler.setConfigManager(configManager);
        adminActionHandler.setIpBanManager(ipBanManager);

        ChannelActionHandler channelActionHandler = new ChannelActionHandler(
                channelManager,
                playerStateManager,
                databaseProvider,
                privateChannelManager,
                invitationManager,
                permissionManager,
                muteManager,
                banManager
        );

        // Bind endpoints. Defaults come from novalink.yml; an embedder/test may
        // override them (the embedded case usually wants 127.0.0.1:0).
        String bindAddress = bindOverrides != null && bindOverrides.bindAddress() != null
                ? bindOverrides.bindAddress()
                : config.getServer().getBindAddress();
        int tcpPort = bindOverrides != null && bindOverrides.tcpPort() >= 0
                ? bindOverrides.tcpPort()
                : config.getServer().getPort();
        int wsPort = bindOverrides != null && bindOverrides.wsPort() >= 0
                ? bindOverrides.wsPort()
                : config.getServer().getWebsocketPort();

        // Relative TLS file paths are relative to the data directory, not the
        // JVM working directory (in a plugin that is the host server root).
        TlsConfig tlsConfig = resolveTlsPaths(config.getServer().getTls(), dataDir);

        // AUTH-002: fail-closed — refuse to start the TCP listener in plaintext
        // unless the operator has explicitly set insecure-allow-plaintext: true.
        // The challenge-response handshake protects the stored password hash
        // either way, but only TLS hides the handshake from a network observer.
        // Checked before any listener object is built so a fresh install fails
        // as early as possible.
        InsecureModeGate.requireTlsOrExplicitInsecure(config.getServer(),
                "TCP listener (port " + tcpPort + ")");

        // Servers (idle timeout: server.idle-timeout-seconds, 0 = disabled).
        // AUTH-002: pass the configured TLS material so the listener wraps the
        // challenge-response handshake in an encrypted channel when configured.
        NettyServer tcpServer = new NettyServer(
                bindAddress,
                tcpPort,
                workerThreads,
                config.getServer().getIdleTimeoutSeconds(),
                networkHandler,
                tlsConfig,
                keepAliveHandler
        );

        // Build a ConsoleCommandHandler for the REST /api/console endpoint.
        // BackendContext is normally published after the servers start, but the
        // REST handler needs ConsoleCommandHandler at construction time. We
        // build a context here with null tcpServer/webSocketGateway — those two
        // fields are never accessed by ConsoleCommandHandler.dispatch (only by
        // the shutdown path, which uses the full context published later).
        BackendContext restConsoleContext = new BackendContext(
                configManager, authManager, permissionManager, clientPermissionRegistry,
                databaseProvider, channelManager, playerStateManager, webhookManager,
                privateChannelManager, invitationManager, muteManager, banManager, null,
                announcementManager,
                sensitiveWordFilter,
                networkHandler, messageRouter, spyManager, null, null);
        ConsoleCommandHandler consoleCommandHandler = new ConsoleCommandHandler(restConsoleContext);

        // Notification store — created before the gateway so it can be passed
        // into RestApiHandler (via the gateway ctor). The gateway reference is
        // back-linked after construction to break the cycle.
        NotificationStore notificationStore = new NotificationStore(databaseProvider);

        // Wire notification triggers into the moderation managers (ban/mute)
        // and the channel action handler (kick) + admin action handler (announce).
        banManager.setNotificationStore(notificationStore);
        muteManager.setNotificationStore(notificationStore);
        channelActionHandler.setNotificationStore(notificationStore);
        adminActionHandler.setNotificationStore(notificationStore);

        // Wire the announcement manager: trusted-route sender callback (so
        // announcements fan out to channel members across all connected game
        // servers) + notification store (panel feed). The sentinel is the
        // operator for console-originated announcements; in-game operators go
        // through AdminActionHandler which calls the manager directly.
        announcementManager.setNotificationStore(notificationStore);
        announcementManager.setAnnouncementSender((channelId, content) -> {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("_announcement", "true");
            messageRouter.routeMessage(channelId, ConsoleSentinel.CONSOLE_SENTINEL,
                    ConsoleSentinel.CONSOLE_NAME, content, placeholders);
        });
        // Restore persisted JOIN/CRON announcements (join index + cron
        // scheduling) now that the sender callback is wired.
        announcementManager.loadPersistedAnnouncements();

        // Disconnect listener: clear permission grants AND surface a panel
        // notification. Wired here (after notificationStore is created) so the
        // notification side-channel is available. The permission cleanup is the
        // original behavior; the notification is the new trigger.
        networkHandler.setDisconnectListener((connection, activeGenerationEnded) -> {
            // Drop the connection's token bucket so the limiter map stays
            // bounded by the number of live connections.
            if (connection != null) {
                rateLimiter.remove(connection.getConnectionId());
                // Same for the KeepAlive flood-guard warn throttle.
                if (keepAliveHandler != null) {
                    keepAliveHandler.forget(connection.getConnectionId());
                }
            }
            String clientId = connection != null ? connection.getClientId() : null;
            // An old generation can report channelInactive after a newer
            // connection has taken over the same clientId. Only the current
            // generation may clear client-scoped permissions or announce the
            // client offline.
            if (activeGenerationEnded && clientId != null && !clientId.isBlank()) {
                clientPermissionRegistry.clearClient(clientId);
                logger.debug("Cleared permission grants for disconnected client '{}'", clientId);
                if (notificationStore != null) {
                    try {
                        notificationStore.createNotification(
                                "Client Disconnected",
                                clientId + " has disconnected",
                                "warning");
                    } catch (Exception ignored) {
                        // non-fatal
                    }
                }
            }
        });

        // Resolve the effective JWT secret: the default placeholder secret is
        // replaced by a generated 256-bit secret persisted to data/.jwt-secret.
        String jwtSecret = JwtSecretResolver.resolve(
                config.getServer().getSecretKey(),
                dataDir.resolve("data").resolve(".jwt-secret"));

        // CORS origin whitelist for the panel HTTP endpoints. Default ["*"]
        // keeps backward compatibility but should be tightened in production.
        List<String> corsAllowedOrigins = config.getServer().getCorsAllowedOrigins();
        if (corsAllowedOrigins.contains("*")) {
            logger.warn("CORS allows all origins (server.cors-allowed-origins: [\"*\"]). "
                    + "Configure the exact panel origin(s) to tighten security.");
        }

        // PANEL-011 / AUTH-002: fail-closed for the WS/REST port too — refuse
        // to start the WebSocket/REST listener in plaintext unless the operator
        // has explicitly set insecure-allow-plaintext: true. The WS port
        // carries JWT login + panel traffic; only TLS hides that from a passive
        // observer. Mirrors the TCP gate above.
        InsecureModeGate.requireTlsOrExplicitInsecure(config.getServer(),
                "WebSocket/REST listener (port " + config.getServer().getWebsocketPort() + ")");

        // PANEL-006: append-only audit store backed by the same DatabaseProvider
        // as the rest of the persistence layer. Constructed once and shared with
        // the REST handler so every P1 admin mutation is recorded.
        AuditStore auditStore = new AuditStore(databaseProvider);

        // PANEL-007: moderation case/appeal manager. Shares the same
        // DatabaseProvider + AuditStore so every moderation mutation (report,
        // assign, resolve, evidence, appeal, review) records an audit entry
        // internally — the REST handler must NOT duplicate those audit calls.
        ModerationManager moderationManager =
                new ModerationManager(databaseProvider, auditStore);
        // PANEL-014: wire the directed-notification producer so a newly filed
        // case or appeal fans out one directed notification per ADMIN-or-above
        // panel user. Recipient discovery uses the live AuthManager role model
        // (super-admins + panel-users config), never a hardcoded list. The
        // fan-out is best-effort inside ModerationManager; per-request cost
        // runs on whatever thread executes createReport/createAppeal (the
        // REST worker pool in production).
        moderationManager.setNotificationStore(notificationStore);
        moderationManager.setPanelAdminUsernames(
                () -> authManager.getPanelUsernamesWithRoleAtLeast(
                        com.nova.link.auth.PanelRole.ADMIN));

        // §11.6 提案 06 (项目 19) — campaign 编排管理器。内存态（slice A，零迁移）；
        // 复用同一个 AuditStore 记录 campaign 审计；投递回调走受信 MessageRouter 路径，
        // 与 AnnouncementManager.setAnnouncementSender 同型。生产持久化为后续 slice。
        CampaignManager campaignManager = new CampaignManager(permissionManager, channelManager);
        campaignManager.setAuditStore(auditStore);
        campaignManager.setDatabaseProvider(databaseProvider);
        campaignManager.setAnnouncementSender((channelId, content) -> {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("_announcement", "true");
            messageRouter.routeMessage(channelId, ConsoleSentinel.CONSOLE_SENTINEL,
                    ConsoleSentinel.CONSOLE_NAME, content, placeholders);
        });
        campaignManager.initialize();
        started.push(closeable("campaigns", campaignManager::shutdown));
        // Rehydrate non-terminal campaigns (PREVIEW/SCHEDULED/ACTIVE) from the
        // database now that the sender callback + scheduler are wired — mirrors
        // announcementManager.loadPersistedAnnouncements() above. Terminal
        // campaigns (EXPIRED/REVOKED) stay in the table as an audit trail but
        // are skipped here. SCHEDULED campaigns with a future startAt are
        // re-armed via CampaignManager.armActivation on the internal scheduler.
        campaignManager.loadPersistedCampaigns();

        WebSocketGateway webSocketGateway = new WebSocketGateway(
                bindAddress,
                wsPort,
                jwtSecret,
                authManager,
                channelManager,
                playerStateManager,
                messageRouter,
                webhookManager,
                networkHandler,
                muteManager,
                banManager,
                invitationManager,
                configManager,
                consoleCommandHandler,
                notificationStore,
                corsAllowedOrigins,
                tlsConfig,
                auditStore
        );
        // Back-link the gateway so notifications broadcast live.
        notificationStore.setWebSocketGateway(webSocketGateway);
        messageRouter.setWebSocketBroadcaster(webSocketGateway.createBroadcaster());
        // Inject the setter-based REST dependencies (kept out of the long
        // RestApiHandler constructor): announcements + message history.
        webSocketGateway.getRestApiHandler().setAnnouncementManager(announcementManager);
        webSocketGateway.getRestApiHandler().setMessageLogService(messageLogService);
        // PANEL-007: hand the moderation manager to the REST handler so the
        // case/appeal endpoints can dispatch to it.
        webSocketGateway.getRestApiHandler().setModerationManager(moderationManager);
        // §11.6 提案 06 (项目 19): hand the campaign manager to the REST handler
        // so the campaign routes dispatch to it (slice A: in-memory, zero migration).
        webSocketGateway.getRestApiHandler().setCampaignManager(campaignManager);
        // §11.6 项目 17: wire ws gateway so metrics expose nova_link_ws_sessions_active + checks.ws.
        webSocketGateway.getRestApiHandler().setWebSocketGateway(webSocketGateway);
        // Wire the live private-messages-enabled flag so panel updates reach
        // PrivateMessageHandler without a full configuration reload.
        webSocketGateway.getRestApiHandler().setPrivateMessagesEnabledFlag(privateMessagesEnabled);
        // Offload REST + auth business logic (JDBC queries, BCrypt hashing)
        // from the Netty IO threads to a dedicated fixed pool
        // (server.rest-worker-threads); a saturated pool answers 503.
        ExecutorService restWorkerPool =
                com.nova.link.api.RestApiHandler.newWorkerPool(config.getServer().getRestWorkerThreads());
        started.push(closeable("rest-worker-pool", restWorkerPool::shutdownNow));
        webSocketGateway.getRestApiHandler().setWorkerExecutor(restWorkerPool);
        webSocketGateway.getHttpAuthHandler().setWorkerExecutor(restWorkerPool);
        // Push player_update to panel sessions when a player joins/leaves a
        // channel, so presence changes show up without polling get_players.
        channelActionHandler.setPlayerUpdateBroadcaster(webSocketGateway::broadcastPlayerUpdate);
        // Wire webhook delivery for player.join / player.leave events (P0-4).
        channelActionHandler.setWebhookManager(webhookManager);

        // AUTH-002: per-server pending-challenge cache for the 3-packet
        // challenge-response handshake. Lives for the lifetime of the server
        // process; entries self-expire (30s TTL) and the map is bounded.
        NonceCache nonceCache = new NonceCache();

        registerPacketHandlers(
                networkHandler,
                configManager,
                authManager,
                channelManager,
                messageRouter,
                adminActionHandler,
                channelActionHandler,
                clientPermissionRegistry,
                clientPermissionBootstrap,
                playerStateManager,
                notificationStore,
                muteManager,
                banManager,
                rateLimiter,
                privateMessagesEnabled::get,
                chatLogger,
                nonceCache,
                databaseProvider,
                keepAliveHandler
        );

        CompletableFuture<Void> startFuture = CompletableFuture.allOf(
                tcpServer.start(),
                webSocketGateway.start()
        );

        try {
            startFuture.get(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // A listener failed to bind (or never completed): tear down the
            // event loops that DID come up before propagating. On a bind
            // failure the server's own failure path already stopped the
            // groups it created, which makes its shutdown() a no-op.
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
                    ? e.getCause() : e;
            logger.error("Failed to start NovaLink services: {}", cause.getMessage(), cause);
            pushListenerTeardown(tcpServer, webSocketGateway);
            throw e;
        }
        pushListenerTeardown(tcpServer, webSocketGateway);

        logger.info("NovaLink Backend Server started successfully on tcp {}:{} and ws {}:{}",
                bindAddress, tcpServer.getBoundPort(),
                bindAddress, webSocketGateway.getBoundPort());

        // Publish the fully-initialized context. CampaignManager is part of it
        // so shutdown can stop its scheduler in the same ordered sequence as
        // every other manager (it used to be a separate field stopped after
        // the shared sequence — i.e. after the database was already closed).
        BackendContext published = new BackendContext(
                configManager, authManager, permissionManager, clientPermissionRegistry,
                databaseProvider, channelManager, playerStateManager, webhookManager,
                privateChannelManager, invitationManager, muteManager, banManager,
                notificationStore, announcementManager, sensitiveWordFilter,
                networkHandler, messageRouter, spyManager, tcpServer, webSocketGateway);
        published.setMessageLogService(messageLogService);
        published.setRestWorkerPool(restWorkerPool);
        published.setCampaignManager(campaignManager);
        this.context = published;
    }

    /**
     * Registers TCP + WS teardown as one parallel step. Both listeners stop
     * concurrently: the old serial {@code join()}s cost ~2s of quiet period
     * each (Netty's {@code shutdownGracefully()} defaults), ~4s minimum.
     */
    private void pushListenerTeardown(NettyServer tcpServer, WebSocketGateway webSocketGateway) {
        listenerCloser = closeable("listeners", () -> {
            CompletableFuture<Void> tcp = tcpServer.shutdown();
            CompletableFuture<Void> ws = webSocketGateway.shutdown();
            try {
                CompletableFuture.allOf(tcp, ws)
                        .get(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.TimeoutException e) {
                logger.warn("Listener shutdown exceeded {} ms", STOP_TIMEOUT_MILLIS);
            } catch (java.util.concurrent.ExecutionException e) {
                logger.debug("Listener shutdown failed: {}", e.getCause());
            }
        });
    }

    // ==================== shutdown ====================

    /**
     * Ordered shutdown of the published context.
     *
     * <p>Order matters and is enforced here explicitly rather than relying on
     * the (reverse) registry order: close the ingress first (TCP + WS in
     * parallel), then let in-flight REST work drain, then stop the services
     * that write to the database, and only then close the database.
     */
    private void shutdownComponents(BackendContext ctx) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_TIMEOUT_MILLIS);

        // 1. Ingress: no new connections, no new chat, no new REST requests.
        AutoCloseable listeners = listenerCloser;
        listenerCloser = null;
        if (listeners != null) {
            closeQuietly(listeners);
        }

        // 2. REST workers: await in-flight requests — they run JDBC queries and
        //    must finish before the pool is torn down / the DB closes.
        ExecutorService restPool = ctx.getRestWorkerPool();
        if (restPool != null) {
            try {
                restPool.shutdown();
                if (!restPool.awaitTermination(remainingMillis(deadline), TimeUnit.MILLISECONDS)) {
                    restPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                restPool.shutdownNow();
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.debug("Error shutting down REST worker pool: {}", e.toString());
            }
        }

        // 3. Managers with their own schedulers/pools, in reverse start order.
        //    Message-log flushes its queued writes here - while the DB is open.
        drainStarted(deadline);

        // 4. Flush player state while the database is still open.
        try {
            if (ctx.getPlayerStateManager() != null) {
                ctx.getPlayerStateManager().saveAllDirty();
                ctx.getPlayerStateManager().clearCache(false);
            }
        } catch (Exception e) {
            logger.debug("Error flushing player state: {}", e.getMessage());
        }

        // 5. Database last.
        AutoCloseable database = databaseCloser;
        databaseCloser = null;
        if (database != null) {
            closeQuietly(database);
        }
    }

    /** Drains {@link #started} in reverse start order, bounded by the deadline. */
    private void drainStarted(long deadline) {
        AutoCloseable next;
        while ((next = started.poll()) != null) {
            closeQuietly(next);
            if (System.nanoTime() > deadline) {
                logger.warn("NovaLink shutdown exceeded {} ms; remaining components skipped",
                        STOP_TIMEOUT_MILLIS);
                started.clear();
                break;
            }
        }
    }

    /**
     * Closes anything still registered after the ordered shutdown. In the
     * normal path this only picks up components the ordered sequence did not
     * touch (there should be none); it is the whole rollback path when startup
     * failed before a context was published.
     */
    private void rollbackRemaining() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_TIMEOUT_MILLIS);
        // Same ingress-first ordering as the normal path: stop accepting work
        // before the components that do the work are torn down.
        AutoCloseable listeners = listenerCloser;
        listenerCloser = null;
        if (listeners != null) {
            closeQuietly(listeners);
        }
        drainStarted(deadline);
        AutoCloseable database = databaseCloser;
        databaseCloser = null;
        if (database != null) {
            closeQuietly(database);
        }
    }

    /** Startup-failure rollback: close everything started so far, in reverse. */
    private void rollback() {
        int count = started.size();
        rollbackRemaining();
        context = null;
        running = false;
        startedAtMillis = 0L;
        if (count > 0) {
            logger.warn("NovaLink startup failed; rolled back {} component(s)", count);
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return Math.max(0L, remaining);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            logger.debug("Error during component shutdown: {}", e.toString());
        }
    }

    /** Wraps a shutdown action with a component label for diagnostics. */
    private static AutoCloseable closeable(String name, Runnable action) {
        return () -> {
            try {
                action.run();
            } catch (Exception e) {
                logger.warn("Failed to stop {}: {}", name, e.toString());
            }
        };
    }

    /** Optional bind-endpoint overrides for embedded/test use. */
    public record BindOverrides(String bindAddress, int tcpPort, int wsPort) {
        public static BindOverrides loopbackEphemeral() {
            return new BindOverrides("127.0.0.1", 0, 0);
        }
    }

    /**
     * Applies the {@code filter} config section (custom words + regex
     * patterns) to the runtime filter. Invalid patterns are skipped one by one
     * with a warning so a single bad entry cannot disable the whole list.
     */
    private static void applyFilterConfig(SensitiveWordFilter filter, FilterConfig filterConfig) {
        if (filter == null || filterConfig == null) {
            return;
        }
        filter.setCustomWords(filterConfig.getWords());
        List<String> validPatterns = new ArrayList<>();
        for (String pattern : filterConfig.getPatterns()) {
            try {
                java.util.regex.Pattern.compile(pattern);
                validPatterns.add(pattern);
            } catch (java.util.regex.PatternSyntaxException e) {
                logger.warn("Skipping invalid filter pattern '{}': {}", pattern, e.getMessage());
            }
        }
        filter.setCustomPatterns(validPatterns);
    }

    /** {@code <dataDir>/novalink.yml} — never the JVM working directory. */
    private static Path resolveConfigPath(Path dataDir) {
        return dataDir.resolve("novalink.yml");
    }

    private static void registerClients(AuthManager authManager, NovaLinkConfig config) {
        if (config.getClients() == null) {
            return;
        }
        for (ClientConfig client : config.getClients()) {
            if (client == null) {
                continue;
            }
            String username = client.getUsername();
            String password = client.getPassword();
            if (username == null || username.isBlank() || password == null) {
                logger.warn("Skipping invalid client entry (username/password missing): {}", client);
                continue;
            }

            // Backward-compatible: allow config password to be either plain text or a precomputed SHA-256 hex hash.
            // Clients always send SHA-256(password) in the handshake, so backend stores SHA-256(password).
            // If the config already contains a 64-hex hash, do NOT hash again.
            String passwordHash = isSha256Hex(password)
                    ? password.trim().toLowerCase(Locale.ROOT)
                    : AuthManager.hashPassword(password);
            String displayName = (client.getDisplayName() != null && !client.getDisplayName().isBlank())
                    ? client.getDisplayName()
                    : username;

            authManager.registerClient(new ClientCredentials(username, passwordHash, displayName));
        }
    }

    /**
     * Builds a username → permission-node list map from client config for handshake grants.
     * Empty list means "grant wildcard" (full GLOBAL access) for backward compatibility.
     */
    private static Map<String, List<String>> buildClientPermissionBootstrap(NovaLinkConfig config) {
        Map<String, List<String>> bootstrap = new HashMap<>();
        if (config.getClients() == null) {
            return bootstrap;
        }
        for (ClientConfig client : config.getClients()) {
            if (client == null || client.getUsername() == null || client.getUsername().isBlank()) {
                continue;
            }
            List<String> perms = client.getPermissions();
            if (perms == null || perms.isEmpty()) {
                bootstrap.put(client.getUsername(), Collections.emptyList());
            } else {
                List<String> cleaned = new ArrayList<>();
                for (String p : perms) {
                    if (p != null && !p.isBlank()) {
                        cleaned.add(p.trim());
                    }
                }
                bootstrap.put(client.getUsername(), cleaned);
            }
        }
        return bootstrap;
    }

    /**
     * Grants GLOBAL channel permissions to an authenticated client.
     * If the client's configured list is empty/missing, grants {@link ClientPermissionRegistry#WILDCARD}.
     */
    private static void grantBootstrapPermissions(ClientPermissionRegistry registry,
                                                  Map<String, List<String>> bootstrap,
                                                  String clientId) {
        if (registry == null || clientId == null || clientId.isBlank()) {
            return;
        }
        List<String> configured = bootstrap != null ? bootstrap.get(clientId) : null;
        if (configured == null || configured.isEmpty()) {
            registry.grant(clientId, ClientPermissionRegistry.WILDCARD);
            logger.debug("Granted wildcard GLOBAL permissions to authenticated client '{}'", clientId);
        } else {
            registry.grantAll(clientId, configured);
            logger.debug("Granted configured GLOBAL permissions {} to client '{}'", configured, clientId);
        }
    }

    private static DatabaseProvider createDatabaseProvider(NovaLinkConfig config, Path dataDir) {
        String normalized = config.getDatabase().getType().trim().toLowerCase(Locale.ROOT);

        switch (normalized) {
            case "mysql":
                DatabaseConfig.MySQLConfig mysql = config.getDatabase().getMysql();
                // H1: thread the AUTH-002 plaintext opt-in into the JDBC layer.
                // sslMode=DISABLED without the flag will fail fast inside
                // MySQLProvider.buildJdbcUrl() before any socket opens.
                return new MySQLProvider(
                        mysql.getHost(),
                        mysql.getPort(),
                        mysql.getDatabase(),
                        mysql.getUsername(),
                        mysql.getPassword(),
                        mysql.getPoolSize(),
                        mysql.getSslMode(),
                        config.getServer().isInsecureAllowPlaintext()
                );
            case "postgresql":
            case "postgres":
            case "pg":
                DatabaseConfig.PostgreSQLConfig pg = config.getDatabase().getPostgresql();
                // H1b: thread the AUTH-002 plaintext opt-in into the JDBC layer.
                // ssl-mode=disable without the flag will fail fast inside
                // PostgreSQLProvider.buildJdbcUrl() before any socket opens.
                return new PostgreSQLProvider(
                        pg.getHost(),
                        pg.getPort(),
                        pg.getDatabase(),
                        pg.getUsername(),
                        pg.getPassword(),
                        pg.getPoolSize(),
                        pg.getSslMode(),
                        config.getServer().isInsecureAllowPlaintext()
                );
            case "sqlite":
                DatabaseConfig.SQLiteConfig sqlite = config.getDatabase().getSqlite();
                Path sqliteFile = resolveDataPath(sqlite.getFilePath(), dataDir);
                // sqlite-jdbc refuses to open a file whose parent directory is
                // missing. The standalone launcher used to get that directory
                // for free (data/ next to the working dir); an embedded core
                // gets a fresh plugin data folder, so create it up front.
                Path sqliteParent = sqliteFile.toAbsolutePath().getParent();
                if (sqliteParent != null) {
                    try {
                        Files.createDirectories(sqliteParent);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(
                                "Cannot create SQLite data directory " + sqliteParent, e);
                    }
                }
                return new SQLiteProvider(
                        sqliteFile.toString(),
                        sqlite.getPoolSize()
                );
            case "redis":
                DatabaseConfig.RedisConfig redis = config.getDatabase().getRedis();
                return new RedisProvider(redis.getHost(), redis.getPort(), redis.getPassword());
            case "memory":
                return new MemoryProvider();
            default:
                throw new IllegalArgumentException("Unsupported database type: " + normalized);
        }
    }

    /**
     * Resolves a configured file path against the data directory. Absolute
     * paths are kept as-is; relative ones (e.g. the default
     * {@code data/novalink.db}) previously resolved against the JVM working
     * directory, which in a plugin is the host server's root.
     */
    static Path resolveDataPath(String configured, Path dataDir) {
        if (configured == null || configured.isBlank()) {
            return dataDir;
        }
        Path path = Paths.get(configured);
        return path.isAbsolute() ? path : dataDir.resolve(path);
    }

    /**
     * Returns a copy of {@code tls} with its relative cert/key paths resolved
     * against {@code dataDir} so the listeners find them regardless of the
     * host's working directory.
     */
    static TlsConfig resolveTlsPaths(TlsConfig tls, Path dataDir) {
        if (tls == null) {
            return null;
        }
        TlsConfig resolved = new TlsConfig(
                resolveOptionalPath(tls.getCertChainFile(), dataDir),
                resolveOptionalPath(tls.getPrivateKeyFile(), dataDir),
                resolveOptionalPath(tls.getCaCertFile(), dataDir),
                tls.isMutualTls());
        return resolved;
    }

    private static String resolveOptionalPath(String configured, Path dataDir) {
        if (configured == null || configured.isBlank()) {
            return configured;
        }
        return resolveDataPath(configured, dataDir).toString();
    }

    private static void loadConfiguredChannels(ChannelManager channelManager, NovaLinkConfig config) {
        // Global channels
        if (config.getGlobalChannels() != null) {
            for (Map.Entry<String, GlobalChannelConfig> entry : config.getGlobalChannels().entrySet()) {
                String channelId = entry.getKey();
                GlobalChannelConfig cfg = entry.getValue();
                if (channelId == null || channelId.isBlank() || cfg == null) {
                    continue;
                }

                try {
                    channelManager.createChannel(ChannelConfig.builder()
                            .id(channelId)
                            .displayName(cfg.getDisplayName() != null ? cfg.getDisplayName() : channelId)
                            .scope(ChannelScope.GLOBAL)
                            .permission(cfg.getPermission())
                            .maxCapacity(cfg.getMaxCapacity())
                            .slowModeSeconds(cfg.getSlowModeSeconds())
                            .build(), ChannelSource.CONFIG);
                } catch (Exception e) {
                    logger.warn("Failed to create global channel '{}': {}", channelId, e.getMessage());
                }
            }
        }

        // Client channels (SERVER/PRIVATE)
        if (config.getClients() == null) {
            return;
        }

        for (ClientConfig client : config.getClients()) {
            if (client == null || client.getUsername() == null || client.getUsername().isBlank()) {
                continue;
            }
            String clientId = client.getUsername();

            if (client.getChannels() == null) {
                continue;
            }

            for (Map.Entry<String, ServerChannelConfig> entry : client.getChannels().entrySet()) {
                String channelId = entry.getKey();
                ServerChannelConfig channelCfg = entry.getValue();
                if (channelId == null || channelId.isBlank() || channelCfg == null) {
                    continue;
                }

                ChannelTemplateConfig template = null;
                if (channelCfg.getUseTemplate() != null && config.getTemplates() != null) {
                    template = config.getTemplates().get(channelCfg.getUseTemplate());
                }

                String displayName = firstNonBlank(channelCfg.getDisplayName(),
                        template != null ? template.getDisplayName() : null,
                        channelId);

                String scopeRaw = firstNonBlank(channelCfg.getScope(), template != null ? template.getScope() : null, "SERVER");
                ChannelScope scope = parseScope(scopeRaw, ChannelScope.SERVER);
                if (scope == ChannelScope.GLOBAL) {
                    logger.warn("Client channel '{}' for client '{}' configured as GLOBAL; forcing to SERVER for isolation",
                            channelId, clientId);
                    scope = ChannelScope.SERVER;
                }

                String permission = firstNonBlank(channelCfg.getPermission(), template != null ? template.getPermission() : null, null);

                int maxCapacity = 100;
                if (template != null && template.getMaxCapacity() != null) {
                    maxCapacity = template.getMaxCapacity();
                }
                if (channelCfg.getMaxCapacity() != null) {
                    maxCapacity = channelCfg.getMaxCapacity();
                }

                java.util.List<String> allowedWorlds = channelCfg.getAllowedWorlds() != null
                        ? channelCfg.getAllowedWorlds()
                        : (template != null && template.getAllowedWorlds() != null
                            ? template.getAllowedWorlds()
                            : java.util.Collections.emptyList());

                try {
                    channelManager.createChannel(ChannelConfig.builder()
                            .id(channelId)
                            .displayName(displayName)
                            .scope(scope)
                            .clientId(clientId)
                            .permission(permission)
                            .maxCapacity(maxCapacity)
                            .allowedWorlds(allowedWorlds)
                            .slowModeSeconds(channelCfg.getSlowModeSeconds())
                            .build(), ChannelSource.CONFIG);
                } catch (Exception e) {
                    logger.warn("Failed to create channel '{}' for client '{}': {}", channelId, clientId, e.getMessage());
                }
            }
        }
    }

    private static void applyConfiguredChannels(ChannelManager channelManager, NovaLinkConfig config) {
        if (config == null) {
            return;
        }

        // Track every channel ID declared in the new config so we can detect
        // (and remove) CONFIG channels that were deleted from config on reload.
        java.util.Set<String> desiredIds = new java.util.HashSet<>();

        // Global channels
        if (config.getGlobalChannels() != null) {
            for (Map.Entry<String, GlobalChannelConfig> entry : config.getGlobalChannels().entrySet()) {
                String channelId = entry.getKey();
                GlobalChannelConfig cfg = entry.getValue();
                if (channelId == null || channelId.isBlank() || cfg == null) {
                    continue;
                }

                ChannelConfig desired = ChannelConfig.builder()
                        .id(channelId)
                        .displayName(cfg.getDisplayName() != null ? cfg.getDisplayName() : channelId)
                        .scope(ChannelScope.GLOBAL)
                        .permission(cfg.getPermission())
                        .maxCapacity(cfg.getMaxCapacity())
                        .slowModeSeconds(cfg.getSlowModeSeconds())
                        .build();

                desiredIds.add(channelId);
                upsertConfiguredChannel(channelManager, desired);
            }
        }

        if (config.getClients() != null) {
            // Client channels (SERVER/PRIVATE)
            for (ClientConfig client : config.getClients()) {
                if (client == null || client.getUsername() == null || client.getUsername().isBlank()) {
                    continue;
                }
                String clientId = client.getUsername();

                if (client.getChannels() == null) {
                    continue;
                }

                for (Map.Entry<String, ServerChannelConfig> entry : client.getChannels().entrySet()) {
                    String channelId = entry.getKey();
                    ServerChannelConfig channelCfg = entry.getValue();
                    if (channelId == null || channelId.isBlank() || channelCfg == null) {
                        continue;
                    }

                    ChannelTemplateConfig template = null;
                    if (channelCfg.getUseTemplate() != null && config.getTemplates() != null) {
                        template = config.getTemplates().get(channelCfg.getUseTemplate());
                    }

                    String displayName = firstNonBlank(channelCfg.getDisplayName(),
                            template != null ? template.getDisplayName() : null,
                            channelId);

                    String scopeRaw = firstNonBlank(channelCfg.getScope(), template != null ? template.getScope() : null, "SERVER");
                    ChannelScope scope = parseScope(scopeRaw, ChannelScope.SERVER);
                    if (scope == ChannelScope.GLOBAL) {
                        scope = ChannelScope.SERVER;
                    }

                    String permission = firstNonBlank(channelCfg.getPermission(), template != null ? template.getPermission() : null, null);

                    int maxCapacity = 100;
                    if (template != null && template.getMaxCapacity() != null) {
                        maxCapacity = template.getMaxCapacity();
                    }
                    if (channelCfg.getMaxCapacity() != null) {
                        maxCapacity = channelCfg.getMaxCapacity();
                    }

                    java.util.List<String> allowedWorlds = channelCfg.getAllowedWorlds() != null
                            ? channelCfg.getAllowedWorlds()
                            : (template != null && template.getAllowedWorlds() != null
                            ? template.getAllowedWorlds()
                            : java.util.Collections.emptyList());

                    ChannelConfig desired = ChannelConfig.builder()
                            .id(channelId)
                            .displayName(displayName)
                            .scope(scope)
                            .clientId(clientId)
                            .permission(permission)
                            .maxCapacity(maxCapacity)
                            .allowedWorlds(allowedWorlds)
                            .slowModeSeconds(channelCfg.getSlowModeSeconds())
                            .build();

                    desiredIds.add(channelId);
                    upsertConfiguredChannel(channelManager, desired);
                }
            }
        }

        // PANEL-004: delete CONFIG channels that are no longer declared in the
        // config. Non-CONFIG (DATABASE/RUNTIME) channels are never touched by
        // reload, so a previously-deleted config channel is not revived and a
        // dynamic channel is never overwritten.
        java.util.List<String> staleConfigIds = new java.util.ArrayList<>();
        for (Channel ch : channelManager.getAllChannels()) {
            if (ch == null || ch.getSource() != ChannelSource.CONFIG) {
                continue;
            }
            if (!desiredIds.contains(ch.getId())) {
                staleConfigIds.add(ch.getId());
            }
        }
        for (String staleId : staleConfigIds) {
            channelManager.deleteChannel(staleId);
            logger.info("Removed config channel '{}' (no longer in config after reload)", staleId);
        }
    }

    /**
     * Applies a single config-declared channel on reload. PANEL-004 semantics:
     * <ul>
     *   <li>If the channel does not exist, it is created with source CONFIG.</li>
     *   <li>If it exists and its source is CONFIG, the config values are applied.</li>
     *   <li>If it exists but its source is DATABASE or RUNTIME (dynamic), it is
     *       left untouched — reload must not overwrite unmanaged channels.</li>
     * </ul>
     * Removed config channels are pruned by {@link #applyConfiguredChannels}.
     */
    private static void upsertConfiguredChannel(ChannelManager channelManager, ChannelConfig desired) {
        if (desired == null || desired.getId() == null || desired.getId().isBlank()) {
            return;
        }

        Channel existing = channelManager.getChannel(desired.getId());
        if (existing == null) {
            try {
                channelManager.createChannel(desired, ChannelSource.CONFIG);
            } catch (Exception e) {
                logger.warn("Failed to create channel '{}' from config reload: {}", desired.getId(), e.getMessage());
            }
            return;
        }

        // PANEL-004: never overwrite a dynamic (non-CONFIG) channel. This covers
        // DATABASE channels restored from persistence and RUNTIME channels
        // created via REST/console. A deleted config channel that was recreated
        // as dynamic stays dynamic and is not revived as CONFIG here.
        if (existing.getSource() != ChannelSource.CONFIG) {
            logger.debug("Skipping config reload for dynamic channel '{}' (source={})",
                    desired.getId(), existing.getSource());
            return;
        }

        // Avoid accidentally rewriting a CONFIG channel that belongs to a different client.
        if (existing.getScope() != ChannelScope.GLOBAL) {
            String existingClient = existing.getClientId();
            String desiredClient = desired.getClientId();
            if (existingClient != null && desiredClient != null && !existingClient.equals(desiredClient)) {
                logger.warn("Config reload attempted to update channel '{}' for client '{}' but it belongs to client '{}' - ignored",
                        desired.getId(), desiredClient, existingClient);
                return;
            }
        }

        existing.setDisplayName(desired.getDisplayName());
        existing.setPermission(desired.getPermission());
        existing.setMaxCapacity(desired.getMaxCapacity());
        existing.setAllowedWorlds(desired.getAllowedWorlds());
        existing.setSlowModeSeconds(desired.getSlowModeSeconds());
        existing.bumpRevision();
    }

    private static void loadPersistedChannels(ChannelManager channelManager, DatabaseProvider databaseProvider) {
        try {
            Collection<Channel> channels = databaseProvider.getAllChannels();
            for (Channel ch : channels) {
                if (ch == null || ch.getId() == null || ch.getId().isBlank()) {
                    continue;
                }
                if (channelManager.channelExists(ch.getId())) {
                    continue;
                }

                try {
                    channelManager.createChannel(ChannelConfig.builder()
                            .id(ch.getId())
                            .displayName(ch.getDisplayName() != null ? ch.getDisplayName() : ch.getId())
                            .scope(ch.getScope() != null ? ch.getScope() : ChannelScope.SERVER)
                            .clientId(ch.getClientId())
                            .permission(ch.getPermission())
                            .maxCapacity(ch.getMaxCapacity())
                            .allowedWorlds(ch.getAllowedWorlds())
                            .password(ch.getPassword())
                            .ownerId(ch.getOwnerId())
                            .build(), ChannelSource.DATABASE);
                } catch (Exception e) {
                    logger.warn("Failed to load persisted channel '{}': {}", ch.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            // Not fatal (e.g., MemoryProvider with no data, or DB temporarily unavailable)
            logger.debug("Skipping persisted channel load: {}", e.getMessage());
        }
    }

    private static void registerPacketHandlers(ServerNetworkHandler networkHandler,
                                               ConfigManager configManager,
                                               AuthManager authManager,
                                               ChannelManager channelManager,
                                               MessageRouter messageRouter,
                                               AdminActionHandler adminActionHandler,
                                               ChannelActionHandler channelActionHandler,
                                               ClientPermissionRegistry clientPermissionRegistry,
                                               Map<String, List<String>> clientPermissionBootstrap,
                                               PlayerStateManager playerStateManager,
                                               NotificationStore notificationStore,
                                               MuteManager muteManager,
                                               BanManager banManager,
                                               RateLimiter rateLimiter,
                                               java.util.function.BooleanSupplier privateMessagesEnabled,
                                               ChatLogger chatLogger,
                                               NonceCache nonceCache,
                                               DatabaseProvider databaseProvider,
                                               KeepAliveHandler keepAliveHandler) {
        // ==================== AUTH-002 challenge-response handshake ====================
        // Replaces the replayable static-hash HandshakePacket (0x01) flow with a
        // 3-packet dance: HandshakeInit (0x15) → HandshakeChallenge (0x16) →
        // HandshakeAuthenticate (0x17). The legacy 0x01 handler below is kept
        // only so existing callers still compile; live clients use the new path.
        networkHandler.registerHandler(HandshakeInitPacket.class, (connection, packet) -> {
            int clientProtocolVersion = packet.getProtocolVersion();
            if (clientProtocolVersion != NovaProtocol.PROTOCOL_VERSION) {
                HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                        NovaConstants.ERROR_PROTOCOL_MISMATCH,
                        String.format(
                                "Protocol version mismatch: client=%d, server=%d. Please update your client.",
                                clientProtocolVersion,
                                NovaProtocol.PROTOCOL_VERSION
                        )
                );
                response.setRequestId(packet.getRequestId());
                sendResponseAndClose(connection, response, "protocol mismatch");
                logger.warn("Protocol version mismatch from {} (clientId={}): client={}, server={}",
                        connection.getRemoteAddress(), packet.getClientId(),
                        clientProtocolVersion, NovaProtocol.PROTOCOL_VERSION);
                return;
            }

            String clientId = packet.getClientId();
            String clientNonce = packet.getClientNonce();
            if (clientId == null || clientId.isEmpty() || clientNonce == null || clientNonce.isEmpty()) {
                HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                        NovaConstants.ERROR_UNAUTHORIZED, "Invalid handshake init");
                response.setRequestId(packet.getRequestId());
                sendResponseAndClose(connection, response, "malformed init");
                return;
            }

            // Record the platform / server version the client reported so the
            // panel can display them in the server-status broadcast.
            connection.setPlatform(packet.getPlatform());
            connection.setServerVersion(packet.getServerVersion());

            // Generate a fresh server nonce (16 bytes → 32 lowercase-hex chars)
            // and store it in the NonceCache keyed by (clientId, clientNonce).
            // The entry is consumed exactly once on authenticate, so a replayed
            // init→authenticate pair finds nothing.
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
                        () -> grantBootstrapPermissions(
                                clientPermissionRegistry,
                                clientPermissionBootstrap,
                                clientId))) {
                    logger.warn("Authentication completed for client '{}' after its connection was closed; ignoring generation",
                            clientId);
                    return;
                }
                response = HandshakeResponsePacket.success("Authentication successful");
                logger.info("Client authenticated: {} from {}", clientId, connection.getRemoteAddress());
                if (notificationStore != null) {
                    try {
                        notificationStore.createNotification(
                                "Client Connected",
                                clientId + " has connected",
                                "info");
                    } catch (Exception ignored) {
                        // non-fatal
                    }
                }
            } else {
                response = HandshakeResponsePacket.failure(
                        authResult.getErrorCode() != null ? authResult.getErrorCode() : NovaConstants.ERROR_UNAUTHORIZED,
                        authResult.getMessage() != null ? authResult.getMessage() : "Authentication failed"
                );
                logger.warn("Challenge authentication failed for client '{}' from {}: {}",
                        clientId, connection.getRemoteAddress(), authResult.getErrorCode());
            }
            response.setRequestId(packet.getRequestId());
            if (authResult.isSuccess()) {
                connection.sendPacket(response);
            } else {
                sendResponseAndClose(connection, response, "authentication failure");
            }

            // Push current config snapshot after a successful handshake (hot reload baseline).
            if (authResult.isSuccess() && configManager != null) {
                try {
                    configManager.sendConfigSync(connection);
                } catch (Exception e) {
                    logger.debug("Failed to send initial config sync to client {}: {}",
                            clientId, e.getMessage());
                }
            }
        });

        // ==================== Legacy handshake handler (0x01) ====================
        // Kept for compile compatibility; live clients use the challenge-response
        // path above. Old v2 clients that still send 0x01 are rejected with NC-420.
        // Handshake handler (auth + version check)
        networkHandler.registerHandler(HandshakePacket.class, (connection, packet) -> {
            int clientProtocolVersion = packet.getProtocolVersion();
            if (clientProtocolVersion != NovaProtocol.PROTOCOL_VERSION) {
                HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                        NovaConstants.ERROR_PROTOCOL_MISMATCH,
                        String.format(
                                "Protocol version mismatch: client=%d, server=%d. Please update your client.",
                                clientProtocolVersion,
                                NovaProtocol.PROTOCOL_VERSION
                        )
                );
                response.setRequestId(packet.getRequestId());
                sendResponseAndClose(connection, response, "protocol mismatch");
                logger.warn("Protocol version mismatch from {} (clientId={}): client={}, server={}",
                        connection.getRemoteAddress(), packet.getClientId(), clientProtocolVersion, NovaProtocol.PROTOCOL_VERSION);
                return;
            }

            // Legacy static-hash auth is no longer accepted: the protocol bumped
            // to v3 and the challenge-response path is now mandatory.
            HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                    NovaConstants.ERROR_PROTOCOL_MISMATCH,
                    "Legacy handshake is no longer supported. Please update your client to use the challenge-response handshake."
            );
            response.setRequestId(packet.getRequestId());
            sendResponseAndClose(connection, response, "legacy handshake rejected");
            logger.warn("Rejected legacy HandshakePacket (0x01) from {} (clientId={}); protocol now requires challenge-response",
                    connection.getRemoteAddress(), packet.getClientId());
        });

        // Chat message handler — single pipeline entry (validate/boundary/mute/filter/fan-out).
        // Boundary enforcement is ON inside MessagePipeline.process via routeMessage(packet).
        networkHandler.registerHandler(ChatMessagePacket.class, (connection, packet) -> {
            if (!connection.isAuthenticated()) {
                return;
            }

            // Per-connection token bucket: excess messages are dropped before
            // touching the pipeline; the sender gets a throttled error response
            // (at most one per 5s) so a flood does not amplify into a response flood.
            if (rateLimiter != null && rateLimiter.isEnabled()
                    && !rateLimiter.tryAcquire(connection.getConnectionId())) {
                if (rateLimiter.shouldNotify(connection.getConnectionId())) {
                    logger.warn("Rate limit exceeded for client {} (chat message dropped)",
                            connection.getClientId());
                    sendThrottleError(connection, packet.getRequestId(), packet.getChannelId(),
                            NovaConstants.ERROR_TOO_MANY_REQUESTS, I18n.tr("network.error.rate_limited"), "rate_limit");
                }
                return;
            }

            // Stamp the authenticated client id so the pipeline can enforce SERVER/PRIVATE isolation.
            packet.setClientId(connection.getClientId());
            com.nova.link.channel.MessagePipelineResult result =
                    messageRouter.getPipeline().process(packet);

            // Slow-mode violations answer the sender with the remaining wait
            // time; other drop reasons keep their existing (silent) behavior.
            if (!result.isDelivered()
                    && result.getDropReason() == com.nova.link.channel.MessagePipelineResult.DropReason.SLOW_MODE) {
                sendThrottleError(connection, packet.getRequestId(), packet.getChannelId(),
                        NovaConstants.ERROR_TOO_MANY_REQUESTS,
                        I18n.tr("network.error.slow_mode", result.getSlowModeRemainingSeconds()),
                        "slow_mode");
            }
        });

        // Item display handler (0x10) — channel-routed fan-out sharing the
        // chat token bucket. The panel does not implement item display, so no
        // WS mirroring is wired.
        networkHandler.registerHandler(ItemDisplayPacket.class, new ItemDisplayHandler(
                channelManager,
                networkHandler,
                muteManager,
                banManager,
                rateLimiter,
                clientPermissionRegistry.asChecker(),
                () -> messageRouter.getPipeline().isCrossServerChatEnabled()
        ));

        // Private message handler (0x14) — cross-server /msg + /reply. Shares
        // the chat token bucket; audited with a [DM] marker only (privacy: no
        // messages-table persistence and no WS panel mirroring).
        // §11.6 item-18 Part B: directional ignore filter. Fail-open: on any
        // lookup failure return false (do not block the DM). databaseProvider
        // is passed into registerPacketHandlers (static idiom, not a field).
        networkHandler.registerHandler(PrivateMessagePacket.class, new PrivateMessageHandler(
                networkHandler,
                playerStateManager,
                muteManager,
                rateLimiter,
                privateMessagesEnabled,
                chatLogger,
                (src, tgt) -> { try { return databaseProvider.isIgnored(src, tgt); } catch (Exception e) { return false; } }
        ));

        // Channel action handler (minimal response; detailed actions handled elsewhere)
        networkHandler.registerHandler(ChannelActionPacket.class, (connection, packet) -> {
            ChannelActionResponsePacket response = channelActionHandler.handle(connection, packet);
            response.setRequestId(packet.getRequestId());
            connection.sendPacket(response);

            // Cross-server KICK/MUTE target notification (UX-DESIGN §5): the
            // response above goes to the OPERATOR's connection. The TARGET player
            // may be on a different connected server, so forward a copy to the
            // target's connection so its plugin can render the kick/mute notice.
            // The client-side ChannelResponseDispatcher renders the notice when it
            // receives a successful KICK/MUTE response carrying a targetId extra.
            if (response.isSuccess()
                    && (response.getAction() == ChannelAction.KICK
                        || response.getAction() == ChannelAction.MUTE
                        || response.getAction() == ChannelAction.BAN)) {
                forwardKickMuteToTarget(response, networkHandler, playerStateManager);
            }
        });

        // Keep-alive handler. Two directions exist:
        //  - client-initiated ping (Bedrock clients, every 15s): echo it back;
        //  - echo of a server-initiated ping (sent by ServerChannelHandler on
        //    write-idle for echo-only Java clients): record latency and DO NOT
        //    re-echo, otherwise both sides would ping-pong forever.
        // Recognition + the per-connection flood budget live in KeepAliveHandler
        // so the write-idle send path and this receive path share one guard.
        networkHandler.registerHandler(KeepAlivePacket.class, keepAliveHandler);

        // Admin action handler (super admin auth / spy / reload / status)
        networkHandler.registerHandler(AdminActionPacket.class, (connection, packet) -> {
            if (!connection.isAuthenticated()) {
                AdminActionResponsePacket response = AdminActionResponsePacket.failure(
                        packet.getAction(), NovaConstants.ERROR_UNAUTHORIZED, "Not authenticated"
                );
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response);
                return;
            }

            AdminActionResponsePacket response = adminActionHandler.handle(packet, connection.getRemoteAddress());
            response.setRequestId(packet.getRequestId());
            connection.sendPacket(response);
        });
    }

    /** Writes the explicit handshake failure before closing the unauthenticated channel. */
    private static void sendResponseAndClose(ClientConnection connection,
                                             HandshakeResponsePacket response,
                                             String reason) {
        connection.sendPacket(response).whenComplete((ignored, error) -> {
            if (error != null) {
                logger.warn("Failed to write handshake {} response to {}: {}",
                        reason, connection.getRemoteAddress(), error.getMessage());
            }
            connection.close();
        });
    }

    /**
     * Generates a fresh server nonce for the AUTH-002 challenge-response
     * handshake: 16 cryptographically-random bytes, lowercase-hex-encoded
     * (32 characters). Matches the wire format the client side expects.
     */
    private static String generateNonceHex() {
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

    /**
     * Sends a failure {@link ChannelActionResponsePacket} to the sender for
     * rate-limit / slow-mode rejections. This packet type is the protocol's
     * generic error carrier (server → client, error code + message + extras);
     * the {@code reason} extra distinguishes these unsolicited notices from
     * channel-action responses.
     */
    private static void sendThrottleError(ClientConnection connection, UUID requestId,
                                          String channelId, String errorCode,
                                          String message, String reason) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, null, channelId != null ? channelId : "", errorCode, message);
        response.setRequestId(requestId);
        response.addExtra("reason", reason);
        connection.sendPacket(response);
    }

    /**
     * Forwards a successful KICK/MUTE response to the TARGET player's connection
     * so the target's platform plugin can render the kick/mute notice
     * (UX-DESIGN §5). The operator already received the response directly; this
     * ensures a cross-server target (on a different connected game server) is
     * notified. No-op when the target is on the same server as the operator, not
     * connected, or when no targetId is available.
     */
    private static void forwardKickMuteToTarget(ChannelActionResponsePacket response,
                                                ServerNetworkHandler networkHandler,
                                                PlayerStateManager playerStateManager) {
        String targetIdRaw = response.getExtra("targetId");
        if (targetIdRaw == null || targetIdRaw.isEmpty()) {
            return;
        }
        UUID targetId;
        try {
            targetId = UUID.fromString(targetIdRaw);
        } catch (IllegalArgumentException e) {
            return;
        }
        PlayerState targetState = playerStateManager.getPlayerState(targetId);
        if (targetState == null) {
            return;
        }
        String targetClientId = targetState.getClientId();
        if (targetClientId == null || targetClientId.isEmpty()) {
            return;
        }
        ClientConnection targetConnection = networkHandler.findByClientId(targetClientId);
        if (targetConnection == null || !targetConnection.isActive() || !targetConnection.isAuthenticated()) {
            return;
        }
        // Forward a copy so the target plugin's ChannelResponseDispatcher renders
        // the notice. The dispatcher only needs the targetId extra + action +
        // channelId to render the notice (it no-ops when the pending context is
        // absent, which is the cross-server-push case).
        ChannelActionResponsePacket forward = new ChannelActionResponsePacket(
                response.isSuccess(), response.getAction(), response.getChannelId(),
                response.getErrorCode(), response.getMessage());
        forward.addExtra("targetId", targetIdRaw);
        String operatorName = response.getExtra("operatorName");
        if (operatorName != null && !operatorName.isEmpty()) {
            forward.addExtra("operatorName", operatorName);
        }
        String duration = response.getExtra("duration");
        if (duration != null && !duration.isEmpty()) {
            forward.addExtra("duration", duration);
        }
        targetConnection.sendPacket(forward);
        logger.debug("Forwarded {} response to target {} on client {}",
                response.getAction(), targetId, targetClientId);
    }

    private static ChannelScope parseScope(String raw, ChannelScope fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return ChannelScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String a, String b, String c) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        if (c != null && !c.isBlank()) return c;
        return null;
    }

    private static boolean isSha256Hex(String value) {
        if (value == null) {
            return false;
        }
        String s = value.trim();
        if (s.length() != 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isDigit = c >= '0' && c <= '9';
            boolean isLowerHex = c >= 'a' && c <= 'f';
            boolean isUpperHex = c >= 'A' && c <= 'F';
            if (!(isDigit || isLowerHex || isUpperHex)) {
                return false;
            }
        }
        return true;
    }

    private static void bootstrapPrivateChannelAdmins(PermissionManager permissionManager, ChannelManager channelManager) {
        for (Channel channel : channelManager.getAllChannels()) {
            if (channel.getScope() == ChannelScope.PRIVATE && channel.getOwnerId() != null) {
                try {
                    permissionManager.grantChannelAdmin(channel.getId(), channel.getOwnerId());
                } catch (Exception e) {
                    logger.debug("Failed to bootstrap channel admin for {}: {}", channel.getId(), e.getMessage());
                }
            }
        }
    }

}
