package com.nova.link.channel;

import java.util.*;

/**
 * Represents a reusable channel template that can be inherited by channels.
 * Templates define default values for channel properties that can be overridden.
 * 
 * Requirements: 5.5 - Support channel templates to avoid repetitive configuration
 */
public class ChannelTemplate {

    /** Unique template identifier */
    private final String id;
    
    /** Display name template */
    private String displayName;
    
    /** Default scope for channels using this template */
    private ChannelScope scope;
    
    /** Default permission node */
    private String permission;
    
    /** Default max capacity */
    private Integer maxCapacity;
    
    /** Default allowed worlds */
    private List<String> allowedWorlds;

    /**
     * Creates a new channel template with the given ID.
     *
     * @param id the template identifier
     */
    public ChannelTemplate(String id) {
        this.id = Objects.requireNonNull(id, "Template ID cannot be null");
    }

    /**
     * Applies this template to a ChannelConfig builder.
     * Only sets values that are defined in the template and not already set in the builder.
     *
     * @param builder the builder to apply template values to
     * @param overrides map of property overrides from channel config
     * @return the builder with template values applied
     */
    public ChannelConfig.Builder applyTo(ChannelConfig.Builder builder, Map<String, Object> overrides) {
        if (overrides == null) {
            overrides = Collections.emptyMap();
        }
        
        // Apply display name if not overridden
        if (!overrides.containsKey("display_name") && displayName != null) {
            builder.displayName(displayName);
        }
        
        // Apply scope if not overridden
        if (!overrides.containsKey("scope") && scope != null) {
            builder.scope(scope);
        }
        
        // Apply permission if not overridden
        if (!overrides.containsKey("permission") && permission != null) {
            builder.permission(permission);
        }
        
        // Apply max capacity if not overridden
        if (!overrides.containsKey("max_capacity") && maxCapacity != null) {
            builder.maxCapacity(maxCapacity);
        }
        
        // Apply allowed worlds if not overridden
        if (!overrides.containsKey("allowed_worlds") && allowedWorlds != null) {
            builder.allowedWorlds(new ArrayList<>(allowedWorlds));
        }
        
        return builder;
    }

    // Getters and setters

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public ChannelScope getScope() {
        return scope;
    }

    public void setScope(ChannelScope scope) {
        this.scope = scope;
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
        return allowedWorlds != null ? Collections.unmodifiableList(allowedWorlds) : null;
    }

    public void setAllowedWorlds(List<String> allowedWorlds) {
        this.allowedWorlds = allowedWorlds != null ? new ArrayList<>(allowedWorlds) : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChannelTemplate that = (ChannelTemplate) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ChannelTemplate{" +
                "id='" + id + '\'' +
                ", displayName='" + displayName + '\'' +
                ", scope=" + scope +
                ", maxCapacity=" + maxCapacity +
                '}';
    }
}
