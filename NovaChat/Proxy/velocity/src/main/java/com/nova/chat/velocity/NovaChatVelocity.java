package com.nova.chat.velocity;

import com.google.inject.Inject;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.command.ProxyCommandCore;
import com.nova.chat.proxy.config.ProxyMode;
import com.nova.chat.proxy.engine.ProxyServerSwitchCore;
import com.nova.chat.proxy.lite.LocalAuthority;
import com.nova.chat.proxy.lite.LocalAuthorityClient;
import com.nova.chat.velocity.chat.MessageFormatter;
import com.nova.chat.velocity.platform.VelocityProxyPlatform;
import com.nova.chat.velocity.chat.ChatListener;
import com.nova.chat.velocity.chat.MentionTabCompleter;
import com.nova.chat.velocity.command.NovaChatCommand;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.nova.chat.velocity.listener.LocaleCaptureListener;
import com.nova.chat.velocity.listener.PluginMessageListener;
import com.nova.chat.velocity.listener.ServerSwitchHandler;
import com.nova.chat.velocity.network.NetworkClient;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * NovaChat Velocity Plugin - Main class
 * 
 * This plugin provides chat channel functionality for Velocity proxy servers,
 * connecting to the NovaLink backend for cross-server communication.
 * 
 * Supports: Velocity proxy servers (Requirements: 23.2)
 * 
 * Key features:
 * - Cancel-and-resend strategy for handling chat signing issues
 * - Cross-server message routing through NovaLink backend
 * - Server switch handling for proper channel management
 */
@Plugin(
    id = "novachat",
    name = "NovaChat",
    version = "1.0.0-SNAPSHOT",
    description = "Cross-server chat channel system",
    authors = {"Nova Team"}
)
public class NovaChatVelocity {
    
    private static NovaChatVelocity instance;
    
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    
    /** Plugin configuration */
    private NovaChatConfig config;
    
    /** Network client for backend connection */
    private NetworkClient networkClient;

    /**
     * Lite-mode transport stand-in: an in-process local authority instead of a
     * Netty backend link. Exactly one of {@link #networkClient} and this field
     * is non-null, decided by {@code mode} at startup and fixed for the process
     * lifetime (a reload that changes the mode is rejected).
     */
    private LocalAuthorityClient localAuthorityClient;

    /** Lite authority bound to the shared chat core, or null in relay mode. */
    private LocalAuthority liteAuthority;
    
    /** Chat listener for handling chat events */
    private ChatListener chatListener;
    
    /** Mention Tab completer for @mentions in chat */
    private MentionTabCompleter mentionTabCompleter;
    
    /** Server switch handler for cross-server routing */
    private ServerSwitchHandler serverSwitchHandler;

    /** Captures player client locales for per-player i18n (Architecture B) */
    private LocaleCaptureListener localeCaptureListener;

    /**
     * Shared known-channel registry populated from backend ConfigSync pushes
     * (UX-DESIGN §2.1). Consumed by {@code /nc list} and {@code /nc join <Tab>}.
     */
    private com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry;

    /**
     * Shared channel command intents (join/leave/toggle/reload).
     * PacketSender resolves {@link #networkClient} on each send so reload/reconnect
     * does not leave a stale client reference.
     */
    private ChannelCommandService channelCommandService;

    /**
     * Shared per-player ignore lists (client-core). Persisted to
     * {@code <dataDirectory>/ignore-lists.json}; consulted by the inbound
     * chat / mention / item-display render paths and the {@code /nc ignore}
     * command family.
     */
    private com.nova.chat.client.ignore.IgnoreListService ignoreListService;

    /**
     * Shared private-message core (client-core): send-side packet building,
     * receive-side role rendering, reply-target tracking for {@code /nc r}
     * and backend error rendering.
     */
    private com.nova.chat.client.privatemsg.PrivateMessageService privateMessageService;

    /** Shared proxy chat core (chat handlers, state, outbound intercept). */
    private ProxyChatCore chatCore;

    /** Shared proxy command core (11 subcommands + completions). */
    private ProxyCommandCore commandCore;

    /** Shared server-switch core. */
    private ProxyServerSwitchCore serverSwitchCore;

    /**
     * Proxy half of the sub-server plugin-messaging channel. Built with the
     * shared cores so it can route upstream chat through the lite authority or
     * the relay backend link without either shell owning that decision.
     */
    private com.nova.chat.proxy.channel.ProxyChannelHandler proxyChannelHandler;

