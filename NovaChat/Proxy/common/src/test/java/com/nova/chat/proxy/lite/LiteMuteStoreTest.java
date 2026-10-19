package com.nova.chat.proxy.lite;

import com.nova.link.database.MuteInfo;
import com.nova.chat.proxy.config.LiteStorageConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Store-level mute persistence for lite mode.
 *
 * <p>These tests drive {@link LiteChannelStore} directly (no authority, no
 * command layer) so a failure points at the storage contract itself. The SQLite
 * cases use a genuine database file in a temporary directory plus a full close
 * and reopen, so they fail if the rows live only in the process.
 *
 * <p>What they pin, in order:
 * <ul>
 *   <li>{@code memory} stays a pure no-op (the store must not gain a thread or
 *       start touching files just because mutes exist);</li>
 *   <li>a mute written before the backend is opened is not dropped (the same
 *       queue-order regression the membership store already guards);</li>
 *   <li>a saved mute survives close + reopen, and its channel scope and
 *       permanent-vs-expiring distinction round-trip intact;</li>
 *   <li>{@code deleteMute} actually removes the row;</li>
 *   <li>re-muting the same (player, channel) overwrites instead of duplicating
 *       or tripping {@code UNIQUE(player_id, channel_id)}.</li>
 * </ul>
 */
@DisplayName("Lite mute persistence (store level)")
class LiteMuteStoreTest {

    private static final String GLOBAL = "global";
    private static final String LOCAL = "local";

