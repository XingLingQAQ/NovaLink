package com.nova.chat.common.protocol.keepalive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded request ID history for distinguishing peer pings from our own echoes. */
public final class RecentKeepAlives {

    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final Deque<Entry> entries = new ArrayDeque<>();

    private record Entry(UUID requestId, long sentAtNanos) {}

    public RecentKeepAlives(int capacity, long ttlNanos) {
        this(capacity, ttlNanos, System::nanoTime);
    }

    public RecentKeepAlives(int capacity, long ttlNanos, LongSupplier nanoClock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        if (ttlNanos <= 0) {
            throw new IllegalArgumentException("ttlNanos must be > 0, got " + ttlNanos);
        }
        this.capacity = capacity;
        this.ttlNanos = ttlNanos;
        this.nanoClock = nanoClock;
    }

    /** Records an outgoing request ID; null is ignored. */
    public synchronized void remember(UUID requestId) {
        if (requestId == null) {
            return;
        }
        long now = nanoClock.getAsLong();
        evictExpired(now);
        while (entries.size() >= capacity) {
            entries.removeFirst();
        }
        entries.addLast(new Entry(requestId, now));
    }

    /** Matches UUID values; decoded packets rebuild UUID objects. */
    public synchronized boolean contains(UUID requestId) {
        if (requestId == null) {
            return false;
        }
        evictExpired(nanoClock.getAsLong());
        for (Entry entry : entries) {
            if (requestId.equals(entry.requestId())) {
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        entries.clear();
    }

    private void evictExpired(long now) {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            if (now - it.next().sentAtNanos() >= ttlNanos) {
                it.remove();
            }
        }
    }
}
