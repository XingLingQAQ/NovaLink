package com.nova.link.network;

import java.util.UUID;
import java.util.function.LongSupplier;

/** Keeps the original network API while sharing the protocol request ID history. */
public final class RecentKeepAlives {

    private final com.nova.chat.common.protocol.keepalive.RecentKeepAlives delegate;

    public RecentKeepAlives(int capacity, long ttlNanos) {
        this(capacity, ttlNanos, System::nanoTime);
    }

    public RecentKeepAlives(int capacity, long ttlNanos, LongSupplier nanoClock) {
        delegate = new com.nova.chat.common.protocol.keepalive.RecentKeepAlives(capacity, ttlNanos, nanoClock);
    }

    public synchronized void remember(UUID requestId) {
        delegate.remember(requestId);
    }

    public synchronized boolean contains(UUID requestId) {
        return delegate.contains(requestId);
    }
}
