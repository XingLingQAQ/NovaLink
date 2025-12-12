package com.nova.chat.mod.quilt;

import com.nova.chat.mod.NovaChatMod;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.chat.MessageFormatter;
import com.nova.chat.mod.config.ConfigManager;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NettyNetworkClient;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.quilt.version.QuiltVersionHelper;
import com.nova.chat.mod.version.VersionAdapter;
import com.nova.chat.mod.version.UnsupportedVersionException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Quilt mod entry point for NovaChat
 * Uses Fabric API compatibility layer through Quilted Fabric API
 * Initializes the platform adapter, network client, and chat interceptor
 * 
 * Requirements: 6.1, 6.2, 6.3, 6.4
 */
public class NovaChatQuilt implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(NovaChatQuilt.class);
    
    private static NovaChatQuilt instance;
    
    private QuiltPlatform platform;
    private ConfigManager configManager;
    private NetworkClient networkClient;
    private ChatInterceptor chatInterceptor;
    private CommandManager commandManager;
    private ModConfig config;
    private VersionAdapter versionAdapter;
    
    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("NovaChat Quilt Mod initializing...");
        
        // Initialize version adapter first
        try {
            versionAdapter = QuiltVersionHelper.initialize();
            LOGGER.info("Quilt version adapter initialized for Minecraft {}", 
                    versionAdapter.getMinecraftVersion());
        } catch (UnsupportedVersionException e) {
            LOGGER.error("Unsupported Minecraft version: {}", e.getMessage());
            LOGGER.error("NovaChat Quilt requires Minecraft 1.14.x - 1.21.x");
            return;
        }
        
        // Initialize common module
        NovaChatMod.init();
        
        // Initialize platform
        platform = new QuiltPlatform();
        
        // Load configuration
        Path configDir = FabricLoader.getInstance().getConfigDir();
        configManager = new ConfigManager(configDir);
        config = configManager.loadConfig();

        // Set replace vanilla chat option
        platform.setReplaceVanillaChat(config.getChat().isReplaceVanilla());
        
        // Initialize network client
        networkClient = new NettyNetworkClient();
        
        // Initialize message formatter
        String defaultChannel = config.getChat().getDefaultChannel();
        String defaultFormat = config.getFormats().getOrDefault(defaultChannel, "{player}: {message}");
        MessageFormatter messageFormatter = new MessageFormatter(config.getFormats(), defaultFormat);
        
        // Initialize chat interceptor
        chatInterceptor = new ChatInterceptor(platform, networkClient, messageFormatter);
        
        // Initialize command manager
        commandManager = new CommandManager(platform);
        
        // Register chat listener
        platform.registerChatListener(chatInterceptor);
        
        // Register commands
        platform.registerCommands(commandManager);
        QuiltCommandRegistrar.registerCommands(commandManager);
        
        // Register server lifecycle events (using Fabric API compatibility)
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Server started, initializing NovaChat connection...");
            platform.setServer(server);
            
            // Connect to backend
            String host = config.getBackend().getHost();
            int port = config.getBackend().getPort();
            
            networkClient.connect(host, port).thenAccept(success -> {
                if (success) {
                    LOGGER.info("Connected to NovaLink backend at {}:{}", host, port);
                } else {
                    LOGGER.warn("Failed to connect to NovaLink backend at {}:{}", host, port);
                }
            });
        });
        
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping, disconnecting from NovaChat...");
            networkClient.disconnect();
        });
        
        LOGGER.info("NovaChat Quilt Mod initialized successfully");
    }
    
    /**
     * Get the singleton instance
     * @return the instance
     */
    public static NovaChatQuilt getInstance() {
        return instance;
    }
    
    /**
     * Get the platform
     * @return the platform
     */
    public QuiltPlatform getPlatform() {
        return platform;
    }
    
    /**
     * Get the config manager
     * @return the config manager
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    /**
     * Get the network client
     * @return the network client
     */
    public NetworkClient getNetworkClient() {
        return networkClient;
    }
    
    /**
     * Get the chat interceptor
     * @return the chat interceptor
     */
    public ChatInterceptor getChatInterceptor() {
        return chatInterceptor;
    }
    
    /**
     * Get the command manager
     * @return the command manager
     */
    public CommandManager getCommandManager() {
        return commandManager;
    }
    
    /**
     * Get the configuration
     * @return the configuration
     */
    public ModConfig getConfig() {
        return config;
    }
    
    /**
     * Reload the configuration
     */
    public void reloadConfig() {
        config = configManager.loadConfig();
        platform.setReplaceVanillaChat(config.getChat().isReplaceVanilla());
        LOGGER.info("Configuration reloaded");
    }
    
    /**
     * Get the version adapter
     * @return the version adapter
     */
    public VersionAdapter getVersionAdapter() {
        return versionAdapter;
    }
    
    /**
     * Get the detected Minecraft version
     * @return the Minecraft version string
     */
    public String getMinecraftVersion() {
        return versionAdapter != null ? versionAdapter.getMinecraftVersion() : "unknown";
    }
}
