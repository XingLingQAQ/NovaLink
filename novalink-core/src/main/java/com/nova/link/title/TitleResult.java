package com.nova.link.title;

/**
 * Result of a title operation.
 * 
 * Requirements:
 * - 15.1-15.5: Title sending functionality with permission-based scope
 */
public class TitleResult {

    private final boolean success;
    private final String channelId;
    private final int recipientCount;
    private final String errorCode;
    private final String message;

    private TitleResult(boolean success, String channelId, int recipientCount, 
                        String errorCode, String message) {
        this.success = success;
        this.channelId = channelId;
        this.recipientCount = recipientCount;
        this.errorCode = errorCode;
        this.message = message;
    }

    /**
     * Creates a successful result.
     *
     * @param channelId the target channel ID
     * @param recipientCount the number of recipients
     * @param message success message
     * @return a successful TitleResult
     */
    public static TitleResult success(String channelId, int recipientCount, String message) {
        return new TitleResult(true, channelId, recipientCount, null, message);
    }

    /**
     * Creates a failure result.
     *
     * @param errorCode the error code (e.g., NC-403)
     * @param message error message
     * @return a failed TitleResult
     */
    public static TitleResult failure(String errorCode, String message) {
        return new TitleResult(false, null, 0, errorCode, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getChannelId() {
        return channelId;
    }

    public int getRecipientCount() {
        return recipientCount;
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
            return "TitleResult{success=true, channelId='" + channelId + 
                   "', recipientCount=" + recipientCount + ", message='" + message + "'}";
        } else {
            return "TitleResult{success=false, errorCode='" + errorCode + 
                   "', message='" + message + "'}";
        }
    }
}
