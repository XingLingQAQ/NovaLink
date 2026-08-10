package com.nova.link.mute;

/**
 * Represents the result of a mute operation.
 * 
 * Requirements: 13.1, 13.2
 */
public class MuteResult {

    private final boolean success;
    private final String errorCode;
    private final String message;

    private MuteResult(boolean success, String errorCode, String message) {
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
    }

    /**
     * Creates a successful mute result.
     *
     * @param message success message
     * @return successful result
     */
    public static MuteResult success(String message) {
        return new MuteResult(true, null, message);
    }

    /**
     * Creates a failed mute result due to insufficient permissions.
     *
     * @param message error message
     * @return failed result with NC-403 code
     */
    public static MuteResult forbidden(String message) {
        return new MuteResult(false, "NC-403", message);
    }

    /**
     * Creates a failed mute result due to invalid parameters.
     *
     * @param message error message
     * @return failed result with NC-400 code
     */
    public static MuteResult badRequest(String message) {
        return new MuteResult(false, "NC-400", message);
    }

    /**
     * Creates a failed mute result due to resource not found.
     *
     * @param message error message
     * @return failed result with NC-404 code
     */
    public static MuteResult notFound(String message) {
        return new MuteResult(false, "NC-404", message);
    }

    /**
     * Creates a failed mute result due to duration exceeding limit.
     *
     * @param message error message
     * @return failed result with NC-400 code
     */
    public static MuteResult durationExceeded(String message) {
        return new MuteResult(false, "NC-400", message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "MuteResult{" +
                "success=" + success +
                ", errorCode='" + errorCode + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
