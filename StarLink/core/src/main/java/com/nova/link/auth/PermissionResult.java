package com.nova.link.auth;

/**
 * Represents the result of a permission check.
 * 
 * Requirements:
 * - 2.7: Returns NC-403 error when permission is denied
 */
public class PermissionResult {

    private final boolean allowed;
    private final String errorCode;
    private final String message;

    private PermissionResult(boolean allowed, String errorCode, String message) {
        this.allowed = allowed;
        this.errorCode = errorCode;
        this.message = message;
    }

    /**
     * Creates a successful permission result (access granted).
     *
     * @return a successful PermissionResult
     */
    public static PermissionResult allowed() {
        return new PermissionResult(true, null, "Permission granted");
    }

    /**
     * Creates a denied permission result with NC-403 error code.
     *
     * @param message the error message explaining why permission was denied
     * @return a denied PermissionResult
     */
    public static PermissionResult denied(String message) {
        return new PermissionResult(false, "NC-403", message);
    }

    /**
     * Creates a denied permission result with a custom error code.
     *
     * @param errorCode the error code
     * @param message   the error message
     * @return a denied PermissionResult
     */
    public static PermissionResult denied(String errorCode, String message) {
        return new PermissionResult(false, errorCode, message);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "PermissionResult{" +
                "allowed=" + allowed +
                ", errorCode='" + errorCode + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
