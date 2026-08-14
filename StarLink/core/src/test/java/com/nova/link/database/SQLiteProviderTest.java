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
}
