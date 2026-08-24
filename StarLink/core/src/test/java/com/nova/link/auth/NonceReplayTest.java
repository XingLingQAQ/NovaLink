package com.nova.link.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTH-002: verifies the replay-resistance primitive of the challenge-response
 * handshake. The {@link NonceCache} must atomically consume each pending
 * challenge so a replayed {@code HandshakeAuthenticate} (same client id +
 * client nonce) finds nothing and {@link AuthManager#authenticateChallenge}
 * returns {@code NC-401}. Also covers expiry and the concurrent-consume race.
 */
@DisplayName("AUTH-002 nonce replay resistance")
class NonceReplayTest {

    private static final String CLIENT_ID = "ReplayClient";
    private static final String CLIENT_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String SERVER_NONCE = "fedcba9876543210fedcba9876543210";
    private static final String PASSWORD = "replay-password";

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("NonceCache.consume atomicity")
    class ConsumeAtomicity {

        @Test
        @DisplayName("first consume returns the stored server nonce")
        void firstConsumeReturnsNonce() {
            NonceCache cache = new NonceCache();
            cache.put(CLIENT_ID, CLIENT_NONCE, SERVER_NONCE);

            String consumed = cache.consume(CLIENT_ID, CLIENT_NONCE);

            assertThat(consumed).isEqualTo(SERVER_NONCE);
        }

        @Test
        @DisplayName("second consume of the same nonce pair returns null (replay rejected)")
        void secondConsumeReturnsNull() {
            NonceCache cache = new NonceCache();
            cache.put(CLIENT_ID, CLIENT_NONCE, SERVER_NONCE);

            String first = cache.consume(CLIENT_ID, CLIENT_NONCE);
            String second = cache.consume(CLIENT_ID, CLIENT_NONCE);

            assertThat(first).isEqualTo(SERVER_NONCE);
            assertThat(second).isNull();
        }

        @Test
        @DisplayName("consume on a missing nonce pair returns null")
        void consumeMissingReturnsNull() {
            NonceCache cache = new NonceCache();
            assertThat(cache.consume(CLIENT_ID, CLIENT_NONCE)).isNull();
        }

        @Test
        @DisplayName("consume on an expired nonce returns null")
        void consumeExpiredReturnsNull() throws InterruptedException {
            // 50ms TTL lets us test expiry deterministically without a long sleep.
            NonceCache cache = new NonceCache(10_000, 50L);
            cache.put(CLIENT_ID, CLIENT_NONCE, SERVER_NONCE);

            Thread.sleep(120L);

            assertThat(cache.consume(CLIENT_ID, CLIENT_NONCE)).isNull();
        }

        @Test
        @DisplayName("put overwrites a stale entry, making the old nonce unusable")
        void putOverwritesOldEntry() {
            NonceCache cache = new NonceCache();
            cache.put(CLIENT_ID, CLIENT_NONCE, SERVER_NONCE);

            // Overwrite with a different server nonce.
            String newServerNonce = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            cache.put(CLIENT_ID, CLIENT_NONCE, newServerNonce);

            assertThat(cache.consume(CLIENT_ID, CLIENT_NONCE)).isEqualTo(newServerNonce);
        }

        @Test
        @DisplayName("two concurrent consumes of the same nonce pair: exactly one wins")
        void concurrentConsumeExactlyOneWinner() throws InterruptedException {
            NonceCache cache = new NonceCache();
            cache.put(CLIENT_ID, CLIENT_NONCE, SERVER_NONCE);

            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            AtomicInteger nulls = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    String result = cache.consume(CLIENT_ID, CLIENT_NONCE);
                    if (result != null) {
                        winners.incrementAndGet();
                    } else {
                        nulls.incrementAndGet();
                    }
                });
            }

            ready.await(2, TimeUnit.SECONDS);
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            assertThat(winners.get())
                    .as("exactly one of the %d racing consumes should win", threads)
                    .isEqualTo(1);
            assertThat(nulls.get())
                    .as("the other %d should find nothing", threads - 1)
                    .isEqualTo(threads - 1);
        }
    }

    @Nested
    @DisplayName("AuthManager.authenticateChallenge replay rejection")
    class AuthenticateChallengeReplay {

        @Test
        @DisplayName("first authenticate succeeds; replay with same nonce pair returns NC-401")
        void firstAuthSucceedsReplayFails() {
            AuthManager authManager = new AuthManager();
            NonceCache cache = new NonceCache();
            String passwordHash = sha256Hex(PASSWORD);
            authManager.registerClient(new ClientCredentials(CLIENT_ID, passwordHash));

            cache.put(CLIENT_ID, CLIENT_NONCE, SERVER_NONCE);
            String hmac = AuthManager.computeChallengeHmac(passwordHash, SERVER_NONCE, CLIENT_NONCE);

            AuthResult first = authManager.authenticateChallenge(
                    CLIENT_ID, CLIENT_NONCE, hmac, cache, "127.0.0.1");
            assertThat(first.isSuccess()).isTrue();

            // Replay: same nonce pair, same HMAC. The cache entry is consumed,
            // so the lookup misses and auth fails with NC-401.
            AuthResult replay = authManager.authenticateChallenge(
                    CLIENT_ID, CLIENT_NONCE, hmac, cache, "127.0.0.1");
            assertThat(replay.isSuccess()).isFalse();
            assertThat(replay.getErrorCode()).isEqualTo("NC-401");
        }

        @Test
        @DisplayName("authenticate rejects when no challenge was ever issued")
        void authFailsWithNoChallengeIssued() {
            AuthManager authManager = new AuthManager();
            NonceCache cache = new NonceCache();
            String passwordHash = sha256Hex(PASSWORD);
            authManager.registerClient(new ClientCredentials(CLIENT_ID, passwordHash));

            // No put() — no pending challenge.
            String hmac = AuthManager.computeChallengeHmac(passwordHash, SERVER_NONCE, CLIENT_NONCE);
            AuthResult result = authManager.authenticateChallenge(
                    CLIENT_ID, CLIENT_NONCE, hmac, cache, "127.0.0.1");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("NC-401");
        }

        @Test
        @DisplayName("authenticate rejects an expired challenge")
        void authFailsWithExpiredChallenge() throws InterruptedException {
            AuthManager authManager = new AuthManager();
            NonceCache cache = new NonceCache(10_000, 50L);
            String passwordHash = sha256Hex(PASSWORD);
            authManager.registerClient(new ClientCredentials(CLIENT_ID, passwordHash));

            cache.put(CLIENT_ID, CLIENT_NONCE, SERVER_NONCE);
            Thread.sleep(120L);

            String hmac = AuthManager.computeChallengeHmac(passwordHash, SERVER_NONCE, CLIENT_NONCE);
            AuthResult result = authManager.authenticateChallenge(
                    CLIENT_ID, CLIENT_NONCE, hmac, cache, "127.0.0.1");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("NC-401");
        }

        @Test
        @DisplayName("authenticate rejects when the echoed clientNonce does not match the init")
        void authFailsWithMismatchedClientNonce() {
            AuthManager authManager = new AuthManager();
            NonceCache cache = new NonceCache();
            String passwordHash = sha256Hex(PASSWORD);
            authManager.registerClient(new ClientCredentials(CLIENT_ID, passwordHash));

            // Challenge issued under CLIENT_NONCE, but the authenticate packet
            // echoes a different nonce — the cache lookup misses.
            cache.put(CLIENT_ID, CLIENT_NONCE, SERVER_NONCE);
            String otherNonce = "11111111111111111111111111111111";
            String hmac = AuthManager.computeChallengeHmac(passwordHash, SERVER_NONCE, otherNonce);
            AuthResult result = authManager.authenticateChallenge(
                    CLIENT_ID, otherNonce, hmac, cache, "127.0.0.1");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("NC-401");
        }

        @Test
        @DisplayName("authenticate rejects when the HMAC is wrong (right nonce pair)")
        void authFailsWithWrongHmac() {
            AuthManager authManager = new AuthManager();
            NonceCache cache = new NonceCache();
            String passwordHash = sha256Hex(PASSWORD);
            authManager.registerClient(new ClientCredentials(CLIENT_ID, passwordHash));

            cache.put(CLIENT_ID, CLIENT_NONCE, SERVER_NONCE);
            // A bogus HMAC — 64 hex chars but not the expected value.
            String bogusHmac = "deadbeef".repeat(8);
            AuthResult result = authManager.authenticateChallenge(
                    CLIENT_ID, CLIENT_NONCE, bogusHmac, cache, "127.0.0.1");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("NC-401");
            // The nonce must still have been consumed so the replay window
            // is not reopened after a wrong-HMAC attempt.
            assertThat(cache.consume(CLIENT_ID, CLIENT_NONCE)).isNull();
        }
    }
}
