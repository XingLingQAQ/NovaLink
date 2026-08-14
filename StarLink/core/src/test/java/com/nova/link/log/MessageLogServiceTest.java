package com.nova.link.log;

import com.nova.link.database.ChatMessageRecord;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.MessageFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@link MessageLogService} behavior: asynchronous writes with shutdown flush,
 * and the retention-days cleanup policy (0 = keep forever).
 */
@DisplayName("MessageLogService async persistence + retention")
class MessageLogServiceTest {

    private TestMemoryProvider provider;
    private MessageLogService service;

    @BeforeEach
    void setUp() throws DatabaseException {
        provider = new TestMemoryProvider();
        provider.initialize();
        service = new MessageLogService(provider, 30);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        provider.shutdown();
    }

    private static ChatMessageRecord record(String content, long timestamp) {
        return new ChatMessageRecord("global", null, "Steve", "Survival", content, timestamp);
    }

    @Test
    @DisplayName("logAsync persists off-thread; shutdown flushes the queue")
    void logAsyncPersistsAndShutdownFlushes() throws Exception {
        for (int i = 0; i < 50; i++) {
            service.logAsync(record("m" + i, 1000 + i));
        }
        // shutdown() must drain everything that was queued before returning.
        MessageLogService.ShutdownResult result = service.shutdown(Duration.ofSeconds(2));

        assertThat(provider.countMessages(MessageFilter.any())).isEqualTo(50);
        assertThat(result.isTimedOut()).isFalse();
        assertThat(result.getAcceptedCount()).isEqualTo(50);
        assertThat(result.getCompletedCount()).isEqualTo(50);
        assertThat(result.getIncompleteCount()).isZero();
    }

    @Test
    @DisplayName("logAsync ignores null and never throws after shutdown")
    void logAsyncIsSafe() {
        service.logAsync(null);
        service.shutdown();
        // Post-shutdown submissions are dropped silently, not thrown.
        service.logAsync(record("late", 1L));
    }

    @Test
    @DisplayName("cleanup removes rows older than retentionDays and keeps newer ones")
    void cleanupHonorsRetentionDays() throws Exception {
        long now = System.currentTimeMillis();
        long expired = now - TimeUnit.DAYS.toMillis(31);
        long fresh = now - TimeUnit.DAYS.toMillis(1);
        provider.saveMessage(record("old", expired));
        provider.saveMessage(record("new", fresh));

        int removed = service.cleanupExpiredMessages();

        assertThat(removed).isEqualTo(1);
        assertThat(provider.countMessages(MessageFilter.any())).isEqualTo(1);
        assertThat(provider.searchMessages(MessageFilter.any(), 0, 10).get(0).getContent())
                .isEqualTo("new");
    }

    @Test
    @DisplayName("retentionDays=0 disables cleanup entirely")
    void zeroRetentionKeepsForever() throws Exception {
        service.setRetentionDays(0);
        provider.saveMessage(record("ancient", 1L));

        assertThat(service.cleanupExpiredMessages()).isZero();
        assertThat(provider.countMessages(MessageFilter.any())).isEqualTo(1);
    }

    @Test
    @DisplayName("setRetentionDays hot-applies (negative values clamp to 0)")
    void retentionDaysHotReload() {
        service.setRetentionDays(7);
        assertThat(service.getRetentionDays()).isEqualTo(7);
        service.setRetentionDays(-5);
        assertThat(service.getRetentionDays()).isZero();
    }

    @Test
    @DisplayName("blocked database cannot grow the queue and timed-out shutdown reports accepted work")
    void blockedDatabaseUsesBoundedQueueAndReportsShutdownTimeout() throws Exception {
        service.shutdown();
        PermanentlyBlockingProvider blockedProvider = new PermanentlyBlockingProvider();
        MessageLogService blockedService = new MessageLogService(blockedProvider, 30, 2);
        service = blockedService;

        try {
            blockedService.logAsync(record("active", 1));
            assertThat(blockedProvider.started.await(2, TimeUnit.SECONDS)).isTrue();
            blockedService.logAsync(record("queued-1", 2));
            blockedService.logAsync(record("queued-2", 3));

            assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
                for (int i = 0; i < 100; i++) {
                    blockedService.logAsync(record("rejected-" + i, 4 + i));
                }
            });

            assertThat(blockedService.getQueueCapacity()).isEqualTo(2);
            assertThat(blockedService.getQueueDepth()).isEqualTo(2);
            assertThat(blockedService.getAcceptedCount()).isEqualTo(3);
            assertThat(blockedService.getRejectedCount()).isEqualTo(100);
            assertThat(blockedService.getCompletedCount()).isZero();

            long startedAt = System.nanoTime();
            MessageLogService.ShutdownResult result =
                    blockedService.shutdown(Duration.ofMillis(100));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(elapsedMillis).isLessThan(1_000);
            assertThat(result.isTimedOut()).isTrue();
            assertThat(result.getAcceptedCount()).isEqualTo(3);
            assertThat(result.getCompletedCount()).isZero();
            assertThat(result.getIncompleteCount()).isEqualTo(3);
            assertThat(result.getCancelledBeforeStartCount()).isEqualTo(2);
        } finally {
            blockedProvider.release.countDown();
        }
    }

    private static final class TestMemoryProvider extends MemoryProvider {
        private final List<ChatMessageRecord> messages = new ArrayList<>();

        @Override
        public synchronized void saveMessage(ChatMessageRecord message) {
            message.setId(messages.size() + 1L);
            messages.add(message);
        }

        @Override
        public synchronized List<ChatMessageRecord> searchMessages(
                MessageFilter filter, int offset, int limit) {
            return messages.stream()
                    .filter(filter::matches)
                    .sorted(Comparator.comparingLong(ChatMessageRecord::getTimestamp).reversed())
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized int countMessages(MessageFilter filter) {
            return (int) messages.stream().filter(filter::matches).count();
        }

        @Override
        public synchronized int cleanupMessagesBefore(long cutoffTimestamp) {
            int before = messages.size();
            messages.removeIf(message -> message.getTimestamp() < cutoffTimestamp);
            return before - messages.size();
        }
    }

    private static final class PermanentlyBlockingProvider extends MemoryProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void saveMessage(ChatMessageRecord message) {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Deliberately model a database call that ignores cancellation.
                }
            }
        }
    }
}
