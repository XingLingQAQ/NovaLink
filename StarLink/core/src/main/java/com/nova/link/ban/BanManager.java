package com.nova.link.ban;

import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.BanInfo;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.moderation.AbstractModerationManager;
import com.nova.link.moderation.DurationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages ban operations for players in channels or globally.
 *
 * <p>Mirrors {@link com.nova.link.mute.MuteManager} with the same permission
 * tiers and console-sentinel bypass. Unlike a mute, a successful ban also
 * removes the player from the channel membership so an online banned player
 * is immediately ejected. A global ban (channelId == null) removes the player
 * from every channel they have joined — implemented via the
 * {@link #onAppliedSuccessfully} hook.
 *
 * Requirements: ban feature — player ban management
 */
public class BanManager extends AbstractModerationManager<BanInfo> {

    private static final Logger logger = LoggerFactory.getLogger(BanManager.class);

    /** Maximum ban duration for channel admins: 1 hour in milliseconds */
    public static final long CHANNEL_ADMIN_MAX_DURATION_MS = 60 * 60 * 1000L;

    /** Maximum ban duration for client admins: 24 hours in milliseconds */
    public static final long CLIENT_ADMIN_MAX_DURATION_MS = 24 * 60 * 60 * 1000L;

    public BanManager(DatabaseProvider databaseProvider,
                      PermissionManager permissionManager,
                      ChannelManager channelManager) {
        super(databaseProvider, permissionManager, channelManager);
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
        return banPlayer(operatorId, targetPlayerId, channelId, durationMs, reason, operatorClientId, false);
    }

    /**
     * Bans a player, optionally treating the operator as already authorized.
     * {@code trustedOperator=true} is used by the REST layer where the caller's
     * role was already checked against the panel RBAC matrix but the
     * (panel-derived) operator UUID is unknown to the PermissionManager. This
     * keeps operation attribution (the real operator id is recorded) without
     * requiring the console sentinel bypass.
     *
     * @param trustedOperator when true, skip PermissionManager lookup and treat
     *                        the operator as SUPER_ADMIN (caller pre-authorized)
     */
    public BanResult banPlayer(UUID operatorId, UUID targetPlayerId, String channelId,
                               long durationMs, String reason, String operatorClientId,
                               boolean trustedOperator) {
        if (operatorId == null) {
            return BanResult.badRequest("Operator ID is required");
        }
        if (targetPlayerId == null) {
            return BanResult.badRequest("Target player ID is required");
        }

        // Console-originated actions (UUID 00000000-0000-0000-0000-000000000000)
        // bypass permission validation — console always has full authority.
        // Trusted operators (panel RBAC pre-authorized) get the same bypass while
        // keeping their own UUID for attribution.
        PermissionLevel operatorLevel = resolveOperatorLevel(operatorId, channelId, trustedOperator);

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
        putInCache(targetPlayerId, cacheKeyFor(channelId), banInfo);

        // Persist to database
        persistToDb(targetPlayerId, banInfo);

        logger.info("Player {} banned in channel {} by {} for {} ms. Reason: {}",
                targetPlayerId, channelId, operatorId, durationMs, reason);

        // Persist + broadcast a notification when the store is wired.
        if (notificationStore != null) {
            try {
                String scope = channelId != null ? channelId : "global";
                notificationStore.createNotification(
                        "Player Banned",
                        "Player " + targetPlayerId + " banned in " + scope
                                + " for " + DurationUtils.describeDuration(durationMs)
                                + ". Reason: " + (reason != null ? reason : "-"),
                        "warning");
            } catch (Exception e) {
                logger.debug("Failed to create ban notification: {}", e.getMessage());
            }
        }

        // Eject the banned player from channel membership (ban-specific).
        onAppliedSuccessfully(targetPlayerId, channelId, channelId == null);

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
        return unbanPlayer(operatorId, targetPlayerId, channelId, operatorClientId, false);
    }

    /**
     * Unbans a player, optionally treating the operator as already authorized
     * (see {@link #banPlayer(UUID, UUID, String, long, String, String, boolean)}).
     */
    public BanResult unbanPlayer(UUID operatorId, UUID targetPlayerId, String channelId,
                                 String operatorClientId, boolean trustedOperator) {
        if (operatorId == null) {
            return BanResult.badRequest("Operator ID is required");
        }
        if (targetPlayerId == null) {
            return BanResult.badRequest("Target player ID is required");
        }

        // Console-originated actions bypass permission validation. Trusted
        // operators (panel RBAC pre-authorized) get the same bypass while
        // keeping their own UUID for attribution.
        PermissionLevel operatorLevel = resolveOperatorLevel(operatorId, channelId, trustedOperator);

        // Validate permission (same rules as ban, but no duration check)
        BanResult validationResult = validateBanPermission(
                operatorId, operatorLevel, channelId, 0, operatorClientId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        // Remove from cache
        removeFromCache(targetPlayerId, cacheKeyFor(channelId));

        // Remove from database
        deleteFromDatabase(targetPlayerId, channelId);

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
        return isModerated(playerId, channelId);
    }

    /**
     * Gets the ban info for a player in a channel.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID
     * @return the ban info, or null if not banned
     */
    public BanInfo getBanInfo(UUID playerId, String channelId) {
        return getInfo(playerId, channelId);
    }

    /**
     * Gets all active bans for a player.
     *
     * @param playerId the player UUID
     * @return list of active ban infos
     */
    public List<BanInfo> getActiveBans(UUID playerId) {
        return getActive(playerId);
    }

    /**
     * Gets all active bans across all players from the in-memory cache.
     *
     * <p>Combined with {@link #loadAllBans()} at startup, this gives REST
     * listings visibility of offline players' bans as well.
     *
     * @return map of player UUID to that player's active bans
     */
    public Map<UUID, List<BanInfo>> getAllActiveBans() {
        return getAllActive();
    }

    /**
     * Loads all active bans from the database into the cache. Called once
     * during startup so persisted bans stay effective across a backend
     * restart ({@code isBanned} only consults the cache).
     *
     * @return the number of bans loaded
     */
    public int loadAllBans() {
        return loadAll();
    }

    /**
     * Loads bans for a player from the database into cache.
     *
     * @param playerId the player UUID
     */
    public void loadPlayerBans(UUID playerId) {
        loadPlayer(playerId);
    }

    /**
     * Clears cached bans for a player (when they disconnect).
     *
     * @param playerId the player UUID
     */
    public void clearPlayerBans(UUID playerId) {
        clearPlayer(playerId);
    }

    // ============================ AbstractModerationManager hooks ============================

    /**
     * Ban-specific hook: eject the banned player from channel membership so an
     * online banned player is immediately removed. A global ban removes the
     * player from every joined channel.
     */
    @Override
    protected void onAppliedSuccessfully(UUID playerId, String channelId, boolean isGlobal) {
        if (!isGlobal) {
            channelManager.removeMember(channelId, playerId);
        } else {
            // Global ban: remove from every joined channel.
            for (Channel channel : channelManager.getAllChannels()) {
                if (channel.isMember(playerId)) {
                    channelManager.removeMember(channel.getId(), playerId);
                }
            }
        }
    }

    @Override
    protected String cleanupThreadName() {
        return "BanManager-Cleanup";
    }

    @Override
    protected Logger logger() {
        return logger;
    }

    @Override
    protected String managerLogName() {
        return "BanManager";
    }

    @Override
    protected String noun() {
        return "ban";
    }

    @Override
    protected String getChannelId(BanInfo info) {
        return info.getChannelId();
    }

    @Override
    protected boolean isExpired(BanInfo info) {
        return info.isExpired();
    }

    @Override
    protected void saveToDb(UUID playerId, BanInfo info) throws DatabaseException {
        databaseProvider.saveBan(playerId, info);
    }

    @Override
    protected void deleteFromDb(UUID playerId, String channelId) throws DatabaseException {
        databaseProvider.deleteBan(playerId, channelId);
    }

    @Override
    protected Map<UUID, List<BanInfo>> loadAllFromDb() throws DatabaseException {
        return databaseProvider.getAllActiveBans();
    }

    @Override
    protected List<BanInfo> loadFromDb(UUID playerId) throws DatabaseException {
        return databaseProvider.loadBans(playerId);
    }

    @Override
    protected int cleanupExpiredFromDb() throws DatabaseException {
        return databaseProvider.cleanupExpiredBans();
    }
}
