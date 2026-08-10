package com.nova.link.database;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for {@link PostgreSQLProvider} using Testcontainers PostgreSQL.
 *
 * <p>Spins up a real PostgreSQL container, runs migrations, and exercises the
 * full CRUD surface (player state, channel, mute, ban, notification,
 * invitation). The test is skipped automatically when Docker is not available
 * on the host, so it never fails a Docker-less CI/dev box.
 *
 * <p>Requirements: 22.1, 22.5
 */
class PostgreSQLIntegrationTest {

    private PostgreSQLProvider provider;
    private PostgreSQLContainer<?> postgres;

    @BeforeEach
    void setUp() throws DatabaseException {
        // Skip gracefully when Docker is unavailable (dev boxes without Docker).
        assumeTrue(isDockerAvailable(), "Docker not available — skipping PostgreSQL integration test");

        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("novalink_test")
                .withUsername("novalink")
                .withPassword("novalink_test_password");
        postgres.start();

        provider = new PostgreSQLProvider(
                postgres.getHost(),
                postgres.getMappedPort(5432),
                postgres.getDatabaseName(),
                postgres.getUsername(),
                postgres.getPassword(),
                5
        );
        provider.initialize();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.shutdown();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void migrationCreatesAllTables() throws DatabaseException {
        // The migration should have run during initialize(); verify by using
        // the provider's surface — if tables were missing, CRUD would throw.
        assertThat(provider.isConnected()).isTrue();
        assertThat(provider.getAllChannels()).isEmpty();
        assertThat(provider.getAllPlayerStates()).isEmpty();
    }

    @Test
    void playerStateRoundTrip() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        PlayerState state = new PlayerState(playerId, "TestPlayer");
        state.setClientId("client-1");
        state.setCurrentWorld("world");
        state.setJoinedChannels(java.util.Set.of("global", "staff"));
        state.setActiveChannel("global");
        state.setPlatform("BUKKIT");
        state.setLastSeen(1234567890L);

        provider.savePlayerState(state);

        Optional<PlayerState> loaded = provider.loadPlayerState(playerId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getPlayerName()).isEqualTo("TestPlayer");
        assertThat(loaded.get().getClientId()).isEqualTo("client-1");
        assertThat(loaded.get().getCurrentWorld()).isEqualTo("world");
        assertThat(loaded.get().getJoinedChannels()).containsExactlyInAnyOrder("global", "staff");
        assertThat(loaded.get().getActiveChannel()).isEqualTo("global");
        assertThat(loaded.get().getPlatform()).isEqualTo("BUKKIT");
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
    void muteRoundTrip() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        MuteInfo mute = new MuteInfo("ch-1", 9999999999L, "spam", operatorId, 1000L);

        provider.saveMute(playerId, mute);

        List<MuteInfo> mutes = provider.loadMutes(playerId);
        assertThat(mutes).hasSize(1);
        assertThat(mutes.get(0).getChannelId()).isEqualTo("ch-1");
        assertThat(mutes.get(0).getReason()).isEqualTo("spam");
        assertThat(mutes.get(0).getOperatorId()).isEqualTo(operatorId);
    }

    @Test
    void banRoundTrip() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        BanInfo ban = new BanInfo(null, 0L, "toxic", operatorId, 1000L);

        provider.saveBan(playerId, ban);

        List<BanInfo> bans = provider.loadBans(playerId);
        assertThat(bans).hasSize(1);
        assertThat(bans.get(0).getChannelId()).isNull();
        assertThat(bans.get(0).getReason()).isEqualTo("toxic");
    }

    @Test
    void notificationRoundTripWithGeneratedId() throws DatabaseException {
        Notification n = new Notification("Title", "Body", Notification.LEVEL_WARNING);
        provider.saveNotification(n);

        // The generated id should be stamped back onto the object.
        assertThat(n.getId()).isGreaterThan(0);

        List<Notification> loaded = provider.getNotifications(0, 10, false);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getTitle()).isEqualTo("Title");
        assertThat(loaded.get(0).getLevel()).isEqualTo(Notification.LEVEL_WARNING);
        assertThat(loaded.get(0).isRead()).isFalse();

        // Unread count
        assertThat(provider.getUnreadCount()).isEqualTo(1);

        // Mark read
        provider.markNotificationRead(n.getId());
        assertThat(provider.getUnreadCount()).isZero();

        // Unread-only query returns nothing now
        assertThat(provider.getNotifications(0, 10, true)).isEmpty();
    }

    @Test
    void invitationRoundTrip() throws DatabaseException {
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
    }

    @Test
    void cleanupExpiredMutesAndBans() throws DatabaseException {
        UUID playerId = UUID.randomUUID();
        long past = System.currentTimeMillis() - 10000;
        MuteInfo expired = new MuteInfo("ch-1", past, "x", UUID.randomUUID(), 1000L);
        provider.saveMute(playerId, expired);

        int deleted = provider.cleanupExpiredMutes();
        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(provider.loadMutes(playerId)).isEmpty();
    }

    /**
     * Best-effort Docker availability probe. Testcontainers itself checks for
     * Docker, but probing first lets us skip with a clear assumption rather
     * than failing the test with a container startup error.
     */
    private boolean isDockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
