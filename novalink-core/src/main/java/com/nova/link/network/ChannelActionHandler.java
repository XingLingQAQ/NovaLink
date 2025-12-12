package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
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
import com.nova.link.auth.PermissionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Handles channel action packets (join/leave/create/invite/accept).
 *
 * Notes:
 * - This handler focuses on the core channel lifecycle features used by the Bukkit client.
 * - Other actions (KICK/MUTE/UNMUTE/DELETE) can be implemented incrementally.
 */
public class ChannelActionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChannelActionHandler.class);

    private final ChannelManager channelManager;
    private final PlayerStateManager playerStateManager;
    private final DatabaseProvider databaseProvider;
    private final PrivateChannelManager privateChannelManager;
    private final InvitationManager invitationManager;
    private final PermissionManager permissionManager;

    public ChannelActionHandler(ChannelManager channelManager,
                                PlayerStateManager playerStateManager,
                                DatabaseProvider databaseProvider,
                                PrivateChannelManager privateChannelManager,
                                InvitationManager invitationManager,
                                PermissionManager permissionManager) {
        this.channelManager = Objects.requireNonNull(channelManager, "channelManager");
        this.playerStateManager = Objects.requireNonNull(playerStateManager, "playerStateManager");
        this.databaseProvider = Objects.requireNonNull(databaseProvider, "databaseProvider");
        this.privateChannelManager = Objects.requireNonNull(privateChannelManager, "privateChannelManager");
        this.invitationManager = Objects.requireNonNull(invitationManager, "invitationManager");
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager");
    }

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
                default:
                    // Keep backward compatibility with previous "accept everything" behavior.
                    return new ChannelActionResponsePacket(true, packet.getAction(), packet.getChannelId(), "", "Action accepted");
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

        upsertPlayerState(connection, playerId, playerName, world, channelId);
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.JOIN, channelId, "", "Joined channel");
        return response;
    }

    private ChannelActionResponsePacket handleLeave(ClientConnection connection, ChannelActionPacket packet) {
        UUID playerId = getUuid(packet.getExtra("playerId"), packet.getExtra("player_id"));
        if (playerId == null) {
            playerId = getUuid(packet.getExtra("player_uuid"), packet.getExtra("uuid"));
        }
        String playerName = firstNonBlank(packet.getExtra("playerName"), packet.getExtra("player_name"));

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
        playerStateManager.leaveChannel(playerId, channelId);

        return new ChannelActionResponsePacket(true, ChannelAction.LEAVE, channelId, "", "Left channel");
    }

    private ChannelActionResponsePacket handleCreate(ClientConnection connection, ChannelActionPacket packet) {
        UUID playerId = getUuid(packet.getExtra("playerId"), packet.getExtra("player_id"));
        if (playerId == null) {
            playerId = getUuid(packet.getExtra("player_uuid"), packet.getExtra("uuid"));
        }
        String playerName = firstNonBlank(packet.getExtra("playerName"), packet.getExtra("player_name"));
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

        upsertPlayerState(connection, playerId, playerName, null, created.getChannelId());

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

        upsertPlayerState(connection, playerId, playerName, world, channelId);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.ACCEPT, channelId, "", "Invitation accepted");
        response.addExtra("code", inviteCode.toUpperCase());
        return response;
    }

    private void upsertPlayerState(ClientConnection connection,
                                   UUID playerId,
                                   String playerName,
                                   String world,
                                   String activeChannel) {
        PlayerState state = playerStateManager.getOrCreateState(playerId, playerName);
        if (playerName != null && !playerName.isBlank()) {
            state.setPlayerName(playerName);
        }
        state.setClientId(connection.getClientId());
        if (world != null && !world.isBlank()) {
            state.setCurrentWorld(world);
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


