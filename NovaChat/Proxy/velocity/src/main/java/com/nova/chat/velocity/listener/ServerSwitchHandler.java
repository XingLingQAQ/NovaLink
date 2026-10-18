package com.nova.chat.velocity.listener;

import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.proxy.engine.ProxyServerSwitchCore;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.platform.VelocityProxyPlayer;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;

/**
 * Handles server switching events for cross-server message routing.
 *
 * <p>When a player switches servers:
 * <ol>
 *   <li>notifies the backend about the server change</li>
 *   <li>updates the player state with the new server information</li>
 *   <li>requests the channel transition on the new server</li>
 * </ol>
 *
 * <p>The logic lives in the shared {@link ProxyServerSwitchCore}; this shell
 * keeps only the Velocity event subscription.
 *
 * <p>Requirements: 4.3, 5.3 - Cross-server message routing
 */
public class ServerSwitchHandler {

    private final NovaChatVelocity plugin;
    private final ProxyServerSwitchCore core;

    /**
     * Creates a new ServerSwitchHandler.
     *
     * @param plugin the plugin instance
     */
    public ServerSwitchHandler(NovaChatVelocity plugin) {
        this.plugin = plugin;
        this.core = plugin.getServerSwitchCore();
    }

    /**
     * Handles pre-connect events to prepare for a server switch: notifies the
     * backend about the pending move.
     *
     * @param event the server pre-connect event
     */
    @Subscribe(order = PostOrder.NORMAL)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        Optional<RegisteredServer> previousServer = player.getCurrentServer()
                .map(conn -> conn.getServer());

        if (previousServer.isPresent() && event.getResult().getServer().isPresent()) {
            core.notifyServerSwitch(
                    new VelocityProxyPlayer(player),
                    previousServer.get().getServerInfo().getName(),
                    event.getResult().getServer().get().getServerInfo().getName());
        }
    }

    /**
     * Handles server connected events after the player has connected: updates the
     * player state and handles the channel transition.
     *
     * @param event the server connected event
     */
    @Subscribe(order = PostOrder.NORMAL)
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();
        VelocityProxyPlayer handle = new VelocityProxyPlayer(player);

        com.nova.chat.velocity.chat.ChatListener chatListener = plugin.getChatListener();
        if (chatListener == null) {
            return;
        }

        PlayerChannelState state = chatListener.getOrCreateState(player);
        String previousServer = state.getCurrentServer();
        state.setCurrentServer(serverName);

        plugin.debug("Player " + player.getUsername()
                + " connected to server: " + serverName);

        // Handle channel transition based on server change
        core.handleChannelTransition(handle, previousServer, serverName);
    }
}
