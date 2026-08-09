package com.nova.link;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.*;
import com.nova.link.auth.*;
import com.nova.link.auth.ClientPermissionRegistry;
import com.nova.link.api.WebhookManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.MessageRouter;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.channel.InvitationManager;
import com.nova.link.console.BackendConsole;
import com.nova.link.console.BackendContext;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.config.*;
import com.nova.link.database.*;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.i18n.I18n;
import com.nova.link.i18n.LocaleResolver;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.NettyServer;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.AdminActionHandler;
import com.nova.link.network.ChannelActionHandler;
import com.nova.link.spy.SpyManager;
import com.nova.link.websocket.WebSocketGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * NovaLink Backend Server - Main entry point
 * 
 * NovaLink is the central backend server for the NovaChat distributed chat system.
 * It handles message routing, authentication, permission management, and data persistence.
 */
public class NovaLinkMain {

    private static final Logger logger = LoggerFactory.getLogger(NovaLinkMain.class);

    /**
     * Runtime holder for all managers/handlers. Built once during startup and
     * referenced by both the console command layer and the JVM shutdown hook.
     * Volatile so the shutdown hook (which may run on a different thread) sees
     * the fully-published context.
     */
    private volatile BackendContext context;

    /**
     * Latch used by the JVM shutdown hook to signal the main (console) thread
     * that it should stop the readline loop and exit.
     */
    private volatile CountDownLatch shutdownLatch;

    public static void main(String[] args) {
        // --help / -h: print the command list and exit 0 without starting a server.
        // Lets the console wiring be smoke-tested without a full backend.
        if (args != null) {
            for (String a : args) {
                if ("--help".equals(a) || "-h".equals(a)) {
                    BackendConsole.printHelpAndExit();
                    return;
                }
            }
        }

        NovaLinkMain app = new NovaLinkMain();
        try {
            app.start(args);
        } catch (Exception e) {
            logger.error("Fatal error during startup: {}", e.getMessage(), e);
            // Best-effort cleanup if anything was partially initialized.
            app.safeShutdown();
            return;
        }
        app.runConsoleLoop();
    }

