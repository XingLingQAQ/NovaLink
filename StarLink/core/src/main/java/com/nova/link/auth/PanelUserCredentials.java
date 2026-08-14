package com.nova.link.auth;

import java.util.Objects;

/**
 * Credentials of a web-panel login account. Panel accounts live in a pool
 * separate from game-server client credentials: game-server accounts must
 * never be able to log into the web panel (credential pool separation).
 *
 * <p>Sources: {@code super-admins} (role SUPER_ADMIN) and the {@code panel-users}
 * config section (role ADMIN or VIEWER).
 */
public class PanelUserCredentials {

    private final String username;
    private final String passwordHash;
    private final PanelRole role;

    public PanelUserCredentials(String username, String passwordHash, PanelRole role) {
        this.username = Objects.requireNonNull(username, "username");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.role = Objects.requireNonNull(role, "role");
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public PanelRole getRole() {
        return role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PanelUserCredentials that = (PanelUserCredentials) o;
        return username.equals(that.username)
                && passwordHash.equals(that.passwordHash)
                && role == that.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, passwordHash, role);
    }

    @Override
    public String toString() {
        return "PanelUserCredentials{username='" + username + "', role=" + role + '}';
    }
}
