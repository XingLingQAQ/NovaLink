package com.nova.chat.folia;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.folia.chat.AsyncChatInterceptor;
import com.nova.chat.folia.chat.MentionTabCompleter;
import com.nova.chat.folia.command.MessageHelper;
import com.nova.chat.folia.command.NovaChatCommand;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.i18n.LocaleListener;
import com.nova.chat.folia.network.AsyncNetworkClient;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import com.nova.chat.folia.welcome.WelcomeListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
    
    @Override
    public void onEnable() {
        instance = this;

        // Save default config if not exists
        saveDefaultConfig();

        // Extract default lang/ bundles to plugins/NovaChat/lang/ (template for
        // user edits / drop-in new languages). I18n reads external overrides on
        // top of the classpath bundles (external wins per-key).
        File langDir = new File(getDataFolder(), "lang");
        I18n.setExternalLangDir(getDataFolder());
        extractDefaultLang(langDir, "zh_CN");
        extractDefaultLang(langDir, "en_US");

        // Load configuration
        loadConfiguration();

        // Initialize the shared i18n default locale from chat.locale (zh_CN fallback).
        I18n.setDefaultLocale(LocaleResolver.parseOrDefault(
                novaChatConfig.getLocale(), LocaleResolver.ROOT_LOCALE));
        // Drop the cached bundles so a reload re-reads external overrides.
        I18n.invalidate();

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
        try (InputStream in = getResource(resourcePath)) {
            if (in == null) {
                return;
            }
            langDir.mkdirs();
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to extract default lang bundle " + locale, e);
        }
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
        
        knownChannelRegistry = new com.nova.chat.client.channel.KnownChannelRegistry();
        networkClient = new AsyncNetworkClient(this, novaChatConfig, scheduler);

        // UX-DESIGN §2.1: register a minimal ConfigSync handler that fills the
        // shared registry so /nc list and join <Tab> have data when the backend
        // pushes a roster. If the backend never pushes, the registry stays empty
        // and consumers degrade gracefully (no crash). AsyncNetworkClient wraps
        // the handler to hop to an async scheduler thread.
        com.nova.chat.client.channel.ConfigSyncHandlerRegistrar.register(
                networkClient, knownChannelRegistry,
                novaChatConfig != null ? novaChatConfig.getUsername() : null);
        
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
        channelCommandService = ChannelCommandService.forPlatform(
                () -> networkClient, PlatformType.FOLIA);
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

        // Register first-join welcome listener (UX-DESIGN §8.1)
        getServer().getPluginManager().registerEvents(new WelcomeListener(this), this);

        // Register per-player locale capture (i18n) — client locale drives
        // player-facing text resolution via I18n.tr(playerId, ...).
        getServer().getPluginManager().registerEvents(new LocaleListener(), this);
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

        // Re-apply the configured default locale so a /nc reload picks up locale changes.
        I18n.setDefaultLocale(LocaleResolver.parseOrDefault(
                novaChatConfig.getLocale(), LocaleResolver.ROOT_LOCALE));
        // Re-read external lang overrides so edited/added lang/*.properties take effect.
        I18n.invalidate();

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
     * Gets the shared known-channel registry (populated from ConfigSync).
     *
     * @return the known-channel registry, never null after initialization
     */
    public com.nova.chat.client.channel.KnownChannelRegistry getKnownChannelRegistry() {
        return knownChannelRegistry;
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
