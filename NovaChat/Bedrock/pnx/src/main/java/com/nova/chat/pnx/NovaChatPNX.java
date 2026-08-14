package com.nova.chat.pnx;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.AsyncTask;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.extension.ExtensionManager;
import com.nova.chat.pnx.chat.ChatInterceptor;
import com.nova.chat.pnx.chat.MentionTabCompleter;
import com.nova.chat.pnx.chat.MessageFormatter;
import com.nova.chat.pnx.command.NovaChatCommand;
import com.nova.chat.pnx.config.NovaChatConfig;
import com.nova.chat.pnx.form.ChannelFormManager;
import com.nova.chat.pnx.listener.LocaleListener;
import com.nova.chat.pnx.network.NetworkClient;
import com.nova.chat.pnx.world.WorldMonitor;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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

    /**
     * Shared known-channel registry populated from backend ConfigSync pushes
     * (UX-DESIGN §2.1). Consumed by {@code /nc list} and {@code /nc join <Tab>}.
     */
    @Getter
    private com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry;

    /**
     * Shared channel command intents (join/leave/reload).
     * PacketSender resolves {@link #networkClient} on each send so reload/reconnect
     * does not leave a stale client reference.
     */
    @Getter
    private ChannelCommandService channelCommandService;

    /** Extension manager for custom extensions */
    @Getter
    private ExtensionManager extensionManager;

    /** Command handler (attached as executor of the descriptor's PluginCommand) */
    @Getter
    private NovaChatCommand commandHandler;

    /**
     * Per-player ignore lists (/nc ignore). Persisted to
     * {@code ignore-lists.json} in the plugin data folder.
     */
    @Getter
    private com.nova.chat.client.ignore.IgnoreListService ignoreListService;

    /**
     * Shared private-message core (client-core): send-side packet building,
     * receive-side role rendering, reply-target tracking for {@code /nc r}
     * and backend error rendering.
     */
    @Getter
    private com.nova.chat.client.privatemsg.PrivateMessageService privateMessageService;

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

        // Extract default lang/ bundles to plugins/NovaChat/lang/ so users have
        // a template to copy/edit and can drop in new languages without a rebuild.
        // I18n reads <externalLangDir>/lang/<locale>.properties on top of the
        // classpath bundles (external overrides win per-key).
        File langDir = new File(getDataFolder(), "lang");
        I18n.setExternalLangDir(getDataFolder());
        extractDefaultLang(langDir, "zh_CN");
        extractDefaultLang(langDir, "en_US");

        // Load configuration
        novaChatConfig = new NovaChatConfig(this);
        novaChatConfig.load();
        debugMode = novaChatConfig.isDebug();

        // Seed the shared I18n default locale from config (per-player locales
        // captured by LocaleListener override this per player).
        I18n.setDefaultLocale(
                LocaleResolver.parseOrDefault(
                        novaChatConfig.getLocale(),
                        LocaleResolver.ROOT_LOCALE));
        // Drop the cached bundles so a reload re-reads external overrides.
        I18n.invalidate();

        // Per-player ignore lists, persisted under the plugin data folder
        // (mirrors the I18n.setExternalLangDir injection precedent).
        ignoreListService = new com.nova.chat.client.ignore.IgnoreListService();
        ignoreListService.setDataDirectory(getDataFolder().toPath());

        // Shared private-message core (/nc msg, /nc r).
        privateMessageService = new com.nova.chat.client.privatemsg.PrivateMessageService();

        // Initialize message formatter
        messageFormatter = new MessageFormatter(this);
        
        // Initialize network client (reuses novachat-common protocol)
        initializeNetworkClient();

        // Shared command intent service (Architecture B client-core)
        initializeChannelCommandService();

        // Initialize chat interceptor
        chatInterceptor = new ChatInterceptor(this);
        getServer().getPluginManager().registerEvents(chatInterceptor, this);

        // Capture per-player Bedrock locale from the login chain (i18n)
        getServer().getPluginManager().registerEvents(new LocaleListener(), this);

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
        registerCommands();
        
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

        // Flush pending ignore-list writes to disk
        if (ignoreListService != null) {
            ignoreListService.close();
            ignoreListService = null;
        }
        
        instance = null;
        getLogger().info("NovaChat-PNX disabled.");
    }

    /**
     * Registers plugin commands.
     *
     * <p>PowerNukkitX's {@code PluginManager.parseYamlCommands} already
     * pre-registered a {@code PluginCommand} for the {@code novachat}/{@code nc}
     * command declared in the plugin descriptor ({@code plugin.yml}). A fresh
     * {@code getCommandMap().register(...)} call is silently rejected because
     * that alias slot is already occupied. So instead of registering a duplicate,
     * we attach {@link NovaChatCommand} (which implements {@code CommandExecutor})
     * as the executor of the existing {@code PluginCommand}, which is the dispatch
     * path PowerNukkitX actually uses.
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
     * Saves the default configuration file if it doesn't exist.
     */
    private void saveDefaultConfigFile() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }
    }

    /**
     * Extracts a default lang bundle from the jar to {@code <langDir>/<locale>.properties}
     * only when it does not already exist (so user customizations win). The
     * classpath resource path is {@code lang/<locale>.properties} (the
     * lang/-directory layout shipped via the bundled client-core resources).
     * Errors are logged but never fatal — i18n still reads the classpath copy
     * when the external file is absent.
     *
     * @param langDir the plugins/NovaChat/lang/ target directory
     * @param locale the locale code (e.g. {@code "zh_CN"})
     */
    private void extractDefaultLang(File langDir, String locale) {
        if (langDir == null || locale == null) {
            return;
        }
        File target = new File(langDir, locale + ".properties");
        if (target.isFile()) {
            // Preserve user customizations.
            return;
        }
        String resourcePath = "lang/" + locale + ".properties";
        try (InputStream in = getResource(resourcePath)) {
            if (in == null) {
                // Resource not packaged in this jar — skip silently.
                return;
            }
            langDir.mkdirs();
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            getLogger().error("Failed to extract default lang bundle " + locale, e);
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
        
        knownChannelRegistry = new com.nova.chat.client.channel.KnownChannelRegistry();
        networkClient = new NetworkClient(this, novaChatConfig);

        // UX-DESIGN §2.1: register a minimal ConfigSync handler that fills the
        // shared registry so /nc list and join <Tab> have data when the backend
        // pushes a roster. If the backend never pushes, the registry stays empty
        // and consumers degrade gracefully (no crash).
        com.nova.chat.client.channel.ConfigSyncHandlerRegistrar.register(
                networkClient, knownChannelRegistry,
                novaChatConfig != null ? novaChatConfig.getBackendUsername() : null);
        
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
     * Builds {@link ChannelCommandService} with a PacketSender that delegates to
     * the live {@link NetworkClient}. Send is accepted only when authenticated,
     * mirroring Velocity/Bungee. Resolves {@link #networkClient} on each send so
     * reload/reconnect does not leave a stale client reference.
     */
    private void initializeChannelCommandService() {
        channelCommandService = ChannelCommandService.forPlatform(
                () -> networkClient, PlatformType.POWERNUKKITX);
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

        // Reply-target cleanup for /nc r (private messages); thread-safe map.
        if (privateMessageService != null) {
            privateMessageService.onPlayerQuit(player.getUniqueId());
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
