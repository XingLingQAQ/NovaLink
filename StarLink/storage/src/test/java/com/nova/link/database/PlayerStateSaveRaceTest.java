package com.nova.link.database;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerStateSaveRaceTest {
    @Test
    void overlappingSavesCannotFinishWithTheOlderSnapshot() throws Exception {
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var provider = new MemoryProvider() {
            boolean gated = true;

            @Override
            public void savePlayerState(PlayerState state) throws DatabaseException {
                PlayerState snapshot = new PlayerState(state);
                if (gated) {
                    gated = false;
                    writing.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new DatabaseException("Save gate timed out");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new DatabaseException("Save interrupted", error);
                    }
                }
                super.savePlayerState(snapshot);
            }
        };
        provider.initialize();
        var manager = new PlayerStateManager(provider);
        UUID player = UUID.randomUUID();
        PlayerState state = manager.getOrCreateState(player, "Player");
        manager.setActiveChannel(player, "before");
        var first = new FutureTask<>(() -> manager.saveState(state));
        var second = new FutureTask<>(() -> manager.saveState(state));
        Thread older = new Thread(first, "state-save-old");
        Thread newer = new Thread(second, "state-save-new");
        try {
            older.start();
            assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
            manager.setActiveChannel(player, "after");
            newer.start();
            var threads = ManagementFactory.getThreadMXBean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waitingForOlder = false;
            while (!second.isDone() && System.nanoTime() < deadline) {
                var info = threads.getThreadInfo(newer.getId());
                if (info != null && info.getThreadState() == Thread.State.BLOCKED
                        && info.getLockOwnerId() == older.getId()) {
                    waitingForOlder = true;
                    break;
                }
                Thread.yield();
            }
            assertThat(second.isDone() || waitingForOlder).isTrue();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(provider.loadPlayerState(player).orElseThrow().getActiveChannel()).isEqualTo("after");
            assertThat(manager.getDirtyCount()).isZero();
        } finally {
            release.countDown();
            older.join(5_000);
            newer.join(5_000);
            if (older.isAlive()) older.interrupt();
            if (newer.isAlive()) newer.interrupt();
            older.join(5_000);
            newer.join(5_000);
            provider.shutdown();
            assertThat(older.isAlive() || newer.isAlive()).isFalse();
        }
    }

    @Test
    void aMutationWhileSavingRemainsDirtyUntilItsOwnSnapshotIsSaved() throws Exception {
        var provider = new MemoryProvider() {
            Runnable afterSave = () -> {};

            @Override
            public void savePlayerState(PlayerState state) throws DatabaseException {
                super.savePlayerState(state);
                afterSave.run();
            }
        };
        provider.initialize();
        var manager = new PlayerStateManager(provider);
        UUID player = UUID.randomUUID();
        try {
            manager.getOrCreateState(player, "Player");
            manager.joinChannel(player, "before");
            manager.setActiveChannel(player, "before");
            provider.afterSave = () -> {
                manager.leaveChannel(player, "before");
                manager.joinChannel(player, "after");
                manager.setActiveChannel(player, "after");
            };

            assertThat(manager.saveAllDirty()).isEqualTo(1);
            assertThat(provider.loadPlayerState(player).orElseThrow().getActiveChannel()).isEqualTo("before");
            assertThat(manager.getPlayerState(player).getActiveChannel()).isEqualTo("after");
            assertThat(manager.getDirtyCount()).isEqualTo(1);
            provider.afterSave = () -> {};

            assertThat(manager.saveAllDirty()).isEqualTo(1);
            assertThat(manager.getDirtyCount()).isZero();
            assertThat(provider.loadPlayerState(player).orElseThrow().getJoinedChannels()).containsExactly("after");
            assertThat(provider.loadPlayerState(player).orElseThrow().getActiveChannel()).isEqualTo("after");
            assertThat(manager.saveAllDirty()).isZero();
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void failedFlushRetainsTheNewDirtyMarkerForRetry() throws Exception {
        var provider = new MemoryProvider() {
            boolean fail;
            Runnable duringFailure = () -> {};

            @Override
            public void savePlayerState(PlayerState state) throws DatabaseException {
                if (fail) {
                    duringFailure.run();
                    throw new DatabaseException("injected write failure");
                }
                super.savePlayerState(state);
            }
        };
        provider.initialize();
        var manager = new PlayerStateManager(provider);
        UUID player = UUID.randomUUID();
        try {
            manager.getOrCreateState(player, "Player");
            manager.joinChannel(player, "first");
            assertThat(manager.saveAllDirty()).isEqualTo(1);
            manager.leaveChannel(player, "first");
            provider.fail = true;
            provider.duringFailure = () -> manager.joinChannel(player, "latest");

            assertThat(manager.saveAllDirty()).isZero();
            assertThat(manager.getDirtyCount()).isEqualTo(1);
            assertThat(provider.loadPlayerState(player).orElseThrow().getJoinedChannels()).containsExactly("first");
            provider.fail = false;
            assertThat(manager.saveAllDirty()).isEqualTo(1);
            assertThat(provider.loadPlayerState(player).orElseThrow().getJoinedChannels()).containsExactly("latest");
            assertThat(manager.getDirtyCount()).isZero();
        } finally {
            provider.shutdown();
        }
    }
}
