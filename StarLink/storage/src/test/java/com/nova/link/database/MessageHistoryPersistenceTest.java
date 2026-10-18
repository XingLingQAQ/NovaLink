package com.nova.link.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Message history persistence tests (schema v5 {@code messages} table):
 * saveMessage id stamping, searchMessages across every filter dimension,
 * pagination with real totals, and retention cleanup — run against both the
 * SQLite (real file DB) and Memory providers so the SQL and in-memory filter
 * paths stay in lockstep.
 */
@DisplayName("Message history persistence (SQLite + Memory)")
class MessageHistoryPersistenceTest {

    /** Shared assertions executed against any provider. */
    abstract static class ProviderContract {

        DatabaseProvider provider;

        abstract DatabaseProvider createProvider() throws Exception;

        @BeforeEach
        void setUpProvider() throws Exception {
            provider = createProvider();
            provider.initialize();
            seedMessages();
        }

        @AfterEach
        void tearDownProvider() {
            if (provider != null) {
                provider.shutdown();
            }
        }

        static final String ALICE_ID = UUID.nameUUIDFromBytes("alice".getBytes()).toString();

        void seedMessages() throws DatabaseException {
            // 5 messages across 2 channels / 2 clients / 2 senders, timestamps 1000..5000.
            provider.saveMessage(new ChatMessageRecord("global", ALICE_ID, "Alice", "Survival", "hello world", 1000));
            provider.saveMessage(new ChatMessageRecord("global", ALICE_ID, "Alice", "Survival", "how are you", 2000));
            provider.saveMessage(new ChatMessageRecord("staff", null, "Bob", "Creative", "secret plan", 3000));
            provider.saveMessage(new ChatMessageRecord("global", null, "Bob", "Creative", "Hello again", 4000));
            provider.saveMessage(new ChatMessageRecord("staff", ALICE_ID, "alice2", "Survival", "bye", 5000));
        }

        @Test
        @DisplayName("saveMessage stamps a backend-generated id")
        void saveStampsGeneratedId() throws DatabaseException {
            ChatMessageRecord record = new ChatMessageRecord("global", null, "Carl", "Survival", "id test", 6000);
            provider.saveMessage(record);
            assertThat(record.getId()).isPositive();
        }

        @Test
        @DisplayName("unfiltered search returns everything newest-first with real total")
        void unfilteredNewestFirst() throws DatabaseException {
            List<ChatMessageRecord> all = provider.searchMessages(MessageFilter.any(), 0, 100);
            assertThat(all).hasSize(5);
            assertThat(all.get(0).getTimestamp()).isEqualTo(5000);
            assertThat(all.get(4).getTimestamp()).isEqualTo(1000);
            assertThat(provider.countMessages(MessageFilter.any())).isEqualTo(5);
        }

        @Test
        @DisplayName("channel filter matches exactly")
        void filtersByChannel() throws DatabaseException {
            MessageFilter filter = new MessageFilter("staff", null, null, null, null, null);
            assertThat(provider.searchMessages(filter, 0, 100))
                    .hasSize(2)
                    .allSatisfy(r -> assertThat(r.getChannelId()).isEqualTo("staff"));
            assertThat(provider.countMessages(filter)).isEqualTo(2);
        }

        @Test
        @DisplayName("server filter matches clientId exactly")
        void filtersByClientId() throws DatabaseException {
            MessageFilter filter = new MessageFilter(null, "Creative", null, null, null, null);
            assertThat(provider.searchMessages(filter, 0, 100))
                    .hasSize(2)
                    .allSatisfy(r -> assertThat(r.getClientId()).isEqualTo("Creative"));
        }

        @Test
        @DisplayName("player filter is a case-insensitive substring on senderName")
        void filtersBySenderNameSubstring() throws DatabaseException {
            // "alice" matches "Alice" (x2) and "alice2" (x1).
            MessageFilter filter = new MessageFilter(null, null, "alice", null, null, null);
            assertThat(provider.searchMessages(filter, 0, 100)).hasSize(3);
            assertThat(provider.countMessages(filter)).isEqualTo(3);
        }

        @Test
        @DisplayName("q filter is a case-insensitive substring on content")
        void filtersByContentSubstring() throws DatabaseException {
            MessageFilter filter = new MessageFilter(null, null, null, "hello", null, null);
            assertThat(provider.searchMessages(filter, 0, 100)).hasSize(2);
        }

        @Test
        @DisplayName("from/to bounds are inclusive")
        void filtersByTimeRangeInclusive() throws DatabaseException {
            MessageFilter filter = new MessageFilter(null, null, null, null, 2000L, 4000L);
            List<ChatMessageRecord> hits = provider.searchMessages(filter, 0, 100);
            assertThat(hits).extracting(ChatMessageRecord::getTimestamp)
                    .containsExactly(4000L, 3000L, 2000L);
        }

