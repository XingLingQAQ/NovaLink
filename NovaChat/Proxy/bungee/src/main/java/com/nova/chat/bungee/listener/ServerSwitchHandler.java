package com.nova.chat.bungee.listener;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.platform.BungeeProxyPlayer;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.proxy.engine.ProxyServerSwitchCore;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

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
 * keeps only the BungeeCord event subscription.
 *
 * <p>Requirements: 4.3, 5.3 - Cross-server message routing
 */
public class ServerSwitchHandler implements Listener {

    private final NovaChatBungee plugin;
    private final ProxyServerSwitchCore core;

    /**
     * Creates a new ServerSwitchHandler.
     *
     * @param plugin the plugin instance
     */
    public ServerSwitchHandler(NovaChatBungee plugin) {
        this.plugin = plugin;
        this.core = plugin.getServerSwitchCore();
    }

    /**
     * Handles server switch events: notifies the backend and handles the channel
     * transition.
     *
     * @param event the server switch event
     */
    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        ProxiedPlayer player = event.getPlayer();
        ServerInfo fromServer = event.getFrom();

        if (fromServer != null && player.getServer() != null) {
            core.onServerSwitch(new BungeeProxyPlayer(player),
                    fromServer.getName(),
                    player.getServer().getInfo().getName());
        }
    }

    /**
     * Handles server connected events: updates the player state and, on the
     * initial connection, joins the default channel and pushes the welcome line.
     *
     * @param event the server connected event
     */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        String serverName = event.getServer().getInfo().getName();

        com.nova.chat.bungee.chat.ChatListener chatListener = plugin.getChatListener();
        if (chatListener == null) {
            return;
        }

        PlayerChannelState state = chatListener.getOrCreateState(player);
        String previousServer = state.getCurrentServer();
        state.setCurrentServer(serverName);

        plugin.debug("Player " + player.getName()
                + " connected to server: " + serverName);

        // If this is the initial connection (no previous server), join default
        // channel and push the first-join welcome line (UX-DESIGN §8.1).
        if (previousServer == null) {
            core.joinDefaultChannel(new BungeeProxyPlayer(player), state, serverName);
            chatListener.pushWelcomeIfFirst(player);
        }
    }
}
