package com.nova.link.moderation;

import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.database.DatabaseProvider;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Shared infrastructure for {@link com.nova.link.mute.MuteManager} and
 * {@link com.nova.link.ban.BanManager}: a two-level {@code UUID -> channelId -> I}
 * cache, a single-thread daemon cleanup executor (1-minute period, 5-second
 * graceful shutdown), console-sentinel + trusted-operator bypass, and the
 * load-all / get-all-active / is-mediated / get-info / clear-player templates.
 *
 * <p>Subclasses keep their concrete {@code *Result} return types and
 * {@code validate*Permission} logic — the Result type is not genericised so
 * that the public method signatures on {@code MuteManager} / {@code BanManager}
 * stay byte-identical. Subclasses delegate cache and lifecycle concerns here
 * and implement the abstract hooks for their Info type and DB accessors.
 *
 * <p>Behaviour preserved from the pre-refactor managers:
 * <ul>
 *   <li>console-sentinel bypass: operator UUID
 *       {@code 00000000-0000-0000-0000-000000000000} is treated as
 *       {@code SUPER_ADMIN} (via {@link ConsoleSentinel#isConsole});</li>
 *   <li>trusted-operator bypass: panel RBAC pre-authorized operators are
 *       treated as {@code SUPER_ADMIN} while keeping their own UUID for
 *       attribution;</li>
 *   <li>cleanup executor: single daemon thread, 1-minute period, 5-second
 *       {@code awaitTermination} then {@code shutdownNow} then
 *       {@code cache.clear()};</li>
 *   <li>{@code notificationStore} optional setter injection.</li>
 * </ul>
 *
 * @param <I> the info type ({@link com.nova.link.database.MuteInfo} /
 *            {@link com.nova.link.database.BanInfo})
 */
public abstract class AbstractModerationManager<I> {

    /**
     * Cache key used for global (channelId == null) entries.
     *
     * <p>Alias of {@link com.nova.link.database.DatabaseProvider#GLOBAL_KEY} —
     * the authoritative definition lives on the provider interface so the
     * provider implementations (same package) do not need to import a
     * moderation class. Kept as {@code protected static final} here to avoid
     * touching per-occurrence references in subclasses.
     */
    protected static final String GLOBAL_KEY = com.nova.link.database.DatabaseProvider.GLOBAL_KEY;

    /** In-memory cache: playerId -> (channelId-or-GLOBAL_KEY -> Info). */
    protected final Map<UUID, Map<String, I>> cache = new ConcurrentHashMap<>();

    protected final DatabaseProvider databaseProvider;
    protected final PermissionManager permissionManager;
    protected final ChannelManager channelManager;

    /** Optional notification store; injected via setter. */
    protected com.nova.link.notification.NotificationStore notificationStore;

    private ScheduledExecutorService cleanupExecutor;

    protected AbstractModerationManager(DatabaseProvider databaseProvider,
                                        PermissionManager permissionManager,
                                        ChannelManager channelManager) {
        this.databaseProvider = databaseProvider;
        this.permissionManager = permissionManager;
        this.channelManager = channelManager;
    }

    /**
     * Sets the optional notification store so apply/remove events are
     * persisted and broadcast to the web panel. Kept public so the inherited
     * method replaces the original {@code setNotificationStore} on each
     * subclass without changing the signature.
     */
    public void setNotificationStore(com.nova.link.notification.NotificationStore notificationStore) {
        this.notificationStore = notificationStore;
    }

    // ============================ lifecycle ============================

    /**
     * Starts the periodic expired-entry cleanup task. Single daemon thread,
     * 1-minute fixed-rate period — matches the pre-refactor managers.
     */
    public void initialize() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, cleanupThreadName());
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired, 1, 1, TimeUnit.MINUTES);
        logger().info("{} initialized with periodic cleanup", managerLogName());
    }

    /**
     * Stops the cleanup executor (5-second graceful await then shutdownNow) and
     * clears the cache — matches the pre-refactor managers.
     */
    public void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        cache.clear();
        logger().info("{} shutdown", managerLogName());
    }

    // ============================ operator resolution ============================

    /**
     * Resolves the operator's permission level, applying the console-sentinel
     * bypass (UUID {@code 00000000-0000-0000-0000-000000000000} ->
     * {@code SUPER_ADMIN}) and the trusted-operator bypass (panel RBAC
     * pre-authorized -> {@code SUPER_ADMIN}). This replaces the inline
     * {@code getMostSignificantBits()==0L && getLeastSignificantBits()==0L}
     * checks that used to live in each manager's apply/remove methods.
     */
    protected PermissionLevel resolveOperatorLevel(UUID operatorId, String channelId, boolean trustedOperator) {
        if (trustedOperator || ConsoleSentinel.isConsole(operatorId)) {
            return PermissionLevel.SUPER_ADMIN;
        }
        return permissionManager.getPermissionLevel(operatorId, channelId);
    }

    // ============================ cache helpers ============================

    /** Computes the cache key for a (possibly null) channelId. */
    protected static String cacheKeyFor(String channelId) {
        return channelId != null ? channelId : GLOBAL_KEY;
    }

    /** Stores an info entry in the cache. */
    protected void putInCache(UUID playerId, String cacheKey, I info) {
        cache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(cacheKey, info);
    }

    /** Removes an info entry from the cache (no-op if absent). */
    protected void removeFromCache(UUID playerId, String cacheKey) {
        Map<String, I> playerInfos = cache.get(playerId);
        if (playerInfos != null) {
            playerInfos.remove(cacheKey);
        }
    }

    // ============================ query templates ============================

    /**
     * True if the player has a non-expired entry for the channel (or global).
     * Expired entries are evicted from the cache and DB on read (auto-unmute/
     * auto-unban), matching the pre-refactor {@code isMuted}/{@code isBanned}.
     */
    protected boolean isModerated(UUID playerId, String channelId) {
        if (playerId == null) {
            return false;
        }

        Map<String, I> playerInfos = cache.get(playerId);
        if (playerInfos == null) {
            return false;
        }

        String cacheKey = cacheKeyFor(channelId);
        I channelEntry = playerInfos.get(cacheKey);
        if (channelEntry != null) {
            if (isExpired(channelEntry)) {
                playerInfos.remove(cacheKey);
                cleanupFromDatabase(playerId, channelId);
                return false;
            }
            return true;
        }

        I globalEntry = playerInfos.get(GLOBAL_KEY);
        if (globalEntry != null) {
            if (isExpired(globalEntry)) {
                playerInfos.remove(GLOBAL_KEY);
                cleanupFromDatabase(playerId, null);
                return false;
            }
            return true;
        }

        return false;
    }

    /** Returns the active info for the channel (or global), or null. */
    protected I getInfo(UUID playerId, String channelId) {
        if (playerId == null) {
            return null;
        }

        Map<String, I> playerInfos = cache.get(playerId);
        if (playerInfos == null) {
            return null;
        }

        String cacheKey = cacheKeyFor(channelId);
        I channelEntry = playerInfos.get(cacheKey);
        if (channelEntry != null && !isExpired(channelEntry)) {
            return channelEntry;
        }

        I globalEntry = playerInfos.get(GLOBAL_KEY);
        if (globalEntry != null && !isExpired(globalEntry)) {
            return globalEntry;
        }

        return null;
    }

    /** Returns all active entries for a player. */
    protected List<I> getActive(UUID playerId) {
        if (playerId == null) {
            return Collections.emptyList();
        }

        Map<String, I> playerInfos = cache.get(playerId);
        if (playerInfos == null) {
            return Collections.emptyList();
        }

        List<I> active = new ArrayList<>();
        for (I info : playerInfos.values()) {
            if (!isExpired(info)) {
                active.add(info);
            }
        }
        return active;
    }

    /** Returns all active entries across all players. */
    protected Map<UUID, List<I>> getAllActive() {
        Map<UUID, List<I>> result = new HashMap<>();
        for (Map.Entry<UUID, Map<String, I>> entry : cache.entrySet()) {
            List<I> active = new ArrayList<>();
            for (I info : entry.getValue().values()) {
                if (!isExpired(info)) {
                    active.add(info);
                }
            }
            if (!active.isEmpty()) {
                result.put(entry.getKey(), active);
            }
        }
        return result;
    }

    // ============================ persistence templates ============================

    /**
     * Loads all active entries from the database into the cache. Called once
     * at startup so persisted entries stay effective across a backend restart.
     *
     * @return the number of entries loaded
     */
    protected int loadAll() {
        if (databaseProvider == null) {
            return 0;
        }

        int loaded = 0;
        try {
            Map<UUID, List<I>> all = loadAllFromDb();
            for (Map.Entry<UUID, List<I>> entry : all.entrySet()) {
                Map<String, I> playerInfos = cache.computeIfAbsent(
                        entry.getKey(), k -> new ConcurrentHashMap<>());
                for (I info : entry.getValue()) {
                    if (!isExpired(info)) {
                        String cacheKey = cacheKeyFor(getChannelId(info));
                        playerInfos.put(cacheKey, info);
                        loaded++;
                    }
                }
            }
            if (loaded > 0) {
                logger().info("Loaded {} active {}s from database", loaded, noun());
            }
        } catch (Exception e) {
            logger().error("Failed to load active {}s from database: {}", noun(), e.getMessage());
        }
        return loaded;
    }

    /** Loads entries for a single player from the database into the cache. */
    protected void loadPlayer(UUID playerId) {
        if (playerId == null || databaseProvider == null) {
            return;
        }

        try {
            List<I> infos = loadFromDb(playerId);
            if (!infos.isEmpty()) {
                Map<String, I> playerInfos = cache.computeIfAbsent(
                        playerId, k -> new ConcurrentHashMap<>());
                for (I info : infos) {
                    if (!isExpired(info)) {
                        String cacheKey = cacheKeyFor(getChannelId(info));
                        playerInfos.put(cacheKey, info);
                    }
                }
                logger().debug("Loaded {} {}s for player {}", infos.size(), noun(), playerId);
            }
        } catch (Exception e) {
            logger().error("Failed to load {}s for player {}: {}", noun(), playerId, e.getMessage());
        }
    }

    /** Clears cached entries for a player (on disconnect). */
    protected void clearPlayer(UUID playerId) {
        if (playerId != null) {
            cache.remove(playerId);
        }
    }

    /** Persists an info entry to the database (best-effort; cache stays authoritative). */
    protected void persistToDb(UUID playerId, I info) {
        if (databaseProvider == null) {
            return;
        }
        try {
            saveToDb(playerId, info);
        } catch (Exception e) {
            logger().error("Failed to persist {} for player {}: {}", noun(), playerId, e.getMessage());
            // Continue - entry is still in cache
        }
    }

    /**
     * Removes a single entry from the database (best-effort). Used by the
     * auto-expire path in {@link #isModerated}; logs the "cleanup" message.
     */
    protected void cleanupFromDatabase(UUID playerId, String channelId) {
        if (databaseProvider == null) {
            return;
        }
        try {
            deleteFromDb(playerId, channelId);
        } catch (Exception e) {
            logger().error("Failed to cleanup {} from database: {}", noun(), e.getMessage());
        }
    }

    /**
     * Removes an entry from the database (best-effort). Used by the explicit
     * unmute/unban path; logs the "delete" message.
     */
    protected void deleteFromDatabase(UUID playerId, String channelId) {
        if (databaseProvider == null) {
            return;
        }
        try {
            deleteFromDb(playerId, channelId);
        } catch (Exception e) {
            logger().error("Failed to delete {} for player {}: {}", noun(), playerId, e.getMessage());
        }
    }

    // ============================ cleanup ============================

    /**
     * Periodic cleanup: removes expired entries from the cache and the
     * database. Runs on the cleanup executor every minute.
     */
    protected void cleanupExpired() {
        int cacheCleanup = 0;

        for (Map.Entry<UUID, Map<String, I>> entry : cache.entrySet()) {
            Iterator<Map.Entry<String, I>> iterator = entry.getValue().entrySet().iterator();
            while (iterator.hasNext()) {
                I info = iterator.next().getValue();
                if (isExpired(info)) {
                    iterator.remove();
                    cacheCleanup++;
                }
            }
        }

        int dbCleanup = 0;
        if (databaseProvider != null) {
            try {
                dbCleanup = cleanupExpiredFromDb();
            } catch (Exception e) {
                logger().error("Failed to cleanup expired {}s from database: {}", noun(), e.getMessage());
            }
        }

        if (cacheCleanup > 0 || dbCleanup > 0) {
            logger().debug("Cleaned up {} expired {}s from cache, {} from database",
                    cacheCleanup, noun(), dbCleanup);
        }
    }

    // ============================ test helpers ============================

    /** Cache size (for testing). */
    public int getCacheSize() {
        return cache.size();
    }

    /** Clears all cached entries (for testing). */
    public void clearCache() {
        cache.clear();
    }

    // ============================ subclass hooks ============================

    /** Thread name for the cleanup executor, e.g. "MuteManager-Cleanup". */
    protected abstract String cleanupThreadName();

    /** Logger for this manager. */
    protected abstract Logger logger();

    /** Display name for lifecycle log messages, e.g. "MuteManager". */
    protected abstract String managerLogName();

    /** Singular noun for per-entry log messages, e.g. "mute" / "ban". */
    protected abstract String noun();

    /** Returns the channelId of an info entry. */
    protected abstract String getChannelId(I info);

    /** Returns whether an info entry has expired. */
    protected abstract boolean isExpired(I info);

    /** Saves an info entry to the database. */
    protected abstract void saveToDb(UUID playerId, I info) throws Exception;

    /** Deletes an info entry from the database. */
    protected abstract void deleteFromDb(UUID playerId, String channelId) throws Exception;

    /** Loads all active entries from the database. */
    protected abstract Map<UUID, List<I>> loadAllFromDb() throws Exception;

    /** Loads entries for a player from the database. */
    protected abstract List<I> loadFromDb(UUID playerId) throws Exception;

    /** Cleans up expired entries from the database; returns count removed. */
    protected abstract int cleanupExpiredFromDb() throws Exception;

    /**
     * Hook invoked after an apply (mute/ban) succeeds. Default no-op; overridden
     * by {@link com.nova.link.ban.BanManager} to eject the player from channel
     * membership.
     *
     * @param playerId  the target player
     * @param channelId the channel id, or null for global
     * @param isGlobal  true when channelId is null (global apply)
     */
    protected void onAppliedSuccessfully(UUID playerId, String channelId, boolean isGlobal) {
        // no-op by default; bans eject the player from channel membership
    }
}
