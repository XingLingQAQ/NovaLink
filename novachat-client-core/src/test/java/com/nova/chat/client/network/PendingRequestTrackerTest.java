package com.nova.chat.client.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PendingRequestTracker")
class PendingRequestTrackerTest {

    private AtomicLong clock;
    private PendingRequestTracker<String> tracker;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        tracker = new PendingRequestTracker<>(1_000L, clock::get);
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("rejects non-positive default timeout")
        void rejectsNonPositiveTimeout() {
            assertThatThrownBy(() -> new PendingRequestTracker<>(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("defaultTimeoutMs");
            assertThatThrownBy(() -> new PendingRequestTracker<>(-5))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null clock")
        void rejectsNullClock() {
            assertThatThrownBy(() -> new PendingRequestTracker<>(100L, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("clock");
        }

        @Test
        @DisplayName("stores default timeout")
        void storesDefaultTimeout() {
            assertThat(tracker.getDefaultTimeoutMs()).isEqualTo(1_000L);
        }
    }

    @Nested
    @DisplayName("track")
    class Track {

        @Test
        @DisplayName("returns incomplete future and marks request pending")
        void tracksNewRequest() {
            UUID id = UUID.randomUUID();
            CompletableFuture<String> future = tracker.track(id);

            assertThat(future).isNotCompleted();
            assertThat(tracker.isPending(id)).isTrue();
            assertThat(tracker.size()).isEqualTo(1);
            assertThat(tracker.isEmpty()).isFalse();
            assertThat(tracker.get(id)).contains(future);
        }

        @Test
        @DisplayName("rejects null request id")
        void rejectsNullId() {
            assertThatThrownBy(() -> tracker.track(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("requestId");
        }

        @Test
        @DisplayName("rejects non-positive custom timeout")
        void rejectsBadCustomTimeout() {
            UUID id = UUID.randomUUID();
            assertThatThrownBy(() -> tracker.track(id, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeoutMs");
        }

        @Test
        @DisplayName("superseding an existing request fails the previous future")
        void supersedeFailsPrevious() {
            UUID id = UUID.randomUUID();
            CompletableFuture<String> first = tracker.track(id);
            CompletableFuture<String> second = tracker.track(id);

            assertThat(first).isCompletedExceptionally();
            assertThatThrownBy(first::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Request " + id + " was superseded");

            assertThat(second).isNotCompleted();
            assertThat(tracker.size()).isEqualTo(1);
            assertThat(tracker.get(id)).contains(second);
        }
    }

    @Nested
    @DisplayName("complete / fail")
    class CompleteAndFail {

        @Test
        @DisplayName("complete resolves future and removes entry")
        void completeResolves() {
            UUID id = UUID.randomUUID();
            CompletableFuture<String> future = tracker.track(id);

            boolean ok = tracker.complete(id, "ok");

            assertThat(ok).isTrue();
            assertThat(future).isCompletedWithValue("ok");
            assertThat(tracker.isPending(id)).isFalse();
            assertThat(tracker.size()).isZero();
            assertThat(tracker.get(id)).isEmpty();
        }

        @Test
        @DisplayName("complete returns false for unknown id")
        void completeUnknown() {
            assertThat(tracker.complete(UUID.randomUUID(), "x")).isFalse();
        }

        @Test
        @DisplayName("fail completes exceptionally and removes entry")
        void failResolves() {
            UUID id = UUID.randomUUID();
            CompletableFuture<String> future = tracker.track(id);
            RuntimeException cause = new RuntimeException("boom");

            boolean ok = tracker.fail(id, cause);

            assertThat(ok).isTrue();
            assertThat(future).isCompletedExceptionally();
            assertThatThrownBy(future::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCause(cause);
            assertThat(tracker.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("fail rejects null cause")
        void failRejectsNullCause() {
            UUID id = UUID.randomUUID();
            tracker.track(id);
            assertThatThrownBy(() -> tracker.fail(id, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("cause");
        }

        @Test
        @DisplayName("fail returns false for unknown id")
        void failUnknown() {
            assertThat(tracker.fail(UUID.randomUUID(), new RuntimeException("x"))).isFalse();
        }

        @Test
        @DisplayName("complete after fail is a no-op (already removed)")
        void completeAfterFail() {
            UUID id = UUID.randomUUID();
            tracker.track(id);
            tracker.fail(id, new RuntimeException("gone"));
            assertThat(tracker.complete(id, "late")).isFalse();
        }
    }

    @Nested
    @DisplayName("cleanupExpired")
    class Cleanup {

        @Test
        @DisplayName("does not remove fresh entries")
        void keepsFresh() {
            UUID id = UUID.randomUUID();
            CompletableFuture<String> future = tracker.track(id, 500L);

            clock.addAndGet(400L);
            int removed = tracker.cleanupExpired();

            assertThat(removed).isZero();
            assertThat(tracker.isPending(id)).isTrue();
            assertThat(future).isNotCompleted();
        }

        @Test
        @DisplayName("expires entries past their timeout with TimeoutException")
        void expiresStale() {
            UUID id = UUID.randomUUID();
            CompletableFuture<String> future = tracker.track(id, 500L);

            clock.addAndGet(501L);
            int removed = tracker.cleanupExpired();

            assertThat(removed).isEqualTo(1);
            assertThat(tracker.isEmpty()).isTrue();
            assertThat(future).isCompletedExceptionally();
            assertThatThrownBy(future::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(TimeoutException.class)
                    .hasRootCauseMessage("Request " + id + " timed out after 500ms");
        }

        @Test
        @DisplayName("uses per-request timeout, not only the default")
        void perRequestTimeout() {
            UUID shortId = UUID.randomUUID();
            UUID longId = UUID.randomUUID();
            CompletableFuture<String> shortFuture = tracker.track(shortId, 100L);
            CompletableFuture<String> longFuture = tracker.track(longId, 5_000L);

            clock.addAndGet(150L);
            int removed = tracker.cleanupExpired();

            assertThat(removed).isEqualTo(1);
            assertThat(shortFuture).isCompletedExceptionally();
            assertThat(longFuture).isNotCompleted();
            assertThat(tracker.isPending(longId)).isTrue();
            assertThat(tracker.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("boundary: exactly at timeout is not yet expired (strict >)")
        void boundaryNotExpired() {
            UUID id = UUID.randomUUID();
            tracker.track(id, 1_000L);

            clock.addAndGet(1_000L);
            assertThat(tracker.cleanupExpired()).isZero();
            assertThat(tracker.isPending(id)).isTrue();

            clock.addAndGet(1L);
            assertThat(tracker.cleanupExpired()).isEqualTo(1);
        }

        @Test
        @DisplayName("isPending is false for expired-but-not-yet-cleaned entries")
        void isPendingFalseWhenExpired() {
            UUID id = UUID.randomUUID();
            tracker.track(id, 100L);
            clock.addAndGet(101L);

            assertThat(tracker.isPending(id)).isFalse();
            // still in map until cleanup
            assertThat(tracker.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("cleans multiple expired entries in one pass")
        void cleansMultiple() {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(tracker.track(UUID.randomUUID(), 50L));
            }
            clock.addAndGet(51L);

            assertThat(tracker.cleanupExpired()).isEqualTo(5);
            assertThat(tracker.isEmpty()).isTrue();
            assertThat(futures).allSatisfy(f -> assertThat(f).isCompletedExceptionally());
        }
    }

    @Nested
    @DisplayName("clearAll")
    class ClearAll {

        @Test
        @DisplayName("fails all futures with provided cause")
        void clearWithCause() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            CompletableFuture<String> fa = tracker.track(a);
            CompletableFuture<String> fb = tracker.track(b);
            RuntimeException cause = new RuntimeException("disconnect");

            int cleared = tracker.clearAll(cause);

            assertThat(cleared).isEqualTo(2);
            assertThat(tracker.isEmpty()).isTrue();
            assertThatThrownBy(fa::join).hasCause(cause);
            assertThatThrownBy(fb::join).hasCause(cause);
        }

        @Test
        @DisplayName("default clearAll uses IllegalStateException")
        void defaultClear() {
            UUID id = UUID.randomUUID();
            CompletableFuture<String> future = tracker.track(id);

            assertThat(tracker.clearAll()).isEqualTo(1);
            assertThatThrownBy(future::join)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Pending requests cleared");
        }

        @Test
        @DisplayName("clearAll on empty tracker returns zero")
        void clearEmpty() {
            assertThat(tracker.clearAll()).isZero();
        }
    }

    @Nested
    @DisplayName("concurrency smoke")
    class Concurrency {

        @Test
        @DisplayName("track/complete from many threads leaves no leaked entries")
        void parallelTrackAndComplete() throws Exception {
            PendingRequestTracker<Integer> multi = new PendingRequestTracker<>(5_000L);
            int n = 200;
            List<UUID> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                ids.add(UUID.randomUUID());
            }

            List<CompletableFuture<Void>> workers = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int value = i;
                final UUID id = ids.get(i);
                workers.add(CompletableFuture.runAsync(() -> {
                    CompletableFuture<Integer> f = multi.track(id);
                    multi.complete(id, value);
                    assertThat(f).succeedsWithin(1, TimeUnit.SECONDS).isEqualTo(value);
                }));
            }
            CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);

            assertThat(multi.isEmpty()).isTrue();
        }
    }
}
