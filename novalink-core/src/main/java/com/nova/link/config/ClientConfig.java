package com.nova.link.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for a client (Minecraft server).
 *
 * Requirements: 20.4 - Client configuration
 */
public class ClientConfig {

    private String username;
    private String password;
    private String displayName;
    /**
     * Optional GLOBAL channel permission nodes granted to this client after
     * successful authentication. Empty/null means grant wildcard {@code *}
     * (full GLOBAL fan-out access) for backward compatibility.
     */
    private List<String> permissions;
    private Map<String, ServerChannelConfig> channels;

    public ClientConfig() {
        this.channels = new LinkedHashMap<>();
        this.permissions = new ArrayList<>();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions != null ? permissions : new ArrayList<>();
    }

    public Map<String, ServerChannelConfig> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, ServerChannelConfig> channels) {
        this.channels = channels != null ? channels : new LinkedHashMap<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClientConfig that = (ClientConfig) o;
        return Objects.equals(username, that.username) &&
               Objects.equals(password, that.password) &&
               Objects.equals(displayName, that.displayName) &&
               Objects.equals(permissions, that.permissions) &&
               Objects.equals(channels, that.channels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, password, displayName, permissions, channels);
    }
}
