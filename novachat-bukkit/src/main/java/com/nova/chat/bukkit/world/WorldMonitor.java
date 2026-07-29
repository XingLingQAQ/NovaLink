package com.nova.chat.bukkit.world;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors world changes and automatically routes players to appropriate channels.
 * 
 * Requirements: 6.2, 6.3, 9.1-9.4
 * - WHEN player in specified world THEN NovaChat SHALL auto-join world channel
 * - WHEN player leaves specified world THEN NovaChat SHALL auto-leave and join default channel
 * - WHEN player changes worlds THEN NovaChat SHALL detect applicable channels
 * - THE NovaChat SHALL send notification on auto-switch
 */
public class WorldMonitor implements Listener {

    private final NovaChatBukkit plugin;
    
    /** 
     * World to channel mappings.
     * Key: world name, Value: set of channel IDs that apply to this world
     */
    private final Map<String, Set<String>> worldChannelMappings;
    
    /**
     * Channel to worlds mappings (reverse lookup).
     * Key: channel ID, Value: set of world names where this channel applies
     */
    private final Map<String, Set<String>> channelWorldMappings;

    /**
     * Creates a new WorldMonitor.
     *
     * @param plugin the plugin instance
     */
    public WorldMonitor(NovaChatBukkit plugin) {
        this.plugin = plugin;
        this.worldChannelMappings = new ConcurrentHashMap<>();
        this.channelWorldMappings = new ConcurrentHashMap<>();
    }

    /**
     * Handles player world change events.
     * Automatically switches the player to the appropriate channel for the new world.
     *
     * Requirements: 9.1, 9.3
     * - WHEN player changes worlds THEN detect applicable channels
     * - WHEN new world not in any special channel THEN fall back to unrestricted server channel
     *
     * @param event the world change event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        String fromWorld = event.getFrom().getName();
        String toWorld = player.getWorld().getName();
        
        plugin.debug("Player " + player.getName() + " changed world: " + fromWorld + " -> " + toWorld);
        
        // Check if player has bypass permission
        if (player.hasPermission("novachat.bypass.world")) {
            plugin.debug("Player " + player.getName() + " has bypass permission, skipping auto-routing");
            return;
        }
        
        // Find the most specific applicable channel for the new world
        String applicableChannel = findApplicableChannel(toWorld);
        
        // Get current player state
        PlayerChannelState state = plugin.getChatInterceptor().getState(player.getUniqueId());
        if (state == null) {
            state = plugin.getChatInterceptor().getOrCreateState(player);
        }
        
        String currentChannel = state.getActiveChannel();
        
        // Check if we need to switch channels
        if (applicableChannel != null && !applicableChannel.equals(currentChannel)) {
            // Check if current channel is a world-restricted channel that doesn't include the new world
            boolean shouldSwitch = shouldSwitchChannel(currentChannel, toWorld);
            
            if (shouldSwitch) {
                switchPlayerChannel(player, currentChannel, applicableChannel);
            }
        } else if (applicableChannel == null) {
            // No specific channel for this world, check if current channel is world-restricted
            if (isWorldRestrictedChannel(currentChannel) && !isWorldAllowedForChannel(currentChannel, toWorld)) {
                // Fall back to default channel
                String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();
                switchPlayerChannel(player, currentChannel, defaultChannel);
            }
        }
    }

    /**
     * Finds the most specific applicable channel for a given world.
     * Priority: world-specific channel > unrestricted server channel
     *
     * @param worldName the world name
     * @return the channel ID, or null if no specific channel found
     */
    public String findApplicableChannel(String worldName) {
        if (worldName == null || worldName.isEmpty()) {
            return null;
        }
        
        Set<String> channels = worldChannelMappings.get(worldName);
        if (channels == null || channels.isEmpty()) {
            return null;
        }
        
        // Return the first applicable channel (could be enhanced with priority logic)
        return channels.iterator().next();
    }

    /**
     * Determines if the player should switch from their current channel.
     *
     * @param currentChannel the current channel ID
     * @param newWorld the new world name
     * @return true if the player should switch channels
     */
    private boolean shouldSwitchChannel(String currentChannel, String newWorld) {
        // If current channel is world-restricted and new world is not in the allowed list
        if (isWorldRestrictedChannel(currentChannel)) {
            return !isWorldAllowedForChannel(currentChannel, newWorld);
        }
        
        // If there's a world-specific channel for the new world, switch to it
        Set<String> worldChannels = worldChannelMappings.get(newWorld);
        return worldChannels != null && !worldChannels.isEmpty();
    }

    /**
     * Checks if a channel has world restrictions.
     *
     * @param channelId the channel ID
     * @return true if the channel is world-restricted
     */
    public boolean isWorldRestrictedChannel(String channelId) {
        if (channelId == null) {
            return false;
        }
        Set<String> worlds = channelWorldMappings.get(channelId);
        return worlds != null && !worlds.isEmpty();
    }

    /**
     * Checks if a world is allowed for a specific channel.
     *
     * @param channelId the channel ID
     * @param worldName the world name
     * @return true if the world is allowed (or channel has no restrictions)
     */
    public boolean isWorldAllowedForChannel(String channelId, String worldName) {
        if (channelId == null || worldName == null) {
            return false;
        }
        
        Set<String> allowedWorlds = channelWorldMappings.get(channelId);
        if (allowedWorlds == null || allowedWorlds.isEmpty()) {
            // No restrictions, all worlds allowed
            return true;
        }
        
        return allowedWorlds.contains(worldName);
    }

