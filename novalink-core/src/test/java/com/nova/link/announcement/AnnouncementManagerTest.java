package com.nova.link.announcement;

import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for AnnouncementManager.
 * 
 * Requirements: 20.4, 20.5
 */
@DisplayName("AnnouncementManager Unit Tests")
class AnnouncementManagerTest {

    private AnnouncementManager announcementManager;
    private PermissionManager permissionManager;
    private ChannelManager channelManager;

    private UUID superAdminId;
    private UUID clientAdminId;
    private UUID channelAdminId;
    private UUID playerId;

    private List<String> sentAnnouncements;

    @BeforeEach
    void setUp() {
        permissionManager = new PermissionManager();
        channelManager = new ChannelManager();
        announcementManager = new AnnouncementManager(permissionManager, channelManager);
        announcementManager.initialize();

        sentAnnouncements = new ArrayList<>();
        announcementManager.setAnnouncementSender((channelId, content) -> {
            sentAnnouncements.add(channelId + ":" + content);
        });

        // Set up test users
        superAdminId = UUID.randomUUID();
        clientAdminId = UUID.randomUUID();
        channelAdminId = UUID.randomUUID();
        playerId = UUID.randomUUID();

        // Set up permissions
        permissionManager.setSessionDurationMs(3600000);

        // Create test channels
        channelManager.createChannel(ChannelConfig.builder()
                .id("global-channel")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("server-channel")
                .scope(ChannelScope.SERVER)
                .clientId("client-1")
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("private-channel")
                .scope(ChannelScope.PRIVATE)
                .clientId("client-1")
                .build());
    }

    @AfterEach
    void tearDown() {
        announcementManager.shutdown();
    }

    // ==================== sendImmediateAnnouncement tests ====================

    @Test
    @DisplayName("sendImmediateAnnouncement - super admin can send to any channel")
    void sendImmediateAnnouncement_superAdmin_succeeds() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult result = announcementManager.sendImmediateAnnouncement(
                superAdminId, "global-channel", "Test announcement", "client-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(sentAnnouncements).contains("global-channel:Test announcement");
    }

