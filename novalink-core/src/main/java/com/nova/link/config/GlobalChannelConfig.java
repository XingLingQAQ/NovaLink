package com.nova.link.config;

import java.util.Objects;

/**
 * Configuration for a global channel.
 * 
 * Requirements: 4.1, 20.2 - Global channels configuration
 */
public class GlobalChannelConfig {

    private String displayName;
    private String permission;
    private int maxCapacity = 1000;

    public GlobalChannelConfig() {}

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity > 0 ? maxCapacity : 1000;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GlobalChannelConfig that = (GlobalChannelConfig) o;
        return maxCapacity == that.maxCapacity &&
               Objects.equals(displayName, that.displayName) &&
               Objects.equals(permission, that.permission);
    }

    @Override
    public int hashCode() {
        return Objects.hash(displayName, permission, maxCapacity);
    }
}
