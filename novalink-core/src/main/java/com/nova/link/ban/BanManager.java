package com.nova.link.ban;

import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.BanInfo;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages ban operations for players in channels or globally.
 *
 * <p>Mirrors {@link com.nova.link.mute.MuteManager} with the same permission
 * tiers and console-sentinel bypass. Unlike a mute, a successful ban also
 * removes the player from the channel membership so an online banned player
 * is immediately ejected. A global ban (channelId == null) removes the player
 * from every channel they have joined.
 *
 * Requirements: ban feature — player ban management
 */
public class BanManager {

    private static final Logger logger = LoggerFactory.getLogger(BanManager.class);

    /** Maximum ban duration for channel admins: 1 hour in milliseconds */
    public static final long CHANNEL_ADMIN_MAX_DURATION_MS = 60 * 60 * 1000L;

    /** Maximum ban duration for client admins: 24 hours in milliseconds */
    public static final long CLIENT_ADMIN_MAX_DURATION_MS = 24 * 60 * 60 * 1000L;

    /** In-memory cache of bans: playerId -> (channelId -> BanInfo) */
    private final Map<UUID, Map<String, BanInfo>> banCache = new ConcurrentHashMap<>();

    /** Key for global bans in the map */
    private static final String GLOBAL_BAN_KEY = "__global__";

    private final DatabaseProvider databaseProvider;
    private final PermissionManager permissionManager;
    private final ChannelManager channelManager;

    /**
     * Optional notification store. When set, successful ban/unban operations
     * create a persisted + broadcast notification. Injected via setter to keep
     * the constructor signature stable and avoid a circular dependency with
     * the notification subsystem.
     */
    private com.nova.link.notification.NotificationStore notificationStore;

    private ScheduledExecutorService cleanupExecutor;

    public BanManager(DatabaseProvider databaseProvider,
                      PermissionManager permissionManager,
                      ChannelManager channelManager) {
        this.databaseProvider = databaseProvider;
        this.permissionManager = permissionManager;
        this.channelManager = channelManager;
    }

    /**
     * Sets the optional notification store so ban/unban events are persisted
     * and broadcast to the web panel.
     */
    public void setNotificationStore(com.nova.link.notification.NotificationStore notificationStore) {
        this.notificationStore = notificationStore;
    }

