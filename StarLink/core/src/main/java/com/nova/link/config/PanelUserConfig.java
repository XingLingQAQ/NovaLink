package com.nova.link.config;

import java.util.Objects;

/**
 * A {@code panel-users} config entry: a web-panel login account with role
 * ADMIN or VIEWER. SUPER_ADMIN accounts are configured via {@code super-admins}
 * only. The password hash is resolved at load time (either {@code password-hash}
 * as-is or SHA-256 of the plain {@code password}), mirroring super-admins.
 */
public class PanelUserConfig {

    private final String username;
    private final String passwordHash;
    /** Role name, validated at parse time: "ADMIN" or "VIEWER". */
    private final String role;

    public PanelUserConfig(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PanelUserConfig that = (PanelUserConfig) o;
        return Objects.equals(username, that.username)
                && Objects.equals(passwordHash, that.passwordHash)
                && Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, passwordHash, role);
    }

    @Override
    public String toString() {
        return "PanelUserConfig{username='" + username + "', role='" + role + "'}";
    }
}
