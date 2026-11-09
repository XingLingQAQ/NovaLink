package com.nova.link.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private final Map<UUID, Object> dirtyStates = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerState> pendingStates = new ConcurrentHashMap<>();

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

        return cache.computeIfAbsent(playerId, id -> {
            PlayerState pending = pendingStates.remove(id);
            if (pending != null) {
                return pending;
            }
            try {
                Optional<PlayerState> loaded = databaseProvider.loadPlayerState(id);
                if (loaded.isPresent()) {
                    return loaded.get();
                }
            } catch (DatabaseException e) {
                logger.warn("Failed to load player state from database: {}", id, e);
            }
            dirtyStates.put(id, new Object());
            return new PlayerState(id, playerName);
        });
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
     * Loads from the database after committing any pending offline write.
     * Returns empty if that write cannot be committed.
     *
     * @param playerId the player UUID
     * @return the player state, or empty if not found
     */
    public Optional<PlayerState> loadState(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }

        AtomicReference<Optional<PlayerState>> loaded = new AtomicReference<>(Optional.empty());
        cache.compute(playerId, (id, current) -> {
            PlayerState pending = pendingStates.get(id);
            if (pending != null) {
                if (!persistState(pending) || dirtyStates.containsKey(id)) {
                    return current;
                }
                pendingStates.remove(id);
            }
            try {
                Optional<PlayerState> state = databaseProvider.loadPlayerState(id);
                loaded.set(state);
                return state.orElse(current);
            } catch (DatabaseException e) {
                logger.error("Failed to load player state: {}", id, e);
                return current;
            }
        });
        return loaded.get();
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

        UUID playerId = state.getPlayerId();
        AtomicBoolean saved = new AtomicBoolean();
        cache.compute(playerId, (id, current) -> {
            if (persistState(state)) {
                pendingStates.remove(id);
                saved.set(true);
                return state;
            }
            if (current == null) {
                pendingStates.put(id, state);
            }
            return current;
        });
        return saved.get();
    }

    private boolean persistState(PlayerState state) {
        UUID playerId = state.getPlayerId();
        Object dirtyVersion = dirtyStates.get(playerId);
        try {
            state.updateLastSeen();
            databaseProvider.savePlayerState(new PlayerState(state));
            if (dirtyVersion != null) {
                dirtyStates.remove(playerId, dirtyVersion);
            }
            logger.debug("Saved player state: {}", playerId);
            return true;
        } catch (DatabaseException e) {
            dirtyStates.putIfAbsent(playerId, new Object());
            logger.error("Failed to save player state: {}", playerId, e);
            return false;
        }
    }

    /**
     * Marks a player state as dirty (needs to be saved).
     *
     * @param playerId the player UUID
     */
    public void markDirty(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PlayerState state = cache.get(playerId);
        if (state != null) {
            dirtyStates.compute(playerId, (id, previous) -> {
                if (cache.get(id) == state || pendingStates.get(id) == state) {
                    return new Object();
                }
                return previous;
            });
        }
    }

    /**
     * Saves dirty states, including offline retries, without recaching offline players.
     *
     * @return number of states saved
     */
    public int saveAllDirty() {
        int count = 0;
        for (UUID playerId : new HashSet<>(dirtyStates.keySet())) {
            AtomicBoolean saved = new AtomicBoolean();
            cache.compute(playerId, (id, current) -> {
                if (!dirtyStates.containsKey(id)) {
                    return current;
                }
                PlayerState state = current != null ? current : pendingStates.get(id);
                if (state != null && persistState(state)) {
                    if (!dirtyStates.containsKey(id)) {
                        pendingStates.remove(id);
                    }
                    saved.set(true);
                }
                return current;
            });
            if (saved.get()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Removes a player state from cache (e.g., when player disconnects).
     * Failed writes remain pending without appearing in the online cache.
     *
     * @param playerId the player UUID
     * @param saveFirst whether to save before removing
     */
    public void removeFromCache(UUID playerId, boolean saveFirst) {
        if (playerId == null) {
            return;
        }

        cache.compute(playerId, (id, current) -> {
            PlayerState state = current != null ? current : pendingStates.get(id);
            if (saveFirst && state != null) {
                pendingStates.put(id, state);
                persistState(state);
            } else if (!saveFirst) {
                pendingStates.remove(id);
                dirtyStates.remove(id);
            }
            return null;
        });
        dirtyStates.compute(playerId, (id, version) -> {
            if (version == null && !cache.containsKey(id)) {
                pendingStates.remove(id);
            }
            return version;
        });
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

        AtomicBoolean deleted = new AtomicBoolean();
        cache.compute(playerId, (id, current) -> {
            try {
                databaseProvider.deletePlayerState(id);
                pendingStates.remove(id);
                dirtyStates.remove(id);
                deleted.set(true);
                logger.debug("Deleted player state: {}", id);
                return null;
            } catch (DatabaseException e) {
                logger.error("Failed to delete player state: {}", id, e);
                return current;
            }
        });
        return deleted.get();
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
        if (channelId == null) {
            return;
        }
        PlayerState state = cache.get(playerId);
        if (state != null) {
            state.removeJoinedChannel(channelId);
            if (java.util.Objects.equals(channelId, state.getActiveChannel())) {
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
            state.addMute(muteInfo.getChannelId() != null ? muteInfo.getChannelId() : DatabaseProvider.GLOBAL_KEY, muteInfo);
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
            state.removeMute(channelId != null ? channelId : DatabaseProvider.GLOBAL_KEY);
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
     * Gets the number of dirty states, including pending offline writes.
     *
     * @return dirty count
     */
    public int getDirtyCount() {
        return dirtyStates.size();
    }

    /**
     * Clears the online cache, retaining failed writes when saving first.
     *
     * @param saveFirst whether to save all dirty states first
     */
    public void clearCache(boolean saveFirst) {
        if (saveFirst) {
            saveAllDirty();
            for (UUID playerId : new HashSet<>(cache.keySet())) {
                cache.computeIfPresent(playerId, (id, state) -> {
                    if (dirtyStates.containsKey(id)) {
                        pendingStates.put(id, state);
                    }
                    return null;
                });
            }
        } else {
            cache.clear();
            pendingStates.clear();
            dirtyStates.clear();
        }
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
