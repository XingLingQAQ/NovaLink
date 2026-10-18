package com.nova.link.network;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Per-connection rate guard for outbound KeepAlive packets.
 *
 * <p>A healthy connection emits at most one KeepAlive per idle interval
 * (the server pings every {@code idle-timeout-seconds / 3}, i.e. >= 1s), so
 * more than a couple of packets inside one second means either a loop or a
 * misbehaving peer. Once the budget is spent, further sends are dropped and
 * the caller is expected to warn (throttled) instead of writing more traffic —
 * rejecting the packet is what stops the loop from turning into a flood.</p>
 *
 * <p>Sliding window over {@code windowNanos}. Thread-safe: the window is
 * mutated under the instance monitor.</p>
 */
public final class KeepAliveBudget {

    /** Default window length: one second. */
    public static final long DEFAULT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** Default number of KeepAlives allowed per window. */
    public static final int DEFAULT_MAX_PER_WINDOW = 2;

    /** Marks a slot that has never been used; 0 would look "just sent". */
    private static final long EMPTY_SLOT = Long.MIN_VALUE;

    private final int maxPerWindow;
    private final long windowNanos;
    private final LongSupplier nanoClock;

    /** Start timestamps (nanos) of the sends still inside the window. */
    private final long[] sentAt;
    private int next;

    public KeepAliveBudget() {
        this(DEFAULT_MAX_PER_WINDOW, DEFAULT_WINDOW_NANOS, System::nanoTime);
    }

    /**
     * @param maxPerWindow maximum sends allowed inside the window
     * @param windowNanos  window length; must be positive
     * @param nanoClock    monotonic clock (injectable for tests)
     */
    public KeepAliveBudget(int maxPerWindow, long windowNanos, LongSupplier nanoClock) {
        if (maxPerWindow < 1) {
            throw new IllegalArgumentException("maxPerWindow must be >= 1, got " + maxPerWindow);
        }
        if (windowNanos <= 0) {
            throw new IllegalArgumentException("windowNanos must be > 0, got " + windowNanos);
        }
        this.maxPerWindow = maxPerWindow;
        this.windowNanos = windowNanos;
        this.nanoClock = nanoClock;
        this.sentAt = new long[maxPerWindow];
        java.util.Arrays.fill(sentAt, EMPTY_SLOT);
    }

    /**
     * Attempts to reserve one send slot.
     *
     * @return true when the caller may send; false when the window is full and
     *         the packet must be dropped
     */
    public synchronized boolean tryAcquire() {
        long now = nanoClock.getAsLong();
        int inWindow = 0;
        for (long ts : sentAt) {
            if (ts != EMPTY_SLOT && now - ts < windowNanos) {
                inWindow++;
            }
        }
        if (inWindow >= maxPerWindow) {
            return false;
        }
        sentAt[next] = now;
        next = (next + 1) % sentAt.length;
        return true;
    }

    /**
     * @return the configured per-window cap
     */
    public int getMaxPerWindow() {
        return maxPerWindow;
    }
}
