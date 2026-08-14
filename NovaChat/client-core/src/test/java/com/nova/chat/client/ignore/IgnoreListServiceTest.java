package com.nova.chat.client.ignore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IgnoreListService}: case-insensitive add/remove,
 * self check, per-player limit, concurrency, persistence round-trip and
 * corrupt-file tolerance.
 */
@DisplayName("IgnoreListService")
class IgnoreListServiceTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Nested
    @DisplayName("in-memory behavior")
    class InMemory {

        @Test
        @DisplayName("ignore adds, isIgnored matches case-insensitively, unignore removes")
        void basicRoundTrip() {
            IgnoreListService service = new IgnoreListService();

            assertThat(service.ignore(ALICE, "Alice", "Steve"))
                    .isEqualTo(IgnoreListService.AddResult.ADDED);
            assertThat(service.isIgnored(ALICE, "steve")).isTrue();
            assertThat(service.isIgnored(ALICE, "STEVE")).isTrue();
            assertThat(service.isIgnored(BOB, "Steve")).isFalse();

            assertThat(service.unignore(ALICE, "sTeVe")).isTrue();
            assertThat(service.isIgnored(ALICE, "Steve")).isFalse();
            assertThat(service.unignore(ALICE, "Steve")).isFalse();
        }

        @Test
        @DisplayName("duplicate ignore reports ALREADY_IGNORED")
        void duplicateIgnore() {
            IgnoreListService service = new IgnoreListService();
            service.ignore(ALICE, "Alice", "Steve");
            assertThat(service.ignore(ALICE, "Alice", "STEVE"))
                    .isEqualTo(IgnoreListService.AddResult.ALREADY_IGNORED);
        }

        @Test
        @DisplayName("a player cannot ignore themselves (case-insensitive)")
        void cannotIgnoreSelf() {
            IgnoreListService service = new IgnoreListService();
            assertThat(service.ignore(ALICE, "Alice", "alice"))
                    .isEqualTo(IgnoreListService.AddResult.SELF);
            assertThat(service.isIgnored(ALICE, "Alice")).isFalse();
        }

        @Test
        @DisplayName("listIgnored returns normalized names sorted")
        void listSorted() {
            IgnoreListService service = new IgnoreListService();
            service.ignore(ALICE, "Alice", "Zed");
            service.ignore(ALICE, "Alice", "Bob");
            service.ignore(ALICE, "Alice", "Mia");
            assertThat(service.listIgnored(ALICE)).containsExactly("bob", "mia", "zed");
            assertThat(service.listIgnored(BOB)).isEmpty();
        }

        @Test
        @DisplayName("limit of 100 entries per player is enforced")
        void limitEnforced() {
            IgnoreListService service = new IgnoreListService();
            for (int i = 0; i < IgnoreListService.MAX_IGNORES_PER_PLAYER; i++) {
                assertThat(service.ignore(ALICE, "Alice", "player" + i))
                        .isEqualTo(IgnoreListService.AddResult.ADDED);
            }
            assertThat(service.ignore(ALICE, "Alice", "one-too-many"))
                    .isEqualTo(IgnoreListService.AddResult.LIMIT_REACHED);
            assertThat(service.listIgnored(ALICE))
                    .hasSize(IgnoreListService.MAX_IGNORES_PER_PLAYER);

            // Removing one frees a slot again.
            service.unignore(ALICE, "player0");
            assertThat(service.ignore(ALICE, "Alice", "one-too-many"))
                    .isEqualTo(IgnoreListService.AddResult.ADDED);
        }

        @Test
        @DisplayName("null / blank inputs are safe no-ops")
        void nullSafety() {
            IgnoreListService service = new IgnoreListService();
            assertThat(service.isIgnored(null, "Steve")).isFalse();
            assertThat(service.isIgnored(ALICE, null)).isFalse();
            assertThat(service.isIgnored(ALICE, "")).isFalse();
            assertThat(service.unignore(ALICE, null)).isFalse();
            assertThat(service.unignore(null, "Steve")).isFalse();
            assertThat(service.listIgnored(null)).isEmpty();
            assertThat(service.ignore(ALICE, "Alice", "   "))
                    .isEqualTo(IgnoreListService.AddResult.ALREADY_IGNORED);
        }

        @Test
        @DisplayName("concurrent ignores never exceed the per-player limit")
        void concurrentLimit() throws Exception {
            IgnoreListService service = new IgnoreListService();
            int threads = 8;
            int perThread = 50; // 8 * 50 = 400 candidate names, limit is 100
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            try {
                for (int t = 0; t < threads; t++) {
                    final int thread = t;
                    pool.execute(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                service.ignore(ALICE, "Alice", "p" + thread + "-" + i);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }
            assertThat(service.listIgnored(ALICE))
                    .hasSize(IgnoreListService.MAX_IGNORES_PER_PLAYER);
        }
    }

    @Nested
    @DisplayName("persistence")
    class Persistence {

        @Test
        @DisplayName("flush + reload round-trips the ignore lists")
        void roundTrip(@TempDir Path dir) {
            IgnoreListService service = new IgnoreListService();
            service.setDataDirectory(dir);
            service.ignore(ALICE, "Alice", "Steve");
            service.ignore(ALICE, "Alice", "Alex");
            service.ignore(BOB, "Bob", "Steve");
            service.close();

            assertThat(dir.resolve(IgnoreListService.FILE_NAME)).exists();

            IgnoreListService reloaded = new IgnoreListService();
            reloaded.setDataDirectory(dir);
            assertThat(reloaded.listIgnored(ALICE)).containsExactly("alex", "steve");
            assertThat(reloaded.isIgnored(BOB, "STEVE")).isTrue();
            reloaded.close();
        }

        @Test
        @DisplayName("debounced write lands on disk without an explicit flush")
        void debouncedWrite(@TempDir Path dir) throws Exception {
            IgnoreListService service = new IgnoreListService(50);
            service.setDataDirectory(dir);
            service.ignore(ALICE, "Alice", "Steve");

            Path file = dir.resolve(IgnoreListService.FILE_NAME);
            long deadline = System.currentTimeMillis() + 5000;
            while (!Files.exists(file) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(file).exists();
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                    .contains("steve");
            service.close();
        }

        @Test
        @DisplayName("corrupt file loads as empty without throwing")
        void corruptFile(@TempDir Path dir) throws Exception {
            Files.write(dir.resolve(IgnoreListService.FILE_NAME),
                    "{not-json!!!".getBytes(StandardCharsets.UTF_8));

            IgnoreListService service = new IgnoreListService();
            service.setDataDirectory(dir);
            assertThat(service.listIgnored(ALICE)).isEmpty();

            // The service stays functional and can overwrite the corrupt file.
            service.ignore(ALICE, "Alice", "Steve");
            service.close();

            IgnoreListService reloaded = new IgnoreListService();
            reloaded.setDataDirectory(dir);
            assertThat(reloaded.isIgnored(ALICE, "Steve")).isTrue();
            reloaded.close();
        }

        @Test
        @DisplayName("malformed entries (bad UUID, null list) are skipped, valid ones kept")
        void partialCorruption(@TempDir Path dir) throws Exception {
            String json = "{\"not-a-uuid\":[\"steve\"],"
                    + "\"" + ALICE + "\":[\"steve\",null,\"  \"],"
                    + "\"" + BOB + "\":null}";
            Files.write(dir.resolve(IgnoreListService.FILE_NAME),
                    json.getBytes(StandardCharsets.UTF_8));

            IgnoreListService service = new IgnoreListService();
            service.setDataDirectory(dir);
            assertThat(service.listIgnored(ALICE)).containsExactly("steve");
            assertThat(service.listIgnored(BOB)).isEmpty();
            service.close();
        }

        @Test
        @DisplayName("without a data directory the service works purely in memory")
        void noDataDirectory() {
            IgnoreListService service = new IgnoreListService();
            assertThat(service.ignore(ALICE, "Alice", "Steve"))
                    .isEqualTo(IgnoreListService.AddResult.ADDED);
            service.flush(); // must not throw
            service.close();
            assertThat(service.isIgnored(ALICE, "Steve")).isTrue();
        }
    }
}
