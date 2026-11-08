package com.nova.link.network;

import java.util.function.LongSupplier;

/** Keeps the original network API while sharing the protocol send budget. */
public final class KeepAliveBudget {

    public static final long DEFAULT_WINDOW_NANOS =
            com.nova.chat.common.protocol.keepalive.KeepAliveBudget.DEFAULT_WINDOW_NANOS;
    public static final int DEFAULT_MAX_PER_WINDOW =
            com.nova.chat.common.protocol.keepalive.KeepAliveBudget.DEFAULT_MAX_PER_WINDOW;

    private final com.nova.chat.common.protocol.keepalive.KeepAliveBudget delegate;

    public KeepAliveBudget() {
        this(DEFAULT_MAX_PER_WINDOW, DEFAULT_WINDOW_NANOS, System::nanoTime);
    }

    public KeepAliveBudget(int maxPerWindow, long windowNanos, LongSupplier nanoClock) {
        delegate = new com.nova.chat.common.protocol.keepalive.KeepAliveBudget(maxPerWindow, windowNanos, nanoClock);
    }

    public synchronized boolean tryAcquire() {
        return delegate.tryAcquire();
    }

    public int getMaxPerWindow() {
        return delegate.getMaxPerWindow();
    }
}
