package com.nova.link.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live-Redis integration test for the {@link RedisProvider} per-user
 * notification API (PANEL-014 parity fix).
 *
 * <p>Connects to a real Redis on 127.0.0.1:6390 (throwaway local instance,
 * database 15) and exercises the full per-user surface end-to-end through
 * Jedis — no Testcontainers, no Docker. Skipped cleanly (JUnit assumption)
 * on hosts without that instance so CI boxes without Redis never fail.
 *
 * <p>Coverage: directed-notification save→list roundtrip preserving the
 * recipient, per-user read isolation, markAllRead(userId) scoping,
 * clearNotifications(userId)/clearBroadcastNotifications() isolation, and
 * read-state orphan cleanup on purge.
 */
@DisplayName("RedisProvider per-user notifications against live Redis (127.0.0.1:6390 db15)")
class RedisProviderNotificationIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6390;
    private static final int DATABASE = 15;

    private RedisProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        // Skip gracefully when the throwaway Redis instance is not running.
        assumeTrue(isTestRedisUp(),
                "No Redis on " + HOST + ":" + PORT + " — skipping live integration test");

        provider = new RedisProvider(HOST, PORT, null, DATABASE);
        provider.initialize();
        // Hermetic fixture: wipe only this test's logical database.
        provider.clearAllForTests();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            try {
                provider.clearAllForTests();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            provider.shutdown();
        }
    }

    private boolean isTestRedisUp() {
        try (redis.clients.jedis.Jedis jedis =
                     new redis.clients.jedis.Jedis(HOST, PORT)) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("directed notification survives save→list roundtrip with recipient intact")
    void directedNotificationRoundTripPreservesRecipient() throws DatabaseException {
        Notification directed = new Notification("directed", "for alice", "warning");
        directed.setRecipient("alice");
        provider.saveNotification(directed);
        assertThat(directed.getId()).as("provider stamps the id").isPositive();

        List<Notification> forAlice = provider.getNotifications(0, 10, false, "alice");
        assertThat(forAlice).hasSize(1);
        assertThat(forAlice.get(0).getRecipient())
                .as("recipient must survive the JSON roundtrip")
                .isEqualTo("alice");
        assertThat(forAlice.get(0).getTitle()).isEqualTo("directed");
        assertThat(forAlice.get(0).getId()).isEqualTo(directed.getId());

        // Other users must not see it; global listing still does.
        assertThat(provider.getNotifications(0, 10, false, "bob")).isEmpty();
        assertThat(provider.getNotifications(0, 10, false)).hasSize(1);

        // Case-insensitive visibility matches the WS normalization contract.
        assertThat(provider.getNotifications(0, 10, false, "  ALICE "))
                .as("visibility match is trim+case-insensitive").hasSize(1);
    }

    @Test
    @DisplayName("broadcast is visible to everyone; directed only to its recipient")
    void broadcastVisibilityAndDirectedScoping() throws DatabaseException {
        provider.saveNotification(new Notification("b1", "m", "info"));
        Notification d = new Notification("d1", "m", "info");
        d.setRecipient("alice");
        provider.saveNotification(d);

        assertThat(provider.getNotifications(0, 10, false, "alice")).hasSize(2);
        assertThat(provider.getNotifications(0, 10, false, "bob")).hasSize(1);

        assertThat(provider.countNotifications(false, "alice")).isEqualTo(2);
        assertThat(provider.countNotifications(false, "bob")).isEqualTo(1);
    }

    @Test
    @DisplayName("per-user read isolation: A marks read, B still unread")
    void markReadIsolationBetweenUsers() throws DatabaseException {
        Notification n = new Notification("shared", "m", "info");
        provider.saveNotification(n);

        provider.markNotificationRead(n.getId(), "alice");

        assertThat(provider.getUnreadCount("alice"))
                .as("alice marked it read").isZero();
        assertThat(provider.getUnreadCount("bob"))
                .as("bob still unread").isEqualTo(1);
        assertThat(provider.getUnreadCount())
                .as("global flag untouched by per-user markRead").isEqualTo(1);
        assertThat(provider.getNotifications(0, 10, true, "bob")).hasSize(1);
        assertThat(provider.getNotifications(0, 10, true, "alice")).isEmpty();
    }

    @Test
    @DisplayName("markAllNotificationsRead(userId) touches ONLY that user's view")
    void markAllReadScopedToUser() throws DatabaseException {
        Notification shared = new Notification("shared", "m", "info");
        provider.saveNotification(shared);
        Notification forBob = new Notification("for-bob", "m", "info");
        forBob.setRecipient("bob");
        provider.saveNotification(forBob);

        provider.markAllNotificationsRead("alice");

        assertThat(provider.getUnreadCount("alice")).isZero();
        assertThat(provider.getUnreadCount("bob"))
                .as("bob's view unaffected by alice's mark-all").isEqualTo(2);
        // Global read flag must not have been flipped.
        assertThat(provider.getNotifications(0, 10, true)).hasSize(2);
    }

    @Test
    @DisplayName("clearNotifications(userId) deletes only that user's directed rows")
    void clearUserDirectedOnlyAffectsThatUser() throws DatabaseException {
        provider.saveNotification(new Notification("b1", "m", "info"));
        Notification forAlice = new Notification("a1", "m", "info");
        forAlice.setRecipient("alice");
        provider.saveNotification(forAlice);
        Notification forBob = new Notification("b2-directed", "m", "info");
        forBob.setRecipient("bob");
        provider.saveNotification(forBob);

        // Alice read her directed one before archiving it.
        provider.markNotificationRead(forAlice.getId(), "alice");

        int cleared = provider.clearNotifications("alice");
        assertThat(cleared)
                .as("only alice's DIRECTED notification is purged; the broadcast "
                        + "she can see is never touched by a per-user clear")
                .isEqualTo(1);

        // Bob's directed row and both users' broadcasts survive.
        List<Notification> bobView = provider.getNotifications(0, 10, false, "bob");
        assertThat(bobView).as("bob keeps broadcast + own directed").hasSize(2);
        List<Notification> aliceView = provider.getNotifications(0, 10, false, "alice");
        assertThat(aliceView).as("alice keeps only the broadcast").hasSize(1);

        // No orphaned read state: the re-seeded directed notification (fresh
        // id from the same INCR sequence) must be unread for alice. The
        // broadcast is unread for alice too (she never read it per-user), so
        // both show up in her unread view.
        Notification reseeded = new Notification("a1-again", "m", "info");
        reseeded.setRecipient("alice");
        provider.saveNotification(reseeded);
        assertThat(provider.getUnreadCount("alice"))
                .as("re-seeded directed notification is unread after the purge; "
                        + "no stale read-state may resurrect as already-read")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("clearBroadcastNotifications deletes broadcasts only, preserves directed")
    void clearBroadcastPreservesDirected() throws DatabaseException {
        provider.saveNotification(new Notification("b1", "m", "info"));
        provider.saveNotification(new Notification("b2", "m", "info"));
        Notification forAlice = new Notification("a1", "m", "info");
        forAlice.setRecipient("alice");
        provider.saveNotification(forAlice);

        // Someone read a broadcast globally.
        Notification first = provider.getNotifications(0, 10, false).get(0);
        provider.markNotificationRead(first.getId());
        // And someone read one per-user.
        provider.markNotificationRead(forAlice.getId(), "alice");

        int cleared = provider.clearBroadcastNotifications();
        assertThat(cleared).isEqualTo(2);

        assertThat(provider.countNotifications(false)).isEqualTo(1);
        assertThat(provider.getNotifications(0, 10, false, "alice"))
                .as("directed notification survives the broadcast purge")
                .hasSize(1);
        assertThat(provider.getUnreadCount("alice"))
                .as("only the directed remains; its read-mark was purged along "
                        + "with the broadcasts' — no orphans")
                .isZero();
    }

    @Test
    @DisplayName("global clearNotifications removes everything including read state")
    void globalClearWipesEverythingWithoutOrphans() throws DatabaseException {
        provider.saveNotification(new Notification("b1", "m", "info"));
        Notification forAlice = new Notification("a1", "m", "info");
        forAlice.setRecipient("alice");
        provider.saveNotification(forAlice);
        provider.markNotificationRead(forAlice.getId(), "alice");

        int cleared = provider.clearNotifications();
        assertThat(cleared).isEqualTo(2);

        assertThat(provider.countNotifications(false)).isZero();
        assertThat(provider.getUnreadCount("alice")).isZero();

        // A fresh notification reusing a later id must be unread for everyone.
        Notification fresh = new Notification("fresh", "m", "info");
        provider.saveNotification(fresh);
        assertThat(provider.getUnreadCount("alice")).isEqualTo(1);
    }
}
