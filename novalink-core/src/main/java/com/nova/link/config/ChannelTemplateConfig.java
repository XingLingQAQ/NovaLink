package com.nova.link.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for a channel template.
 * 
 * Requirements: 5.5, 20.3 - Channel templates configuration
 */
public class ChannelTemplateConfig {

    private String displayName;
    private String scope = "SERVER";
    private String permission;
    private Integer maxCapacity;
    private List<String> allowedWorlds;

    public ChannelTemplateConfig() {}

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope != null ? scope : "SERVER";
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public Integer getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(Integer maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public List<String> getAllowedWorlds() {
        return allowedWorlds;
    }

    public void setAllowedWorlds(List<String> allowedWorlds) {
        this.allowedWorlds = allowedWorlds != null ? new ArrayList<>(allowedWorlds) : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChannelTemplateConfig that = (ChannelTemplateConfig) o;
        return Objects.equals(displayName, that.displayName) &&
               Objects.equals(scope, that.scope) &&
               Objects.equals(permission, that.permission) &&
               Objects.equals(maxCapacity, that.maxCapacity) &&
               Objects.equals(allowedWorlds, that.allowedWorlds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(displayName, scope, permission, maxCapacity, allowedWorlds);
    }
}
