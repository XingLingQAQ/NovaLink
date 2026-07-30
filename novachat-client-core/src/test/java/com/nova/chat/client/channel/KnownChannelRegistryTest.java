package com.nova.chat.client.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KnownChannelRegistry")
class KnownChannelRegistryTest {

    @Nested
    @DisplayName("replaceAll / addAll")
    class Mutation {
        @Test
        @DisplayName("replaceAll replaces the full set and trims null/blank entries")
        void replaceAllReplacesAndCleans() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("global", "local"));
            assertThat(registry.getAll()).containsExactlyInAnyOrder("global", "local");

            Set<String> replacement = new HashSet<>();
            replacement.add("pvp");
            replacement.add("  ");
            replacement.add(null);
            replacement.add("resource");
            registry.replaceAll(replacement);
            assertThat(registry.getAll()).containsExactlyInAnyOrder("pvp", "resource");
        }

        @Test
        @DisplayName("replaceAll with null clears the set")
        void replaceAllNullClears() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("global"));
            registry.replaceAll(null);
            assertThat(registry.getAll()).isEmpty();
        }

        @Test
        @DisplayName("addAll with null is a no-op")
        void addAllNullIsNoOp() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(null);
            assertThat(registry.getAll()).isEmpty();
        }

        @Test
        @DisplayName("addAll is additive without clearing")
        void addAllIsAdditive() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("global"));
            registry.addAll(Set.of("global", "pvp"));
            assertThat(registry.getAll()).containsExactlyInAnyOrder("global", "pvp");
        }
    }

    @Nested
    @DisplayName("getKnownChannelIds(prefix)")
    class PrefixQuery {
        @Test
        @DisplayName("null prefix returns all known ids sorted case-insensitively")
        void nullPrefixReturnsAllSorted() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("pvp", "Global", "local", "Resource"));

            List<String> result = registry.getKnownChannelIds(null);

            assertThat(result).containsExactly("Global", "local", "pvp", "Resource");
        }

        @Test
        @DisplayName("empty prefix returns all known ids")
        void emptyPrefixReturnsAll() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("pvp", "global"));

            assertThat(registry.getKnownChannelIds("")).containsExactlyInAnyOrder("global", "pvp");
        }

        @Test
        @DisplayName("prefix filters case-insensitively")
        void prefixFiltersCaseInsensitive() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("global", "global-chat", "local", "pvp"));

            assertThat(registry.getKnownChannelIds("GLO"))
                    .containsExactly("global", "global-chat");
        }

        @Test
        @DisplayName("prefix with no matches returns empty list")
        void prefixNoMatchReturnsEmpty() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("global", "local"));

            assertThat(registry.getKnownChannelIds("xyz")).isEmpty();
        }

        @Test
        @DisplayName("empty registry returns empty list for any prefix")
        void emptyRegistryReturnsEmpty() {
            KnownChannelRegistry registry = new KnownChannelRegistry();

            assertThat(registry.getKnownChannelIds(null)).isEmpty();
            assertThat(registry.getKnownChannelIds("g")).isEmpty();
        }

        @Test
        @DisplayName("result is a mutable copy — does not leak the backing set")
        void resultIsDefensiveCopy() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("global"));

            List<String> result = registry.getKnownChannelIds(null);
            result.clear();
            assertThat(registry.getAll()).containsExactly("global");
        }
    }

    @Nested
    @DisplayName("getAll")
    class Membership {
        @Test
        @DisplayName("getAll returns an unmodifiable view")
        void getAllIsUnmodifiable() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.addAll(Set.of("global"));

            Set<String> view = registry.getAll();
            assertThatThrownByModify(view);
            assertThat(view).containsExactly("global");
        }

        private void assertThatThrownByModify(Set<String> view) {
            try {
                view.add("pvp");
                throw new AssertionError("expected view to be unmodifiable");
            } catch (UnsupportedOperationException expected) {
                // expected
            }
        }
    }

    @Nested
    @DisplayName("thread safety")
    class Concurrency {
        @Test
        @DisplayName("concurrent addAll + getKnownChannelIds does not throw")
        void concurrentAccessIsSafe() throws InterruptedException {
            final KnownChannelRegistry registry = new KnownChannelRegistry();
            int threads = 8;
            int iterations = 200;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threads; t++) {
                final int seed = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            Set<String> batch = new HashSet<>();
                            batch.add("ch-" + seed + "-" + i);
                            batch.add("shared-" + (i % 5));
                            registry.addAll(batch);
                            registry.getKnownChannelIds("ch-" + seed);
                            if (i % 50 == 0) {
                                registry.replaceAll(batch);
                            }
                        }
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }

            start.countDown();
            done.await();

            assertThat(errors.get()).isZero();
            assertThat(registry.getAll()).isNotEmpty();
        }
    }
}
