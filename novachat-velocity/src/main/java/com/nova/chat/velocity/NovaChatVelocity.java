package com.nova.chat.velocity;

import com.google.inject.Inject;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.velocity.chat.ChatListener;
import com.nova.chat.velocity.chat.MentionTabCompleter;
import com.nova.chat.velocity.command.NovaChatCommand;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.nova.chat.velocity.listener.LocaleCaptureListener;
import com.nova.chat.velocity.listener.ServerSwitchHandler;
import com.nova.chat.velocity.network.NetworkClient;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * NovaChat Velocity Plugin - Main class
 * 
 * This plugin provides chat channel functionality for Velocity proxy servers,
 * connecting to the NovaLink backend for cross-server communication.
 * 
 * Supports: Velocity proxy servers (Requirements: 23.2)
 * 
 * Key features:
 * - Cancel-and-resend strategy for handling chat signing issues
 * - Cross-server message routing through NovaLink backend
 * - Server switch handling for proper channel management
 */
@Plugin(
    id = "novachat",
    name = "NovaChat",
    version = "1.0.0-SNAPSHOT",
    description = "Cross-server chat channel system",
    authors = {"Nova Team"}
)
public class NovaChatVelocity {
    
    private static NovaChatVelocity instance;
    
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    
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

    /** Captures player client locales for per-player i18n (Architecture B) */
    private LocaleCaptureListener localeCaptureListener;

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
    public NovaChatVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        instance = this;
    }
    
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("NovaChat Velocity plugin initializing...");
        
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
        
        logger.info("NovaChat Velocity plugin enabled!");
    }
    
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("NovaChat Velocity plugin disabling...");
        
        // Disconnect from backend
        if (networkClient != null) {
            networkClient.disconnect();
            networkClient = null;
        }
        
        instance = null;
        logger.info("NovaChat Velocity plugin disabled!");
    }
    
    /**
     * Loads or reloads the plugin configuration.
     */
    public void loadConfiguration() {
        config = new NovaChatConfig(dataDirectory);
        debugMode = config.isDebug();

        // Apply the configured default locale to the shared i18n service before
        // any player-facing text is rendered. Falls back to zh_CN (ROOT_LOCALE)
        // when the configured value is blank or unparseable.
        I18n.setDefaultLocale(LocaleResolver.parseOrDefault(config.getLocale(), LocaleResolver.ROOT_LOCALE));

        if (debugMode) {
            logger.info("[Debug] Configuration loaded successfully");
        }
    }
    
    /**
     * Initializes the network client and connects to the backend.
     */
    private void initializeNetworkClient() {
        if (config == null) {
            logger.error("Cannot initialize network client: configuration not loaded");
            return;
        }
        
        knownChannelRegistry = new com.nova.chat.client.channel.KnownChannelRegistry();
        networkClient = new NetworkClient(this, config);

        // UX-DESIGN §2.1: register a minimal ConfigSync handler that fills the
        // shared registry so /nc list and join <Tab> have data when the backend
        // pushes a roster. If the backend never pushes, the registry stays empty
        // and consumers degrade gracefully (no crash).
        networkClient.registerHandler(
                com.nova.chat.common.protocol.packets.ConfigSyncPacket.class,
                packet -> {
                    String json = packet.getConfigJson();
                    if (json == null || json.isBlank()) {
                        return;
                    }
                    String username = config != null ? config.getUsername() : null;
                    knownChannelRegistry.replaceAll(
                            com.nova.chat.client.channel.ConfigSyncChannels.extract(json, username));
                });
        
        // Connect asynchronously
        server.getScheduler()
            .buildTask(this, () -> {
                networkClient.connect(
                    config.getBackendHost(),
                    config.getBackendPort()
                ).thenAccept(success -> {
                    if (success) {
                        logger.info("Connected to NovaLink backend at " + 
                            config.getBackendHost() + ":" + config.getBackendPort());
                    } else {
                        logger.warn("Failed to connect to NovaLink backend. Will retry...");
                    }
                });
            })
            .schedule();
    }
    
    /**
     * Registers event listeners.
     */
    private void registerListeners() {
        // Register chat listener for handling chat events
        chatListener = new ChatListener(this);
        server.getEventManager().register(this, chatListener);
        
        // Initialize mention Tab completer (Requirements: 11.3)
        mentionTabCompleter = new MentionTabCompleter(this);
        
        // Register server switch handler for cross-server routing (Requirements: 4.3, 5.3)
        serverSwitchHandler = new ServerSwitchHandler(this);
        server.getEventManager().register(this, serverSwitchHandler);

        // Register locale capture listener for per-player i18n (Architecture B)
        localeCaptureListener = new LocaleCaptureListener(this);
        server.getEventManager().register(this, localeCaptureListener);
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
        }, PlatformType.VELOCITY.name());
    }

    /**
     * Registers plugin commands.
     */
    private void registerCommands() {
        NovaChatCommand command = new NovaChatCommand(this);
        
        CommandMeta meta = server.getCommandManager().metaBuilder("novachat")
            .aliases("nc")
            .plugin(this)
            .build();
        
        server.getCommandManager().register(meta, command);
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
    public NovaChatConfig getConfig() {
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
     * Gets the proxy server.
     * 
     * @return the proxy server
     */
    public ProxyServer getServer() {
        return server;
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
     * Gets the data directory.
     * 
     * @return the data directory
     */
    public Path getDataDirectory() {
        return dataDirectory;
    }
    
    /**
     * Gets the plugin instance.
     * 
     * @return the plugin instance
     */
    public static NovaChatVelocity getInstance() {
        return instance;
    }
}
