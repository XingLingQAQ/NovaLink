package com.nova.link.spy;

/**
 * Result of a spy operation.
 * 
 * Requirements: 17.1-17.5
 */
public class SpyResult {

    private final boolean success;
    private final String errorCode;
    private final String message;

    private SpyResult(boolean success, String errorCode, String message) {
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
    }

    /**
     * Creates a successful result.
     *
     * @param message success message
     * @return successful result
     */
    public static SpyResult success(String message) {
        return new SpyResult(true, null, message);
    }

    /**
     * Creates a failure result.
     *
     * @param errorCode the error code
     * @param message   the error message
     * @return failure result
     */
    public static SpyResult failure(String errorCode, String message) {
        return new SpyResult(false, errorCode, message);
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
        return "SpyResult{" +
                "success=" + success +
                ", errorCode='" + errorCode + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
