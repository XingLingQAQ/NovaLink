package com.nova.link.database;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Nested-connection regression test for {@link MySQLProvider} on a pool of
 * size 1, against a real MySQL/MariaDB server.
 *
 * <p>The defect and the fix are identical in all three JDBC providers:
 * {@code loadPlayerState} held the only pooled connection and then asked the
 * pool for a second one to read mutes, so at poolSize=1 the borrow blocked for
 * the full {@code connectionTimeout} and then threw. This test pins the MySQL
 * flavour of that path.
 *
 * <p>Gated the same way as {@link PostgreSQLRealInstanceTest}: it uses whatever
 * {@code NOVALINK_MYSQL_*} points at (defaults 127.0.0.1:3306) and skips via
 * {@code assumeTrue} when the server is unreachable, so a Docker-less or
 * MySQL-less box stays green without claiming coverage it does not have.
 *
 * <p>Requirements: 22.1, 22.5
 */
@DisplayName("MySQL nested-connection regression (poolSize=1)")
class MySQLProviderPoolSizeOneTest {

    /** Generous vs. the real path (milliseconds) and tight vs. the 10s pool timeout. */
    private static final Duration BUDGET = Duration.ofSeconds(10);

    private static final String MYSQL_HOST = System.getenv().getOrDefault("NOVALINK_MYSQL_HOST", "127.0.0.1");
    private static final int MYSQL_PORT = Integer.parseInt(
            System.getenv().getOrDefault("NOVALINK_MYSQL_PORT", "3306"));
    private static final String MYSQL_DB = System.getenv().getOrDefault("NOVALINK_MYSQL_DB", "novalink_test");
    private static final String MYSQL_USER = System.getenv().getOrDefault("NOVALINK_MYSQL_USER", "novalink");
    private static final String MYSQL_PASSWORD = System.getenv().getOrDefault(
            "NOVALINK_MYSQL_PASSWORD", "novalink_test_password");
    /**
     * The provider's fail-closed default is REQUIRED; a local server without
     * provisioned certs needs the explicit plaintext opt-in, matched here by
     * {@code NOVALINK_MYSQL_ALLOW_PLAINTEXT} so production still fails closed.
     */
    private static final String MYSQL_SSL_MODE = System.getenv().getOrDefault(
            "NOVALINK_MYSQL_SSL_MODE", "DISABLED");
    private static final boolean MYSQL_ALLOW_PLAINTEXT = Boolean.parseBoolean(
            System.getenv().getOrDefault("NOVALINK_MYSQL_ALLOW_PLAINTEXT", "true"));

    private MySQLProvider provider;

    /**
     * Probes the configured server with a short login timeout. Returns false
     * (never throws) so the caller can skip rather than fail the build.
     */
    private static boolean mysqlReachable() {
        Properties props = new Properties();
        props.setProperty("user", MYSQL_USER);
        props.setProperty("password", MYSQL_PASSWORD);
        props.setProperty("connectTimeout", "2000");
        try {
            String url = "jdbc:mysql://" + MYSQL_HOST + ":" + MYSQL_PORT + "/" + MYSQL_DB
                    + "?sslMode=" + MYSQL_SSL_MODE + "&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=UTC&connectTimeout=2000";
            try (Connection conn = DriverManager.getConnection(url, props)) {
                return conn.isValid(2);
            }
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Drops every table the migrations create, so each test method starts from
     * a clean schema — the same per-test isolation posture as the SQLite
     * {@code @TempDir} and PostgreSQL real-instance tests.
     */
    private static void dropAllTables(MySQLProvider p) throws SQLException {
        try (Connection conn = p.getDataSource().getConnection();
             Statement stmt = conn.createStatement()) {
            // MySQL has no DROP ... CASCADE; drop children before parents.
            String[] tables = {
                    "campaigns", "social_relations", "notification_preferences",
                    "config_history", "appeals", "case_evidence", "moderation_cases",
                    "notification_read", "notifications", "audit_events",
                    "webhooks", "announcements", "messages",
                    "channel_members", "invitations", "bans", "mutes", "channels", "players",
                    "novalink_migrations"
            };
            for (String table : tables) {
                stmt.executeUpdate("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @BeforeEach
    void setUp() throws DatabaseException {
        assumeTrue(mysqlReachable(), "MySQL not reachable at " + MYSQL_HOST + ":" + MYSQL_PORT
                + "/" + MYSQL_DB + " — skipping poolSize=1 regression test");

        // poolSize = 1: the exact configuration the defect deadlocked.
        provider = new MySQLProvider(MYSQL_HOST, MYSQL_PORT, MYSQL_DB, MYSQL_USER, MYSQL_PASSWORD,
                1, MYSQL_SSL_MODE, MYSQL_ALLOW_PLAINTEXT);
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
    @DisplayName("loadPlayerState hydrates a global mute (null channel) on a 1-connection pool")
    void loadPlayerStateHydratesGlobalMute() {
        UUID playerId = UUID.randomUUID();

        assertTimeoutPreemptively(BUDGET, () -> {
            provider.savePlayerState(new PlayerState(playerId, "GlobalMute"));
            provider.saveMute(playerId, new MuteInfo(null, 0, "global", UUID.randomUUID(), 1000L));

            Optional<PlayerState> loaded = provider.loadPlayerState(playerId);

            assertThat(loaded).isPresent();
            assertThat(loaded.get().getMutes()).containsOnlyKeys(DatabaseProvider.GLOBAL_KEY);
        }, "global mute hydration must not need a second connection");
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
