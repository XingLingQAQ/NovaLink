package com.nova.chat.nukkit.config;

import cn.nukkit.utils.Config;
import com.nova.chat.client.network.ClientConnectionConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration wrapper for NovaChat Nukkit plugin.
 * Parses and provides access to config.yml settings.
 * 
 * Adapted from Bukkit version for Nukkit API.
 */
public class NovaChatConfig {

    // Backend connection settings
    private final String backendHost;
    private final int backendPort;
    private final String username;
    private final String password;
    private final int reconnectDelay;

    // Chat settings
    private final boolean replaceVanilla;
    private final String defaultChannel;
    private final String locale;
    private final Map<String, String> channelPrefixes;

    // Format settings
    private final String prefix;
    private final String errorFormat;
    private final String successFormat;
    private final String defaultFormat;
    private final Map<String, String> channelFormats;

    // Debug mode
    private final boolean debug;

    /**
     * Creates a new configuration from the given Nukkit Config.
     *
     * @param config the Nukkit configuration
     */
    public NovaChatConfig(Config config) {
        // Backend settings
        this.backendHost = requireNonBlankString(config, "backend.host");
        this.backendPort = requirePort(config, "backend.port");
        this.username = requireNonBlankString(config, "backend.username");
        this.password = requireString(config, "backend.password");
        this.reconnectDelay = requirePositiveInt(config, "backend.reconnect-delay");

        // Chat settings
        this.replaceVanilla = requireBoolean(config, "chat.replace_vanilla");
        this.defaultChannel = requireNonBlankString(config, "chat.default_channel");
        this.locale = requireNonBlankString(config, "chat.locale");

        // Channel-prefix routing (prefix string -> channel ID); empty = disabled
        this.channelPrefixes = new HashMap<>();
        Map<String, Object> prefixesSection = requireSection(config, "chat.channel-prefixes");
        for (Map.Entry<String, Object> entry : prefixesSection.entrySet()) {
            String key = entry.getKey();
            if (!(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException(
                        "Configuration value chat.channel-prefixes." + key + " must be a string");
            }
            if (key != null && !key.isEmpty() && !value.isEmpty()) {
                channelPrefixes.put(key, value);
            }
        }

        // Format settings
        this.prefix = requireString(config, "format.prefix");
        this.errorFormat = requireString(config, "format.error");
        this.successFormat = requireString(config, "format.success");
        this.defaultFormat = requireString(config, "format.default");

        // Channel formats
        this.channelFormats = new HashMap<>();
        Map<String, Object> channelsSection = requireSection(config, "format.channels");
        for (Map.Entry<String, Object> entry : channelsSection.entrySet()) {
            if (!(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException(
                        "Configuration value format.channels." + entry.getKey() + " must be a string");
            }
            channelFormats.put(entry.getKey(), value);
        }

        // Debug mode
        this.debug = requireBoolean(config, "debug");

        toClientConnectionConfig();
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

    // Getters

    public String getBackendHost() {
        return backendHost;
    }

    public int getBackendPort() {
        return backendPort;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getReconnectDelay() {
        return reconnectDelay;
    }

    public boolean isReplaceVanilla() {
        return replaceVanilla;
    }

    public String getDefaultChannel() {
        return defaultChannel;
    }

    /**
     * Gets the configured default locale code (e.g. {@code "zh_CN"},
     * {@code "en_US"}). Used at startup to seed {@link com.nova.chat.client.i18n.I18n}.
     *
     * @return the locale code read from {@code chat.locale}
     */
    public String getLocale() {
        return locale;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getErrorFormat() {
        return errorFormat;
    }

    public String getSuccessFormat() {
        return successFormat;
    }

    public String getDefaultFormat() {
        return defaultFormat;
    }

    public Map<String, String> getChannelFormats() {
        return channelFormats;
    }

    /**
     * Gets the configured channel-prefix routing map
     * ({@code chat.channel-prefixes}: prefix string → channel ID). Empty map
     * (the default) disables prefix routing.
     *
     * @return the prefix→channelId map, never null
     */
    public Map<String, String> getChannelPrefixes() {
        return channelPrefixes;
    }

    /**
     * Gets the format for a specific channel.
     *
     * @param channelId the channel ID
     * @return the format string, or default format if not configured
     */
    public String getChannelFormat(String channelId) {
        return channelFormats.getOrDefault(channelId, defaultFormat);
    }

    public boolean isDebug() {
        return debug;
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
                .username(username)
                .password(password)
                .initialReconnectDelaySeconds(reconnectDelay)
                .build();
    }
}
