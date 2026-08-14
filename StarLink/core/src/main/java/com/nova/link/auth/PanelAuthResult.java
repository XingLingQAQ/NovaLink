package com.nova.link.auth;

/**
 * Result of a web-panel login attempt (see
 * {@link AuthManager#authenticatePanelUser}). Mirrors {@link AuthResult} but
 * carries {@link PanelUserCredentials} (with the panel role) instead of
 * game-client credentials.
 */
public class PanelAuthResult {

    private final boolean success;
    private final String errorCode;
    private final PanelUserCredentials credentials;

    private PanelAuthResult(boolean success, String errorCode, PanelUserCredentials credentials) {
        this.success = success;
        this.errorCode = errorCode;
        this.credentials = credentials;
    }

    public static PanelAuthResult success(PanelUserCredentials credentials) {
        return new PanelAuthResult(true, null, credentials);
    }

    /** Invalid credentials (or the account is not a panel account). */
    public static PanelAuthResult unauthorized() {
        return new PanelAuthResult(false, "NC-401", null);
    }

    /** Too many failed attempts from this IP. */
    public static PanelAuthResult ipBanned() {
        return new PanelAuthResult(false, "NC-429", null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public PanelUserCredentials getCredentials() {
        return credentials;
    }
}
