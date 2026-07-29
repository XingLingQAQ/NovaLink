package com.nova.link.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Loads global channels from configuration and registers them with the ChannelManager.
 * 
 * Global channels are defined in the `global_channels` section of novalink.yml
 * and have GLOBAL scope, meaning messages are routed to all connected clients.
 * 
 * Requirements: 4.1 - Load global channels from config on backend startup
 */
public class GlobalChannelLoader {

    private static final Logger logger = LoggerFactory.getLogger(GlobalChannelLoader.class);

    private final ChannelManager channelManager;

    public GlobalChannelLoader(ChannelManager channelManager) {
        this.channelManager = Objects.requireNonNull(channelManager, "ChannelManager cannot be null");
    }

    /**
     * Loads global channels from a configuration map.
     * 
     * Expected format:
     * <pre>
     * global_channels:
     *   global:
     *     display_name: "全服"
     *     permission: "novachat.channel.global"
     *     max_capacity: 1000
     * </pre>
     *
     * @param globalChannelsConfig map of channel ID to channel configuration
     * @return list of created channels
     */
    public List<Channel> loadGlobalChannels(Map<String, Map<String, Object>> globalChannelsConfig) {
        List<Channel> loadedChannels = new ArrayList<>();
        
        if (globalChannelsConfig == null || globalChannelsConfig.isEmpty()) {
            logger.info("No global channels configured");
            return loadedChannels;
        }
        
        for (Map.Entry<String, Map<String, Object>> entry : globalChannelsConfig.entrySet()) {
            String channelId = entry.getKey();
            Map<String, Object> channelConfig = entry.getValue();
            
            try {
                Channel channel = loadGlobalChannel(channelId, channelConfig);
                loadedChannels.add(channel);
                logger.info("Loaded global channel: {} ({})", channelId, channel.getDisplayName());
            } catch (Exception e) {
                logger.error("Failed to load global channel '{}': {}", channelId, e.getMessage());
            }
        }
        
        logger.info("Loaded {} global channel(s)", loadedChannels.size());
        return loadedChannels;
    }


    /**
     * Loads a single global channel from configuration.
     *
     * @param channelId the channel ID
     * @param config the channel configuration map
     * @return the created channel
     */
    private Channel loadGlobalChannel(String channelId, Map<String, Object> config) {
        if (config == null) {
            config = Collections.emptyMap();
        }
        
        String displayName = getStringValue(config, "display_name", channelId);
        String permission = getStringValue(config, "permission", null);
        int maxCapacity = getIntValue(config, "max_capacity", 1000);
        
        ChannelConfig channelConfig = ChannelConfig.builder()
                .id(channelId)
                .displayName(displayName)
                .scope(ChannelScope.GLOBAL)
                .permission(permission)
                .maxCapacity(maxCapacity)
                .build();
        
        return channelManager.createChannel(channelConfig);
    }

    /**
     * Reloads global channels from configuration.
     * Removes existing global channels and loads new ones.
     *
     * @param globalChannelsConfig the new configuration
     * @return list of newly created channels
     */
    public List<Channel> reloadGlobalChannels(Map<String, Map<String, Object>> globalChannelsConfig) {
        // Remove existing global channels
        List<Channel> existingGlobalChannels = channelManager.getGlobalChannels();
        for (Channel channel : existingGlobalChannels) {
            channelManager.deleteChannel(channel.getId());
            logger.debug("Removed global channel: {}", channel.getId());
        }
        
        // Load new channels
        return loadGlobalChannels(globalChannelsConfig);
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
     * Gets an integer value from the config map with a default.
     */
    private int getIntValue(Map<String, Object> config, String key, int defaultValue) {
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
     * Creates a GlobalChannelConfig from a map for easier testing.
     */
    public static class GlobalChannelConfig {
        private final String id;
        private final String displayName;
        private final String permission;
        private final int maxCapacity;

        public GlobalChannelConfig(String id, String displayName, String permission, int maxCapacity) {
            this.id = id;
            this.displayName = displayName;
            this.permission = permission;
            this.maxCapacity = maxCapacity;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getPermission() {
            return permission;
        }

        public int getMaxCapacity() {
            return maxCapacity;
        }

        /**
         * Converts this config to a map format suitable for loadGlobalChannels.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            if (displayName != null) {
                map.put("display_name", displayName);
            }
            if (permission != null) {
                map.put("permission", permission);
            }
            map.put("max_capacity", maxCapacity);
            return map;
        }
    }
}
