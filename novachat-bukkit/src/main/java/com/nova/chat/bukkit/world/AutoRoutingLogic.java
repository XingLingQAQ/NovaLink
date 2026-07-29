package com.nova.chat.bukkit.world;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core auto-routing logic for world-based channel switching.
 * This class is separated from WorldMonitor to enable property-based testing
 * without Bukkit dependencies.
 * 
 * Requirements: 9.1, 9.3
 * - WHEN player changes worlds THEN detect applicable channels
 * - WHEN new world not in any special channel THEN fall back to unrestricted server channel
 * 
 * Property 12: Auto-Routing World Change
 * For any player changing worlds, they should automatically join the most specific 
 * applicable channel for the new world.
 */
public class AutoRoutingLogic {

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
    
    /** Default channel to fall back to when no specific channel applies */
    private final String defaultChannel;

    /**
     * Creates a new AutoRoutingLogic instance.
     *
     * @param defaultChannel the default channel to use when no specific channel applies
     */
    public AutoRoutingLogic(String defaultChannel) {
        this.defaultChannel = defaultChannel != null ? defaultChannel : "local";
        this.worldChannelMappings = new ConcurrentHashMap<>();
        this.channelWorldMappings = new ConcurrentHashMap<>();
    }

    /**
     * Registers a world-channel mapping.
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
        for (String world : allowedWorlds) {
            if (world != null && !world.trim().isEmpty()) {
                worldSet.add(world.trim());
            }
        }
        
        if (worldSet.isEmpty()) {
            return;
        }
        
        channelWorldMappings.put(channelId, worldSet);
        
        // Store world -> channels mapping (reverse)
        for (String world : worldSet) {
            worldChannelMappings.computeIfAbsent(world, k -> ConcurrentHashMap.newKeySet())
                    .add(channelId);
        }
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
    }

    /**
     * Clears all world-channel mappings.
     */
    public void clearMappings() {
        worldChannelMappings.clear();
        channelWorldMappings.clear();
    }

    /**
     * Determines the target channel for a player after a world change.
     * 
     * Property 12: Auto-Routing World Change
     * For any player changing worlds, they should automatically join the most specific 
     * applicable channel for the new world.
     *
     * @param currentChannel the player's current channel
     * @param newWorld the world the player is moving to
     * @return the routing result containing the target channel and whether a switch is needed
     */
    public RoutingDecision determineTargetChannel(String currentChannel, String newWorld) {
        if (newWorld == null || newWorld.trim().isEmpty()) {
            return new RoutingDecision(currentChannel, false, "Invalid world name");
        }
        
        String trimmedWorld = newWorld.trim();
        
        // Find channels that apply to the new world
        Set<String> applicableChannels = worldChannelMappings.get(trimmedWorld);
        
        // Check if current channel is world-restricted
        boolean currentIsWorldRestricted = isWorldRestrictedChannel(currentChannel);
        boolean currentAllowsNewWorld = isWorldAllowedForChannel(currentChannel, trimmedWorld);
        
        // Case 1: New world has specific channels
        if (applicableChannels != null && !applicableChannels.isEmpty()) {
            // If current channel is one of the applicable channels, stay
            if (applicableChannels.contains(currentChannel)) {
                return new RoutingDecision(currentChannel, false, "Already in applicable channel");
            }
            
            // Switch to the first applicable channel (could be enhanced with priority)
            String targetChannel = applicableChannels.iterator().next();
            return new RoutingDecision(targetChannel, true, "Switching to world-specific channel");
        }
        
        // Case 2: New world has no specific channels
        if (currentIsWorldRestricted && !currentAllowsNewWorld) {
            // Current channel doesn't allow this world, fall back to default
            return new RoutingDecision(defaultChannel, true, "Falling back to default channel");
        }
        
        // Case 3: Current channel is not world-restricted or allows the new world
        return new RoutingDecision(currentChannel, false, "No channel switch needed");
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
        
        return allowedWorlds.contains(worldName.trim());
    }

    /**
     * Gets all channels applicable to a specific world.
     *
     * @param worldName the world name
     * @return set of channel IDs, or empty set if none
     */
    public Set<String> getChannelsForWorld(String worldName) {
        if (worldName == null) {
            return Collections.emptySet();
        }
        Set<String> channels = worldChannelMappings.get(worldName.trim());
        return channels != null ? Collections.unmodifiableSet(channels) : Collections.emptySet();
    }

    /**
     * Gets all worlds allowed for a specific channel.
     *
     * @param channelId the channel ID
     * @return set of world names, or empty set if no restrictions
     */
    public Set<String> getWorldsForChannel(String channelId) {
        if (channelId == null) {
            return Collections.emptySet();
        }
        Set<String> worlds = channelWorldMappings.get(channelId);
        return worlds != null ? Collections.unmodifiableSet(worlds) : Collections.emptySet();
    }

    /**
     * Gets the default channel.
     *
     * @return the default channel ID
     */
    public String getDefaultChannel() {
        return defaultChannel;
    }

    /**
     * Gets the number of registered world-channel mappings.
     *
     * @return the count of world-restricted channels
     */
    public int getMappingCount() {
        return channelWorldMappings.size();
    }

    /**
     * Represents the result of a routing decision.
     */
    public static class RoutingDecision {
        private final String targetChannel;
        private final boolean switchRequired;
        private final String reason;

        public RoutingDecision(String targetChannel, boolean switchRequired, String reason) {
            this.targetChannel = targetChannel;
            this.switchRequired = switchRequired;
            this.reason = reason;
        }

        public String getTargetChannel() {
            return targetChannel;
        }

        public boolean isSwitchRequired() {
            return switchRequired;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return "RoutingDecision{" +
                    "targetChannel='" + targetChannel + '\'' +
                    ", switchRequired=" + switchRequired +
                    ", reason='" + reason + '\'' +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RoutingDecision that = (RoutingDecision) o;
            return switchRequired == that.switchRequired &&
                    Objects.equals(targetChannel, that.targetChannel);
        }

        @Override
        public int hashCode() {
            return Objects.hash(targetChannel, switchRequired);
        }
    }
}
