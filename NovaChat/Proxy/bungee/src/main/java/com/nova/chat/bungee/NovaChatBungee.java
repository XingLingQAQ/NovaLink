package com.nova.chat.bungee;

import com.nova.chat.bungee.chat.ChatListener;
import com.nova.chat.bungee.chat.MentionTabCompleter;
import com.nova.chat.bungee.command.NovaChatCommand;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.bungee.listener.LocaleCaptureListener;
import com.nova.chat.bungee.listener.PluginMessageListener;
import com.nova.chat.bungee.listener.ServerSwitchHandler;
import com.nova.chat.bungee.network.NetworkClient;
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
import com.nova.chat.bungee.chat.MessageFormatter;
import com.nova.chat.bungee.platform.BungeeProxyPlatform;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * NovaChat BungeeCord Plugin - Main class
 * 
 * This plugin provides chat channel functionality for BungeeCord proxy servers,
 * connecting to the NovaLink backend for cross-server communication.
 * 
 * Supports: BungeeCord proxy servers (Requirements: 23.3)
 * 
 * Key features:
 * - Cross-server message routing through NovaLink backend
 * - Server switch handling for proper channel management
 * - Similar to Velocity but with BungeeCord API
 */
public class NovaChatBungee extends Plugin {
    
    private static NovaChatBungee instance;
    
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
     * Per-player ignore lists (/nc ignore). Persisted to
     * {@code ignore-lists.json} in the plugin data folder.
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
     * Proxy half of the sub-server plugin-messaging channel: lite routes through
     * the authority, relay through the backend link.
     */
    private com.nova.chat.proxy.channel.ProxyChannelHandler proxyChannelHandler;

    /** Listener for {@code novachat:main} plugin messages (registered on enable). */
    private PluginMessageListener pluginMessageListener;

    /** Whether {@code novachat:main} is currently registered with the proxy. */
    private boolean pluginChannelRegistered;

    /** BungeeCord platform adapter (md_5 rendering, scheduler, logger). */
    private BungeeProxyPlatform platform;

    /** BungeeCord-side message formatter shell (renders shared strings). */
    private MessageFormatter messageFormatter;

    /** Debug mode flag */
    private boolean debugMode = false;
    
    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("NovaChat BungeeCord plugin initializing...");

        // Extract default lang/ bundles to plugins/NovaChat/lang/ (template for
        // user edits / drop-in new languages). I18n reads external overrides on
        // top of the classpath bundles (external wins per-key).
        File langDir = new File(getDataFolder(), "lang");
        I18n.setExternalLangDir(getDataFolder());
        extractDefaultLang(langDir, "zh_CN");
        extractDefaultLang(langDir, "en_US");

        // Load the resource-backed configuration before initializing services.
        if (!loadConfiguration()) {
            getLogger().severe("NovaChat initialization stopped because config.yml could not be loaded safely");
            return;
        }

        // Drop the cached bundles so a reload re-reads external overrides.
        I18n.invalidate();

        // Per-player ignore lists, persisted under the plugin data folder
        // (mirrors the I18n.setExternalLangDir injection precedent).
        ignoreListService = new com.nova.chat.client.ignore.IgnoreListService();
        ignoreListService.setDataDirectory(getDataFolder().toPath());

        // Shared private-message core (/nc msg, /nc r).
        privateMessageService = new com.nova.chat.client.privatemsg.PrivateMessageService();
        
        // Initialize network client
        initializeNetworkClient();
        
        // Shared command intent service (Architecture B client-core)
        initializeChannelCommandService();

        // Shared proxy cores (chat / command / server switch) built on the
        // BungeeCord platform adapter. Both proxies share this layer.
        initializeProxyCores();

        // Accept novachat:main from downstream servers (plugin messaging).
        initializePluginChannel();

        // Register event listeners
        registerListeners();

        // Register commands
        registerCommands();

