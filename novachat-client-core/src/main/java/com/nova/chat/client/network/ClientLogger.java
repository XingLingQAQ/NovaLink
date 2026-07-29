package com.nova.chat.client.network;

/**
 * Logging port that abstracts JUL-style and SLF-style platform loggers.
 *
 * <p>Platform adapters map these levels to their native API
 * ({@code info/warning/severe} vs {@code info/warn/error}). Debug may be gated
 * by a plugin debug flag before reaching this interface.
 */
public interface ClientLogger {

    /** Informational message (connection progress, reconnect countdown). */
    void info(String message);

    /** Warning (connect failure, unexpected disconnect). */
    void warn(String message);

    /** Debug detail (packet send/receive). May be no-op when debug is off. */
    void debug(String message);

    /** Error (auth failure, max reconnect exhausted). */
    void error(String message);

    /**
     * Error with cause. Default appends the cause message; adapters may log the
     * throwable natively.
     */
    default void error(String message, Throwable cause) {
        if (cause == null) {
            error(message);
        } else {
            error(message + ": " + cause.getMessage());
        }
    }
}
