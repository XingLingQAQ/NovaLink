package com.nova.chat.bukkit;

import com.nova.chat.bukkit.api.NovaChatAPI;
import com.nova.chat.bukkit.chat.ChatInterceptor;
import com.nova.chat.bukkit.chat.MentionTabCompleter;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.bukkit.command.NovaChatCommand;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.bukkit.error.ErrorMessageHandler;
import com.nova.chat.bukkit.i18n.LocaleListener;
import com.nova.chat.bukkit.network.NetworkClient;
import com.nova.chat.bukkit.welcome.WelcomeListener;
import com.nova.chat.bukkit.world.WorldMonitor;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.common.protocol.PlatformType;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * NovaChat Bukkit Plugin - Main class
 * 
 * This plugin provides chat channel functionality for Bukkit/Spigot/Paper servers,
 * connecting to the NovaLink backend for cross-server communication.
 * 
 * Supports: Bukkit/Spigot/Paper servers (Requirements: 23.1)
 */
public class NovaChatBukkit extends JavaPlugin {
    
    private static NovaChatBukkit instance;
    
    /** Plugin configuration */
    private NovaChatConfig novaChatConfig;
    
    /** Network client for backend connection */
    private NetworkClient networkClient;
    
    /** Chat interceptor for handling chat events */
    private ChatInterceptor chatInterceptor;
    
    /** World monitor for auto-routing on world change */
    private WorldMonitor worldMonitor;
    
    /** Mention Tab completer for @mentions in chat */
    private MentionTabCompleter mentionTabCompleter;
    
    /** Command handler */
    private NovaChatCommand commandHandler;
    
    /** Message helper for formatting messages */
    private MessageHelper messageHelper;
    
    /** Error message handler for displaying errors */
    private ErrorMessageHandler errorHandler;
    
    /** Public API for other plugins */
    private NovaChatAPI api;

    /**
     * Shared known-channel registry populated from backend ConfigSync pushes
     * (UX-DESIGN §2.1). Filled by {@link NetworkClient#handleConfigSync} and
     * consumed by {@code /nc list} and {@code /nc join <Tab>}.
     */
    private com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry;

    /**
     * Shared channel command intents (join/leave/toggle/reload).
     * PacketSender resolves {@link #networkClient} on each send so reload/reconnect
     * does not leave a stale client reference.
     */
    private ChannelCommandService channelCommandService;

    /** Debug mode flag */
    private boolean debugMode = false;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // Save default config if not exists
        saveDefaultConfig();
        
        // Load configuration
        loadConfiguration();

        // Initialize the shared i18n default locale from chat.locale (zh_CN fallback).
        I18n.setDefaultLocale(LocaleResolver.parseOrDefault(
                novaChatConfig.getLocale(), LocaleResolver.ROOT_LOCALE));

        // Initialize message helper and error handler
        initializeMessageHandlers();
        
        // Initialize API (Requirements: 25.1-25.3)
        initializeAPI();
        
        // Initialize network client
        initializeNetworkClient();

        // Shared command intent service (Architecture B client-core)
        initializeChannelCommandService();

        // Register event listeners
        registerListeners();
        
        // Register commands
        registerCommands();
        
