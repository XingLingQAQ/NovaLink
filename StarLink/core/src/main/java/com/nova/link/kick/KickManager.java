package com.nova.link.kick;

import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Manages kick functionality with permission-based scope restrictions.
 * 
 * Requirements:
 * - 16.1: Admin can kick players via `/nc kick <player> [channelId]`
 * - 16.2: Channel admin can only kick from their managed private channels
 * - 16.3: Client admin can kick from all channels within their client
 * - 16.4: Super admin can kick from any channel
 * - 16.5: Kicked player is moved to default channel
 */
public class KickManager {

    private static final Logger logger = LoggerFactory.getLogger(KickManager.class);

    /** Default channel ID to move kicked players to */
    private static final String DEFAULT_CHANNEL_ID = "local";

    private final ChannelManager channelManager;
    private final PermissionManager permissionManager;
    private String defaultChannelId;

    public KickManager(ChannelManager channelManager, PermissionManager permissionManager) {
        this.channelManager = Objects.requireNonNull(channelManager, "ChannelManager cannot be null");
        this.permissionManager = Objects.requireNonNull(permissionManager, "PermissionManager cannot be null");
        this.defaultChannelId = DEFAULT_CHANNEL_ID;
    }

    /**
     * Sets the default channel ID for kicked players.
     *
     * @param defaultChannelId the default channel ID
     */
    public void setDefaultChannelId(String defaultChannelId) {
        this.defaultChannelId = defaultChannelId != null ? defaultChannelId : DEFAULT_CHANNEL_ID;
    }

    /**
     * Gets the default channel ID.
     *
     * @return the default channel ID
     */
    public String getDefaultChannelId() {
        return defaultChannelId;
    }


    /**
     * Kicks a player from a channel and moves them to the default channel.
     *
     * @param operatorId the UUID of the admin performing the kick
     * @param operatorClientId the client ID of the operator
     * @param targetPlayerId the UUID of the player to kick
     * @param channelId the channel ID to kick from (if null, kicks from current channel)
     * @return the result of the kick operation
     */
    public KickResult kickPlayer(UUID operatorId, String operatorClientId, 
                                  UUID targetPlayerId, String channelId) {
        if (operatorId == null) {
            return KickResult.failure("NC-400", "Operator ID is required");
        }

        if (targetPlayerId == null) {
            return KickResult.failure("NC-400", "Target player ID is required");
        }

        if (channelId == null || channelId.isEmpty()) {
            return KickResult.failure("NC-400", "Channel ID is required");
        }

        // Get the channel
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return KickResult.failure("NC-404", "Channel not found: " + channelId);
        }

        // Check if target is a member of the channel
        if (!channel.isMember(targetPlayerId)) {
            return KickResult.failure("NC-404", "Player is not a member of channel: " + channelId);
        }

        // Check permission based on operator's permission level
        KickResult permissionCheck = checkKickPermission(operatorId, operatorClientId, 
                                                          targetPlayerId, channel);
        if (!permissionCheck.isSuccess()) {
            return permissionCheck;
        }

        // Cannot kick the channel owner from their own private channel
        if (channel.getScope() == ChannelScope.PRIVATE && 
            targetPlayerId.equals(channel.getOwnerId())) {
            return KickResult.failure("NC-403", "Cannot kick the channel owner from their own channel");
        }

        // Remove player from channel
        boolean removed = channel.removeMember(targetPlayerId);
        if (!removed) {
            return KickResult.failure("NC-500", "Failed to remove player from channel");
        }

        // Add player to default channel
        String targetDefaultChannel = findDefaultChannel(operatorClientId);
        Channel defaultChannel = channelManager.getChannel(targetDefaultChannel);
        if (defaultChannel != null) {
            defaultChannel.addMember(targetPlayerId);
        }

        logger.info("Player {} kicked from channel {} by {}, moved to {}",
                targetPlayerId, channelId, operatorId, targetDefaultChannel);

        return KickResult.success(channelId, targetDefaultChannel,
                "Player kicked from channel and moved to default channel");
    }

    /**
     * Checks if the operator has permission to kick from the specified channel.
     *
     * @param operatorId the operator's UUID
     * @param operatorClientId the operator's client ID
     * @param targetPlayerId the target player's UUID
     * @param channel the target channel
     * @return KickResult indicating success or failure with error details
     */
    private KickResult checkKickPermission(UUID operatorId, String operatorClientId,
                                            UUID targetPlayerId, Channel channel) {
        PermissionLevel operatorLevel = permissionManager.getPermissionLevel(operatorId, operatorClientId);
        PermissionLevel targetLevel = permissionManager.getPermissionLevel(targetPlayerId, channel.getClientId());

        // Cannot kick someone with equal or higher permission level
        if (!operatorLevel.isHigherThan(targetLevel) && operatorLevel != PermissionLevel.SUPER_ADMIN) {
            return KickResult.failure("NC-403", 
                    "Cannot kick a player with equal or higher permission level");
        }

        // Super admin can kick from any channel
        if (operatorLevel == PermissionLevel.SUPER_ADMIN) {
            return KickResult.success(channel.getId(), null, "Permission granted");
        }

        // Client admin can kick from channels within their client
        if (operatorLevel == PermissionLevel.CLIENT_ADMIN) {
            if (channel.getScope() == ChannelScope.GLOBAL) {
                return KickResult.failure("NC-403", 
                        "Client admin cannot kick from global channels");
            }
            if (!operatorClientId.equals(channel.getClientId())) {
                return KickResult.failure("NC-403", 
                        "Client admin can only kick from channels within their client");
            }
            return KickResult.success(channel.getId(), null, "Permission granted");
        }

        // Channel admin can only kick from their managed private channels
        if (operatorLevel == PermissionLevel.CHANNEL_ADMIN) {
            if (channel.getScope() != ChannelScope.PRIVATE) {
                return KickResult.failure("NC-403", 
                        "Channel admin can only kick from private channels they manage");
            }
            if (!operatorId.equals(channel.getOwnerId())) {
                return KickResult.failure("NC-403", 
                        "Channel admin can only kick from channels they own");
            }
            return KickResult.success(channel.getId(), null, "Permission granted");
        }

        // Regular players cannot kick
        return KickResult.failure("NC-403", "Insufficient permission to kick players");
    }

    /**
     * Finds the appropriate default channel for a client.
     *
     * @param clientId the client ID
     * @return the default channel ID
     */
    private String findDefaultChannel(String clientId) {
        // First try to find a client-specific default channel
        if (clientId != null) {
            Channel clientDefault = channelManager.getChannel(clientId + "_" + defaultChannelId);
            if (clientDefault != null) {
                return clientDefault.getId();
            }
        }
        
        // Fall back to global default
        return defaultChannelId;
    }
}
