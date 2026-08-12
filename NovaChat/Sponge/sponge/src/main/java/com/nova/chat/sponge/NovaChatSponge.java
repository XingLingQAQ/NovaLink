package com.nova.chat.sponge;

import com.google.inject.Inject;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
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

        // Seed the shared i18n default locale from chat.locale (zh_CN fallback).
        // Player-specific locales are registered later on join (ChatListener).
        I18n.setDefaultLocale(
                LocaleResolver.parseOrDefault(novaChatConfig.getLocale(), LocaleResolver.ROOT_LOCALE));
        // TODO(i18n): wire I18n.setExternalLangDir(configDir) here and extract
        // default lang/{zh_CN,en_US}.properties bundles into configDir/lang/ so
        // users can drop in / override languages without a rebuild. The bukkit/
        // folia/velocity/bungee platforms do this in onEnable; follow the same
        // pattern (see NovaChatBukkit#extractDefaultLang).
    }
    
    @Listener
    public void onServerStarted(StartedEngineEvent<Server> event) {
        logger.info("NovaChat Sponge plugin starting...");
        
        // Initialize message formatter
        messageFormatter = new MessageFormatter(this);
        
        // Initialize network client
        initializeNetworkClient();

        // Shared command intent service (Architecture B client-core)
        initializeChannelCommandService();

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
     *
     * <p>The injected {@code @DefaultConfig(sharedRoot=false)} loader reads HOCON
     * from {@code <configDir>/novachat.conf} (Sponge resolves it to
     * {@code config/novachat/novachat.conf} for plugin id "novachat"). The old
     * implementation wrote a YAML {@code config.yml} into {@code configDir}, which
     * the HOCON loader never reads — silently leaving the user's config ignored.
     * This writes the bundled HOCON default resource to the exact path the loader
     * reads, and only when the file is absent (so user/E2E-provided configs win).
     */
    private void saveDefaultConfig() {
        // @DefaultConfig(sharedRoot=false) targets <configDir>/<plugin-id>.conf
        Path configFile = configDir.resolve("novachat.conf");

        if (!Files.exists(configFile)) {
            try {
                Files.createDirectories(configDir);

                // HOCON resource — must match the format the injected loader parses.
                try (InputStream in = getClass().getResourceAsStream("/default-novachat.conf")) {
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
        
        knownChannelRegistry = new com.nova.chat.client.channel.KnownChannelRegistry();
        networkClient = new NetworkClient(this, novaChatConfig);

        // UX-DESIGN §2.1: register a minimal ConfigSync handler that fills the
        // shared registry so /nc list and join <Tab> have data when the backend
        // pushes a roster. If the backend never pushes, the registry stays empty
        // and consumers degrade gracefully (no crash).
        com.nova.chat.client.channel.ConfigSyncHandlerRegistrar.register(
                networkClient, knownChannelRegistry,
                novaChatConfig != null ? novaChatConfig.getUsername() : null);
        
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
     * Builds {@link ChannelCommandService} with a PacketSender that delegates to
     * the live {@link NetworkClient}. Send is accepted only when authenticated.
     */
    private void initializeChannelCommandService() {
        channelCommandService = ChannelCommandService.forPlatform(
                () -> networkClient, PlatformType.SPONGE);
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

        // Re-seed the i18n default locale in case chat.locale changed.
        I18n.setDefaultLocale(
                LocaleResolver.parseOrDefault(novaChatConfig.getLocale(), LocaleResolver.ROOT_LOCALE));

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
     * Gets the shared channel command service.
     *
     * @return the channel command service
     */
    public ChannelCommandService getChannelCommandService() {
        return channelCommandService;
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
