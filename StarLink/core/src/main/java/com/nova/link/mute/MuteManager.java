package com.nova.link.mute;

import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.MuteInfo;
import com.nova.link.moderation.AbstractModerationManager;
import com.nova.link.moderation.DurationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages mute operations for players in channels.
 *
 * <p>Cache, lifecycle (cleanup executor), console-sentinel bypass and
 * trusted-operator bypass are inherited from
 * {@link AbstractModerationManager}. This class keeps the mute-specific
 * validation logic, the static duration helpers ({@link #getMaxDuration} /
 * {@link #isValidDuration}) and the public {@code mutePlayer} /
 * {@code unmutePlayer} signatures.
 *
 * Requirements:
 * - 13.1: Record mute information when admin executes mute command
 * - 13.2: Reject messages from muted players and return mute notification
 * - 13.3: Channel admin: own channels, max 1 hour
 * - 13.4: Client admin: client channels, max 24 hours
 * - 13.5: Super admin: any channel, no limit
 * - 13.6: Auto-unmute when mute expires
 */
public class MuteManager extends AbstractModerationManager<MuteInfo> {

    private static final Logger logger = LoggerFactory.getLogger(MuteManager.class);

    /** Maximum mute duration for channel admins: 1 hour in milliseconds */
    public static final long CHANNEL_ADMIN_MAX_DURATION_MS = 60 * 60 * 1000L;

    /** Maximum mute duration for client admins: 24 hours in milliseconds */
    public static final long CLIENT_ADMIN_MAX_DURATION_MS = 24 * 60 * 60 * 1000L;

    public MuteManager(DatabaseProvider databaseProvider,
                       PermissionManager permissionManager,
                       ChannelManager channelManager) {
        super(databaseProvider, permissionManager, channelManager);
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
        return mutePlayer(operatorId, targetPlayerId, channelId, durationMs, reason, operatorClientId, false);
    }

    /**
     * Mutes a player in a channel, optionally treating the operator as already
     * authorized. {@code trustedOperator=true} is used by the REST layer where
     * the caller's role was already checked against the panel RBAC matrix but
     * the (panel-derived) operator UUID is unknown to the PermissionManager.
     * This keeps operation attribution (the real operator id is recorded)
     * without requiring the console sentinel bypass.
     *
     * @param trustedOperator when true, skip PermissionManager lookup and treat
     *                        the operator as SUPER_ADMIN (caller pre-authorized)
     */
    public MuteResult mutePlayer(UUID operatorId, UUID targetPlayerId, String channelId,
                                  long durationMs, String reason, String operatorClientId,
                                  boolean trustedOperator) {
        if (operatorId == null) {
            return MuteResult.badRequest("Operator ID is required");
        }
        if (targetPlayerId == null) {
            return MuteResult.badRequest("Target player ID is required");
        }

        // Console-originated actions (UUID 00000000-0000-0000-0000-000000000000)
        // bypass permission validation — console always has full authority. This
        // mirrors the bypass in ChannelActionHandler.requireModerationPermission;
        // without it, mutePlayer would reject console mutes as PLAYER-level even
        // though the handler already authorized the console sentinel.
        // Trusted operators (panel RBAC pre-authorized) get the same bypass while
        // keeping their own UUID for attribution.
        PermissionLevel operatorLevel = resolveOperatorLevel(operatorId, channelId, trustedOperator);

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
        putInCache(targetPlayerId, cacheKeyFor(channelId), muteInfo);

        // Persist to database
        persistToDb(targetPlayerId, muteInfo);

        logger.info("Player {} muted in channel {} by {} for {} ms. Reason: {}",
                targetPlayerId, channelId, operatorId, durationMs, reason);

        // Notify the optional notification store (panel feed).
        if (notificationStore != null) {
            try {
                String scope = channelId != null ? channelId : "global";
                notificationStore.createNotification(
                        "Player Muted",
                        "Player " + targetPlayerId + " muted in " + scope
                                + " for " + DurationUtils.describeDuration(durationMs)
                                + ". Reason: " + (reason != null ? reason : "-"),
                        "warning");
            } catch (Exception e) {
                logger.debug("Failed to create mute notification: {}", e.getMessage());
            }
        }

        // Hook for subclasses (ban ejects the player; mute does nothing).
        onAppliedSuccessfully(targetPlayerId, channelId, channelId == null);

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
        return unmutePlayer(operatorId, targetPlayerId, channelId, operatorClientId, false);
    }

    /**
     * Unmutes a player, optionally treating the operator as already authorized
     * (see {@link #mutePlayer(UUID, UUID, String, long, String, String, boolean)}).
     */
    public MuteResult unmutePlayer(UUID operatorId, UUID targetPlayerId, String channelId,
                                    String operatorClientId, boolean trustedOperator) {
        if (operatorId == null) {
            return MuteResult.badRequest("Operator ID is required");
        }
        if (targetPlayerId == null) {
            return MuteResult.badRequest("Target player ID is required");
        }

        // Console-originated actions bypass permission validation. Trusted
        // operators (panel RBAC pre-authorized) get the same bypass while
        // keeping their own UUID for attribution.
        PermissionLevel operatorLevel = resolveOperatorLevel(operatorId, channelId, trustedOperator);

        // Validate permission (same rules as mute, but no duration check)
        MuteResult validationResult = validateMutePermission(
                operatorId, operatorLevel, channelId, 0, operatorClientId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        // Remove from cache
        removeFromCache(targetPlayerId, cacheKeyFor(channelId));

        // Remove from database
        deleteFromDatabase(targetPlayerId, channelId);

        logger.info("Player {} unmuted in channel {} by {}", targetPlayerId, channelId, operatorId);

        if (notificationStore != null) {
            try {
                String scope = channelId != null ? channelId : "global";
                notificationStore.createNotification(
                        "Player Unmuted",
                        "Player " + targetPlayerId + " unmuted in " + scope,
                        "info");
            } catch (Exception e) {
                logger.debug("Failed to create unmute notification: {}", e.getMessage());
            }
        }

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
        return isModerated(playerId, channelId);
    }

    /**
     * Gets the mute info for a player in a channel.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     * @return the mute info, or null if not muted
     */
    public MuteInfo getMuteInfo(UUID playerId, String channelId) {
        return getInfo(playerId, channelId);
    }

    /**
     * Gets all active mutes for a player.
     *
     * @param playerId the player UUID
     * @return list of active mute infos
     */
    public List<MuteInfo> getActiveMutes(UUID playerId) {
        return getActive(playerId);
    }

    /**
     * Gets all active mutes across all players from the in-memory cache.
     *
     * <p>Combined with {@link #loadAllMutes()} at startup, this gives REST
     * listings visibility of offline players' mutes as well.
     *
     * @return map of player UUID to that player's active mutes
     */
    public Map<UUID, List<MuteInfo>> getAllActiveMutes() {
        return getAllActive();
    }

    /**
     * Loads all active mutes from the database into the cache. Called once
     * during startup so persisted mutes stay effective across a backend
     * restart ({@code isMuted} only consults the cache).
     *
     * @return the number of mutes loaded
     */
    public int loadAllMutes() {
        return loadAll();
    }

    /**
     * Loads mutes for a player from the database into cache.
     *
     * @param playerId the player UUID
     */
    public void loadPlayerMutes(UUID playerId) {
        loadPlayer(playerId);
    }

    /**
     * Clears cached mutes for a player (when they disconnect).
     *
     * @param playerId the player UUID
     */
    public void clearPlayerMutes(UUID playerId) {
        clearPlayer(playerId);
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

    // ============================ AbstractModerationManager hooks ============================

    @Override
    protected String cleanupThreadName() {
        return "MuteManager-Cleanup";
    }

    @Override
    protected Logger logger() {
        return logger;
    }

    @Override
    protected String managerLogName() {
        return "MuteManager";
    }

    @Override
    protected String noun() {
        return "mute";
    }

    @Override
    protected String getChannelId(MuteInfo info) {
        return info.getChannelId();
    }

    @Override
    protected boolean isExpired(MuteInfo info) {
        return info.isExpired();
    }

    @Override
    protected void saveToDb(UUID playerId, MuteInfo info) throws DatabaseException {
        databaseProvider.saveMute(playerId, info);
    }

    @Override
    protected void deleteFromDb(UUID playerId, String channelId) throws DatabaseException {
        databaseProvider.deleteMute(playerId, channelId);
    }

    @Override
    protected Map<UUID, List<MuteInfo>> loadAllFromDb() throws DatabaseException {
        return databaseProvider.getAllActiveMutes();
    }

    @Override
    protected List<MuteInfo> loadFromDb(UUID playerId) throws DatabaseException {
        return databaseProvider.loadMutes(playerId);
    }

    @Override
    protected int cleanupExpiredFromDb() throws DatabaseException {
        return databaseProvider.cleanupExpiredMutes();
    }
}
