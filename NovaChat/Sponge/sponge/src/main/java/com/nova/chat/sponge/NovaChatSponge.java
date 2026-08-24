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
import com.nova.chat.sponge.config.HoconConfigUpdater;
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
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

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

    private static final Set<String> DYNAMIC_CONFIG_MAPPINGS = Set.of(
            "chat.channel-prefixes",
            "format.channels",
            "world-routing.mappings"
    );
    
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

    /**
     * Per-player ignore lists (/nc ignore). Persisted to
     * {@code ignore-lists.json} in the plugin config directory.
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

    /** Whether startup configuration was installed, upgraded, and loaded. */
    private boolean configurationReady;
    
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

        configurationReady = loadConfiguration();
        if (!configurationReady) {
            logger.error("NovaChat initialization stopped because the configuration could not be loaded. "
                    + "The existing file was left unchanged.");
            return;
        }

        // Seed the shared i18n default locale from chat.locale (zh_CN fallback).
        // Player-specific locales are registered later on join (ChatListener).
        I18n.setDefaultLocale(
                LocaleResolver.parseOrDefault(novaChatConfig.getLocale(), LocaleResolver.ROOT_LOCALE));

        // Extract default lang/ bundles to <configDir>/lang/ so users have a
        // template to copy/edit and can drop in new languages without a rebuild.
        // I18n reads <externalLangDir>/lang/<locale>.properties on top of the
        // classpath bundles (external overrides win per-key). The bukkit/folia/
        // velocity/bungee platforms do this in onEnable; sponge does it here
        // (config dir is available after construction).
        I18n.setExternalLangDir(configDir);
        extractDefaultLang("zh_CN");
        extractDefaultLang("en_US");
        // Drop the cached bundles so the external overrides are read fresh.
        I18n.invalidate();

        // Per-player ignore lists, persisted under the plugin config directory
        // (mirrors the I18n.setExternalLangDir injection precedent).
        ignoreListService = new com.nova.chat.client.ignore.IgnoreListService();
        ignoreListService.setDataDirectory(configDir);

        // Shared private-message core (/nc msg, /nc r).
        privateMessageService = new com.nova.chat.client.privatemsg.PrivateMessageService();
    }
    
    @Listener
    public void onServerStarted(StartedEngineEvent<Server> event) {
        if (!configurationReady) {
            logger.error("NovaChat was not started because its configuration is unavailable");
            return;
        }
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

        // Flush pending ignore-list writes to disk
        if (ignoreListService != null) {
            ignoreListService.close();
            ignoreListService = null;
        }
        
        instance = null;
        logger.info("NovaChat Sponge plugin disabled!");
    }
    
    @Listener
    public void onRegisterCommands(RegisterCommandEvent<Command.Parameterized> event) {
        if (!configurationReady) {
            return;
        }
        // Register commands (Requirements: 3.1)
        NovaChatCommand commandHandler = new NovaChatCommand(this);
        event.register(container, commandHandler.buildCommand(), "novachat", "nc");
        debug("Registered /novachat and /nc commands");
    }

    /**
     * Extracts a default lang bundle from the jar to
     * {@code <configDir>/lang/<locale>.properties} only when it does not already
     * exist (so user customizations win). The classpath resource path is
     * {@code lang/<locale>.properties} (shipped via the bundled client-core
     * resources). Errors are logged but never fatal — i18n still reads the
     * classpath copy when the external file is absent.
     *
     * @param locale the locale code (e.g. {@code "zh_CN"})
     */
    private void extractDefaultLang(String locale) {
        if (locale == null) {
            return;
        }
        Path langDir = configDir.resolve("lang");
        Path target = langDir.resolve(locale + ".properties");
        if (Files.isRegularFile(target)) {
            return;
        }
        String resourcePath = "/lang/" + locale + ".properties";
        try (InputStream in = NovaChatSponge.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return;
            }
            Files.createDirectories(langDir);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warn("Failed to extract default lang bundle " + locale, e);
        }
    }

    /**
     * Loads or reloads the plugin configuration.
     */
    public boolean loadConfiguration() {
        Path configFile = configDir.resolve("novachat.conf");
        Path legacyConfig = configDir.resolve("config.yml");
        try {
            boolean migrated;
            try (InputStream template = openConfigTemplate()) {
                migrated = HoconConfigUpdater.migrateLegacyYaml(
                        legacyConfig, configFile, template, DYNAMIC_CONFIG_MAPPINGS);
            }
            if (migrated) {
                logger.info("Migrated legacy config.yml to novachat.conf; the original YAML file was retained");
            }

            HoconConfigUpdater.UpdateResult updateResult;
            try (InputStream template = openConfigTemplate()) {
                updateResult = HoconConfigUpdater.update(
                        configFile, template, DYNAMIC_CONFIG_MAPPINGS);
            }
            if (updateResult.created()) {
                logger.info("Created configuration from the bundled HOCON template");
            } else if (updateResult.updated()) {
                logger.info("Added new configuration entries from the bundled template; backup: "
                        + updateResult.backupPath());
            }

            CommentedConfigurationNode rootNode = configLoader.load();
            NovaChatConfig loadedConfig = new NovaChatConfig(rootNode);
            novaChatConfig = loadedConfig;
            debugMode = loadedConfig.isDebug();
            
            if (debugMode) {
                logger.info("[Debug] Configuration loaded successfully");
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to install, upgrade, or load configuration", e);
            return false;
        }
    }

    private InputStream openConfigTemplate() throws IOException {
        InputStream template = NovaChatSponge.class.getResourceAsStream("/default-novachat.conf");
        if (template == null) {
            throw new IOException("Bundled configuration template default-novachat.conf is missing");
        }
        return template;
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
        if (!loadConfiguration()) {
            logger.warn("NovaChat configuration reload was rejected; the previous runtime configuration remains active");
            return;
        }

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
     * Gets the per-player ignore list service (/nc ignore).
     *
     * @return the ignore list service, never null after construction
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