        getLogger().info("NovaChat BungeeCord plugin enabled!");
    }
    
    @Override
    public void onDisable() {
        getLogger().info("NovaChat BungeeCord plugin disabling...");

        if (pluginChannelRegistered) {
            try {
                getProxy().unregisterChannel(
                        com.nova.chat.channel.ProxyChannelProtocol.CHANNEL_ID);
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING,
                        "Failed to unregister the plugin-messaging channel", e);
            }
            pluginChannelRegistered = false;
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

        // Flush pending ignore-list writes to disk
        if (ignoreListService != null) {
            ignoreListService.close();
            ignoreListService = null;
        }
        
        instance = null;
        getLogger().info("NovaChat BungeeCord plugin disabled!");
    }
    
    /**
     * Extracts a default lang bundle from the jar to {@code <langDir>/<locale>.properties}
     * only when it does not already exist (so user customizations win). The
     * classpath resource path is {@code lang/<locale>.properties}. Errors are
     * logged but never fatal.
     */
    private void extractDefaultLang(File langDir, String locale) {
        if (langDir == null || locale == null) {
            return;
        }
        File target = new File(langDir, locale + ".properties");
        if (target.isFile()) {
            return;
        }
        String resourcePath = "lang/" + locale + ".properties";
        try (InputStream in = getResourceAsStream(resourcePath)) {
            if (in == null) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            langDir.mkdirs();
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            getLogger().warning("Failed to extract default lang bundle " + locale + ": " + e.getMessage());
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
            NovaChatConfig loaded = new NovaChatConfig(getDataFolder());
            applyConfiguration(loaded);
            return true;
        } catch (Exception e) {
            config = previousConfig;
            debugMode = previousDebugMode;
            getLogger().log(Level.SEVERE,
                    "Failed to install, upgrade, or load config.yml; the file was not overwritten", e);
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

        if (loaded.getUpdateResult().created()) {
            getLogger().info("Created config.yml from the bundled template");
        } else if (loaded.getUpdateResult().updated()) {
            getLogger().info("Added new config.yml fields; previous file saved to "
                    + loaded.getUpdateResult().backupPath());
        }

        I18n.setDefaultLocale(LocaleResolver.parseOrDefault(
                loaded.getLocale(), LocaleResolver.ROOT_LOCALE));
        I18n.invalidate();

        if (debugMode) {
            getLogger().info("[Debug] Configuration loaded successfully");
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
            getLogger().severe("Cannot initialize network client: configuration not loaded");
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
        getProxy().getScheduler().runAsync(this, () -> {
            networkClient.connect(
                config.getBackendHost(),
                config.getBackendPort()
            ).thenAccept(success -> {
                if (success) {
                    getLogger().info("Connected to NovaLink backend at " +
                        config.getBackendHost() + ":" + config.getBackendPort());
                } else {
                    getLogger().warning("Failed to connect to NovaLink backend. Will retry...");
                }
            });
        });
    }

    /**
     * Seeds the state that lite mode still needs without a backend.
     *
     * <p>The roster is filled from {@code lite.channels} so {@code /nc list},
     * {@code /nc join} completion and channel-prefix routing see the locally
     * declared channels. No {@link NetworkClient} is created: lite has no
     * backend to talk to.
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
                platform = new BungeeProxyPlatform(this);
            }
            localAuthorityClient = LocalAuthorityClient.create(platform);
        }
        getLogger().info("NovaChat running in lite mode (local authority, no backend connection); "
                + config.getLiteChannels().size() + " local channel(s) declared");
    }

    /**
     * Builds the shared proxy cores (chat / command / server switch).
     *
     * <p>These are platform-agnostic classes from {@code :NovaChat:Proxy:proxy-common};
     * this method only supplies the BungeeCord adapter plus the shared
     * client-core services.
     */
    private void initializeProxyCores() {
        if (platform == null) {
            platform = new BungeeProxyPlatform(this);
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
                this::getPluginConfig);
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
     * Registers the {@code novachat:main} channel with BungeeCord.
     *
     * <p>Registration is what makes the proxy fire {@code PluginMessageEvent}
     * for this channel; without it server-originated payloads are forwarded to
     * the player's client (or dropped) instead of reaching the handler.
     */
    private void initializePluginChannel() {
        if (pluginChannelRegistered) {
            return;
        }
        try {
            getProxy().registerChannel(com.nova.chat.channel.ProxyChannelProtocol.CHANNEL_ID);
            pluginChannelRegistered = true;
            getLogger().info("Registered plugin-messaging channel "
                    + com.nova.chat.channel.ProxyChannelProtocol.CHANNEL_ID);
        } catch (RuntimeException e) {
            pluginChannelRegistered = false;
            getLogger().log(Level.WARNING,
                    "Failed to register the plugin-messaging channel", e);
        }
    }

    /**
     * Registers event listeners.
     */
    private void registerListeners() {
        // Register chat listener for handling chat events
        chatListener = new ChatListener(this);
        getProxy().getPluginManager().registerListener(this, chatListener);
        
        // Register mention Tab completer (Requirements: 11.3)
        mentionTabCompleter = new MentionTabCompleter(this);
        getProxy().getPluginManager().registerListener(this, mentionTabCompleter);
        
        // Register server switch handler for cross-server routing (Requirements: 4.3, 5.3)
        serverSwitchHandler = new ServerSwitchHandler(this);
        getProxy().getPluginManager().registerListener(this, serverSwitchHandler);

        // Register locale capture listener for per-player i18n (Architecture B)
        localeCaptureListener = new LocaleCaptureListener(this);
        getProxy().getPluginManager().registerListener(this, localeCaptureListener);

        // Plugin messaging: downstream servers -> proxy (and the downstream
        // fan-out results). Registered after the shared cores so the handler
        // exists by the time the first payload can arrive.
        pluginMessageListener = new PluginMessageListener(this);
        getProxy().getPluginManager().registerListener(this, pluginMessageListener);
    }
    
    /**
     * Builds {@link ChannelCommandService} with a PacketSender that delegates to
     * the live {@link NetworkClient}. Send is accepted only when authenticated.
     */
    private void initializeChannelCommandService() {
        channelCommandService = ChannelCommandService.forPlatform(
                this::getPlatformNetworkClient, PlatformType.BUNGEECORD);
    }

    /**
     * Registers plugin commands.
     */
    private void registerCommands() {
        NovaChatCommand command = new NovaChatCommand(this);
        getProxy().getPluginManager().registerCommand(this, command);
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
            loaded = new NovaChatConfig(getDataFolder());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE,
                    "Configuration reload rejected; continuing with previous values", e);
            return;
        }

        if (runningMode != null && loaded.getMode() != runningMode) {
            getLogger().severe("Configuration reload rejected: mode changed from " + runningMode
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

        getLogger().info("NovaChat configuration reloaded");
    }
    
    /**
     * Logs a debug message if debug mode is enabled.
     * 
     * @param message the message to log
     */
    public void debug(String message) {
        if (debugMode) {
            getLogger().info("[Debug] " + message);
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
    public NovaChatConfig getPluginConfig() {
        return config;
    }
    
    /**
     * Gets the network client.
     *
     * @return the relay Netty facade, or null in lite mode
     */
    public NetworkClient getNetworkClient() {
        return networkClient;
    }

    /**
     * Gets the client the shared cores must send through, independent of mode.
     *
     * <p>{@link #getNetworkClient()} is the historical BungeeCord-typed
     * accessor (used by the chat listener and tests); the shared cores only
     * need the {@link AbstractPlatformNetworkClient} base, so lite can supply
     * the local authority client without pretending it is a Netty facade.
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
    public void onLitePlayerDisconnect(java.util.UUID playerId) {
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
     * Gets the per-player ignore list service (/nc ignore).
     *
     * @return the ignore list service, never null after initialization
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
     * Gets the BungeeCord platform adapter.
     *
     * @return the platform adapter, never null after initialization
     */
    public BungeeProxyPlatform getProxyPlatform() {
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
     * Gets the plugin instance.
     * 
     * @return the plugin instance
     */
    public static NovaChatBungee getInstance() {
        return instance;
    }
}
