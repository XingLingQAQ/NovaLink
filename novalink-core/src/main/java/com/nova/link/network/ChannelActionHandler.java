package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.ban.BanResult;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.InvitationResult;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.mute.MuteManager;
import com.nova.link.mute.MuteResult;
import com.nova.link.notification.NotificationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Handles channel action packets (join/leave/create/invite/accept/kick/mute/unmute/delete).
 *
 * Requirements: 7.x, 13.x, 16.x
 */
public class ChannelActionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChannelActionHandler.class);

    private final ChannelManager channelManager;
    private final PlayerStateManager playerStateManager;
    private final DatabaseProvider databaseProvider;
    private final PrivateChannelManager privateChannelManager;
    private final InvitationManager invitationManager;
    private final PermissionManager permissionManager;
    private final MuteManager muteManager;
    private final BanManager banManager;
    private NotificationStore notificationStore;

    public ChannelActionHandler(ChannelManager channelManager,
                                PlayerStateManager playerStateManager,
                                DatabaseProvider databaseProvider,
                                PrivateChannelManager privateChannelManager,
                                InvitationManager invitationManager,
                                PermissionManager permissionManager) {
        this(channelManager, playerStateManager, databaseProvider, privateChannelManager,
                invitationManager, permissionManager, null, null);
    }

    public ChannelActionHandler(ChannelManager channelManager,
                                PlayerStateManager playerStateManager,
                                DatabaseProvider databaseProvider,
                                PrivateChannelManager privateChannelManager,
                                InvitationManager invitationManager,
                                PermissionManager permissionManager,
                                MuteManager muteManager) {
        this(channelManager, playerStateManager, databaseProvider, privateChannelManager,
                invitationManager, permissionManager, muteManager, null);
    }

    public ChannelActionHandler(ChannelManager channelManager,
                                PlayerStateManager playerStateManager,
                                DatabaseProvider databaseProvider,
                                PrivateChannelManager privateChannelManager,
                                InvitationManager invitationManager,
                                PermissionManager permissionManager,
                                MuteManager muteManager,
                                BanManager banManager) {
        this.channelManager = Objects.requireNonNull(channelManager, "channelManager");
        this.playerStateManager = Objects.requireNonNull(playerStateManager, "playerStateManager");
        this.databaseProvider = Objects.requireNonNull(databaseProvider, "databaseProvider");
        this.privateChannelManager = Objects.requireNonNull(privateChannelManager, "privateChannelManager");
        this.invitationManager = Objects.requireNonNull(invitationManager, "invitationManager");
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager");
        this.muteManager = muteManager;
        this.banManager = banManager;
    }

    /**
     * Late-binds the notification store so moderation actions can surface
     * notifications to the web panel. Optional — if never set, moderation
     * actions still work, just without notifications.
     */
    public void setNotificationStore(NotificationStore notificationStore) {
        this.notificationStore = notificationStore;
    }

    /**
     * Dispatches a channel action packet to its per-action handler (join, leave,
     * create, invite, accept, kick, mute/unmute, ban/unban, delete, who).
     *
     * <p>Rejects unauthenticated connections and null/malformed packets before
     * dispatch, and wraps any handler exception into an {@code NC-500} response
     * so a single bad action never drops the connection.
     *
     * @param connection the authenticated client connection
     * @param packet     the action request
     * @return a response packet indicating success or a coded failure
     */
    public ChannelActionResponsePacket handle(ClientConnection connection, ChannelActionPacket packet) {
        if (packet == null || packet.getAction() == null) {
            return new ChannelActionResponsePacket(false, null, "", "NC-400", "Invalid channel action packet");
        }
        if (connection == null || !connection.isAuthenticated()) {
            return new ChannelActionResponsePacket(false, packet.getAction(), packet.getChannelId(), "NC-401", "Not authenticated");
        }

        try {
            switch (packet.getAction()) {
                case JOIN:
                    return handleJoin(connection, packet);
                case LEAVE:
                    return handleLeave(connection, packet);
                case CREATE:
                    return handleCreate(connection, packet);
                case INVITE:
                    return handleInvite(connection, packet);
                case ACCEPT:
                    return handleAccept(connection, packet);
                case KICK:
                    return handleKick(connection, packet);
                case MUTE:
                    return handleMute(connection, packet);
                case UNMUTE:
                    return handleUnmute(connection, packet);
                case BAN:
                    return handleBan(connection, packet);
                case UNBAN:
                    return handleUnban(connection, packet);
                case DELETE:
                    return handleDelete(connection, packet);
                case WHO:
                    return handleWho(connection, packet);
                default:
                    return new ChannelActionResponsePacket(false, packet.getAction(), packet.getChannelId(),
                            "NC-400", "Unsupported channel action");
            }
        } catch (Exception e) {
            logger.error("Failed to handle channel action {} from client {}: {}",
                    packet.getAction(), connection.getClientId(), e.getMessage(), e);
            return new ChannelActionResponsePacket(false, packet.getAction(), packet.getChannelId(), "NC-500", "Internal server error");
        }
    }

    private ChannelActionResponsePacket handleJoin(ClientConnection connection, ChannelActionPacket packet) {
        UUID playerId = getUuid(packet.getExtra("playerId"), packet.getExtra("player_id"));
        if (playerId == null) {
            playerId = getUuid(packet.getExtra("player_uuid"), packet.getExtra("uuid"));
        }
        String playerName = firstNonBlank(packet.getExtra("playerName"), packet.getExtra("player_name"));
        String world = packet.getExtra("world");
        String platform = packet.getExtra("platform");

        if (playerId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.JOIN, packet.getChannelId(), "NC-400", "Player ID is required");
        }

        String channelId = packet.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.JOIN, "", "NC-400", "Channel ID is required");
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.JOIN, channelId, "NC-404", "Channel not found");
        }

        // Enforce client boundary for SERVER/PRIVATE channels
        if (channel.getScope() != ChannelScope.GLOBAL) {
            String clientId = connection.getClientId();
            if (clientId == null || !clientId.equals(channel.getClientId())) {
                return new ChannelActionResponsePacket(false, ChannelAction.JOIN, channelId, "NC-403", "Cross-client channel access denied");
            }
        }

        // Enforce world restrictions if provided by client
        if (channel.hasWorldFilter() && !channel.isWorldAllowed(world)) {
            return new ChannelActionResponsePacket(false, ChannelAction.JOIN, channelId, "NC-435", "Channel is restricted to specific worlds");
        }

        boolean joined;
        if (channel.getScope() == ChannelScope.PRIVATE) {
            var result = privateChannelManager.joinPrivateChannel(
                    channelId,
                    playerId,
                    connection.getClientId(),
                    packet.getPassword()
            );
            if (!result.isGranted()) {
                return new ChannelActionResponsePacket(false, ChannelAction.JOIN, channelId,
                        result.getErrorCode() != null ? result.getErrorCode() : "NC-403",
                        result.getErrorMessage() != null ? result.getErrorMessage() : "Join denied");
            }
            joined = true;
        } else {
            if (channel.isMember(playerId)) {
                // Idempotent success
                joined = true;
            } else {
                joined = channelManager.addMember(channelId, playerId);
                if (!joined) {
                    return new ChannelActionResponsePacket(false, ChannelAction.JOIN, channelId, "NC-431", "Channel is full");
                }
            }
        }

        upsertPlayerState(connection, playerId, playerName, world, channelId, platform);
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.JOIN, channelId, "", "Joined channel");
        return response;
    }

    private ChannelActionResponsePacket handleLeave(ClientConnection connection, ChannelActionPacket packet) {
        UUID playerId = getUuid(packet.getExtra("playerId"), packet.getExtra("player_id"));
        if (playerId == null) {
            playerId = getUuid(packet.getExtra("player_uuid"), packet.getExtra("uuid"));
        }
        String playerName = firstNonBlank(packet.getExtra("playerName"), packet.getExtra("player_name"));
        String platform = packet.getExtra("platform");

        if (playerId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.LEAVE, packet.getChannelId(), "NC-400", "Player ID is required");
        }

        String channelId = packet.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.LEAVE, "", "NC-400", "Channel ID is required");
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.LEAVE, channelId, "NC-404", "Channel not found");
        }

        // Enforce client boundary for SERVER/PRIVATE channels
        if (channel.getScope() != ChannelScope.GLOBAL) {
            String clientId = connection.getClientId();
            if (clientId == null || !clientId.equals(channel.getClientId())) {
                return new ChannelActionResponsePacket(false, ChannelAction.LEAVE, channelId, "NC-403", "Cross-client channel access denied");
            }
        }

        if (!channel.isMember(playerId)) {
            return new ChannelActionResponsePacket(false, ChannelAction.LEAVE, channelId, "NC-433", "Not in channel");
        }

        channelManager.removeMember(channelId, playerId);

        // Update state (keep activeChannel null if leaving the active one; client decides fallback)
        PlayerState state = playerStateManager.getOrCreateState(playerId, playerName);
        state.setClientId(connection.getClientId());
        if (platform != null && !platform.isBlank()) {
            state.setPlatform(platform);
        }
        playerStateManager.leaveChannel(playerId, channelId);

        return new ChannelActionResponsePacket(true, ChannelAction.LEAVE, channelId, "", "Left channel");
    }

    private ChannelActionResponsePacket handleCreate(ClientConnection connection, ChannelActionPacket packet) {
        UUID playerId = getUuid(packet.getExtra("playerId"), packet.getExtra("player_id"));
        if (playerId == null) {
            playerId = getUuid(packet.getExtra("player_uuid"), packet.getExtra("uuid"));
        }
        String playerName = firstNonBlank(packet.getExtra("playerName"), packet.getExtra("player_name"));
        String platform = packet.getExtra("platform");
        if (playerId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.CREATE, packet.getChannelId(), "NC-400", "Player ID is required");
        }

        String displayName = firstNonBlank(packet.getExtra("displayName"), packet.getChannelId(), "Private Channel");
        String clientId = connection.getClientId();
        if (clientId == null || clientId.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.CREATE, "", "NC-400", "Client ID is required");
        }

        PrivateChannelManager.PrivateChannelCreationResult created = privateChannelManager.createPrivateChannel(
                displayName,
                clientId,
                playerId,
                packet.getPassword()
        );

        // Creator becomes ChannelAdmin (Req 2.5)
        try {
            permissionManager.grantChannelAdmin(created.getChannelId(), playerId);
        } catch (Exception e) {
            logger.debug("Failed to grant channel admin for {} to {}: {}", created.getChannelId(), playerId, e.getMessage());
        }

        // Persist newly created channel
        try {
            databaseProvider.saveChannel(created.getChannel());
        } catch (DatabaseException e) {
            logger.warn("Failed to persist private channel {}: {}", created.getChannelId(), e.getMessage());
            // Not fatal: channel still exists in memory for this process.
        }

        upsertPlayerState(connection, playerId, playerName, null, created.getChannelId(), platform);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.CREATE, created.getChannelId(), "", "Private channel created");
        response.addExtra("password", created.getPassword());
        response.addExtra("passwordGenerated", String.valueOf(created.isPasswordGenerated()));
        response.addExtra("displayName", created.getChannel().getDisplayName());
        return response;
    }

    private ChannelActionResponsePacket handleInvite(ClientConnection connection, ChannelActionPacket packet) {
        UUID inviterId = getUuid(packet.getExtra("playerId"), packet.getExtra("player_id"));
        if (inviterId == null) {
            inviterId = getUuid(packet.getExtra("player_uuid"), packet.getExtra("uuid"));
        }
        if (inviterId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.INVITE, packet.getChannelId(), "NC-400", "Inviter ID is required");
        }

        String channelId = packet.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.INVITE, "", "NC-400", "Channel ID is required");
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.INVITE, channelId, "NC-404", "Channel not found");
        }

        if (channel.getScope() != ChannelScope.PRIVATE) {
            return new ChannelActionResponsePacket(false, ChannelAction.INVITE, channelId, "NC-400", "Invitations are only supported for private channels");
        }

        // Only channel admins can create invites for private channels.
        if (!permissionManager.isChannelAdmin(channelId, inviterId)) {
            return new ChannelActionResponsePacket(false, ChannelAction.INVITE, channelId, "NC-403", "Channel admin permission required");
        }

        try {
            var invitation = invitationManager.createInvitation(channelId, inviterId);
            ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.INVITE, channelId, "", "Invitation created");
            response.addExtra("code", invitation.getCode());
            response.addExtra("expireTime", String.valueOf(invitation.getExpireTime()));
            return response;
        } catch (IllegalArgumentException e) {
            return new ChannelActionResponsePacket(false, ChannelAction.INVITE, channelId, "NC-404", e.getMessage());
        } catch (DatabaseException e) {
            return new ChannelActionResponsePacket(false, ChannelAction.INVITE, channelId, "NC-510", "Database error while creating invitation");
        }
    }

    private ChannelActionResponsePacket handleAccept(ClientConnection connection, ChannelActionPacket packet) {
        UUID playerId = getUuid(packet.getExtra("playerId"), packet.getExtra("player_id"));
        if (playerId == null) {
            playerId = getUuid(packet.getExtra("player_uuid"), packet.getExtra("uuid"));
        }
        String playerName = firstNonBlank(packet.getExtra("playerName"), packet.getExtra("player_name"));
        String world = packet.getExtra("world");
        String platform = packet.getExtra("platform");

        if (playerId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.ACCEPT, packet.getChannelId(), "NC-400", "Player ID is required");
        }

        String inviteCode = packet.getChannelId();
        if (inviteCode == null || inviteCode.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.ACCEPT, "", "NC-400", "Invite code is required");
        }

        InvitationResult result;
        try {
            result = invitationManager.acceptInvitation(inviteCode, playerId, connection.getClientId());
        } catch (DatabaseException e) {
            return new ChannelActionResponsePacket(false, ChannelAction.ACCEPT, inviteCode, "NC-510", "Database error while accepting invitation");
        }

        if (!result.isSuccess()) {
            return new ChannelActionResponsePacket(false, ChannelAction.ACCEPT, inviteCode,
                    result.getErrorCode() != null ? result.getErrorCode() : "NC-400",
                    result.getErrorMessage() != null ? result.getErrorMessage() : "Invite invalid");
        }

        String channelId = result.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.ACCEPT, inviteCode, "NC-500", "Invite accepted but channelId missing");
        }

        upsertPlayerState(connection, playerId, playerName, world, channelId, platform);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.ACCEPT, channelId, "", "Invitation accepted");
        response.addExtra("code", inviteCode.toUpperCase());
        return response;
    }

    private ChannelActionResponsePacket handleKick(ClientConnection connection, ChannelActionPacket packet) {
        UUID operatorId = resolveOperatorId(packet);
        UUID targetId = resolveTargetId(packet);
        String channelId = packet.getChannelId();

        if (operatorId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.KICK, channelId, "NC-400", "Operator ID is required");
        }
        if (targetId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.KICK, channelId, "NC-400", "Target player ID is required");
        }
        if (channelId == null || channelId.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.KICK, "", "NC-400", "Channel ID is required");
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.KICK, channelId, "NC-404", "Channel not found");
        }

        ChannelActionResponsePacket permissionError = requireModerationPermission(
                ChannelAction.KICK, operatorId, channel, connection.getClientId());
        if (permissionError != null) {
            return permissionError;
        }

        if (!channel.isMember(targetId)) {
            return new ChannelActionResponsePacket(false, ChannelAction.KICK, channelId, "NC-433", "Target is not in channel");
        }

        channelManager.removeMember(channelId, targetId);
        try {
            playerStateManager.leaveChannel(targetId, channelId);
        } catch (Exception e) {
            logger.debug("Failed to update player state after kick for {}: {}", targetId, e.getMessage());
        }

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.KICK, channelId, "", "Player kicked");
        // Stamp targetId + operatorName so the originating client + any forwarded
        // connection can render the target-side kick notification (UX-DESIGN §5).
        response.addExtra("targetId", targetId.toString());
        String kickOperatorName = firstNonBlank(packet.getExtra("operatorName"), packet.getExtra("operator_name"));
        if (kickOperatorName != null && !kickOperatorName.isEmpty()) {
            response.addExtra("operatorName", kickOperatorName);
        }

        // Surface the kick to the web panel notification feed.
        if (notificationStore != null) {
            try {
                PlayerState ps = playerStateManager.getPlayerState(targetId);
                String targetName = ps != null && ps.getPlayerName() != null ? ps.getPlayerName() : targetId.toString();
                String opLabel = kickOperatorName != null && !kickOperatorName.isEmpty()
                        ? kickOperatorName : operatorId.toString();
                notificationStore.createNotification(
                        "Player Kicked",
                        opLabel + " kicked " + targetName + " from " + channelId,
                        "warning");
            } catch (Exception e) {
                logger.debug("Failed to emit kick notification: {}", e.getMessage());
            }
        }
        return response;
    }

    private ChannelActionResponsePacket handleMute(ClientConnection connection, ChannelActionPacket packet) {
        if (muteManager == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.MUTE, packet.getChannelId(),
                    "NC-503", "Mute system is not available");
        }

        UUID operatorId = resolveOperatorId(packet);
        UUID targetId = resolveTargetId(packet);
        String channelId = packet.getChannelId();
        if (operatorId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.MUTE, channelId, "NC-400", "Operator ID is required");
        }
        if (targetId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.MUTE, channelId, "NC-400", "Target player ID is required");
        }
        if (channelId == null || channelId.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.MUTE, "", "NC-400", "Channel ID is required");
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.MUTE, channelId, "NC-404", "Channel not found");
        }

        ChannelActionResponsePacket permissionError = requireModerationPermission(
                ChannelAction.MUTE, operatorId, channel, connection.getClientId());
        if (permissionError != null) {
            return permissionError;
        }

        long durationMs = parseDurationMs(packet);
        String reason = firstNonBlank(packet.getExtra("reason"), packet.getExtra("muteReason"), "Muted by admin");

        MuteResult result = muteManager.mutePlayer(
                operatorId,
                targetId,
                channelId,
                durationMs,
                reason,
                connection.getClientId()
        );

        if (!result.isSuccess()) {
            return new ChannelActionResponsePacket(false, ChannelAction.MUTE, channelId,
                    result.getErrorCode() != null ? result.getErrorCode() : "NC-400",
                    result.getMessage() != null ? result.getMessage() : "Mute failed");
        }

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.MUTE, channelId, "", "Player muted");
        response.addExtra("targetId", targetId.toString());
        response.addExtra("durationMs", String.valueOf(durationMs));
        // Echo operatorName + duration (seconds) so a cross-server target
        // connection receiving the forwarded response can render the mute notice
        // (UX-DESIGN §5). The target plugin has no pending context for this push.
        String operatorName = firstNonBlank(packet.getExtra("operatorName"), packet.getExtra("operator_name"));
        if (operatorName != null && !operatorName.isEmpty()) {
            response.addExtra("operatorName", operatorName);
        }
        String durationSeconds = firstNonBlank(packet.getExtra("duration"), packet.getExtra("durationSeconds"));
        if (durationSeconds != null && !durationSeconds.isEmpty()) {
            response.addExtra("duration", durationSeconds);
        }
        return response;
    }

    private ChannelActionResponsePacket handleUnmute(ClientConnection connection, ChannelActionPacket packet) {
        if (muteManager == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.UNMUTE, packet.getChannelId(),
                    "NC-503", "Mute system is not available");
        }

        UUID operatorId = resolveOperatorId(packet);
        UUID targetId = resolveTargetId(packet);
        String channelId = packet.getChannelId();
        if (operatorId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.UNMUTE, channelId, "NC-400", "Operator ID is required");
        }
        if (targetId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.UNMUTE, channelId, "NC-400", "Target player ID is required");
        }
        if (channelId == null || channelId.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.UNMUTE, "", "NC-400", "Channel ID is required");
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.UNMUTE, channelId, "NC-404", "Channel not found");
        }

        ChannelActionResponsePacket permissionError = requireModerationPermission(
                ChannelAction.UNMUTE, operatorId, channel, connection.getClientId());
        if (permissionError != null) {
            return permissionError;
        }

        MuteResult result = muteManager.unmutePlayer(operatorId, targetId, channelId, connection.getClientId());
        if (!result.isSuccess()) {
            return new ChannelActionResponsePacket(false, ChannelAction.UNMUTE, channelId,
                    result.getErrorCode() != null ? result.getErrorCode() : "NC-400",
                    result.getMessage() != null ? result.getMessage() : "Unmute failed");
        }

        return new ChannelActionResponsePacket(true, ChannelAction.UNMUTE, channelId, "", "Player unmuted");
    }

    private ChannelActionResponsePacket handleBan(ClientConnection connection, ChannelActionPacket packet) {
        if (banManager == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.BAN, packet.getChannelId(),
                    "NC-503", "Ban system is not available");
        }

        UUID operatorId = resolveOperatorId(packet);
        UUID targetId = resolveTargetId(packet);
        String channelId = packet.getChannelId();
        if (operatorId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.BAN, channelId, "NC-400", "Operator ID is required");
        }
        if (targetId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.BAN, channelId, "NC-400", "Target player ID is required");
        }
        // channelId may be null/blank for a global ban — do not reject here.

        // Validate channel exists when a channel-scoped ban is requested.
        if (channelId != null && !channelId.isBlank()) {
            Channel channel = channelManager.getChannel(channelId);
            if (channel == null) {
                return new ChannelActionResponsePacket(false, ChannelAction.BAN, channelId, "NC-404", "Channel not found");
            }
            ChannelActionResponsePacket permissionError = requireModerationPermission(
                    ChannelAction.BAN, operatorId, channel, connection.getClientId());
            if (permissionError != null) {
                return permissionError;
            }
        }

        long durationMs = parseDurationMs(packet);
        String reason = firstNonBlank(packet.getExtra("reason"), packet.getExtra("banReason"), "Banned by admin");

        BanResult result = banManager.banPlayer(
                operatorId,
                targetId,
                (channelId == null || channelId.isBlank()) ? null : channelId,
                durationMs,
                reason,
                connection.getClientId()
        );

        if (!result.isSuccess()) {
            return new ChannelActionResponsePacket(false, ChannelAction.BAN, channelId,
                    result.getErrorCode() != null ? result.getErrorCode() : "NC-400",
                    result.getMessage() != null ? result.getMessage() : "Ban failed");
        }

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.BAN, channelId, "", "Player banned");
        response.addExtra("targetId", targetId.toString());
        response.addExtra("durationMs", String.valueOf(durationMs));
        String operatorName = firstNonBlank(packet.getExtra("operatorName"), packet.getExtra("operator_name"));
        if (operatorName != null && !operatorName.isEmpty()) {
            response.addExtra("operatorName", operatorName);
        }
        String durationSeconds = firstNonBlank(packet.getExtra("duration"), packet.getExtra("durationSeconds"));
        if (durationSeconds != null && !durationSeconds.isEmpty()) {
            response.addExtra("duration", durationSeconds);
        }
        return response;
    }

    private ChannelActionResponsePacket handleUnban(ClientConnection connection, ChannelActionPacket packet) {
        if (banManager == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.UNBAN, packet.getChannelId(),
                    "NC-503", "Ban system is not available");
        }

        UUID operatorId = resolveOperatorId(packet);
        UUID targetId = resolveTargetId(packet);
        String channelId = packet.getChannelId();
        if (operatorId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.UNBAN, channelId, "NC-400", "Operator ID is required");
        }
        if (targetId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.UNBAN, channelId, "NC-400", "Target player ID is required");
        }

        if (channelId != null && !channelId.isBlank()) {
            Channel channel = channelManager.getChannel(channelId);
            if (channel == null) {
                return new ChannelActionResponsePacket(false, ChannelAction.UNBAN, channelId, "NC-404", "Channel not found");
            }
            ChannelActionResponsePacket permissionError = requireModerationPermission(
                    ChannelAction.UNBAN, operatorId, channel, connection.getClientId());
            if (permissionError != null) {
                return permissionError;
            }
        }

        BanResult result = banManager.unbanPlayer(
                operatorId, targetId,
                (channelId == null || channelId.isBlank()) ? null : channelId,
                connection.getClientId());
        if (!result.isSuccess()) {
            return new ChannelActionResponsePacket(false, ChannelAction.UNBAN, channelId,
                    result.getErrorCode() != null ? result.getErrorCode() : "NC-400",
                    result.getMessage() != null ? result.getMessage() : "Unban failed");
        }

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.UNBAN, channelId, "", "Player unbanned");
        response.addExtra("targetId", targetId.toString());
        return response;
    }

    private ChannelActionResponsePacket handleDelete(ClientConnection connection, ChannelActionPacket packet) {
        UUID operatorId = resolveOperatorId(packet);
        String channelId = packet.getChannelId();
        if (operatorId == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.DELETE, channelId, "NC-400", "Operator ID is required");
        }
        if (channelId == null || channelId.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.DELETE, "", "NC-400", "Channel ID is required");
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.DELETE, channelId, "NC-404", "Channel not found");
        }

        // Only private channels can be deleted via protocol (config channels are managed by backend).
        if (channel.getScope() != ChannelScope.PRIVATE) {
            return new ChannelActionResponsePacket(false, ChannelAction.DELETE, channelId, "NC-403",
                    "Only private channels can be deleted");
        }

        PermissionLevel level = permissionManager.getPermissionLevel(operatorId, channelId);
        boolean allowed = level == PermissionLevel.SUPER_ADMIN
                || permissionManager.isChannelAdmin(channelId, operatorId)
                || (channel.getOwnerId() != null && channel.getOwnerId().equals(operatorId));
        if (!allowed) {
            return new ChannelActionResponsePacket(false, ChannelAction.DELETE, channelId, "NC-403",
                    "Insufficient permissions to delete channel");
        }

        // Enforce client boundary for non-super-admins
        if (level != PermissionLevel.SUPER_ADMIN) {
            String clientId = connection.getClientId();
            if (clientId == null || !clientId.equals(channel.getClientId())) {
                return new ChannelActionResponsePacket(false, ChannelAction.DELETE, channelId, "NC-403",
                        "Cross-client channel delete denied");
            }
        }

        boolean deleted = channelManager.deleteChannel(channelId);
        if (!deleted) {
            return new ChannelActionResponsePacket(false, ChannelAction.DELETE, channelId, "NC-404", "Channel not found");
        }

        privateChannelManager.removeTrackedId(channelId);
        try {
            databaseProvider.deleteChannel(channelId);
        } catch (Exception e) {
            logger.warn("Failed to delete channel {} from database: {}", channelId, e.getMessage());
        }

        return new ChannelActionResponsePacket(true, ChannelAction.DELETE, channelId, "", "Channel deleted");
    }

    /**
     * Handles {@link ChannelAction#WHO}: lists the online members of a channel.
     *
     * <p>Read-only query — no state change. The response {@code extra} map is
     * populated with:
     * <ul>
     *   <li>{@code members} — comma-separated display names (UUID fallback when
     *       a cached name is unavailable), case-insensitively sorted</li>
     *   <li>{@code memberCount} — number of online members returned</li>
     *   <li>{@code displayName} — channel display name (for client header rendering)</li>
     * </ul>
     * The requesting player's identity ({@code requesterId} / {@code requesterName}
     * from the request extra) is echoed back so the client can route the
     * asynchronous response to the player who ran {@code /nc who}.
     */
    private ChannelActionResponsePacket handleWho(ClientConnection connection, ChannelActionPacket packet) {
        String channelId = packet.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            return new ChannelActionResponsePacket(false, ChannelAction.WHO, "", "NC-400", "Channel ID is required");
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return new ChannelActionResponsePacket(false, ChannelAction.WHO, channelId, "NC-404", "Channel not found");
        }

        // Enforce client boundary for SERVER/PRIVATE channels — WHO is a read on
        // channel membership, so the same cross-client rule as JOIN applies.
        if (channel.getScope() != ChannelScope.GLOBAL) {
            String clientId = connection.getClientId();
            if (clientId == null || !clientId.equals(channel.getClientId())) {
                return new ChannelActionResponsePacket(false, ChannelAction.WHO, channelId, "NC-403",
                        "Cross-client channel access denied");
            }
        }

        // Resolve display names for each member UUID, preferring the cached
        // player name; fall back to the UUID string when no state is cached
        // (e.g. a member who joined but whose state has not propagated yet).
        Collection<UUID> memberIds = channel.getMembers();
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<String> unknownIds = new ArrayList<>();
        for (UUID memberId : memberIds) {
            PlayerState state = playerStateManager.getPlayerState(memberId);
            if (state != null && state.getPlayerName() != null && !state.getPlayerName().isBlank()) {
                names.add(state.getPlayerName());
            } else {
                unknownIds.add(memberId.toString());
            }
        }
        // Append UUID fallbacks after named entries, preserving sort stability.
        names.addAll(unknownIds);

        String membersCsv = String.join(", ", names);
        String memberCount = String.valueOf(names.size());

        ChannelActionResponsePacket response =
                new ChannelActionResponsePacket(true, ChannelAction.WHO, channelId, "", "Channel members");
        response.addExtra("members", membersCsv);
        response.addExtra("memberCount", memberCount);
        response.addExtra("displayName",
                channel.getDisplayName() != null ? channel.getDisplayName() : channelId);
        // Echo requester identity so the client can deliver the async response
        // to the player who ran /nc who (the response travels over a server-
        // scoped connection, not a per-player channel).
        String requesterId = firstNonBlank(packet.getExtra("requesterId"), packet.getExtra("requester_id"));
        if (requesterId != null && !requesterId.isBlank()) {
            response.addExtra("requesterId", requesterId);
        }
        String requesterName = firstNonBlank(packet.getExtra("requesterName"), packet.getExtra("requester_name"));
        if (requesterName != null && !requesterName.isBlank()) {
            response.addExtra("requesterName", requesterName);
        }
        return response;
    }

    /**
     * Validates that the operator can moderate the given channel.
     *
     * @return null when allowed, otherwise a failure response
     */
    private ChannelActionResponsePacket requireModerationPermission(ChannelAction action,
                                                                    UUID operatorId,
                                                                    Channel channel,
                                                                    String operatorClientId) {
        // Console-originated actions (UUID 00000000-0000-0000-0000-000000000000)
        // bypass permission checks — console always has full authority.
        if (operatorId != null && operatorId.getMostSignificantBits() == 0L && operatorId.getLeastSignificantBits() == 0L) {
            return null;
        }
        PermissionLevel level = permissionManager.getPermissionLevel(operatorId, channel.getId());
        switch (level) {
            case SUPER_ADMIN:
                return null;
            case CLIENT_ADMIN:
                if (channel.getScope() != ChannelScope.GLOBAL
                        && operatorClientId != null
                        && !operatorClientId.equals(channel.getClientId())) {
                    return new ChannelActionResponsePacket(false, action, channel.getId(), "NC-403",
                            "Client admin can only moderate their own client's channels");
                }
                return null;
            case CHANNEL_ADMIN:
                if (!permissionManager.isChannelAdmin(channel.getId(), operatorId)) {
                    return new ChannelActionResponsePacket(false, action, channel.getId(), "NC-403",
                            "Channel admin can only moderate channels they manage");
                }
                return null;
            case PLAYER:
            default:
                return new ChannelActionResponsePacket(false, action, channel.getId(), "NC-403",
                        "Insufficient permissions");
        }
    }

    private UUID resolveOperatorId(ChannelActionPacket packet) {
        UUID id = getUuid(packet.getExtra("operatorId"), packet.getExtra("operator_id"));
        if (id != null) {
            return id;
        }
        // Some clients reuse playerId for the operator
        id = getUuid(packet.getExtra("playerId"), packet.getExtra("player_id"));
        if (id != null) {
            return id;
        }
        return getUuid(packet.getExtra("player_uuid"), packet.getExtra("uuid"));
    }

    private UUID resolveTargetId(ChannelActionPacket packet) {
        UUID id = getUuid(packet.getExtra("targetId"), packet.getExtra("target_id"));
        if (id != null) {
            return id;
        }
        id = getUuid(packet.getExtra("targetUuid"), packet.getExtra("target_uuid"));
        if (id != null) {
            return id;
        }
        // Cross-server fallback: resolve by player name across all connected clients.
        // This allows moderation commands (mute/kick/unmute) to target players on
        // other servers when the originating plugin cannot resolve a local UUID.
        String targetName = firstNonBlank(packet.getExtra("targetName"), packet.getExtra("target_name"));
        if (targetName != null && !targetName.isBlank()) {
            for (PlayerState state : playerStateManager.getAllPlayerStates()) {
                if (targetName.equalsIgnoreCase(state.getPlayerName())) {
                    return state.getPlayerId();
                }
            }
        }
        return null;
    }

    /**
     * Parses mute duration from packet extras.
     * Accepts either milliseconds ({@code durationMs}) or seconds ({@code duration}).
     * Returns 0 for permanent when value is missing/invalid.
     */
    private long parseDurationMs(ChannelActionPacket packet) {
        String durationMsRaw = firstNonBlank(packet.getExtra("durationMs"), packet.getExtra("duration_ms"));
        if (durationMsRaw != null) {
            try {
                long value = Long.parseLong(durationMsRaw.trim());
                return Math.max(0L, value);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }

        String durationSecRaw = firstNonBlank(packet.getExtra("duration"), packet.getExtra("durationSeconds"));
        if (durationSecRaw != null) {
            try {
                long seconds = Long.parseLong(durationSecRaw.trim());
                if (seconds <= 0) {
                    return 0L;
                }
                return TimeUnit.SECONDS.toMillis(seconds);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0L;
    }

    private void upsertPlayerState(ClientConnection connection,
                                   UUID playerId,
                                   String playerName,
                                   String world,
                                   String activeChannel,
                                   String platform) {
        PlayerState state = playerStateManager.getOrCreateState(playerId, playerName);
        if (playerName != null && !playerName.isBlank()) {
            state.setPlayerName(playerName);
        }
        state.setClientId(connection.getClientId());
        if (world != null && !world.isBlank()) {
            state.setCurrentWorld(world);
        }
        if (platform != null && !platform.isBlank()) {
            state.setPlatform(platform);
        }
        if (activeChannel != null && !activeChannel.isBlank()) {
            state.addJoinedChannel(activeChannel);
            state.setActiveChannel(activeChannel);
            playerStateManager.markDirty(playerId);
        }
    }

    private UUID getUuid(String primary, String fallback) {
        String raw = primary != null ? primary : fallback;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private String firstNonBlank(String a, String b, String c) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        if (c != null && !c.isBlank()) return c;
        return null;
    }
}


