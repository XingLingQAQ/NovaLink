package com.nova.link.database;

/**
 * Represents a persisted panel notification.
 *
 * <p>Notifications are created by backend events (ban/mute/kick/reload/etc.)
 * and surfaced to the web panel. The {@code read} flag is mutable so the panel
 * can mark individual notifications as read.
 *
 * Requirements: notification persistence feature
 */
public class Notification {

    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_WARNING = "warning";
    public static final String LEVEL_ERROR = "error";

    private final long id;
    private final String title;
    private final String message;
    private final String level;
    private final long createdAt;
    private boolean read;

    public Notification(String title, String message, String level) {
        this.id = 0; // assigned by the store/provider on persist
        this.title = title;
        this.message = message;
        this.level = level != null ? level : LEVEL_INFO;
        this.createdAt = System.currentTimeMillis();
        this.read = false;
    }

    public Notification(long id, String title, String message, String level, long createdAt, boolean read) {
        this.id = id;
        this.title = title;
        this.message = message;
        this.level = level != null ? level : LEVEL_INFO;
        this.createdAt = createdAt;
        this.read = read;
    }

    public long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getLevel() {
        return level;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    @Override
    public String toString() {
        return "Notification{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", level='" + level + '\'' +
                ", createdAt=" + createdAt +
                ", read=" + read +
                '}';
    }
}
