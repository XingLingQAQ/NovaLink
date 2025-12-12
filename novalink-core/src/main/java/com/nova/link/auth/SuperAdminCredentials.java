package com.nova.link.auth;

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

    public SuperAdminCredentials(UUID uuid, String passwordHash) {
        this.uuid = uuid;
        this.passwordHash = passwordHash;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SuperAdminCredentials that = (SuperAdminCredentials) o;
        return uuid.equals(that.uuid) && passwordHash.equals(that.passwordHash);
    }

    @Override
    public int hashCode() {
        int result = uuid.hashCode();
        result = 31 * result + passwordHash.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "SuperAdminCredentials{uuid=" + uuid + '}';
    }
}
