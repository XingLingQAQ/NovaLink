package com.nova.chat.mod.quilt;

import com.nova.chat.mod.NovaChatMod;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.config.ConfigManager;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.ModServices;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Quilt mod entry point for NovaChat.
 *
 * <p>Builds the shared mod runtime via {@link NovaChatMod#bootstrap} and wires it
 * into the Quilted Fabric API lifecycle events and command registrar.
 */
public class NovaChatQuilt implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(NovaChatQuilt.class);

    private static NovaChatQuilt instance;

    private QuiltPlatform platform;
    private ConfigManager configManager;
    private ModConfig config;
    private ModServices services;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("NovaChat Quilt Mod initializing...");

        // Initialize common module (version banner)
        NovaChatMod.init();

        // Initialize platform
        platform = new QuiltPlatform();

        // Load configuration
        Path configDir = FabricLoader.getInstance().getConfigDir();
        configManager = new ConfigManager(configDir);
        config = configManager.loadConfig();

        // Set replace vanilla chat option
        platform.setReplaceVanillaChat(config.getChat().isReplaceVanilla());

        // Bootstrap the shared mod runtime
        services = NovaChatMod.bootstrap(platform, config, config.getBackend().getUsername());

        // Initialize command manager and register handlers
        CommandManager commandManager = new CommandManager(platform);
        QuiltCommandRegistrar.registerCommands(commandManager, services);

        // Register chat listener (common ChatInterceptor)
        platform.registerChatListener(services.getChatInterceptor());

        // Register commands on the platform
        platform.registerCommands(commandManager);

        // Register player join/leave events → forward to common ChatInterceptor.
        // Quilt uses the Quilted Fabric API (same callbacks as Fabric): the
        // common handler owns state init (join) + removePlayerState (leave),
        // so the loader stays a thin event forwarder. This wiring finally calls
        // the previously-dead removePlayerState on disconnect.
        ChatInterceptor chatInterceptor = services.getChatInterceptor();
        ServerPlayerEvents.JOIN.register((ServerPlayer player) ->
                chatInterceptor.onPlayerJoin(player.getUUID(), player.getName().getString()));
        ServerPlayerEvents.LEAVE.register((ServerPlayer player) ->
                chatInterceptor.onPlayerQuit(player.getUUID()));

        // Register server lifecycle events (using Fabric API compatibility)
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Server started, initializing NovaChat connection...");
            platform.setServer(server);

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
            platform.shutdown();
        });

        LOGGER.info("NovaChat Quilt Mod initialized successfully");
    }

    public static NovaChatQuilt getInstance() {
        return instance;
    }

    public QuiltPlatform getPlatform() {
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

    /**
     * Reloads the configuration from disk and propagates the change to the
     * platform's replace-vanilla-chat flag and the shared chat interceptor.
     */
    public void reloadConfig() {
        config = configManager.loadConfig();
        platform.setReplaceVanillaChat(config.getChat().isReplaceVanilla());
        if (services != null && services.getChatInterceptor() != null) {
            services.getChatInterceptor().reload();
        }
        LOGGER.info("Configuration reloaded");
    }
}
