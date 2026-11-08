package com.nova.chat.common.protocol.keepalive;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Per-connection sliding-window limit for outbound KeepAlive packets. */
public final class KeepAliveBudget {

    public static final long DEFAULT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);
    public static final int DEFAULT_MAX_PER_WINDOW = 2;

    private static final long EMPTY_SLOT = Long.MIN_VALUE;

    private final int maxPerWindow;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final long[] sentAt;
    private int next;

    public KeepAliveBudget() {
        this(DEFAULT_MAX_PER_WINDOW, DEFAULT_WINDOW_NANOS, System::nanoTime);
    }

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
        Arrays.fill(sentAt, EMPTY_SLOT);
    }

    /** Reserves one send; false means the caller must drop the packet. */
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

    public int getMaxPerWindow() {
        return maxPerWindow;
    }
}
