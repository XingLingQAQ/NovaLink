package com.nova.chat.mod.forge;

import com.nova.chat.mod.NovaChatMod;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.chat.MessageFormatter;
import com.nova.chat.mod.config.ConfigManager;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NettyNetworkClient;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.CommandManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Forge mod entry point for NovaChat
 * Initializes the platform adapter, network client, and chat interceptor
 * 
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5
 */
@Mod(NovaChatMod.MOD_ID)
public class NovaChatForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(NovaChatForge.class);
    
    private static NovaChatForge instance;
    
    private ForgePlatform platform;
    private ConfigManager configManager;
    private NetworkClient networkClient;
    private ChatInterceptor chatInterceptor;
    private CommandManager commandManager;
    private ModConfig config;
    
    public NovaChatForge() {
        instance = this;
        LOGGER.info("NovaChat Forge Mod initializing...");
        
        // Initialize common module
        NovaChatMod.init();
        
        // Initialize platform
        platform = new ForgePlatform();

        
        // Load configuration
        Path configDir = FMLPaths.CONFIGDIR.get();
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
        ForgeCommandRegistrar.registerCommands(commandManager);
        
        // Register server lifecycle events on MinecraftForge event bus
        MinecraftForge.EVENT_BUS.register(this);
        
        LOGGER.info("NovaChat Forge Mod initialized successfully");
    }
    
    /**
     * Handle server started event
     * @param event the server started event
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("Server started, initializing NovaChat connection...");
        platform.setServer(event.getServer());
        
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
    }
    
    /**
     * Handle server stopping event
     * @param event the server stopping event
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Server stopping, disconnecting from NovaChat...");
        networkClient.disconnect();
    }


    /**
     * Get the singleton instance
     * @return the instance
     */
    public static NovaChatForge getInstance() {
        return instance;
    }
    
    /**
     * Get the platform
     * @return the platform
     */
    public ForgePlatform getPlatform() {
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
