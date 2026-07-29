package com.nova.link.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-focused tests for {@link AuthManager}:
 * hashing, constant-time compare, ban interaction, and failure isolation.
 */
@DisplayName("AuthManager security")
class AuthManagerSecurityTest {

    private AuthManager authManager;
    private IpBanManager ipBanManager;

    @BeforeEach
    void setUp() {
        ipBanManager = new IpBanManager(3, 60_000);
        authManager = new AuthManager(ipBanManager);
        authManager.registerClient(new ClientCredentials(
                "Survival_Server",
                AuthManager.hashPassword("s3cret"),
                "Survival_Server"
        ));
    }

    @Nested
    @DisplayName("hashPassword")
    class Hashing {

        @Test
        @DisplayName("SHA-256 produces 64 hex chars")
        void lengthAndCharset() {
            String hash = AuthManager.hashPassword("s3cret");
            assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("same input yields same hash")
        void deterministic() {
            assertThat(AuthManager.hashPassword("abc"))
                    .isEqualTo(AuthManager.hashPassword("abc"));
        }

        @Test
        @DisplayName("different inputs yield different hashes")
        void differentInputs() {
            assertThat(AuthManager.hashPassword("abc"))
                    .isNotEqualTo(AuthManager.hashPassword("abd"));
        }

        @Test
        @DisplayName("null password throws")
        void nullThrows() {
            assertThatThrownBy(() -> AuthManager.hashPassword(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("known vector for empty string")
        void emptyStringKnownVector() {
            // SHA-256("") =
            // e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            assertThat(AuthManager.hashPassword(""))
                    .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        }
    }

    @Nested
    @DisplayName("constantTimeEqualsIgnoreCase")
    class ConstantTime {

        @Test
        @DisplayName("equal hex hashes match case-insensitively")
        void equalIgnoreCase() {
            assertThat(AuthManager.constantTimeEqualsIgnoreCase("AbCdEf", "abcdef")).isTrue();
        }

        @Test
        @DisplayName("different lengths are not equal")
        void differentLengths() {
            assertThat(AuthManager.constantTimeEqualsIgnoreCase("abc", "ab")).isFalse();
        }

        @Test
        @DisplayName("same length different content")
        void sameLengthDifferent() {
            assertThat(AuthManager.constantTimeEqualsIgnoreCase("aaaa", "aaab")).isFalse();
        }

        @Test
        @DisplayName("null handling")
        void nulls() {
            assertThat(AuthManager.constantTimeEqualsIgnoreCase(null, null)).isTrue();
            assertThat(AuthManager.constantTimeEqualsIgnoreCase(null, "a")).isFalse();
            assertThat(AuthManager.constantTimeEqualsIgnoreCase("a", null)).isFalse();
        }
    }

    @Nested
    @DisplayName("authenticate")
    class Authenticate {

        @Test
        @DisplayName("success with correct hash")
        void success() {
            AuthResult result = authManager.authenticate(
                    "Survival_Server",
                    AuthManager.hashPassword("s3cret"),
                    "10.0.0.1"
            );
            assertThat(result.isSuccess()).isTrue();
            assertThat(ipBanManager.getFailureCount("10.0.0.1")).isZero();
        }

        @Test
        @DisplayName("success with plain password overload")
        void plainPassword() {
            AuthResult result = authManager.authenticateWithPlainPassword(
                    "Survival_Server", "s3cret", "10.0.0.2");
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("wrong password returns unauthorized and records failure")
        void wrongPassword() {
            AuthResult result = authManager.authenticate(
                    "Survival_Server",
                    AuthManager.hashPassword("wrong"),
                    "10.0.0.3"
            );
            assertThat(result.isSuccess()).isFalse();
            assertThat(ipBanManager.getFailureCount("10.0.0.3")).isEqualTo(1);
        }

        @Test
        @DisplayName("unknown user does not reveal whether user exists")
        void unknownUserGenericMessage() {
            AuthResult result = authManager.authenticate(
                    "NoSuchServer",
                    AuthManager.hashPassword("x"),
                    "10.0.0.4"
            );
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).containsIgnoringCase("Invalid credentials");
        }

        @Test
        @DisplayName("three failures ban the IP")
        void banAfterThreshold() {
            String ip = "10.0.0.5";
            for (int i = 0; i < 3; i++) {
                authManager.authenticate("Survival_Server", AuthManager.hashPassword("bad"), ip);
            }
            assertThat(ipBanManager.isBanned(ip)).isTrue();

            // Even correct credentials are rejected while banned
            AuthResult result = authManager.authenticate(
                    "Survival_Server",
                    AuthManager.hashPassword("s3cret"),
                    ip
            );
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).containsIgnoringCase("banned");
        }

        @Test
        @DisplayName("successful auth clears prior failures")
        void successClearsFailures() {
            String ip = "10.0.0.6";
            authManager.authenticate("Survival_Server", AuthManager.hashPassword("bad"), ip);
            authManager.authenticate("Survival_Server", AuthManager.hashPassword("bad"), ip);
            assertThat(ipBanManager.getFailureCount(ip)).isEqualTo(2);

            authManager.authenticate("Survival_Server", AuthManager.hashPassword("s3cret"), ip);
            assertThat(ipBanManager.getFailureCount(ip)).isZero();
        }

        @Test
        @DisplayName("empty username/passwordHash rejected")
        void emptyInputs() {
            assertThat(authManager.authenticate("", "hash", "1.1.1.1").isSuccess()).isFalse();
            assertThat(authManager.authenticate("Survival_Server", "", "1.1.1.1").isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("IpBanManager cleanup")
    class BanCleanup {

        @Test
        @DisplayName("cleanupExpiredBans removes only expired entries")
        void cleanup() {
            // Use a short but non-racey ban window: assert active ban first,
            // then wait past expiry before cleanup checks.
            IpBanManager shortBan = new IpBanManager(1, 50);
            shortBan.recordFailure("9.9.9.9");
            assertThat(shortBan.isBanned("9.9.9.9")).isTrue();
            assertThat(shortBan.getTrackedBanCount()).isEqualTo(1);

            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // isBanned itself expires lazily
            assertThat(shortBan.isBanned("9.9.9.9")).isFalse();

            shortBan.recordFailure("8.8.8.8");
            assertThat(shortBan.isBanned("8.8.8.8")).isTrue();
            assertThat(shortBan.getTrackedBanCount()).isGreaterThanOrEqualTo(1);

            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int removed = shortBan.cleanupExpiredBans();
            assertThat(removed).isGreaterThanOrEqualTo(1);
            assertThat(shortBan.getTrackedBanCount()).isZero();
        }
    }
}
