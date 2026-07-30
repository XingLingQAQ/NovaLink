package com.nova.chat.bukkit.error;

import com.nova.chat.client.error.ErrorCode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an error that occurred in NovaChat.
 * Contains error code, message, suggestion, and additional context.
 * 
 * Requirements: 27.1-27.4
 */
public class NovaError {

    private final ErrorCode errorCode;
    private final String customMessage;
    private final String customSuggestion;
    private final Instant timestamp;
    private final UUID errorId;
    private final Map<String, Object> context;

    /**
     * Creates a new NovaError with the specified error code.
     *
     * @param errorCode the error code
     */
    public NovaError(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    /**
     * Creates a new NovaError with a custom message.
     *
     * @param errorCode     the error code
     * @param customMessage custom message (overrides default)
     */
    public NovaError(ErrorCode errorCode, String customMessage) {
        this(errorCode, customMessage, null);
    }

    /**
     * Creates a new NovaError with custom message and suggestion.
     *
     * @param errorCode        the error code
     * @param customMessage    custom message (overrides default)
     * @param customSuggestion custom suggestion (overrides default)
     */
    public NovaError(ErrorCode errorCode, String customMessage, String customSuggestion) {
        this.errorCode = errorCode;
        this.customMessage = customMessage;
        this.customSuggestion = customSuggestion;
        this.timestamp = Instant.now();
        this.errorId = UUID.randomUUID();
        this.context = new HashMap<>();
    }

    /**
     * Gets the error code.
     *
     * @return the error code
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * Gets the error code string (e.g., "NC-401").
     *
     * @return the error code string
     */
    public String getCode() {
        return errorCode.getCode();
    }

    /**
     * Gets the error message (custom or default).
     *
     * @return the error message
     */
    public String getMessage() {
        return customMessage != null ? customMessage : errorCode.getMessage();
    }

    /**
     * Gets the suggestion (custom or default).
     *
     * @return the suggestion
     */
    public String getSuggestion() {
        return customSuggestion != null ? customSuggestion : errorCode.getSuggestion();
    }

    /**
     * Gets the timestamp when the error occurred.
     *
     * @return the timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the unique error ID for tracking.
     *
     * @return the error ID
     */
    public UUID getErrorId() {
        return errorId;
    }

    /**
     * Gets the context map.
     *
     * @return the context map
     */
    public Map<String, Object> getContext() {
        return context;
    }

    /**
     * Adds context information to the error.
     *
     * @param key   the context key
     * @param value the context value
     * @return this error for chaining
     */
    public NovaError withContext(String key, Object value) {
        context.put(key, value);
        return this;
    }

    /**
     * Checks if this is a client error (4XX).
     *
     * @return true if client error
     */
    public boolean isClientError() {
        return errorCode.isClientError();
    }

    /**
     * Checks if this is a server error (5XX).
     *
     * @return true if server error
     */
    public boolean isServerError() {
        return errorCode.isServerError();
    }

    /**
     * Creates a formatted string for display.
     *
     * @return formatted error string
     */
    public String toDisplayString() {
        return String.format("%s: %s", getCode(), getMessage());
    }

    /**
     * Creates a detailed string for logging.
     *
     * @return detailed error string
     */
    public String toLogString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(errorId.toString().substring(0, 8)).append("] ");
        sb.append(getCode()).append(": ").append(getMessage());
        if (!context.isEmpty()) {
            sb.append(" | Context: ").append(context);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toDisplayString();
    }

    // ==========================================
    // Static Factory Methods
    // ==========================================

    /**
     * Creates a BAD_REQUEST error.
     */
    public static NovaError badRequest(String message) {
        return new NovaError(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * Creates an UNAUTHORIZED error.
     */
    public static NovaError unauthorized() {
        return new NovaError(ErrorCode.UNAUTHORIZED);
    }

    /**
     * Creates an UNAUTHORIZED error with custom message.
     */
    public static NovaError unauthorized(String message) {
        return new NovaError(ErrorCode.UNAUTHORIZED, message);
    }

    /**
     * Creates a FORBIDDEN error.
     */
    public static NovaError forbidden() {
        return new NovaError(ErrorCode.FORBIDDEN);
    }

    /**
     * Creates a FORBIDDEN error with custom message.
     */
    public static NovaError forbidden(String message) {
        return new NovaError(ErrorCode.FORBIDDEN, message);
    }

    /**
     * Creates a NOT_FOUND error.
     */
    public static NovaError notFound(String resource) {
        return new NovaError(ErrorCode.NOT_FOUND, resource + " 不存在");
    }

    /**
     * Creates a CONFLICT error.
     */
    public static NovaError conflict(String message) {
        return new NovaError(ErrorCode.CONFLICT, message);
    }

    /**
     * Creates an INVITE_EXPIRED error.
     */
    public static NovaError inviteExpired() {
        return new NovaError(ErrorCode.INVITE_EXPIRED);
    }

    /**
     * Creates an INVITE_USED error.
     */
    public static NovaError inviteUsed() {
        return new NovaError(ErrorCode.INVITE_USED);
    }

    /**
     * Creates a RATE_LIMITED error.
     */
    public static NovaError rateLimited() {
        return new NovaError(ErrorCode.RATE_LIMITED);
    }

    /**
     * Creates an INVALID_FORMAT error.
     */
    public static NovaError invalidFormat(String message) {
        return new NovaError(ErrorCode.INVALID_FORMAT, message);
    }

    /**
     * Creates a CHANNEL_FULL error.
     */
    public static NovaError channelFull() {
        return new NovaError(ErrorCode.CHANNEL_FULL);
    }

    /**
     * Creates an ALREADY_JOINED error.
     */
    public static NovaError alreadyJoined() {
        return new NovaError(ErrorCode.ALREADY_JOINED);
    }

    /**
     * Creates a NOT_IN_CHANNEL error.
     */
    public static NovaError notInChannel() {
        return new NovaError(ErrorCode.NOT_IN_CHANNEL);
    }

    /**
     * Creates a WRONG_PASSWORD error.
     */
    public static NovaError wrongPassword() {
        return new NovaError(ErrorCode.WRONG_PASSWORD);
    }

    /**
     * Creates a WORLD_RESTRICTED error.
     */
    public static NovaError worldRestricted() {
        return new NovaError(ErrorCode.WORLD_RESTRICTED);
    }

    /**
     * Creates a MUTED error.
     */
    public static NovaError muted(String remainingTime) {
        return new NovaError(ErrorCode.MUTED, "您已被禁言，剩余时间: " + remainingTime);
    }

    /**
     * Creates a SELF_ACTION error.
     */
    public static NovaError selfAction() {
        return new NovaError(ErrorCode.SELF_ACTION);
    }

    /**
     * Creates a TARGET_OFFLINE error.
     */
    public static NovaError targetOffline(String playerName) {
        return new NovaError(ErrorCode.TARGET_OFFLINE, "玩家 " + playerName + " 不在线");
    }

    /**
     * Creates an INVALID_DURATION error.
     */
    public static NovaError invalidDuration() {
        return new NovaError(ErrorCode.INVALID_DURATION);
    }

    /**
     * Creates an INTERNAL_ERROR.
     */
    public static NovaError internalError() {
        return new NovaError(ErrorCode.INTERNAL_ERROR);
    }

    /**
     * Creates a SERVICE_UNAVAILABLE error.
     */
    public static NovaError serviceUnavailable() {
        return new NovaError(ErrorCode.SERVICE_UNAVAILABLE);
    }

    /**
     * Creates a GATEWAY_TIMEOUT error.
     */
    public static NovaError gatewayTimeout() {
        return new NovaError(ErrorCode.GATEWAY_TIMEOUT);
    }

    /**
     * Creates a DATABASE_ERROR.
     */
    public static NovaError databaseError() {
        return new NovaError(ErrorCode.DATABASE_ERROR);
    }

    /**
     * Creates a CONFIG_ERROR.
     */
    public static NovaError configError(String message) {
        return new NovaError(ErrorCode.CONFIG_ERROR, message);
    }
}
