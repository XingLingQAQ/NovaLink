package com.nova.chat.client.network;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Thread-safe tracker mapping request IDs to response futures with timeout cleanup.
 *
 * <p>Platform-agnostic: callers schedule timeouts / cleanup on their own executor
 * or event loop. This class only stores futures and expires them based on wall-clock
 * time supplied by a {@link LongSupplier} (defaults to {@link System#currentTimeMillis()}).
 *
 * @param <T> response payload type carried by the future
 */
public final class PendingRequestTracker<T> {

    private final ConcurrentHashMap<UUID, Entry<T>> pending = new ConcurrentHashMap<>();
    private final long defaultTimeoutMs;
    private final LongSupplier clock;

    /**
     * Creates a tracker with the given default timeout.
     *
     * @param defaultTimeoutMs default TTL for tracked requests; must be &gt; 0
     */
    public PendingRequestTracker(long defaultTimeoutMs) {
        this(defaultTimeoutMs, System::currentTimeMillis);
    }

    /**
     * Creates a tracker with an injectable clock (for tests).
     *
     * @param defaultTimeoutMs default TTL for tracked requests; must be &gt; 0
     * @param clock wall-clock supplier in milliseconds
     */
    public PendingRequestTracker(long defaultTimeoutMs, LongSupplier clock) {
        if (defaultTimeoutMs <= 0L) {
            throw new IllegalArgumentException("defaultTimeoutMs must be > 0");
        }
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers a new pending request and returns its future.
     * If an entry already exists for {@code requestId}, it is replaced and the
     * previous future is completed exceptionally.
     *
     * @param requestId unique request identifier
     * @return a new incomplete future that will be completed on
     *         {@link #complete(UUID, Object)}, {@link #fail(UUID, Throwable)},
     *         or timeout cleanup
     */
    public CompletableFuture<T> track(UUID requestId) {
        return track(requestId, defaultTimeoutMs);
    }

    /**
     * Registers a new pending request with a custom timeout.
     */
    public CompletableFuture<T> track(UUID requestId, long timeoutMs) {
        Objects.requireNonNull(requestId, "requestId");
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("timeoutMs must be > 0");
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        long now = clock.getAsLong();
        Entry<T> previous = pending.put(requestId, new Entry<>(future, now, timeoutMs));
        if (previous != null && !previous.future.isDone()) {
            previous.future.completeExceptionally(
                    new IllegalStateException("Request " + requestId + " was superseded"));
        }
        return future;
    }

    /**
     * Completes the future for {@code requestId} with {@code result}.
     *
     * @return {@code true} if a pending entry was found and completed
     */
    public boolean complete(UUID requestId, T result) {
        Objects.requireNonNull(requestId, "requestId");
        Entry<T> entry = pending.remove(requestId);
        if (entry == null) {
            return false;
        }
        return entry.future.complete(result);
    }

    /**
     * Completes the future for {@code requestId} exceptionally.
     *
     * @return {@code true} if a pending entry was found and completed exceptionally
     */
    public boolean fail(UUID requestId, Throwable cause) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(cause, "cause");
        Entry<T> entry = pending.remove(requestId);
        if (entry == null) {
            return false;
        }
        return entry.future.completeExceptionally(cause);
    }

    /**
     * Returns the future for a request without removing it, if still pending.
     */
    public Optional<CompletableFuture<T>> get(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        Entry<T> entry = pending.get(requestId);
        return entry == null ? Optional.empty() : Optional.of(entry.future);
    }

    /**
     * @return {@code true} if a non-expired entry exists for {@code requestId}
     */
    public boolean isPending(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        Entry<T> entry = pending.get(requestId);
        if (entry == null) {
            return false;
        }
        return !entry.isExpired(clock.getAsLong());
    }

    /**
     * Removes and fails all entries whose age exceeds their timeout.
     *
     * @return number of entries cleaned up
     */
    public int cleanupExpired() {
        long now = clock.getAsLong();
        AtomicLong removed = new AtomicLong();
        pending.entrySet().removeIf(mapEntry -> {
            Entry<T> entry = mapEntry.getValue();
            if (!entry.isExpired(now)) {
                return false;
            }
            if (!entry.future.isDone()) {
                entry.future.completeExceptionally(
                        new TimeoutException(
                                "Request " + mapEntry.getKey() + " timed out after "
                                        + entry.timeoutMs + "ms"));
            }
            removed.incrementAndGet();
            return true;
        });
        return (int) removed.get();
    }

    /**
     * Cancels and removes every pending request.
     *
     * @param cause exception used to complete remaining futures
     * @return number of entries cleared
     */
    public int clearAll(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        int count = 0;
        for (Map.Entry<UUID, Entry<T>> mapEntry : pending.entrySet()) {
            Entry<T> entry = mapEntry.getValue();
            if (!entry.future.isDone()) {
                entry.future.completeExceptionally(cause);
            }
            count++;
        }
        pending.clear();
        return count;
    }

    /**
     * Cancels and removes every pending request with a generic cancellation cause.
     */
    public int clearAll() {
        return clearAll(new IllegalStateException("Pending requests cleared"));
    }

    public int size() {
        return pending.size();
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    private static final class Entry<T> {
        private final CompletableFuture<T> future;
        private final long createdAtMs;
        private final long timeoutMs;

        private Entry(CompletableFuture<T> future, long createdAtMs, long timeoutMs) {
            this.future = future;
            this.createdAtMs = createdAtMs;
            this.timeoutMs = timeoutMs;
        }

        private boolean isExpired(long nowMs) {
            return nowMs - createdAtMs > timeoutMs;
        }
    }
}
