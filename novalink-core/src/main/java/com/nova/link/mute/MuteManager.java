package com.nova.link.mute;

import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.MuteInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages mute operations for players in channels.
 * 
 * Requirements:
 * - 13.1: Record mute information when admin executes mute command
 * - 13.2: Reject messages from muted players and return mute notification
 * - 13.3: Channel admin: own channels, max 1 hour
 * - 13.4: Client admin: client channels, max 24 hours
 * - 13.5: Super admin: any channel, no limit
 * - 13.6: Auto-unmute when mute expires
 */
public class MuteManager {

    private static final Logger logger = LoggerFactory.getLogger(MuteManager.class);

    /** Maximum mute duration for channel admins: 1 hour in milliseconds */
    public static final long CHANNEL_ADMIN_MAX_DURATION_MS = 60 * 60 * 1000L;

    /** Maximum mute duration for client admins: 24 hours in milliseconds */
    public static final long CLIENT_ADMIN_MAX_DURATION_MS = 24 * 60 * 60 * 1000L;

    /** In-memory cache of mutes: playerId -> (channelId -> MuteInfo) */
    private final Map<UUID, Map<String, MuteInfo>> muteCache = new ConcurrentHashMap<>();

    /** Key for global mutes in the map */
    private static final String GLOBAL_MUTE_KEY = "__global__";

    private final DatabaseProvider databaseProvider;
    private final PermissionManager permissionManager;
    private final ChannelManager channelManager;

    private ScheduledExecutorService cleanupExecutor;

    public MuteManager(DatabaseProvider databaseProvider, 
                       PermissionManager permissionManager,
                       ChannelManager channelManager) {
        this.databaseProvider = databaseProvider;
        this.permissionManager = permissionManager;
        this.channelManager = channelManager;
    }

