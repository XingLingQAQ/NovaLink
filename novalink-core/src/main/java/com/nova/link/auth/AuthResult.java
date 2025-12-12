package com.nova.link.auth;

/**
 * Represents the result of an authentication attempt.
 * 
 * Requirements: 1.2, 1.3 - Authentication success/failure responses
 */
public class AuthResult {

    private final boolean success;
    private final String errorCode;
    private final String message;
    private final ClientCredentials credentials;

    private AuthResult(boolean success, String errorCode, String message, ClientCredentials credentials) {
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
        this.credentials = credentials;
    }

    /**
     * Creates a successful authentication result.
     *
     * @param credentials the authenticated client credentials
     * @return a successful AuthResult
     */
    public static AuthResult success(ClientCredentials credentials) {
        return new AuthResult(true, null, "Authentication successful", credentials);
    }

    /**
     * Creates a failed authentication result with NC-401 error code.
     *
     * @param message the error message
     * @return a failed AuthResult
     */
    public static AuthResult unauthorized(String message) {
        return new AuthResult(false, "NC-401", message, null);
    }

    /**
     * Creates a failed authentication result due to IP ban with NC-429 error code.
     *
     * @param message the error message
     * @return a failed AuthResult
     */
    public static AuthResult ipBanned(String message) {
        return new AuthResult(false, "NC-429", message, null);
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

    public ClientCredentials getCredentials() {
        return credentials;
    }

    @Override
    public String toString() {
        return "AuthResult{" +
                "success=" + success +
                ", errorCode='" + errorCode + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
