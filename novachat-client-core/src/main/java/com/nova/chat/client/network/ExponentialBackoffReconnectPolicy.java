package com.nova.chat.client.network;

/**
 * Exponential backoff reconnect policy matching the historical client behaviour:
 * delays of {@code initialDelay * 2^(attempt-1)} seconds, capped at
 * {@code maxDelaySeconds}, for up to {@code maxAttempts} tries.
 *
 * <p>Example with defaults (initial=1, maxDelay=30, maxAttempts=10):
 * 1s, 2s, 4s, 8s, 16s, 30s, 30s, 30s, 30s, 30s.
 */
public final class ExponentialBackoffReconnectPolicy implements ReconnectPolicy {

    private final int maxAttempts;
    private final int initialDelaySeconds;
    private final int maxDelaySeconds;

    public ExponentialBackoffReconnectPolicy(
            int maxAttempts,
            int initialDelaySeconds,
            int maxDelaySeconds
    ) {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0");
        }
        if (initialDelaySeconds < 0) {
            throw new IllegalArgumentException("initialDelaySeconds must be >= 0");
        }
        if (maxDelaySeconds < 0) {
            throw new IllegalArgumentException("maxDelaySeconds must be >= 0");
        }
        if (maxDelaySeconds < initialDelaySeconds) {
            throw new IllegalArgumentException(
                    "maxDelaySeconds must be >= initialDelaySeconds");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelaySeconds = initialDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
    }

    /**
     * Defaults matching existing platform NetworkClient constants:
     * 10 attempts, 1s initial, 30s cap.
     */
    public static ExponentialBackoffReconnectPolicy defaults() {
        return new ExponentialBackoffReconnectPolicy(
                ClientConnectionConfig.DEFAULT_MAX_RECONNECT_ATTEMPTS,
                ClientConnectionConfig.DEFAULT_INITIAL_RECONNECT_DELAY_SECONDS,
                ClientConnectionConfig.DEFAULT_MAX_RECONNECT_DELAY_SECONDS
        );
    }

    @Override
    public Decision nextAttempt(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException(
                    "attemptNumber must be >= 1, got " + attemptNumber);
        }
        if (maxAttempts == 0 || attemptNumber > maxAttempts) {
            return Decision.stop(attemptNumber);
        }
        int delay = computeDelaySeconds(attemptNumber);
        return Decision.retry(delay, attemptNumber);
    }

    /**
     * Computes the delay for a given attempt without checking attempt limits.
     * Formula: {@code min(initial * 2^(attempt-1), maxDelay)}.
     * Overflow-safe: once the shift would overflow int, the max delay is returned.
     */
    public int computeDelaySeconds(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException(
                    "attemptNumber must be >= 1, got " + attemptNumber);
        }
        if (initialDelaySeconds == 0) {
            return 0;
        }
        // 2^(attempt-1) * initial, capped. Avoid overflow for large attempt numbers.
        int shift = attemptNumber - 1;
        if (shift >= 31) {
            return maxDelaySeconds;
        }
        long multiplied = (long) initialDelaySeconds << shift;
        if (multiplied >= maxDelaySeconds) {
            return maxDelaySeconds;
        }
        return (int) multiplied;
    }

    @Override
    public int maxAttempts() {
        return maxAttempts;
    }

    public int initialDelaySeconds() {
        return initialDelaySeconds;
    }

    public int maxDelaySeconds() {
        return maxDelaySeconds;
    }

    @Override
    public String toString() {
        return "ExponentialBackoffReconnectPolicy{"
                + "maxAttempts=" + maxAttempts
                + ", initialDelaySeconds=" + initialDelaySeconds
                + ", maxDelaySeconds=" + maxDelaySeconds
                + '}';
    }
}
