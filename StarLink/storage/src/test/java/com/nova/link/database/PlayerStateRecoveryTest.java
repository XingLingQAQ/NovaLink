package com.nova.link.database;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerStateRecoveryTest {
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
