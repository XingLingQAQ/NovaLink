package com.nova.chat.client.format;

import com.nova.chat.client.i18n.I18n;

/**
 * Formats a duration (given as seconds or a seconds string) as a short
 * localized phrase such as {@code "60秒"} / {@code "60s"},
 * {@code "5分钟"} / {@code "5m"}, {@code "1小时"} / {@code "1h"},
 * {@code "2天"} / {@code "2d"} (zh_CN / en_US).
 *
 * <p>Pure, no platform APIs. Used by the KICK/MUTE target-side notification
 * (BUG-H1) across platforms so each handler does not re-implement the same
 * threshold logic. Returns the localized "unknown duration" phrase
 * (key {@code notice.duration.unknown}) when the input is absent or
 * unparseable — the same fallback the platform handlers previously used.
 */
public final class DurationFormatter {

    private DurationFormatter() {
    }

    /**
     * @return the localized "unknown duration" phrase (e.g. "一段时间" / "a while")
     */
    public static String unknown() {
        return I18n.tr("notice.duration.unknown");
    }

    /**
     * Formats a duration given as a seconds string, or the unknown phrase if
     * absent or unparseable.
     *
     * @param durationSeconds the seconds value as a string (e.g. the bukkit
     *        MuteCommand {@code "duration"} packet extra), or {@code null}/blank
     * @return a short localized duration phrase
     */
    public static String formatSeconds(String durationSeconds) {
        if (durationSeconds == null || durationSeconds.isEmpty()) {
            return unknown();
        }
        try {
            return formatSeconds(Long.parseLong(durationSeconds));
        } catch (NumberFormatException e) {
            return unknown();
        }
    }

    /**
     * Formats a duration given as a number of seconds, or the unknown phrase
     * when non-positive (treated as unknown).
     *
     * @param seconds the duration in seconds
     * @return a short localized duration phrase
     */
    public static String formatSeconds(long seconds) {
        if (seconds <= 0) {
            return unknown();
        }
        if (seconds < 60) {
            return I18n.tr("duration.seconds", seconds);
        } else if (seconds < 3600) {
            return I18n.tr("duration.minutes", seconds / 60);
        } else if (seconds < 86400) {
            return I18n.tr("duration.hours", seconds / 3600);
        } else {
            return I18n.tr("duration.days", seconds / 86400);
        }
    }
}
