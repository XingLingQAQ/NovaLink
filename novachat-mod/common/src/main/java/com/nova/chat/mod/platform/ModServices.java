package com.nova.chat.mod.platform;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;

/**
 * Holder for the shared mod-level services that command handlers need.
 *
 * <p>This avoids stashing platform services on the {@link Platform} interface
 * (which is a loader-bridge) and avoids per-command re-wiring. The
 * {@code NovaChatMod} bootstrap constructs one instance and attaches it to each
 * {@link CommandContext} via {@link CommandContext#withServices(ModServices)}.
 */
public final class ModServices {

    private final ModConfig config;
    private final NetworkClient networkClient;
    private final ChatInterceptor chatInterceptor;
    private final ChannelCommandService channelCommandService;
    private final KnownChannelRegistry knownChannelRegistry;

    public ModServices(ModConfig config, NetworkClient networkClient,
                       ChatInterceptor chatInterceptor,
                       ChannelCommandService channelCommandService,
                       KnownChannelRegistry knownChannelRegistry) {
        this.config = config;
        this.networkClient = networkClient;
        this.chatInterceptor = chatInterceptor;
        this.channelCommandService = channelCommandService;
        this.knownChannelRegistry = knownChannelRegistry;
    }

    public ModConfig getConfig() {
        return config;
    }

    public AbstractPlatformNetworkClient getNetworkClient() {
        return networkClient;
    }

    public ChatInterceptor getChatInterceptor() {
        return chatInterceptor;
    }

    public ChannelCommandService getChannelCommandService() {
        return channelCommandService;
    }

    public KnownChannelRegistry getKnownChannelRegistry() {
        return knownChannelRegistry;
    }
}
