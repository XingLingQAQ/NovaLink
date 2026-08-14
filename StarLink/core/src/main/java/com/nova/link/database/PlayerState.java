package com.nova.link.database;

import java.util.*;

/**
 * Represents the persistent state of a player in the NovaLink system.
 * This state is saved to and loaded from the database.
 * 
 * Requirements: 3.3, 22.4
 */
public class PlayerState {

    /** Player UUID */
    private final UUID playerId;
    
    /** Player name */
    private String playerName;
    
    /** Current client ID the player is connected through */
    private String clientId;
    
    /** Current world the player is in */
    private String currentWorld;
    
    /** Set of channel IDs the player has joined */
    private Set<String> joinedChannels;
    
    /** Currently active channel ID */
    private String activeChannel;
    
    /** Map of channel ID to mute info */
    private Map<String, MuteInfo> mutes;

    /** Platform the player connects from (e.g. "BUKKIT", "VELOCITY"); null = unknown/Java */
    private String platform;

    /** Per-player DM toggle (P0-5): when false, incoming private messages are
     *  rejected by PrivateMessageHandler before delivery. Defaults to true so
     *  existing players keep receiving DMs unless they explicitly opt out. */
    private boolean dmEnabled = true;

    /** Last seen timestamp */
    private long lastSeen;

    public PlayerState(UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "Player ID cannot be null");
        this.joinedChannels = new HashSet<>();
        this.mutes = new HashMap<>();
        this.lastSeen = System.currentTimeMillis();
    }

    public PlayerState(UUID playerId, String playerName) {
        this(playerId);
        this.playerName = playerName;
    }

    // Copy constructor for creating deep copies
    public PlayerState(PlayerState other) {
        this.playerId = other.playerId;
        this.playerName = other.playerName;
        this.clientId = other.clientId;
        this.currentWorld = other.currentWorld;
        this.joinedChannels = new HashSet<>(other.joinedChannels);
        this.activeChannel = other.activeChannel;
        this.mutes = new HashMap<>(other.mutes);
        this.platform = other.platform;
        this.dmEnabled = other.dmEnabled;
        this.lastSeen = other.lastSeen;
    }

    // Getters and setters

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getCurrentWorld() {
        return currentWorld;
    }

    public void setCurrentWorld(String currentWorld) {
        this.currentWorld = currentWorld;
    }

    public Set<String> getJoinedChannels() {
        return Collections.unmodifiableSet(joinedChannels);
    }

    public void setJoinedChannels(Set<String> joinedChannels) {
        this.joinedChannels = joinedChannels != null ? new HashSet<>(joinedChannels) : new HashSet<>();
    }

    /** Adds a channel id to the player's joined set; null ids are ignored. */
    public void addJoinedChannel(String channelId) {
        if (channelId != null) {
            joinedChannels.add(channelId);
        }
    }

    /** Removes a channel id from the player's joined set, if present. */
    public void removeJoinedChannel(String channelId) {
        joinedChannels.remove(channelId);
    }

    public boolean hasJoinedChannel(String channelId) {
        return joinedChannels.contains(channelId);
    }

    public String getActiveChannel() {
        return activeChannel;
    }

    public void setActiveChannel(String activeChannel) {
        this.activeChannel = activeChannel;
    }

    public Map<String, MuteInfo> getMutes() {
        return Collections.unmodifiableMap(mutes);
    }

    public void setMutes(Map<String, MuteInfo> mutes) {
        this.mutes = mutes != null ? new HashMap<>(mutes) : new HashMap<>();
    }

    /** Associates a mute with the given channel id; null keys or values are ignored. */
    public void addMute(String channelId, MuteInfo muteInfo) {
        if (channelId != null && muteInfo != null) {
            mutes.put(channelId, muteInfo);
        }
    }

    /** Removes any mute recorded for the given channel id. */
    public void removeMute(String channelId) {
        mutes.remove(channelId);
    }

    public MuteInfo getMute(String channelId) {
        return mutes.get(channelId);
    }

    public boolean isMuted(String channelId) {
        MuteInfo mute = mutes.get(channelId);
        if (mute == null) {
            return false;
        }
        // Check if mute has expired
        if (mute.getExpireTime() > 0 && System.currentTimeMillis() > mute.getExpireTime()) {
            mutes.remove(channelId);
            return false;
        }
        return true;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    /**
     * Per-player DM toggle (P0-5). When {@code false}, incoming private
     * messages targeting this player are rejected by PrivateMessageHandler
     * before delivery. Defaults to {@code true}.
     */
    public boolean isDmEnabled() {
        return dmEnabled;
    }

    /**
     * Sets the per-player DM toggle (P0-5). Persisted via PlayerStateManager
     * so the preference survives reconnects/restarts.
     *
     * @param dmEnabled {@code false} to reject incoming DMs, {@code true} to allow
     */
    public void setDmEnabled(boolean dmEnabled) {
        this.dmEnabled = dmEnabled;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    /** Refreshes the last-seen timestamp to the current time. */
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlayerState that = (PlayerState) o;
        return lastSeen == that.lastSeen &&
                dmEnabled == that.dmEnabled &&
                Objects.equals(playerId, that.playerId) &&
                Objects.equals(playerName, that.playerName) &&
                Objects.equals(clientId, that.clientId) &&
                Objects.equals(currentWorld, that.currentWorld) &&
                Objects.equals(joinedChannels, that.joinedChannels) &&
                Objects.equals(activeChannel, that.activeChannel) &&
                Objects.equals(mutes, that.mutes) &&
                Objects.equals(platform, that.platform);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, playerName, clientId, currentWorld,
                joinedChannels, activeChannel, mutes, platform, dmEnabled, lastSeen);
    }

    @Override
    public String toString() {
        return "PlayerState{" +
                "playerId=" + playerId +
                ", playerName='" + playerName + '\'' +
                ", clientId='" + clientId + '\'' +
                ", currentWorld='" + currentWorld + '\'' +
                ", joinedChannels=" + joinedChannels +
                ", activeChannel='" + activeChannel + '\'' +
                ", mutesCount=" + mutes.size() +
                ", platform='" + platform + '\'' +
                ", dmEnabled=" + dmEnabled +
                ", lastSeen=" + lastSeen +
                '}';
    }
}
