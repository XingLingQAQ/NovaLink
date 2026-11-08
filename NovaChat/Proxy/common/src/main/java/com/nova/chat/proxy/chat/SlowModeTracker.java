package com.nova.chat.proxy.chat;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-(player, channel) slow-mode gate for lite mode.
 *
 * <p>Mirrors the backend {@code SlowModeTracker} contract used by
 * {@code MessagePipeline} (stage 4c): the first message in a window is allowed,
 * subsequent messages inside {@code slowModeSeconds} are rejected with the
 * remaining wait time. Lite has no backend, so the proxy enforces the same
 * {@code lite.channels.<id>.slow-mode} value locally.
 *
 * <p>Channel admins and the console are exempt on the backend. Lite mode has no
 * channel-admin concept in this phase, so only the configured interval applies.
 *
 * <p>Thread-safe: the map is concurrent and each key is updated with
 * {@link ConcurrentMap#compute}, so two concurrent sends from the same player
 * cannot both win the window.
 */
public final class SlowModeTracker {

    /** How many acquisitions between opportunistic stale-entry sweeps. */
    private static final int CLEANUP_INTERVAL = 256;

    // Retain recent timestamps for interval changes, and longer active windows until expiry.
    private static final long MIN_RETENTION_MS = 60L * 60L * 1000L;

    private record Window(long sentAt, long retainUntil) {}

    private final ConcurrentMap<String, Window> lastMessageAt = new ConcurrentHashMap<>();
    private final AtomicLong acquires = new AtomicLong();
    private final LongSupplier clock;

    public SlowModeTracker() {
        this(System::currentTimeMillis);
    }

    SlowModeTracker(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Attempts to claim the player's send slot in the channel.
     *
     * @param playerId        the sender's UUID (null never throttles)
     * @param channelId       the channel id (null never throttles)
     * @param slowModeSeconds configured interval; {@code <= 0} disables the gate
     * @return {@code 0} when the message may be sent, otherwise the remaining
     *         wait time rounded up to whole seconds
     */
    public long tryAcquire(UUID playerId, String channelId, int slowModeSeconds) {
        if (playerId == null || channelId == null || slowModeSeconds <= 0) {
            return 0L;
        }
        long windowMs = slowModeSeconds * 1000L;
        long now = clock.getAsLong();
        long[] remainingSeconds = new long[1];
        String key = key(playerId, channelId);

        lastMessageAt.compute(key, (ignored, last) -> {
            if (last != null) {
                long elapsed = now - last.sentAt();
                if (elapsed < windowMs) {
                    remainingSeconds[0] = (windowMs - elapsed + 999L) / 1000L;
                    return new Window(last.sentAt(), Math.max(last.retainUntil(), last.sentAt() + windowMs));
                }
            }
            return new Window(now, now + Math.max(MIN_RETENTION_MS, windowMs));
        });

        if (acquires.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            cleanupStale(now);
        }
        return remainingSeconds[0];
    }

    /**
     * Drops the player's window entries (e.g. on disconnect). Safe to call for
     * players that never sent anything.
     *
     * @param playerId the player UUID
     */
    public void forgetPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        String prefix = playerId + "|";
        lastMessageAt.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * @return number of tracked (player, channel) windows
     */
    public int size() {
        return lastMessageAt.size();
    }

    private void cleanupStale(long now) {
        lastMessageAt.entrySet().removeIf(entry -> now > entry.getValue().retainUntil());
    }

    private static String key(UUID playerId, String channelId) {
        return playerId + "|" + channelId;
    }
}