    /** Listener for {@code novachat:main} plugin messages (registered on enable). */
    private PluginMessageListener pluginMessageListener;

    /** Channel identifier registered with Velocity so the proxy accepts the channel. */
    private com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier channelIdentifier;

    /** Velocity platform adapter (Adventure rendering, scheduler, logger). */
    private VelocityProxyPlatform platform;

    /** Velocity-side message formatter shell (renders shared strings). */
    private MessageFormatter messageFormatter;

    /** Debug mode flag */
    private boolean debugMode = false;
    
    @Inject
    public NovaChatVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        instance = this;
    }
    
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("NovaChat Velocity plugin initializing...");

        // Extract default lang/ bundles to <dataDirectory>/lang/ (template for
        // user edits / drop-in new languages). I18n reads external overrides on
        // top of the classpath bundles (external wins per-key).
        I18n.setExternalLangDir(dataDirectory);
        extractDefaultLang("zh_CN");
        extractDefaultLang("en_US");

        // Load the resource-backed configuration before initializing services.
        if (!loadConfiguration()) {
            logger.error("NovaChat initialization stopped because config.toml could not be loaded safely");
            return;
        }

        // Per-player ignore lists (persisted in the data directory,
        // injection precedent: I18n.setExternalLangDir above).
        ignoreListService = new com.nova.chat.client.ignore.IgnoreListService();
        ignoreListService.setDataDirectory(dataDirectory);

        // Shared private-message core (/nc msg, /nc r).
        privateMessageService = new com.nova.chat.client.privatemsg.PrivateMessageService();

        // Drop the cached bundles so a reload re-reads external overrides.
        I18n.invalidate();

        // Initialize the backend link. Lite mode has no backend at all, so no
        // NetworkClient is created and nothing below may assume one exists.
        initializeNetworkClient();

        // Shared command intent service (Architecture B client-core)
        initializeChannelCommandService();

        // Shared proxy cores (chat / command / server switch) built on the
        // Velocity platform adapter. Both proxies share this layer.
        initializeProxyCores();

        // Accept novachat:main from downstream servers (plugin messaging).
        initializePluginChannel();

        // Register event listeners
        registerListeners();
        
        // Register commands
        registerCommands();
        
        logger.info("NovaChat Velocity plugin enabled!");
    }
    
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("NovaChat Velocity plugin disabling...");

        if (channelIdentifier != null) {
            try {
                server.getChannelRegistrar().unregister(channelIdentifier);
            } catch (RuntimeException e) {
                logger.warn("Failed to unregister the plugin-messaging channel", e);
            }
            channelIdentifier = null;
        }
        pluginMessageListener = null;
        proxyChannelHandler = null;

        // Disconnect from backend (relay) or stop the local dispatcher (lite)
        if (networkClient != null) {
            networkClient.disconnect();
            networkClient = null;
        }
        if (localAuthorityClient != null) {
            localAuthorityClient.shutdown();
            localAuthorityClient = null;
        }
        liteAuthority = null;

        // Flush pending ignore-list writes and stop the save thread
        if (ignoreListService != null) {
            ignoreListService.close();
            ignoreListService = null;
        }

        instance = null;
        logger.info("NovaChat Velocity plugin disabled!");
    }
    
    /**
     * Extracts a default lang bundle from the jar to
     * {@code <dataDirectory>/lang/<locale>.properties} only when it does not
     * already exist (so user customizations win). The classpath resource path
     * is {@code lang/<locale>.properties}. Errors are logged but never fatal.
     */
    private void extractDefaultLang(String locale) {
        if (locale == null) {
            return;
        }
        Path langDir = dataDirectory.resolve("lang");
        Path target = langDir.resolve(locale + ".properties");
        if (Files.isRegularFile(target)) {
            return;
        }
        String resourcePath = "/lang/" + locale + ".properties";
        try (InputStream in = NovaChatVelocity.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return;
            }
            Files.createDirectories(langDir);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warn("Failed to extract default lang bundle " + locale, e);
        }
    }

    /**
     * Loads or reloads the plugin configuration.
     *
     * <p>The new document is parsed first; the running snapshot is replaced only
     * once it validated, so a bad file leaves the previous values in effect.
     */
    public boolean loadConfiguration() {
        NovaChatConfig previousConfig = config;
        boolean previousDebugMode = debugMode;
        try {
            NovaChatConfig loaded = new NovaChatConfig(dataDirectory);
            applyConfiguration(loaded);
            return true;
        } catch (Exception e) {
            config = previousConfig;
            debugMode = previousDebugMode;
            logger.error("Failed to install, upgrade, or load config.toml; the file was not overwritten", e);
            return false;
        }
    }

    /**
     * Installs a validated configuration snapshot and logs the upgrade outcome.
     *
     * @param loaded the freshly parsed configuration
     */
    private void applyConfiguration(NovaChatConfig loaded) {
        config = loaded;
        debugMode = loaded.isDebug();

        if (loaded.wasConfigCreated()) {
            logger.info("Created config.toml from the bundled template");
        } else if (loaded.wasConfigUpdated()) {
            logger.info("Added new config.toml fields; previous file saved to {}",
                    loaded.getConfigBackupPath());
        }

        I18n.setDefaultLocale(LocaleResolver.parseOrDefault(
                loaded.getLocale(), LocaleResolver.ROOT_LOCALE));
        I18n.invalidate();

        if (debugMode) {
            logger.info("[Debug] Configuration loaded successfully");
        }
    }
    
    /**
     * Initializes the network client and connects to the backend.
     *
     * <p>In {@code lite} mode this only seeds the local channel roster: no
     * {@link NetworkClient} is created and no connection is attempted, so
     * {@link #getNetworkClient()} keeps returning null for the lifetime of the
     * process. Every consumer of the client is null-tolerant (see
     * {@code ProxyChatCore} / {@code ProxyServerSwitchCore} /
     * {@code ProxyCommandCore}), so the shared cores degrade to "backend not
     * connected" replies instead of throwing.
     */
    private void initializeNetworkClient() {
        if (config == null) {
            logger.error("Cannot initialize network client: configuration not loaded");
            return;
        }

        if (config.getMode() == ProxyMode.LITE) {
            initializeLiteMode();
            return;
        }

        knownChannelRegistry = new com.nova.chat.client.channel.KnownChannelRegistry();
        networkClient = new NetworkClient(this, config);

        // UX-DESIGN §2.1: register a minimal ConfigSync handler that fills the
        // shared registry so /nc list and join <Tab> have data when the backend
        // pushes a roster. If the backend never pushes, the registry stays empty
        // and consumers degrade gracefully (no crash).
        com.nova.chat.client.channel.ConfigSyncHandlerRegistrar.register(
                networkClient, knownChannelRegistry,
                config != null ? config.getClientId() : null);

        // Connect asynchronously
        server.getScheduler()
            .buildTask(this, () -> {
                networkClient.connect(
                    config.getBackendHost(),
                    config.getBackendPort()
                ).thenAccept(success -> {
                    if (success) {
                        logger.info("Connected to NovaLink backend at " +
                            config.getBackendHost() + ":" + config.getBackendPort());
                    } else {
                        logger.warn("Failed to connect to NovaLink backend. Will retry...");
                    }
                });
            })
            .schedule();
    }

    /**
     * Seeds the state that lite mode still needs without a backend.
     *
     * <p>The roster is filled from {@code lite.channels} so {@code /nc list},
     * {@code /nc join} completion and channel-prefix routing see the locally
     * declared channels. No {@link NetworkClient} is created: lite has no
     * backend to talk to.
     *
     * <p>The shared cores still need a client-shaped object to route their
     * upstream packets into the local authority, so a
     * {@link LocalAuthorityClient} is created instead. It is bound to the
     * authority in {@link #initializeProxyCores()}, once the chat core exists.
     * A reload reuses the same client: the cores registered their downstream
     * handlers on it and must keep them.
     */
    private void initializeLiteMode() {
        // Reuse the registry object on reload: ProxyChatCore and ProxyCommandCore
        // hold the reference taken at startup, so replacing it would leave them
        // looking at a stale roster.
        if (knownChannelRegistry == null) {
            knownChannelRegistry = new com.nova.chat.client.channel.KnownChannelRegistry();
        }
        knownChannelRegistry.replaceAll(config.getLiteChannels().keySet());
        networkClient = null;
        if (localAuthorityClient == null) {
            // initializeNetworkClient runs before initializeProxyCores, so the
            // adapter may not exist yet; create it once and let the core
            // initialization below reuse it.
            if (platform == null) {
                platform = new VelocityProxyPlatform(this);
            }
            localAuthorityClient = LocalAuthorityClient.create(platform);
        }
        logger.info("NovaChat running in lite mode (local authority, no backend connection); "
                + config.getLiteChannels().size() + " local channel(s) declared");
    }

    /**
     * Builds the shared proxy cores (chat / command / server switch).
     *
     * <p>These are platform-agnostic classes from {@code :NovaChat:Proxy:proxy-common};
     * this method only supplies the Velocity adapter plus the shared client-core
     * services.
     */
    private void initializeProxyCores() {
        if (platform == null) {
            platform = new VelocityProxyPlatform(this);
        }
        chatCore = new ProxyChatCore(
                platform,
                config,
                ignoreListService,
                privateMessageService,
                knownChannelRegistry,
                this::getPlatformNetworkClient);
        commandCore = new ProxyCommandCore(
                platform,
                chatCore,
                channelCommandService,
                ignoreListService,
                privateMessageService,
                knownChannelRegistry,
                this::getConfig);
        serverSwitchCore = new ProxyServerSwitchCore(platform, chatCore);
        messageFormatter = new MessageFormatter(this);

        // Lite: bind the local authority once the chat core (state store,
        // formatter, ignore lists) exists. The client itself was created in
        // initializeLiteMode so the core's client supplier saw it at build time.
        if (localAuthorityClient != null) {
            liteAuthority = new LocalAuthority(
                    platform, chatCore, ignoreListService);
            localAuthorityClient.bind(liteAuthority);
        }

        // Plugin messaging: lite routes through the authority, relay through the
        // backend link. The sink resolves the live client on every send so a
        // reload that swaps NetworkClient is picked up without rebuilding this
        // handler.
        proxyChannelHandler = new com.nova.chat.proxy.channel.ProxyChannelHandler(
                platform,
                chatCore,
                liteAuthority != null ? liteAuthority::handle : null,
                liteAuthority == null ? this::relayChannelPacket : null);

        // H6: the ChatListener shell binds inbound handlers in its constructor;
        // this is the first point where the network client exists.
        if (chatCore.getNetworkClient() != null) {
            chatCore.rebindIncomingHandlers(chatCore.getNetworkClient());
        }
    }

    /**
     * Forwards one packet received on the plugin-messaging channel to the
     * backend (relay mode). The packet is sent unchanged: its {@code clientId}
     * already identifies the sub-server that produced it, so backend
     * attribution is preserved.
     *
     * @param packet the upstream packet
     */
    private void relayChannelPacket(com.nova.chat.common.protocol.Packet packet) {
        AbstractPlatformNetworkClient client = getPlatformNetworkClient();
        if (client == null || !client.isAuthenticated()) {
            debug("Dropped plugin-message packet " + packet.getClass().getSimpleName()
                    + ": backend link is not authenticated");
            return;
        }
        client.sendPacket(packet);
    }

    /**
     * Registers the {@code novachat:main} channel with Velocity.
     *
     * <p>Registration is what makes the proxy fire {@code PluginMessageEvent}
     * for this channel; without it server-originated payloads are forwarded to
     * the player's client (or dropped) instead of reaching the handler. It is
     * idempotent on the proxy side, so a reload does not need to repeat it.
     */
    private void initializePluginChannel() {
        if (channelIdentifier != null) {
            return;
        }
        try {
            channelIdentifier = com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
                    .from(com.nova.chat.channel.ProxyChannelProtocol.CHANNEL_ID);
            server.getChannelRegistrar().register(channelIdentifier);
            logger.info("Registered plugin-messaging channel "
                    + com.nova.chat.channel.ProxyChannelProtocol.CHANNEL_ID);
        } catch (RuntimeException e) {
            channelIdentifier = null;
            logger.warn("Failed to register the plugin-messaging channel", e);
        }
    }

    /**
     * Registers event listeners.
     */
    private void registerListeners() {
        // Register chat listener for handling chat events
        chatListener = new ChatListener(this);
        server.getEventManager().register(this, chatListener);
        
        // Initialize mention Tab completer (Requirements: 11.3)
        mentionTabCompleter = new MentionTabCompleter(this);
        server.getEventManager().register(this, mentionTabCompleter);
        
        // Register server switch handler for cross-server routing (Requirements: 4.3, 5.3)
        serverSwitchHandler = new ServerSwitchHandler(this);
        server.getEventManager().register(this, serverSwitchHandler);

        // Register locale capture listener for per-player i18n (Architecture B)
        localeCaptureListener = new LocaleCaptureListener(this);
        server.getEventManager().register(this, localeCaptureListener);

        // Plugin messaging: downstream servers -> proxy (and the downstream
        // fan-out results). Registered after the shared cores so the handler
        // exists by the time the first payload can arrive.
        pluginMessageListener = new PluginMessageListener(this);
        server.getEventManager().register(this, pluginMessageListener);
    }
    
    /**
     * Builds {@link ChannelCommandService} with a PacketSender that delegates to
     * the live {@link NetworkClient}. Send is accepted only when authenticated.
     */
    private void initializeChannelCommandService() {
        channelCommandService = ChannelCommandService.forPlatform(
                this::getPlatformNetworkClient, PlatformType.VELOCITY);
    }

    /**
     * Registers plugin commands.
     */
    private void registerCommands() {
        NovaChatCommand command = new NovaChatCommand(this);
        
        CommandMeta meta = server.getCommandManager().metaBuilder("novachat")
            .aliases("nc")
            .plugin(this)
            .build();
        
        server.getCommandManager().register(meta, command);
    }
    
    /**
     * Reloads the plugin configuration and reconnects to backend if needed.
     *
     * <p>Changing {@code mode} requires a restart: lite and relay build a
     * different object graph (no NetworkClient, no ConfigSync registration,
     * different channel roster source), and rebuilding it under live players is
     * out of scope. A reload whose new file changes the mode is rejected and the
     * running snapshot stays untouched.
     */
    public void reload() {
        ProxyMode runningMode = config != null ? config.getMode() : null;

        NovaChatConfig loaded;
        try {
            loaded = new NovaChatConfig(dataDirectory);
        } catch (Exception e) {
            logger.error("Configuration reload rejected; continuing with previous values", e);
            return;
        }

        if (runningMode != null && loaded.getMode() != runningMode) {
            logger.error("Configuration reload rejected: mode changed from " + runningMode
                    + " to " + loaded.getMode() + "; restart the proxy to switch modes");
            return;
        }

        applyConfiguration(loaded);

        // Reload chat listener settings
        if (chatListener != null) {
            chatListener.reload();
        }

        // Reconnect if connection settings changed. In lite mode
        // initializeNetworkClient rebinds nothing: it re-seeds the roster from
        // the reloaded lite.channels and keeps the existing local authority
        // client, whose handler registrations must survive the reload.
        if (networkClient != null) {
            networkClient.disconnect();
        }
        initializeNetworkClient();

        // H6: re-bind incoming packet handlers to the new NetworkClient instance.
        // The old handlers were registered against the discarded client and
        // would silently drop all inbound packets after this reload.
        if (chatListener != null) {
            chatListener.rebindIncomingHandlers();
        }

        logger.info("NovaChat configuration reloaded");
    }
    
    /**
     * Logs a debug message if debug mode is enabled.
     * 
     * @param message the message to log
     */
    public void debug(String message) {
        if (debugMode) {
            logger.info("[Debug] " + message);
        }
    }
    
    /**
     * Sets the debug mode.
     * 
     * @param enabled true to enable debug mode
     */
    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
    }
    
    /**
     * Checks if debug mode is enabled.
     * 
     * @return true if debug mode is enabled
     */
    public boolean isDebugMode() {
        return debugMode;
    }
    
    /**
     * Gets the plugin configuration.
     * 
     * @return the configuration
     */
    public NovaChatConfig getConfig() {
        return config;
    }
    
    /**
     * Gets the network client.
     *
     * <p>In lite mode this returns the {@link LocalAuthorityClient} (typed as
     * {@link NetworkClient} because that is the historical return type and the
     * shared cores need the common base class). Run-time callers that need the
     * Netty-specific surface must check the mode first.
     *
     * @return the transport-facing client (relay Netty facade or lite local
     *         authority), or null before startup
     */
    public NetworkClient getNetworkClient() {
        return networkClient;
    }

    /**
     * Gets the client the shared cores must send through, independent of mode.
     *
     * <p>{@link #getNetworkClient()} is the historical Velocity-typed accessor
     * (used by the chat listener and tests); the shared cores only need the
     * {@link AbstractPlatformNetworkClient} base, so lite can supply the local
     * authority client without pretending it is a Netty facade.
     *
     * @return the transport-facing client, or null before startup
     */
    public AbstractPlatformNetworkClient getPlatformNetworkClient() {
        return networkClient != null ? networkClient : localAuthorityClient;
    }

    /**
     * Gets the lite-mode transport stand-in.
     *
     * @return the local authority client, or null in relay mode
     */
    public LocalAuthorityClient getLocalAuthorityClient() {
        return localAuthorityClient;
    }

    /**
     * Drops the player from the local authority's channel table (lite only).
     *
     * <p>Relay orders the backend to evict the player with a logout LEAVE; lite
     * has no backend, so the shell calls this from its disconnect listener.
     * No-op in relay mode and before the authority is bound.
     *
     * @param playerId the disconnecting player
     */
    public void onLitePlayerDisconnect(UUID playerId) {
        LocalAuthority authority = liteAuthority;
        if (authority != null) {
            authority.onPlayerDisconnect(playerId);
        }
    }

    /**
     * Gets the shared known-channel registry (populated from ConfigSync).
     *
     * @return the known-channel registry, never null after initialization
     */
    public com.nova.chat.client.channel.KnownChannelRegistry getKnownChannelRegistry() {
        return knownChannelRegistry;
    }
    
    /**
     * Gets the chat listener.
     *
     * @return the chat listener
     */
    public ChatListener getChatListener() {
        return chatListener;
    }

    /**
     * Gets the shared channel command service.
     *
     * @return the channel command service
     */
    public ChannelCommandService getChannelCommandService() {
        return channelCommandService;
    }

    /**
     * Gets the shared per-player ignore list service.
     *
     * @return the ignore list service
     */
    public com.nova.chat.client.ignore.IgnoreListService getIgnoreListService() {
        return ignoreListService;
    }

    /**
     * Gets the shared private-message service (/nc msg, /nc r).
     *
     * @return the private message service
     */
    public com.nova.chat.client.privatemsg.PrivateMessageService getPrivateMessageService() {
        return privateMessageService;
    }
    
    /**
     * Gets the shared proxy chat core.
     *
     * @return the chat core, never null after initialization
     */
    public ProxyChatCore getChatCore() {
        return chatCore;
    }

    /**
     * Gets the shared proxy command core.
     *
     * @return the command core, never null after initialization
     */
    public ProxyCommandCore getCommandCore() {
        return commandCore;
    }

    /**
     * Gets the shared server-switch core.
     *
     * @return the server-switch core, never null after initialization
     */
    public ProxyServerSwitchCore getServerSwitchCore() {
        return serverSwitchCore;
    }

    /**
     * Gets the proxy half of the plugin-messaging channel.
     *
     * @return the channel handler, or null before startup / after shutdown
     */
    public com.nova.chat.proxy.channel.ProxyChannelHandler getProxyChannelHandler() {
        return proxyChannelHandler;
    }

    /**
     * Gets the plugin-message listener.
     *
     * @return the listener, or null before startup / after shutdown
     */
    public PluginMessageListener getPluginMessageListener() {
        return pluginMessageListener;
    }

    /**
     * Gets the Velocity platform adapter.
     *
     * @return the platform adapter, never null after initialization
     */
    public VelocityProxyPlatform getProxyPlatform() {
        return platform;
    }

    /**
     * Gets the message formatter shell.
     *
     * @return the message formatter
     */
    public MessageFormatter getMessageFormatter() {
        return messageFormatter;
    }

    /**
     * Gets the mention Tab completer.
     * 
     * @return the mention Tab completer
     */
    public MentionTabCompleter getMentionTabCompleter() {
        return mentionTabCompleter;
    }
    
    /**
     * Gets the server switch handler.
     * 
     * @return the server switch handler
     */
    public ServerSwitchHandler getServerSwitchHandler() {
        return serverSwitchHandler;
    }
    
    /**
     * Gets the proxy server.
     * 
     * @return the proxy server
     */
    public ProxyServer getServer() {
        return server;
    }
    
    /**
     * Gets the logger.
     * 
     * @return the logger
     */
    public Logger getLogger() {
        return logger;
    }
    
    /**
     * Gets the data directory.
     * 
     * @return the data directory
     */
    public Path getDataDirectory() {
        return dataDirectory;
    }
    
    /**
     * Gets the plugin instance.
     * 
     * @return the plugin instance
     */
    public static NovaChatVelocity getInstance() {
        return instance;
    }
}