    @TempDir
    Path tempDir;

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @Test
    @DisplayName("memory mode performs no mute I/O and starts no storage thread")
    void memoryModeIsPureNoOpForMutes() {
        LiteChannelStore store = LiteChannelStore.memory();

        store.saveMute(UUID.randomUUID(), permanent(GLOBAL, "noop"));
        store.loadMutes(UUID.randomUUID(), rows -> {
            throw new AssertionError("a memory store must never invoke the sink");
        });
        store.deleteMute(UUID.randomUUID(), GLOBAL);
        store.close();

        assertThat(store.isPersistent()).isFalse();
        assertThat(threadExists()).isFalse();
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("a mute queued before open() still reaches the database")
    void muteBeforeOpenIsNotDropped() {
        Path database = tempDir.resolve("early-mute.db");
        UUID playerId = UUID.randomUUID();
        MuteInfo mute = expiring(GLOBAL, 60_000L, "spam", UUID.randomUUID());

        LiteChannelStore store = store(database);
        try {
            // Deliberately before open(): the I/O task connects the backend itself.
            store.saveMute(playerId, mute);
            store.open();
            assertThat(store.flush(30_000L)).isTrue();

            List<MuteInfo> loaded = read(store, playerId);
            assertThat(loaded).hasSize(1);
            assertThat(loaded.get(0).getChannelId()).isEqualTo(GLOBAL);
            assertThat(loaded.get(0).getReason()).isEqualTo("spam");
            assertThat(loaded.get(0).getExpireTime()).isEqualTo(mute.getExpireTime());
            assertThat(warnings).isEmpty();
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("a mute survives a close and reopen with its scope and expiry intact")
    void muteSurvivesRestart() {
        Path database = tempDir.resolve("restart-mute.db");
        UUID playerId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        long expiresAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2);

        LiteChannelStore first = store(database);
        try {
            first.open();
            // One channel-scoped and one permanent global mute for the same player:
            // the pair proves the (player, channel) key is what distinguishes them.
            first.saveMute(playerId, new MuteInfo(GLOBAL, expiresAt, "caps", operatorId));
            first.saveMute(playerId, permanent(null, "global ban-ish"));
            assertThat(first.flush(30_000L)).isTrue();
        } finally {
            first.close();
        }

        assertThat(database).exists();

        LiteChannelStore second = store(database);
        try {
            second.open();
            assertThat(second.flush(30_000L)).isTrue();

            List<MuteInfo> loaded = read(second, playerId);
            assertThat(loaded).hasSize(2);

            MuteInfo channelMute = loaded.stream()
                    .filter(m -> GLOBAL.equals(m.getChannelId())).findFirst().orElseThrow();
            assertThat(channelMute.getReason()).isEqualTo("caps");
            assertThat(channelMute.getOperatorId()).isEqualTo(operatorId);
            assertThat(channelMute.getExpireTime()).isEqualTo(expiresAt);
            assertThat(channelMute.isPermanent()).isFalse();

            MuteInfo globalMute = loaded.stream()
                    .filter(m -> m.getChannelId() == null).findFirst().orElseThrow();
            assertThat(globalMute.getReason()).isEqualTo("global ban-ish");
            // 0 must keep meaning permanent: a restart must not turn it into
            // "expired in 1970" and silently lift the mute.
            assertThat(globalMute.getExpireTime()).isZero();
            assertThat(globalMute.isPermanent()).isTrue();
            assertThat(globalMute.isExpired()).isFalse();
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("re-muting the same player and channel overwrites the earlier record")
    void remuteOverwrites() {
        Path database = tempDir.resolve("remute.db");
        UUID playerId = UUID.randomUUID();

        LiteChannelStore store = store(database);
        try {
            store.open();
            store.saveMute(playerId, permanent(GLOBAL, "first"));
            assertThat(store.flush(30_000L)).isTrue();
            store.saveMute(playerId, expiring(GLOBAL, 5_000L, "second", UUID.randomUUID()));
            assertThat(store.flush(30_000L)).isTrue();

            List<MuteInfo> loaded = read(store, playerId);
            assertThat(loaded).as("upsert, not append").hasSize(1);
            assertThat(loaded.get(0).getReason()).isEqualTo("second");
            assertThat(loaded.get(0).isPermanent()).isFalse();
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("deleteMute removes exactly the row it names, leaving other channels alone")
    void deleteRemovesOneRow() {
        Path database = tempDir.resolve("delete-mute.db");
        UUID playerId = UUID.randomUUID();

        LiteChannelStore store = store(database);
        try {
            store.open();
            store.saveMute(playerId, permanent(GLOBAL, "keep"));
            store.saveMute(playerId, permanent(LOCAL, "drop"));
            assertThat(store.flush(30_000L)).isTrue();

            store.deleteMute(playerId, LOCAL);
            assertThat(store.flush(30_000L)).isTrue();

            List<MuteInfo> loaded = read(store, playerId);
            assertThat(loaded).hasSize(1);
            assertThat(loaded.get(0).getChannelId()).isEqualTo(GLOBAL);
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("close is idempotent and still drains a mute queued just before it")
    void closeIsIdempotentAndDrainsMutes() {
        Path database = tempDir.resolve("close-mute.db");
        UUID playerId = UUID.randomUUID();

        LiteChannelStore store = store(database);
        store.open();
        store.saveMute(playerId, permanent(GLOBAL, "queued"));
        // Two closes: the platform shell and LocalAuthorityClient may both reach
        // this, and only the first may do work.
        store.close();
        store.close();

        LiteChannelStore reopened = store(database);
        try {
            reopened.open();
            assertThat(reopened.flush(30_000L)).isTrue();
            assertThat(read(reopened, playerId))
                    .as("the write queued before close() reached the database")
                    .hasSize(1);
        } finally {
            reopened.close();
        }
    }

    // ---------------------------------------------------------------- fixtures

    private LiteChannelStore store(Path database) {
        return LiteChannelStore.create(
                LiteStorageConfig.forTests(LiteStorageConfig.Type.SQLITE, database.toString(), 0, null),
                tempDir,
                Map.of(GLOBAL, channel(GLOBAL), LOCAL, channel(LOCAL)),
                "VELOCITY",
                warnings::add,
                message -> { });
    }

    private static com.nova.chat.proxy.config.LiteChannelConfig channel(String id) {
        return new com.nova.chat.proxy.config.LiteChannelConfig(id, id, null, 0, 0);
    }

    /** Queues a read and returns the rows (the store invokes the sink on its I/O thread). */
    private List<MuteInfo> read(LiteChannelStore store, UUID playerId) {
        List<MuteInfo> loaded = new CopyOnWriteArrayList<>();
        store.loadMutes(playerId, loaded::addAll);
        assertThat(store.flush(30_000L)).isTrue();
        return new ArrayList<>(loaded);
    }

    private static MuteInfo permanent(String channelId, String reason) {
        return new MuteInfo(channelId, 0L, reason, UUID.randomUUID());
    }

    private static MuteInfo expiring(String channelId, long durationMs, String reason,
                                     UUID operatorId) {
        return new MuteInfo(channelId, System.currentTimeMillis() + durationMs, reason, operatorId);
    }

    private static boolean threadExists() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> LiteChannelStore.IO_THREAD_NAME.equals(thread.getName()));
    }
}
