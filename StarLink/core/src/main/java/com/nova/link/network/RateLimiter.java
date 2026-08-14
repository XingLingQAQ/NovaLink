package com.nova.link.network;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Per-connection token-bucket rate limiter for inbound message packets
 * (ChatMessage / ItemDisplay share one bucket per connection).
 *
 * <p>Each key (connection id) owns a bucket of {@code burst} capacity refilled
 * at {@code messagesPerSecond} tokens per second. {@code messagesPerSecond == 0}
 * disables limiting entirely ({@link #tryAcquire} always grants).</p>
 *
 * <p>Thread-safe: bucket state is mutated under the bucket's own monitor;
 * bucket creation uses {@link ConcurrentHashMap#computeIfAbsent}. Buckets are
 * removed via {@link #remove} when a connection closes, so the map is bounded
 * by the number of live connections.</p>
 */
public class RateLimiter {

    /** Minimum interval between "rate limited" notifications per connection. */
    private static final long NOTIFY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final int messagesPerSecond;
    private final int burst;
    private final LongSupplier nanoClock;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastNotifiedAt = new ConcurrentHashMap<>();

    public RateLimiter(int messagesPerSecond, int burst) {
        this(messagesPerSecond, burst, System::nanoTime);
    }

    /**
     * @param messagesPerSecond sustained refill rate; {@code 0} disables limiting
     * @param burst             bucket capacity (clamped to at least 1 when enabled)
     * @param nanoClock         nanosecond clock source (injectable for tests)
     */
    public RateLimiter(int messagesPerSecond, int burst, LongSupplier nanoClock) {
        this.messagesPerSecond = Math.max(0, messagesPerSecond);
        this.burst = Math.max(1, burst);
        this.nanoClock = nanoClock;
    }

    /**
     * @return true when rate limiting is active (rate &gt; 0)
     */
    public boolean isEnabled() {
        return messagesPerSecond > 0;
    }

    /**
     * Attempts to consume one token from the given connection's bucket.
     *
     * @param key connection identifier
     * @return true if the message is allowed; false if it must be dropped
     */
    public boolean tryAcquire(String key) {
        if (!isEnabled() || key == null) {
            return true;
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(nanoClock.getAsLong(), burst));
        synchronized (bucket) {
            long now = nanoClock.getAsLong();
            long elapsed = now - bucket.lastRefillNanos;
            if (elapsed > 0) {
                double refill = elapsed / 1_000_000_000.0 * messagesPerSecond;
                bucket.tokens = Math.min(burst, bucket.tokens + refill);
                bucket.lastRefillNanos = now;
            }
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /**
     * Throttled notification gate: returns true at most once per 5 seconds per
     * connection. Use to decide whether to send the "rate limited" error
     * response / WARN log for a rejected message.
     *
     * @param key connection identifier
     * @return true when the caller should emit a notification now
     */
    public boolean shouldNotify(String key) {
        if (key == null) {
            return false;
        }
        long now = nanoClock.getAsLong();
        boolean[] notify = new boolean[1];
        lastNotifiedAt.compute(key, (k, prev) -> {
            if (prev == null || now - prev >= NOTIFY_INTERVAL_NANOS) {
                notify[0] = true;
                return now;
            }
            return prev;
        });
        return notify[0];
    }

    /**
     * Drops all state for a closed connection.
     *
     * @param key connection identifier
     */
    public void remove(String key) {
        if (key != null) {
            buckets.remove(key);
            lastNotifiedAt.remove(key);
        }
    }

    /** Visible for tests. */
    int trackedConnections() {
        return buckets.size();
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(long nowNanos, int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = nowNanos;
        }
    }
}
