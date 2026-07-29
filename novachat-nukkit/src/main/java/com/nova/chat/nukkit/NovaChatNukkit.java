package com.nova.chat.nukkit;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.AsyncTask;
import cn.nukkit.utils.Config;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.common.extension.ExtensionManager;
import com.nova.chat.nukkit.chat.ChatInterceptor;
import com.nova.chat.nukkit.chat.MentionTabCompleter;
import com.nova.chat.nukkit.command.MessageHelper;
import com.nova.chat.nukkit.command.NovaChatCommand;
import com.nova.chat.nukkit.config.NovaChatConfig;
import com.nova.chat.nukkit.form.ChannelFormManager;
import com.nova.chat.nukkit.network.NetworkClient;
import com.nova.chat.nukkit.world.WorldMonitor;

import java.io.File;
import java.nio.file.Path;

/**
 * NovaChat Nukkit Plugin - Main class
 * 
 * This plugin provides chat channel functionality for Nukkit Bedrock servers,
 * connecting to the NovaLink backend for cross-server communication.
 * 
 * Supports: Nukkit Bedrock servers (Requirements: 23.4)
 */
public class NovaChatNukkit extends PluginBase implements Listener {
    
    private static NovaChatNukkit instance;
    
    /** Plugin configuration */
    private NovaChatConfig novaChatConfig;
    
    /** Network client for backend connection */
    private NetworkClient networkClient;
    
    /** Chat interceptor for handling chat events */
    private ChatInterceptor chatInterceptor;
    
    /** Mention Tab completer for @mentions in chat */
    private MentionTabCompleter mentionTabCompleter;
    
    /** Command handler */
    private NovaChatCommand commandHandler;
    
    /** Message helper for formatting messages */
    private MessageHelper messageHelper;
    
    /** Form manager for Bedrock GUI */
    private ChannelFormManager formManager;
    
    /** World monitor for auto-routing */
    private WorldMonitor worldMonitor;
    
    /** Extension manager for custom extensions */
    private ExtensionManager extensionManager;

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
        saveDefaultConfigFile();

        // Load configuration
        loadConfiguration();

        // Initialize message helper
        messageHelper = new MessageHelper(this);

        // Initialize network client
        initializeNetworkClient();

        // Shared command intent service (Architecture B client-core)
        initializeChannelCommandService();

        // Initialize form manager for Bedrock GUI
        formManager = new ChannelFormManager(this);

        // Register event listeners
        registerListeners();

        // Register commands
        registerCommands();

        // Load and enable extensions
        // Requirements: 10.3 - THE Nukkit/PNX 扩展 SHALL 使用 Java 编写并放置在 extensions 目录
        loadExtensions();

        getLogger().info("NovaChat Nukkit plugin enabled!");
    }
    
    @Override
    public void onDisable() {
        getLogger().info("NovaChat Nukkit plugin disabling...");
        
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
        getLogger().info("NovaChat Nukkit plugin disabled!");
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
     * Loads or reloads the plugin configuration.
     */
    public void loadConfiguration() {
        Config config = new Config(new File(getDataFolder(), "config.yml"), Config.YAML);
        novaChatConfig = new NovaChatConfig(config);
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
            getLogger().error("Cannot initialize network client: configuration not loaded");
            return;
        }
        
        networkClient = new NetworkClient(this, novaChatConfig);
        
        // Connect asynchronously
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
     * Builds {@link ChannelCommandService} with a PacketSender that delegates to
     * the live {@link NetworkClient}. Send is accepted only when authenticated.
     */
    private void initializeChannelCommandService() {
        channelCommandService = new ChannelCommandService(packet -> {
            NetworkClient client = networkClient;
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
        // Register chat interceptor
        chatInterceptor = new ChatInterceptor(this);
        getServer().getPluginManager().registerEvents(chatInterceptor, this);
        
        // Initialize mention Tab completer (Requirements: 11.3)
        mentionTabCompleter = new MentionTabCompleter(this);
        
        // Register world monitor for auto-routing (Requirements: 6.2, 6.3, 9.1-9.4)
        worldMonitor = new WorldMonitor(this);
        getServer().getPluginManager().registerEvents(worldMonitor, this);
        
        // Register this class for form responses
        getServer().getPluginManager().registerEvents(this, this);
    }
    
    /**
     * Registers plugin commands.
     */
    private void registerCommands() {
        commandHandler = new NovaChatCommand(this);
        getServer().getCommandMap().register("novachat", commandHandler);
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
     * Handles form responses from players.
     * This is used for the Bedrock Form API integration.
     *
     * @param event the form response event
     */
    @EventHandler
    public void onFormResponse(PlayerFormRespondedEvent event) {
        Player player = event.getPlayer();
        int formId = event.getFormID();
        
        // Check if this is one of our forms (IDs 1001-1006)
        if (formId >= 1001 && formId <= 1006) {
            formManager.handleFormResponse(player, formId, event.getResponse());
        }
    }
    
    /**
     * Handles player quit events to clean up form manager data.
     *
     * @param event the quit event
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up pending form data
        if (formManager != null) {
            formManager.clearPendingData(event.getPlayer().getUniqueId());
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
    public ChatInterceptor getChatInterceptor() {
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
     * Gets the command handler.
     * 
     * @return the command handler
     */
    public NovaChatCommand getCommandHandler() {
        return commandHandler;
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
     * Gets the form manager for Bedrock GUI.
     * 
     * @return the form manager
     */
    public ChannelFormManager getFormManager() {
        return formManager;
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
     * Gets the extension manager.
     * 
     * @return the extension manager
     */
    public ExtensionManager getExtensionManager() {
        return extensionManager;
    }
    
    /**
     * Gets the plugin instance.
     * 
     * @return the plugin instance
     */
    public static NovaChatNukkit getInstance() {
        return instance;
    }
}