    /**
     * Switches a player from one channel to another.
     * Sends leave/join packets to the backend and notifies the player.
     *
     * Requirements: 9.4
     * - THE NovaChat SHALL send notification on auto-switch
     *
     * @param player the player
     * @param fromChannel the channel to leave
     * @param toChannel the channel to join
     */
    private void switchPlayerChannel(Player player, String fromChannel, String toChannel) {
        plugin.debug("Auto-switching " + player.getName() + " from " + fromChannel + " to " + toChannel);
        
        // Send leave packet for old channel
        if (fromChannel != null && plugin.getNetworkClient().isAuthenticated()) {
            ChannelActionPacket leavePacket = new ChannelActionPacket(
                ChannelAction.LEAVE,
                fromChannel,
                null
            );
            // Pass player info via extra map
            leavePacket.addExtra("player_id", player.getUniqueId().toString());
            leavePacket.addExtra("player_name", player.getName());
            leavePacket.addExtra("auto_route", "true");
            plugin.getNetworkClient().sendPacket(leavePacket);
        }
        
        // Send join packet for new channel
        if (toChannel != null && plugin.getNetworkClient().isAuthenticated()) {
            ChannelActionPacket joinPacket = new ChannelActionPacket(
                ChannelAction.JOIN,
                toChannel,
                null
            );
            // Pass player info via extra map
            joinPacket.addExtra("player_id", player.getUniqueId().toString());
            joinPacket.addExtra("player_name", player.getName());
            joinPacket.addExtra("auto_route", "true");
            plugin.getNetworkClient().sendPacket(joinPacket);
        }
        
        // Update local state
        plugin.getChatInterceptor().setPlayerChannel(player, toChannel);
        
        // Notify player (Requirement 9.4)
        String message = formatSwitchMessage(fromChannel, toChannel);
        player.sendMessage(message);
    }

    /**
     * Formats the channel switch notification message.
     *
     * @param fromChannel the old channel
     * @param toChannel the new channel
     * @return the formatted message
     */
    private String formatSwitchMessage(String fromChannel, String toChannel) {
        String prefix = plugin.getNovaChatConfig().getPrefix();
        return prefix + "§e已自动切换频道: §7" + fromChannel + " §e-> §a" + toChannel;
    }

    /**
     * Registers a world-channel mapping.
     * Called when channel configurations are loaded/synced from the backend.
     *
     * @param channelId the channel ID
     * @param allowedWorlds the list of allowed worlds for this channel
     */
    public void registerWorldChannel(String channelId, List<String> allowedWorlds) {
        if (channelId == null || allowedWorlds == null || allowedWorlds.isEmpty()) {
            return;
        }
        
        // Store channel -> worlds mapping
        Set<String> worldSet = ConcurrentHashMap.newKeySet();
        worldSet.addAll(allowedWorlds);
        channelWorldMappings.put(channelId, worldSet);
        
        // Store world -> channels mapping (reverse)
        for (String world : allowedWorlds) {
            worldChannelMappings.computeIfAbsent(world, k -> ConcurrentHashMap.newKeySet())
                    .add(channelId);
        }
        
        plugin.debug("Registered world channel: " + channelId + " -> " + allowedWorlds);
    }

    /**
     * Unregisters a world-channel mapping.
     *
     * @param channelId the channel ID to unregister
     */
    public void unregisterWorldChannel(String channelId) {
        if (channelId == null) {
            return;
        }
        
        Set<String> worlds = channelWorldMappings.remove(channelId);
        if (worlds != null) {
            for (String world : worlds) {
                Set<String> channels = worldChannelMappings.get(world);
                if (channels != null) {
                    channels.remove(channelId);
                    if (channels.isEmpty()) {
                        worldChannelMappings.remove(world);
                    }
                }
            }
        }
        
        plugin.debug("Unregistered world channel: " + channelId);
    }

    /**
     * Clears all world-channel mappings.
     */
    public void clearMappings() {
        worldChannelMappings.clear();
        channelWorldMappings.clear();
        plugin.debug("Cleared all world-channel mappings");
    }

    /**
     * Gets all channels applicable to a specific world.
     *
     * @param worldName the world name
     * @return set of channel IDs, or empty set if none
     */
    public Set<String> getChannelsForWorld(String worldName) {
        Set<String> channels = worldChannelMappings.get(worldName);
        return channels != null ? Collections.unmodifiableSet(channels) : Collections.emptySet();
    }

    /**
     * Gets all worlds allowed for a specific channel.
     *
     * @param channelId the channel ID
     * @return set of world names, or empty set if no restrictions
     */
    public Set<String> getWorldsForChannel(String channelId) {
        Set<String> worlds = channelWorldMappings.get(channelId);
        return worlds != null ? Collections.unmodifiableSet(worlds) : Collections.emptySet();
    }

    /**
     * Gets the number of registered world-channel mappings.
     *
     * @return the count of world-restricted channels
     */
    public int getMappingCount() {
        return channelWorldMappings.size();
    }
}
