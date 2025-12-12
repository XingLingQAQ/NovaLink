package com.nova.chat.sponge;

import com.google.inject.Inject;
import com.nova.chat.sponge.chat.ChatListener;
import com.nova.chat.sponge.chat.MentionTabCompleter;
import com.nova.chat.sponge.chat.MessageFormatter;
import com.nova.chat.sponge.command.NovaChatCommand;
import com.nova.chat.sponge.config.NovaChatConfig;
import com.nova.chat.sponge.network.NetworkClient;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NovaChat Sponge Plugin - Main class
 * 
 * This plugin provides chat channel functionality for Sponge servers,
 * connecting to the NovaLink backend for cross-server communication.
 * 
 * Requirements: 3.1, 3.2
 */
@Plugin("novachat")
public class NovaChatSponge {
    
    private static NovaChatSponge instance;
    
    private final PluginContainer container;
    private final Logger logger;
    private final Path configDir;
    private final ConfigurationLoader<CommentedConfigurationNode> configLoader;
    
    /** Plugin configuration */
    private NovaChatConfig novaChatConfig;
    
    /** Network client for backend connection */
    private NetworkClient networkClient;
    
    /** Chat listener for handling chat events */
    private ChatListener chatListener;
    
    /** Mention Tab completer for @mentions in chat */
    private MentionTabCompleter mentionTabCompleter;
    
    /** Message formatter for rendering messages */
    private MessageFormatter messageFormatter;
    
    /** Debug mode flag */
    private boolean debugMode = false;
    
    @Inject
    public NovaChatSponge(
            PluginContainer container,
            Logger logger,
            @ConfigDir(sharedRoot = false) Path configDir,
            @DefaultConfig(sharedRoot = false) ConfigurationLoader<CommentedConfigurationNode> configLoader) {
        this.container = container;
        this.logger = logger;
        this.configDir = configDir;
        this.configLoader = configLoader;
        instance = this;
    }
    
    @Listener
    public void onConstruct(ConstructPluginEvent event) {
        logger.info("NovaChat Sponge plugin constructing...");
        
        // Save default config if not exists
        saveDefaultConfig();
        
        // Load configuration
        loadConfiguration();
    }
    
    @Listener
    public void onServerStarted(StartedEngineEvent<Server> event) {
        logger.info("NovaChat Sponge plugin starting...");
        
        // Initialize message formatter
        messageFormatter = new MessageFormatter(this);
        
        // Initialize network client
        initializeNetworkClient();
        
        // Register event listeners
        registerListeners();
        
        logger.info("NovaChat Sponge plugin enabled!");
    }
    
    @Listener
    public void onServerStopping(StoppingEngineEvent<Server> event) {
        logger.info("NovaChat Sponge plugin disabling...");
        
        // Disconnect from backend
        if (networkClient != null) {
            networkClient.disconnect();
            networkClient = null;
        }
        
        instance = null;
        logger.info("NovaChat Sponge plugin disabled!");
    }
    
    @Listener
    public void onRegisterCommands(RegisterCommandEvent<Command.Parameterized> event) {
        // Register commands (Requirements: 3.1)
        NovaChatCommand commandHandler = new NovaChatCommand(this);
        event.register(container, commandHandler.buildCommand(), "novachat", "nc");
        debug("Registered /novachat and /nc commands");
    }

    /**
     * Saves the default configuration file if it doesn't exist.
     */
    private void saveDefaultConfig() {
        Path configFile = configDir.resolve("config.yml");
        
        if (!Files.exists(configFile)) {
            try {
                Files.createDirectories(configDir);
                
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                        logger.info("Created default configuration file");
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to save default config", e);
            }
        }
    }
    
    /**
     * Loads or reloads the plugin configuration.
     */
    public void loadConfiguration() {
        try {
            CommentedConfigurationNode rootNode = configLoader.load();
            novaChatConfig = new NovaChatConfig(rootNode);
            debugMode = novaChatConfig.isDebug();
            
            if (debugMode) {
                logger.info("[Debug] Configuration loaded successfully");
            }
        } catch (ConfigurateException e) {
            logger.error("Failed to load configuration", e);
            // Create default config
            novaChatConfig = new NovaChatConfig(null);
        }
    }
    
    /**
     * Initializes the network client and connects to the backend.
     */
    private void initializeNetworkClient() {
        if (novaChatConfig == null) {
            logger.error("Cannot initialize network client: configuration not loaded");
            return;
        }
        
        networkClient = new NetworkClient(this, novaChatConfig);
        
        // Connect asynchronously
        Sponge.asyncScheduler().executor(container).execute(() -> {
            networkClient.connect(
                novaChatConfig.getBackendHost(),
                novaChatConfig.getBackendPort()
            ).thenAccept(success -> {
                if (success) {
                    logger.info("Connected to NovaLink backend at " + 
                        novaChatConfig.getBackendHost() + ":" + novaChatConfig.getBackendPort());
                } else {
                    logger.warn("Failed to connect to NovaLink backend. Will retry...");
                }
            });
        });
    }
    
    /**
     * Registers event listeners.
     * Requirements: 3.2, 3.3
     */
    private void registerListeners() {
        // Register chat listener
        chatListener = new ChatListener(this);
        Sponge.eventManager().registerListeners(container, chatListener);
        
        // Initialize mention Tab completer (Requirements: 11.3)
        mentionTabCompleter = new MentionTabCompleter(this);
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
        
        // Reload message formatter
        if (messageFormatter != null) {
            messageFormatter.reload();
        }
        
        // Reconnect if connection settings changed
        if (networkClient != null) {
            networkClient.disconnect();
        }
        initializeNetworkClient();
        
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
     * Logs a debug message with exception if debug mode is enabled.
     * 
     * @param message the message to log
     * @param throwable the exception to log
     */
    public void debug(String message, Throwable throwable) {
        if (debugMode) {
            logger.info("[Debug] " + message, throwable);
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
     * Gets the plugin container.
     * 
     * @return the plugin container
     */
    public PluginContainer getContainer() {
        return container;
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
     * Gets the chat listener.
     * 
     * @return the chat listener
     */
    public ChatListener getChatListener() {
        return chatListener;
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
     * Gets the message formatter.
     * 
     * @return the message formatter
     */
    public MessageFormatter getMessageFormatter() {
        return messageFormatter;
    }
    
    /**
     * Gets the plugin instance.
     * 
     * @return the plugin instance
     */
    public static NovaChatSponge getInstance() {
        return instance;
    }
}
