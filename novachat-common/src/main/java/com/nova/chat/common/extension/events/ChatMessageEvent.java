package com.nova.chat.common.extension.events;

import com.nova.chat.common.extension.ExtensionEvent;

import java.util.UUID;

/**
 * Event fired when a chat message is sent.
 * 
 * <p>Extensions can listen for this event to modify or cancel chat messages.
 */
public class ChatMessageEvent extends ExtensionEvent {
    
    private final UUID senderId;
    private final String senderName;
    private final String channelId;
    private String message;
    
    /**
     * Creates a new ChatMessageEvent.
     * 
     * @param senderId the UUID of the message sender
     * @param senderName the name of the message sender
     * @param channelId the ID of the channel
     * @param message the message content
     */
    public ChatMessageEvent(UUID senderId, String senderName, String channelId, String message) {
        super(true); // Cancellable
        this.senderId = senderId;
        this.senderName = senderName;
        this.channelId = channelId;
        this.message = message;
    }
    
    /**
     * Gets the sender's UUID.
     * 
     * @return the sender UUID
     */
    public UUID getSenderId() {
        return senderId;
    }
    
    /**
     * Gets the sender's name.
     * 
     * @return the sender name
     */
    public String getSenderName() {
        return senderName;
    }
    
    /**
     * Gets the channel ID.
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
     * 
     * @param message the new message
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
