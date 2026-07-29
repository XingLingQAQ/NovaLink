package com.nova.link.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Loads server channels from client configuration and registers them with the ChannelManager.
 * Server channels are stored under client configuration and have SERVER scope,
 * meaning messages are routed only within the same client (physical isolation).
 * 
 * Requirements: 5.1, 5.2, 5.3
 * - Store channels under client configuration
 * - Verify player is connected through the same client
 * - Route messages only to same client members
 */
public class ServerChannelLoader {

    private static final Logger logger = LoggerFactory.getLogger(ServerChannelLoader.class);

    private final ChannelManager channelManager;
    private final TemplateManager templateManager;

    public ServerChannelLoader(ChannelManager channelManager, TemplateManager templateManager) {
        this.channelManager = Objects.requireNonNull(channelManager, "ChannelManager cannot be null");
        this.templateManager = Objects.requireNonNull(templateManager, "TemplateManager cannot be null");
    }

    /**
     * Loads server channels for a specific client from configuration.
     * 
     * Expected format:
     * <pre>
     * channels:
     *   local:
     *     use_template: "standard_local"
     *     display_name: "&e[生存大厅]"
     *   resource:
     *     display_name: "资源区"
     *     scope: SERVER
     *     allowed_worlds:
     *       - "resource_world"
     *       - "resource_nether"
     * </pre>
     *
     * @param clientId the client ID these channels belong to
     * @param channelsConfig map of channel ID to channel configuration
     * @return list of created channels
     */
    public List<Channel> loadServerChannels(String clientId, Map<String, Map<String, Object>> channelsConfig) {
        Objects.requireNonNull(clientId, "Client ID cannot be null");
        List<Channel> loadedChannels = new ArrayList<>();
        
        if (channelsConfig == null || channelsConfig.isEmpty()) {
            logger.info("No server channels configured for client '{}'", clientId);
            return loadedChannels;
        }
        
        for (Map.Entry<String, Map<String, Object>> entry : channelsConfig.entrySet()) {
            String channelId = entry.getKey();
            Map<String, Object> channelConfig = entry.getValue();
            
            try {
                Channel channel = loadServerChannel(clientId, channelId, channelConfig);
                loadedChannels.add(channel);
                logger.info("Loaded server channel: {} for client '{}' ({})", 
                        channelId, clientId, channel.getDisplayName());
            } catch (Exception e) {
                logger.error("Failed to load server channel '{}' for client '{}': {}", 
                        channelId, clientId, e.getMessage());
            }
        }
        
        logger.info("Loaded {} server channel(s) for client '{}'", loadedChannels.size(), clientId);
        return loadedChannels;
    }

    /**
     * Loads a single server channel from configuration.
     *
     * @param clientId the client ID
     * @param channelId the channel ID
     * @param config the channel configuration map
     * @return the created channel
     */
    private Channel loadServerChannel(String clientId, String channelId, Map<String, Object> config) {
        if (config == null) {
            config = Collections.emptyMap();
        }
        
        ChannelConfig.Builder builder = ChannelConfig.builder()
                .id(channelId)
                .clientId(clientId);
        
        // Check if using a template
        String templateId = getStringValue(config, "use_template", null);
        if (templateId != null) {
            // Apply template first, then overrides
            templateManager.applyTemplate(templateId, builder, config);
        }
        
        // Apply explicit configuration (overrides template values)
        applyExplicitConfig(builder, config);
        
        // Ensure scope is SERVER if not set
        if (builder.getScope() == null) {
            builder.scope(ChannelScope.SERVER);
        }
        
        // Validate scope - server channels must be SERVER scope
        if (builder.getScope() != ChannelScope.SERVER) {
            logger.warn("Channel '{}' has scope {} but is defined under client '{}', forcing SERVER scope",
                    channelId, builder.getScope(), clientId);
            builder.scope(ChannelScope.SERVER);
        }
        
        return channelManager.createChannel(builder.build());
    }

