package com.nova.chat.folia.config;

import com.nova.chat.client.network.ClientConnectionConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration wrapper for NovaChat Folia plugin.
 * Parses and provides access to config.yml settings.
 * 
 * Requirements: 2.1
 */
public class NovaChatConfig {

    // Backend connection settings
    private final String backendHost;
    private final int backendPort;
    private final String username;
    private final String password;
    private final int reconnectDelay;

    // Folia specific settings
    private final boolean asyncProcessing;
    private final int networkTimeout;

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
     * Creates a new configuration from the given FileConfiguration.
     *
     * @param config the Bukkit configuration
     */
    public NovaChatConfig(FileConfiguration config) {
        // Backend settings
        this.backendHost = requiredNonBlankString(config, "backend.host");
        this.backendPort = requiredPort(config, "backend.port");
        this.username = requiredNonBlankString(config, "backend.username");
        this.password = requiredString(config, "backend.password");
        this.reconnectDelay = requiredPositiveInt(config, "backend.reconnect-delay");

        // Folia settings
        this.asyncProcessing = requiredBoolean(config, "folia.async-processing");
        this.networkTimeout = requiredPositiveInt(config, "folia.network-timeout");

        // Chat settings
        this.replaceVanilla = requiredBoolean(config, "chat.replace_vanilla");
        this.defaultChannel = requiredNonBlankString(config, "chat.default_channel");
        this.locale = requiredNonBlankString(config, "chat.locale");

        // Channel prefixes (prefix -> channel id; empty map = feature disabled)
        this.channelPrefixes = new HashMap<>();
        ConfigurationSection prefixSection = requiredSection(config, "chat.channel-prefixes");
        for (String key : prefixSection.getKeys(false)) {
            String channelId = requiredString(config, "chat.channel-prefixes." + key);
            if (!key.isEmpty() && !channelId.isEmpty()) {
                channelPrefixes.put(key, channelId);
            }
        }

        // Format settings
        this.prefix = requiredString(config, "format.prefix");
        this.errorFormat = requiredString(config, "format.error");
        this.successFormat = requiredString(config, "format.success");
        this.defaultFormat = requiredString(config, "format.default");

        // Channel formats
        this.channelFormats = new HashMap<>();
        ConfigurationSection channelsSection = requiredSection(config, "format.channels");
        for (String key : channelsSection.getKeys(false)) {
            channelFormats.put(key, requiredString(config, "format.channels." + key));
        }

        // Debug mode
        this.debug = requiredBoolean(config, "debug");

        toClientConnectionConfig();
    }

    private static String requiredString(FileConfiguration config, String path) {
        if (!config.isString(path)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a string");
        }
        return Objects.requireNonNull(config.getString(path));
    }

    private static String requiredNonBlankString(FileConfiguration config, String path) {
        String value = requiredString(config, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Configuration value " + path + " must not be blank");
        }
        return value;
    }

    private static int requiredInt(FileConfiguration config, String path) {
        if (!config.isInt(path)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be an integer");
        }
        return config.getInt(path);
    }

    private static int requiredPositiveInt(FileConfiguration config, String path) {
        int value = requiredInt(config, path);
        if (value <= 0) {
            throw new IllegalArgumentException("Configuration value " + path + " must be greater than 0");
        }
        return value;
    }

    private static int requiredPort(FileConfiguration config, String path) {
        int value = requiredInt(config, path);
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be between 1 and 65535");
        }
        return value;
    }

    private static boolean requiredBoolean(FileConfiguration config, String path) {
        if (!config.isBoolean(path)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a boolean");
        }
        return config.getBoolean(path);
    }

    private static ConfigurationSection requiredSection(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a mapping");
        }
        return section;
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

    public boolean isAsyncProcessing() {
        return asyncProcessing;
    }

    public int getNetworkTimeout() {
        return networkTimeout;
    }

    public boolean isReplaceVanilla() {
        return replaceVanilla;
    }

    public String getDefaultChannel() {
        return defaultChannel;
    }

    /**
     * @return the configured default locale tag (e.g. {@code "zh_CN"}),
     *         parsed by {@link com.nova.chat.client.i18n.LocaleResolver} at startup
     */
    public String getLocale() {
        return locale;
    }

    /**
     * @return the {@code chat.channel-prefixes} map (message prefix → channel id);
     *         empty when the feature is disabled
     */
    public Map<String, String> getChannelPrefixes() {
        return channelPrefixes;
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
     * <p>The configured reconnect delay and Folia network timeout are both
     * forwarded to the shared client.
     */
    public ClientConnectionConfig toClientConnectionConfig() {
        return ClientConnectionConfig.builder()
                .host(backendHost)
                .port(backendPort)
                .username(username)
                .password(password)
                .connectTimeoutMs(networkTimeout)
                .initialReconnectDelaySeconds(reconnectDelay)
                .build();
    }
}
