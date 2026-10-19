package com.nova.link.database;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression test for the nested-connection defect: an aggregating read that
 * borrowed a second pooled connection while still holding the first.
 *
 * <p>Every test here runs the provider on a pool of <b>size 1</b>. Before the
 * fix, {@code loadPlayerState} held the only connection and then called
 * {@code loadMutes}, which asked the pool for another one; HikariCP blocked
 * until {@code connectionTimeout} (10s here) and then threw, so player-state
 * restore could never succeed. The {@code assertTimeoutPreemptively} guards
 * turn that hang into a fast, explicit failure instead of a 10-second stall.
 *
 * <p>The assertions deliberately keep the mute lookup inside the aggregating
 * call: dropping the mute read would also make the test pass while silently
 * losing data.
 *
 * <p>Requirements: 22.1, 22.5
 */
class SQLiteProviderPoolSizeOneTest {

    /** Generous vs. the real path (milliseconds) and tight vs. the 10s pool timeout. */
    private static final Duration BUDGET = Duration.ofSeconds(5);

    @TempDir
    Path tempDir;

    private SQLiteProvider provider;

    @BeforeEach
    void setUp() throws DatabaseException {
        // poolSize = 1: the exact configuration the defect deadlocked.
        provider = new SQLiteProvider(tempDir.resolve("pool-one.db").toString(), 1);
        provider.initialize();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.shutdown();
        }
    }

    @Test
    @DisplayName("loadPlayerState on a 1-connection pool returns instead of starving the pool")
    void loadPlayerStateDoesNotBorrowANestedConnection() {
        UUID playerId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();

        assertTimeoutPreemptively(BUDGET, () -> {
            PlayerState state = new PlayerState(playerId, "SoloPool");
            state.setCurrentWorld("world-1");
            provider.savePlayerState(state);
            provider.saveMute(playerId, new MuteInfo("ch-1", 0, "spam", operatorId, 1000L));

            Optional<PlayerState> loaded = provider.loadPlayerState(playerId);

            assertThat(loaded).isPresent();
            assertThat(loaded.get().getPlayerName()).isEqualTo("SoloPool");
            assertThat(loaded.get().getCurrentWorld()).isEqualTo("world-1");
            // The mute must still be hydrated by the same call — this is the
            // work that used to require the second connection.
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
            // A null channelId is keyed under GLOBAL_KEY, not dropped.
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
            Map<String, MuteInfo> mutesOfP1 = all.stream()
                    .filter(s -> s.getPlayerId().equals(p1))
                    .findFirst()
                    .orElseThrow()
                    .getMutes();
            assertThat(mutesOfP1).containsOnlyKeys("ch-1");
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
    @DisplayName("public loadMutes/loadChannel still work on their own with poolSize=1")
    void standaloneEntryPointsStillOwnTheirConnection() {
        UUID playerId = UUID.randomUUID();

        assertTimeoutPreemptively(BUDGET, () -> {
            provider.saveMute(playerId, new MuteInfo("ch-1", 0, "standalone", UUID.randomUUID(), 1000L));
            provider.saveChannel(new Channel("ch-1", "Chan", ChannelScope.GLOBAL, null));

            // The overload refactor must leave the public API behaviour intact:
            // these entry points borrow their own connection and close it.
            assertThat(provider.loadMutes(playerId)).hasSize(1);
            assertThat(provider.loadChannel("ch-1")).isPresent();
            // Sequential borrows prove the previous one was released.
            assertThat(provider.loadMutes(playerId)).hasSize(1);
        }, "standalone entry points must release their connection");
    }

    @Test
    @DisplayName("missing player on a 1-connection pool returns empty without a second borrow")
    void loadPlayerStateOfUnknownPlayerReturnsEmpty() {
        assertTimeoutPreemptively(BUDGET, () ->
                        assertThat(provider.loadPlayerState(UUID.randomUUID())).isEmpty(),
                "an absent player must short-circuit before any mute lookup");
    }
}
