package com.nova.chat.folia;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.folia.chat.AsyncChatInterceptor;
import com.nova.chat.folia.chat.MentionTabCompleter;
import com.nova.chat.folia.command.MessageHelper;
import com.nova.chat.folia.command.NovaChatCommand;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.network.AsyncNetworkClient;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * NovaChat Folia Plugin - Main class
 * 
 * This plugin provides chat channel functionality for Folia servers,
 * connecting to the NovaLink backend for cross-server communication.
 * Uses Folia's regionized scheduler for thread-safe operations.
 * 
 * Requirements: 2.1, 2.2
 */
public class NovaChatFolia extends JavaPlugin {
    
    private static NovaChatFolia instance;
    
    /** Plugin configuration */
    private NovaChatConfig novaChatConfig;
    
    /** Folia scheduler adapter */
    private FoliaSchedulerAdapter scheduler;
    
    /** Async network client for backend connection */
    private AsyncNetworkClient networkClient;
    
    /** Async chat interceptor for handling chat events */
    private AsyncChatInterceptor chatInterceptor;
    
    /** Mention Tab completer for @mentions in chat */
    private MentionTabCompleter mentionTabCompleter;
    
    /** Message helper for command formatting */
    private MessageHelper messageHelper;

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
        
        // Initialize message helper
        messageHelper = new MessageHelper(this);
        
        // Initialize Folia scheduler adapter
        scheduler = new FoliaSchedulerAdapter(this);
        
        if (scheduler.isFolia()) {
            getLogger().info("Folia detected! Using regionized scheduler.");
        } else {
            getLogger().info("Running in Paper compatibility mode (Folia not detected).");
        }
        
        // Initialize network client
        initializeNetworkClient();

        // Shared command intent service (Architecture B client-core)
        initializeChannelCommandService();

        // Register event listeners
        registerListeners();
        
        // Register commands
        registerCommands();
        
        getLogger().info("NovaChat Folia plugin enabled!");
    }
    
    @Override
    public void onDisable() {
        getLogger().info("NovaChat Folia plugin disabling...");
        
        // Disconnect from backend
        if (networkClient != null) {
            networkClient.disconnect();
            networkClient = null;
        }
        
        instance = null;
        getLogger().info("NovaChat Folia plugin disabled!");
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
     * Initializes the network client and connects to the backend.
     */
    private void initializeNetworkClient() {
        if (novaChatConfig == null) {
            getLogger().severe("Cannot initialize network client: configuration not loaded");
            return;
        }
        
        networkClient = new AsyncNetworkClient(this, novaChatConfig, scheduler);
        
        // Connect asynchronously using Folia scheduler
        scheduler.runAsync(() -> {
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
     * the live {@link AsyncNetworkClient}. Send is accepted only when
     * authenticated. The lambda resolves {@link #networkClient} on each send so
     * a reload/reconnect never targets a stale client reference.
     *
     * <p>Thread safety: command execution happens on a Folia region thread, but
     * {@link AsyncNetworkClient#sendPacket} is thread-safe (Netty), so the lambda
     * does not add any extra thread hop.
     */
    private void initializeChannelCommandService() {
        channelCommandService = new ChannelCommandService(packet -> {
            AsyncNetworkClient client = networkClient;
            if (client == null || !client.isAuthenticated()) {
                return false;
            }
            client.sendPacket(packet);
            return true;
        });
    }

    /**
     * Registers event listeners.
     */
    private void registerListeners() {
        // Register async chat interceptor (Requirements: 2.3, 2.4)
        chatInterceptor = new AsyncChatInterceptor(this);
        getServer().getPluginManager().registerEvents(chatInterceptor, this);
        
        // Register mention Tab completer (Requirements: 11.3)
        mentionTabCompleter = new MentionTabCompleter(this);
        getServer().getPluginManager().registerEvents(mentionTabCompleter, this);
    }
    
    /**
     * Registers plugin commands.
     * 
     * Requirements: 2.1
     */
    private void registerCommands() {
        NovaChatCommand commandHandler = new NovaChatCommand(this);
        
        PluginCommand ncCommand = getCommand("nc");
        if (ncCommand != null) {
            ncCommand.setExecutor(commandHandler);
            ncCommand.setTabCompleter(commandHandler);
            debug("Registered /nc command");
        } else {
            getLogger().warning("Failed to register /nc command - not defined in plugin.yml");
        }
        
        PluginCommand novachatCommand = getCommand("novachat");
        if (novachatCommand != null) {
            novachatCommand.setExecutor(commandHandler);
            novachatCommand.setTabCompleter(commandHandler);
            debug("Registered /novachat command");
        }
    }
    
    /**
     * Reloads the plugin configuration and reconnects to backend if needed.
     */
    public void reload() {
        loadConfiguration();
        
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
     * Gets the Folia scheduler adapter.
     * 
     * @return the scheduler adapter
     */
    public FoliaSchedulerAdapter getScheduler() {
        return scheduler;
    }
    
    /**
     * Gets the network client.
     *
     * @return the network client
     */
    public AsyncNetworkClient getNetworkClient() {
        return networkClient;
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
     * Gets the chat interceptor.
     * 
     * @return the chat interceptor
     */
    public AsyncChatInterceptor getChatInterceptor() {
        return chatInterceptor;
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
     * Gets the plugin instance.
     * 
     * @return the plugin instance
     */
    public static NovaChatFolia getInstance() {
        return instance;
    }
}