    /**
     * Initializes the ban manager and starts the cleanup task.
     */
    public void initialize() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BanManager-Cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredBans, 1, 1, TimeUnit.MINUTES);
        logger.info("BanManager initialized with periodic cleanup");
    }

    /**
     * Shuts down the ban manager.
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
        banCache.clear();
        logger.info("BanManager shutdown");
    }

    /**
     * Bans a player in a channel or globally.
     *
     * <p>On success the player is also removed from the affected channel
     * membership so an online banned player is immediately ejected. For a
     * global ban the player is removed from every joined channel.
     *
     * @param operatorId the UUID of the operator issuing the ban
     * @param targetPlayerId the UUID of the player to ban
     * @param channelId the channel ID (null for global ban)
     * @param durationMs the ban duration in milliseconds (0 for permanent)
     * @param reason the reason for the ban
     * @param operatorClientId the client ID of the operator (for scope validation)
     * @return the result of the ban operation
     */
    public BanResult banPlayer(UUID operatorId, UUID targetPlayerId, String channelId,
                               long durationMs, String reason, String operatorClientId) {
        if (operatorId == null) {
            return BanResult.badRequest("Operator ID is required");
        }
        if (targetPlayerId == null) {
            return BanResult.badRequest("Target player ID is required");
        }

        // Console-originated actions (UUID 00000000-0000-0000-0000-000000000000)
        // bypass permission validation — console always has full authority.
        PermissionLevel operatorLevel;
        if (operatorId.getMostSignificantBits() == 0L && operatorId.getLeastSignificantBits() == 0L) {
            operatorLevel = PermissionLevel.SUPER_ADMIN;
        } else {
            operatorLevel = permissionManager.getPermissionLevel(operatorId, channelId);
        }

        // Validate permission and scope
        BanResult validationResult = validateBanPermission(
                operatorId, operatorLevel, channelId, durationMs, operatorClientId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        // Calculate expiration time
        long expireTime = durationMs > 0 ? System.currentTimeMillis() + durationMs : 0;

        // Create ban info
        BanInfo banInfo = new BanInfo(channelId, expireTime, reason, operatorId);

        // Store in cache
        String cacheKey = channelId != null ? channelId : GLOBAL_BAN_KEY;
        banCache.computeIfAbsent(targetPlayerId, k -> new ConcurrentHashMap<>())
                .put(cacheKey, banInfo);

        // Persist to database
        if (databaseProvider != null) {
            try {
                databaseProvider.saveBan(targetPlayerId, banInfo);
            } catch (DatabaseException e) {
                logger.error("Failed to persist ban for player {}: {}", targetPlayerId, e.getMessage());
                // Continue - ban is still in cache
            }
        }

        // Eject the banned player from channel membership.
        if (channelId != null) {
            channelManager.removeMember(channelId, targetPlayerId);
        } else {
            // Global ban: remove from every joined channel.
            for (Channel channel : channelManager.getAllChannels()) {
                if (channel.isMember(targetPlayerId)) {
                    channelManager.removeMember(channel.getId(), targetPlayerId);
                }
            }
        }

        logger.info("Player {} banned in channel {} by {} for {} ms. Reason: {}",
                targetPlayerId, channelId, operatorId, durationMs, reason);

        // Persist + broadcast a notification when the store is wired.
        if (notificationStore != null) {
            try {
                String scope = channelId != null ? channelId : "global";
                notificationStore.createNotification(
                        "Player Banned",
                        "Player " + targetPlayerId + " banned in " + scope
                                + " for " + describeDuration(durationMs)
                                + ". Reason: " + (reason != null ? reason : "-"),
                        "warning");
            } catch (Exception e) {
                logger.debug("Failed to create ban notification: {}", e.getMessage());
            }
        }

        return BanResult.success("Player banned successfully");
    }

    /**
     * Validates if the operator has permission to ban in the specified scope.
     */
    public BanResult validateBanPermission(UUID operatorId, PermissionLevel operatorLevel,
                                           String channelId, long durationMs,
                                           String operatorClientId) {
        switch (operatorLevel) {
            case SUPER_ADMIN:
                // Super admin can ban anyone, anywhere, with no duration limit
                return BanResult.success("Validated");

            case CLIENT_ADMIN:
                // Client admin can ban in their client's channels, max 24 hours
                if (durationMs > CLIENT_ADMIN_MAX_DURATION_MS && durationMs != 0) {
                    return BanResult.durationExceeded(
                            "Client admin ban duration cannot exceed 24 hours");
                }
                if (channelId != null) {
                    Channel channel = channelManager.getChannel(channelId);
                    if (channel == null) {
                        return BanResult.notFound("Channel not found: " + channelId);
                    }
                    if (channel.getScope() != ChannelScope.GLOBAL &&
                        !Objects.equals(channel.getClientId(), operatorClientId)) {
                        return BanResult.forbidden(
                                "Client admin can only ban in their own client's channels");
                    }
                }
                return BanResult.success("Validated");

            case CHANNEL_ADMIN:
                // Channel admin can only ban in their own channels, max 1 hour
                if (durationMs > CHANNEL_ADMIN_MAX_DURATION_MS && durationMs != 0) {
                    return BanResult.durationExceeded(
                            "Channel admin ban duration cannot exceed 1 hour");
                }
                if (channelId == null) {
                    return BanResult.forbidden(
                            "Channel admin cannot issue global bans");
                }
                if (!permissionManager.isChannelAdmin(channelId, operatorId)) {
                    return BanResult.forbidden(
                            "Channel admin can only ban in channels they manage");
                }
                return BanResult.success("Validated");

            case PLAYER:
            default:
                return BanResult.forbidden("Insufficient permissions to ban players");
        }
    }

    /**
     * Unbans a player in a channel or globally.
     *
     * @param operatorId the UUID of the operator
     * @param targetPlayerId the UUID of the player to unban
     * @param channelId the channel ID (null for global unban)
     * @param operatorClientId the client ID of the operator
     * @return the result of the unban operation
     */
    public BanResult unbanPlayer(UUID operatorId, UUID targetPlayerId, String channelId,
                                 String operatorClientId) {
        if (operatorId == null) {
            return BanResult.badRequest("Operator ID is required");
        }
        if (targetPlayerId == null) {
            return BanResult.badRequest("Target player ID is required");
        }

        // Console-originated actions bypass permission validation.
        PermissionLevel operatorLevel;
        if (operatorId.getMostSignificantBits() == 0L && operatorId.getLeastSignificantBits() == 0L) {
            operatorLevel = PermissionLevel.SUPER_ADMIN;
        } else {
            operatorLevel = permissionManager.getPermissionLevel(operatorId, channelId);
        }

        // Validate permission (same rules as ban, but no duration check)
        BanResult validationResult = validateBanPermission(
                operatorId, operatorLevel, channelId, 0, operatorClientId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        // Remove from cache
        String cacheKey = channelId != null ? channelId : GLOBAL_BAN_KEY;
        Map<String, BanInfo> playerBans = banCache.get(targetPlayerId);
        if (playerBans != null) {
            playerBans.remove(cacheKey);
        }

        // Remove from database
        if (databaseProvider != null) {
            try {
                databaseProvider.deleteBan(targetPlayerId, channelId);
            } catch (DatabaseException e) {
                logger.error("Failed to delete ban for player {}: {}", targetPlayerId, e.getMessage());
            }
        }

        logger.info("Player {} unbanned in channel {} by {}", targetPlayerId, channelId, operatorId);

        // Persist + broadcast a notification when the store is wired.
        if (notificationStore != null) {
            try {
                String scope = channelId != null ? channelId : "global";
                notificationStore.createNotification(
                        "Player Unbanned",
                        "Player " + targetPlayerId + " unbanned in " + scope,
                        "info");
            } catch (Exception e) {
                logger.debug("Failed to create unban notification: {}", e.getMessage());
            }
        }

        return BanResult.success("Player unbanned successfully");
    }

    /**
     * Checks if a player is banned in a specific channel.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     * @return true if the player is banned
     */
    public boolean isBanned(UUID playerId, String channelId) {
        if (playerId == null) {
            return false;
        }

        Map<String, BanInfo> playerBans = banCache.get(playerId);
        if (playerBans == null) {
            return false;
        }

        // Check channel-specific ban
        String cacheKey = channelId != null ? channelId : GLOBAL_BAN_KEY;
        BanInfo channelBan = playerBans.get(cacheKey);
        if (channelBan != null) {
            if (channelBan.isExpired()) {
                playerBans.remove(cacheKey);
                cleanupBanFromDatabase(playerId, channelId);
                return false;
            }
            return true;
        }

        // Check global ban
        BanInfo globalBan = playerBans.get(GLOBAL_BAN_KEY);
        if (globalBan != null) {
            if (globalBan.isExpired()) {
                playerBans.remove(GLOBAL_BAN_KEY);
                cleanupBanFromDatabase(playerId, null);
                return false;
            }
            return true;
        }

        return false;
    }

    /**
     * Gets the ban info for a player in a channel.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     * @return the ban info, or null if not banned
     */
    public BanInfo getBanInfo(UUID playerId, String channelId) {
        if (playerId == null) {
            return null;
        }

        Map<String, BanInfo> playerBans = banCache.get(playerId);
        if (playerBans == null) {
            return null;
        }

        String cacheKey = channelId != null ? channelId : GLOBAL_BAN_KEY;
        BanInfo channelBan = playerBans.get(cacheKey);
        if (channelBan != null && !channelBan.isExpired()) {
            return channelBan;
        }

        BanInfo globalBan = playerBans.get(GLOBAL_BAN_KEY);
        if (globalBan != null && !globalBan.isExpired()) {
            return globalBan;
        }

        return null;
    }

    /**
     * Gets all active bans for a player.
     *
     * @param playerId the player UUID
     * @return list of active ban infos
     */
    public List<BanInfo> getActiveBans(UUID playerId) {
        if (playerId == null) {
            return Collections.emptyList();
        }

        Map<String, BanInfo> playerBans = banCache.get(playerId);
        if (playerBans == null) {
            return Collections.emptyList();
        }

        List<BanInfo> activeBans = new ArrayList<>();
        for (BanInfo ban : playerBans.values()) {
            if (!ban.isExpired()) {
                activeBans.add(ban);
            }
        }
        return activeBans;
    }

    /**
     * Loads bans for a player from the database into cache.
     *
     * @param playerId the player UUID
     */
    public void loadPlayerBans(UUID playerId) {
        if (playerId == null || databaseProvider == null) {
            return;
        }

        try {
            List<BanInfo> bans = databaseProvider.loadBans(playerId);
            if (!bans.isEmpty()) {
                Map<String, BanInfo> playerBans = banCache.computeIfAbsent(
                        playerId, k -> new ConcurrentHashMap<>());
                for (BanInfo ban : bans) {
                    if (!ban.isExpired()) {
                        String cacheKey = ban.getChannelId() != null ?
                                ban.getChannelId() : GLOBAL_BAN_KEY;
                        playerBans.put(cacheKey, ban);
                    }
                }
                logger.debug("Loaded {} bans for player {}", bans.size(), playerId);
            }
        } catch (DatabaseException e) {
            logger.error("Failed to load bans for player {}: {}", playerId, e.getMessage());
        }
    }

    /**
     * Clears cached bans for a player (when they disconnect).
     *
     * @param playerId the player UUID
     */
    public void clearPlayerBans(UUID playerId) {
        if (playerId != null) {
            banCache.remove(playerId);
        }
    }

    /**
     * Cleans up expired bans from cache and database.
     */
    public void cleanupExpiredBans() {
        int cacheCleanup = 0;

        for (Map.Entry<UUID, Map<String, BanInfo>> entry : banCache.entrySet()) {
            Map<String, BanInfo> playerBans = entry.getValue();
            Iterator<Map.Entry<String, BanInfo>> iterator = playerBans.entrySet().iterator();
            while (iterator.hasNext()) {
                BanInfo ban = iterator.next().getValue();
                if (ban.isExpired()) {
                    iterator.remove();
                    cacheCleanup++;
                }
            }
        }

        int dbCleanup = 0;
        if (databaseProvider != null) {
            try {
                dbCleanup = databaseProvider.cleanupExpiredBans();
            } catch (DatabaseException e) {
                logger.error("Failed to cleanup expired bans from database: {}", e.getMessage());
            }
        }

        if (cacheCleanup > 0 || dbCleanup > 0) {
            logger.debug("Cleaned up {} expired bans from cache, {} from database",
                    cacheCleanup, dbCleanup);
        }
    }

    /**
     * Helper method to clean up a single ban from database.
     */
    private void cleanupBanFromDatabase(UUID playerId, String channelId) {
        if (databaseProvider != null) {
            try {
                databaseProvider.deleteBan(playerId, channelId);
            } catch (DatabaseException e) {
                logger.error("Failed to cleanup ban from database: {}", e.getMessage());
            }
        }
    }

    /**
     * Gets the ban cache size (for testing).
     */
    public int getCacheSize() {
        return banCache.size();
    }

    /**
     * Clears all cached bans (for testing).
     */
    public void clearCache() {
        banCache.clear();
    }

    private static String describeDuration(long ms) {
        if (ms <= 0) {
            return "permanent";
        }
        long secs = ms / 1000;
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        if (secs < 86400) return (secs / 3600) + "h";
        return (secs / 86400) + "d";
    }
}
