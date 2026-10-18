package com.nova.chat.client.network;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Bounded, time-limited memory of KeepAlive requestIds this client sent.
 *
 * <p>NovaProtocol KeepAlives carry no direction bit, so the only reliable way
 * to tell "a peer KeepAlive to answer" from "the echo of a KeepAlive we wrote"
 * is to remember what we wrote. A reply arrives within one round trip, so the
 * last few ids (expiring after a minute) are enough; an id that has aged out
 * cannot be replayed into an answer.</p>
 *
 * <p><strong>Value equality matters:</strong> {@code Packet.decode()} rebuilds
 * the requestId with {@code new UUID(...)}, so identity comparison can never
 * match an inbound id against a stored one (that exact mistake caused the
 * backend KeepAlive echo loop). {@link UUID#equals(Object)} is value-based and
 * is the only comparison used here.</p>
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
     * @param capacity  maximum ids retained (oldest evicted first)
     * @param ttlNanos  how long an id stays recognizable; must be positive
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
     * @return true when the id was sent by this client and has not expired
     */
    public synchronized boolean contains(UUID requestId) {
        if (requestId == null) {
            return false;
        }
        evictExpired(nanoClock.getAsLong());
        for (Entry entry : entries) {
            // UUID.equals is value-based; never replace with == (see class doc).
            if (requestId.equals(entry.requestId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forgets every remembered id, e.g. when a new connection generation
     * starts and ids from the previous socket can no longer arrive.
     */
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
