package com.nova.chat.velocity.listener;

import com.nova.chat.proxy.engine.ProxyServerSwitchCore;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.platform.VelocityProxyPlayer;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;

/**
 * Handles server switching events for cross-server message routing.
 *
 * <p>On a server connection it records the new server and then joins the default
 * channel (first connection of the session) or re-requests the active channel.
 *
 * <p><b>There is no separate "server switch" packet.</b> The backend learns
 * about the new server from the JOIN packet's {@code server} extra, which is a
 * real channel action it understands. {@code ChannelAction} has no switch value
 * and StarLink's {@code ChannelActionHandler} only dispatches the declared
 * actions, so the historical switch-notification LEAVE was dead code (the
 * backend answered NC-400 for its blank channel id, and the response was
 * dropped because it carried {@code player_uuid} rather than the
 * {@code playerId} the tracker keys on). The {@code ServerPreConnectEvent}
 * handler that sent it is gone.
 *
 * <p>The first-connection decision is the shared atomic claim
 * ({@link com.nova.chat.proxy.chat.ProxyChatCore#claimFirstConnection}), not
 * {@code getCurrentServer() == null}: both this handler and the chat listener
 * write {@code currentServer} and their execution order is not guaranteed.
 *
 * <p>Requirements: 4.3, 5.3 - Cross-server message routing
 */
public class ServerSwitchHandler {

    private final ProxyServerSwitchCore core;

    /**
     * Creates a new ServerSwitchHandler.
     *
     * @param plugin the plugin instance
     */
    public ServerSwitchHandler(NovaChatVelocity plugin) {
        this.core = plugin.getServerSwitchCore();
    }

    /**
     * Handles server connected events after the player has connected.
     * Updates player state and handles channel transitions.
     *
     * @param event the server connected event
     */
    @Subscribe(order = PostOrder.NORMAL)
    public void onServerConnected(ServerConnectedEvent event) {
        core.onServerConnected(
                new VelocityProxyPlayer(event.getPlayer()),
                event.getServer().getServerInfo().getName());
    }
}
