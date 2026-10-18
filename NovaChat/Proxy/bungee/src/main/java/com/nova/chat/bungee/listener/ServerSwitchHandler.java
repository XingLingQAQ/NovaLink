package com.nova.chat.bungee.listener;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.chat.ChatListener;
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
 * <p>On a server connection it records the new server and then joins the default
 * channel (first connection of the session) or re-requests the active channel.
 *
 * <p>The first-connection decision is the shared atomic claim
 * ({@link com.nova.chat.proxy.chat.ProxyChatCore#claimFirstConnection}), not
 * {@code getCurrentServer() == null}. BungeeCord's {@code EventBus} keeps
 * same-priority listeners in a {@code HashMap}, so the execution order between
 * this handler and the chat listener is JVM-start random; on the bad order the
 * default-channel JOIN and the welcome message were both skipped.
 *
 * <p>The backend learns about the new server from the JOIN packet's
 * {@code server} extra; there is no separate "server switch" packet.
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
     * Handles server switch events: re-requests the active channel on the new
     * server.
     *
     * @param event the server switch event
     */
    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        ProxiedPlayer player = event.getPlayer();
        ServerInfo fromServer = event.getFrom();

        if (fromServer != null && player.getServer() != null) {
            plugin.debug("Player " + player.getName() + " switching from "
                    + fromServer.getName() + " to " + player.getServer().getInfo().getName());

            ChatListener chatListener = plugin.getChatListener();
            if (chatListener != null) {
                PlayerChannelState state = chatListener.getOrCreateState(player);
                core.handleChannelTransition(new BungeeProxyPlayer(player), state,
                        player.getServer().getInfo().getName());
            }
        }
    }

    /**
     * Handles server connected events: updates the player state and joins the
     * default channel (first connection) or handles the channel transition.
     *
     * @param event the server connected event
     */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        String serverName = event.getServer().getInfo().getName();

        BungeeProxyPlayer handle = new BungeeProxyPlayer(player);
        boolean first = core.onServerConnected(handle, serverName);

        // UX-DESIGN §8.1: push the shared first-join welcome line once per proxy
        // session (Bungee has no hasPlayedBefore).
        if (first) {
            ChatListener chatListener = plugin.getChatListener();
            if (chatListener != null) {
                chatListener.pushWelcomeIfFirst(player);
            }
        }
    }
}
