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
 * 1. Notifies the backend about the server change
 * 2. Updates player state with new server information
 * 3. Handles channel transitions based on server-specific channels
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
            
            // Notify backend about server switch
            notifyServerSwitch(player, fromServerName, toServerName);
            
            // Handle channel transition
            ChatListener chatListener = plugin.getChatListener();
            if (chatListener != null) {
                PlayerChannelState state = chatListener.getOrCreateState(player);
                handleChannelTransition(player, state, fromServerName, toServerName);
            }
        }
    }
    
    /**
     * Handles server connected events after the player has connected.
     * Updates player state and handles initial channel join.
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
            String previousServer = state.getCurrentServer();
            state.setCurrentServer(serverName);
            
            plugin.debug("Player " + player.getName() + " connected to server: " + serverName);
            
            // If this is the initial connection (no previous server), join default channel
            if (previousServer == null) {
                joinDefaultChannel(player, state, serverName);
                // UX-DESIGN §8.1: push the shared first-join welcome line once
                // per proxy session (Bungee has no hasPlayedBefore).
                chatListener.pushWelcomeIfFirst(player);
            }
        }
    }
    
    /**
     * Notifies the backend about a server switch.
     *
     * @param player the player switching servers
     * @param fromServer the server the player is leaving
     * @param toServer the server the player is joining
     */
    private void notifyServerSwitch(ProxiedPlayer player, String fromServer, String toServer) {
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            return;
        }
        
        // Send a channel action packet to notify backend about server switch
        // The backend can use this to update player state and handle routing
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.LEAVE, "");
        packet.addExtra("action_type", "server_switch");
        packet.addExtra("player_uuid", player.getUniqueId().toString());
        packet.addExtra("player_name", player.getName());
        packet.addExtra("from_server", fromServer);
        packet.addExtra("to_server", toServer);
        
        plugin.getNetworkClient().sendPacket(packet);
        plugin.debug("Notified backend about server switch: " + fromServer + " -> " + toServer);
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
     * @param previousServer the previous server
     * @param newServer the new server
     */
    private void handleChannelTransition(ProxiedPlayer player, PlayerChannelState state, 
                                         String previousServer, String newServer) {
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
        joinPacket.addExtra("previous_server", previousServer);
        
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
