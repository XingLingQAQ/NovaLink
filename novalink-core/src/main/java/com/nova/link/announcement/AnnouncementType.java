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
    SCHEDULED
}
