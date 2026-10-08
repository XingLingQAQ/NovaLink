package com.nova.link.title;

import com.nova.link.auth.PermissionLevel;
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
 * Regression tests for P0 permission defect fix in TitleManager.
 *
 * Before fix: {@code checkTitlePermission} passed {@code senderClientId} as the
 * channelId argument to {@link PermissionManager#getPermissionLevel(UUID, String)}.
 * {@code isChannelAdmin("client-x", pid)} never hit, so CHANNEL_ADMIN was
 * downgraded to PLAYER and channel admins could not send titles.
 *
 * After fix: the call passes {@code channel.getId()}.
 */
@DisplayName("TitleManager P0 channelId parameter regression tests")
class TitleManagerPermissionTest {

    private ChannelManager channelManager;
    private PermissionManager permissionManager;
    private TitleManager titleManager;

    private UUID channelOwner;
    private UUID otherMember;
    private Channel privateChannel;

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
        permissionManager = new PermissionManager();
        titleManager = new TitleManager(channelManager, permissionManager);

        channelOwner = UUID.randomUUID();
        otherMember = UUID.randomUUID();

        ChannelConfig config = ChannelConfig.builder()
                .id("private-room-2")
                .displayName("Private Room 2")
                .scope(ChannelScope.PRIVATE)
                .clientId("web-client-y")
                .build();
        privateChannel = channelManager.createChannel(config);
        privateChannel.setOwnerId(channelOwner);
        privateChannel.addMember(channelOwner);
        privateChannel.addMember(otherMember);

        permissionManager.grantChannelAdmin(privateChannel.getId(), channelOwner);
    }

    @Test
    @DisplayName("CHANNEL_ADMIN can send a title to their private channel")
    void channelAdmin_canSendTitle() {
        assertThat(
                permissionManager.getPermissionLevel(channelOwner, privateChannel.getId()))
                .isEqualTo(PermissionLevel.CHANNEL_ADMIN);

        TitleResult result = titleManager.sendTitle(
                channelOwner,
                privateChannel.getClientId(),
                privateChannel.getId(),
                "Hello",
                null);

        assertThat(result.isSuccess())
                .as("CHANNEL_ADMIN should be able to send title; got: %s", result.getMessage())
                .isTrue();
        assertThat(result.getRecipientCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("REGRESSION: passing clientId as channelId must NOT grant CHANNEL_ADMIN")
    void clientId_isNotChannelId() {
        assertThat(
                permissionManager.getPermissionLevel(channelOwner, privateChannel.getClientId()))
                .isNotEqualTo(PermissionLevel.CHANNEL_ADMIN);
    }

    @Test
    @DisplayName("Player (not channel admin) still cannot send titles")
    void player_cannotSendTitle() {
        TitleResult result = titleManager.sendTitle(
                otherMember,
                privateChannel.getClientId(),
                privateChannel.getId(),
                "Hello",
                null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-403");
    }
}
