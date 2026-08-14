package com.nova.link.log;

import com.nova.link.database.ChatMessageRecord;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.MessageFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persists chat messages to the database off the message-routing hot path and
 * enforces the retention policy.
 *
 * <p>Writes are funneled through a dedicated single-thread executor with a
 * bounded queue so the {@link com.nova.link.channel.MessagePipeline} never
 * blocks on the database and a stalled backend cannot consume unbounded
 * memory. When full, the newest write is rejected.
 *
 * <p>Retention: an hourly task deletes rows older than
 * {@code features.message-log-retention-days} (0 = keep forever). The value is
 * hot-reloadable via {@link #setRetentionDays(int)}.
 */
public class MessageLogService {

    private static final Logger logger = LoggerFactory.getLogger(MessageLogService.class);

    /** Default number of writes allowed to wait behind the active database call. */
    public static final int DEFAULT_QUEUE_CAPACITY = 4096;

    /** How long shutdown waits for queued writes to flush. */
    private static final long SHUTDOWN_FLUSH_TIMEOUT_SECONDS = 10;

    /** At most one queue-overflow warning is emitted per interval. */
    private static final long REJECTION_WARN_INTERVAL_MILLIS = 30_000;

    private final DatabaseProvider databaseProvider;
    private final ThreadPoolExecutor writeExecutor;
    private final int queueCapacity;
    private final Clock clock;
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong nextRejectionWarnAt = new AtomicLong();
    private final Object shutdownLock = new Object();

    private volatile ScheduledExecutorService cleanupExecutor;
    private volatile ShutdownResult shutdownResult;

    private volatile int retentionDays;

    public MessageLogService(DatabaseProvider databaseProvider, int retentionDays) {
        this(databaseProvider, retentionDays, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * Creates a message log writer with an explicitly bounded backlog.
     *
     * @param databaseProvider persistence backend
     * @param retentionDays days to retain messages
     * @param queueCapacity maximum number of waiting writes
     */
    public MessageLogService(DatabaseProvider databaseProvider, int retentionDays, int queueCapacity) {
        this(databaseProvider, retentionDays, queueCapacity, Clock.systemUTC(),
                createWriteExecutor(queueCapacity));
    }

    /**
     * Injection entry point for deterministic tests. The executor is owned by
     * this service and must be a single-thread executor with an empty bounded
     * queue whose capacity equals {@code queueCapacity}.
     */
    public MessageLogService(DatabaseProvider databaseProvider, int retentionDays,
                             int queueCapacity, Clock clock, ThreadPoolExecutor writeExecutor) {
        this.databaseProvider = Objects.requireNonNull(databaseProvider, "databaseProvider");
        this.retentionDays = Math.max(0, retentionDays);
        this.queueCapacity = requirePositive(queueCapacity, "queueCapacity");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writeExecutor = validateExecutor(
                Objects.requireNonNull(writeExecutor, "writeExecutor"), queueCapacity);
        this.writeExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Starts the hourly retention cleanup task (mirrors the MuteManager
     * cleanup-scheduler pattern).
     */
    public void initialize() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NovaLink-MessageLog-Cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredMessages, 1, 1, TimeUnit.HOURS);
        logger.info("MessageLogService initialized (retentionDays={})", retentionDays);
    }

    /**
     * Queues a message for asynchronous persistence. Never blocks the caller;
     * failures are logged and swallowed so chat routing is unaffected.
     *
     * @param record the message to persist
     */
    public void logAsync(ChatMessageRecord record) {
        if (record == null) {
            return;
        }
        try {
            writeExecutor.execute(() -> {
                try {
                    databaseProvider.saveMessage(record);
                } catch (DatabaseException e) {
                    logger.warn("Failed to persist chat message: {}", e.getMessage());
                } finally {
                    completedCount.incrementAndGet();
                }
            });
            acceptedCount.incrementAndGet();
        } catch (RejectedExecutionException e) {
            rejectedCount.incrementAndGet();
            warnWriteRejected();
        }
    }

    /** @return current number of writes waiting behind the active write */
    public int getQueueDepth() {
        return writeExecutor.getQueue().size();
    }

    /** @return maximum number of writes that may wait */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /** @return total writes accepted by the executor */
    public long getAcceptedCount() {
        return acceptedCount.get();
    }

    /** @return total writes rejected because the queue was full or closed */
    public long getRejectedCount() {
        return rejectedCount.get();
    }

    /** @return total accepted writes whose database call has returned */
    public long getCompletedCount() {
        return completedCount.get();
    }

    /**
     * Searches persisted messages (delegates to the provider).
     *
     * @param filter filter criteria
     * @param offset 0-based row offset
     * @param limit maximum rows
     * @return matching messages, newest first
     * @throws DatabaseException if the query fails
     */
    public List<ChatMessageRecord> search(MessageFilter filter, int offset, int limit) throws DatabaseException {
        return databaseProvider.searchMessages(filter, offset, limit);
    }

    /**
     * Counts persisted messages matching the filter.
     *
     * @param filter filter criteria
     * @return real total for pagination
     * @throws DatabaseException if the query fails
     */
    public int count(MessageFilter filter) throws DatabaseException {
        return databaseProvider.countMessages(filter);
    }

    /**
     * Updates the retention period (hot-reload from config).
     *
     * @param retentionDays days to keep messages; 0 = keep forever
     */
    public void setRetentionDays(int retentionDays) {
        this.retentionDays = Math.max(0, retentionDays);
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    /**
     * Deletes messages older than the retention period. Public for tests and
     * manual triggering; normally invoked by the hourly scheduler.
     *
     * @return number of deleted rows (0 when retention is disabled or on error)
     */
    public int cleanupExpiredMessages() {
        int days = retentionDays;
        if (days <= 0) {
            return 0;
        }
        long cutoff = clock.millis() - TimeUnit.DAYS.toMillis(days);
        try {
            int removed = databaseProvider.cleanupMessagesBefore(cutoff);
            if (removed > 0) {
                logger.info("Message retention cleanup removed {} rows older than {} days", removed, days);
            }
            return removed;
        } catch (DatabaseException e) {
            logger.warn("Message retention cleanup failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Flushes pending writes and stops the executors. Waits up to
     * {@value #SHUTDOWN_FLUSH_TIMEOUT_SECONDS}s for the write queue to drain.
     */
    public void shutdown() {
        shutdown(Duration.ofSeconds(SHUTDOWN_FLUSH_TIMEOUT_SECONDS));
    }

    /**
     * Flushes accepted writes for at most {@code timeout}. If the deadline is
     * exceeded, queued writes are cancelled and the returned report states how
     * many accepted writes were still incomplete.
     *
     * @param timeout maximum drain time
     * @return immutable shutdown outcome; repeated calls return the first outcome
     */
    public ShutdownResult shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }

        synchronized (shutdownLock) {
            if (shutdownResult != null) {
                return shutdownResult;
            }

            ScheduledExecutorService cleanup = cleanupExecutor;
            if (cleanup != null) {
                cleanup.shutdownNow();
            }

            writeExecutor.shutdown();
            boolean terminated = false;
            boolean interrupted = false;
            int cancelledBeforeStart = 0;
            try {
                terminated = writeExecutor.awaitTermination(toNanos(timeout), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }

            if (!terminated) {
                cancelledBeforeStart = writeExecutor.shutdownNow().size();
            }

            long accepted = acceptedCount.get();
            long completed = completedCount.get();
            long incomplete = Math.max(0L, accepted - completed);
            shutdownResult = new ShutdownResult(
                    !terminated, interrupted, accepted, completed, incomplete, cancelledBeforeStart);

            if (!terminated) {
                logger.warn(
                        "Message log shutdown deadline reached: {} accepted writes incomplete "
                                + "({} cancelled before start)",
                        incomplete, cancelledBeforeStart);
            } else {
                logger.info("MessageLogService shutdown (accepted={}, completed={})",
                        accepted, completed);
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return shutdownResult;
        }
    }

    /** @return the first shutdown report, or {@code null} before shutdown */
    public ShutdownResult getShutdownResult() {
        return shutdownResult;
    }

    private void warnWriteRejected() {
        long now = clock.millis();
        long next = nextRejectionWarnAt.get();
        if (now < next || !nextRejectionWarnAt.compareAndSet(next, saturatingAdd(
                now, REJECTION_WARN_INTERVAL_MILLIS))) {
            return;
        }
        logger.warn("Message log queue full or closed; dropping newest write "
                        + "(capacity={}, rejectedTotal={})",
                queueCapacity, rejectedCount.get());
    }

    private static ThreadPoolExecutor createWriteExecutor(int queueCapacity) {
        int capacity = requirePositive(queueCapacity, "queueCapacity");
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                daemonThreadFactory("NovaLink-MessageLog-Writer"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadPoolExecutor validateExecutor(
            ThreadPoolExecutor executor, int queueCapacity) {
        if (executor.getCorePoolSize() != 1 || executor.getMaximumPoolSize() != 1) {
            throw new IllegalArgumentException("writeExecutor must use exactly one worker");
        }
        if (!executor.getQueue().isEmpty()
                || executor.getQueue().remainingCapacity() != queueCapacity) {
            throw new IllegalArgumentException(
                    "writeExecutor must have an empty bounded queue matching queueCapacity");
        }
        return executor;
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long toNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(long value, long increment) {
        if (value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    /**
     * Immutable report for bounded shutdown. Counts are snapshots taken after
     * the drain deadline (and cancellation when required).
     */
    public static final class ShutdownResult {
        private final boolean timedOut;
        private final boolean interrupted;
        private final long acceptedCount;
        private final long completedCount;
        private final long incompleteCount;
        private final int cancelledBeforeStartCount;

        private ShutdownResult(boolean timedOut, boolean interrupted,
                               long acceptedCount, long completedCount,
                               long incompleteCount, int cancelledBeforeStartCount) {
            this.timedOut = timedOut;
            this.interrupted = interrupted;
            this.acceptedCount = acceptedCount;
            this.completedCount = completedCount;
            this.incompleteCount = incompleteCount;
            this.cancelledBeforeStartCount = cancelledBeforeStartCount;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public boolean isInterrupted() {
            return interrupted;
        }

        public long getAcceptedCount() {
            return acceptedCount;
        }

        public long getCompletedCount() {
            return completedCount;
        }

        public long getIncompleteCount() {
            return incompleteCount;
        }

        public int getCancelledBeforeStartCount() {
            return cancelledBeforeStartCount;
        }
    }
}
