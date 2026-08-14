package com.nova.chat.bungee;

import com.nova.chat.bungee.chat.ChatListener;
import com.nova.chat.bungee.chat.MentionTabCompleter;
import com.nova.chat.bungee.command.NovaChatCommand;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.bungee.listener.LocaleCaptureListener;
import com.nova.chat.bungee.listener.ServerSwitchHandler;
import com.nova.chat.bungee.network.NetworkClient;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

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

        // Load configuration
        loadConfiguration();

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

        // Register event listeners
        registerListeners();

        // Register commands
        registerCommands();

        getLogger().info("NovaChat BungeeCord plugin enabled!");
    }
    
    @Override
    public void onDisable() {
        getLogger().info("NovaChat BungeeCord plugin disabling...");
        
        // Disconnect from backend
        if (networkClient != null) {
            networkClient.disconnect();
            networkClient = null;
        }

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
     */
    public void loadConfiguration() {
        config = new NovaChatConfig(getDataFolder());
        debugMode = config.isDebug();

        // Apply the configured default locale to the shared i18n service before
        // any player-facing text is rendered. Falls back to zh_CN (ROOT_LOCALE)
        // when the configured value is blank or unparseable.
        I18n.setDefaultLocale(LocaleResolver.parseOrDefault(config.getLocale(), LocaleResolver.ROOT_LOCALE));
        // Re-read external lang overrides so edited/added lang/*.properties take effect.
        I18n.invalidate();

        if (debugMode) {
            getLogger().info("[Debug] Configuration loaded successfully");
        }
    }

    /**
     * Initializes the network client and connects to the backend.
     */
    private void initializeNetworkClient() {
        if (config == null) {
            getLogger().severe("Cannot initialize network client: configuration not loaded");
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
                config != null ? config.getUsername() : null);
        
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
    }
    
    /**
     * Builds {@link ChannelCommandService} with a PacketSender that delegates to
     * the live {@link NetworkClient}. Send is accepted only when authenticated.
     */
    private void initializeChannelCommandService() {
        channelCommandService = ChannelCommandService.forPlatform(
                () -> networkClient, PlatformType.BUNGEECORD);
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
     */
    public void reload() {
        loadConfiguration();
        
        // Reload chat listener settings
        if (chatListener != null) {
            chatListener.reload();
        }
        
        // Reconnect if connection settings changed
        if (networkClient != null) {
            networkClient.disconnect();
        }
        initializeNetworkClient();
        
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
     * @return the network client
     */
    public NetworkClient getNetworkClient() {
        return networkClient;
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
