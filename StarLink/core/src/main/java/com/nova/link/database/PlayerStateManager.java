package com.nova.link.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player state persistence with caching support.
 * Provides high-level API for saving/loading player channel memberships and mute status.
 * 
 * Requirements: 22.4, 3.3
 */
public class PlayerStateManager {

    private static final Logger logger = LoggerFactory.getLogger(PlayerStateManager.class);

    private final DatabaseProvider databaseProvider;
    
    // In-memory cache for online players
    private final Map<UUID, PlayerState> cache = new ConcurrentHashMap<>();
    
    // Track dirty states that need to be persisted
    private final Set<UUID> dirtyStates = ConcurrentHashMap.newKeySet();

    public PlayerStateManager(DatabaseProvider databaseProvider) {
        this.databaseProvider = Objects.requireNonNull(databaseProvider, "Database provider cannot be null");
    }

    /**
     * Gets or creates a player state.
     * First checks cache, then database, then creates new if not found.
     *
     * @param playerId the player UUID
     * @param playerName the player name (used when creating new state)
     * @return the player state
     */
    public PlayerState getOrCreateState(UUID playerId, String playerName) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }

        // Check cache first
        PlayerState cached = cache.get(playerId);
        if (cached != null) {
            return cached;
        }

        // Try to load from database
        try {
            Optional<PlayerState> loaded = databaseProvider.loadPlayerState(playerId);
            if (loaded.isPresent()) {
                PlayerState state = loaded.get();
                // Use putIfAbsent so a concurrent caller that already cached a
                // (possibly newer) state wins; we return whichever is canonical.
                PlayerState existing = cache.putIfAbsent(playerId, state);
                logger.debug("Loaded player state from database: {}", playerId);
                return existing != null ? existing : state;
            }
        } catch (DatabaseException e) {
            logger.warn("Failed to load player state from database: {}", playerId, e);
        }

        // Create new state
        PlayerState newState = new PlayerState(playerId, playerName);
        PlayerState existing = cache.putIfAbsent(playerId, newState);
        if (existing == null) {
            dirtyStates.add(playerId);
            logger.debug("Created new player state: {}", playerId);
            return newState;
        }
        // Another thread cached a state between our check and the put; return it.
        return existing;
    }

    /**
     * Gets a player state from cache only.
     *
     * @param playerId the player UUID
     * @return the cached state, or empty if not in cache
     */
    public Optional<PlayerState> getCachedState(UUID playerId) {
        return Optional.ofNullable(cache.get(playerId));
    }

    /**
     * Gets a player state from cache.
     * Convenience method for API access.
     *
     * @param playerId the player UUID
     * @return the player state, or null if not in cache
     */
    public PlayerState getPlayerState(UUID playerId) {
        return cache.get(playerId);
    }

    /**
     * Gets all cached player states.
     * Returns only online/cached players.
     *
     * @return collection of all cached player states
     */
    public Collection<PlayerState> getAllPlayerStates() {
        return Collections.unmodifiableCollection(cache.values());
    }

    /**
     * Loads a player state from the database.
     *
     * @param playerId the player UUID
     * @return the player state, or empty if not found
     */
    public Optional<PlayerState> loadState(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }

        try {
            Optional<PlayerState> state = databaseProvider.loadPlayerState(playerId);
            state.ifPresent(s -> cache.put(playerId, s));
            return state;
        } catch (DatabaseException e) {
            logger.error("Failed to load player state: {}", playerId, e);
            return Optional.empty();
        }
    }

    /**
     * Saves a player state to the database.
     *
     * @param state the player state to save
     * @return true if saved successfully
     */
    public boolean saveState(PlayerState state) {
        if (state == null || state.getPlayerId() == null) {
            return false;
        }

        try {
            state.updateLastSeen();
            databaseProvider.savePlayerState(state);
            cache.put(state.getPlayerId(), state);
            dirtyStates.remove(state.getPlayerId());
            logger.debug("Saved player state: {}", state.getPlayerId());
            return true;
        } catch (DatabaseException e) {
            logger.error("Failed to save player state: {}", state.getPlayerId(), e);
            return false;
        }
    }

    /**
     * Marks a player state as dirty (needs to be saved).
     *
     * @param playerId the player UUID
     */
    public void markDirty(UUID playerId) {
        if (playerId != null && cache.containsKey(playerId)) {
            dirtyStates.add(playerId);
        }
    }

    /**
     * Saves all dirty states to the database.
     *
     * @return number of states saved
     */
    public int saveAllDirty() {
        int count = 0;
        for (UUID playerId : new HashSet<>(dirtyStates)) {
            PlayerState state = cache.get(playerId);
            if (state != null && saveState(state)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Removes a player state from cache (e.g., when player disconnects).
     * Optionally saves to database before removing.
     *
     * @param playerId the player UUID
     * @param saveFirst whether to save before removing
     */
    public void removeFromCache(UUID playerId, boolean saveFirst) {
        if (playerId == null) {
            return;
        }

        if (saveFirst) {
            PlayerState state = cache.get(playerId);
            if (state != null) {
                saveState(state);
            }
        }

        cache.remove(playerId);
        dirtyStates.remove(playerId);
        logger.debug("Removed player state from cache: {}", playerId);
    }

    /**
     * Deletes a player state from both cache and database.
     *
     * @param playerId the player UUID
     * @return true if deleted successfully
     */
    public boolean deleteState(UUID playerId) {
        if (playerId == null) {
            return false;
        }

        try {
            databaseProvider.deletePlayerState(playerId);
            cache.remove(playerId);
            dirtyStates.remove(playerId);
            logger.debug("Deleted player state: {}", playerId);
            return true;
        } catch (DatabaseException e) {
            logger.error("Failed to delete player state: {}", playerId, e);
            return false;
        }
    }

    // ==================== Channel Membership Operations ====================

    /**
     * Adds a channel to a player's joined channels.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     */
    public void joinChannel(UUID playerId, String channelId) {
        PlayerState state = cache.get(playerId);
        if (state != null) {
            state.addJoinedChannel(channelId);
            markDirty(playerId);
        }
    }

    /**
     * Removes a channel from a player's joined channels.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     */
    public void leaveChannel(UUID playerId, String channelId) {
        PlayerState state = cache.get(playerId);
        if (state != null) {
            state.removeJoinedChannel(channelId);
            if (channelId.equals(state.getActiveChannel())) {
                state.setActiveChannel(null);
            }
            markDirty(playerId);
        }
    }

    /**
     * Sets a player's active channel.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     */
    public void setActiveChannel(UUID playerId, String channelId) {
        PlayerState state = cache.get(playerId);
        if (state != null) {
            state.setActiveChannel(channelId);
            markDirty(playerId);
        }
    }

    /**
     * Gets a player's joined channels.
     *
     * @param playerId the player UUID
     * @return set of channel IDs, or empty set if not found
     */
    public Set<String> getJoinedChannels(UUID playerId) {
        PlayerState state = cache.get(playerId);
        return state != null ? state.getJoinedChannels() : Collections.emptySet();
    }

    // ==================== Mute Operations ====================

    /**
     * Adds a mute to a player.
     *
     * @param playerId the player UUID
     * @param muteInfo the mute information
     */
    public void addMute(UUID playerId, MuteInfo muteInfo) {
        if (playerId == null || muteInfo == null) {
            return;
        }

        PlayerState state = cache.get(playerId);
        if (state != null) {
            state.addMute(muteInfo.getChannelId() != null ? muteInfo.getChannelId() : "__global__", muteInfo);
            markDirty(playerId);
        }

        // Also save to database directly for persistence
        try {
            databaseProvider.saveMute(playerId, muteInfo);
        } catch (DatabaseException e) {
            logger.error("Failed to save mute to database: {}", playerId, e);
        }
    }

    /**
     * Removes a mute from a player.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID (null for global mute)
     */
    public void removeMute(UUID playerId, String channelId) {
        if (playerId == null) {
            return;
        }

        PlayerState state = cache.get(playerId);
        if (state != null) {
            state.removeMute(channelId != null ? channelId : "__global__");
            markDirty(playerId);
        }

        try {
            databaseProvider.deleteMute(playerId, channelId);
        } catch (DatabaseException e) {
            logger.error("Failed to delete mute from database: {}", playerId, e);
        }
    }

    /**
     * Checks if a player is muted in a channel.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     * @return true if muted
     */
    public boolean isMuted(UUID playerId, String channelId) {
        PlayerState state = cache.get(playerId);
        return state != null && state.isMuted(channelId);
    }

    /**
     * Gets mute info for a player in a channel.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     * @return the mute info, or empty if not muted
     */
    public Optional<MuteInfo> getMuteInfo(UUID playerId, String channelId) {
        PlayerState state = cache.get(playerId);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.getMute(channelId));
    }

    // ==================== Utility Methods ====================

    /**
     * Gets the number of cached player states.
     *
     * @return cache size
     */
    public int getCacheSize() {
        return cache.size();
    }

    /**
     * Gets the number of dirty states.
     *
     * @return dirty count
     */
    public int getDirtyCount() {
        return dirtyStates.size();
    }

    /**
     * Clears the cache (for testing or shutdown).
     *
     * @param saveFirst whether to save all dirty states first
     */
    public void clearCache(boolean saveFirst) {
        if (saveFirst) {
            saveAllDirty();
        }
        cache.clear();
        dirtyStates.clear();
    }

    /**
     * Gets the underlying database provider.
     *
     * @return the database provider
     */
    public DatabaseProvider getDatabaseProvider() {
        return databaseProvider;
    }
}
