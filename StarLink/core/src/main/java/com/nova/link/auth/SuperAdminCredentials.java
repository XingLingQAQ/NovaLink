package com.nova.link.auth;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents super admin credentials stored in the backend configuration.
 *
 * Requirements:
 * - 2.2: Super admin authentication via password
 */
public class SuperAdminCredentials {

    private final UUID uuid;
    private final String passwordHash;
    // Optional human-readable username used for web-panel login. When null/blank,
    // the UUID string is used as the web-panel login username (backward compatible).
    private final String username;

    public SuperAdminCredentials(UUID uuid, String passwordHash) {
        this(uuid, passwordHash, null);
    }

    public SuperAdminCredentials(UUID uuid, String passwordHash, String username) {
        this.uuid = uuid;
        this.passwordHash = passwordHash;
        this.username = username;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * @return the optional human-readable web-panel login username, or null when
     *         not configured (callers fall back to {@link #getUuid()}).
     */
    public String getUsername() {
        return username;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SuperAdminCredentials that = (SuperAdminCredentials) o;
        return uuid.equals(that.uuid)
                && passwordHash.equals(that.passwordHash)
                && Objects.equals(username, that.username);
    }

    @Override
    public int hashCode() {
        int result = uuid.hashCode();
        result = 31 * result + passwordHash.hashCode();
        result = 31 * result + Objects.hashCode(username);
        return result;
    }

    @Override
    public String toString() {
        return "SuperAdminCredentials{uuid=" + uuid
                + ", username=" + username + '}';
    }
}
