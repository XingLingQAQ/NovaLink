package com.nova.chat.velocity.listener;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.chat.ChatListener;
import com.nova.chat.client.state.PlayerChannelState;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;

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
public class ServerSwitchHandler {

    private final NovaChatVelocity plugin;

    /**
     * Creates a new ServerSwitchHandler.
     *
     * @param plugin the plugin instance
     */
    public ServerSwitchHandler(NovaChatVelocity plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles server connected events after the player has connected.
     * Updates player state and handles channel transitions.
     *
     * <p>The first-connection decision comes from
     * {@link ChatListener#claimFirstConnection(java.util.UUID)}, an atomic
     * per-session claim, deliberately <em>not</em> from
     * {@code PlayerChannelState#getCurrentServer() == null}: both this handler
     * and {@link ChatListener#onServerConnected(ServerConnectedEvent)} write
     * {@code currentServer}, and their execution order is not guaranteed.
     *
     * @param event the server connected event
     */
    @Subscribe(order = PostOrder.NORMAL)
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        // Update player state with new server
        ChatListener chatListener = plugin.getChatListener();
        if (chatListener != null) {
            PlayerChannelState state = chatListener.getOrCreateState(player);
            state.setCurrentServer(serverName);

            plugin.debug("Player " + player.getUsername() + " connected to server: " + serverName);

            if (chatListener.claimFirstConnection(player.getUniqueId())) {
                joinDefaultChannel(player, state, serverName);
            } else {
                handleChannelTransition(player, state, serverName);
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
    private void handleChannelTransition(Player player, PlayerChannelState state, String newServer) {
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            return;
        }

        String currentChannel = state.getActiveChannel();

        // Send a join request for the current channel on the new server
        // The backend will determine if the player can stay in the channel
        ChannelActionPacket joinPacket = new ChannelActionPacket(ChannelAction.JOIN, currentChannel);
        joinPacket.addExtra("player_uuid", player.getUniqueId().toString());
        joinPacket.addExtra("player_name", player.getUsername());
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
    private void joinDefaultChannel(Player player, PlayerChannelState state, String server) {
        String defaultChannel = plugin.getConfig().getDefaultChannel();
        state.setActiveChannel(defaultChannel);

        if (plugin.getNetworkClient() != null && plugin.getNetworkClient().isAuthenticated()) {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, defaultChannel);
            packet.addExtra("player_uuid", player.getUniqueId().toString());
            packet.addExtra("player_name", player.getUsername());
            packet.addExtra("server", server);

            plugin.getNetworkClient().sendPacket(packet);
            plugin.debug("Player " + player.getUsername() + " joined default channel: " + defaultChannel);
        }
    }
}
