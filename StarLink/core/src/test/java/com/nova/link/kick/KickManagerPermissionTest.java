package com.nova.link.kick;

import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for P0 permission defect fix in KickManager.
 *
 * Before fix: {@code checkKickPermission} passed {@code operatorClientId} /
 * {@code channel.getClientId()} as the channelId argument to
 * {@link PermissionManager#getPermissionLevel(UUID, String)}, which is a
 * channelId parameter. Result: {@code isChannelAdmin("client-x", pid)} never
 * hit, so CHANNEL_ADMIN was downgraded to PLAYER and channel admins could not
 * kick.
 *
 * After fix: the call passes {@code channel.getId()}, so channel admins are
 * correctly resolved.
 */
@DisplayName("KickManager P0 channelId parameter regression tests")
class KickManagerPermissionTest {

    private ChannelManager channelManager;
    private PermissionManager permissionManager;
    private KickManager kickManager;

    private UUID channelOwner;
    private UUID otherMember;
    private Channel privateChannel;

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
        permissionManager = new PermissionManager();
        kickManager = new KickManager(channelManager, permissionManager);

        channelOwner = UUID.randomUUID();
        otherMember = UUID.randomUUID();

        ChannelConfig config = ChannelConfig.builder()
                .id("private-room-1")
                .displayName("Private Room")
                .scope(ChannelScope.PRIVATE)
                .clientId("web-client-x")
                .build();
        privateChannel = channelManager.createChannel(config);
        privateChannel.setOwnerId(channelOwner);
        privateChannel.addMember(channelOwner);
        privateChannel.addMember(otherMember);

        // Grant channel admin on the real channelId (this is how PermissionManager
        // distinguishes CHANNEL_ADMIN from PLAYER).
        permissionManager.grantChannelAdmin(privateChannel.getId(), channelOwner);
    }

    @Test
    @DisplayName("CHANNEL_ADMIN can kick a regular member from their private channel")
    void channelAdmin_canKickMember() {
        // Sanity: without the fix, getPermissionLevel(channelOwner, "web-client-x")
        // would never match the channel admin grant, returning PLAYER and rejecting
        // the kick. With the fix, getPermissionLevel(channelOwner, "private-room-1")
        // returns CHANNEL_ADMIN and the kick proceeds.
        assertThat(
                permissionManager.getPermissionLevel(channelOwner, privateChannel.getId()))
                .isEqualTo(com.nova.link.auth.PermissionLevel.CHANNEL_ADMIN);

        KickResult result = kickManager.kickPlayer(
                channelOwner,
                privateChannel.getClientId(),
                otherMember,
                privateChannel.getId());

        assertThat(result.isSuccess())
                .as("CHANNEL_ADMIN should be able to kick; got: %s", result.getMessage())
                .isTrue();
        assertThat(privateChannel.isMember(otherMember)).isFalse();
    }

    @Test
    @DisplayName("REGRESSION: passing clientId as channelId must NOT grant CHANNEL_ADMIN")
    void clientId_isNotChannelId() {
        // Documents the misconception: clientId "web-client-x" was passed where
        // channelId was expected. Such a lookup should NOT be CHANNEL_ADMIN.
        assertThat(
                permissionManager.getPermissionLevel(channelOwner, privateChannel.getClientId()))
                .isNotEqualTo(com.nova.link.auth.PermissionLevel.CHANNEL_ADMIN);
    }

    @Test
    @DisplayName("Player (not channel admin) still cannot kick")
    void player_cannotKick() {
        KickResult result = kickManager.kickPlayer(
                otherMember,
                privateChannel.getClientId(),
                channelOwner,
                privateChannel.getId());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-403");
    }
}