        getLogger().info("NovaChat Bukkit plugin enabled!");
    }
    
    @Override
    public void onDisable() {
        getLogger().info("NovaChat Bukkit plugin disabling...");
        
        // Disconnect from backend
        if (networkClient != null) {
            networkClient.disconnect();
            networkClient = null;
        }
        
        // Clear API instance
        NovaChatAPI.clearInstance();
        api = null;
        
        instance = null;
        getLogger().info("NovaChat Bukkit plugin disabled!");
    }
    
    /**
     * Loads or reloads the plugin configuration.
     */
    public void loadConfiguration() {
        reloadConfig();
        novaChatConfig = new NovaChatConfig(getConfig());
        debugMode = novaChatConfig.isDebug();
        
        if (debugMode) {
            getLogger().info("[Debug] Configuration loaded successfully");
        }
    }
    
    /**
     * Initializes message helper and error handler.
     */
    private void initializeMessageHandlers() {
        messageHelper = new MessageHelper(this);
        errorHandler = new ErrorMessageHandler(this, messageHelper);
    }
    
    /**
     * Initializes the public API.
     * Requirements: 25.1-25.3
     */
    private void initializeAPI() {
        api = new NovaChatAPI(this);
        getLogger().info("NovaChat API initialized");
    }
    
    /**
     * Initializes the network client and connects to the backend.
     */
    private void initializeNetworkClient() {
        if (novaChatConfig == null) {
            getLogger().severe("Cannot initialize network client: configuration not loaded");
            return;
        }

        knownChannelRegistry = new com.nova.chat.client.channel.KnownChannelRegistry();
        networkClient = new NetworkClient(this, novaChatConfig, knownChannelRegistry);
        
        // Connect asynchronously
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            networkClient.connect(
                novaChatConfig.getBackendHost(),
                novaChatConfig.getBackendPort()
            ).thenAccept(success -> {
                if (success) {
                    getLogger().info("Connected to NovaLink backend at " + 
                        novaChatConfig.getBackendHost() + ":" + novaChatConfig.getBackendPort());
                } else {
                    getLogger().warning("Failed to connect to NovaLink backend. Will retry...");
                }
            });
        });
    }
    
    /**
     * Builds {@link ChannelCommandService} with a PacketSender that delegates to
     * the live {@link NetworkClient}. Send is accepted only when the client is
     * connected and authenticated.
     *
     * <p>The pending-request tracker lives inside {@link NetworkClient#sendPacket}
     * (it calls {@code trackPendingRequest} for every {@code ChannelActionPacket}
     * before delegating to the core), so channel-action packets sent via the
     * service are correlated automatically — no command-side tracker code needed.
     */
    private void initializeChannelCommandService() {
        channelCommandService = new ChannelCommandService(packet -> {
            NetworkClient client = networkClient;
            if (client == null || !client.isConnected() || !client.isAuthenticated()) {
                return false;
            }
            client.sendPacket(packet);
            return true;
        }, PlatformType.BUKKIT.name());
    }

    /**
     * Registers event listeners.
     */
    private void registerListeners() {
        // Register chat interceptor
        chatInterceptor = new ChatInterceptor(this);
        getServer().getPluginManager().registerEvents(chatInterceptor, this);
        
        // Register world monitor for auto-routing (Requirements: 6.2, 6.3, 9.1-9.4)
        worldMonitor = new WorldMonitor(this);
        getServer().getPluginManager().registerEvents(worldMonitor, this);
        
        // Register mention Tab completer (Requirements: 11.3)
        mentionTabCompleter = new MentionTabCompleter(this);
        getServer().getPluginManager().registerEvents(mentionTabCompleter, this);

        // Register first-join welcome listener (UX-DESIGN §8.1)
        getServer().getPluginManager().registerEvents(new WelcomeListener(this), this);

        // Register per-player locale capture (i18n) — client locale drives
        // player-facing text resolution via I18n.tr(playerId, ...).
        getServer().getPluginManager().registerEvents(new LocaleListener(), this);
    }
    
    /**
     * Registers plugin commands.
     */
    private void registerCommands() {
        commandHandler = new NovaChatCommand(this);
        
        PluginCommand novachatCmd = getCommand("novachat");
        if (novachatCmd != null) {
            novachatCmd.setExecutor(commandHandler);
            novachatCmd.setTabCompleter(commandHandler);
        }
    }
    
    /**
     * Gets the command handler.
     *
     * @return the command handler
     */
    public NovaChatCommand getCommandHandler() {
        return commandHandler;
    }
    
    /**
     * Reloads the plugin configuration and reconnects to backend if needed.
     */
    public void reload() {
        loadConfiguration();

        // Re-apply the configured default locale so a /nc reload picks up locale changes.
        I18n.setDefaultLocale(LocaleResolver.parseOrDefault(
                novaChatConfig.getLocale(), LocaleResolver.ROOT_LOCALE));

        // Reload chat interceptor settings
        if (chatInterceptor != null) {
            chatInterceptor.reload();
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
     * Logs a debug message with exception if debug mode is enabled.
     * 
     * @param message the message to log
     * @param throwable the exception to log
     */
    public void debug(String message, Throwable throwable) {
        if (debugMode) {
            getLogger().log(Level.INFO, "[Debug] " + message, throwable);
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
    public NovaChatConfig getNovaChatConfig() {
        return novaChatConfig;
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
     * Gets the shared channel command service (join/leave/toggle/reload intents).
     *
     * @return the channel command service
     */
    public ChannelCommandService getChannelCommandService() {
        return channelCommandService;
    }
    
    /**
     * Gets the chat interceptor.
     * 
     * @return the chat interceptor
     */
    public ChatInterceptor getChatInterceptor() {
        return chatInterceptor;
    }
    
    /**
     * Gets the world monitor.
     * 
     * @return the world monitor
     */
    public WorldMonitor getWorldMonitor() {
        return worldMonitor;
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
     * Gets the message helper.
     * 
     * @return the message helper
     */
    public MessageHelper getMessageHelper() {
        return messageHelper;
    }
    
    /**
     * Gets the error message handler.
     * 
     * @return the error handler
     */
    public ErrorMessageHandler getErrorHandler() {
        return errorHandler;
    }
    
    /**
     * Gets the public API.
     * 
     * @return the API instance
     */
    public NovaChatAPI getAPI() {
        return api;
    }
    
    /**
     * Gets the plugin instance.
     * 
     * @return the plugin instance
     */
    public static NovaChatBukkit getInstance() {
        return instance;
    }
}
