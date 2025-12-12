package com.nova.chat.common.chat;

/**
 * Exception thrown when item serialization or deserialization fails.
 * 
 * This exception is used by {@link ItemSerializer} to indicate
 * problems with JSON parsing or invalid item data format.
 */
public class ItemSerializationException extends RuntimeException {

    /**
     * Creates a new ItemSerializationException with a message.
     * 
     * @param message the error message
     */
    public ItemSerializationException(String message) {
        super(message);
    }

    /**
     * Creates a new ItemSerializationException with a message and cause.
     * 
     * @param message the error message
     * @param cause the underlying cause
     */
    public ItemSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
