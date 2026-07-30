package com.nova.chat.client.format;

/**
 * Formats a duration (given as seconds or a seconds string) as a short Chinese
 * phrase such as {@code "60秒"}, {@code "5分钟"}, {@code "1小时"}, {@code "2天"}.
 *
 * <p>Pure, no platform APIs. Used by the KICK/MUTE target-side notification
 * (BUG-H1) across platforms so each handler does not re-implement the same
 * threshold logic. Returns {@code "一段时间"} when the input is absent or
 * unparseable — the same fallback the platform handlers previously used.
 */
public final class DurationFormatter {

    /** Returned when the duration is unknown / unparseable. */
    public static final String UNKNOWN = "一段时间";

    private DurationFormatter() {
    }

    /**
     * Formats a duration given as a seconds string, or {@link #UNKNOWN} if
     * absent or unparseable.
     *
     * @param durationSeconds the seconds value as a string (e.g. the bukkit
     *        MuteCommand {@code "duration"} packet extra), or {@code null}/blank
     * @return a short Chinese duration phrase
     */
    public static String formatSeconds(String durationSeconds) {
        if (durationSeconds == null || durationSeconds.isEmpty()) {
            return UNKNOWN;
        }
        try {
            return formatSeconds(Long.parseLong(durationSeconds));
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /**
     * Formats a duration given as a number of seconds, or {@link #UNKNOWN}
     * when non-positive (treated as unknown).
     *
     * @param seconds the duration in seconds
     * @return a short Chinese duration phrase
     */
    public static String formatSeconds(long seconds) {
        if (seconds <= 0) {
            return UNKNOWN;
        }
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            return (seconds / 60) + "分钟";
        } else if (seconds < 86400) {
            return (seconds / 3600) + "小时";
        } else {
            return (seconds / 86400) + "天";
        }
    }
}