        @Test
        @DisplayName("combined filters intersect")
        void combinedFilters() throws DatabaseException {
            MessageFilter filter = new MessageFilter("global", "Survival", "alice", "hello", null, null);
            List<ChatMessageRecord> hits = provider.searchMessages(filter, 0, 100);
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).getContent()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("LIKE wildcards in q are treated literally, not as wildcards")
        void likeWildcardsEscaped() throws DatabaseException {
            provider.saveMessage(new ChatMessageRecord("global", null, "Eve", "Survival", "50% off_sale", 7000));
            // '%' must not act as a match-all.
            assertThat(provider.countMessages(new MessageFilter(null, null, null, "%", null, null)))
                    .isEqualTo(1);
            // '_' must not act as a single-char wildcard ("off-sale" shouldn't match).
            assertThat(provider.countMessages(new MessageFilter(null, null, null, "off_sale", null, null)))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("pagination slices newest-first while total stays the real count")
        void paginationWithRealTotal() throws DatabaseException {
            List<ChatMessageRecord> page1 = provider.searchMessages(MessageFilter.any(), 0, 2);
            List<ChatMessageRecord> page2 = provider.searchMessages(MessageFilter.any(), 2, 2);
            List<ChatMessageRecord> page3 = provider.searchMessages(MessageFilter.any(), 4, 2);
            assertThat(page1).extracting(ChatMessageRecord::getTimestamp).containsExactly(5000L, 4000L);
            assertThat(page2).extracting(ChatMessageRecord::getTimestamp).containsExactly(3000L, 2000L);
            assertThat(page3).extracting(ChatMessageRecord::getTimestamp).containsExactly(1000L);
            assertThat(provider.countMessages(MessageFilter.any())).isEqualTo(5);
        }

        @Test
        @DisplayName("authorized channel set is applied before pagination and count")
        void authorizedChannelsConstrainPaginationAndCount() throws DatabaseException {
            MessageFilter globalOnly = new MessageFilter(
                    null, null, null, null, null, null, Set.of("global"));
            assertThat(provider.searchMessages(globalOnly, 0, 2))
                    .extracting(ChatMessageRecord::getTimestamp)
                    .containsExactly(4000L, 2000L);
            assertThat(provider.countMessages(globalOnly)).isEqualTo(3);

            MessageFilter noChannels = new MessageFilter(
                    null, null, null, null, null, null, Set.of());
            assertThat(provider.searchMessages(noChannels, 0, 100)).isEmpty();
            assertThat(provider.countMessages(noChannels)).isZero();
        }

        @Test
        @DisplayName("cleanupMessagesBefore deletes strictly-older rows and reports the count")
        void cleanupRemovesExpiredRows() throws DatabaseException {
            int removed = provider.cleanupMessagesBefore(3000);
            assertThat(removed).isEqualTo(2);
            assertThat(provider.countMessages(MessageFilter.any())).isEqualTo(3);
            // Remaining rows are all >= the cutoff.
            assertThat(provider.searchMessages(MessageFilter.any(), 0, 100))
                    .allSatisfy(r -> assertThat(r.getTimestamp()).isGreaterThanOrEqualTo(3000));
        }
    }

    @Nested
    @DisplayName("SQLiteProvider")
    class SQLite extends ProviderContract {
        @TempDir
        Path tempDir;

        @Override
        DatabaseProvider createProvider() {
            return new SQLiteProvider(tempDir.resolve("messages-test.db").toString(), 5);
        }
    }

    @Nested
    @DisplayName("MemoryProvider")
    class Memory extends ProviderContract {
        @Override
        DatabaseProvider createProvider() {
            return new MemoryProvider();
        }

        @Test
        @DisplayName("bounded queue evicts the oldest rows beyond the cap")
        void boundedQueueEvictsOldest() throws DatabaseException {
            // Fill up to the cap plus 10; the 10 oldest seeded rows must be gone.
            int cap = MemoryProvider.MAX_MESSAGES;
            for (int i = 0; i < cap + 5; i++) {
                provider.saveMessage(new ChatMessageRecord("bulk", null, "Bot", "Survival", "m" + i, 10_000 + i));
            }
            // 5 seeded + cap + 5 inserted, bounded to cap.
            assertThat(provider.countMessages(MessageFilter.any())).isEqualTo(cap);
            // The seeded rows (timestamps 1000..5000) were the oldest → evicted.
            assertThat(provider.countMessages(new MessageFilter(null, null, null, null, null, 9999L)))
                    .isZero();
        }
    }
}
