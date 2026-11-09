package com.nova.link.database;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerStateRecoveryTest {
    @Test
    void detachingQueuesTheExpectedInstanceWithoutDatabaseIoAndKeepsFailedWritesRetryable() throws Exception {
        var provider = new MemoryProvider() {
            int writes;
            boolean fail;

            @Override
            public void savePlayerState(PlayerState state) throws DatabaseException {
                writes++;
                if (fail) {
                    throw new DatabaseException("injected detached write failure");
                }
                super.savePlayerState(state);
            }
        };
        provider.initialize();
        var manager = new PlayerStateManager(provider);
        UUID player = UUID.randomUUID();
        try {
            var original = manager.getOrCreateState(player, "Player");
            assertThat(manager.saveState(original)).isTrue();
            manager.clearCache(false);
            var current = manager.getOrCreateState(player, "Player");
            assertThat(manager.detachState(original)).isFalse();
            assertThat(manager.getCachedState(player)).containsSame(current);
            current.setDmEnabled(false);

            assertThat(manager.detachState(current)).isTrue();
            assertThat(provider.writes).isEqualTo(1);
            assertThat(manager.getAllPlayerStates()).isEmpty();
            assertThat(manager.getDirtyCount()).isEqualTo(1);
            provider.fail = true;
            assertThat(manager.saveAllDirty()).isZero();
            assertThat(manager.getDirtyCount()).isEqualTo(1);
            provider.fail = false;
            assertThat(manager.saveAllDirty()).isEqualTo(1);
            assertThat(manager.getDirtyCount()).isZero();
            assertThat(manager.getAllPlayerStates()).isEmpty();
            assertThat(provider.loadPlayerState(player).orElseThrow().isDmEnabled()).isFalse();
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void preparingDoesNotPublishAPlayerOrReplaceANewerCachedState() throws Exception {
        var provider = new MemoryProvider();
        provider.initialize();
        var manager = new PlayerStateManager(provider);
        UUID player = UUID.randomUUID();
        try {
            var prepared = manager.prepareState(player, "Player");
            assertThat(manager.getAllPlayerStates()).isEmpty();
            assertThat(manager.getDirtyCount()).isZero();

            var current = manager.getOrCreateState(player, "Player");
            current.setDmEnabled(false);
            manager.joinChannel(player, "current");
            assertThat(manager.cachePreparedState(prepared)).isSameAs(current);
            assertThat(manager.saveAllDirty()).isEqualTo(1);
            var stored = provider.loadPlayerState(player).orElseThrow();
            assertThat(stored.isDmEnabled()).isFalse();
            assertThat(stored.getJoinedChannels()).containsExactly("current");
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void aReadFailureCannotBecomeANewDefaultPlayerRecord() throws Exception {
        var provider = new MemoryProvider() {
            boolean fail;

            @Override
            public Optional<PlayerState> loadPlayerState(UUID player) throws DatabaseException {
                if (fail) {
                    throw new DatabaseException("injected player read failure");
                }
                return super.loadPlayerState(player);
            }
        };
        provider.initialize();
        var manager = new PlayerStateManager(provider);
        UUID player = UUID.randomUUID();
        try {
            var stored = new PlayerState(player, "Player");
            stored.setDmEnabled(false);
            stored.addJoinedChannel("stored");
            provider.savePlayerState(stored);
            provider.fail = true;

            assertThatThrownBy(() -> manager.getOrCreateState(player, "Player"))
                    .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(DatabaseException.class);
            assertThat(manager.getCachedState(player)).isEmpty();
            assertThat(manager.getDirtyCount()).isZero();
            assertThat(manager.saveAllDirty()).isZero();
            provider.fail = false;
            var restored = manager.getOrCreateState(player, "Player");
            assertThat(restored.isDmEnabled()).isFalse();
            assertThat(restored.getJoinedChannels()).containsExactly("stored");
        } finally {
            provider.shutdown();
        }
    }

    private static class FailingProvider extends MemoryProvider {
        boolean fail;

        @Override
        public void savePlayerState(PlayerState state) throws DatabaseException {
            if (fail) {
                throw new DatabaseException("injected player save failure");
            }
            super.savePlayerState(state);
        }
    }

    @Test
    void reconnectUsesTheUnsavedDepartureInsteadOfTheOldDatabaseRow() throws Exception {
        var provider = new FailingProvider();
        provider.initialize();
        var manager = new PlayerStateManager(provider);
        UUID player = UUID.randomUUID();
        try {
            var state = manager.getOrCreateState(player, "Player");
            manager.joinChannel(player, "old");
            assertThat(manager.saveState(state)).isTrue();
            manager.leaveChannel(player, "old");
            state.setDmEnabled(false);
            provider.fail = true;
            manager.removeFromCache(player, true);
            assertThat(manager.getCachedState(player)).isEmpty();

            var reconnected = manager.getOrCreateState(player, "Player");
            assertThat(reconnected.getJoinedChannels()).isEmpty();
            assertThat(reconnected.isDmEnabled()).isFalse();
            manager.joinChannel(player, "new");
            provider.fail = false;
            assertThat(manager.saveAllDirty()).isEqualTo(1);
            assertThat(provider.loadPlayerState(player).orElseThrow().getJoinedChannels()).containsExactly("new");
            assertThat(manager.getCachedState(player)).containsSame(reconnected);
            assertThat(manager.getDirtyCount()).isZero();
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void databaseLoadAndSavingClearPreserveAnOfflineWriteUntilItCanBeCommitted() throws Exception {
        var provider = new FailingProvider();
        provider.initialize();
        var manager = new PlayerStateManager(provider);
        UUID player = UUID.randomUUID();
        try {
            var state = manager.getOrCreateState(player, "Player");
            assertThat(manager.saveState(state)).isTrue();
            state.setDmEnabled(false);
            manager.markDirty(player);
            provider.fail = true;
            manager.clearCache(true);

            assertThat(manager.getAllPlayerStates()).isEmpty();
            assertThat(manager.getDirtyCount()).isEqualTo(1);
            assertThat(manager.loadState(player)).isEmpty();
            assertThat(manager.getAllPlayerStates()).isEmpty();
            assertThat(manager.getDirtyCount()).isEqualTo(1);
            provider.fail = false;
            assertThat(manager.loadState(player).orElseThrow().isDmEnabled()).isFalse();
            assertThat(provider.loadPlayerState(player).orElseThrow().isDmEnabled()).isFalse();
            assertThat(manager.getDirtyCount()).isZero();
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void deletingPendingStatePreventsAFutureFlushFromRecreatingIt() throws Exception {
        var provider = new FailingProvider();
        provider.initialize();
        var manager = new PlayerStateManager(provider);
        UUID player = UUID.randomUUID();
        try {
            manager.getOrCreateState(player, "Player");
            provider.fail = true;
            manager.removeFromCache(player, true);
            assertThat(manager.getDirtyCount()).isEqualTo(1);
            assertThat(manager.deleteState(player)).isTrue();
            provider.fail = false;
            assertThat(manager.saveAllDirty()).isZero();
            assertThat(manager.getDirtyCount()).isZero();
            assertThat(manager.getAllPlayerStates()).isEmpty();
            assertThat(provider.loadPlayerState(player)).isEmpty();
        } finally {
            provider.shutdown();
        }
    }
}
