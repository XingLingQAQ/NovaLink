package com.nova.link.notification;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.Notification;
import com.nova.link.websocket.WebSocketGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Aggregates notification persistence (DatabaseProvider) and real-time
 * broadcast (WebSocketGateway) behind a single entry point.
 *
 * <p>Every backend event that wants to surface a notification to the web panel
 * calls {@link #createNotification}. The store persists the record (so the
 * panel can paginate history) and broadcasts it live (so open panel sessions
 * see it immediately). This keeps call sites one-liners and guarantees the two
 * side-channels stay in sync.
 *
 * Requirements: notification persistence feature
 */
public class NotificationStore {

    private static final Logger logger = LoggerFactory.getLogger(NotificationStore.class);

    private final DatabaseProvider databaseProvider;
    private volatile WebSocketGateway webSocketGateway;

    public NotificationStore(DatabaseProvider databaseProvider, WebSocketGateway webSocketGateway) {
        this.databaseProvider = databaseProvider;
        this.webSocketGateway = webSocketGateway;
    }

    public NotificationStore(DatabaseProvider databaseProvider) {
        this.databaseProvider = databaseProvider;
    }

    /**
     * Sets the WebSocket gateway used for live notification broadcast. Allows
     * late binding to break the RestApiHandler &harr; WebSocketGateway cycle.
     *
     * @param webSocketGateway the gateway, or null to disable live broadcast
     */
    public void setWebSocketGateway(WebSocketGateway webSocketGateway) {
        this.webSocketGateway = webSocketGateway;
    }

    /**
     * Creates, persists, and broadcasts a notification.
     *
     * @param title the notification title
     * @param message the notification body
     * @param level the level (info / warning / error)
     * @return the persisted notification (with its generated id), or null on failure
     */
    public Notification createNotification(String title, String message, String level) {
        Notification notification = new Notification(title, message, level);
        if (databaseProvider != null) {
            try {
                databaseProvider.saveNotification(notification);
            } catch (DatabaseException e) {
                logger.error("Failed to persist notification '{}': {}", title, e.getMessage());
                // Still attempt the broadcast so the panel sees it live.
            }
        }
        if (webSocketGateway != null) {
            try {
                webSocketGateway.broadcastNotification(title, message, level);
            } catch (Exception e) {
                logger.debug("Failed to broadcast notification '{}': {}", title, e.getMessage());
            }
        }
        return notification;
    }

    /**
     * Lists notifications with pagination.
     *
     * @param offset 0-based offset
     * @param limit max results
     * @param unreadOnly when true, only unread notifications are returned
     * @return list of notifications (newest first), never null
     */
    public List<Notification> getNotifications(int offset, int limit, boolean unreadOnly) {
        if (databaseProvider == null) {
            return Collections.emptyList();
        }
        try {
            return databaseProvider.getNotifications(offset, limit, unreadOnly);
        } catch (DatabaseException e) {
            logger.error("Failed to load notifications: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Marks a single notification as read.
     *
     * @param id the notification id
     */
    public void markRead(long id) {
        if (databaseProvider == null) {
            return;
        }
        try {
            databaseProvider.markNotificationRead(id);
        } catch (DatabaseException e) {
            logger.error("Failed to mark notification {} as read: {}", id, e.getMessage());
        }
    }

    /**
     * Marks all unread notifications as read.
     */
    public void markAllRead() {
        if (databaseProvider == null) {
            return;
        }
        try {
            databaseProvider.markAllNotificationsRead();
        } catch (DatabaseException e) {
            logger.error("Failed to mark all notifications as read: {}", e.getMessage());
        }
    }

    /**
     * Deletes all notifications.
     *
     * @return number of notifications deleted
     */
    public int clearAll() {
        if (databaseProvider == null) {
            return 0;
        }
        try {
            return databaseProvider.clearNotifications();
        } catch (DatabaseException e) {
            logger.error("Failed to clear notifications: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Gets the count of unread notifications.
     *
     * @return the unread count, or 0 on failure
     */
    public int getUnreadCount() {
        if (databaseProvider == null) {
            return 0;
        }
        try {
            return databaseProvider.getUnreadCount();
        } catch (DatabaseException e) {
            logger.error("Failed to get unread count: {}", e.getMessage());
            return 0;
        }
    }
}
