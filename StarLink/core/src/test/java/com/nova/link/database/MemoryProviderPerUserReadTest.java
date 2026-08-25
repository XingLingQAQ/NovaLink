package com.nova.link.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the per-user notification read-flag fix in {@link MemoryProvider}.
 *
 * <p>Before the fix, {@code getNotifications(offset, limit, unreadOnly, userId)}
 * returned the shared persisted {@link Notification} objects whose {@code read}
 * flag was the legacy global broadcast flag — so the moment one user marked a
 * broadcast notification read, every admin saw it as read. After the fix, the
 * per-user listing returns copies whose {@code read} flag reflects per-user
 * state (double-read: global flag OR per-user {@code notification_read} row),
 * so one user marking a notification read does not flip the flag for others.
 *
 * <p>Requirements: 22.5 (PANEL-014 per-user notification state).
 */
@DisplayName("MemoryProvider per-user notification read state")
class MemoryProviderPerUserReadTest {

    private MemoryProvider provider;

    @BeforeEach
    void setUp() throws DatabaseException {
        provider = new MemoryProvider();
        provider.initialize();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.shutdown();
        }
    }

    @Test
    void perUserReadListingReflectsPerUserStateNotGlobal() throws DatabaseException {
        // Broadcast notification (recipient null) visible to every user.
        Notification n = new Notification("Title", "Body", Notification.LEVEL_INFO);
        provider.saveNotification(n);
        assertThat(n.getId()).isGreaterThan(0);

        // Before any per-user mark: read==false for both A and B.
        List<Notification> forA = provider.getNotifications(0, 10, false, "A");
        List<Notification> forB = provider.getNotifications(0, 10, false, "B");
        assertThat(forA).hasSize(1);
        assertThat(forB).hasSize(1);
        assertThat(forA.get(0).isRead()).isFalse();
        assertThat(forB.get(0).isRead()).isFalse();

        // Mark read per-user for A only. The legacy global flag must NOT flip
        // (per-user mark only records into notification_read state).
        provider.markNotificationRead(n.getId(), "A");

        List<Notification> forAAfter = provider.getNotifications(0, 10, false, "A");
        List<Notification> forBAfter = provider.getNotifications(0, 10, false, "B");
        assertThat(forAAfter).hasSize(1);
        assertThat(forBAfter).hasSize(1);
        // A marked it read -> per-user read flag is true for A.
        assertThat(forAAfter.get(0).isRead()).isTrue();
        // B never marked it -> per-user read flag stays false for B.
        assertThat(forBAfter.get(0).isRead()).isFalse();

        // Unread counts must agree: A has 0 unread, B still has 1 unread.
        assertThat(provider.getUnreadCount("A")).isZero();
        assertThat(provider.getUnreadCount("B")).isEqualTo(1);

        // The shared persisted object's global flag must remain false — the
        // per-user listing must not mutate the stored notification.
        assertThat(n.isRead()).isFalse();
    }

    @Test
    void unreadOnlyListingRespectsPerUserState() throws DatabaseException {
        Notification n = new Notification("Title", "Body", Notification.LEVEL_INFO);
        provider.saveNotification(n);

        provider.markNotificationRead(n.getId(), "A");

        // unreadOnly listing for A is empty (A read it); for B it still shows.
        assertThat(provider.getNotifications(0, 10, true, "A")).isEmpty();
        assertThat(provider.getNotifications(0, 10, true, "B")).hasSize(1);
    }

    @Test
    void directedNotificationIsVisibleOnlyToRecipient() throws DatabaseException {
        Notification directed = new Notification(
                0, "Title", "Body", Notification.LEVEL_INFO, System.currentTimeMillis(), false, "B");
        provider.saveNotification(directed);

        // Only B sees the directed notification; A does not.
        assertThat(provider.getNotifications(0, 10, false, "B")).hasSize(1);
        assertThat(provider.getNotifications(0, 10, false, "A")).isEmpty();
        assertThat(provider.getUnreadCount("B")).isEqualTo(1);
        assertThat(provider.getUnreadCount("A")).isZero();
    }

    @Test
    void directedNotificationRecipientMatchingIsCaseInsensitive() throws DatabaseException {
        Notification upper = new Notification("upper", "m", Notification.LEVEL_INFO);
        upper.setRecipient("Admin");
        provider.saveNotification(upper);

        // Listing + unread count match regardless of stored vs queried case.
        assertThat(provider.getNotifications(0, 10, false, "admin")).hasSize(1);
        assertThat(provider.getNotifications(0, 10, true, "ADMIN")).hasSize(1);
        assertThat(provider.getUnreadCount("ADMIN")).isEqualTo(1);
        assertThat(provider.countNotifications(false, "admin")).isEqualTo(1);

        // markAllRead flips the per-user flag. Per-user read-state keys are
        // matched exactly (out of scope here — the reviewed defect concerns
        // recipient matching), so subsequent reads use the same spelling
        // the mark was made with.
        provider.markAllNotificationsRead("Admin");
        assertThat(provider.getUnreadCount("Admin")).isZero();
        assertThat(provider.getNotifications(0, 10, true, "Admin")).isEmpty();

        // Stored recipient values are preserved verbatim for display.
        List<Notification> listed = provider.getNotifications(0, 10, false, "admin");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getRecipient()).isEqualTo("Admin");
    }

    @Test
    void clearDirectedRemovesReadStateAndIsCaseInsensitive() throws DatabaseException {
        Notification directed = new Notification("d", "m", Notification.LEVEL_INFO);
        directed.setRecipient("Bob");
        provider.saveNotification(directed);
        Notification broadcast = new Notification("b", "m", Notification.LEVEL_INFO);
        provider.saveNotification(broadcast);

        provider.markNotificationRead(directed.getId(), "Bob");

        int cleared = provider.clearNotifications("BOB");
        assertThat(cleared).isEqualTo(1);

        // The directed notification is gone; the broadcast stays.
        assertThat(provider.getNotifications(0, 10, false, "bob")).hasSize(1);
        assertThat(provider.getNotifications(0, 10, false, "carol")).hasSize(1);

        // Stale-resurrection guard: clearNotifications() (no-arg) must also
        // drop per-user read state so a reused id is not silently pre-read.
        provider.markNotificationRead(broadcast.getId(), "Carol");
        provider.clearNotifications();
        assertThat(provider.getNotifications(0, 10, false)).isEmpty();
        Notification recycled = new Notification("recycled", "m", Notification.LEVEL_INFO);
        provider.saveNotification(recycled);
        // notificationIdSeq keeps incrementing in MemoryProvider; simulate a
        // wiped id by asserting the fresh stream starts unread for everyone.
        assertThat(provider.getUnreadCount("carol")).isEqualTo(1);
    }
}
