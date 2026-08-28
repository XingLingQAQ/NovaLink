package com.nova.link.moderation;

/**
 * Shared duration-rendering helpers for the moderation subsystem.
 *
 * <p>Two distinct call-site conventions coexist in the codebase, and they
 * cannot collapse into one method because the value {@code 0} means different
 * things depending on the caller:
 *
 * <ul>
 *   <li>{@link #describeDuration(long)} — input is the <em>set</em> duration
 *       of a mute/ban (the value the operator chose). {@code 0} (and any
 *       negative, which never occurs in practice) means <em>permanent</em>.
 *       Used by {@code MuteManager} / {@code BanManager} notification messages
 *       and by {@code ConsoleCommandHandler} success lines, where the
 *       operator just chose {@code 0 = perm}.</li>
 *   <li>{@link #describeRemaining(long)} — input is the <em>remaining</em>
 *       milliseconds from {@code MuteInfo.getRemainingTime()} /
 *       {@code BanInfo.getRemainingTime()}. Those return {@code -1} for
 *       permanent entries, {@code 0} for an entry that has just expired, and a
 *       positive value while still active. So {@code -1 -> "permanent"},
 *       {@code 0 -> "expired"}. This is the exact semantics the old
 *       {@code ConsoleCommandHandler.describeDuration} relied on for its
 *       {@code mutes}/{@code bans} listing call sites.</li>
 * </ul>
 *
 * <p>Merging the two into one method would force either every
 * {@code remaining == 0} listing to read "permanent" (breaking the console
 * {@code mutes}/{@code bans} listing for just-expired entries) or every
 * set-duration {@code 0} notification to read "expired" (breaking the manager
 * notification for permanent mutes/bans). A single method cannot honour both
 * conventions, so two methods are provided.
 */
public final class DurationUtils {

    private DurationUtils() {
        // utility class
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
