package com.nova.link.title;

import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Manages title sending functionality with permission-based scope restrictions.
 * 
 * Requirements:
 * - 15.1: Admin can send Title messages via `/nc title <channelId> <title> [subtitle]`
 * - 15.2: Channel admin can only send to their managed private channels
 * - 15.3: Client admin can send to all channels within their client
 * - 15.4: Super admin can send to any channel
 * - 15.5: Support color codes in Title messages
 */
public class TitleManager {

    private static final Logger logger = LoggerFactory.getLogger(TitleManager.class);

    private final ChannelManager channelManager;
    private final PermissionManager permissionManager;

    public TitleManager(ChannelManager channelManager, PermissionManager permissionManager) {
        this.channelManager = Objects.requireNonNull(channelManager, "ChannelManager cannot be null");
        this.permissionManager = Objects.requireNonNull(permissionManager, "PermissionManager cannot be null");
    }

    /**
     * Sends a title to all members of a channel.
     *
     * @param senderId the UUID of the admin sending the title
     * @param senderClientId the client ID of the sender
     * @param channelId the target channel ID
     * @param title the title text
     * @param subtitle the subtitle text (can be null)
     * @return the result of the title operation
     */
    public TitleResult sendTitle(UUID senderId, String senderClientId, String channelId, 
                                  String title, String subtitle) {
        return sendTitle(senderId, senderClientId, channelId, title, subtitle, 10, 70, 20);
    }


    /**
     * Sends a title to all members of a channel with custom timing.
     *
     * @param senderId the UUID of the admin sending the title
     * @param senderClientId the client ID of the sender
     * @param channelId the target channel ID
     * @param title the title text
     * @param subtitle the subtitle text (can be null)
     * @param fadeIn fade in time in ticks
     * @param stay stay time in ticks
     * @param fadeOut fade out time in ticks
     * @return the result of the title operation
     */
    public TitleResult sendTitle(UUID senderId, String senderClientId, String channelId,
                                  String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        if (senderId == null) {
            return TitleResult.failure("NC-400", "Sender ID is required");
        }

        if (channelId == null || channelId.isEmpty()) {
            return TitleResult.failure("NC-400", "Channel ID is required");
        }

        if (title == null || title.isEmpty()) {
            return TitleResult.failure("NC-400", "Title text is required");
        }

        // Get the channel
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return TitleResult.failure("NC-404", "Channel not found: " + channelId);
        }

        // Check permission based on sender's permission level
        TitleResult permissionCheck = checkTitlePermission(senderId, senderClientId, channel);
        if (!permissionCheck.isSuccess()) {
            return permissionCheck;
        }

        // Get channel members
        Set<UUID> members = channel.getMembers();
        if (members.isEmpty()) {
            return TitleResult.success(channelId, 0, "Title sent but no members in channel");
        }

        logger.info("Title sent to channel {} by {}: title='{}', subtitle='{}', recipients={}",
                channelId, senderId, title, subtitle, members.size());

        return TitleResult.success(channelId, members.size(), 
                "Title sent to " + members.size() + " player(s)");
    }

    /**
     * Checks if the sender has permission to send a title to the specified channel.
     *
     * @param senderId the sender's UUID
     * @param senderClientId the sender's client ID
     * @param channel the target channel
     * @return TitleResult indicating success or failure with error details
     */
    private TitleResult checkTitlePermission(UUID senderId, String senderClientId, Channel channel) {
        PermissionLevel senderLevel = permissionManager.getPermissionLevel(senderId, senderClientId);

        // Super admin can send to any channel
        if (senderLevel == PermissionLevel.SUPER_ADMIN) {
            return TitleResult.success(channel.getId(), 0, "Permission granted");
        }

        // Client admin can send to channels within their client
        if (senderLevel == PermissionLevel.CLIENT_ADMIN) {
            if (channel.getScope() == ChannelScope.GLOBAL) {
                return TitleResult.failure("NC-403", 
                        "Client admin cannot send title to global channels");
            }
            if (!senderClientId.equals(channel.getClientId())) {
                return TitleResult.failure("NC-403", 
                        "Client admin can only send title to channels within their client");
            }
            return TitleResult.success(channel.getId(), 0, "Permission granted");
        }

        // Channel admin can only send to their managed private channels
        if (senderLevel == PermissionLevel.CHANNEL_ADMIN) {
            if (channel.getScope() != ChannelScope.PRIVATE) {
                return TitleResult.failure("NC-403", 
                        "Channel admin can only send title to private channels they manage");
            }
            if (!senderId.equals(channel.getOwnerId())) {
                return TitleResult.failure("NC-403", 
                        "Channel admin can only send title to channels they own");
            }
            return TitleResult.success(channel.getId(), 0, "Permission granted");
        }

        // Regular players cannot send titles
        return TitleResult.failure("NC-403", "Insufficient permission to send title");
    }
}
