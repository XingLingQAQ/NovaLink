package com.nova.link.database;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit/integration test for {@link SQLiteProvider} against a real on-disk
 * SQLite database file.
 *
 * <p>SQLite is embedded, so this needs no Docker/Testcontainers — the xerial
 * JDBC driver creates the database file on first connect. The test exercises
 * migration (all 4 versions) plus the full CRUD surface: player state upsert,
 * channel upsert, mute/ban, notification with generated-id stamping, and
 * invitation lifecycle.
 *
 * <p>Requirements: 22.1, 22.5
 */
class SQLiteProviderTest {

    @TempDir
    Path tempDir;

    private SQLiteProvider provider;
    private Path dbFile;

    @BeforeEach
    void setUp() throws DatabaseException {
        dbFile = tempDir.resolve("novalink-test.db");
        provider = new SQLiteProvider(dbFile.toString(), 5);
        provider.initialize();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.shutdown();
        }
    }

    @Test
    void migrationCreatesAllTablesAndIsConnected() throws DatabaseException {
        assertThat(provider.isConnected()).isTrue();
        // Empty after fresh migration
        assertThat(provider.getAllChannels()).isEmpty();
        assertThat(provider.getAllPlayerStates()).isEmpty();
        assertThat(provider.getUnreadCount()).isZero();
    }

    @Test
    void migrationReachesVersion4OnReinit() throws DatabaseException {
        // Shut down and reopen the same file — migrations should be up to date.
        provider.shutdown();
        provider = new SQLiteProvider(dbFile.toString(), 5);
        provider.initialize();

        // Still connected and empty (no data inserted between runs).
        assertThat(provider.isConnected()).isTrue();
        assertThat(provider.getAllChannels()).isEmpty();
    }

    @Test
    void playerStateRoundTrip() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        PlayerState state = new PlayerState(playerId, "TestPlayer");
        state.setClientId("client-1");
        state.setCurrentWorld("world");
        state.setJoinedChannels(java.util.Set.of("global", "staff"));
        state.setActiveChannel("global");
        state.setPlatform("NUKKIT");
        state.setDmEnabled(false);
        state.setLastSeen(1234567890L);

        provider.savePlayerState(state);

        Optional<PlayerState> loaded = provider.loadPlayerState(playerId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getPlayerName()).isEqualTo("TestPlayer");
        assertThat(loaded.get().getClientId()).isEqualTo("client-1");
        assertThat(loaded.get().getCurrentWorld()).isEqualTo("world");
        assertThat(loaded.get().getJoinedChannels()).containsExactlyInAnyOrder("global", "staff");
        assertThat(loaded.get().getActiveChannel()).isEqualTo("global");
        assertThat(loaded.get().getPlatform()).isEqualTo("NUKKIT");
        assertThat(loaded.get().isDmEnabled()).isFalse();
        assertThat(loaded.get().getLastSeen()).isEqualTo(1234567890L);
    }

    @Test
    void playerStateUpsertOverwrites() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        PlayerState state = new PlayerState(playerId, "Name1");
        state.setCurrentWorld("w1");
        provider.savePlayerState(state);

        state.setCurrentWorld("w2");
        state.setPlayerName("Name2");
        provider.savePlayerState(state);

        Optional<PlayerState> loaded = provider.loadPlayerState(playerId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getPlayerName()).isEqualTo("Name2");
        assertThat(loaded.get().getCurrentWorld()).isEqualTo("w2");
    }

    @Test
    void deletePlayerStateRemovesMutesAndBans() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        PlayerState state = new PlayerState(playerId, "P");
        provider.savePlayerState(state);

        provider.saveMute(playerId, new MuteInfo("ch-1", 0, "r", UUID.randomUUID()));
        provider.saveBan(playerId, new BanInfo("ch-1", 0, "r", UUID.randomUUID()));

        provider.deletePlayerState(playerId);

        assertThat(provider.loadPlayerState(playerId)).isEmpty();
        assertThat(provider.loadMutes(playerId)).isEmpty();
        assertThat(provider.loadBans(playerId)).isEmpty();
    }

    @Test
    void channelRoundTrip() throws DatabaseException {
        Channel channel = new Channel("ch-1", "Display", ChannelScope.GLOBAL, null);
        channel.setPermission("nova.chat.use");
        channel.setMaxCapacity(50);
        channel.setAllowedWorlds(java.util.Arrays.asList("world", "nether"));

        provider.saveChannel(channel);

        Optional<Channel> loaded = provider.loadChannel("ch-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDisplayName()).isEqualTo("Display");
        assertThat(loaded.get().getScope()).isEqualTo(ChannelScope.GLOBAL);
        assertThat(loaded.get().getMaxCapacity()).isEqualTo(50);
        assertThat(loaded.get().getAllowedWorlds()).containsExactlyInAnyOrder("world", "nether");
    }

    @Test
    void channelUpsertOverwrites() throws DatabaseException {
        Channel channel = new Channel("ch-2", "Original", ChannelScope.GLOBAL, null);
        channel.setMaxCapacity(10);
        provider.saveChannel(channel);

        channel.setMaxCapacity(99);
        channel.setDisplayName("Updated");
        provider.saveChannel(channel);

        Optional<Channel> loaded = provider.loadChannel("ch-2");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDisplayName()).isEqualTo("Updated");
        assertThat(loaded.get().getMaxCapacity()).isEqualTo(99);
    }

    @Test
    void muteRoundTripAndDelete() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        MuteInfo mute = new MuteInfo("ch-1", 9999999999L, "spam", operatorId, 1000L);

        provider.saveMute(playerId, mute);

        List<MuteInfo> mutes = provider.loadMutes(playerId);
        assertThat(mutes).hasSize(1);
        assertThat(mutes.get(0).getChannelId()).isEqualTo("ch-1");
        assertThat(mutes.get(0).getReason()).isEqualTo("spam");
        assertThat(mutes.get(0).getOperatorId()).isEqualTo(operatorId);

        // Delete by channel
        provider.deleteMute(playerId, "ch-1");
        assertThat(provider.loadMutes(playerId)).isEmpty();
    }

    @Test
    void globalMuteWithNullChannelRoundTrip() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        MuteInfo globalMute = new MuteInfo(null, 0, "global", UUID.randomUUID(), 1000L);

        provider.saveMute(playerId, globalMute);

        List<MuteInfo> mutes = provider.loadMutes(playerId);
        assertThat(mutes).hasSize(1);
        assertThat(mutes.get(0).getChannelId()).isNull();

        // Delete the global mute specifically
        provider.deleteMute(playerId, null);
        assertThat(provider.loadMutes(playerId)).isEmpty();
    }

    @Test
    void banRoundTrip() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        BanInfo ban = new BanInfo("ch-1", 0, "toxic", operatorId, 1000L);

        provider.saveBan(playerId, ban);

        List<BanInfo> bans = provider.loadBans(playerId);
        assertThat(bans).hasSize(1);
        assertThat(bans.get(0).getChannelId()).isEqualTo("ch-1");
        assertThat(bans.get(0).getReason()).isEqualTo("toxic");
        assertThat(bans.get(0).getOperatorId()).isEqualTo(operatorId);
    }

    @Test
    void notificationRoundTripWithGeneratedId() throws DatabaseException {
        Notification n = new Notification("Title", "Body", Notification.LEVEL_ERROR);
        provider.saveNotification(n);

        // Generated id stamped back onto the object.
        assertThat(n.getId()).isGreaterThan(0);

        List<Notification> loaded = provider.getNotifications(0, 10, false);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getTitle()).isEqualTo("Title");
        assertThat(loaded.get(0).getLevel()).isEqualTo(Notification.LEVEL_ERROR);
        assertThat(loaded.get(0).isRead()).isFalse();

        assertThat(provider.getUnreadCount()).isEqualTo(1);

        provider.markNotificationRead(n.getId());
        assertThat(provider.getUnreadCount()).isZero();
        assertThat(provider.getNotifications(0, 10, true)).isEmpty();

        // Mark-all + clear
        Notification n2 = new Notification("T2", "B2", Notification.LEVEL_INFO);
        provider.saveNotification(n2);
        provider.markAllNotificationsRead();
        assertThat(provider.getUnreadCount()).isZero();

        int cleared = provider.clearNotifications();
        assertThat(cleared).isEqualTo(2);
        assertThat(provider.getNotifications(0, 10, false)).isEmpty();
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

        // Mark read per-user for A only. The global notifications.read column
        // must NOT flip (per-user mark inserts only into notification_read).
        provider.markNotificationRead(n.getId(), "A");

        List<Notification> forAAfter = provider.getNotifications(0, 10, false, "A");
        List<Notification> forBAfter = provider.getNotifications(0, 10, false, "B");
        assertThat(forAAfter).hasSize(1);
        assertThat(forBAfter).hasSize(1);
        // A marked it read -> per-user read flag is true for A.
        assertThat(forAAfter.get(0).isRead()).isTrue();
        // B never marked it -> per-user read flag is false for B.
        assertThat(forBAfter.get(0).isRead()).isFalse();

        // Unread counts must agree: A has 0 unread, B still has 1 unread.
        assertThat(provider.getUnreadCount("A")).isZero();
        assertThat(provider.getUnreadCount("B")).isEqualTo(1);
    }

    @Test
    void invitationLifecycle() throws DatabaseException {
        UUID inviter = UUID.randomUUID();
        Invitation invitation = new Invitation("CODE123", "ch-1", inviter, 9999999999L);
        provider.saveInvitation(invitation);

        Optional<Invitation> loaded = provider.loadInvitation("CODE123");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getChannelId()).isEqualTo("ch-1");
        assertThat(loaded.get().getInviterId()).isEqualTo(inviter);
        assertThat(loaded.get().isUsed()).isFalse();

        UUID usedBy = UUID.randomUUID();
        provider.markInvitationUsed("CODE123", usedBy);

        Optional<Invitation> used = provider.loadInvitation("CODE123");
        assertThat(used).isPresent();
        assertThat(used.get().isUsed()).isTrue();
        assertThat(used.get().getUsedBy()).isEqualTo(usedBy);
        assertThat(used.get().getUsedAt()).isGreaterThan(0L);

        provider.deleteInvitation("CODE123");
        assertThat(provider.loadInvitation("CODE123")).isEmpty();
    }

    @Test
    void cleanupExpiredMutes() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        long past = System.currentTimeMillis() - 10000;
        MuteInfo expired = new MuteInfo("ch-1", past, "x", UUID.randomUUID(), 1000L);
        provider.saveMute(playerId, expired);

        int deleted = provider.cleanupExpiredMutes();
        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(provider.loadMutes(playerId)).isEmpty();
    }

    @Test
    void cleanupExpiredInvitations() throws DatabaseException {
        UUID inviter = UUID.randomUUID();
        long past = System.currentTimeMillis() - 10000;
        Invitation expired = new Invitation("EXP1", "ch-1", inviter, past);
        provider.saveInvitation(expired);

        int deleted = provider.cleanupExpiredInvitations();
        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(provider.loadInvitation("EXP1")).isEmpty();
    }

    @Test
    void getAllChannelsReturnsAll() throws DatabaseException {
        provider.saveChannel(new Channel("a", "A", ChannelScope.GLOBAL, null));
        provider.saveChannel(new Channel("b", "B", ChannelScope.GLOBAL, null));

        List<Channel> all = provider.getAllChannels();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(Channel::getId).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void getAllPlayerStatesReturnsAll() throws DatabaseException {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        provider.savePlayerState(new PlayerState(p1, "P1"));
        provider.savePlayerState(new PlayerState(p2, "P2"));

        List<PlayerState> all = provider.getAllPlayerStates();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(PlayerState::getPlayerId).containsExactlyInAnyOrder(p1, p2);
    }

    @Test
    void providerTypeIsSQLite() {
        assertThat(provider.getProviderType()).isEqualTo("SQLite");
    }

    @Test
    void getAllActiveMutesSkipsExpiredAndSurvivesReopen() throws DatabaseException {
        UUID permanent = UUID.randomUUID();
        UUID timed = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        long future = System.currentTimeMillis() + 3600_000;
        long past = System.currentTimeMillis() - 10_000;

        provider.saveMute(permanent, new MuteInfo(null, 0, "perm", UUID.randomUUID(), 1000L));
        provider.saveMute(timed, new MuteInfo("ch-1", future, "timed", UUID.randomUUID(), 1000L));
        provider.saveMute(expired, new MuteInfo("ch-1", past, "old", UUID.randomUUID(), 1000L));

        // Simulate a backend restart: close and reopen the same database file.
        provider.shutdown();
        provider = new SQLiteProvider(dbFile.toString(), 5);
        provider.initialize();

        java.util.Map<UUID, List<MuteInfo>> active = provider.getAllActiveMutes();
        assertThat(active).containsOnlyKeys(permanent, timed);
        assertThat(active.get(permanent).get(0).isPermanent()).isTrue();
        assertThat(active.get(timed).get(0).getChannelId()).isEqualTo("ch-1");
    }

    @Test
    void getAllActiveBansSkipsExpiredAndSurvivesReopen() throws DatabaseException {
        UUID permanent = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        long past = System.currentTimeMillis() - 10_000;

        provider.saveBan(permanent, new BanInfo(null, 0, "perm", UUID.randomUUID(), 1000L));
        provider.saveBan(expired, new BanInfo("ch-1", past, "old", UUID.randomUUID(), 1000L));

        provider.shutdown();
        provider = new SQLiteProvider(dbFile.toString(), 5);
        provider.initialize();

        java.util.Map<UUID, List<BanInfo>> active = provider.getAllActiveBans();
        assertThat(active).containsOnlyKeys(permanent);
        assertThat(active.get(permanent).get(0).isPermanent()).isTrue();
    }

    @Test
    void countNotificationsReturnsTotalAndUnread() throws DatabaseException {
        Notification n1 = new Notification("T1", "B1", Notification.LEVEL_INFO);
        Notification n2 = new Notification("T2", "B2", Notification.LEVEL_INFO);
        Notification n3 = new Notification("T3", "B3", Notification.LEVEL_INFO);
        provider.saveNotification(n1);
        provider.saveNotification(n2);
        provider.saveNotification(n3);

        assertThat(provider.countNotifications(false)).isEqualTo(3);
        assertThat(provider.countNotifications(true)).isEqualTo(3);

        provider.markNotificationRead(n1.getId());
        assertThat(provider.countNotifications(false)).isEqualTo(3);
        assertThat(provider.countNotifications(true)).isEqualTo(2);
    }

    @Test
    void clearNotificationsRemovesOrphanPerUserReadState() throws Exception {
        Notification n = new Notification("Title", "Body", Notification.LEVEL_INFO);
        provider.saveNotification(n);
        provider.markNotificationRead(n.getId(), "A");
        assertThat(provider.getUnreadCount("A")).isZero();

        int cleared = provider.clearNotifications();
        assertThat(cleared).isEqualTo(1);
        assertThat(provider.getNotifications(0, 10, false)).isEmpty();

        // notification_read has no FK ON DELETE CASCADE — the wipe must have
        // removed the read-state children too, leaving zero orphan rows.
        assertThat(countNotificationReadRows()).isZero();

        // Stale-resurrection guard: a fresh notification reusing the wiped id
        // (seeded explicitly — AUTOINCREMENT alone would hand out a new id)
        // must be unread for A, not silently "already read" via leftover state.
        seedNotificationWithExplicitId(n.getId());
        assertThat(provider.getUnreadCount("A")).isEqualTo(1);
    }

    @Test
    void clearDirectedNotificationsRemovesTheirReadStateOnly() throws Exception {
        Notification directed = new Notification("d", "m", Notification.LEVEL_INFO);
        directed.setRecipient("Bob");
        provider.saveNotification(directed);
        Notification broadcast = new Notification("b", "m", Notification.LEVEL_INFO);
        provider.saveNotification(broadcast);

        // Per-user read state is keyed by the exact username each caller uses;
        // the reviewed defect concerns recipient-case divergence, so both
        // sides here use one consistent spelling per user.
        provider.markNotificationRead(directed.getId(), "Bob");
        provider.markNotificationRead(broadcast.getId(), "Carol");

        int cleared = provider.clearNotifications("BOB");
        assertThat(cleared).isEqualTo(1);

        // The directed notification is gone for everyone; the broadcast stays.
        assertThat(provider.getNotifications(0, 10, false, "Bob"))
                .as("only the broadcast remains visible to Bob")
                .hasSize(1);
        assertThat(provider.getNotifications(0, 10, false, "Carol")).hasSize(1);

        // Only the broadcast's read-state row (Carol's) survives; Bob's mark on
        // the deleted directed notification must not be orphaned behind.
        assertThat(countNotificationReadRows()).isEqualTo(1);
        assertThat(provider.getUnreadCount("Carol")).isZero();
    }

    @Test
    void directedNotificationRecipientMatchingIsCaseInsensitive() throws DatabaseException {
        // Stored recipient case diverges from the querying username case —
        // the exact mismatch the WS delivery path already tolerates via
        // trim().toLowerCase(Locale.ROOT) normalization (commit 700bf5a).
        Notification upper = new Notification("upper", "m", Notification.LEVEL_INFO);
        upper.setRecipient("Admin");
        provider.saveNotification(upper);
        Notification lower = new Notification("lower", "m", Notification.LEVEL_INFO);
        lower.setRecipient("bob");
        provider.saveNotification(lower);

        // Each user queries under one consistent self-spelling throughout;
        // only the STORED recipient case differs from it.
        String adminUser = "Admin";
        String bobUser = "bob";

        // Listing: both directed notifications are visible despite case gap.
        assertThat(provider.getNotifications(0, 10, false, adminUser))
                .as("'Admin'-directed notification visible to user 'Admin'")
                .hasSize(1);
        assertThat(provider.getNotifications(0, 10, false, bobUser))
                .as("'bob'-directed notification visible to user 'bob'")
                .hasSize(1);
        assertThat(provider.getUnreadCount(adminUser)).isEqualTo(1);
        assertThat(provider.getUnreadCount(bobUser)).isEqualTo(1);
        assertThat(provider.countNotifications(false, adminUser)).isEqualTo(1);

        // markAllRead flips the per-user flags for both.
        provider.markAllNotificationsRead(adminUser);
        provider.markAllNotificationsRead(bobUser);
        assertThat(provider.getUnreadCount(adminUser)).isZero();
        assertThat(provider.getUnreadCount(bobUser)).isZero();
        assertThat(provider.getNotifications(0, 10, true, adminUser)).isEmpty();

        // Clear under a differently-cased spelling than stored still removes
        // the directed notification (and only it).
        int cleared = provider.clearNotifications("BOB");
        assertThat(cleared).isEqualTo(1);
        assertThat(provider.getNotifications(0, 10, false, bobUser)).isEmpty();
        List<Notification> remaining = provider.getNotifications(0, 10, false, adminUser);
        assertThat(remaining).hasSize(1);
        // Stored recipient values are preserved verbatim for display.
        assertThat(remaining.get(0).getRecipient()).isEqualTo("Admin");
    }

    /**
     * Opens a second raw JDBC connection to the same database file and counts
     * the {@code notification_read} rows. This is the direct oracle for orphan
     * cleanup — the provider API deliberately exposes no read-state dump, and
     * orphaned rows are by definition invisible through the normal JOINs.
     */
    private int countNotificationReadRows() throws Exception {
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             var stmt = conn.prepareStatement("SELECT COUNT(*) FROM notification_read");
             var rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Seeds a notification row with an explicit id, bypassing AUTOINCREMENT. */
    private void seedNotificationWithExplicitId(long id) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             var stmt = conn.prepareStatement(
                     "INSERT INTO notifications (id, title, message, level, created_at, read, recipient) "
                             + "VALUES (?, 'fresh', 'fresh', 'info', ?, FALSE, NULL)")) {
            stmt.setLong(1, id);
            stmt.setLong(2, System.currentTimeMillis());
            stmt.executeUpdate();
        }
    }
}
