package com.nova.link.notification;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.BanInfo;
import com.nova.link.database.ChatMessageRecord;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.MuteInfo;
import com.nova.link.database.Notification;
import com.nova.link.database.PlayerState;
import com.nova.link.database.Invitation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PANEL-014 fail-safe contract: when the backing {@link DatabaseProvider}
 * does not implement the per-user notification API (throws
 * UnsupportedOperationException from the interface default methods),
 * {@link NotificationStore} must degrade to a NO-OP / zero result — it must
 * NEVER escalate a user-scoped operation into the global destructive variants
 * (clearAll / global markAllRead), which would let one user's action wipe or
 * flip every other user's notifications (privilege amplification).
 *
 * <p>The stub below inherits exactly the interface defaults, i.e. it models
 * any provider that was never upgraded with the per-user API (the position
 * RedisProvider was in before its PANEL-014 parity fix).
 */
@DisplayName("NotificationStore per-user UOE fallbacks must fail safe, not escalate to global")
class NotificationStoreFailSafeTest {

    /** Minimal provider: inherits the throwing per-user defaults, counts global calls. */
    private static class LegacyProviderStub implements DatabaseProvider {

        int globalClearNotificationsCalls;
        int globalMarkAllReadCalls;
        int globalMarkOneReadCalls;
        int globalListCalls;
        int globalCountCalls;
        int globalUnreadCountCalls;

        @Override
        public void initialize() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void savePlayerState(PlayerState state) {
        }

        @Override
        public Optional<PlayerState> loadPlayerState(UUID playerId) {
            return Optional.empty();
        }

        @Override
        public void deletePlayerState(UUID playerId) {
        }

        @Override
        public List<PlayerState> getAllPlayerStates() {
            return Collections.emptyList();
        }

        @Override
        public void saveChannel(Channel channel) {
        }

        @Override
        public Optional<Channel> loadChannel(String channelId) {
            return Optional.empty();
        }

        @Override
        public void deleteChannel(String channelId) {
        }

        @Override
        public List<Channel> getAllChannels() {
            return Collections.emptyList();
        }

        @Override
        public void saveMute(UUID playerId, MuteInfo muteInfo) {
        }

        @Override
        public List<MuteInfo> loadMutes(UUID playerId) {
            return Collections.emptyList();
        }

        @Override
        public void deleteMute(UUID playerId, String channelId) {
        }

        @Override
        public int cleanupExpiredMutes() {
            return 0;
        }

        @Override
        public Map<UUID, List<MuteInfo>> getAllActiveMutes() {
            return Collections.emptyMap();
        }

        @Override
        public void saveBan(UUID playerId, BanInfo banInfo) {
        }

        @Override
        public List<BanInfo> loadBans(UUID playerId) {
            return Collections.emptyList();
        }

        @Override
        public void deleteBan(UUID playerId, String channelId) {
        }

        @Override
        public int cleanupExpiredBans() {
            return 0;
        }

        @Override
        public Map<UUID, List<BanInfo>> getAllActiveBans() {
            return Collections.emptyMap();
        }

        @Override
        public void saveNotification(Notification notification) {
        }

        @Override
        public List<Notification> getNotifications(int offset, int limit, boolean unreadOnly) {
            globalListCalls++;
            return Collections.emptyList();
        }

        @Override
        public void markNotificationRead(long id) {
            globalMarkOneReadCalls++;
        }

        @Override
        public void markAllNotificationsRead() {
            globalMarkAllReadCalls++;
        }

        @Override
        public int clearNotifications() {
            globalClearNotificationsCalls++;
            return 0;
        }

        @Override
        public int getUnreadCount() {
            globalUnreadCountCalls++;
            return 7;
        }

        @Override
        public int countNotifications(boolean unreadOnly) {
            globalCountCalls++;
            return 7;
        }

        @Override
        public void saveAuditEvent(com.nova.link.audit.AuditEvent event) {
        }

        @Override
        public List<com.nova.link.audit.AuditEvent> getAuditEvents(int offset, int limit,
                                                                   String actor, String action) {
            return Collections.emptyList();
        }

        @Override
        public int countAuditEvents(String actor, String action) {
            return 0;
        }

        @Override
        public void saveInvitation(Invitation invitation) {
        }

        @Override
        public Optional<Invitation> loadInvitation(String code) {
            return Optional.empty();
        }

        @Override
        public boolean markInvitationUsed(String code, UUID usedBy) {
            return false;
        }

        @Override
        public int claimInvitationUse(String code, UUID playerId, long now) {
            return 0;
        }

        @Override
        public void deleteInvitation(String code) {
        }

        @Override
        public int cleanupExpiredInvitations() {
            return 0;
        }

        @Override
        public void saveMessage(ChatMessageRecord message) {
        }

        @Override
        public List<ChatMessageRecord> searchMessages(com.nova.link.database.MessageFilter filter,
                                                      int offset, int limit) {
            return Collections.emptyList();
        }

        @Override
        public int countMessages(com.nova.link.database.MessageFilter filter) {
            return 0;
        }

        @Override
        public int cleanupMessagesBefore(long cutoffTimestamp) {
            return 0;
        }

