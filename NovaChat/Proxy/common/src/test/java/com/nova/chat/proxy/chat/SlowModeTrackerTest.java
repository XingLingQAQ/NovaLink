package com.nova.chat.proxy.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SlowModeTrackerTest {

    @Test
    void cleanupRetainsAWindowLongerThanOneHour() {
        var clock = new AtomicLong(1_000L);
        var tracker = new SlowModeTracker(clock::get);
        UUID player = UUID.randomUUID();
        assertThat(tracker.tryAcquire(player, "slow", 7200)).isZero();

        clock.addAndGet(TimeUnit.HOURS.toMillis(1) + 1_000L);
        triggerCleanup(tracker);

        assertThat(tracker.tryAcquire(player, "slow", 7200)).isEqualTo(3599L);
        clock.addAndGet(TimeUnit.SECONDS.toMillis(3599));
        assertThat(tracker.tryAcquire(player, "slow", 7200)).isZero();
    }

    @Test
    void cleanupDropsAnExpiredLongWindow() {
        var clock = new AtomicLong(1_000L);
        var tracker = new SlowModeTracker(clock::get);
        UUID player = UUID.randomUUID();
        tracker.tryAcquire(player, "expired", 7200);

        clock.addAndGet(TimeUnit.HOURS.toMillis(2) + 1_000L);
        triggerCleanup(tracker);

        assertThat(tracker.size()).isEqualTo(1);
        assertThat(tracker.tryAcquire(player, "expired", 7200)).isZero();
    }

    @Test
    void changedIntervalsStillApplyFromTheLastAcceptedMessage() {
        var clock = new AtomicLong(1_000L);
        var tracker = new SlowModeTracker(clock::get);
        UUID player = UUID.randomUUID();
        assertThat(tracker.tryAcquire(player, "channel", 30)).isZero();
        clock.addAndGet(10_000L);
        assertThat(tracker.tryAcquire(player, "channel", 20)).isEqualTo(10L);
        assertThat(tracker.tryAcquire(player, "channel", 5)).isZero();
        assertThat(tracker.tryAcquire(player, "channel", 0)).isZero();
        tracker.forgetPlayer(player);
        assertThat(tracker.size()).isZero();
    }

    private static void triggerCleanup(SlowModeTracker tracker) {
        UUID other = UUID.randomUUID();
        for (int i = 0; i < 256; i++) {
            tracker.tryAcquire(other, "cleanup", 1);
        }
    }
}
