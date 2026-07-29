package com.nova.chat.multipaper;

import com.nova.chat.multipaper.chat.ChatInterceptor;
import com.nova.chat.multipaper.chat.MentionTabCompleter;
import com.nova.chat.multipaper.command.MessageHelper;
import com.nova.chat.multipaper.command.NovaChatCommand;
import com.nova.chat.multipaper.config.NovaChatConfig;
import com.nova.chat.multipaper.network.NetworkClient;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * NovaChat MultiPaper Plugin - Main class
 * 
 * This plugin provides chat channel functionality for MultiPaper servers,
 * connecting to the NovaLink backend for cross-server communication.
 * Supports cross-instance state synchronization for MultiPaper environments.
 * 
 * Requirements: 1.1, 1.3
 */
public class NovaChatMultiPaper extends JavaPlugin {
    
    private static NovaChatMultiPaper instance;
    
    /** Plugin configuration */
    private NovaChatConfig novaChatConfig;
    
    /** Network client for backend connection */
    private NetworkClient networkClient;
    
    /** Chat interceptor for handling chat events */
    private ChatInterceptor chatInterceptor;
    
    /** MultiPaper adapter for environment detection and state sync */
    private MultiPaperAdapter multiPaperAdapter;
    
    /** Mention Tab completer for @mentions in chat */
    private MentionTabCompleter mentionTabCompleter;
    
    /** Command handler */
    private NovaChatCommand commandHandler;
    
    /** Message helper for formatting messages */
    private MessageHelper messageHelper;
    
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

        // Initialize MultiPaper adapter (Requirements: 1.2)
        initializeMultiPaperAdapter();
        
        // Initialize network client
        initializeNetworkClient();
        
        // Register event listeners
        registerListeners();
        
        // Register commands
        registerCommands();
        
        getLogger().info("NovaChat MultiPaper plugin enabled!");
        if (multiPaperAdapter.isMultiPaper()) {
            getLogger().info("MultiPaper environment detected, instance ID: " + multiPaperAdapter.getInstanceId());
        } else {
            getLogger().info("Running in standard Paper mode (MultiPaper not detected)");
        }
    }
    
    @Override
    public void onDisable() {
        getLogger().info("NovaChat MultiPaper plugin disabling...");
        
        // Disconnect from backend
        if (networkClient != null) {
            networkClient.disconnect();
            networkClient = null;
        }
        
        instance = null;
        getLogger().info("NovaChat MultiPaper plugin disabled!");
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
     * Initializes the MultiPaper adapter.
     * Requirements: 1.2
     */
    private void initializeMultiPaperAdapter() {
        multiPaperAdapter = new MultiPaperAdapter(this, novaChatConfig);
        
        if (debugMode) {
            getLogger().info("[Debug] MultiPaper adapter initialized");
            getLogger().info("[Debug] Is MultiPaper: " + multiPaperAdapter.isMultiPaper());
            getLogger().info("[Debug] Instance ID: " + multiPaperAdapter.getInstanceId());
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
        
        networkClient = new NetworkClient(this, novaChatConfig);
        
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
     * Registers event listeners.
     */
    private void registerListeners() {
        // Register chat interceptor (Requirements: 1.3, 1.4)
        chatInterceptor = new ChatInterceptor(this);
        getServer().getPluginManager().registerEvents(chatInterceptor, this);
        
        // Register mention Tab completer (Requirements: 11.3)
        mentionTabCompleter = new MentionTabCompleter(this);
        getServer().getPluginManager().registerEvents(mentionTabCompleter, this);
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
        
        // Reload MultiPaper adapter settings
        if (multiPaperAdapter != null) {
            multiPaperAdapter.reload(novaChatConfig);
        }
        
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
     * Gets the chat interceptor.
     * 
     * @return the chat interceptor
     */
    public ChatInterceptor getChatInterceptor() {
        return chatInterceptor;
    }
    
    /**
     * Gets the MultiPaper adapter.
     * 
     * @return the MultiPaper adapter
     */
    public MultiPaperAdapter getMultiPaperAdapter() {
        return multiPaperAdapter;
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
    public static NovaChatMultiPaper getInstance() {
        return instance;
    }
}
