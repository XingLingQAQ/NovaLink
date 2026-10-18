package com.nova.chat.bungee.listener;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.chat.ChatListener;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

/**
 * Handles server switching events for cross-server message routing.
 *
 * When a player switches servers:
 * 1. Updates player state with new server information
 * 2. Handles channel transitions based on server-specific channels
 *
 * <p>The backend learns about the new server from the JOIN packet below (its
 * {@code server} extra), which is a real channel action it understands. There
 * is no separate "server switch" packet: {@code ChannelAction} has no value for
 * it and {@code StarLink}'s {@code ChannelActionHandler} only dispatches the
 * declared actions.
 *
 * Requirements: 4.3, 5.3 - Cross-server message routing
 */
public class ServerSwitchHandler implements Listener {

    private final NovaChatBungee plugin;

    /**
     * Creates a new ServerSwitchHandler.
     *
     * @param plugin the plugin instance
     */
    public ServerSwitchHandler(NovaChatBungee plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles server switch events.
     * This is called when a player switches from one server to another.
     *
     * @param event the server switch event
     */
    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        ProxiedPlayer player = event.getPlayer();
        ServerInfo fromServer = event.getFrom();

        if (fromServer != null && player.getServer() != null) {
            String fromServerName = fromServer.getName();
            String toServerName = player.getServer().getInfo().getName();

            plugin.debug("Player " + player.getName() + " switching from " + fromServerName + " to " + toServerName);

            // Handle channel transition
            ChatListener chatListener = plugin.getChatListener();
            if (chatListener != null) {
                PlayerChannelState state = chatListener.getOrCreateState(player);
                handleChannelTransition(player, state, toServerName);
            }
        }
    }

    /**
     * Handles server connected events after the player has connected.
     * Updates player state and handles initial channel join.
     *
     * <p><b>Listener order:</b> Bungee's {@code EventBus} stores same-priority
     * listeners in a {@code HashMap}, so the order between this handler and
     * {@link ChatListener#onServerConnected(ServerConnectedEvent)} varies per JVM
     * start. The first-connection decision therefore comes from
     * {@link ChatListener#claimFirstConnection(java.util.UUID)}, an atomic
     * per-session claim, not from {@code state.getCurrentServer() == null}
     * (both handlers write {@code currentServer}).
     *
     * @param event the server connected event
     */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        String serverName = event.getServer().getInfo().getName();

        // Update player state with new server
        ChatListener chatListener = plugin.getChatListener();
        if (chatListener != null) {
            PlayerChannelState state = chatListener.getOrCreateState(player);
            state.setCurrentServer(serverName);

            plugin.debug("Player " + player.getName() + " connected to server: " + serverName);

            if (chatListener.claimFirstConnection(player.getUniqueId())) {
                joinDefaultChannel(player, state, serverName);
                // UX-DESIGN §8.1: push the shared first-join welcome line once
                // per proxy session (Bungee has no hasPlayedBefore).
                chatListener.pushWelcomeIfFirst(player);
            }
        }
    }

    /**
     * Handles channel transitions when a player switches servers.
     *
     * For SERVER-scoped channels:
     * - Player may need to leave server-specific channels
     * - Player may need to join new server-specific channels
     *
     * For GLOBAL-scoped channels:
     * - Player remains in the channel across servers
     *
     * @param player the player
     * @param state the player's chat state
     * @param newServer the server the player is joining
     */
    private void handleChannelTransition(ProxiedPlayer player, PlayerChannelState state, String newServer) {
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            return;
        }

        String currentChannel = state.getActiveChannel();

        // Send a join request for the current channel on the new server
        // The backend will determine if the player can stay in the channel
        ChannelActionPacket joinPacket = new ChannelActionPacket(ChannelAction.JOIN, currentChannel);
        joinPacket.addExtra("player_uuid", player.getUniqueId().toString());
        joinPacket.addExtra("player_name", player.getName());
        joinPacket.addExtra("server", newServer);

        plugin.getNetworkClient().sendPacket(joinPacket);
        plugin.debug("Requested channel join after server switch: " + currentChannel + " on " + newServer);
    }

    /**
     * Joins the default channel for a player on initial connection.
     *
     * @param player the player
     * @param state the player's chat state
     * @param server the server name
     */
    private void joinDefaultChannel(ProxiedPlayer player, PlayerChannelState state, String server) {
        String defaultChannel = plugin.getPluginConfig().getDefaultChannel();
        state.setActiveChannel(defaultChannel);

        if (plugin.getNetworkClient() != null && plugin.getNetworkClient().isAuthenticated()) {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, defaultChannel);
            packet.addExtra("player_uuid", player.getUniqueId().toString());
            packet.addExtra("player_name", player.getName());
            packet.addExtra("server", server);

            plugin.getNetworkClient().sendPacket(packet);
            plugin.debug("Player " + player.getName() + " joined default channel: " + defaultChannel);
        }
    }
}
