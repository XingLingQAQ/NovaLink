package com.nova.chat.bukkit.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Event fired when a message is received from a channel.
 * Other plugins can listen to this event to intercept or modify channel messages.
 * 
 * Requirements: 25.1 - ChannelMessageEvent for other plugins to listen
 */
public class ChannelMessageEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID senderId;
    private final String senderName;
    private final String channelId;
    private String message;
    private final Map<String, String> placeholders;
    private boolean cancelled = false;

    /**
     * Creates a new ChannelMessageEvent.
     *
     * @param senderId the UUID of the message sender
     * @param senderName the display name of the sender
     * @param channelId the channel ID where the message was sent
     * @param message the message content
     * @param placeholders the message placeholders
     */
    public ChannelMessageEvent(UUID senderId, String senderName, String channelId, 
                               String message, Map<String, String> placeholders) {
        super(true); // Async event
        this.senderId = senderId;
        this.senderName = senderName;
        this.channelId = channelId;
        this.message = message;
        this.placeholders = placeholders != null ? placeholders : Collections.emptyMap();
    }

    /**
     * Gets the UUID of the message sender.
     *
     * @return the sender's UUID
     */
    public UUID getSenderId() {
        return senderId;
    }

    /**
     * Gets the display name of the message sender.
     *
     * @return the sender's name
     */
    public String getSenderName() {
        return senderName;
    }

    /**
     * Gets the channel ID where the message was sent.
     *
     * @return the channel ID
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * Gets the message content.
     *
     * @return the message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Sets the message content.
     * This allows plugins to modify the message before it's displayed.
     *
     * @param message the new message content
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * Gets the message placeholders.
     *
     * @return an unmodifiable map of placeholders
     */
    public Map<String, String> getPlaceholders() {
        return Collections.unmodifiableMap(placeholders);
    }

    /**
     * Gets a specific placeholder value.
     *
     * @param key the placeholder key
     * @return the placeholder value, or null if not found
     */
    public String getPlaceholder(String key) {
        return placeholders.get(key);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
