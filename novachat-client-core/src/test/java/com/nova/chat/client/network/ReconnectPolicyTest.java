package com.nova.chat.client.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ReconnectPolicy} and
 * {@code ExponentialBackoffReconnectPolicy}, covering default parameters,
 * backoff progression, and bound clamping.
 */
@DisplayName("ReconnectPolicy / ExponentialBackoffReconnectPolicy")
class ReconnectPolicyTest {

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("defaults match historical NetworkClient constants")
        void defaultConstants() {
            ExponentialBackoffReconnectPolicy policy = ExponentialBackoffReconnectPolicy.defaults();
            assertThat(policy.maxAttempts()).isEqualTo(10);
            assertThat(policy.initialDelaySeconds()).isEqualTo(1);
            assertThat(policy.maxDelaySeconds()).isEqualTo(30);
        }

        @Test
        @DisplayName("ClientConnectionConfig.toReconnectPolicy mirrors builder values")
        void fromConfig() {
            ClientConnectionConfig config = ClientConnectionConfig.builder()
                    .host("backend.example")
                    .maxReconnectAttempts(5)
                    .initialReconnectDelaySeconds(2)
                    .maxReconnectDelaySeconds(16)
                    .build();

            ReconnectPolicy policy = config.toReconnectPolicy();
            assertThat(policy).isInstanceOf(ExponentialBackoffReconnectPolicy.class);
            assertThat(policy.maxAttempts()).isEqualTo(5);

            ReconnectPolicy.Decision first = policy.nextAttempt(1);
            assertThat(first.shouldRetry()).isTrue();
            assertThat(first.delaySeconds()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("construction validation")
    class Construction {

        @Test
        @DisplayName("rejects negative maxAttempts")
        void rejectsNegativeMaxAttempts() {
            assertThatThrownBy(() -> new ExponentialBackoffReconnectPolicy(-1, 1, 30))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxAttempts");
        }

        @Test
        @DisplayName("rejects negative delays")
        void rejectsNegativeDelays() {
            assertThatThrownBy(() -> new ExponentialBackoffReconnectPolicy(10, -1, 30))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("initialDelaySeconds");
            assertThatThrownBy(() -> new ExponentialBackoffReconnectPolicy(10, 1, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxDelaySeconds");
        }

        @Test
        @DisplayName("rejects maxDelay < initialDelay")
        void rejectsMaxLessThanInitial() {
            assertThatThrownBy(() -> new ExponentialBackoffReconnectPolicy(10, 10, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxDelaySeconds");
        }

        @Test
        @DisplayName("allows maxAttempts = 0 (reconnect disabled)")
        void allowsZeroMaxAttempts() {
            ExponentialBackoffReconnectPolicy policy =
                    new ExponentialBackoffReconnectPolicy(0, 1, 30);
            assertThat(policy.nextAttempt(1).shouldRetry()).isFalse();
        }
    }

    @Nested
    @DisplayName("delay schedule")
    class DelaySchedule {

        @ParameterizedTest(name = "attempt {0} -> {1}s")
        @CsvSource({
                "1, 1",
                "2, 2",
                "3, 4",
                "4, 8",
                "5, 16",
                "6, 30",
                "7, 30",
                "10, 30"
        })
        @DisplayName("default exponential series capped at 30s")
        void defaultSeries(int attempt, int expectedDelay) {
            ExponentialBackoffReconnectPolicy policy = ExponentialBackoffReconnectPolicy.defaults();
            assertThat(policy.computeDelaySeconds(attempt)).isEqualTo(expectedDelay);
            ReconnectPolicy.Decision decision = policy.nextAttempt(attempt);
            assertThat(decision.shouldRetry()).isTrue();
            assertThat(decision.delaySeconds()).isEqualTo(expectedDelay);
            assertThat(decision.attemptNumber()).isEqualTo(attempt);
        }

        @Test
        @DisplayName("custom initial delay scales the series")
        void customInitial() {
            ExponentialBackoffReconnectPolicy policy =
                    new ExponentialBackoffReconnectPolicy(8, 3, 40);
            assertThat(policy.computeDelaySeconds(1)).isEqualTo(3);
            assertThat(policy.computeDelaySeconds(2)).isEqualTo(6);
            assertThat(policy.computeDelaySeconds(3)).isEqualTo(12);
            assertThat(policy.computeDelaySeconds(4)).isEqualTo(24);
            assertThat(policy.computeDelaySeconds(5)).isEqualTo(40); // capped
        }

        @Test
        @DisplayName("initial delay of zero always yields zero delay")
        void zeroInitial() {
            ExponentialBackoffReconnectPolicy policy =
                    new ExponentialBackoffReconnectPolicy(5, 0, 0);
            assertThat(policy.computeDelaySeconds(1)).isZero();
            assertThat(policy.computeDelaySeconds(4)).isZero();
        }

        @Test
        @DisplayName("overflow-safe for very large attempt numbers")
        void overflowSafe() {
            ExponentialBackoffReconnectPolicy policy =
                    new ExponentialBackoffReconnectPolicy(100, 1, 30);
            assertThat(policy.computeDelaySeconds(40)).isEqualTo(30);
            assertThat(policy.computeDelaySeconds(100)).isEqualTo(30);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -99})
        @DisplayName("computeDelaySeconds rejects attempt < 1")
        void rejectsBadAttempt(int attempt) {
            ExponentialBackoffReconnectPolicy policy = ExponentialBackoffReconnectPolicy.defaults();
            assertThatThrownBy(() -> policy.computeDelaySeconds(attempt))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("attemptNumber");
            assertThatThrownBy(() -> policy.nextAttempt(attempt))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("attempt limits")
    class AttemptLimits {

        @Test
        @DisplayName("nextAttempt stops after maxAttempts")
        void stopsAfterMax() {
            ExponentialBackoffReconnectPolicy policy =
                    new ExponentialBackoffReconnectPolicy(3, 1, 30);

            assertThat(policy.nextAttempt(1).shouldRetry()).isTrue();
            assertThat(policy.nextAttempt(2).shouldRetry()).isTrue();
            assertThat(policy.nextAttempt(3).shouldRetry()).isTrue();

            ReconnectPolicy.Decision stop = policy.nextAttempt(4);
            assertThat(stop.shouldRetry()).isFalse();
            assertThat(stop.delaySeconds()).isZero();
            assertThat(stop.attemptNumber()).isEqualTo(4);
        }

        @Test
        @DisplayName("hasRemainingAttempts respects max")
        void hasRemaining() {
            ReconnectPolicy policy = new ExponentialBackoffReconnectPolicy(2, 1, 10);
            assertThat(policy.hasRemainingAttempts(1)).isTrue();
            assertThat(policy.hasRemainingAttempts(2)).isTrue();
            assertThat(policy.hasRemainingAttempts(3)).isFalse();
            assertThat(policy.hasRemainingAttempts(0)).isFalse();
        }

        @Test
        @DisplayName("Decision factory helpers")
        void decisionFactories() {
            ReconnectPolicy.Decision stop = ReconnectPolicy.Decision.stop(5);
            assertThat(stop.shouldRetry()).isFalse();
            assertThat(stop.delaySeconds()).isZero();
            assertThat(stop.attemptNumber()).isEqualTo(5);

            ReconnectPolicy.Decision retry = ReconnectPolicy.Decision.retry(7, 2);
            assertThat(retry.shouldRetry()).isTrue();
            assertThat(retry.delaySeconds()).isEqualTo(7);

            assertThatThrownBy(() -> ReconnectPolicy.Decision.retry(-1, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("ClientConnectionConfig")
    class ConfigPojo {

        @Test
        @DisplayName("builder defaults and equality")
        void builderDefaults() {
            ClientConnectionConfig a = ClientConnectionConfig.builder()
                    .host("127.0.0.1")
                    .username("server-a")
                    .password("secret")
                    .build();
            ClientConnectionConfig b = ClientConnectionConfig.builder()
                    .host("127.0.0.1")
                    .username("server-a")
                    .password("secret")
                    .build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
            assertThat(a.getPort()).isEqualTo(ClientConnectionConfig.DEFAULT_PORT);
            assertThat(a.isAutoReconnect()).isTrue();
            assertThat(a.toString()).contains("host='127.0.0.1'").contains("password=***")
                    .doesNotContain("secret");
        }

        @Test
        @DisplayName("builder validation")
        void builderValidation() {
            assertThatThrownBy(() -> ClientConnectionConfig.builder().host("").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host");
            assertThatThrownBy(() -> ClientConnectionConfig.builder().port(0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("port");
            assertThatThrownBy(() -> ClientConnectionConfig.builder().port(70000).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ClientConnectionConfig.builder()
                    .host("h")
                    .initialReconnectDelaySeconds(10)
                    .maxReconnectDelaySeconds(5)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxReconnectDelaySeconds");
        }

        @Test
        @DisplayName("toBuilder round-trips")
        void toBuilderRoundTrip() {
            ClientConnectionConfig original = ClientConnectionConfig.builder()
                    .host("backend")
                    .port(9999)
                    .username("u")
                    .password("p")
                    .connectTimeoutMs(2500)
                    .maxReconnectAttempts(4)
                    .initialReconnectDelaySeconds(2)
                    .maxReconnectDelaySeconds(20)
                    .requestTimeoutMs(3000L)
                    .autoReconnect(false)
                    .build();

            ClientConnectionConfig copy = original.toBuilder().build();
            assertThat(copy).isEqualTo(original);
            assertThat(copy.getConnectTimeoutMs()).isEqualTo(2500);
            assertThat(copy.isAutoReconnect()).isFalse();
        }

        @Test
        @DisplayName("null username/password become empty strings")
        void nullCredentials() {
            ClientConnectionConfig config = ClientConnectionConfig.builder()
                    .host("h")
                    .username(null)
                    .password(null)
                    .build();
            assertThat(config.getUsername()).isEmpty();
            assertThat(config.getPassword()).isEmpty();
        }
    }
}
