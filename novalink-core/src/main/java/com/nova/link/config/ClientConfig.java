package com.nova.link.config;

import java.util.LinkedHashMap;
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
    private Map<String, ServerChannelConfig> channels;

    public ClientConfig() {
        this.channels = new LinkedHashMap<>();
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
               Objects.equals(channels, that.channels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, password, displayName, channels);
    }
}