    /**
     * Builds all managers/handlers, starts the TCP + WebSocket servers, and
     * registers the JVM shutdown hook. On success, {@link #context} is set and
     * the caller may invoke {@link #runConsoleLoop()}.
     */
    private void start(String[] args) {
        logger.info("Starting NovaLink Backend Server...");
        logger.info("NovaLink v1.0.0-SNAPSHOT (protocol v{})", NovaProtocol.PROTOCOL_VERSION);

        Path configPath = resolveConfigPath(args);
        ConfigManager configManager = new ConfigManager(configPath);

        NovaLinkConfig config;
        try {
            config = configManager.load();
        } catch (ConfigException e) {
            logger.error("Failed to load configuration from {}: {}", configPath, e.getMessage(), e);
            return;
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
                Math.max(1, config.getSecurity().getIpBanDuration()) * 1000L
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
                        String webLoginUsername = (admin.getUsername() != null && !admin.getUsername().isBlank())
                                ? admin.getUsername()
                                : admin.getUuid().toString();
                        authManager.registerSuperAdmin(webLoginUsername, admin.getPasswordHash());
                    }
                } catch (Exception e) {
                    logger.warn("Failed to register super admin for web-panel auth: {}", admin, e);
                }
            }
        }

        // Initialize database
        DatabaseProvider databaseProvider = createDatabaseProvider(config);
        try {
            databaseProvider.initialize();
        } catch (DatabaseException e) {
            logger.error("Failed to initialize database provider {}: {}", databaseProvider.getProviderType(), e.getMessage(), e);
            return;
        }

        // Core managers
        ChannelManager channelManager = new ChannelManager();
        PlayerStateManager playerStateManager = new PlayerStateManager(databaseProvider);
        WebhookManager webhookManager = new WebhookManager();
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);
        InvitationManager invitationManager = new InvitationManager(databaseProvider, channelManager);
        MuteManager muteManager = new MuteManager(databaseProvider, permissionManager, channelManager);
        muteManager.initialize();
        SensitiveWordFilter sensitiveWordFilter = new SensitiveWordFilter();

        loadConfiguredChannels(channelManager, config);
        loadPersistedChannels(channelManager, databaseProvider);
        bootstrapPrivateChannelAdmins(permissionManager, channelManager);

        // Network + routing
        int workerThreads = Math.max(1, config.getServer().getWorkerThreads());
        ServerNetworkHandler networkHandler = new ServerNetworkHandler(workerThreads);
        MessageRouter messageRouter = new MessageRouter(channelManager, networkHandler);
        messageRouter.setMuteManager(muteManager);
        messageRouter.setSensitiveWordFilter(sensitiveWordFilter);
        messageRouter.setPermissionChecker(clientPermissionRegistry.asChecker());

        // Clear grants when a game server disconnects so reconnect gets a fresh bootstrap.
        networkHandler.setDisconnectListener(connection -> {
            String clientId = connection != null ? connection.getClientId() : null;
            if (clientId != null && !clientId.isBlank()) {
                clientPermissionRegistry.clearClient(clientId);
                logger.debug("Cleared permission grants for disconnected client '{}'", clientId);
            }
        });

        // Enable config hot-reload broadcasting via the network layer
        configManager.setNetworkHandler(networkHandler);
        configManager.startWatching();
        configManager.addReloadListener(newConfig -> {
            applyConfiguredChannels(channelManager, newConfig);
            bootstrapPrivateChannelAdmins(permissionManager, channelManager);
        });

        SpyManager spyManager = new SpyManager(permissionManager, channelManager, networkHandler);
        messageRouter.setSpyManager(spyManager);

        AdminActionHandler adminActionHandler = new AdminActionHandler(permissionManager);
        adminActionHandler.setSpyManager(spyManager);
        adminActionHandler.setChannelManager(channelManager);
        adminActionHandler.setNetworkHandler(networkHandler);
        adminActionHandler.setMessageRouter(messageRouter);
        adminActionHandler.setConfigManager(configManager);

        ChannelActionHandler channelActionHandler = new ChannelActionHandler(
                channelManager,
                playerStateManager,
                databaseProvider,
                privateChannelManager,
                invitationManager,
                permissionManager,
                muteManager
        );

        // Servers
        NettyServer tcpServer = new NettyServer(
                config.getServer().getBindAddress(),
                config.getServer().getPort(),
                workerThreads,
                networkHandler
        );

        WebSocketGateway webSocketGateway = new WebSocketGateway(
                config.getServer().getBindAddress(),
                config.getServer().getWebsocketPort(),
                config.getServer().getSecretKey(),
                authManager,
                channelManager,
                playerStateManager,
                messageRouter,
                webhookManager,
                networkHandler
        );
        messageRouter.setWebSocketBroadcaster(webSocketGateway.createBroadcaster());

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
                playerStateManager
        );

        CompletableFuture<Void> startFuture = CompletableFuture.allOf(
                tcpServer.start(),
                webSocketGateway.start()
        );

        try {
            startFuture.join();
        } catch (Exception e) {
            logger.error("Failed to start NovaLink services: {}", e.getMessage(), e);
            // Build a partial context so safeShutdown can clean up what exists.
            this.context = new BackendContext(
                    configManager, authManager, permissionManager, clientPermissionRegistry,
                    databaseProvider, channelManager, playerStateManager, webhookManager,
                    privateChannelManager, invitationManager, muteManager, sensitiveWordFilter,
                    networkHandler, messageRouter, spyManager, tcpServer, webSocketGateway);
            safeShutdown();
            return;
        }

        logger.info("NovaLink Backend Server started successfully on tcp {}:{} and ws {}:{}",
                config.getServer().getBindAddress(), config.getServer().getPort(),
                config.getServer().getBindAddress(), config.getServer().getWebsocketPort());

        // Publish the fully-initialized context for the console + shutdown hook.
        this.context = new BackendContext(
                configManager, authManager, permissionManager, clientPermissionRegistry,
                databaseProvider, channelManager, playerStateManager, webhookManager,
                privateChannelManager, invitationManager, muteManager, sensitiveWordFilter,
                networkHandler, messageRouter, spyManager, tcpServer, webSocketGateway);

        // JVM shutdown hook: Ctrl+C / SIGTERM -> same safeShutdown the 'stop'
        // console command uses. Signals the main thread via the latch.
        this.shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown requested (JVM hook). Stopping services...");
            appSafeShutdown();
            if (shutdownLatch != null) {
                shutdownLatch.countDown();
            }
        }, "NovaLink-Shutdown"));
    }

    /**
     * Runs the interactive console on the main thread (replaces the old
     * {@code shutdownLatch.await()}). The {@code stop}/{@code shutdown} command
     * and EOF/Ctrl+C invoke {@link #safeShutdown()} via the console's shutdown
     * callback, then the loop returns. The JVM shutdown hook remains active for
     * SIGTERM.
     */
    private void runConsoleLoop() {
        if (context == null) {
            logger.error("Backend context not initialized; console loop skipped.");
            return;
        }
        ConsoleCommandHandler cmdHandler = new ConsoleCommandHandler(context);
        Runnable consoleShutdown = this::appSafeShutdown;
        BackendConsole console = new BackendConsole(cmdHandler, consoleShutdown);

        // Run on the main thread. The loop blocks on readline until stop/EOF.
        console.run();
        logger.info("Console loop exited; NovaLink main thread returning.");
    }

    /**
     * Instance shutdown used by the console loop and JVM hook — calls the
     * shared {@link #safeShutdown()} against the published context.
     */
    private void appSafeShutdown() {
        try {
            safeShutdown();
        } catch (Exception e) {
            logger.debug("Error during appSafeShutdown: {}", e.getMessage());
        }
    }

    private static Path resolveConfigPath(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            return Paths.get(args[0]);
        }
        return Paths.get("novalink.yml");
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

    private static DatabaseProvider createDatabaseProvider(NovaLinkConfig config) {
        String type = config.getDatabase() != null ? config.getDatabase().getType() : "memory";
        String normalized = type != null ? type.trim().toLowerCase(Locale.ROOT) : "memory";

        switch (normalized) {
            case "mysql":
                DatabaseConfig.MySQLConfig mysql = config.getDatabase().getMysql();
                return new MySQLProvider(
                        mysql.getHost(),
                        mysql.getPort(),
                        mysql.getDatabase(),
                        mysql.getUsername(),
                        mysql.getPassword(),
                        mysql.getPoolSize()
                );
            case "redis":
                DatabaseConfig.RedisConfig redis = config.getDatabase().getRedis();
                return new RedisProvider(redis.getHost(), redis.getPort(), redis.getPassword());
            case "memory":
            default:
                return new MemoryProvider();
        }
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
                            .build());
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
                            .build());
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
                        .build();

                upsertConfiguredChannel(channelManager, desired);
            }
        }

        if (config.getClients() == null) {
            return;
        }

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
                        .build();

                upsertConfiguredChannel(channelManager, desired);
            }
        }
    }

    private static void upsertConfiguredChannel(ChannelManager channelManager, ChannelConfig desired) {
        if (desired == null || desired.getId() == null || desired.getId().isBlank()) {
            return;
        }

        Channel existing = channelManager.getChannel(desired.getId());
        if (existing == null) {
            try {
                channelManager.createChannel(desired);
            } catch (Exception e) {
                logger.warn("Failed to create channel '{}' from config reload: {}", desired.getId(), e.getMessage());
            }
            return;
        }

        // Never mutate runtime private channels from config reload.
        if (existing.getScope() == ChannelScope.PRIVATE) {
            return;
        }

        // Avoid accidentally rewriting a channel that belongs to a different client.
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
                            .build());
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
                                               PlayerStateManager playerStateManager) {
        // Handshake handler (auth + version check)
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
                connection.sendPacket(response);
                logger.warn("Protocol version mismatch from {} (clientId={}): client={}, server={}",
                        connection.getRemoteAddress(), packet.getClientId(), clientProtocolVersion, NovaProtocol.PROTOCOL_VERSION);
                return;
            }

            AuthResult authResult = authManager.authenticate(
                    packet.getClientId(),
                    packet.getPasswordHash(),
                    connection.getRemoteAddress()
            );

            HandshakeResponsePacket response;
            if (authResult.isSuccess()) {
                connection.setAuthenticated(true);
                connection.setClientId(packet.getClientId());
                // Bootstrap GLOBAL channel permission grants for this game-server client.
                grantBootstrapPermissions(clientPermissionRegistry, clientPermissionBootstrap, packet.getClientId());
                response = HandshakeResponsePacket.success("Authentication successful");
                logger.info("Client authenticated: {} from {}", packet.getClientId(), connection.getRemoteAddress());
            } else {
                response = HandshakeResponsePacket.failure(
                        authResult.getErrorCode() != null ? authResult.getErrorCode() : "NC-401",
                        authResult.getMessage() != null ? authResult.getMessage() : "Authentication failed"
                );
                logger.warn("Authentication failed for client '{}' from {}: {}",
                        packet.getClientId(), connection.getRemoteAddress(), authResult.getErrorCode());
            }
            response.setRequestId(packet.getRequestId());
            connection.sendPacket(response);

            // Push current config snapshot after a successful handshake (hot reload baseline).
            if (authResult.isSuccess() && configManager != null) {
                try {
                    configManager.sendConfigSync(connection);
                } catch (Exception e) {
                    logger.debug("Failed to send initial config sync to client {}: {}",
                            packet.getClientId(), e.getMessage());
                }
            }
        });

        // Chat message handler — single pipeline entry (validate/boundary/mute/filter/fan-out).
        // Boundary enforcement is ON inside MessagePipeline.process via routeMessage(packet).
        networkHandler.registerHandler(ChatMessagePacket.class, (connection, packet) -> {
            if (!connection.isAuthenticated()) {
                return;
            }

            // Stamp the authenticated client id so the pipeline can enforce SERVER/PRIVATE isolation.
            packet.setClientId(connection.getClientId());
            messageRouter.routeMessage(packet);
        });

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
                        || response.getAction() == ChannelAction.MUTE)) {
                forwardKickMuteToTarget(response, networkHandler, playerStateManager);
            }
        });

        // Keep-alive handler
        networkHandler.registerHandler(KeepAlivePacket.class, (connection, packet) -> {
            KeepAlivePacket response = new KeepAlivePacket(packet.getTimestamp());
            response.setRequestId(packet.getRequestId());
            connection.sendPacket(response);
        });

        // Admin action handler (super admin auth / spy / reload / status)
        networkHandler.registerHandler(AdminActionPacket.class, (connection, packet) -> {
            if (!connection.isAuthenticated()) {
                AdminActionResponsePacket response = AdminActionResponsePacket.failure(
                        packet.getAction(), "NC-401", "Not authenticated"
                );
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response);
                return;
            }

            AdminActionResponsePacket response = adminActionHandler.handle(packet);
            response.setRequestId(packet.getRequestId());
            connection.sendPacket(response);
        });
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

    /**
     * Graceful shutdown driven by the published {@link #context}. Called by
     * both the {@code stop}/{@code shutdown} console command (via
     * {@link #appSafeShutdown()}) and the JVM shutdown hook. Each step is
     * best-effort so one failing component does not prevent later cleanup.
     */
    private void safeShutdown() {
        BackendContext ctx = this.context;
        if (ctx == null) {
            // Nothing was initialized (e.g. config load failed) — nothing to shut down.
            return;
        }
        safeShutdownComponents(ctx);
    }

    /**
     * Shared component shutdown used by both the instance path and the legacy
     * static callers. Keeps the exact same ordering + try/catch isolation as
     * the original so behavior is unchanged for the 40 existing tests + the
     * real server.
     */
    private static void safeShutdownComponents(BackendContext ctx) {
        // Best-effort: each step should not prevent subsequent shutdown steps.
        try {
            if (ctx.getMuteManager() != null) {
                ctx.getMuteManager().shutdown();
            }
        } catch (Exception e) {
            logger.debug("Error shutting down MuteManager: {}", e.getMessage());
        }

        try {
            if (ctx.getWebSocketGateway() != null) {
                ctx.getWebSocketGateway().shutdown().join();
            }
        } catch (Exception e) {
            logger.debug("Error shutting down WebSocket gateway: {}", e.getMessage());
        }

        try {
            if (ctx.getTcpServer() != null) {
                ctx.getTcpServer().shutdown().join();
            }
        } catch (Exception e) {
            logger.debug("Error shutting down TCP server: {}", e.getMessage());
        }

        try {
            if (ctx.getNetworkHandler() != null) {
                ctx.getNetworkHandler().shutdown();
            }
        } catch (Exception e) {
            logger.debug("Error shutting down network handler: {}", e.getMessage());
        }

        try {
            if (ctx.getPlayerStateManager() != null) {
                ctx.getPlayerStateManager().saveAllDirty();
                ctx.getPlayerStateManager().clearCache(false);
            }
        } catch (Exception e) {
            logger.debug("Error flushing player state: {}", e.getMessage());
        }

        try {
            if (ctx.getDatabaseProvider() != null) {
                ctx.getDatabaseProvider().shutdown();
            }
        } catch (Exception e) {
            logger.debug("Error shutting down database provider: {}", e.getMessage());
        }

        try {
            if (ctx.getConfigManager() != null) {
                ctx.getConfigManager().shutdown();
            }
        } catch (Exception e) {
            logger.debug("Error shutting down config manager: {}", e.getMessage());
        }

        try {
            if (ctx.getWebhookManager() != null) {
                ctx.getWebhookManager().shutdown();
            }
        } catch (Exception e) {
            logger.debug("Error shutting down webhook manager: {}", e.getMessage());
        }
    }
}
