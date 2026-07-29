package com.nova.link.mute;

import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.MuteInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for MuteManager.
 * 
 * Requirements: 20.3, 20.5
 */
@DisplayName("MuteManager Unit Tests")
class MuteManagerTest {

    private MuteManager muteManager;
    private PermissionManager permissionManager;
    private ChannelManager channelManager;

    private UUID superAdminId;
    private UUID clientAdminId;
    private UUID channelAdminId;
    private UUID playerId;
    private UUID targetPlayerId;

    @BeforeEach
    void setUp() {
        permissionManager = new PermissionManager();
        channelManager = new ChannelManager();
        muteManager = new MuteManager(null, permissionManager, channelManager);

        // Set up test users
        superAdminId = UUID.randomUUID();
        clientAdminId = UUID.randomUUID();
        channelAdminId = UUID.randomUUID();
        playerId = UUID.randomUUID();
        targetPlayerId = UUID.randomUUID();

        // Set up permissions
        permissionManager.setSessionDurationMs(3600000); // 1 hour
        
        // Create a test channel
        channelManager.createChannel(ChannelConfig.builder()
                .id("test-channel")
                .scope(ChannelScope.SERVER)
                .clientId("client-1")
                .build());
    }

    // ==================== mutePlayer tests ====================

    @Test
    @DisplayName("mutePlayer - super admin can mute any player")
    void mutePlayer_superAdmin_succeeds() {
        // Grant super admin session
        permissionManager.registerSuperAdmin(
                new com.nova.link.auth.SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        MuteResult result = muteManager.mutePlayer(
                superAdminId, targetPlayerId, "test-channel",
                3600000, "Test mute", "client-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(muteManager.isMuted(targetPlayerId, "test-channel")).isTrue();
    }

    @Test
    @DisplayName("mutePlayer - client admin can mute in their client's channels")
    void mutePlayer_clientAdmin_succeeds() {
        permissionManager.registerClientAdmin(clientAdminId, "client-1");

        MuteResult result = muteManager.mutePlayer(
                clientAdminId, targetPlayerId, "test-channel",
                3600000, "Test mute", "client-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(muteManager.isMuted(targetPlayerId, "test-channel")).isTrue();
    }

    @Test
    @DisplayName("mutePlayer - client admin cannot exceed 24 hour duration")
    void mutePlayer_clientAdminExceedsDuration_fails() {
        permissionManager.registerClientAdmin(clientAdminId, "client-1");

        // Try to mute for more than 24 hours
        long duration = MuteManager.CLIENT_ADMIN_MAX_DURATION_MS + 1;
        MuteResult result = muteManager.mutePlayer(
                clientAdminId, targetPlayerId, "test-channel",
                duration, "Test mute", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-400");
    }

    @Test
    @DisplayName("mutePlayer - channel admin can mute in their channel")
    void mutePlayer_channelAdmin_succeeds() {
        permissionManager.grantChannelAdmin("test-channel", channelAdminId);

        MuteResult result = muteManager.mutePlayer(
                channelAdminId, targetPlayerId, "test-channel",
                1800000, "Test mute", "client-1"); // 30 minutes

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("mutePlayer - channel admin cannot exceed 1 hour duration")
    void mutePlayer_channelAdminExceedsDuration_fails() {
        permissionManager.grantChannelAdmin("test-channel", channelAdminId);

        // Try to mute for more than 1 hour
        long duration = MuteManager.CHANNEL_ADMIN_MAX_DURATION_MS + 1;
        MuteResult result = muteManager.mutePlayer(
                channelAdminId, targetPlayerId, "test-channel",
                duration, "Test mute", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-400");
    }

    @Test
    @DisplayName("mutePlayer - regular player cannot mute")
    void mutePlayer_regularPlayer_fails() {
        MuteResult result = muteManager.mutePlayer(
                playerId, targetPlayerId, "test-channel",
                3600000, "Test mute", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-403");
    }

    @Test
    @DisplayName("mutePlayer - fails with null operator ID")
    void mutePlayer_nullOperatorId_fails() {
        MuteResult result = muteManager.mutePlayer(
                null, targetPlayerId, "test-channel",
                3600000, "Test mute", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-400");
    }

    @Test
    @DisplayName("mutePlayer - fails with null target player ID")
    void mutePlayer_nullTargetPlayerId_fails() {
        permissionManager.registerSuperAdmin(
                new com.nova.link.auth.SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        MuteResult result = muteManager.mutePlayer(
                superAdminId, null, "test-channel",
                3600000, "Test mute", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-400");
    }

    // ==================== unmutePlayer tests ====================

    @Test
    @DisplayName("unmutePlayer - super admin can unmute")
    void unmutePlayer_superAdmin_succeeds() {
        permissionManager.registerSuperAdmin(
                new com.nova.link.auth.SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        // First mute the player
        muteManager.mutePlayer(superAdminId, targetPlayerId, "test-channel",
                3600000, "Test mute", "client-1");

        // Then unmute
        MuteResult result = muteManager.unmutePlayer(
                superAdminId, targetPlayerId, "test-channel", "client-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(muteManager.isMuted(targetPlayerId, "test-channel")).isFalse();
    }

    // ==================== isMuted tests ====================

    @Test
    @DisplayName("isMuted - returns false for non-muted player")
    void isMuted_nonMutedPlayer_returnsFalse() {
        assertThat(muteManager.isMuted(targetPlayerId, "test-channel")).isFalse();
    }

    @Test
    @DisplayName("isMuted - returns false for null player ID")
    void isMuted_nullPlayerId_returnsFalse() {
        assertThat(muteManager.isMuted(null, "test-channel")).isFalse();
    }

    // ==================== getMuteInfo tests ====================

    @Test
    @DisplayName("getMuteInfo - returns mute info for muted player")
    void getMuteInfo_mutedPlayer_returnsMuteInfo() {
        permissionManager.registerSuperAdmin(
                new com.nova.link.auth.SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        muteManager.mutePlayer(superAdminId, targetPlayerId, "test-channel",
                3600000, "Test reason", "client-1");

        MuteInfo info = muteManager.getMuteInfo(targetPlayerId, "test-channel");

        assertThat(info).isNotNull();
        assertThat(info.getChannelId()).isEqualTo("test-channel");
        assertThat(info.getReason()).isEqualTo("Test reason");
        assertThat(info.getOperatorId()).isEqualTo(superAdminId);
    }

    @Test
    @DisplayName("getMuteInfo - returns null for non-muted player")
    void getMuteInfo_nonMutedPlayer_returnsNull() {
        MuteInfo info = muteManager.getMuteInfo(targetPlayerId, "test-channel");

        assertThat(info).isNull();
    }

    @Test
    @DisplayName("getMuteInfo - returns null for null player ID")
    void getMuteInfo_nullPlayerId_returnsNull() {
        MuteInfo info = muteManager.getMuteInfo(null, "test-channel");

        assertThat(info).isNull();
    }

    // ==================== getActiveMutes tests ====================

    @Test
    @DisplayName("getActiveMutes - returns all active mutes for player")
    void getActiveMutes_multipleMutes_returnsAll() {
        permissionManager.registerSuperAdmin(
                new com.nova.link.auth.SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        // Create another channel
        channelManager.createChannel(ChannelConfig.builder()
                .id("test-channel-2")
                .scope(ChannelScope.SERVER)
                .clientId("client-1")
                .build());

        // Mute in both channels
        muteManager.mutePlayer(superAdminId, targetPlayerId, "test-channel",
                3600000, "Reason 1", "client-1");
        muteManager.mutePlayer(superAdminId, targetPlayerId, "test-channel-2",
                3600000, "Reason 2", "client-1");

        List<MuteInfo> mutes = muteManager.getActiveMutes(targetPlayerId);

        assertThat(mutes).hasSize(2);
    }

    @Test
    @DisplayName("getActiveMutes - returns empty list for null player ID")
    void getActiveMutes_nullPlayerId_returnsEmptyList() {
        List<MuteInfo> mutes = muteManager.getActiveMutes(null);

        assertThat(mutes).isEmpty();
    }

    // ==================== static utility method tests ====================

    @Test
    @DisplayName("getMaxDuration - returns correct values for each level")
    void getMaxDuration_allLevels_returnsCorrectValues() {
        assertThat(MuteManager.getMaxDuration(PermissionLevel.SUPER_ADMIN)).isEqualTo(-1);
        assertThat(MuteManager.getMaxDuration(PermissionLevel.CLIENT_ADMIN))
                .isEqualTo(MuteManager.CLIENT_ADMIN_MAX_DURATION_MS);
        assertThat(MuteManager.getMaxDuration(PermissionLevel.CHANNEL_ADMIN))
                .isEqualTo(MuteManager.CHANNEL_ADMIN_MAX_DURATION_MS);
        assertThat(MuteManager.getMaxDuration(PermissionLevel.PLAYER)).isEqualTo(0);
    }

    @Test
    @DisplayName("isValidDuration - validates durations correctly")
    void isValidDuration_variousDurations_validatesCorrectly() {
        // Super admin - any duration is valid
        assertThat(MuteManager.isValidDuration(PermissionLevel.SUPER_ADMIN, 0)).isTrue();
        assertThat(MuteManager.isValidDuration(PermissionLevel.SUPER_ADMIN, Long.MAX_VALUE)).isTrue();

        // Client admin - up to 24 hours
        assertThat(MuteManager.isValidDuration(PermissionLevel.CLIENT_ADMIN, 
                MuteManager.CLIENT_ADMIN_MAX_DURATION_MS)).isTrue();
        assertThat(MuteManager.isValidDuration(PermissionLevel.CLIENT_ADMIN, 
                MuteManager.CLIENT_ADMIN_MAX_DURATION_MS + 1)).isFalse();

        // Channel admin - up to 1 hour
        assertThat(MuteManager.isValidDuration(PermissionLevel.CHANNEL_ADMIN, 
                MuteManager.CHANNEL_ADMIN_MAX_DURATION_MS)).isTrue();
        assertThat(MuteManager.isValidDuration(PermissionLevel.CHANNEL_ADMIN, 
                MuteManager.CHANNEL_ADMIN_MAX_DURATION_MS + 1)).isFalse();

        // Player - cannot mute
        assertThat(MuteManager.isValidDuration(PermissionLevel.PLAYER, 0)).isFalse();

        // Negative duration is always invalid
        assertThat(MuteManager.isValidDuration(PermissionLevel.SUPER_ADMIN, -1)).isFalse();
    }

    // ==================== clearCache tests ====================

    @Test
    @DisplayName("clearCache - clears all cached mutes")
    void clearCache_withMutes_clearsAll() {
        permissionManager.registerSuperAdmin(
                new com.nova.link.auth.SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        muteManager.mutePlayer(superAdminId, targetPlayerId, "test-channel",
                3600000, "Test mute", "client-1");

        muteManager.clearCache();

        assertThat(muteManager.getCacheSize()).isEqualTo(0);
        assertThat(muteManager.isMuted(targetPlayerId, "test-channel")).isFalse();
    }

    // ==================== clearPlayerMutes tests ====================

    @Test
    @DisplayName("clearPlayerMutes - clears mutes for specific player")
    void clearPlayerMutes_specificPlayer_clearsMutes() {
        permissionManager.registerSuperAdmin(
                new com.nova.link.auth.SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        muteManager.mutePlayer(superAdminId, targetPlayerId, "test-channel",
                3600000, "Test mute", "client-1");

        muteManager.clearPlayerMutes(targetPlayerId);

        assertThat(muteManager.isMuted(targetPlayerId, "test-channel")).isFalse();
    }
}