    @Test
    @DisplayName("sendImmediateAnnouncement - client admin can send to their client's channels")
    void sendImmediateAnnouncement_clientAdmin_succeeds() {
        permissionManager.registerClientAdmin(clientAdminId, "client-1");

        AnnouncementResult result = announcementManager.sendImmediateAnnouncement(
                clientAdminId, "server-channel", "Test announcement", "client-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(sentAnnouncements).contains("server-channel:Test announcement");
    }

    @Test
    @DisplayName("sendImmediateAnnouncement - channel admin can send to their private channel")
    void sendImmediateAnnouncement_channelAdmin_succeeds() {
        permissionManager.grantChannelAdmin("private-channel", channelAdminId);

        AnnouncementResult result = announcementManager.sendImmediateAnnouncement(
                channelAdminId, "private-channel", "Test announcement", "client-1");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("sendImmediateAnnouncement - regular player cannot send")
    void sendImmediateAnnouncement_regularPlayer_fails() {
        AnnouncementResult result = announcementManager.sendImmediateAnnouncement(
                playerId, "global-channel", "Test announcement", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-403");
    }

    @Test
    @DisplayName("sendImmediateAnnouncement - fails with null operator ID")
    void sendImmediateAnnouncement_nullOperatorId_fails() {
        AnnouncementResult result = announcementManager.sendImmediateAnnouncement(
                null, "global-channel", "Test announcement", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-400");
    }

    @Test
    @DisplayName("sendImmediateAnnouncement - fails with empty channel ID")
    void sendImmediateAnnouncement_emptyChannelId_fails() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult result = announcementManager.sendImmediateAnnouncement(
                superAdminId, "", "Test announcement", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-400");
    }

    @Test
    @DisplayName("sendImmediateAnnouncement - fails with empty content")
    void sendImmediateAnnouncement_emptyContent_fails() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult result = announcementManager.sendImmediateAnnouncement(
                superAdminId, "global-channel", "", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-400");
    }

    // ==================== sendBroadcast tests ====================

    @Test
    @DisplayName("sendBroadcast - super admin can broadcast to all channels")
    void sendBroadcast_superAdmin_succeeds() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult result = announcementManager.sendBroadcast(
                superAdminId, "Broadcast message", null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(sentAnnouncements).hasSize(3); // 3 channels
    }

    @Test
    @DisplayName("sendBroadcast - super admin can broadcast to specific channels")
    void sendBroadcast_specificChannels_succeeds() {
        grantSuperAdmin(superAdminId);

        List<String> targets = List.of("global-channel", "server-channel");
        AnnouncementResult result = announcementManager.sendBroadcast(
                superAdminId, "Broadcast message", targets);

        assertThat(result.isSuccess()).isTrue();
        assertThat(sentAnnouncements).hasSize(2);
    }

    @Test
    @DisplayName("sendBroadcast - non-super admin cannot broadcast")
    void sendBroadcast_nonSuperAdmin_fails() {
        permissionManager.registerClientAdmin(clientAdminId, "client-1");

        AnnouncementResult result = announcementManager.sendBroadcast(
                clientAdminId, "Broadcast message", null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-403");
    }

    // ==================== createJoinAnnouncement tests ====================

    @Test
    @DisplayName("createJoinAnnouncement - creates join announcement successfully")
    void createJoinAnnouncement_superAdmin_succeeds() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult result = announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "Welcome {player}!", "client-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAnnouncement()).isNotNull();
        assertThat(result.getAnnouncement().getType()).isEqualTo(AnnouncementType.JOIN);
    }

    @Test
    @DisplayName("createJoinAnnouncement - fails with empty content")
    void createJoinAnnouncement_emptyContent_fails() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult result = announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-400");
    }

    // ==================== createScheduledAnnouncement tests ====================

    @Test
    @DisplayName("createScheduledAnnouncement - creates scheduled announcement")
    void createScheduledAnnouncement_validCron_succeeds() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult result = announcementManager.createScheduledAnnouncement(
                superAdminId, "global-channel", "Scheduled message",
                "0 * * * *", "client-1"); // Every hour

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAnnouncement()).isNotNull();
        assertThat(result.getAnnouncement().getType()).isEqualTo(AnnouncementType.SCHEDULED);
        assertThat(result.getAnnouncement().getCronExpression()).isEqualTo("0 * * * *");
    }

    @Test
    @DisplayName("createScheduledAnnouncement - fails with invalid cron expression")
    void createScheduledAnnouncement_invalidCron_fails() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult result = announcementManager.createScheduledAnnouncement(
                superAdminId, "global-channel", "Scheduled message",
                "invalid-cron", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-400");
    }

    @Test
    @DisplayName("createScheduledAnnouncement - fails with empty cron expression")
    void createScheduledAnnouncement_emptyCron_fails() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult result = announcementManager.createScheduledAnnouncement(
                superAdminId, "global-channel", "Scheduled message",
                "", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-400");
    }

    // ==================== deleteAnnouncement tests ====================

    @Test
    @DisplayName("deleteAnnouncement - deletes existing announcement")
    void deleteAnnouncement_existingAnnouncement_succeeds() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult createResult = announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "Welcome!", "client-1");
        String announcementId = createResult.getAnnouncement().getId();

        AnnouncementResult deleteResult = announcementManager.deleteAnnouncement(
                superAdminId, announcementId, "client-1");

