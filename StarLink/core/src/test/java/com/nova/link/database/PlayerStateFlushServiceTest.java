package com.nova.link.database;

import com.nova.link.lifecycle.ShutdownBudget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerStateFlushServiceTest {
    @Test
    void aScheduledFlushRetriesOfflineStateWithoutPublishingItOnline() throws Exception {
        var failedTick = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        var observe = new AtomicBoolean();
        var fail = new AtomicBoolean();
        var provider = new MemoryProvider() {
            @Override
            public void savePlayerState(PlayerState state) throws DatabaseException {
                if (fail.get()) {
                    if (observe.get()) {
                        failedTick.countDown();
                    }
                    throw new DatabaseException("injected player write failure");
                }
                super.savePlayerState(state);
                if (observe.get()) {
                    committed.countDown();
                }
            }
        };
        provider.initialize();
        var players = new PlayerStateManager(provider);
        var flusher = new PlayerStateFlushService(players, Duration.ofMillis(10));
        UUID player = UUID.randomUUID();
        try {
            var state = players.getOrCreateState(player, "Player");
            assertThat(players.saveState(state)).isTrue();
            players.joinChannel(player, "new");
            players.setActiveChannel(player, "new");
            fail.set(true);
            players.removeFromCache(player, true);
            observe.set(true);
            flusher.initialize();
            assertThat(failedTick.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(players.getAllPlayerStates()).isEmpty();
            assertThat(players.getDirtyCount()).isEqualTo(1);

            fail.set(false);
            assertThat(committed.await(5, TimeUnit.SECONDS)).isTrue();
            flusher.close();
            assertThat(players.getAllPlayerStates()).isEmpty();
            assertThat(players.getDirtyCount()).isZero();
            assertThat(provider.loadPlayerState(player).orElseThrow().getActiveChannel()).isEqualTo("new");
        } finally {
            flusher.close();
            provider.shutdown();
        }
    }

    @Test
    void aFailedTickKeepsTheScheduleAndExpiredShutdownInterruptsTheNextTick() throws Exception {
        var running = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var worker = new AtomicReference<Thread>();
        var provider = new MemoryProvider();
        var players = new PlayerStateManager(provider) {
            @Override
            public int saveAllDirty() {
                worker.set(Thread.currentThread());
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("injected flush failure");
                }
                running.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return 0;
            }
        };
        var flusher = new PlayerStateFlushService(players, Duration.ofMillis(10));
        try {
            flusher.initialize();
            flusher.initialize();
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            flusher.shutdown(new ShutdownBudget(Duration.ZERO));
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            worker.get().join(5_000);
            assertThat(worker.get().isAlive()).isFalse();
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            flusher.shutdown(new ShutdownBudget(Duration.ZERO));
            if (worker.get() != null) {
                worker.get().join(5_000);
            }
        }
    }
}
