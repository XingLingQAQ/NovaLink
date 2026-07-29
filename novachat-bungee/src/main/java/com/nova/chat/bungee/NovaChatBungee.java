package com.nova.chat.bungee;

import com.nova.chat.bungee.chat.ChatListener;
import com.nova.chat.bungee.chat.MentionTabCompleter;
import com.nova.chat.bungee.command.NovaChatCommand;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.bungee.listener.ServerSwitchHandler;
import com.nova.chat.bungee.network.NetworkClient;
import com.nova.chat.client.command.ChannelCommandService;
import net.md_5.bungee.api.plugin.Plugin;

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
        getLogger().info("NovaChat BungeeCord plugin initializing...");
        
        // Load configuration
        loadConfiguration();
        
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
        
        instance = null;
        getLogger().info("NovaChat BungeeCord plugin disabled!");
    }
    
    /**
     * Loads or reloads the plugin configuration.
     */
    public void loadConfiguration() {
        config = new NovaChatConfig(getDataFolder());
        debugMode = config.isDebug();
        
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
        
        networkClient = new NetworkClient(this, config);
        
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
