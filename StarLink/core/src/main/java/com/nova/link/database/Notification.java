package com.nova.link.database;

/**
 * Represents a persisted panel notification.
 *
 * <p>Notifications are created by backend events (ban/mute/kick/reload/etc.)
 * and surfaced to the web panel. The {@code read} flag is mutable so the panel
 * can mark individual notifications as read.
 *
 * <p>PANEL-014: notifications are an immutable event stream. The {@code read}
 * flag is retained as a fallback/broadcast default for migration-period
 * double-read (providers fall back to it when the {@code notification_read}
 * table is absent). Per-user read state lives in {@code notification_read}.
 * The {@code recipient} field is null for broadcast notifications (visible to
 * every admin) or a specific username for directed notifications.
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
    private String recipient;

    public Notification(String title, String message, String level) {
        this.id = 0; // assigned by the store/provider on persist
        this.title = title;
        this.message = message;
        this.level = level != null ? level : LEVEL_INFO;
        this.createdAt = System.currentTimeMillis();
        this.read = false;
        this.recipient = null; // broadcast
    }

    public Notification(long id, String title, String message, String level, long createdAt, boolean read) {
        this(id, title, message, level, createdAt, read, null);
    }

    public Notification(long id, String title, String message, String level, long createdAt, boolean read,
                        String recipient) {
        this.id = id;
        this.title = title;
        this.message = message;
        this.level = level != null ? level : LEVEL_INFO;
        this.createdAt = createdAt;
        this.read = read;
        this.recipient = recipient;
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

    /**
     * Returns the recipient of this notification. A null recipient means
     * broadcast (visible to all admins); a non-null value is the username of
     * the single admin this directed notification is addressed to.
     *
     * @return the recipient username, or null for broadcast
     */
    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    @Override
    public String toString() {
        return "Notification{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", level='" + level + '\'' +
                ", createdAt=" + createdAt +
                ", read=" + read +
                ", recipient='" + recipient + '\'' +
                '}';
    }
}