    /**
     * Applies explicit configuration values to the builder.
     * These values override any template values.
     *
     * @param builder the builder to apply values to
     * @param config the configuration map
     */
    private void applyExplicitConfig(ChannelConfig.Builder builder, Map<String, Object> config) {
        // Display name
        String displayName = getStringValue(config, "display_name", null);
        if (displayName != null) {
            builder.displayName(displayName);
        }
        
        // Scope (always SERVER for server channels, but allow explicit setting)
        String scopeStr = getStringValue(config, "scope", null);
        if (scopeStr != null) {
            try {
                ChannelScope scope = ChannelScope.valueOf(scopeStr.toUpperCase());
                builder.scope(scope);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid scope '{}', defaulting to SERVER", scopeStr);
                builder.scope(ChannelScope.SERVER);
            }
        } else {
            // Default to SERVER scope for server channels
            builder.scope(ChannelScope.SERVER);
        }
        
        // Permission
        String permission = getStringValue(config, "permission", null);
        if (permission != null) {
            builder.permission(permission);
        }
        
        // Max capacity
        Integer maxCapacity = getIntValue(config, "max_capacity", null);
        if (maxCapacity != null) {
            builder.maxCapacity(maxCapacity);
        }
        
        // Allowed worlds (for world channels)
        List<String> allowedWorlds = getStringListValue(config, "allowed_worlds");
        if (allowedWorlds != null && !allowedWorlds.isEmpty()) {
            builder.allowedWorlds(allowedWorlds);
        }
    }

    /**
     * Loads server channels for all clients from a clients configuration.
     * 
     * Expected format:
     * <pre>
     * clients:
     *   - username: "Survival_Server"
     *     channels:
     *       local:
     *         use_template: "standard_local"
     * </pre>
     *
     * @param clientsConfig list of client configurations
     * @return map of client ID to list of created channels
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<Channel>> loadAllClientChannels(List<Map<String, Object>> clientsConfig) {
        Map<String, List<Channel>> result = new HashMap<>();
        
        if (clientsConfig == null || clientsConfig.isEmpty()) {
            logger.info("No clients configured");
            return result;
        }
        
        for (Map<String, Object> clientConfig : clientsConfig) {
            String clientId = getStringValue(clientConfig, "username", null);
            if (clientId == null) {
                logger.warn("Client configuration missing 'username', skipping");
                continue;
            }
            
            Object channelsObj = clientConfig.get("channels");
            if (channelsObj instanceof Map) {
                Map<String, Map<String, Object>> channelsConfig = (Map<String, Map<String, Object>>) channelsObj;
                List<Channel> channels = loadServerChannels(clientId, channelsConfig);
                result.put(clientId, channels);
            }
        }
        
        return result;
    }

    /**
     * Reloads server channels for a specific client.
     * Removes existing channels for the client and loads new ones.
     *
     * @param clientId the client ID
     * @param channelsConfig the new channel configuration
     * @return list of newly created channels
     */
    public List<Channel> reloadClientChannels(String clientId, Map<String, Map<String, Object>> channelsConfig) {
        // Remove existing channels for this client
        List<Channel> existingChannels = channelManager.getChannelsByClient(clientId);
        for (Channel channel : existingChannels) {
            // Only remove SERVER scope channels (not PRIVATE)
            if (channel.getScope() == ChannelScope.SERVER) {
                channelManager.deleteChannel(channel.getId());
                logger.debug("Removed server channel: {} for client '{}'", channel.getId(), clientId);
            }
        }
        
        // Load new channels
        return loadServerChannels(clientId, channelsConfig);
    }

    /**
     * Gets a string value from the config map with a default.
     */
    private String getStringValue(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return defaultValue;
    }

    /**
     * Gets an integer value from the config map.
     */
    private Integer getIntValue(Map<String, Object> config, String key, Integer defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Gets a string list value from the config map.
     */
    @SuppressWarnings("unchecked")
    private List<String> getStringListValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item instanceof String) {
                    result.add((String) item);
                }
            }
            return result;
        }
        return null;
    }
}
