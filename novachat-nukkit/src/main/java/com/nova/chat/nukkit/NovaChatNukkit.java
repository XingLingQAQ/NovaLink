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
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.extension.ExtensionManager;
import com.nova.chat.nukkit.chat.ChatInterceptor;
import com.nova.chat.nukkit.chat.MentionTabCompleter;
import com.nova.chat.nukkit.command.MessageHelper;
import com.nova.chat.nukkit.command.NovaChatCommand;
import com.nova.chat.nukkit.config.NovaChatConfig;
import com.nova.chat.nukkit.form.ChannelFormManager;
import com.nova.chat.nukkit.listener.LocaleListener;
import com.nova.chat.nukkit.network.NetworkClient;
import com.nova.chat.nukkit.world.WorldMonitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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
        saveDefaultConfigFile();

        // Load configuration
        loadConfiguration();

        // Seed the shared I18n default locale from config (per-player locales
        // captured by LocaleListener override this per player).
        com.nova.chat.client.i18n.I18n.setDefaultLocale(
                com.nova.chat.client.i18n.LocaleResolver.parseOrDefault(
                        novaChatConfig.getLocale(),
                        com.nova.chat.client.i18n.LocaleResolver.ROOT_LOCALE));

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
     *
     * <p>Reads the config file with explicit UTF-8 charset and strips any
     * UTF-8 BOM (U+FEFF) that PowerShell {@code Set-Content -Encoding utf8}
     * or other Windows editors may prepend. Without BOM stripping, the first
     * YAML key becomes {@code ﻿backend} and is silently ignored, causing
     * the format section (or any section) to fall back to jar-bundled defaults.
     * The Nukkit {@link Config} class already reads via UTF-8 internally, but
     * its SnakeYAML reader does not strip a leading BOM, so we handle it here.
     */
    public void loadConfiguration() {
        Config config = new Config(Config.YAML);
        File configFile = new File(getDataFolder(), "config.yml");
        try (InputStream raw = new FileInputStream(configFile);
             InputStream bomStripped = stripBom(raw)) {
            config.loadFromStream(bomStripped);
        } catch (Exception e) {
            getLogger().error("Failed to load config.yml with UTF-8: " + e.getMessage(), e);
            // Fallback: let Config handle the file directly
            config = new Config(configFile, Config.YAML);
        }
        novaChatConfig = new NovaChatConfig(config);
        debugMode = novaChatConfig.isDebug();

        if (debugMode) {
            getLogger().info("[Debug] Configuration loaded successfully");
        }
    }

    /**
     * Wraps the given stream to skip a leading UTF-8 BOM (EF BB BF) if present.
     *
     * @param in the raw input stream (not yet read)
     * @return a stream positioned after the BOM, or the original stream if no BOM
     * @throws java.io.IOException if reading the first bytes fails
     */
    private static InputStream stripBom(InputStream in) throws java.io.IOException {
        java.io.PushbackInputStream pb = new java.io.PushbackInputStream(in, 3);
        byte[] head = new byte[3];
        int read = pb.read(head);
        if (read == 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            // BOM consumed — return the pushback stream (remaining bytes follow)
            return pb;
        }
        // No BOM — push back the bytes we read
        if (read > 0) {
            pb.unread(head, 0, read);
        }
        return pb;
    }
    
    /**
     * Initializes the network client and connects to the backend.
     */
    private void initializeNetworkClient() {
        if (novaChatConfig == null) {
            getLogger().error("Cannot initialize network client: configuration not loaded");
            return;
        }

        knownChannelRegistry = new com.nova.chat.client.channel.KnownChannelRegistry();
        networkClient = new NetworkClient(this, novaChatConfig);

        // UX-DESIGN §2.1: register a minimal ConfigSync handler that fills the
        // shared registry so /nc list and join <Tab> have data when the backend
        // pushes a roster. If the backend never pushes, the registry stays empty
        // and consumers degrade gracefully (no crash).
        registerConfigSyncHandler();
        
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
     * Registers a minimal {@code ConfigSyncPacket} handler that fills the shared
     * {@link com.nova.chat.client.channel.KnownChannelRegistry} (UX-DESIGN §2.1).
     * Safe even if the backend never pushes to this platform — the registry stays
     * empty and {@code /nc list} shows the empty prompt.
     */
    private void registerConfigSyncHandler() {
        if (networkClient == null || knownChannelRegistry == null) {
            return;
        }
        networkClient.registerHandler(
                com.nova.chat.common.protocol.packets.ConfigSyncPacket.class,
                packet -> {
                    String json = packet.getConfigJson();
                    if (json == null || json.isBlank()) {
                        return;
                    }
                    String username = novaChatConfig != null ? novaChatConfig.getUsername() : null;
                    knownChannelRegistry.replaceAll(
                            com.nova.chat.client.channel.ConfigSyncChannels.extract(json, username));
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
        }, PlatformType.NUKKIT.name());
    }

    /**
     * Registers event listeners.
     */
    private void registerListeners() {
        // Register chat interceptor
        chatInterceptor = new ChatInterceptor(this);
        getServer().getPluginManager().registerEvents(chatInterceptor, this);

        // Capture per-player Bedrock locale from the login chain (i18n)
        getServer().getPluginManager().registerEvents(new LocaleListener(), this);

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
     *
     * <p>Nukkit's {@code PluginManager.parseYamlCommands} already pre-registered
     * a {@code PluginCommand} for the {@code novachat}/{@code nc} command
     * declared in the plugin descriptor ({@code nukkit.yml} at the jar root).
     * A fresh {@code getCommandMap().register(...)} call is silently rejected
     * because that alias slot is already occupied. So instead of registering a
     * duplicate, we attach {@link NovaChatCommand} (which implements
     * {@code CommandExecutor}) as the executor of the existing
     * {@code PluginCommand}, which is the dispatch path Nukkit actually uses.
     */
    private void registerCommands() {
        commandHandler = new NovaChatCommand(this);
        cn.nukkit.command.Command existing = getServer().getCommandMap().getCommand("novachat");
        boolean wired = false;
        if (existing instanceof cn.nukkit.command.PluginCommand) {
            ((cn.nukkit.command.PluginCommand<?>) existing).setExecutor(commandHandler);
            wired = true;
        }
        if (!wired) {
            // Fallback: no descriptor command (e.g. running outside the fat jar),
            // register directly so dispatch still works.
            getServer().getCommandMap().register("novachat", commandHandler);
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
