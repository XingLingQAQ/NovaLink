package com.nova.chat.client.network;

/**
 * Strategy for computing reconnect delays after a connection loss.
 *
 * <p>Implementations must be thread-safe if shared across clients. The policy
 * itself is pure computation — scheduling is left to the platform module.
 */
public interface ReconnectPolicy {

    /**
     * Decision returned by {@link #nextAttempt(int)}.
     *
     * @param shouldRetry whether another attempt should be made
     * @param delaySeconds delay before the next attempt (0 when {@code shouldRetry} is false)
     * @param attemptNumber the 1-based attempt number this decision applies to
     */
    record Decision(boolean shouldRetry, int delaySeconds, int attemptNumber) {
        public static Decision stop(int attemptNumber) {
            return new Decision(false, 0, attemptNumber);
        }

        public static Decision retry(int delaySeconds, int attemptNumber) {
            if (delaySeconds < 0) {
                throw new IllegalArgumentException("delaySeconds must be >= 0");
            }
            return new Decision(true, delaySeconds, attemptNumber);
        }
    }

    /**
     * Computes the next reconnect decision for the given 1-based attempt number.
     *
     * @param attemptNumber 1 for the first reconnect after a disconnect
     * @return decision describing whether to retry and after how long
     */
    Decision nextAttempt(int attemptNumber);

    /**
     * Maximum number of reconnect attempts allowed by this policy.
     * A value of {@code 0} means reconnection is disabled; negative means unlimited.
     */
    int maxAttempts();

    /**
     * Whether the given attempt number is still within the allowed range.
     */
    default boolean hasRemainingAttempts(int attemptNumber) {
        int max = maxAttempts();
        if (max < 0) {
            return true;
        }
        return attemptNumber >= 1 && attemptNumber <= max;
    }
}
