package com.nova.link.database;

import com.nova.link.lifecycle.ShutdownBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class PlayerStateFlushService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PlayerStateFlushService.class);
    private static final Duration STOP_WAIT = Duration.ofSeconds(5);

    private final PlayerStateManager players;
    private final ScheduledThreadPoolExecutor executor;
    private final long intervalNanos;
    private boolean started;

    public PlayerStateFlushService(PlayerStateManager players) {
        this(players, Duration.ofSeconds(30));
    }

    PlayerStateFlushService(PlayerStateManager players, Duration interval) {
        this.players = Objects.requireNonNull(players, "players");
        intervalNanos = Objects.requireNonNull(interval, "interval").toNanos();
        if (intervalNanos <= 0) {
            throw new IllegalArgumentException("interval must be positive");
        }
        executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "NovaLink-PlayerState-Flush");
            thread.setDaemon(true);
            return thread;
        });
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public synchronized void initialize() {
        if (started) {
            return;
        }
        executor.scheduleWithFixedDelay(this::flush, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
        started = true;
    }

    private void flush() {
        try {
            players.saveAllDirty();
        } catch (RuntimeException e) {
            logger.warn("Player state flush failed; the next scheduled flush will retry", e);
        }
    }

    public void shutdown(ShutdownBudget budget) {
        budget.stop(executor, STOP_WAIT);
    }

    @Override
    public void close() {
        shutdown(new ShutdownBudget(STOP_WAIT));
    }
}
