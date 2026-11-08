package com.nova.link.lifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** A shared deadline for graceful waits; expiry still requires cancellation. */
public final class ShutdownBudget {
    private final long deadline;

    public ShutdownBudget(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        deadline = System.nanoTime() + timeout.toNanos();
    }

    private ShutdownBudget(long deadline) {
        this.deadline = deadline;
    }

    public static ShutdownBudget until(long deadline) {
        return new ShutdownBudget(deadline);
    }

    public long remainingNanos() {
        return Math.max(0L, deadline - System.nanoTime());
    }

    public Duration remaining() {
        return Duration.ofNanos(remainingNanos());
    }

    public List<Runnable> stop(ExecutorService executor) {
        return stop(executor, remaining());
    }

    public List<Runnable> stop(ExecutorService executor, Duration maximumWait) {
        executor.shutdown();
        try {
            if (executor.awaitTermination(Math.min(remainingNanos(), maximumWait.toNanos()), TimeUnit.NANOSECONDS)) {
                return List.of();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return executor.shutdownNow();
    }
}
