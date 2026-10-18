package com.nova.link.network;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Bounded, time-limited memory of KeepAlive requestIds this connection sent.
 *
 * <p>NovaProtocol KeepAlives carry no direction bit, so the only reliable way
 * to tell "the client is pinging us" from "the client is echoing our ping" is
 * to remember what we sent. Keeping the last few ids (and expiring them after
 * a minute) is enough: a reply arrives within one round trip, and a stale id
 * cannot be replayed into an answer once it has aged out.</p>
 *
 * <p>Thread-safe: all state is mutated under the instance monitor.</p>
 */
public final class RecentKeepAlives {

    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final Deque<Entry> entries = new ArrayDeque<>();

    private record Entry(UUID requestId, long sentAtNanos) {
    }

    /**
     * @param capacity maximum ids retained (oldest evicted first)
     * @param ttlNanos how long an id stays recognizable; must be positive
     */
    public RecentKeepAlives(int capacity, long ttlNanos) {
        this(capacity, ttlNanos, System::nanoTime);
    }

    /**
     * @param capacity maximum ids retained (oldest evicted first)
     * @param ttlNanos how long an id stays recognizable; must be positive
     * @param nanoClock monotonic clock (injectable for tests)
     */
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

    /**
     * Records an outgoing KeepAlive requestId.
     *
     * @param requestId the id just written; null is ignored
     */
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

    /**
     * @param requestId an inbound requestId; null never matches
     * @return true when the id was sent by this connection and has not expired
     */
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

    private void evictExpired(long now) {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            if (now - it.next().sentAtNanos() >= ttlNanos) {
                it.remove();
            }
        }
    }
}
