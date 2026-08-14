package com.nova.link.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary + concurrency tests for the per-connection token bucket
 * {@link RateLimiter}.
 */
@DisplayName("RateLimiter token bucket")
class RateLimiterTest {

    private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

    @Test
    @DisplayName("burst capacity allows exactly `burst` immediate acquires")
    void burstBoundary() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(10, 20, clock::get);

        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("conn")).as("token %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire("conn")).isFalse();
    }

    @Test
    @DisplayName("tokens refill at messages-per-second rate")
    void refill() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(10, 20, clock::get);

        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire("conn");
        }
        assertThat(limiter.tryAcquire("conn")).isFalse();

        // +100ms → 1 token refilled.
        clock.addAndGet(SECOND_NANOS / 10);
        assertThat(limiter.tryAcquire("conn")).isTrue();
        assertThat(limiter.tryAcquire("conn")).isFalse();

        // +1s → 10 tokens.
        clock.addAndGet(SECOND_NANOS);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("conn")).as("refilled token %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire("conn")).isFalse();
    }

    @Test
    @DisplayName("refill never exceeds burst capacity")
    void refillCappedAtBurst() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(10, 5, clock::get);

        // Long idle: bucket must cap at burst=5, not accumulate 100 tokens.
        clock.addAndGet(10 * SECOND_NANOS);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("conn")).isTrue();
        }
        assertThat(limiter.tryAcquire("conn")).isFalse();
    }

    @Test
    @DisplayName("rate 0 disables limiting entirely")
    void disabled() {
        RateLimiter limiter = new RateLimiter(0, 20);
        assertThat(limiter.isEnabled()).isFalse();
        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire("conn")).isTrue();
        }
        assertThat(limiter.trackedConnections()).isZero();
    }

    @Test
    @DisplayName("buckets are independent per connection")
    void perConnectionIsolation() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(10, 2, clock::get);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();

        // Connection "b" has its own full bucket.
        assertThat(limiter.tryAcquire("b")).isTrue();
        assertThat(limiter.tryAcquire("b")).isTrue();
    }

    @Test
    @DisplayName("shouldNotify throttles to once per 5s per connection")
    void notifyThrottle() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(10, 20, clock::get);

        assertThat(limiter.shouldNotify("conn")).isTrue();
        assertThat(limiter.shouldNotify("conn")).isFalse();

        clock.addAndGet(4 * SECOND_NANOS);
        assertThat(limiter.shouldNotify("conn")).isFalse();

        clock.addAndGet(SECOND_NANOS);
        assertThat(limiter.shouldNotify("conn")).isTrue();
        assertThat(limiter.shouldNotify("conn")).isFalse();

        // Different connection has its own notify window.
        assertThat(limiter.shouldNotify("other")).isTrue();
    }

    @Test
    @DisplayName("remove() drops bucket state so the map stays bounded")
    void removeCleansUp() {
        RateLimiter limiter = new RateLimiter(10, 20);
        limiter.tryAcquire("conn");
        assertThat(limiter.trackedConnections()).isEqualTo(1);

        limiter.remove("conn");
        assertThat(limiter.trackedConnections()).isZero();
    }

    @Test
    @DisplayName("concurrent acquires on a frozen clock grant exactly burst tokens")
    void concurrentAcquires() throws Exception {
        // Frozen clock: no refill, so total grants must equal burst exactly.
        RateLimiter limiter = new RateLimiter(10, 100, () -> 0L);

        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.tryAcquire("conn")) {
                            granted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(granted.get()).isEqualTo(100);
    }
}
