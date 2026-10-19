package com.nova.link.database;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Nested-connection regression test for {@link PostgreSQLProvider} on a pool of
 * size 1, against a real host-installed PostgreSQL instance.
 *
 * <p>Mirrors {@link SQLiteProviderPoolSizeOneTest}: before the fix
 * {@code loadPlayerState} held the only pooled connection and then asked the
 * pool for a second one to read mutes, which blocked for the whole
 * {@code connectionTimeout} and then threw — so player-state restore could
 * never succeed at poolSize=1.
 *
 * <p>Gated the same way as {@link PostgreSQLRealInstanceTest}: it connects to
 * whatever {@code NOVALINK_PG_*} points at (defaults 127.0.0.1:5432) and skips
 * via {@code assumeTrue} when the server is unreachable, so a Docker-less box
 * stays green without claiming coverage it does not have.
 *
 * <p>Requirements: 22.1, 22.5
 */
@DisplayName("PostgreSQL nested-connection regression (poolSize=1)")
class PostgreSQLProviderPoolSizeOneTest {

    /** Generous vs. the real path (milliseconds) and tight vs. the 10s pool timeout. */
    private static final Duration BUDGET = Duration.ofSeconds(10);

    private static final String PG_HOST = System.getenv().getOrDefault("NOVALINK_PG_HOST", "127.0.0.1");
    private static final int PG_PORT = Integer.parseInt(System.getenv().getOrDefault("NOVALINK_PG_PORT", "5432"));
    private static final String PG_DB = System.getenv().getOrDefault("NOVALINK_PG_DB", "novalink_test");
    private static final String PG_USER = System.getenv().getOrDefault("NOVALINK_PG_USER", "novalink");
    private static final String PG_PASSWORD = System.getenv().getOrDefault("NOVALINK_PG_PASSWORD", "");
    private static final String PG_SSL_MODE = System.getenv().getOrDefault(
            "NOVALINK_PG_SSL_MODE", PostgreSQLProvider.DEFAULT_SSL_MODE);
    private static final boolean PG_ALLOW_PLAINTEXT = Boolean.parseBoolean(
            System.getenv().getOrDefault("NOVALINK_PG_ALLOW_PLAINTEXT", "false"));

    private PostgreSQLProvider provider;

    /**
     * Probes the configured server with a 2-second login timeout. Returns false
     * (never throws) so the caller can skip instead of failing the build.
     */
    private static boolean pgReachable() {
        Properties props = new Properties();
        props.setProperty("user", PG_USER);
        if (PG_PASSWORD != null && !PG_PASSWORD.isEmpty()) {
            props.setProperty("password", PG_PASSWORD);
        }
        props.setProperty("loginTimeout", "2");
        try {
            String url = new PostgreSQLProvider(PG_HOST, PG_PORT, PG_DB, PG_USER, PG_PASSWORD, 5,
                    PG_SSL_MODE, PG_ALLOW_PLAINTEXT).buildJdbcUrl();
            try (Connection conn = DriverManager.getConnection(url, props)) {
                return conn.isValid(2);
            }
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    /** Wipes the schema so each test method starts clean, as the real-instance test does. */
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
        assumeTrue(pgReachable(), "PostgreSQL not reachable at " + PG_HOST + ":" + PG_PORT
                + "/" + PG_DB + " — skipping poolSize=1 regression test");

        // poolSize = 1: the exact configuration the defect deadlocked.
        provider = new PostgreSQLProvider(PG_HOST, PG_PORT, PG_DB, PG_USER, PG_PASSWORD, 1,
                PG_SSL_MODE, PG_ALLOW_PLAINTEXT);
        provider.initialize();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            try {
                dropAllTables(provider);
            } catch (SQLException ignored) {
                // Drop failures are non-fatal — shutdown releases the pool anyway.
            }
            provider.shutdown();
        }
    }

    @Test
    @DisplayName("loadPlayerState on a 1-connection pool returns instead of starving the pool")
    void loadPlayerStateDoesNotBorrowANestedConnection() {
        UUID playerId = UUID.randomUUID();

        assertTimeoutPreemptively(BUDGET, () -> {
            PlayerState state = new PlayerState(playerId, "SoloPool");
            state.setCurrentWorld("world-1");
            provider.savePlayerState(state);
            provider.saveMute(playerId, new MuteInfo("ch-1", 0, "spam", UUID.randomUUID(), 1000L));

            Optional<PlayerState> loaded = provider.loadPlayerState(playerId);

            assertThat(loaded).isPresent();
            assertThat(loaded.get().getPlayerName()).isEqualTo("SoloPool");
            // The mute read is what used to demand the second connection.
            assertThat(loaded.get().getMutes()).containsOnlyKeys("ch-1");
            assertThat(loaded.get().getMutes().get("ch-1").getReason()).isEqualTo("spam");
        }, "loadPlayerState must not wait for a second connection on a size-1 pool");
    }

    @Test
    @DisplayName("getAllPlayerStates on a 1-connection pool hydrates every player")
    void getAllPlayerStatesDoesNotBorrowANestedConnection() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        assertTimeoutPreemptively(BUDGET, () -> {
            provider.savePlayerState(new PlayerState(p1, "P1"));
            provider.savePlayerState(new PlayerState(p2, "P2"));
            provider.saveMute(p1, new MuteInfo("ch-1", 0, "muted", UUID.randomUUID(), 1000L));

            List<PlayerState> all = provider.getAllPlayerStates();

            assertThat(all).extracting(PlayerState::getPlayerId).containsExactlyInAnyOrder(p1, p2);
            assertThat(all.stream()
                    .filter(s -> s.getPlayerId().equals(p1))
                    .findFirst()
                    .orElseThrow()
                    .getMutes()).containsOnlyKeys("ch-1");
        }, "getAllPlayerStates must not nest per-player connection borrows");
    }

    @Test
    @DisplayName("getAllChannels on a 1-connection pool does not borrow per row")
    void getAllChannelsDoesNotBorrowANestedConnection() {
        assertTimeoutPreemptively(BUDGET, () -> {
            provider.saveChannel(new Channel("a", "A", ChannelScope.GLOBAL, null));
            provider.saveChannel(new Channel("b", "B", ChannelScope.GLOBAL, null));

            List<Channel> all = provider.getAllChannels();

            assertThat(all).extracting(Channel::getId).containsExactlyInAnyOrder("a", "b");
        }, "getAllChannels must not nest per-row connection borrows");
    }

    @Test
    @DisplayName("standalone loadMutes/loadChannel still release their connection")
    void standaloneEntryPointsStillOwnTheirConnection() {
        UUID playerId = UUID.randomUUID();

        assertTimeoutPreemptively(BUDGET, () -> {
            provider.saveMute(playerId, new MuteInfo("ch-1", 0, "standalone", UUID.randomUUID(), 1000L));
            provider.saveChannel(new Channel("ch-1", "Chan", ChannelScope.GLOBAL, null));

            assertThat(provider.loadMutes(playerId)).hasSize(1);
            assertThat(provider.loadChannel("ch-1")).isPresent();
            // A second sequential borrow proves the first was returned.
            assertThat(provider.loadMutes(playerId)).hasSize(1);
        }, "standalone entry points must release their connection");
    }
}
