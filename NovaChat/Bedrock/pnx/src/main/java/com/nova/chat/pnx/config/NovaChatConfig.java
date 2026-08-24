package com.nova.chat.pnx.config;

import cn.nukkit.utils.Config;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.pnx.NovaChatPNX;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration manager for NovaChat-PNX plugin.
 */
@Getter
public class NovaChatConfig {

    private final NovaChatPNX plugin;

    // Backend settings
    private String backendHost;
    private int backendPort;
    private String backendUsername;
    private String backendPassword;
    private int reconnectDelay;

    // Chat settings
    private boolean replaceVanilla;
    private String defaultChannel;
    private String locale;

    /**
     * Channel-prefix routing map ({@code chat.channel-prefixes}: prefix string
     * → channel ID). Empty map (the default) disables prefix routing.
     */
    private Map<String, String> channelPrefixes;

    // Format settings
    private String formatPrefix;
    private String formatError;
    private String formatSuccess;
    private String formatDefault;
    private Map<String, String> channelFormats;

    // World routing settings
    private boolean worldRoutingEnabled;
    private Map<String, String> worldMappings;

    // Debug mode
    private boolean debug;

    public NovaChatConfig(NovaChatPNX plugin) {
        this.plugin = plugin;
        this.channelFormats = new HashMap<>();
        this.worldMappings = new HashMap<>();
        this.channelPrefixes = new HashMap<>();
    }

    /**
     * Load configuration from config.yml
     */
    public void load() {
        Config config = plugin.getConfig();

        // Backend settings
        backendHost = requireNonBlankString(config, "backend.host");
        backendPort = requirePort(config, "backend.port");
        backendUsername = requireNonBlankString(config, "backend.username");
        backendPassword = requireString(config, "backend.password");
        reconnectDelay = requirePositiveInt(config, "backend.reconnect-delay");

        // Chat settings
        replaceVanilla = requireBoolean(config, "chat.replace_vanilla");
        defaultChannel = requireNonBlankString(config, "chat.default_channel");
        locale = requireNonBlankString(config, "chat.locale");

        // Channel-prefix routing (prefix string -> channel ID); empty = disabled
        channelPrefixes.clear();
        Map<String, Object> prefixesSection = requireSection(config, "chat.channel-prefixes");
        for (Map.Entry<String, Object> entry : prefixesSection.entrySet()) {
            if (!(entry.getValue() instanceof String channelId)) {
                throw new IllegalArgumentException("Configuration value chat.channel-prefixes."
                        + entry.getKey() + " must be a string");
            }
            if (!entry.getKey().isEmpty() && !channelId.isEmpty()) {
                channelPrefixes.put(entry.getKey(), channelId);
            }
        }

        // Format settings
        formatPrefix = requireString(config, "format.prefix");
        formatError = requireString(config, "format.error");
        formatSuccess = requireString(config, "format.success");
        formatDefault = requireString(config, "format.default");

        // Load channel formats
        channelFormats.clear();
        Map<String, Object> channelsSection = requireSection(config, "format.channels");
        for (Map.Entry<String, Object> entry : channelsSection.entrySet()) {
            if (!(entry.getValue() instanceof String format)) {
                throw new IllegalArgumentException("Configuration value format.channels."
                        + entry.getKey() + " must be a string");
            }
            channelFormats.put(entry.getKey(), format);
        }

        // World routing settings
        worldRoutingEnabled = requireBoolean(config, "world-routing.enabled");
        worldMappings.clear();
        Map<String, Object> mappingsSection = requireSection(config, "world-routing.mappings");
        for (Map.Entry<String, Object> entry : mappingsSection.entrySet()) {
            if (!(entry.getValue() instanceof String channelId)) {
                throw new IllegalArgumentException("Configuration value world-routing.mappings."
                        + entry.getKey() + " must be a string");
            }
            worldMappings.put(entry.getKey(), channelId);
        }

        // Debug mode
        debug = requireBoolean(config, "debug");

        toClientConnectionConfig();

        if (debug) {
            plugin.getLogger().info("Configuration loaded:");
            plugin.getLogger().info("  Backend: " + backendHost + ":" + backendPort);
            plugin.getLogger().info("  Username: " + backendUsername);
            plugin.getLogger().info("  Default channel: " + defaultChannel);
            plugin.getLogger().info("  World routing: " + worldRoutingEnabled);
        }
    }

    private static String requireString(Config config, String path) {
        Object value = config.get(path);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a string");
        }
        return stringValue;
    }

    private static String requireNonBlankString(Config config, String path) {
        String value = requireString(config, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Configuration value " + path + " must not be blank");
        }
        return value;
    }

    private static int requireInt(Config config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Number numberValue)
                || !Double.isFinite(numberValue.doubleValue())
                || numberValue.doubleValue() != Math.rint(numberValue.doubleValue())
                || numberValue.doubleValue() < Integer.MIN_VALUE
                || numberValue.doubleValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Configuration value " + path + " must be an integer");
        }
        return numberValue.intValue();
    }

    private static int requirePositiveInt(Config config, String path) {
        int value = requireInt(config, path);
        if (value <= 0) {
            throw new IllegalArgumentException("Configuration value " + path + " must be greater than 0");
        }
        return value;
    }

    private static int requirePort(Config config, String path) {
        int value = requireInt(config, path);
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be between 1 and 65535");
        }
        return value;
    }

    private static boolean requireBoolean(Config config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a boolean");
        }
        return booleanValue;
    }

    private static Map<String, Object> requireSection(Config config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a mapping");
        }
        return config.getSection(path).getAllMap();
    }

    /**
     * Get the format for a specific channel.
     *
     * @param channelId The channel ID
     * @return The format string, or the default format if not configured
     */
    public String getChannelFormat(String channelId) {
        return channelFormats.getOrDefault(channelId, formatDefault);
    }

    /**
     * Get the channel ID for a specific world.
     *
     * @param worldName The world name
     * @return The channel ID, or null if not mapped
     */
    public String getWorldChannel(String worldName) {
        return worldMappings.get(worldName);
    }

    /**
     * Maps platform config to the shared {@link ClientConnectionConfig}.
     *
     * <p>The initial reconnect delay comes from {@code backend.reconnect-delay}.
     */
    public ClientConnectionConfig toClientConnectionConfig() {
        return ClientConnectionConfig.builder()
                .host(backendHost)
                .port(backendPort)
                .username(backendUsername)
                .password(backendPassword)
                .initialReconnectDelaySeconds(reconnectDelay)
                .build();
    }
}
