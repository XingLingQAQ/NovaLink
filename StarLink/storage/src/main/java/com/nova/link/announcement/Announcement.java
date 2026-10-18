package com.nova.link.announcement;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents an announcement in the NovaLink system.
 * Announcements can be one-time, join-triggered, or scheduled (Cron).
 * 
 * Requirements: 14.1, 14.2, 14.3
 */
public class Announcement {

    /** Unique identifier for the announcement */
    private final String id;

    /** Channel ID this announcement belongs to (null for cross-channel broadcast) */
    private final String channelId;

    /** The announcement content */
    private String content;

    /** Type of announcement */
    private final AnnouncementType type;

    /** Cron expression for scheduled announcements (null for non-scheduled) */
    private String cronExpression;

    /** UUID of the creator */
    private final UUID creatorId;

    /** Client ID of the creator (for scope validation) */
    private final String creatorClientId;

    /** Creation timestamp */
    private final long createdAt;

    /** Whether this announcement is enabled */
    private boolean enabled;

    /**
     * Creates a new announcement.
     *
     * @param id the unique announcement ID
     * @param channelId the channel ID (null for cross-channel)
     * @param content the announcement content
     * @param type the announcement type
     * @param creatorId the creator's UUID
     * @param creatorClientId the creator's client ID
     */
    public Announcement(String id, String channelId, String content, 
                        AnnouncementType type, UUID creatorId, String creatorClientId) {
        this(id, channelId, content, type, creatorId, creatorClientId,
                System.currentTimeMillis(), true);
    }

    /**
     * Restores an announcement from persistent storage, preserving the original
     * creation timestamp and enabled flag.
     *
     * @param id the unique announcement ID
     * @param channelId the channel ID (null for cross-channel)
     * @param content the announcement content
     * @param type the announcement type
     * @param creatorId the creator's UUID (may be null for restored rows)
     * @param creatorClientId the creator's client ID (may be null)
     * @param createdAt original creation timestamp (epoch millis)
     * @param enabled whether the announcement is currently enabled
     */
    public Announcement(String id, String channelId, String content,
                        AnnouncementType type, UUID creatorId, String creatorClientId,
                        long createdAt, boolean enabled) {
        this.id = Objects.requireNonNull(id, "Announcement ID cannot be null");
        this.channelId = channelId;
        this.content = Objects.requireNonNull(content, "Content cannot be null");
        this.type = Objects.requireNonNull(type, "Type cannot be null");
        this.creatorId = creatorId;
        this.creatorClientId = creatorClientId;
        this.createdAt = createdAt;
        this.enabled = enabled;
    }

    // Getters and setters

    public String getId() {
        return id;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = Objects.requireNonNull(content, "Content cannot be null");
    }

    public AnnouncementType getType() {
        return type;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public String getCreatorClientId() {
        return creatorClientId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Checks if this is a scheduled announcement.
     *
     * @return true if this announcement has a cron expression
     */
    public boolean isScheduled() {
        return type == AnnouncementType.SCHEDULED && cronExpression != null && !cronExpression.isEmpty();
    }

    /**
     * Checks if this is a join announcement.
     *
     * @return true if this announcement triggers on player join
     */
    public boolean isJoinAnnouncement() {
        return type == AnnouncementType.JOIN;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Announcement that = (Announcement) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Announcement{" +
                "id='" + id + '\'' +
                ", channelId='" + channelId + '\'' +
                ", type=" + type +
                ", enabled=" + enabled +
                '}';
    }
}
