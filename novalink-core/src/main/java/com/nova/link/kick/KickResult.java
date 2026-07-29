package com.nova.link.kick;

/**
 * Result of a kick operation.
 * 
 * Requirements:
 * - 16.1-16.5: Kick functionality with permission-based scope
 */
public class KickResult {

    private final boolean success;
    private final String channelId;
    private final String newChannelId;
    private final String errorCode;
    private final String message;

    private KickResult(boolean success, String channelId, String newChannelId,
                       String errorCode, String message) {
        this.success = success;
        this.channelId = channelId;
        this.newChannelId = newChannelId;
        this.errorCode = errorCode;
        this.message = message;
    }

    /**
     * Creates a successful result.
     *
     * @param channelId the channel the player was kicked from
     * @param newChannelId the default channel the player was moved to
     * @param message success message
     * @return a successful KickResult
     */
    public static KickResult success(String channelId, String newChannelId, String message) {
        return new KickResult(true, channelId, newChannelId, null, message);
    }

    /**
     * Creates a failure result.
     *
     * @param errorCode the error code (e.g., NC-403)
     * @param message error message
     * @return a failed KickResult
     */
    public static KickResult failure(String errorCode, String message) {
        return new KickResult(false, null, null, errorCode, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getNewChannelId() {
        return newChannelId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        if (success) {
            return "KickResult{success=true, channelId='" + channelId + 
                   "', newChannelId='" + newChannelId + "', message='" + message + "'}";
        } else {
            return "KickResult{success=false, errorCode='" + errorCode + 
                   "', message='" + message + "'}";
        }
    }
}
