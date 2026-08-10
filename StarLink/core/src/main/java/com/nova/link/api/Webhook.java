package com.nova.link.api;

/**
 * Represents a webhook configuration for external notifications.
 * 
 * Requirements: 25.5 - Webhook support
 */
public class Webhook {

    private final String id;
    private final String url;
    private final String event;
    private final String secret;
    private final long createdAt;
    private long lastTriggered;

    /**
     * Creates a new Webhook.
     *
     * @param id the unique webhook ID
     * @param url the webhook URL to call
     * @param event the event type to trigger on
     * @param secret the optional secret for signing payloads
     */
    public Webhook(String id, String url, String event, String secret) {
        this.id = id;
        this.url = url;
        this.event = event;
        this.secret = secret;
        this.createdAt = System.currentTimeMillis();
        this.lastTriggered = 0;
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
     * Gets the event type this webhook listens for.
     *
     * @return the event type
     */
    public String getEvent() {
        return event;
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
