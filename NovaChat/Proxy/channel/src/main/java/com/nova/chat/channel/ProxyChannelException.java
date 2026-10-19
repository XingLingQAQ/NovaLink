package com.nova.chat.channel;

/**
 * Raised when a plugin-messaging payload cannot be encoded or decoded.
 *
 * <p>Checked on purpose: both sides must treat a malformed or oversized
 * payload as a droppable message (log + return), never as a reason to fail the
 * connection. A plugin-message channel is reachable by clients, so every byte
 * in this envelope is untrusted input.
 */
public class ProxyChannelException extends Exception {

    /**
     * @param message the reason
     */
    public ProxyChannelException(String message) {
        super(message);
    }

    /**
     * @param message the reason
     * @param cause the underlying parser failure
     */
    public ProxyChannelException(String message, Throwable cause) {
        super(message, cause);
    }
}
