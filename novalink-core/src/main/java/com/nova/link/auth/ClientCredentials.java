package com.nova.link.auth;

/**
 * Represents client credentials stored in the backend configuration.
 * 
 * Requirements: 1.1 - Client credential storage
 */
public class ClientCredentials {

    private final String username;
    private final String passwordHash;
    private final String displayName;
    private boolean superAdmin;

    public ClientCredentials(String username, String passwordHash) {
        this(username, passwordHash, username);
    }

    public ClientCredentials(String username, String passwordHash, String displayName) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.superAdmin = false;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isSuperAdmin() {
        return superAdmin;
    }

    public void setSuperAdmin(boolean superAdmin) {
        this.superAdmin = superAdmin;
    }

    @Override
    public String toString() {
        return "ClientCredentials{" +
                "username='" + username + '\'' +
                ", displayName='" + displayName + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClientCredentials that = (ClientCredentials) o;
        return username.equals(that.username) &&
               passwordHash.equals(that.passwordHash);
    }

    @Override
    public int hashCode() {
        int result = username.hashCode();
        result = 31 * result + passwordHash.hashCode();
        return result;
    }
}
