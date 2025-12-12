package com.nova.chat.velocity.listener;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.chat.ChatListener;
import com.nova.chat.velocity.chat.PlayerChatState;
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
 * When a player switches servers:
 * 1. Notifies the backend about the server change
 * 2. Updates player state with new server information
 * 3. Handles channel transitions based on server-specific channels
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
     * Handles pre-connect events to prepare for server switch.
     * This is called before the player actually connects to the new server.
     *
     * @param event the server pre-connect event
     */
    @Subscribe(order = PostOrder.NORMAL)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        Optional<RegisteredServer> previousServer = player.getCurrentServer()
            .map(conn -> conn.getServer());
        
        if (previousServer.isPresent() && event.getResult().getServer().isPresent()) {
            String fromServer = previousServer.get().getServerInfo().getName();
            String toServer = event.getResult().getServer().get().getServerInfo().getName();
            
            plugin.debug("Player " + player.getUsername() + " switching from " + fromServer + " to " + toServer);
            
            // Notify backend about pending server switch
            notifyServerSwitch(player, fromServer, toServer);
        }
    }
    
    /**
     * Handles server connected events after the player has connected.
     * Updates player state and handles channel transitions.
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
            PlayerChatState state = chatListener.getOrCreateState(player);
            String previousServer = state.getCurrentServer();
            state.setCurrentServer(serverName);
            
            plugin.debug("Player " + player.getUsername() + " connected to server: " + serverName);
            
            // Handle channel transition based on server change
            handleChannelTransition(player, state, previousServer, serverName);
        }
    }
    
    /**
     * Notifies the backend about a server switch.
     *
     * @param player the player switching servers
     * @param fromServer the server the player is leaving
     * @param toServer the server the player is joining
     */
    private void notifyServerSwitch(Player player, String fromServer, String toServer) {
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            return;
        }
        
        // Send a channel action packet to notify backend about server switch
        // The backend can use this to update player state and handle routing
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.LEAVE, "");
        packet.addExtra("action_type", "server_switch");
        packet.addExtra("player_uuid", player.getUniqueId().toString());
        packet.addExtra("player_name", player.getUsername());
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
     * @param previousServer the previous server (may be null for initial join)
     * @param newServer the new server
     */
    private void handleChannelTransition(Player player, PlayerChatState state, 
                                         String previousServer, String newServer) {
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            return;
        }
        
        String currentChannel = state.getActiveChannel();
        
        // If this is the initial connection (no previous server), just join default channel
        if (previousServer == null) {
            joinDefaultChannel(player, state, newServer);
            return;
        }
        
        // For server switches, we need to check if the current channel is server-specific
        // The backend will handle the actual channel membership based on scope
        
        // Send a join request for the current channel on the new server
        // The backend will determine if the player can stay in the channel
        ChannelActionPacket joinPacket = new ChannelActionPacket(ChannelAction.JOIN, currentChannel);
        joinPacket.addExtra("player_uuid", player.getUniqueId().toString());
        joinPacket.addExtra("player_name", player.getUsername());
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
    private void joinDefaultChannel(Player player, PlayerChatState state, String server) {
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
