package com.nova.link.channel;

import java.util.*;

/**
 * Encapsulates world filter logic for channels.
 * A world filter restricts channel membership to players in specific worlds.
 * 
 * Requirements: 6.1, 6.4
 * - Parse `allowed_worlds` from channel config
 * - Implement world membership check
 * - WHEN server channel configures `allowed_worlds` THEN NovaLink SHALL identify it as a world channel
 * - WHEN server channel has no `allowed_worlds` THEN NovaLink SHALL treat it as universal within that server
 */
public class WorldFilter {

    /** The set of allowed world names (case-sensitive) */
    private final Set<String> allowedWorlds;

    /**
     * Creates an empty world filter (no restrictions).
     */
    public WorldFilter() {
        this.allowedWorlds = new HashSet<>();
    }

    /**
     * Creates a world filter with the specified allowed worlds.
     *
     * @param allowedWorlds the list of allowed world names (null or empty means no filter)
     */
    public WorldFilter(List<String> allowedWorlds) {
        this.allowedWorlds = new HashSet<>();
        if (allowedWorlds != null) {
            for (String world : allowedWorlds) {
                if (world != null && !world.trim().isEmpty()) {
                    this.allowedWorlds.add(world.trim());
                }
            }
        }
    }

    /**
     * Creates a world filter with the specified allowed worlds.
     *
     * @param allowedWorlds the collection of allowed world names
     */
    public WorldFilter(Collection<String> allowedWorlds) {
        this.allowedWorlds = new HashSet<>();
        if (allowedWorlds != null) {
            for (String world : allowedWorlds) {
                if (world != null && !world.trim().isEmpty()) {
                    this.allowedWorlds.add(world.trim());
                }
            }
        }
    }

    /**
     * Parses a world filter from a channel configuration.
     * This is the primary factory method for creating WorldFilter instances from config.
     *
     * @param config the channel configuration
     * @return a WorldFilter instance
     */
    public static WorldFilter fromConfig(ChannelConfig config) {
        if (config == null) {
            return new WorldFilter();
        }
        return new WorldFilter(config.getAllowedWorlds());
    }

    /**
     * Parses a world filter from a channel.
     *
     * @param channel the channel
     * @return a WorldFilter instance
     */
    public static WorldFilter fromChannel(Channel channel) {
        if (channel == null) {
            return new WorldFilter();
        }
        return new WorldFilter(channel.getAllowedWorlds());
    }

    /**
     * Checks if this filter has any world restrictions.
     * A filter with no allowed worlds means no restrictions (all worlds allowed).
     *
     * @return true if there are world restrictions, false if all worlds are allowed
     */
    public boolean hasRestrictions() {
        return !allowedWorlds.isEmpty();
    }

    /**
     * Checks if a player in the given world should be a member of a channel with this filter.
     * 
     * Property 8: World Filter Membership
     * For any channel with `allowed_worlds` configured, a player should be a member 
     * if and only if their current world is in the allowed list.
     *
     * @param worldName the world name to check
     * @return true if the world is allowed (player should be member), false otherwise
     */
    public boolean isWorldAllowed(String worldName) {
        // If no restrictions, all worlds are allowed
        if (!hasRestrictions()) {
            return true;
        }
        
        // Null or empty world name is not allowed when there are restrictions
        if (worldName == null || worldName.trim().isEmpty()) {
            return false;
        }
        
        return allowedWorlds.contains(worldName.trim());
    }

    /**
     * Determines if a player should be a member of a channel based on their world.
     * This is the core membership check method.
     * 
     * Requirements: 6.1, 6.2, 6.3, 6.4
     * - Player in specified world -> should be member
     * - Player not in specified world -> should not be member
     * - No world filter -> all players allowed
     *
     * @param playerWorld the player's current world
     * @return true if the player should be a member based on world filter
     */
    public boolean shouldBeMember(String playerWorld) {
        return isWorldAllowed(playerWorld);
    }

    /**
     * Gets an unmodifiable view of the allowed worlds.
     *
     * @return set of allowed world names
     */
    public Set<String> getAllowedWorlds() {
        return Collections.unmodifiableSet(allowedWorlds);
    }

    /**
     * Gets the allowed worlds as a list (for serialization).
     *
     * @return list of allowed world names
     */
    public List<String> getAllowedWorldsList() {
        return new ArrayList<>(allowedWorlds);
    }

    /**
     * Adds a world to the allowed list.
     *
     * @param worldName the world name to add
     * @return true if the world was added (not already present)
     */
    public boolean addWorld(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return false;
        }
        return allowedWorlds.add(worldName.trim());
    }

    /**
     * Removes a world from the allowed list.
     *
     * @param worldName the world name to remove
     * @return true if the world was removed
     */
    public boolean removeWorld(String worldName) {
        if (worldName == null) {
            return false;
        }
        return allowedWorlds.remove(worldName.trim());
    }

    /**
     * Clears all world restrictions.
     */
    public void clearRestrictions() {
        allowedWorlds.clear();
    }

    /**
     * Gets the number of allowed worlds.
     *
     * @return count of allowed worlds
     */
    public int getWorldCount() {
        return allowedWorlds.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorldFilter that = (WorldFilter) o;
        return Objects.equals(allowedWorlds, that.allowedWorlds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowedWorlds);
    }

    @Override
    public String toString() {
        if (!hasRestrictions()) {
            return "WorldFilter{unrestricted}";
        }
        return "WorldFilter{allowedWorlds=" + allowedWorlds + "}";
    }
}
