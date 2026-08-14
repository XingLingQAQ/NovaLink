package com.nova.link.api;

/**
 * Represents a webhook configuration for external notifications.
 *
 * <p>Since schema v5 webhooks are persisted; {@code url}/{@code event}/{@code secret}
 * are mutable to support PUT /api/webhooks/{id}, and {@code active} controls
 * whether the webhook participates in event distribution (inactive webhooks are
 * skipped but kept in storage).
 *
 * Requirements: 25.5 - Webhook support
 */
public class Webhook {

    private final String id;
    private String url;
    private String event;
    private String secret;
    private boolean active;
    private final long createdAt;
    private long lastTriggered;

    /**
     * Creates a new Webhook (active by default).
     *
     * @param id the unique webhook ID
     * @param url the webhook URL to call
     * @param event the event type to trigger on
     * @param secret the optional secret for signing payloads
     */
    public Webhook(String id, String url, String event, String secret) {
        this(id, url, event, secret, true, System.currentTimeMillis(), 0L);
    }

    /**
     * Restores a webhook from persistent storage.
     *
     * @param id the unique webhook ID
     * @param url the webhook URL to call
     * @param event the event type to trigger on
     * @param secret the optional secret for signing payloads
     * @param active whether the webhook participates in distribution
     * @param createdAt original creation timestamp (epoch millis)
     * @param lastTriggered last successful trigger (epoch millis, 0 = never)
     */
    public Webhook(String id, String url, String event, String secret,
                   boolean active, long createdAt, long lastTriggered) {
        this.id = id;
        this.url = url;
        this.event = event;
        this.secret = secret;
        this.active = active;
        this.createdAt = createdAt;
        this.lastTriggered = lastTriggered;
    }

    /**
     * Gets the webhook ID.
     *
     * @return the ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the webhook URL.
     *
     * @return the URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the webhook URL.
     *
     * @param url the new URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Gets the event type this webhook listens for.
     *
     * @return the event type
     */
    public String getEvent() {
        return event;
    }

    /**
     * Sets the event type this webhook listens for.
     *
     * @param event the new event type
     */
    public void setEvent(String event) {
        this.event = event;
    }

    /**
     * Gets the secret for signing payloads.
     *
     * @return the secret, or null if not set
     */
    public String getSecret() {
        return secret;
    }

    /**
     * Sets the secret for signing payloads.
     *
     * @param secret the new secret (null to clear)
     */
    public void setSecret(String secret) {
        this.secret = secret;
    }

    /**
     * Checks whether this webhook participates in event distribution.
     *
     * @return true when active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Enables or disables this webhook for event distribution.
     *
     * @param active the new active flag
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Gets the creation timestamp.
     *
     * @return the creation time in milliseconds
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * Gets the last triggered timestamp.
     *
     * @return the last triggered time in milliseconds, or 0 if never triggered
     */
    public long getLastTriggered() {
        return lastTriggered;
    }

    /**
     * Sets the last triggered timestamp.
     *
     * @param lastTriggered the timestamp
     */
    public void setLastTriggered(long lastTriggered) {
        this.lastTriggered = lastTriggered;
    }

    /**
     * Checks if this webhook matches the given event.
     *
     * @param eventType the event type to check
     * @return true if this webhook should be triggered for the event
     */
    public boolean matchesEvent(String eventType) {
        if (event == null || eventType == null) {
            return false;
        }
        // Support wildcard matching (e.g., "message.*" matches "message.sent")
        if (event.endsWith(".*")) {
            String prefix = event.substring(0, event.length() - 2);
            return eventType.startsWith(prefix);
        }
        return event.equals(eventType);
    }
}
