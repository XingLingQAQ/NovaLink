package com.nova.link.announcement;

/**
 * Defines the types of announcements in the NovaLink system.
 * 
 * Requirements: 14.1, 14.2, 14.3
 */
public enum AnnouncementType {
    /**
     * One-time announcement sent immediately.
     * Triggered by admin command: /nc announce <channelId> <content>
     */
    IMMEDIATE,

    /**
     * Announcement sent when a player joins a channel.
     * Configured per-channel.
     */
    JOIN,

    /**
     * Scheduled announcement sent according to a Cron expression.
     * Supports periodic announcements.
     */
    SCHEDULED;

    /**
     * Maps this type to its persistence / REST contract value.
     * {@code SCHEDULED} is stored and exposed as {@code CRON};
     * {@code IMMEDIATE} maps to {@code INSTANT} (never persisted).
     *
     * @return the external string value
     */
    public String dbValue() {
        switch (this) {
            case SCHEDULED:
                return "CRON";
            case JOIN:
                return "JOIN";
            case IMMEDIATE:
            default:
                return "INSTANT";
        }
    }

    /**
     * Parses an external (database or REST) type value.
     *
     * @param value the external value ("JOIN", "CRON", "INSTANT"; case-insensitive)
     * @return the matching type, or null when unrecognized
     */
    public static AnnouncementType fromDbValue(String value) {
        if (value == null) {
            return null;
        }
        switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "CRON":
            case "SCHEDULED":
                return SCHEDULED;
            case "JOIN":
                return JOIN;
            case "INSTANT":
            case "IMMEDIATE":
                return IMMEDIATE;
            default:
                return null;
        }
    }
}
