package com.nova.link.moderation;

/**
 * Moderation expiration and duration labels.
 * Configured duration 0 means permanent; remaining time 0 means expired.
 */
public final class DurationUtils {

    private DurationUtils() {
    }

    public static long expirationTime(long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("Duration must be zero or positive (0 = permanent).");
        }
        if (durationMs == 0) {
            return 0L;
        }
        try {
            return Math.addExact(System.currentTimeMillis(), durationMs);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Duration is too large. Choose a shorter duration.", e);
        }
    }

    /**
     * Renders a <em>set</em> duration. {@code ms <= 0} means permanent.
     *
     * @param ms the configured duration in milliseconds (0 = permanent)
     * @return a short human-readable label
     */
    public static String describeDuration(long ms) {
        if (ms <= 0) {
            return "permanent";
        }
        long secs = ms / 1000;
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        if (secs < 86400) return (secs / 3600) + "h";
        return (secs / 86400) + "d";
    }

    /**
     * Renders a <em>remaining</em> duration.
     * {@code ms < 0} means permanent, {@code ms == 0} means expired.
     *
     * <p>This preserves the observable semantics of the old
     * {@code ConsoleCommandHandler.describeDuration}: a permanent entry
     * (remaining {@code -1}) still renders as "permanent", while an entry
     * whose remaining time has hit {@code 0} renders as "expired".
     *
     * @param ms the remaining milliseconds, or -1 for permanent
     * @return a short human-readable label
     */
    public static String describeRemaining(long ms) {
        if (ms < 0) {
            return "permanent";
        }
        if (ms == 0) {
            return "expired";
        }
        long secs = ms / 1000;
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        if (secs < 86400) return (secs / 3600) + "h";
        return (secs / 86400) + "d";
    }
}
