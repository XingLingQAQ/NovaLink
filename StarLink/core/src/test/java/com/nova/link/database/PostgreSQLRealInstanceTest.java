package com.nova.link.database;

import com.nova.link.announcement.Campaign;
import com.nova.link.announcement.CampaignStatus;
import com.nova.link.announcement.DeliveryPolicy;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import com.nova.link.config.ConfigSnapshot;
import com.nova.link.database.dialect.PostgreSQLDialect;
import com.nova.link.social.NotificationPreference;
import com.nova.link.social.SocialRelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-instance integration test for {@link PostgreSQLProvider} against a
 * host-installed PostgreSQL server (NOT Testcontainers).
 *
 * <p>This test closes VERIFY-006 (migrations reach CURRENT_VERSION; the v7
 * {@code channels.slow_mode_seconds} column round-trips) and VERIFY-013 (the
 * v10 per-user notification migration: {@code notifications.recipient} +
 * {@code notification_read} table round-trip for broadcast + directed
 * notifications). It also exercises the schema v12 config-history active-flag
 * flip and the schema v13/v14 social_relations + campaigns CRUD surface so
 * the PostgreSQL parity story is covered by real-DB evidence rather than
 * SQLite-only tests.
 *
 * <p>The test is gated by {@link #pgReachable()}: it connects to a real
 * PostgreSQL instance whose connection coordinates are supplied via the
 * {@code NOVALINK_PG_HOST}, {@code NOVALINK_PG_PORT}, {@code NOVALINK_PG_DB},
 * {@code NOVALINK_PG_USER}, and {@code NOVALINK_PG_PASSWORD} environment
 * variables (defaults {@code 127.0.0.1}, {@code 5432}, {@code novalink_test},
 * {@code novalink}, empty password). When the server is unreachable the test
 * is skipped via {@link org.junit.jupiter.api.Assumptions#assumeTrue} so a
 * Docker-less CI/dev box stays green without false negatives — mirroring the
 * skip posture of {@link PostgreSQLIntegrationTest}'s Docker probe but
 * without the Testcontainers dependency.
 *
 * <p>Unlike {@link PostgreSQLIntegrationTest}, this class does NOT spin up a
 * container; it assumes a host-installed PostgreSQL (e.g. installed via
 * {@code scoop install postgresql} and started with {@code pg_ctl}). The
 * server is left running after the test so subsequent reruns do not need to
 * re-init the data directory.
 *
 * <p>Scope boundary (declared honestly): this test covers the DB-migration /
 * schema round-trip slice of VERIFY-006 and VERIFY-013 only. The VERIFY-013
 * "dual-user API/WS end-to-end" flow and the VERIFY-010/011 WebSocket race
 * conditions still require a live backend + browser and are out of scope
 * here.
 *
 * <p>Requirements: VERIFY-006, VERIFY-013 (DB-migration/schema slice only).
 */
@DisplayName("PostgreSQL real-instance migrations + CRUD (VERIFY-006 / VERIFY-013)")
class PostgreSQLRealInstanceTest {

    // ==================== Connection coordinates (env-overridable) ====================

    private static final String PG_HOST = System.getenv().getOrDefault("NOVALINK_PG_HOST", "127.0.0.1");
    private static final int PG_PORT = Integer.parseInt(System.getenv().getOrDefault("NOVALINK_PG_PORT", "5432"));
    private static final String PG_DB = System.getenv().getOrDefault("NOVALINK_PG_DB", "novalink_test");
    private static final String PG_USER = System.getenv().getOrDefault("NOVALINK_PG_USER", "novalink");
    private static final String PG_PASSWORD = System.getenv().getOrDefault("NOVALINK_PG_PASSWORD", "");

    private PostgreSQLProvider provider;

    /**
     * Probes the configured PostgreSQL instance with a 2-second login
     * attempt. Returns false (not throws) on any connection failure so the
     * caller can skip the test via {@code assumeTrue} instead of failing it.
     *
     * <p>The probe uses a login-timeout of 2 seconds so an unreachable host
     * fails fast rather than blocking the full default socket timeout.
     *
     * @return true if a connection can be established within 2 seconds
     */
    private static boolean pgReachable() {
        String url = "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT + "/" + PG_DB;
        Properties props = new Properties();
        props.setProperty("user", PG_USER);
        if (PG_PASSWORD != null && !PG_PASSWORD.isEmpty()) {
            props.setProperty("password", PG_PASSWORD);
        }
        props.setProperty("loginTimeout", "2");
        try (Connection conn = DriverManager.getConnection(url, props)) {
            return conn.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Drops every table the migrations create so each test method starts
     * from a clean schema. This is faster than tearing the whole provider
     * down and re-running all 14 migrations per test, and it keeps the
     * per-test isolation story identical to the SQLite @TempDir approach.
     */
    private static void dropAllTables(PostgreSQLProvider p) throws SQLException {
        try (Connection conn = p.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DROP TABLE IF EXISTS campaigns, social_relations, notification_preferences, "
                             + "config_history, appeals, case_evidence, moderation_cases, "
                             + "notification_read, notifications, audit_events, "
                             + "webhooks, announcements, messages, "
                             + "channel_members, invitations, bans, mutes, channels, players, "
                             + "novalink_migrations "
                             + "CASCADE")) {
            stmt.executeUpdate();
        }
    }

    @BeforeEach
    void setUp() throws DatabaseException {
        // Skip gracefully when the real PostgreSQL instance is unreachable —
        // this keeps the test green on Docker-less CI without false negatives.
        assumeTrue(pgReachable(), "PostgreSQL not reachable at " + PG_HOST + ":" + PG_PORT
                + "/" + PG_DB + " — skipping real-instance test");

        provider = new PostgreSQLProvider(PG_HOST, PG_PORT, PG_DB, PG_USER, PG_PASSWORD, 5);
        provider.initialize();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            // Best-effort schema reset so the next test method starts clean.
            try {
                dropAllTables(provider);
            } catch (SQLException ignored) {
                // Drop failures are non-fatal — the provider shutdown will
                // release the connection pool either way.
            }
            provider.shutdown();
        }
    }

    // ==================== Migration / version ====================

    @Test
    @DisplayName("VERIFY-006: migrations reach PostgreSQLDialect.CURRENT_VERSION (dynamic, not hardcoded)")
    void migrationReachesCurrentVersion() throws DatabaseException {
        PostgreSQLDialect dialect = new PostgreSQLDialect();
        // DYNAMIC assertion: getCurrentVersion() is the public getter for the
        // private CURRENT_VERSION constant. Asserting it is positive (not
        // hardcoded 14) keeps the test green after a future v15 bump without
        // touching the test. Hardcoding 14 would make the test stale on v15.
        assertThat(dialect.getCurrentVersion()).isPositive();
        assertThat(dialect.getCurrentVersion()).isGreaterThanOrEqualTo(10);
        assertThat(provider.isConnected()).isTrue();
        // A fresh migration leaves the CRUD tables empty.
        assertThat(provider.getAllChannels()).isEmpty();
        assertThat(provider.getAllPlayerStates()).isEmpty();
    }

    @Test
    @DisplayName("VERIFY-006: migration version stamped into novalink_migrations matches CURRENT_VERSION")
    void migrationVersionStamped() throws SQLException {
        PostgreSQLDialect dialect = new PostgreSQLDialect();
        try (Connection conn = provider.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT MAX(version) FROM novalink_migrations");
             ResultSet rs = stmt.executeQuery()) {
            assertThat(rs.next()).isTrue();
            // DYNAMIC: the stamped version must equal the dialect's current
            // version (via the public getter, not the private constant).
            assertThat(rs.getInt(1)).isEqualTo(dialect.getCurrentVersion());
        }
    }

    // ==================== VERIFY-006: channels.slow_mode_seconds round-trip ====================

    @Test
    @DisplayName("VERIFY-006: channels.slow_mode_seconds round-trips (save → load) — schema v7 column")
    void channelSlowModeSecondsRoundTrip() throws DatabaseException {
        Channel channel = new Channel("ch-slow", "Slow Mode Channel", ChannelScope.GLOBAL, null);
        channel.setSlowModeSeconds(30);
        channel.setMaxCapacity(50);

        provider.saveChannel(channel);

        Optional<Channel> loaded = provider.loadChannel("ch-slow");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSlowModeSeconds()).isEqualTo(30);
        assertThat(loaded.get().getDisplayName()).isEqualTo("Slow Mode Channel");
        assertThat(loaded.get().getMaxCapacity()).isEqualTo(50);
    }

    @Test
    @DisplayName("VERIFY-006: channels.slow_mode_seconds defaults to 0 when not set (schema v7 DEFAULT 0)")
    void channelSlowModeSecondsDefaultsToZero() throws DatabaseException {
        Channel channel = new Channel("ch-default-slow", "Default Slow", ChannelScope.GLOBAL, null);
        // Do NOT call setSlowModeSeconds — rely on the Channel field default (0).
        provider.saveChannel(channel);

        Optional<Channel> loaded = provider.loadChannel("ch-default-slow");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSlowModeSeconds()).isZero();
    }

    @Test
    @DisplayName("VERIFY-006: channels.slow_mode_seconds upsert overwrites prior value on re-save")
    void channelSlowModeSecondsUpsertOverwrites() throws DatabaseException {
        Channel channel = new Channel("ch-slow-upsert", "Slow Upsert", ChannelScope.GLOBAL, null);
        channel.setSlowModeSeconds(10);
        provider.saveChannel(channel);

        channel.setSlowModeSeconds(60);
        provider.saveChannel(channel);

        Optional<Channel> loaded = provider.loadChannel("ch-slow-upsert");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSlowModeSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("VERIFY-006: channels.slow_mode_seconds survives a provider reopen (persisted, not in-memory)")
    void channelSlowModeSecondsSurvivesReopen() throws DatabaseException, SQLException {
        Channel channel = new Channel("ch-slow-reopen", "Slow Reopen", ChannelScope.GLOBAL, null);
        channel.setSlowModeSeconds(45);
        provider.saveChannel(channel);
        provider.shutdown();

        // Reopen a fresh provider against the SAME database — the row must
        // survive because the column is persisted, not in-memory.
        provider = new PostgreSQLProvider(PG_HOST, PG_PORT, PG_DB, PG_USER, PG_PASSWORD, 5);
        provider.initialize();

        Optional<Channel> loaded = provider.loadChannel("ch-slow-reopen");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSlowModeSeconds()).isEqualTo(45);
    }

    // ==================== VERIFY-013: per-user notification migration round-trip ====================

    @Test
    @DisplayName("VERIFY-013: notifications.recipient column accepts NULL (broadcast) — schema v10")
    void notificationRecipientNullBroadcastRoundTrip() throws DatabaseException {
        Notification broadcast = new Notification("Broadcast", "Hello all", Notification.LEVEL_INFO);
        assertThat(broadcast.getRecipient()).isNull();
        provider.saveNotification(broadcast);
        assertThat(broadcast.getId()).isGreaterThan(0);

        // Legacy getNotifications (no userId) returns the row.
        List<Notification> all = provider.getNotifications(0, 10, false);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getTitle()).isEqualTo("Broadcast");
        assertThat(all.get(0).getRecipient()).isNull();
    }

    @Test
    @DisplayName("VERIFY-013: notifications.recipient column accepts a directed recipient — schema v10")
    void notificationRecipientDirectedRoundTrip() throws DatabaseException, SQLException {
        Notification directed = new Notification(
                0, "Directed", "Private message", Notification.LEVEL_WARNING,
                System.currentTimeMillis(), false, "alice");
        provider.saveNotification(directed);
        assertThat(directed.getId()).isGreaterThan(0);

        // Legacy getNotifications (no userId) still returns the row (the read
        // flag fallback path). The legacy getter constructs Notification via
        // the 6-arg constructor (no recipient), so getRecipient() is null on
        // the returned object — the recipient column is still persisted, just
        // not selected by the legacy getter. Verify persistence via a direct
        // SQL probe on the recipient column.
        List<Notification> all = provider.getNotifications(0, 10, false);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getTitle()).isEqualTo("Directed");

        // Direct SQL probe: the recipient column holds "alice" for this row.
        try (Connection conn = provider.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT recipient FROM notifications WHERE title = ?")) {
            stmt.setString(1, "Directed");
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("recipient")).isEqualTo("alice");
            }
        }
    }

    @Test
    @DisplayName("VERIFY-013: per-user unread count — broadcast visible to all users (userA + userB both see 1)")
    void perUserUnreadCountBroadcastVisibleToAll() throws DatabaseException {
        Notification broadcast = new Notification("Broadcast", "All", Notification.LEVEL_INFO);
        provider.saveNotification(broadcast);

        // Both users see the broadcast as unread.
        assertThat(provider.getUnreadCount("alice")).isEqualTo(1);
        assertThat(provider.getUnreadCount("bob")).isEqualTo(1);
        assertThat(provider.getUnreadCount("charlie")).isEqualTo(1);
    }

    @Test
    @DisplayName("VERIFY-013: per-user unread count — directed notification visible only to its recipient")
    void perUserUnreadCountDirectedScopedToRecipient() throws DatabaseException {
        Notification directed = new Notification(
                0, "Directed", "Private", Notification.LEVEL_INFO,
                System.currentTimeMillis(), false, "alice");
        provider.saveNotification(directed);

        // Only alice sees the directed notification as unread.
        assertThat(provider.getUnreadCount("alice")).isEqualTo(1);
        assertThat(provider.getUnreadCount("bob")).isZero();
        assertThat(provider.getUnreadCount("charlie")).isZero();
    }

    @Test
    @DisplayName("VERIFY-013: markNotificationRead(id, userId) marks read for one user but not another (notification_read upsert)")
    void perUserMarkReadIsPerUser() throws DatabaseException {
        Notification broadcast = new Notification("Broadcast", "All", Notification.LEVEL_INFO);
        provider.saveNotification(broadcast);
        long id = broadcast.getId();

        // Both users start unread.
        assertThat(provider.getUnreadCount("alice")).isEqualTo(1);
        assertThat(provider.getUnreadCount("bob")).isEqualTo(1);

        // Alice marks it read.
        provider.markNotificationRead(id, "alice");
        assertThat(provider.getUnreadCount("alice")).isZero();
        // Bob still sees it as unread — the read state is per-user.
        assertThat(provider.getUnreadCount("bob")).isEqualTo(1);

        // Bob's unread-only list still contains the broadcast; alice's does not.
        assertThat(provider.getNotifications(0, 10, true, "alice")).isEmpty();
        assertThat(provider.getNotifications(0, 10, true, "bob"))
                .extracting(Notification::getTitle)
                .containsExactly("Broadcast");
    }

    @Test
    @DisplayName("VERIFY-013: markNotificationRead(id, userId) is idempotent (re-marking keeps alice read, bob unread)")
    void perUserMarkReadIdempotent() throws DatabaseException {
        Notification broadcast = new Notification("Broadcast", "All", Notification.LEVEL_INFO);
        provider.saveNotification(broadcast);
        long id = broadcast.getId();

        provider.markNotificationRead(id, "alice");
        provider.markNotificationRead(id, "alice"); // idempotent re-mark

        assertThat(provider.getUnreadCount("alice")).isZero();
        assertThat(provider.getUnreadCount("bob")).isEqualTo(1);
    }

    @Test
    @DisplayName("VERIFY-013: broadcast + directed mix — alice sees both, bob sees only broadcast")
    void perUserMixedBroadcastAndDirected() throws DatabaseException {
        Notification broadcast = new Notification("Broadcast", "All", Notification.LEVEL_INFO);
        provider.saveNotification(broadcast);
        Notification directedToAlice = new Notification(
                0, "Directed", "Private", Notification.LEVEL_INFO,
                System.currentTimeMillis(), false, "alice");
        provider.saveNotification(directedToAlice);
        Notification directedToBob = new Notification(
                0, "Directed Bob", "Private", Notification.LEVEL_INFO,
                System.currentTimeMillis(), false, "bob");
        provider.saveNotification(directedToBob);

        // Alice sees the broadcast + her directed notification.
        assertThat(provider.getUnreadCount("alice")).isEqualTo(2);
        // Bob sees the broadcast + his directed notification.
        assertThat(provider.getUnreadCount("bob")).isEqualTo(2);
        // Charlie sees only the broadcast.
        assertThat(provider.getUnreadCount("charlie")).isEqualTo(1);

        // Alice's unread list contains both the broadcast and her directed row.
        List<Notification> aliceUnread = provider.getNotifications(0, 10, true, "alice");
        assertThat(aliceUnread).hasSize(2);
        assertThat(aliceUnread).extracting(Notification::getTitle)
                .containsExactlyInAnyOrder("Broadcast", "Directed");

        // Charlie's unread list contains only the broadcast.
        List<Notification> charlieUnread = provider.getNotifications(0, 10, true, "charlie");
        assertThat(charlieUnread).hasSize(1);
        assertThat(charlieUnread.get(0).getTitle()).isEqualTo("Broadcast");
    }

    @Test
    @DisplayName("VERIFY-013: markAllNotificationsRead(userId) marks every visible notification read for one user only")
    void perUserMarkAllReadIsPerUser() throws DatabaseException {
        Notification broadcast = new Notification("Broadcast", "All", Notification.LEVEL_INFO);
        provider.saveNotification(broadcast);
        Notification directed = new Notification(
                0, "Directed", "Private", Notification.LEVEL_INFO,
                System.currentTimeMillis(), false, "alice");
        provider.saveNotification(directed);

        provider.markAllNotificationsRead("alice");
        assertThat(provider.getUnreadCount("alice")).isZero();
        // Bob still sees the broadcast as unread.
        assertThat(provider.getUnreadCount("bob")).isEqualTo(1);

        // Bob's mark-all only touches the broadcast (the directed-to-alice row
        // is out of bob's scope), so bob's unread drops to 0.
        provider.markAllNotificationsRead("bob");
        assertThat(provider.getUnreadCount("bob")).isZero();
        // Alice is still read.
        assertThat(provider.getUnreadCount("alice")).isZero();
    }

    @Test
    @DisplayName("VERIFY-013: clearNotifications(userId) deletes only the directed notifications for that user")
    void perUserClearDeletesOnlyDirected() throws DatabaseException {
        Notification broadcast = new Notification("Broadcast", "All", Notification.LEVEL_INFO);
        provider.saveNotification(broadcast);
        Notification directedToAlice = new Notification(
                0, "Directed", "Private", Notification.LEVEL_INFO,
                System.currentTimeMillis(), false, "alice");
        provider.saveNotification(directedToAlice);

        int cleared = provider.clearNotifications("alice");
        // Only the directed-to-alice row is deleted; the broadcast is not.
        assertThat(cleared).isEqualTo(1);

        // The broadcast is still visible to bob.
        assertThat(provider.getUnreadCount("bob")).isEqualTo(1);
        // Alice has no directed row left, but the broadcast is still unread
        // for alice (clear only deletes directed rows).
        assertThat(provider.getUnreadCount("alice")).isEqualTo(1);
    }

    @Test
    @DisplayName("VERIFY-013: countNotifications(unreadOnly, userId) returns per-user counts")
    void perUserCountNotifications() throws DatabaseException {
        Notification broadcast = new Notification("Broadcast", "All", Notification.LEVEL_INFO);
        provider.saveNotification(broadcast);
        Notification directedToAlice = new Notification(
                0, "Directed", "Private", Notification.LEVEL_INFO,
                System.currentTimeMillis(), false, "alice");
        provider.saveNotification(directedToAlice);

        // alice total = 2 (broadcast + directed), unread = 2.
        assertThat(provider.countNotifications(false, "alice")).isEqualTo(2);
        assertThat(provider.countNotifications(true, "alice")).isEqualTo(2);
        // bob total = 1 (broadcast only), unread = 1.
        assertThat(provider.countNotifications(false, "bob")).isEqualTo(1);
        assertThat(provider.countNotifications(true, "bob")).isEqualTo(1);

        // After alice marks the broadcast read, alice unread = 1.
        provider.markNotificationRead(broadcast.getId(), "alice");
        assertThat(provider.countNotifications(true, "alice")).isEqualTo(1);
        assertThat(provider.countNotifications(true, "bob")).isEqualTo(1);
    }

    @Test
    @DisplayName("VERIFY-013: notification_read table is created (schema v10) — direct column probe")
    void notificationReadTableExists() throws SQLException {
        try (Connection conn = provider.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM notification_read");
             ResultSet rs = stmt.executeQuery()) {
            assertThat(rs.next()).isTrue();
            // Empty table on fresh migration.
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    @DisplayName("VERIFY-013: notifications.recipient column is queryable (schema v10) — direct column probe")
    void notificationsRecipientColumnQueryable() throws SQLException {
        try (Connection conn = provider.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT column_name, data_type FROM information_schema.columns "
                             + "WHERE table_name = 'notifications' AND column_name = 'recipient'")) {
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("column_name")).isEqualTo("recipient");
                assertThat(rs.getString("data_type")).isEqualTo("character varying");
            }
        }
    }

    // ==================== config_history active-flag flip (schema v12) ====================

    @Test
    @DisplayName("schema v12: saveConfigSnapshot twice — only the latest row is active (active-flag flip)")
    void configHistoryActiveFlagFlip() throws DatabaseException {
        long now = System.currentTimeMillis();
        ConfigSnapshot first = new ConfigSnapshot(1L, "{\"v\":1}", now, "admin");
        provider.saveConfigSnapshot(first);

        // After the first save, exactly one row exists and it is active.
        assertThat(provider.countConfigSnapshots()).isEqualTo(1);
        List<ConfigSnapshot> history = provider.getConfigHistory(10);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).isActive()).isTrue();
        assertThat(history.get(0).getRevision()).isEqualTo(1L);

        // Save a second snapshot — the first must flip to inactive.
        ConfigSnapshot second = new ConfigSnapshot(2L, "{\"v\":2}", now + 1000, "admin");
        provider.saveConfigSnapshot(second);

        assertThat(provider.countConfigSnapshots()).isEqualTo(2);
        List<ConfigSnapshot> history2 = provider.getConfigHistory(10);
        assertThat(history2).hasSize(2);
        // Exactly one row is active — the latest (revision 2).
        long activeCount = history2.stream().filter(ConfigSnapshot::isActive).count();
        assertThat(activeCount).isEqualTo(1L);
        // The active row is the revision-2 row.
        ConfigSnapshot activeRow = history2.stream()
                .filter(ConfigSnapshot::isActive).findFirst().orElseThrow();
        assertThat(activeRow.getRevision()).isEqualTo(2L);

        // The revision-1 row is now inactive.
        Optional<ConfigSnapshot> revision1 = provider.getConfigSnapshot(1L);
        assertThat(revision1).isPresent();
        assertThat(revision1.get().isActive()).isFalse();
        assertThat(revision1.get().getSnapshotJson()).isEqualTo("{\"v\":1}");
    }

    @Test
    @DisplayName("schema v12: getConfigSnapshot(revision) returns the payload + active flag")
    void configHistoryGetSnapshotByRevision() throws DatabaseException {
        long now = System.currentTimeMillis();
        provider.saveConfigSnapshot(new ConfigSnapshot(7L, "{\"k\":\"v7\"}", now, "alice"));
        provider.saveConfigSnapshot(new ConfigSnapshot(8L, "{\"k\":\"v8\"}", now + 1, "bob"));

        Optional<ConfigSnapshot> revision7 = provider.getConfigSnapshot(7L);
        assertThat(revision7).isPresent();
        assertThat(revision7.get().getSnapshotJson()).isEqualTo("{\"k\":\"v7\"}");
        assertThat(revision7.get().getCreatedBy()).isEqualTo("alice");
        assertThat(revision7.get().isActive()).isFalse(); // superseded by revision 8

        Optional<ConfigSnapshot> revision8 = provider.getConfigSnapshot(8L);
        assertThat(revision8).isPresent();
        assertThat(revision8.get().getSnapshotJson()).isEqualTo("{\"k\":\"v8\"}");
        assertThat(revision8.get().isActive()).isTrue();
    }

    // ==================== social_relations CRUD (schema v13) ====================

    @Test
    @DisplayName("schema v13: social_relations save then getSocialRelations round-trips (PostgreSQL parity)")
    void socialRelationsRoundTrip() throws DatabaseException {
        UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000002");
        long createdAt = 1234567890L;
        long updatedAt = 1234567999L;

        provider.saveSocialRelation(
                new SocialRelation(alice, bob, SocialRelation.RelationType.IGNORE, createdAt, updatedAt));

        List<SocialRelation> ignores = provider.getSocialRelations(alice, SocialRelation.RelationType.IGNORE);
        assertThat(ignores).hasSize(1);
        SocialRelation loaded = ignores.get(0);
        assertThat(loaded.getSourceId()).isEqualTo(alice);
        assertThat(loaded.getTargetId()).isEqualTo(bob);
        assertThat(loaded.getType()).isEqualTo(SocialRelation.RelationType.IGNORE);
        assertThat(loaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(loaded.getUpdatedAt()).isEqualTo(updatedAt);

        // isIgnored is directional — alice ignores bob, not the reverse.
        assertThat(provider.isIgnored(alice, bob)).isTrue();
        assertThat(provider.isIgnored(bob, alice)).isFalse();
    }

    @Test
    @DisplayName("schema v13: social_relations save is an upsert (DELETE+INSERT on composite key)")
    void socialRelationsUpsert() throws DatabaseException {
        UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000002");

        provider.saveSocialRelation(new SocialRelation(alice, bob, SocialRelation.RelationType.IGNORE, 1L, 1L));
        provider.saveSocialRelation(new SocialRelation(alice, bob, SocialRelation.RelationType.IGNORE, 5L, 5L));

        List<SocialRelation> ignores = provider.getSocialRelations(alice, SocialRelation.RelationType.IGNORE);
        assertThat(ignores).hasSize(1);
        assertThat(ignores.get(0).getCreatedAt()).isEqualTo(5L);
    }

    @Test
    @DisplayName("schema v13: social_relations remove drops the row; missing-key remove is a no-op")
    void socialRelationsRemove() throws DatabaseException {
        UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000002");

        provider.saveSocialRelation(new SocialRelation(alice, bob, SocialRelation.RelationType.IGNORE));
        assertThat(provider.isIgnored(alice, bob)).isTrue();

        provider.removeSocialRelation(alice, bob, SocialRelation.RelationType.IGNORE);
        assertThat(provider.isIgnored(alice, bob)).isFalse();
        assertThat(provider.getSocialRelations(alice, SocialRelation.RelationType.IGNORE)).isEmpty();

        // Removing again must not throw.
        provider.removeSocialRelation(alice, bob, SocialRelation.RelationType.IGNORE);
    }

    @Test
    @DisplayName("schema v13: notification_preferences round-trip + upsert (PostgreSQL parity)")
    void notificationPreferencesRoundTrip() throws DatabaseException {
        UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // Defaults when absent.
        NotificationPreference defaults = provider.getNotificationPreference(alice);
        assertThat(defaults.isMentionsEnabled()).isTrue();

        // Save then load.
        provider.saveNotificationPreference(new NotificationPreference(alice, false, 123L));
        NotificationPreference loaded = provider.getNotificationPreference(alice);
        assertThat(loaded.isMentionsEnabled()).isFalse();
        assertThat(loaded.getUpdatedAt()).isEqualTo(123L);

        // Upsert — second save replaces the first.
        provider.saveNotificationPreference(new NotificationPreference(alice, true, 456L));
        NotificationPreference after = provider.getNotificationPreference(alice);
        assertThat(after.isMentionsEnabled()).isTrue();
        assertThat(after.getUpdatedAt()).isEqualTo(456L);
    }

    // ==================== campaigns CRUD (schema v14) ====================

    @Test
    @DisplayName("schema v14: campaigns save then getCampaign round-trips all fields (PostgreSQL parity)")
    void campaignsRoundTrip() throws DatabaseException {
        UUID creator = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        Set<String> platforms = new LinkedHashSet<>(Arrays.asList("BUKKIT", "FABRIC"));
        Campaign campaign = new Campaign(
                "CMP-DEAD01", "ch-1", platforms, "Hello campaign",
                CampaignStatus.SCHEDULED, 1L, DeliveryPolicy.INSTANT,
                1000L, 2000L, 10, creator, "client-1", 500L);

        provider.saveCampaign(campaign);

        Optional<Campaign> loaded = provider.getCampaign("CMP-DEAD01");
        assertThat(loaded).isPresent();
        Campaign c = loaded.get();
        assertThat(c.getId()).isEqualTo("CMP-DEAD01");
        assertThat(c.getChannelId()).isEqualTo("ch-1");
        assertThat(c.getPlatforms()).containsExactlyInAnyOrder("BUKKIT", "FABRIC");
        assertThat(c.getContent()).isEqualTo("Hello campaign");
        assertThat(c.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(c.getScheduleRevision()).isEqualTo(1L);
        assertThat(c.getDeliveryPolicy()).isEqualTo(DeliveryPolicy.INSTANT);
        assertThat(c.getStartAt()).isEqualTo(1000L);
        assertThat(c.getEndAt()).isEqualTo(2000L);
        assertThat(c.getRateLimitPerChannelPerHour()).isEqualTo(10);
        assertThat(c.getCreatorId()).isEqualTo(creator);
        assertThat(c.getCreatorClientId()).isEqualTo("client-1");
        assertThat(c.getCreatedAt()).isEqualTo(500L);
        assertThat(c.getRevokedAt()).isZero();
        assertThat(c.getRevokedBy()).isNull();
    }

    @Test
    @DisplayName("schema v14: campaigns save is an upsert (DELETE+INSERT on id) — re-save updates status")
    void campaignsUpsert() throws DatabaseException {
        UUID creator = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        Set<String> platforms = new LinkedHashSet<>(Arrays.asList("SPONGE"));
        Campaign first = new Campaign(
                "CMP-UP01", "ch-1", platforms, "First",
                CampaignStatus.PREVIEW, 0L, DeliveryPolicy.TITLE_FALLBACK,
                0L, 0L, 5, creator, "client-1", 100L);
        provider.saveCampaign(first);

        Campaign second = new Campaign(
                "CMP-UP01", "ch-1", platforms, "Second",
                CampaignStatus.ACTIVE, 1L, DeliveryPolicy.TITLE_FALLBACK,
                100L, 200L, 5, creator, "client-1", 100L);
        provider.saveCampaign(second);

        Optional<Campaign> loaded = provider.getCampaign("CMP-UP01");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getContent()).isEqualTo("Second");
        assertThat(loaded.get().getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(loaded.get().getScheduleRevision()).isEqualTo(1L);
        // getAllPersistedCampaigns must not duplicate the row.
        assertThat(provider.getAllPersistedCampaigns()).hasSize(1);
    }

    @Test
    @DisplayName("schema v14: campaigns delete removes the row; missing-id delete is a no-op")
    void campaignsDelete() throws DatabaseException {
        UUID creator = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        Set<String> platforms = new LinkedHashSet<>(Arrays.asList("FABRIC"));
        Campaign campaign = new Campaign(
                "CMP-DEL01", "ch-1", platforms, "Delete me",
                CampaignStatus.SCHEDULED, 0L, DeliveryPolicy.INSTANT,
                0L, 0L, 5, creator, "client-1", 100L);
        provider.saveCampaign(campaign);

        provider.deleteCampaign("CMP-DEL01");
        assertThat(provider.getCampaign("CMP-DEL01")).isEmpty();
        assertThat(provider.getAllPersistedCampaigns()).isEmpty();

        // Deleting again must not throw.
        provider.deleteCampaign("CMP-DEL01");
    }

    @Test
    @DisplayName("schema v14: campaigns updateCampaignStatus stamps revokedAt + revokedBy + REVOKED status")
    void campaignsUpdateStatus() throws DatabaseException {
        UUID creator = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
        UUID revoker = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
        Set<String> platforms = new LinkedHashSet<>(Arrays.asList("FABRIC"));
        Campaign campaign = new Campaign(
                "CMP-REV01", "ch-1", platforms, "Revoke me",
                CampaignStatus.ACTIVE, 0L, DeliveryPolicy.INSTANT,
                0L, 0L, 5, creator, "client-1", 100L);
        provider.saveCampaign(campaign);

        provider.updateCampaignStatus("CMP-REV01", CampaignStatus.REVOKED, 999L, revoker);

        Optional<Campaign> loaded = provider.getCampaign("CMP-REV01");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getStatus()).isEqualTo(CampaignStatus.REVOKED);
        assertThat(loaded.get().getRevokedAt()).isEqualTo(999L);
        assertThat(loaded.get().getRevokedBy()).isEqualTo(revoker);
    }

    @Test
    @DisplayName("schema v14: campaigns DeliveryPolicy.ACTIONBAR_FALLBACK round-trips (enum dbValue() parity)")
    void campaignsDeliveryPolicyActionbarFallback() throws DatabaseException {
        Set<String> platforms = new LinkedHashSet<>(Arrays.asList("NEOFORGE"));
        Campaign campaign = new Campaign(
                "CMP-POL01", "ch-1", platforms, "Policy",
                CampaignStatus.PREVIEW, 0L, DeliveryPolicy.ACTIONBAR_FALLBACK,
                0L, 0L, 5, null, null, 100L);
        provider.saveCampaign(campaign);

        Optional<Campaign> loaded = provider.getCampaign("CMP-POL01");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDeliveryPolicy()).isEqualTo(DeliveryPolicy.ACTIONBAR_FALLBACK);
    }

    // ==================== provider type ====================

    @Test
    @DisplayName("provider type is PostgreSQL")
    void providerTypeIsPostgreSQL() {
        assertThat(provider.getProviderType()).isEqualTo("PostgreSQL");
    }
}
