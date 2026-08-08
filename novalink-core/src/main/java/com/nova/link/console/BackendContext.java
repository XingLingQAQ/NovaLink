package com.nova.link.console;

import com.nova.link.api.WebhookManager;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.ClientPermissionRegistry;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.config.ConfigManager;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.NettyServer;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.spy.SpyManager;
import com.nova.link.websocket.WebSocketGateway;

/**
 * Holder for the runtime backend managers/handlers, lifted out of NovaLinkMain
 * so the console command layer can call them directly without re-declaring a
 * long parameter list. Built once during startup; getters are intentionally
 * package-private-accessible public methods so the console classes can reuse
 * them.
 *
 * <p>This object holds NO business logic of its own — it is pure wiring.
 */
public final class BackendContext {

    private final ConfigManager configManager;
    private final AuthManager authManager;
    private final PermissionManager permissionManager;
    private final ClientPermissionRegistry clientPermissionRegistry;
    private final DatabaseProvider databaseProvider;
    private final ChannelManager channelManager;
    private final PlayerStateManager playerStateManager;
    private final WebhookManager webhookManager;
    private final PrivateChannelManager privateChannelManager;
    private final InvitationManager invitationManager;
    private final MuteManager muteManager;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final ServerNetworkHandler networkHandler;
    private final MessageRouter messageRouter;
    private final SpyManager spyManager;
    private final NettyServer tcpServer;
    private final WebSocketGateway webSocketGateway;

    public BackendContext(ConfigManager configManager,
                          AuthManager authManager,
                          PermissionManager permissionManager,
                          ClientPermissionRegistry clientPermissionRegistry,
                          DatabaseProvider databaseProvider,
                          ChannelManager channelManager,
                          PlayerStateManager playerStateManager,
                          WebhookManager webhookManager,
                          PrivateChannelManager privateChannelManager,
                          InvitationManager invitationManager,
                          MuteManager muteManager,
                          SensitiveWordFilter sensitiveWordFilter,
                          ServerNetworkHandler networkHandler,
                          MessageRouter messageRouter,
                          SpyManager spyManager,
                          NettyServer tcpServer,
                          WebSocketGateway webSocketGateway) {
        this.configManager = configManager;
        this.authManager = authManager;
        this.permissionManager = permissionManager;
        this.clientPermissionRegistry = clientPermissionRegistry;
        this.databaseProvider = databaseProvider;
        this.channelManager = channelManager;
        this.playerStateManager = playerStateManager;
        this.webhookManager = webhookManager;
        this.privateChannelManager = privateChannelManager;
        this.invitationManager = invitationManager;
        this.muteManager = muteManager;
        this.sensitiveWordFilter = sensitiveWordFilter;
        this.networkHandler = networkHandler;
        this.messageRouter = messageRouter;
        this.spyManager = spyManager;
        this.tcpServer = tcpServer;
        this.webSocketGateway = webSocketGateway;
    }

    public ConfigManager getConfigManager() { return configManager; }
    public AuthManager getAuthManager() { return authManager; }
    public PermissionManager getPermissionManager() { return permissionManager; }
    public ClientPermissionRegistry getClientPermissionRegistry() { return clientPermissionRegistry; }
    public DatabaseProvider getDatabaseProvider() { return databaseProvider; }
    public ChannelManager getChannelManager() { return channelManager; }
    public PlayerStateManager getPlayerStateManager() { return playerStateManager; }
    public WebhookManager getWebhookManager() { return webhookManager; }
    public PrivateChannelManager getPrivateChannelManager() { return privateChannelManager; }
    public InvitationManager getInvitationManager() { return invitationManager; }
    public MuteManager getMuteManager() { return muteManager; }
    public SensitiveWordFilter getSensitiveWordFilter() { return sensitiveWordFilter; }
    public ServerNetworkHandler getNetworkHandler() { return networkHandler; }
    public MessageRouter getMessageRouter() { return messageRouter; }
    public SpyManager getSpyManager() { return spyManager; }
    public NettyServer getTcpServer() { return tcpServer; }
    public WebSocketGateway getWebSocketGateway() { return webSocketGateway; }
}
