package com.nova.chat.pnx;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.AsyncTask;
import com.nova.chat.common.extension.ExtensionManager;
import com.nova.chat.pnx.chat.ChatInterceptor;
import com.nova.chat.pnx.chat.MentionTabCompleter;
import com.nova.chat.pnx.chat.MessageFormatter;
import com.nova.chat.pnx.command.NovaChatCommand;
import com.nova.chat.pnx.config.NovaChatConfig;
import com.nova.chat.pnx.form.ChannelFormManager;
import com.nova.chat.pnx.network.NetworkClient;
import com.nova.chat.pnx.world.WorldMonitor;
import lombok.Getter;

import java.io.File;
import java.nio.file.Path;

/**
 * NovaChat plugin for PowerNukkitX Bedrock servers.
 * Provides cross-server chat functionality using the NovaProtocol.
 * 
 * This plugin reuses the novachat-common module for protocol implementation,
 * ensuring compatibility with all other NovaChat clients.
 * 
 * Requirements: 28.1, 28.2, 28.3, 28.5
 */
public class NovaChatPNX extends PluginBase implements Listener {

    @Getter
    private static NovaChatPNX instance;

    /** Plugin configuration manager */
    @Getter
    private NovaChatConfig novaChatConfig;

    /** Network client for backend connection */
    @Getter
    private NetworkClient networkClient;

    /** Chat interceptor for handling chat events */
    @Getter
    private ChatInterceptor chatInterceptor;

    /** Mention Tab completer for @mentions in chat */
    @Getter
    private MentionTabCompleter mentionTabCompleter;

    /** Message formatter for rendering messages */
    @Getter
    private MessageFormatter messageFormatter;

    /** World monitor for auto-routing */
    @Getter
    private WorldMonitor worldMonitor;

    /** Channel form manager for Bedrock Form UI (Requirements: 28.8) */
    @Getter
    private ChannelFormManager channelFormManager;
    
    /** Extension manager for custom extensions */
    @Getter
    private ExtensionManager extensionManager;

    /** Debug mode flag */
    private boolean debugMode = false;

    @Override
    public void onLoad() {
        instance = this;
        getLogger().info("NovaChat-PNX loading...");
    }

    @Override
    public void onEnable() {
        // Save default config if not exists
        saveDefaultConfigFile();
        
        // Load configuration
        novaChatConfig = new NovaChatConfig(this);
        novaChatConfig.load();
        debugMode = novaChatConfig.isDebug();
        
        // Initialize message formatter
        messageFormatter = new MessageFormatter(this);
        
        // Initialize network client (reuses novachat-common protocol)
        initializeNetworkClient();
        
        // Initialize chat interceptor
        chatInterceptor = new ChatInterceptor(this);
        getServer().getPluginManager().registerEvents(chatInterceptor, this);
        
        // Initialize mention Tab completer (Requirements: 11.3)
        mentionTabCompleter = new MentionTabCompleter(this);
        
        // Initialize world monitor if enabled (Requirements: 29.6)
        if (novaChatConfig.isWorldRoutingEnabled()) {
            worldMonitor = new WorldMonitor(this);
            getServer().getPluginManager().registerEvents(worldMonitor, this);
        }
        
        // Initialize channel form manager (Requirements: 28.8)
        channelFormManager = new ChannelFormManager(this);
        
        // Register commands (Requirements: 29.1, 29.2)
        getServer().getCommandMap().register("novachat", new NovaChatCommand(this));
        
        // Register this class for form responses and player quit events
        getServer().getPluginManager().registerEvents(this, this);
        
        // Load and enable extensions
        // Requirements: 10.3 - THE Nukkit/PNX 扩展 SHALL 使用 Java 编写并放置在 extensions 目录
        loadExtensions();
        
        getLogger().info("NovaChat-PNX enabled successfully!");
    }

    @Override
    public void onDisable() {
        getLogger().info("NovaChat-PNX disabling...");
        
        // Disable all extensions first
        if (extensionManager != null) {
            extensionManager.disableAllExtensions();
            extensionManager = null;
        }
        
        // Disconnect from backend
        if (networkClient != null) {
            networkClient.disconnect();
            networkClient = null;
        }
        
        instance = null;
        getLogger().info("NovaChat-PNX disabled.");
    }

    /**
     * Saves the default configuration file if it doesn't exist.
     */
    private void saveDefaultConfigFile() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }
    }

    /**
     * Loads and enables extensions from the extensions directory.
     * 
     * Requirements: 10.3 - THE Nukkit/PNX 扩展 SHALL 使用 Java 编写并放置在 extensions 目录
     * Requirements: 10.4 - WHEN 扩展加载 THEN 各平台扩展加载器 SHALL 调用对应的初始化方法
     */
    private void loadExtensions() {
        Path extensionsDir = new File(getDataFolder(), "extensions").toPath();
        
        extensionManager = new ExtensionManager();
        int loaded = extensionManager.loadExtensions(extensionsDir);
        
        if (loaded > 0) {
            getLogger().info("Found " + loaded + " extension(s)");
            extensionManager.enableAllExtensions();
        }
    }

    /**
     * Initializes the network client and connects to the backend.
     * Uses novachat-common protocol implementation for compatibility.
     */
    private void initializeNetworkClient() {
        if (novaChatConfig == null) {
            getLogger().error("Cannot initialize network client: configuration not loaded");
            return;
        }
        
        networkClient = new NetworkClient(this, novaChatConfig);
        
        // Connect asynchronously to avoid blocking the main thread
        getServer().getScheduler().scheduleAsyncTask(this, new AsyncTask() {
            @Override
            public void onRun() {
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
            }
        });
    }

    /**
     * Handles player quit events to clean up player state.
     *
     * @param event the quit event
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // Clean up player chat state
        if (chatInterceptor != null) {
            chatInterceptor.removeState(player);
        }
        
        // Clean up pending form data
        if (channelFormManager != null) {
            channelFormManager.clearPendingData(player.getUniqueId());
        }
    }

    /**
     * Handles form responses from players.
     * This is used for the Bedrock Form API integration (Requirements: 28.8).
     *
     * @param event the form response event
     */
    @EventHandler
    public void onFormResponse(PlayerFormRespondedEvent event) {
        int formId = event.getFormID();
        
        // Check if this is a NovaChat form (IDs 1001-1010)
        if (formId >= ChannelFormManager.FORM_CHANNEL_SELECT && 
            formId <= ChannelFormManager.FORM_SETTINGS) {
            
            if (channelFormManager != null) {
                channelFormManager.handleFormResponse(
                    event.getPlayer(),
                    formId,
                    event.getResponse()
                );
            }
        }
    }

    /**
     * Reload the plugin configuration.
     */
    public void reload() {
        reloadConfig();
        novaChatConfig.load();
        debugMode = novaChatConfig.isDebug();
        
        // Reload chat interceptor settings
        if (chatInterceptor != null) {
            chatInterceptor.reload();
        }
        
        // Reconnect with new settings
        if (networkClient != null) {
            networkClient.disconnect();
        }
        initializeNetworkClient();
        
        getLogger().info("NovaChat-PNX configuration reloaded.");
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
            getLogger().info("[Debug] " + message);
            getLogger().error("Exception: ", throwable);
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
}
