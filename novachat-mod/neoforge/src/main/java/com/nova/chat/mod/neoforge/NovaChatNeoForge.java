package com.nova.chat.mod.neoforge;

import com.nova.chat.mod.NovaChatMod;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.config.ConfigManager;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.ModServices;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * NeoForge mod entry point for NovaChat.
 *
 * <p>Builds the shared mod runtime via {@link NovaChatMod#bootstrap} and wires it
 * into the NeoForge server lifecycle events and command registrar.
 */
@Mod(NovaChatMod.MOD_ID)
public class NovaChatNeoForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(NovaChatNeoForge.class);

    private static NovaChatNeoForge instance;

    private NeoForgePlatform platform;
    private ConfigManager configManager;
    private ModConfig config;
    private ModServices services;

    public NovaChatNeoForge(IEventBus modEventBus) {
        instance = this;
        LOGGER.info("NovaChat NeoForge Mod initializing...");

        // Initialize common module (version banner)
        NovaChatMod.init();

        // Initialize platform
        platform = new NeoForgePlatform();

        // Load configuration
        Path configDir = FMLPaths.CONFIGDIR.get();
        configManager = new ConfigManager(configDir);
        config = configManager.loadConfig();

        // Set replace vanilla chat option
        platform.setReplaceVanillaChat(config.getChat().isReplaceVanilla());

        // Bootstrap the shared mod runtime
        services = NovaChatMod.bootstrap(platform, config,
                config.getBackend() != null ? config.getBackend().getUsername() : null);

        // Initialize command manager and register handlers
        CommandManager commandManager = new CommandManager(platform);
        NeoForgeCommandRegistrar.registerCommands(commandManager, services);

        // Register chat listener (common ChatInterceptor)
        platform.registerChatListener(services.getChatInterceptor());

        // Register commands on the platform
        platform.registerCommands(commandManager);

        // Register server lifecycle events on NeoForge event bus
        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("NovaChat NeoForge Mod initialized successfully");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("Server started, initializing NovaChat connection...");
        platform.setServer(event.getServer());

        String host = config.getBackend().getHost();
        int port = config.getBackend().getPort();

        services.getNetworkClient().connect(host, port).thenAccept(success -> {
            if (success) {
                LOGGER.info("Connected to NovaLink backend at {}:{}", host, port);
            } else {
                LOGGER.warn("Failed to connect to NovaLink backend at {}:{}", host, port);
            }
        });
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Server stopping, disconnecting from NovaChat...");
        services.getNetworkClient().disconnect();
    }

    public static NovaChatNeoForge getInstance() {
        return instance;
    }

    public NeoForgePlatform getPlatform() {
        return platform;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public NetworkClient getNetworkClient() {
        return services != null ? (NetworkClient) services.getNetworkClient() : null;
    }

    public ChatInterceptor getChatInterceptor() {
        return services != null ? services.getChatInterceptor() : null;
    }

    public ModServices getServices() {
        return services;
    }

    public ModConfig getConfig() {
        return config;
    }

    public void reloadConfig() {
        config = configManager.loadConfig();
        platform.setReplaceVanillaChat(config.getChat().isReplaceVanilla());
        if (services != null && services.getChatInterceptor() != null) {
            services.getChatInterceptor().reload();
        }
        LOGGER.info("Configuration reloaded");
    }
}
