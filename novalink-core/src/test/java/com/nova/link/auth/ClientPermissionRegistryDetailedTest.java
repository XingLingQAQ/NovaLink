package com.nova.link.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive unit tests for {@link ClientPermissionRegistry}:
 * grant/revoke/clear, hasPermission rules, wildcard, trim, BiPredicate adapter,
 * unmodifiable snapshots, and concurrent grant smoke coverage.
 */
@DisplayName("ClientPermissionRegistry detailed")
class ClientPermissionRegistryDetailedTest {

    private ClientPermissionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ClientPermissionRegistry();
    }

    @Nested
    @DisplayName("grant / revoke / clear")
    class GrantRevokeClear {

        @Test
        @DisplayName("grant stores permission and increments tracked client count")
        void grantStoresPermission() {
            registry.grant("client-1", "novachat.channel.staff");

            assertThat(registry.hasPermission("client-1", "novachat.channel.staff")).isTrue();
            assertThat(registry.getGrants("client-1")).containsExactly("novachat.channel.staff");
            assertThat(registry.getTrackedClientCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("grant is idempotent for the same node")
        void grantIdempotent() {
            registry.grant("client-1", "perm.a");
            registry.grant("client-1", "perm.a");

            assertThat(registry.getGrants("client-1")).containsExactly("perm.a");
            assertThat(registry.getTrackedClientCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("grant multiple distinct nodes for same client")
        void grantMultipleNodes() {
            registry.grant("client-1", "perm.a");
            registry.grant("client-1", "perm.b");

            assertThat(registry.getGrants("client-1")).containsExactlyInAnyOrder("perm.a", "perm.b");
            assertThat(registry.getTrackedClientCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("grantAll adds every non-blank permission")
        void grantAll() {
            registry.grantAll("client-1", List.of("a", "b", "c"));

            assertThat(registry.getGrants("client-1")).containsExactlyInAnyOrder("a", "b", "c");
        }

        @Test
        @DisplayName("grantAll ignores null iterable and blank client")
        void grantAllIgnoresInvalid() {
            registry.grantAll(null, List.of("a"));
            registry.grantAll("  ", List.of("a"));
            registry.grantAll("client-1", null);

            assertThat(registry.getTrackedClientCount()).isZero();
        }

        @Test
        @DisplayName("grant ignores null/blank clientId or permission")
        void grantIgnoresInvalidInputs() {
            registry.grant(null, "perm");
            registry.grant("  ", "perm");
            registry.grant("client-1", null);
            registry.grant("client-1", "   ");
            registry.grant("", "");

            assertThat(registry.getTrackedClientCount()).isZero();
            assertThat(registry.getGrants("client-1")).isEmpty();
        }

        @Test
        @DisplayName("revoke removes a present node and returns true")
        void revokePresent() {
            registry.grant("client-1", "perm.a");
            registry.grant("client-1", "perm.b");

            assertThat(registry.revoke("client-1", "perm.a")).isTrue();
            assertThat(registry.getGrants("client-1")).containsExactly("perm.b");
            assertThat(registry.getTrackedClientCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("revoke of last node removes the client from tracking")
        void revokeLastNodeClearsClient() {
            registry.grant("client-1", "only");

            assertThat(registry.revoke("client-1", "only")).isTrue();
            assertThat(registry.getGrants("client-1")).isEmpty();
            assertThat(registry.getTrackedClientCount()).isZero();
            assertThat(registry.hasPermission("client-1", "only")).isFalse();
        }

        @Test
        @DisplayName("revoke missing node returns false")
        void revokeMissing() {
            registry.grant("client-1", "perm.a");
            assertThat(registry.revoke("client-1", "perm.missing")).isFalse();
            assertThat(registry.revoke("unknown", "perm.a")).isFalse();
        }

        @Test
        @DisplayName("revoke with null client or permission returns false")
        void revokeNullArgs() {
            registry.grant("client-1", "perm.a");
            assertThat(registry.revoke(null, "perm.a")).isFalse();
            assertThat(registry.revoke("client-1", null)).isFalse();
        }

        @Test
        @DisplayName("revoke trims permission argument before removal")
        void revokeTrimsPermission() {
            registry.grant("client-1", "perm.a");
            assertThat(registry.revoke("client-1", "  perm.a  ")).isTrue();
            assertThat(registry.getTrackedClientCount()).isZero();
        }

        @Test
        @DisplayName("clearClient removes all grants for one client")
        void clearClient() {
            registry.grant("client-1", "a");
            registry.grant("client-1", "b");
            registry.grant("client-2", "c");

            registry.clearClient("client-1");

            assertThat(registry.getGrants("client-1")).isEmpty();
            assertThat(registry.getGrants("client-2")).containsExactly("c");
            assertThat(registry.getTrackedClientCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("clearClient with null is a no-op")
        void clearClientNull() {
            registry.grant("client-1", "a");
            registry.clearClient(null);
            assertThat(registry.getTrackedClientCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("clearAll removes every grant")
        void clearAll() {
            registry.grant("c1", "a");
            registry.grant("c2", "b");
            registry.clearAll();

            assertThat(registry.getTrackedClientCount()).isZero();
            assertThat(registry.getGrants("c1")).isEmpty();
            assertThat(registry.getGrants("c2")).isEmpty();
        }
    }

    @Nested
    @DisplayName("hasPermission rules")
    class HasPermission {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("null/blank permission is always allowed regardless of client")
        void blankPermissionAlwaysTrue(String permission) {
            assertThat(registry.hasPermission(null, permission)).isTrue();
            assertThat(registry.hasPermission("", permission)).isTrue();
            assertThat(registry.hasPermission("client-1", permission)).isTrue();
            registry.grant("client-1", "other");
            assertThat(registry.hasPermission("client-1", permission)).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("null/blank clientId is denied when permission is required")
        void blankClientDeniedWhenPermissionRequired(String clientId) {
            assertThat(registry.hasPermission(clientId, "novachat.channel.staff")).isFalse();
        }

        @Test
        @DisplayName("unregistered client denied when allowWhenUnregistered=false (default)")
        void unregisteredDeniedByDefault() {
            assertThat(registry.isAllowWhenUnregistered()).isFalse();
            assertThat(registry.hasPermission("unknown", "any.perm")).isFalse();
        }

        @Test
        @DisplayName("unregistered client allowed when allowWhenUnregistered=true")
        void unregisteredAllowedWhenFlagTrue() {
            registry.setAllowWhenUnregistered(true);
            assertThat(registry.isAllowWhenUnregistered()).isTrue();
            assertThat(registry.hasPermission("unknown", "any.perm")).isTrue();
        }

        @Test
        @DisplayName("registered client with empty grants after revoke uses allowWhenUnregistered")
        void emptyGrantsUsesAllowFlag() {
            registry.grant("client-1", "temp");
            registry.revoke("client-1", "temp");

            assertThat(registry.hasPermission("client-1", "temp")).isFalse();

            registry.setAllowWhenUnregistered(true);
            assertThat(registry.hasPermission("client-1", "temp")).isTrue();
        }

        @Test
        @DisplayName("wildcard * satisfies any required permission")
        void wildcardSatisfiesAny() {
            registry.grant("client-1", ClientPermissionRegistry.WILDCARD);

            assertThat(registry.hasPermission("client-1", "novachat.channel.staff")).isTrue();
            assertThat(registry.hasPermission("client-1", "anything.else")).isTrue();
            assertThat(registry.hasPermission("client-1", "*")).isTrue();
        }

        @Test
        @DisplayName("exact match allows only the granted node")
        void exactMatch() {
            registry.grant("client-1", "novachat.channel.staff");

            assertThat(registry.hasPermission("client-1", "novachat.channel.staff")).isTrue();
            assertThat(registry.hasPermission("client-1", "novachat.channel.admin")).isFalse();
            assertThat(registry.hasPermission("client-1", "novachat.channel.staff.extra")).isFalse();
        }

        @Test
        @DisplayName("grant trims permission; hasPermission trims required node for exact match")
        void trimBehavior() {
            registry.grant("client-1", "  novachat.channel.staff  ");

            assertThat(registry.getGrants("client-1")).containsExactly("novachat.channel.staff");
            assertThat(registry.hasPermission("client-1", "  novachat.channel.staff  ")).isTrue();
            assertThat(registry.hasPermission("client-1", "novachat.channel.staff")).isTrue();
        }

        @Test
        @DisplayName("permissions are case-sensitive")
        void caseSensitive() {
            registry.grant("client-1", "Staff");
            assertThat(registry.hasPermission("client-1", "Staff")).isTrue();
            assertThat(registry.hasPermission("client-1", "staff")).isFalse();
        }

        @Test
        @DisplayName("client isolation: grants do not leak across clients")
        void clientIsolation() {
            registry.grant("client-a", "perm.a");
            registry.grant("client-b", "perm.b");

            assertThat(registry.hasPermission("client-a", "perm.a")).isTrue();
            assertThat(registry.hasPermission("client-a", "perm.b")).isFalse();
            assertThat(registry.hasPermission("client-b", "perm.b")).isTrue();
            assertThat(registry.hasPermission("client-b", "perm.a")).isFalse();
        }
    }

    @Nested
    @DisplayName("asChecker BiPredicate")
    class AsChecker {

        @Test
        @DisplayName("asChecker delegates to hasPermission")
        void asCheckerDelegates() {
            registry.grant("client-1", "node.x");
            BiPredicate<String, String> checker = registry.asChecker();

            assertThat(checker).isNotNull();
            assertThat(checker.test("client-1", "node.x")).isTrue();
            assertThat(checker.test("client-1", "node.y")).isFalse();
            assertThat(checker.test("client-1", null)).isTrue();
            assertThat(checker.test(null, "node.x")).isFalse();
        }

        @Test
        @DisplayName("asChecker reflects later grant mutations")
        void asCheckerLiveView() {
            BiPredicate<String, String> checker = registry.asChecker();
            assertThat(checker.test("client-1", "late")).isFalse();
            registry.grant("client-1", "late");
            assertThat(checker.test("client-1", "late")).isTrue();
        }
    }

    @Nested
    @DisplayName("getGrants / getTrackedClientCount / toString")
    class SnapshotsAndMeta {

        @Test
        @DisplayName("getGrants returns empty unmodifiable set when unknown")
        void getGrantsUnknownEmpty() {
            Set<String> grants = registry.getGrants("missing");
            assertThat(grants).isEmpty();
            assertThatThrownBy(() -> grants.add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("getGrants returns unmodifiable snapshot of real grants")
        void getGrantsUnmodifiable() {
            registry.grant("client-1", "a");
            Set<String> grants = registry.getGrants("client-1");
            assertThat(grants).containsExactly("a");
            assertThatThrownBy(() -> grants.add("b"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> grants.remove("a"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("getTrackedClientCount tracks distinct clients only")
        void trackedClientCount() {
            assertThat(registry.getTrackedClientCount()).isZero();
            registry.grant("c1", "a");
            registry.grant("c1", "b");
            assertThat(registry.getTrackedClientCount()).isEqualTo(1);
            registry.grant("c2", "a");
            assertThat(registry.getTrackedClientCount()).isEqualTo(2);
            registry.clearClient("c1");
            assertThat(registry.getTrackedClientCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("toString is non-null and includes client count")
        void toStringNonNull() {
            registry.grant("c1", "a");
            registry.setAllowWhenUnregistered(true);
            String text = registry.toString();
            assertThat(text)
                    .isNotNull()
                    .contains("ClientPermissionRegistry")
                    .contains("clients=1")
                    .contains("allowWhenUnregistered=true");
        }

        @Test
        @DisplayName("WILDCARD constant is *")
        void wildcardConstant() {
            assertThat(ClientPermissionRegistry.WILDCARD).isEqualTo("*");
        }
    }

    @Nested
    @DisplayName("concurrent grant safety smoke")
    class ConcurrentGrant {

        @Test
        @DisplayName("concurrent grants to many clients complete without error")
        void concurrentGrantsSmoke() throws InterruptedException {
            int threads = 8;
            int grantsPerThread = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < grantsPerThread; i++) {
                            String clientId = "client-" + threadIndex + "-" + i;
                            registry.grant(clientId, "perm." + i);
                            if (!registry.hasPermission(clientId, "perm." + i)) {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            assertThat(errors.get()).isZero();
            assertThat(registry.getTrackedClientCount()).isEqualTo(threads * grantsPerThread);
        }

        @Test
        @DisplayName("concurrent grant/revoke on same client does not throw")
        void concurrentGrantRevokeSameClient() throws InterruptedException {
            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            List<Throwable> failures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < 100; i++) {
                            registry.grant("shared", "node-" + (i % 5));
                            registry.hasPermission("shared", "node-" + (i % 5));
                            registry.revoke("shared", "node-" + (i % 5));
                        }
                    } catch (Throwable e) {
                        synchronized (failures) {
                            failures.add(e);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();
            assertThat(failures).isEmpty();
        }
    }
}
