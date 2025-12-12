package com.nova.chat.mod.fabric;

import com.nova.chat.mod.NovaChatMod;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.chat.MessageFormatter;
import com.nova.chat.mod.config.ConfigManager;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NettyNetworkClient;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.CommandManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Fabric mod entry point for NovaChat
 * Initializes the platform adapter, network client, and chat interceptor
 */
public class NovaChatFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(NovaChatFabric.class);
    
    private static NovaChatFabric instance;
    
    private FabricPlatform platform;
    private ConfigManager configManager;
    private NetworkClient networkClient;
    private ChatInterceptor chatInterceptor;
    private CommandManager commandManager;
    private ModConfig config;
    
    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("NovaChat Fabric Mod initializing...");
        
        // Initialize common module
        NovaChatMod.init();
        
        // Initialize platform
        platform = new FabricPlatform();
        
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
        FabricCommandRegistrar.registerCommands(commandManager);
        
        // Register server lifecycle events
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
        
        LOGGER.info("NovaChat Fabric Mod initialized successfully");
    }
    
    /**
     * Get the singleton instance
     * @return the instance
     */
    public static NovaChatFabric getInstance() {
        return instance;
    }
    
    /**
     * Get the platform
     * @return the platform
     */
    public FabricPlatform getPlatform() {
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
}
