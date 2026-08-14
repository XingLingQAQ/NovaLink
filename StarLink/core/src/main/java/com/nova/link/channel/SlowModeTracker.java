package com.nova.link.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Tracks per-player-per-channel message timestamps for channel slow mode.
 *
 * <p>The map is bounded in practice: entries expire as soon as the slow-mode
 * window elapses and a minute-interval cleanup task (same pattern as
 * {@link com.nova.link.mute.MuteManager}) removes stale entries so the map
 * never grows beyond the set of players actively inside a slow-mode window.</p>
 *
 * <p>Thread-safe: state transitions run inside {@link ConcurrentHashMap#compute}.</p>
 */
public class SlowModeTracker {

    private static final Logger logger = LoggerFactory.getLogger(SlowModeTracker.class);

    private static final long CLEANUP_INTERVAL_MINUTES = 1;

    /** key: channelId + '\0' + playerId → epoch millis when the next message is allowed. */
    private final ConcurrentHashMap<String, Long> nextAllowedAt = new ConcurrentHashMap<>();

    private final LongSupplier clock;

    private ScheduledExecutorService cleanupExecutor;

    public SlowModeTracker() {
        this(System::currentTimeMillis);
    }

    /**
     * @param clock millisecond clock source (injectable for tests)
     */
    public SlowModeTracker(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Attempts to record a message from the given player in the given channel.
     *
     * @param playerId        the sender
     * @param channelId       the channel
     * @param slowModeSeconds the channel's slow-mode window (must be &gt; 0)
     * @return {@code 0} if the message is allowed (the window is restarted);
     *         otherwise the remaining wait time in seconds (rounded up, &gt;= 1)
     */
    public long tryAcquire(UUID playerId, String channelId, int slowModeSeconds) {
        if (slowModeSeconds <= 0 || playerId == null || channelId == null) {
            return 0;
        }
        long now = clock.getAsLong();
        long[] remainingMs = {0};
        nextAllowedAt.compute(key(playerId, channelId), (k, prev) -> {
            if (prev != null && prev > now) {
                remainingMs[0] = prev - now;
                return prev;
            }
            return now + slowModeSeconds * 1000L;
        });
        return remainingMs[0] == 0 ? 0 : (remainingMs[0] + 999) / 1000;
    }

    /**
     * Starts the periodic cleanup task that evicts expired entries.
     */
    public synchronized void startCleanupTask() {
        if (cleanupExecutor != null) {
            return;
        }
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NovaLink-SlowModeCleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired,
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        logger.debug("Slow-mode cleanup task started (interval: {} minute)", CLEANUP_INTERVAL_MINUTES);
    }

    /**
     * Removes all entries whose slow-mode window already elapsed.
     * Visible for tests.
     */
    void cleanupExpired() {
        long now = clock.getAsLong();
        nextAllowedAt.entrySet().removeIf(e -> e.getValue() <= now);
    }

    /** Visible for tests. */
    int size() {
        return nextAllowedAt.size();
    }

    public synchronized void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
            cleanupExecutor = null;
        }
        nextAllowedAt.clear();
    }

    private static String key(UUID playerId, String channelId) {
        return channelId + '\0' + playerId;
    }
}
