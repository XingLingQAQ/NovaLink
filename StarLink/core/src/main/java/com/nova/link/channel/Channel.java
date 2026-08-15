package com.nova.link.channel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a chat channel in the NovaLink system.
 * Channels are the basic unit of message isolation - players only receive
 * messages from channels they have joined.
 * 
 * Requirements: 3.1, 3.4
 */
public class Channel {

    /** Unique identifier for the channel (e.g., "NC-5A3F" for private channels) */
    private final String id;
    
    /** Display name for the channel (used in logs and admin panel) */
    private String displayName;
    
    /** Channel scope determining message routing behavior */
    private final ChannelScope scope;
    
    /** Client ID this channel belongs to (null for GLOBAL channels) */
    private final String clientId;
    
    /** Permission node required to join this channel */
    private String permission;
    
    /** Maximum number of members allowed */
    private int maxCapacity;
    
    /** World filter - if set, only players in these worlds can be members */
    private List<String> allowedWorlds;
    
    /** Password for private channels */
    private String password;
    
    /** Owner UUID for private channels */
    private UUID ownerId;

    /** Slow mode: minimum seconds between two messages from the same player (0 = disabled) */
    private volatile int slowModeSeconds;

    /** Set of member UUIDs currently in this channel */
    private final Set<UUID> members;
    
    /** Creation timestamp */
    private final long createdAt;

    /**
     * Copy constructor. Produces a defensive copy of the given channel,
     * including a fresh members set and allowed-worlds list so mutations to
     * the original (or the copy) do not leak across the boundary.
     *
     * @param other the channel to copy (must not be null)
     */
    public Channel(Channel other) {
        Objects.requireNonNull(other, "Channel to copy cannot be null");
        this.id = other.id;
        this.displayName = other.displayName;
        this.scope = other.scope;
        this.clientId = other.clientId;
        this.permission = other.permission;
        this.maxCapacity = other.maxCapacity;
        this.allowedWorlds = other.allowedWorlds != null
                ? new ArrayList<>(other.allowedWorlds) : new ArrayList<>();
        this.password = other.password;
        this.ownerId = other.ownerId;
        this.slowModeSeconds = other.slowModeSeconds;
        this.members = ConcurrentHashMap.newKeySet();
        if (other.members != null) {
            this.members.addAll(other.members);
        }
        this.createdAt = other.createdAt;
    }

    /**
     * Creates a new channel with the specified parameters.
     *
     * @param id the unique channel ID
     * @param displayName the display name
     * @param scope the channel scope
     * @param clientId the owning client ID (null for GLOBAL)
     */
    public Channel(String id, String displayName, ChannelScope scope, String clientId) {
        this.id = Objects.requireNonNull(id, "Channel ID cannot be null");
        this.displayName = displayName != null ? displayName : id;
        this.scope = Objects.requireNonNull(scope, "Channel scope cannot be null");
        this.clientId = clientId;
        this.maxCapacity = 100;
        this.allowedWorlds = new ArrayList<>();
        this.members = ConcurrentHashMap.newKeySet();
        this.createdAt = System.currentTimeMillis();
        
        // Validate: GLOBAL channels should not have a clientId
        if (scope == ChannelScope.GLOBAL && clientId != null) {
            throw new IllegalArgumentException("GLOBAL channels cannot have a clientId");
        }
        
        // Validate: SERVER and PRIVATE channels must have a clientId
        if ((scope == ChannelScope.SERVER || scope == ChannelScope.PRIVATE) && clientId == null) {
            throw new IllegalArgumentException("SERVER and PRIVATE channels must have a clientId");
        }
    }

    /**
     * Adds a member to this channel.
     *
     * @param playerId the player UUID to add
     * @return true if the player was added, false if already a member or at capacity
     */
    public synchronized boolean addMember(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        if (maxCapacity > 0 && members.size() >= maxCapacity) {
            return false;
        }
        return members.add(playerId);
    }

    /**
     * Removes a member from this channel.
     *
     * @param playerId the player UUID to remove
     * @return true if the player was removed, false if not a member
     */
    public boolean removeMember(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        return members.remove(playerId);
    }

    /**
     * Checks if a player is a member of this channel.
     *
     * @param playerId the player UUID to check
     * @return true if the player is a member
     */
    public boolean isMember(UUID playerId) {
        return playerId != null && members.contains(playerId);
    }

    /**
     * Gets an unmodifiable view of the channel members.
     *
     * @return set of member UUIDs
     */
    public Set<UUID> getMembers() {
        return Collections.unmodifiableSet(members);
    }

    /**
     * Gets the current member count.
     *
     * @return number of members
     */
    public int getMemberCount() {
        return members.size();
    }

    /**
     * Checks if this channel has world restrictions.
     *
     * @return true if allowedWorlds is not empty
     */
    public boolean hasWorldFilter() {
        return allowedWorlds != null && !allowedWorlds.isEmpty();
    }

    /**
     * Checks if a world is allowed for this channel.
     *
     * @param worldName the world name to check
     * @return true if the world is allowed or no filter is set
     */
    public boolean isWorldAllowed(String worldName) {
        if (!hasWorldFilter()) {
            return true;
        }
        return worldName != null && allowedWorlds.contains(worldName);
    }

    // Getters and setters

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName != null ? displayName : id;
    }

    public ChannelScope getScope() {
        return scope;
    }

    public String getClientId() {
        return clientId;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public List<String> getAllowedWorlds() {
        return allowedWorlds != null ? Collections.unmodifiableList(allowedWorlds) : Collections.emptyList();
    }

    public void setAllowedWorlds(List<String> allowedWorlds) {
        this.allowedWorlds = allowedWorlds != null ? new ArrayList<>(allowedWorlds) : new ArrayList<>();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * @return minimum seconds between two messages from the same player;
     *         {@code 0} means slow mode is disabled
     */
    public int getSlowModeSeconds() {
        return slowModeSeconds;
    }

    public void setSlowModeSeconds(int slowModeSeconds) {
        this.slowModeSeconds = Math.max(0, slowModeSeconds);
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Channel channel = (Channel) o;
        return Objects.equals(id, channel.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Channel{" +
                "id='" + id + '\'' +
                ", displayName='" + displayName + '\'' +
                ", scope=" + scope +
                ", clientId='" + clientId + '\'' +
                ", memberCount=" + members.size() +
                '}';
    }
}