        @Override
        public void saveAnnouncement(com.nova.link.announcement.Announcement announcement) {
        }

        @Override
        public void deleteAnnouncement(String announcementId) {
        }

        @Override
        public List<com.nova.link.announcement.Announcement> getAllPersistedAnnouncements() {
            return Collections.emptyList();
        }

        @Override
        public void saveWebhook(com.nova.link.api.Webhook webhook) {
        }

        @Override
        public void deleteWebhook(String webhookId) {
        }

        @Override
        public List<com.nova.link.api.Webhook> getAllPersistedWebhooks() {
            return Collections.emptyList();
        }

        @Override
        public String getProviderType() {
            return "Legacy";
        }
    }

    private LegacyProviderStub provider;
    private NotificationStore store;

    @BeforeEach
    void setUp() {
        provider = new LegacyProviderStub();
        store = new NotificationStore(provider);
    }

    @Test
    @DisplayName("clearNotifications(userId) on a legacy provider returns 0 and never wipes globally")
    void perUserClearFailsSafeInsteadOfWipingEverything() throws Exception {
        int cleared = store.clearAll("alice");

        assertThat(cleared).as("fail-safe clear must report nothing deleted").isZero();
        assertThat(provider.globalClearNotificationsCalls)
                .as("must NOT fall back to the global destructive clear").isZero();
    }

    @Test
    @DisplayName("clearBroadcast on a legacy provider returns 0 and never deletes everything")
    void broadcastClearFailsSafeInsteadOfDeletingDirected() throws Exception {
        int cleared = store.clearBroadcast();

        assertThat(cleared).as("fail-safe broadcast clear must report nothing deleted").isZero();
        assertThat(provider.globalClearNotificationsCalls)
                .as("must NOT fall back to clearNotifications() (global)").isZero();
    }

    @Test
    @DisplayName("markAllRead(userId) on a legacy provider is a no-op, not a global flip")
    void markAllReadForUserFailsSafeInsteadOfGlobalFlip() throws Exception {
        store.markAllRead("alice");

        assertThat(provider.globalMarkAllReadCalls)
                .as("must NOT fall back to the global mark-all-read").isZero();
    }

    @Test
    @DisplayName("markRead(id, userId) on a legacy provider is a no-op, not a global flip")
    void markReadForUserFailsSafeInsteadOfGlobalFlag() throws Exception {
        store.markRead(42L, "alice");

        assertThat(provider.globalMarkOneReadCalls)
                .as("must NOT fall back to the global mark-read").isZero();
    }

    @Test
    @DisplayName("per-user listing on a legacy provider returns empty, not the global view")
    void perUserListingFailsSafeToEmptyNotGlobalView() throws Exception {
        List<Notification> result = store.getNotifications(0, 50, false, "alice");

        assertThat(result).as("fail-safe listing must be empty").isEmpty();
        assertThat(provider.globalListCalls)
                .as("must NOT fall back to the global listing (leaks other users' "
                        + "directed notifications)").isZero();
    }

    @Test
    @DisplayName("per-user count/unread on a legacy provider return 0, not the global aggregates")
    void perUserCountsFailSafeToZero() throws Exception {
        assertThat(store.count(false, "alice")).isZero();
        assertThat(store.getUnreadCount("alice")).isZero();

        assertThat(provider.globalCountCalls).isZero();
        assertThat(provider.globalUnreadCountCalls).isZero();
    }

    @Test
    @DisplayName("legacy provider keeps serving the genuinely-global API unchanged")
    void globalPathsStillWorkOnLegacyProvider() throws Exception {
        store.markAllRead();
        store.markRead(1L);
        store.clearAll();
        store.count(true);
        store.getUnreadCount();
        store.getNotifications(0, 10, false);

        assertThat(provider.globalMarkAllReadCalls).isEqualTo(1);
        assertThat(provider.globalMarkOneReadCalls).isEqualTo(1);
        assertThat(provider.globalClearNotificationsCalls).isEqualTo(1);
        assertThat(provider.globalCountCalls).isEqualTo(1);
        assertThat(provider.globalUnreadCountCalls).isEqualTo(1);
        assertThat(provider.globalListCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("null userId is a guard: per-user overloads no-op instead of delegating")
    void nullUserIdPerUserOverloadsAreNoOps() throws Exception {
        store.markAllRead(null);
        store.markRead(1L, null);

        // Null userId means "not per-user scoped": the store's null-guard
        // makes these no-ops rather than global destructive calls. Callers
        // that genuinely want the global paths use the no-userId overloads
        // (covered by globalPathsStillWorkOnLegacyProvider).
        assertThat(store.clearAll(null)).isZero();
        assertThat(store.count(true, null)).isZero();
        assertThat(store.getUnreadCount(null)).isZero();
        assertThat(store.getNotifications(0, 10, false, null)).isEmpty();

        assertThat(provider.globalMarkAllReadCalls).isZero();
        assertThat(provider.globalMarkOneReadCalls).isZero();
        assertThat(provider.globalClearNotificationsCalls).isZero();
        assertThat(provider.globalCountCalls).isZero();
        assertThat(provider.globalUnreadCountCalls).isZero();
        assertThat(provider.globalListCalls).isZero();
    }
}
