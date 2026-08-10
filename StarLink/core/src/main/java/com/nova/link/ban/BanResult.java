package com.nova.link.ban;

/**
 * Represents the result of a ban operation.
 *
 * <p>Mirrors {@link com.nova.link.mute.MuteResult} with the same error-code
 * conventions (NC-403 forbidden / NC-400 bad request / NC-404 not found).
 *
 * Requirements: ban feature — player ban management
 */
public class BanResult {

    private final boolean success;
    private final String errorCode;
    private final String message;

    private BanResult(boolean success, String errorCode, String message) {
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
    }

    /**
     * Creates a successful ban result.
     *
     * @param message success message
     * @return successful result
     */
    public static BanResult success(String message) {
        return new BanResult(true, null, message);
    }

    /**
     * Creates a failed ban result due to insufficient permissions.
     *
     * @param message error message
     * @return failed result with NC-403 code
     */
    public static BanResult forbidden(String message) {
        return new BanResult(false, "NC-403", message);
    }

    /**
     * Creates a failed ban result due to invalid parameters.
     *
     * @param message error message
     * @return failed result with NC-400 code
     */
    public static BanResult badRequest(String message) {
        return new BanResult(false, "NC-400", message);
    }

    /**
     * Creates a failed ban result due to resource not found.
     *
     * @param message error message
     * @return failed result with NC-404 code
     */
    public static BanResult notFound(String message) {
        return new BanResult(false, "NC-404", message);
    }

    /**
     * Creates a failed ban result due to duration exceeding limit.
     *
     * @param message error message
     * @return failed result with NC-400 code
     */
    public static BanResult durationExceeded(String message) {
        return new BanResult(false, "NC-400", message);
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
        return "BanResult{" +
                "success=" + success +
                ", errorCode='" + errorCode + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
