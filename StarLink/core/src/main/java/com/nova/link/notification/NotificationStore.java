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
 * <p>PANEL-014 per-user isolation contract: every scoped operation (per-user
 * list/read/clear/count) is served by the provider's dedicated per-user method.
 * If a provider does not implement it and throws
 * {@link UnsupportedOperationException}, the store FAILS SAFE — the scoped
 * call returns an empty/zero result or a no-op with a warning log — and NEVER
 * escalates to the global destructive variants ({@link #clearAll()},
 * global {@link #markAllRead()}). Falling back from a user-scoped failure to a
 * global destructive operation would let any single user's action wipe or
 * flip state for every other user (privilege amplification).
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
        boolean persisted = false;
        if (databaseProvider != null) {
            try {
                databaseProvider.saveNotification(notification);
                persisted = true;
            } catch (DatabaseException e) {
                logger.error("Failed to persist notification '{}': {}", title, e.getMessage());
            }
        }
        // Ghost suppression: skip live delivery when the record was not
        // persisted — an unpersisted popup has no id, so it could never be
        // marked read or listed and REST/realtime would permanently diverge.
        if (webSocketGateway != null && persisted) {
            try {
                webSocketGateway.broadcastNotification(title, message, level);
            } catch (Exception e) {
                logger.debug("Failed to broadcast notification '{}': {}", title, e.getMessage());
            }
        }
        return notification;
    }

    /**
     * Creates, persists, and delivers a directed notification to a single
     * recipient over WebSocket. PANEL-014: the directed-delivery path uses
     * {@link WebSocketGateway#sendDirectedNotification} so the notification
     * reaches only the session whose username matches {@code recipient},
     * not every authenticated session. The persisted notification carries the
     * recipient so the per-user REST listing (GET /api/notifications) also
     * scopes it to that user.
     *
     * <p>The recipient is trimmed before persisting; a blank value degrades to
     * a broadcast (null/blank recipient means broadcast downstream). Stored
     * case is preserved — matching is case-insensitive at comparison time. If
     * persistence fails, live WS delivery is SKIPPED so the realtime view
     * never diverges from REST: an unpersisted notification has no id and
     * could never be marked read or listed.
     *
     * @param title     the notification title
     * @param message   the notification body
     * @param level     the level (info / warning / error)
     * @param recipient the recipient username (panel username); null or blank
     *                  falls back to a broadcast via {@link #createNotification}
     * @return the persisted notification (with its generated id), or the
     *         unpersisted in-memory instance (id 0) when persistence failed or
     *         no provider is configured
     */
    public Notification createDirectedNotification(String title, String message, String level, String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return createNotification(title, message, level);
        }
        // Trim at write time; stored case is preserved (comparison-time
        // matching handles case-insensitivity downstream).
        String normalizedRecipient = recipient.trim();
        Notification notification = new Notification(title, message, level);
        notification.setRecipient(normalizedRecipient);
        boolean persisted = false;
        if (databaseProvider != null) {
            try {
                databaseProvider.saveNotification(notification);
                persisted = true;
            } catch (DatabaseException e) {
                logger.error("Failed to persist directed notification '{}': {}", title, e.getMessage());
            }
        }
        if (webSocketGateway != null && persisted) {
            try {
                webSocketGateway.sendDirectedNotification(normalizedRecipient, title, message, level);
            } catch (Exception e) {
                logger.debug("Failed to deliver directed notification '{}': {}", title, e.getMessage());
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
     * Deletes all broadcast notifications (those with a null recipient). Used by
     * the SUPER_ADMIN global-retention path (DELETE /api/notifications/broadcast)
     * so that purging the shared broadcast stream does NOT remove other admins'
     * directed notifications.
     *
     * <p>PANEL-014: prior to this method the broadcast-clear endpoint called
     * {@link #clearAll()} which delegates to
     * {@code databaseProvider.clearNotifications()} (DELETE FROM notifications
     * with no recipient filter), wiping every notification including other
     * admins' directed ones — a per-user isolation defect surfaced by the
     * VERIFY-013 §7 two-user E2E slice.
     *
     * <p>Fail-safe: a provider that does not implement
     * {@code clearBroadcastNotifications()} (UnsupportedOperationException)
     * gets a no-op returning 0 — NEVER the old {@link #clearAll()} fallback,
     * which would escalate the scoped retention op into deleting every user's
     * notifications.
     *
     * @return number of broadcast notifications deleted, or 0 if the provider
     *         does not support the scoped operation
     */
    public int clearBroadcast() {
        if (databaseProvider == null) {
            return 0;
        }
        try {
            return databaseProvider.clearBroadcastNotifications();
        } catch (DatabaseException e) {
            logger.error("Failed to clear broadcast notifications: {}", e.getMessage());
            return 0;
        } catch (UnsupportedOperationException e) {
            // Fail-safe: never escalate a scoped failure to clearAll() — that
            // would wipe every user's notifications, not just broadcasts.
            logger.warn("Provider {} does not support clear-broadcast; skipping "
                    + "(fail-safe, no destructive fallback)", databaseProvider.getProviderType());
            return 0;
        }
    }

    /**
     * Counts notifications matching the pagination filter.
     *
     * @param unreadOnly when true, only unread notifications are counted
     * @return the total number of matching notifications, or 0 on failure
     */
    public int count(boolean unreadOnly) {
        if (databaseProvider == null) {
            return 0;
        }
        try {
            return databaseProvider.countNotifications(unreadOnly);
        } catch (DatabaseException e) {
            logger.error("Failed to count notifications: {}", e.getMessage());
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

    // ==================== Per-user state (PANEL-014) ====================

    /**
     * Lists notifications visible to a specific user with pagination and
     * per-user read state. Visible = broadcast (recipient null) or directed to
     * this user.
     *
     * <p>Fail-safe: a provider without the per-user API returns an empty list
     * (never the global listing, which would leak other users' directed
     * notifications).
     *
     * @param offset 0-based offset
     * @param limit max results
     * @param unreadOnly when true, only notifications this user has not read
     * @param userId the per-user identity (panel username)
     * @return list of notifications (newest first), never null
     */
    public List<Notification> getNotifications(int offset, int limit, boolean unreadOnly, String userId) {
        if (databaseProvider == null || userId == null) {
            return Collections.emptyList();
        }
        try {
            return databaseProvider.getNotifications(offset, limit, unreadOnly, userId);
        } catch (DatabaseException e) {
            logger.error("Failed to load notifications for user {}: {}", userId, e.getMessage());
            return Collections.emptyList();
        } catch (UnsupportedOperationException e) {
            // Fail-safe: an empty result instead of the global listing — the
            // global view would leak other users' directed notifications.
            logger.warn("Provider {} does not support per-user notification listing; returning empty "
                    + "(fail-safe, no global fallback)", databaseProvider.getProviderType());
            return Collections.emptyList();
        }
    }

    /**
     * Marks a single notification as read for a specific user. Fail-safe: a
     * provider without the per-user API makes this a no-op (never a global
     * mark-read, which would flip the flag for every user).
     */
    public void markRead(long id, String userId) {
        if (databaseProvider == null || userId == null) {
            return;
        }
        try {
            databaseProvider.markNotificationRead(id, userId);
        } catch (DatabaseException e) {
            logger.error("Failed to mark notification {} as read for user {}: {}", id, userId, e.getMessage());
        } catch (UnsupportedOperationException e) {
            logger.warn("Provider {} does not support per-user mark-read; skipping "
                    + "(fail-safe, no global fallback)", databaseProvider.getProviderType());
        }
    }

    /**
     * Marks all notifications visible to a user as read (per-user state).
     * Fail-safe: a provider without the per-user API makes this a no-op
     * (never global {@link #markAllRead()}, which would mark every user's
     * notifications read).
     */
    public void markAllRead(String userId) {
        if (databaseProvider == null || userId == null) {
            return;
        }
        try {
            databaseProvider.markAllNotificationsRead(userId);
        } catch (DatabaseException e) {
            logger.error("Failed to mark all notifications as read for user {}: {}", userId, e.getMessage());
        } catch (UnsupportedOperationException e) {
            logger.warn("Provider {} does not support per-user mark-all-read; skipping "
                    + "(fail-safe, no global fallback)", databaseProvider.getProviderType());
        }
    }

    /**
     * Clears directed notifications for a specific user. Broadcast events are
     * never cleared by this call. Fail-safe: a provider without the per-user
     * API returns 0 and deletes nothing (never the old {@link #clearAll()}
     * fallback, which let one admin's archive wipe every user's inbox).
     *
     * @return number of directed notifications deleted, or 0 if the provider
     *         does not support the scoped operation
     */
    public int clearAll(String userId) {
        if (databaseProvider == null || userId == null) {
            return 0;
        }
        try {
            return databaseProvider.clearNotifications(userId);
        } catch (DatabaseException e) {
            logger.error("Failed to clear notifications for user {}: {}", userId, e.getMessage());
            return 0;
        } catch (UnsupportedOperationException e) {
            // Fail-safe: never escalate to clearNotifications() (global) — one
            // user's archive must not wipe everyone's data.
            logger.warn("Provider {} does not support per-user clear; skipping "
                    + "(fail-safe, no global fallback)", databaseProvider.getProviderType());
            return 0;
        }
    }

    /**
     * Counts notifications visible to a user matching the filter. Fail-safe:
     * a provider without the per-user API returns 0 (never the global count,
     * which would include other users' directed notifications).
     */
    public int count(boolean unreadOnly, String userId) {
        if (databaseProvider == null || userId == null) {
            return 0;
        }
        try {
            return databaseProvider.countNotifications(unreadOnly, userId);
        } catch (DatabaseException e) {
            logger.error("Failed to count notifications for user {}: {}", userId, e.getMessage());
            return 0;
        } catch (UnsupportedOperationException e) {
            logger.warn("Provider {} does not support per-user notification count; returning 0 "
                    + "(fail-safe, no global fallback)", databaseProvider.getProviderType());
            return 0;
        }
    }

    /**
     * Gets the per-user unread count. Fail-safe: a provider without the
     * per-user API returns 0 (never the global unread count, which aggregates
     * every user's notifications).
     */
    public int getUnreadCount(String userId) {
        if (databaseProvider == null || userId == null) {
            return 0;
        }
        try {
            return databaseProvider.getUnreadCount(userId);
        } catch (DatabaseException e) {
            logger.error("Failed to get unread count for user {}: {}", userId, e.getMessage());
            return 0;
        } catch (UnsupportedOperationException e) {
            logger.warn("Provider {} does not support per-user unread count; returning 0 "
                    + "(fail-safe, no global fallback)", databaseProvider.getProviderType());
            return 0;
        }
    }
}