    /**
     * Initializes the mute manager and starts the cleanup task.
     */
    public void initialize() {
        // Start periodic cleanup of expired mutes
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MuteManager-Cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredMutes, 1, 1, TimeUnit.MINUTES);
        logger.info("MuteManager initialized with periodic cleanup");
    }

    /**
     * Shuts down the mute manager.
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
        muteCache.clear();
        logger.info("MuteManager shutdown");
    }


    /**
     * Mutes a player in a channel.
     * 
     * Requirements:
     * - 13.1: Record mute information
     * - 13.3: Channel admin: own channels, max 1 hour
     * - 13.4: Client admin: client channels, max 24 hours
     * - 13.5: Super admin: any channel, no limit
     *
     * @param operatorId the UUID of the operator issuing the mute
     * @param targetPlayerId the UUID of the player to mute
     * @param channelId the channel ID (null for global mute)
     * @param durationMs the mute duration in milliseconds (0 for permanent)
     * @param reason the reason for the mute
     * @param operatorClientId the client ID of the operator (for scope validation)
     * @return the result of the mute operation
     */
    public MuteResult mutePlayer(UUID operatorId, UUID targetPlayerId, String channelId,
                                  long durationMs, String reason, String operatorClientId) {
        if (operatorId == null) {
            return MuteResult.badRequest("Operator ID is required");
        }
        if (targetPlayerId == null) {
            return MuteResult.badRequest("Target player ID is required");
        }

        // Get operator's permission level
        PermissionLevel operatorLevel = permissionManager.getPermissionLevel(operatorId, channelId);

        // Validate permission and scope
        MuteResult validationResult = validateMutePermission(
                operatorId, operatorLevel, channelId, durationMs, operatorClientId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        // Calculate expiration time
        long expireTime = durationMs > 0 ? System.currentTimeMillis() + durationMs : 0;

        // Create mute info
        MuteInfo muteInfo = new MuteInfo(channelId, expireTime, reason, operatorId);

        // Store in cache
        String cacheKey = channelId != null ? channelId : GLOBAL_MUTE_KEY;
        muteCache.computeIfAbsent(targetPlayerId, k -> new ConcurrentHashMap<>())
                .put(cacheKey, muteInfo);

        // Persist to database
        if (databaseProvider != null) {
            try {
                databaseProvider.saveMute(targetPlayerId, muteInfo);
            } catch (DatabaseException e) {
                logger.error("Failed to persist mute for player {}: {}", targetPlayerId, e.getMessage());
                // Continue - mute is still in cache
            }
        }

        logger.info("Player {} muted in channel {} by {} for {} ms. Reason: {}",
                targetPlayerId, channelId, operatorId, durationMs, reason);

        return MuteResult.success("Player muted successfully");
    }

    /**
     * Validates if the operator has permission to mute in the specified scope.
     */
    private MuteResult validateMutePermission(UUID operatorId, PermissionLevel operatorLevel,
                                               String channelId, long durationMs, 
                                               String operatorClientId) {
        switch (operatorLevel) {
            case SUPER_ADMIN:
                // Super admin can mute anyone, anywhere, with no duration limit
                return MuteResult.success("Validated");

            case CLIENT_ADMIN:
                // Client admin can mute in their client's channels, max 24 hours
                if (durationMs > CLIENT_ADMIN_MAX_DURATION_MS && durationMs != 0) {
                    return MuteResult.durationExceeded(
                            "Client admin mute duration cannot exceed 24 hours");
                }
                if (channelId != null) {
                    Channel channel = channelManager.getChannel(channelId);
                    if (channel == null) {
                        return MuteResult.notFound("Channel not found: " + channelId);
                    }
                    // Check if channel belongs to operator's client
                    if (channel.getScope() != ChannelScope.GLOBAL && 
                        !Objects.equals(channel.getClientId(), operatorClientId)) {
                        return MuteResult.forbidden(
                                "Client admin can only mute in their own client's channels");
                    }
                }
                return MuteResult.success("Validated");

            case CHANNEL_ADMIN:
                // Channel admin can only mute in their own channels, max 1 hour
                if (durationMs > CHANNEL_ADMIN_MAX_DURATION_MS && durationMs != 0) {
                    return MuteResult.durationExceeded(
                            "Channel admin mute duration cannot exceed 1 hour");
                }
                if (channelId == null) {
                    return MuteResult.forbidden(
                            "Channel admin cannot issue global mutes");
                }
                if (!permissionManager.isChannelAdmin(channelId, operatorId)) {
                    return MuteResult.forbidden(
                            "Channel admin can only mute in channels they manage");
                }
                return MuteResult.success("Validated");

            case PLAYER:
            default:
                return MuteResult.forbidden("Insufficient permissions to mute players");
        }
    }


    /**
     * Unmutes a player in a channel.
     *
     * @param operatorId the UUID of the operator
     * @param targetPlayerId the UUID of the player to unmute
     * @param channelId the channel ID (null for global unmute)
     * @param operatorClientId the client ID of the operator
     * @return the result of the unmute operation
     */
    public MuteResult unmutePlayer(UUID operatorId, UUID targetPlayerId, String channelId,
                                    String operatorClientId) {
        if (operatorId == null) {
            return MuteResult.badRequest("Operator ID is required");
        }
        if (targetPlayerId == null) {
            return MuteResult.badRequest("Target player ID is required");
        }

        // Get operator's permission level
        PermissionLevel operatorLevel = permissionManager.getPermissionLevel(operatorId, channelId);

        // Validate permission (same rules as mute, but no duration check)
        MuteResult validationResult = validateMutePermission(
                operatorId, operatorLevel, channelId, 0, operatorClientId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        // Remove from cache
        String cacheKey = channelId != null ? channelId : GLOBAL_MUTE_KEY;
        Map<String, MuteInfo> playerMutes = muteCache.get(targetPlayerId);
        if (playerMutes != null) {
            playerMutes.remove(cacheKey);
        }

        // Remove from database
        if (databaseProvider != null) {
            try {
                databaseProvider.deleteMute(targetPlayerId, channelId);
            } catch (DatabaseException e) {
                logger.error("Failed to delete mute for player {}: {}", targetPlayerId, e.getMessage());
            }
        }

        logger.info("Player {} unmuted in channel {} by {}", targetPlayerId, channelId, operatorId);

        return MuteResult.success("Player unmuted successfully");
    }

    /**
     * Checks if a player is muted in a specific channel.
     * 
     * Requirements:
     * - 13.2: Check mute status on message send
     * - 13.6: Auto-unmute when mute expires
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     * @return true if the player is muted
     */
    public boolean isMuted(UUID playerId, String channelId) {
        if (playerId == null) {
            return false;
        }

        Map<String, MuteInfo> playerMutes = muteCache.get(playerId);
        if (playerMutes == null) {
            return false;
        }

        // Check channel-specific mute
        String cacheKey = channelId != null ? channelId : GLOBAL_MUTE_KEY;
        MuteInfo channelMute = playerMutes.get(cacheKey);
        if (channelMute != null) {
            if (channelMute.isExpired()) {
                // Auto-unmute: remove expired mute
                playerMutes.remove(cacheKey);
                cleanupMuteFromDatabase(playerId, channelId);
                return false;
            }
            return true;
        }

        // Check global mute
        MuteInfo globalMute = playerMutes.get(GLOBAL_MUTE_KEY);
        if (globalMute != null) {
            if (globalMute.isExpired()) {
                // Auto-unmute: remove expired global mute
                playerMutes.remove(GLOBAL_MUTE_KEY);
                cleanupMuteFromDatabase(playerId, null);
                return false;
            }
            return true;
        }

        return false;
    }

    /**
     * Gets the mute info for a player in a channel.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     * @return the mute info, or null if not muted
     */
    public MuteInfo getMuteInfo(UUID playerId, String channelId) {
        if (playerId == null) {
            return null;
        }

        Map<String, MuteInfo> playerMutes = muteCache.get(playerId);
        if (playerMutes == null) {
            return null;
        }

        // Check channel-specific mute first
        String cacheKey = channelId != null ? channelId : GLOBAL_MUTE_KEY;
        MuteInfo channelMute = playerMutes.get(cacheKey);
        if (channelMute != null && !channelMute.isExpired()) {
            return channelMute;
        }

        // Check global mute
        MuteInfo globalMute = playerMutes.get(GLOBAL_MUTE_KEY);
        if (globalMute != null && !globalMute.isExpired()) {
            return globalMute;
        }

        return null;
    }

    /**
     * Gets all active mutes for a player.
     *
     * @param playerId the player UUID
     * @return list of active mute infos
     */
    public List<MuteInfo> getActiveMutes(UUID playerId) {
        if (playerId == null) {
            return Collections.emptyList();
        }

        Map<String, MuteInfo> playerMutes = muteCache.get(playerId);
        if (playerMutes == null) {
            return Collections.emptyList();
        }

        List<MuteInfo> activeMutes = new ArrayList<>();
        for (MuteInfo mute : playerMutes.values()) {
            if (!mute.isExpired()) {
                activeMutes.add(mute);
            }
        }
        return activeMutes;
    }


    /**
     * Loads mutes for a player from the database into cache.
     *
     * @param playerId the player UUID
     */
    public void loadPlayerMutes(UUID playerId) {
        if (playerId == null || databaseProvider == null) {
            return;
        }

        try {
            List<MuteInfo> mutes = databaseProvider.loadMutes(playerId);
            if (!mutes.isEmpty()) {
                Map<String, MuteInfo> playerMutes = muteCache.computeIfAbsent(
                        playerId, k -> new ConcurrentHashMap<>());
                for (MuteInfo mute : mutes) {
                    if (!mute.isExpired()) {
                        String cacheKey = mute.getChannelId() != null ? 
                                mute.getChannelId() : GLOBAL_MUTE_KEY;
                        playerMutes.put(cacheKey, mute);
                    }
                }
                logger.debug("Loaded {} mutes for player {}", mutes.size(), playerId);
            }
        } catch (DatabaseException e) {
            logger.error("Failed to load mutes for player {}: {}", playerId, e.getMessage());
        }
    }

    /**
     * Clears cached mutes for a player (when they disconnect).
     *
     * @param playerId the player UUID
     */
    public void clearPlayerMutes(UUID playerId) {
        if (playerId != null) {
            muteCache.remove(playerId);
        }
    }

    /**
     * Cleans up expired mutes from cache and database.
     */
    private void cleanupExpiredMutes() {
        int cacheCleanup = 0;
        
        // Clean up cache
        for (Map.Entry<UUID, Map<String, MuteInfo>> entry : muteCache.entrySet()) {
            Map<String, MuteInfo> playerMutes = entry.getValue();
            Iterator<Map.Entry<String, MuteInfo>> iterator = playerMutes.entrySet().iterator();
            while (iterator.hasNext()) {
                MuteInfo mute = iterator.next().getValue();
                if (mute.isExpired()) {
                    iterator.remove();
                    cacheCleanup++;
                }
            }
        }

        // Clean up database
        int dbCleanup = 0;
        if (databaseProvider != null) {
            try {
                dbCleanup = databaseProvider.cleanupExpiredMutes();
            } catch (DatabaseException e) {
                logger.error("Failed to cleanup expired mutes from database: {}", e.getMessage());
            }
        }

        if (cacheCleanup > 0 || dbCleanup > 0) {
            logger.debug("Cleaned up {} expired mutes from cache, {} from database", 
                    cacheCleanup, dbCleanup);
        }
    }

    /**
     * Helper method to clean up a single mute from database.
     */
    private void cleanupMuteFromDatabase(UUID playerId, String channelId) {
        if (databaseProvider != null) {
            try {
                databaseProvider.deleteMute(playerId, channelId);
            } catch (DatabaseException e) {
                logger.error("Failed to cleanup mute from database: {}", e.getMessage());
            }
        }
    }

    /**
     * Gets the maximum mute duration for a permission level.
     *
     * @param level the permission level
     * @return the maximum duration in milliseconds, or -1 for unlimited
     */
    public static long getMaxDuration(PermissionLevel level) {
        switch (level) {
            case SUPER_ADMIN:
                return -1; // Unlimited
            case CLIENT_ADMIN:
                return CLIENT_ADMIN_MAX_DURATION_MS;
            case CHANNEL_ADMIN:
                return CHANNEL_ADMIN_MAX_DURATION_MS;
            default:
                return 0; // Cannot mute
        }
    }

    /**
     * Checks if a duration is valid for a permission level.
     *
     * @param level the permission level
     * @param durationMs the duration in milliseconds
     * @return true if the duration is valid
     */
    public static boolean isValidDuration(PermissionLevel level, long durationMs) {
        if (durationMs < 0) {
            return false;
        }
        long maxDuration = getMaxDuration(level);
        if (maxDuration == -1) {
            return true; // Unlimited
        }
        if (maxDuration == 0) {
            return false; // Cannot mute
        }
        return durationMs == 0 || durationMs <= maxDuration;
    }

    /**
     * Gets the mute cache size (for testing).
     */
    public int getCacheSize() {
        return muteCache.size();
    }

    /**
     * Clears all cached mutes (for testing).
     */
    public void clearCache() {
        muteCache.clear();
    }
}
