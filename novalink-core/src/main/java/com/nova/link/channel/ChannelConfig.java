package com.nova.link.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Configuration object for creating a new channel.
 * Uses builder pattern for convenient construction.
 */
public class ChannelConfig {

    private String id;
    private String displayName;
    private ChannelScope scope;
    private String clientId;
    private String permission;
    private int maxCapacity = 100;
    private List<String> allowedWorlds;
    private String password;
    private UUID ownerId;

    private ChannelConfig() {
        this.allowedWorlds = new ArrayList<>();
    }

    /**
     * Creates a new builder for ChannelConfig.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    // Getters

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ChannelScope getScope() {
        return scope;
    }

    public String getClientId() {
        return clientId;
    }

    public String getPermission() {
        return permission;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public List<String> getAllowedWorlds() {
        return allowedWorlds;
    }

    public String getPassword() {
        return password;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    /**
     * Builder for ChannelConfig.
     */
    public static class Builder {
        private final ChannelConfig config;

        private Builder() {
            this.config = new ChannelConfig();
        }

        public Builder id(String id) {
            config.id = id;
            return this;
        }

        public Builder displayName(String displayName) {
            config.displayName = displayName;
            return this;
        }

        public Builder scope(ChannelScope scope) {
            config.scope = scope;
            return this;
        }

        public Builder clientId(String clientId) {
            config.clientId = clientId;
            return this;
        }

        public Builder permission(String permission) {
            config.permission = permission;
            return this;
        }

        public Builder maxCapacity(int maxCapacity) {
            config.maxCapacity = maxCapacity;
            return this;
        }

        public Builder allowedWorlds(List<String> allowedWorlds) {
            config.allowedWorlds = allowedWorlds != null ? new ArrayList<>(allowedWorlds) : new ArrayList<>();
            return this;
        }

        public Builder password(String password) {
            config.password = password;
            return this;
        }

        public Builder ownerId(UUID ownerId) {
            config.ownerId = ownerId;
            return this;
        }

        /**
         * Gets the current scope value (for checking before build).
         *
         * @return the current scope, or null if not set
         */
        public ChannelScope getScope() {
            return config.scope;
        }

        /**
         * Gets the current display name value (for checking before build).
         *
         * @return the current display name, or null if not set
         */
        public String getDisplayName() {
            return config.displayName;
        }

        /**
         * Gets the current permission value (for checking before build).
         *
         * @return the current permission, or null if not set
         */
        public String getPermission() {
            return config.permission;
        }

        /**
         * Gets the current max capacity value (for checking before build).
         *
         * @return the current max capacity
         */
        public int getMaxCapacity() {
            return config.maxCapacity;
        }

        /**
         * Gets the current allowed worlds value (for checking before build).
         *
         * @return the current allowed worlds list
         */
        public List<String> getAllowedWorlds() {
            return config.allowedWorlds;
        }

        /**
         * Builds the ChannelConfig.
         *
         * @return the built config
         * @throws IllegalStateException if required fields are missing
         */
        public ChannelConfig build() {
            if (config.scope == null) {
                throw new IllegalStateException("Channel scope is required");
            }
            
            // Validate scope-specific requirements
            if (config.scope == ChannelScope.GLOBAL && config.clientId != null) {
                throw new IllegalStateException("GLOBAL channels cannot have a clientId");
            }
            
            if ((config.scope == ChannelScope.SERVER || config.scope == ChannelScope.PRIVATE) 
                    && config.clientId == null) {
                throw new IllegalStateException("SERVER and PRIVATE channels require a clientId");
            }
            
            return config;
        }
    }
}