        assertThat(deleteResult.isSuccess()).isTrue();
        assertThat(announcementManager.getAnnouncement(announcementId)).isNull();
    }

    @Test
    @DisplayName("deleteAnnouncement - fails for non-existent announcement")
    void deleteAnnouncement_nonExistent_fails() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult result = announcementManager.deleteAnnouncement(
                superAdminId, "non-existent-id", "client-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-404");
    }

    // ==================== getJoinAnnouncements tests ====================

    @Test
    @DisplayName("getJoinAnnouncements - returns join announcements for channel")
    void getJoinAnnouncements_withAnnouncements_returnsContents() {
        grantSuperAdmin(superAdminId);

        announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "Welcome message 1", "client-1");
        announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "Welcome message 2", "client-1");

        List<String> contents = announcementManager.getJoinAnnouncements("global-channel");

        assertThat(contents).hasSize(2);
        assertThat(contents).contains("Welcome message 1", "Welcome message 2");
    }

    @Test
    @DisplayName("getJoinAnnouncements - returns empty list for channel without announcements")
    void getJoinAnnouncements_noAnnouncements_returnsEmptyList() {
        List<String> contents = announcementManager.getJoinAnnouncements("global-channel");

        assertThat(contents).isEmpty();
    }

    @Test
    @DisplayName("getJoinAnnouncements - returns empty list for null channel")
    void getJoinAnnouncements_nullChannel_returnsEmptyList() {
        List<String> contents = announcementManager.getJoinAnnouncements(null);

        assertThat(contents).isEmpty();
    }

    // ==================== triggerJoinAnnouncements tests ====================

    @Test
    @DisplayName("triggerJoinAnnouncements - sends announcements with player placeholder")
    void triggerJoinAnnouncements_withPlaceholder_replacesPlayer() {
        grantSuperAdmin(superAdminId);

        announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "Welcome {player}!", "client-1");

        UUID joiningPlayerId = UUID.randomUUID();
        announcementManager.triggerJoinAnnouncements("global-channel", joiningPlayerId, "TestPlayer");

        assertThat(sentAnnouncements).contains("global-channel:Welcome TestPlayer!");
    }

    // ==================== getAnnouncement tests ====================

    @Test
    @DisplayName("getAnnouncement - returns announcement by ID")
    void getAnnouncement_existingId_returnsAnnouncement() {
        grantSuperAdmin(superAdminId);

        AnnouncementResult createResult = announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "Test content", "client-1");
        String announcementId = createResult.getAnnouncement().getId();

        Announcement announcement = announcementManager.getAnnouncement(announcementId);

        assertThat(announcement).isNotNull();
        assertThat(announcement.getContent()).isEqualTo("Test content");
    }

    @Test
    @DisplayName("getAnnouncement - returns null for non-existent ID")
    void getAnnouncement_nonExistentId_returnsNull() {
        Announcement announcement = announcementManager.getAnnouncement("non-existent");

        assertThat(announcement).isNull();
    }

    // ==================== getAnnouncementsByChannel tests ====================

    @Test
    @DisplayName("getAnnouncementsByChannel - returns announcements for channel")
    void getAnnouncementsByChannel_withAnnouncements_returnsAll() {
        grantSuperAdmin(superAdminId);

        announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "Announcement 1", "client-1");
        announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "Announcement 2", "client-1");
        announcementManager.createJoinAnnouncement(
                superAdminId, "server-channel", "Other channel", "client-1");

        List<Announcement> announcements = announcementManager.getAnnouncementsByChannel("global-channel");

        assertThat(announcements).hasSize(2);
    }

    @Test
    @DisplayName("getAnnouncementsByChannel - returns empty list for null channel")
    void getAnnouncementsByChannel_nullChannel_returnsEmptyList() {
        List<Announcement> announcements = announcementManager.getAnnouncementsByChannel(null);

        assertThat(announcements).isEmpty();
    }

    // ==================== getAllAnnouncements tests ====================

    @Test
    @DisplayName("getAllAnnouncements - returns all announcements")
    void getAllAnnouncements_multipleAnnouncements_returnsAll() {
        grantSuperAdmin(superAdminId);

        announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "Announcement 1", "client-1");
        announcementManager.createJoinAnnouncement(
                superAdminId, "server-channel", "Announcement 2", "client-1");

        Collection<Announcement> all = announcementManager.getAllAnnouncements();

        assertThat(all).hasSize(2);
    }

    // ==================== clear tests ====================

    @Test
    @DisplayName("clear - removes all announcements")
    void clear_withAnnouncements_removesAll() {
        grantSuperAdmin(superAdminId);

        announcementManager.createJoinAnnouncement(
                superAdminId, "global-channel", "Announcement 1", "client-1");
        announcementManager.createJoinAnnouncement(
                superAdminId, "server-channel", "Announcement 2", "client-1");

        announcementManager.clear();

        assertThat(announcementManager.getAnnouncementCount()).isEqualTo(0);
        assertThat(announcementManager.getAllAnnouncements()).isEmpty();
    }

    // ==================== helper methods ====================

    private void grantSuperAdmin(UUID userId) {
        permissionManager.registerSuperAdmin(new SuperAdminCredentials(userId, "hash"));
        permissionManager.authenticateSuperAdmin(userId, "hash");
    }
}
