package com.nova.chat.mod.fabric;

import com.nova.chat.mod.NovaChatMod;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.config.ConfigManager;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.ModServices;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Fabric mod entry point for NovaChat.
 *
 * <p>Builds the shared mod runtime via {@link NovaChatMod#bootstrap} (network
 * client over {@code AbstractPlatformNetworkClient}, {@link ChatInterceptor} on
 * {@code PlayerStateStore} + {@code ChannelResponseDispatcher}, shared
 * {@code ChannelCommandService} + {@code KnownChannelRegistry}) and wires it
 * into the Fabric lifecycle events and command registrar.
 */
public class NovaChatFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(NovaChatFabric.class);

    private static NovaChatFabric instance;

    private FabricPlatform platform;
    private ConfigManager configManager;
    private ModConfig config;
    private ModServices services;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("NovaChat Fabric Mod initializing...");

        // Initialize common module (version banner)
        NovaChatMod.init();

        // Initialize platform
        platform = new FabricPlatform();

        // Load configuration
        Path configDir = FabricLoader.getInstance().getConfigDir();
        configManager = new ConfigManager(configDir);
        config = configManager.loadConfig();

        // Set replace vanilla chat option
        platform.setReplaceVanillaChat(config.getChat().isReplaceVanilla());

        // Bootstrap the shared mod runtime (network + chat + commands + registry)
        services = NovaChatMod.bootstrap(platform, config,
                config.getBackend() != null ? config.getBackend().getUsername() : null);

        // Initialize command manager and register handlers
        CommandManager commandManager = new CommandManager(platform);
        FabricCommandRegistrar.registerCommands(commandManager, services);

        // Register chat listener (common ChatInterceptor)
        platform.registerChatListener(services.getChatInterceptor());

        // Register commands on the platform
        platform.registerCommands(commandManager);

        // Register server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Server started, initializing NovaChat connection...");
            platform.setServer(server);

            // Connect to backend
            String host = config.getBackend().getHost();
            int port = config.getBackend().getPort();

            services.getNetworkClient().connect(host, port).thenAccept(success -> {
                if (success) {
                    LOGGER.info("Connected to NovaLink backend at {}:{}", host, port);
                } else {
                    LOGGER.warn("Failed to connect to NovaLink backend at {}:{}", host, port);
                }
            });
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping, disconnecting from NovaChat...");
            services.getNetworkClient().disconnect();
        });

        LOGGER.info("NovaChat Fabric Mod initialized successfully");
    }

    public static NovaChatFabric getInstance() {
        return instance;
    }

    public FabricPlatform getPlatform() {
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
