package com.nova.chat.bukkit.config;

import com.nova.chat.client.network.ClientConnectionConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration wrapper for NovaChat Bukkit plugin.
 * Parses and provides access to config.yml settings.
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

    // Plugin-messaging bridge to the proxy (novachat:main)
    private final boolean pluginMessagingEnabled;
    private final String pluginMessagingServerId;
    private final java.util.List<String> pluginMessagingProxyAddresses;

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

        // Chat settings
        this.replaceVanilla = requiredBoolean(config, "chat.replace_vanilla");
        this.defaultChannel = requiredNonBlankString(config, "chat.default_channel");
        this.locale = requiredNonBlankString(config, "chat.locale");

        // Channel prefixes (prefix -> channel id; empty map = feature disabled)
        this.channelPrefixes = new HashMap<>();
        ConfigurationSection prefixSection = requiredSection(config, "chat.channel-prefixes");
        for (String key : prefixSection.getKeys(false)) {
            String path = "chat.channel-prefixes." + key;
            String channelId = requiredString(config, path);
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

        // Plugin messaging: optional section so configs installed before the
        // channel existed keep loading (the template updater adds it on the
        // next start, but a missing section must not be fatal either).
        this.pluginMessagingEnabled = optionalBoolean(config, "plugin-messaging.enabled", true);
        String configuredServerId = optionalString(config, "plugin-messaging.server-id", "");
        this.pluginMessagingServerId = configuredServerId != null && !configuredServerId.isBlank()
                ? configuredServerId.trim()
                : this.username;
        this.pluginMessagingProxyAddresses = optionalStringList(
                config, "plugin-messaging.proxy-addresses");

        // Debug mode
        this.debug = requiredBoolean(config, "debug");

        toClientConnectionConfig();
    }

    private static boolean optionalBoolean(FileConfiguration config, String path,
                                           boolean fallback) {
        return config.isBoolean(path) ? config.getBoolean(path) : fallback;
    }

    private static String optionalString(FileConfiguration config, String path,
                                         String fallback) {
        return config.isString(path) ? config.getString(path) : fallback;
    }

    /**
     * Reads an optional string list, tolerating a single scalar value.
     *
     * @param config the configuration
     * @param path the list path
     * @return the entries, or an empty list when the key is absent
     */
    private static java.util.List<String> optionalStringList(FileConfiguration config,
                                                             String path) {
        if (config.isList(path)) {
            java.util.List<String> values = config.getStringList(path);
            return values != null ? java.util.List.copyOf(values) : java.util.List.of();
        }
        String single = optionalString(config, path, null);
        return single != null && !single.isBlank()
                ? java.util.List.of(single.trim())
                : java.util.List.of();
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

    public boolean isReplaceVanilla() {
        return replaceVanilla;
    }

    /**
     * @return whether the {@code novachat:main} plugin-messaging bridge is
     *         enabled (default true)
     */
    public boolean isPluginMessagingEnabled() {
        return pluginMessagingEnabled;
    }

    /**
     * Returns the identity this server declares on the plugin-messaging
     * channel. The proxy verifies it against the server the message actually
     * arrived from, so it must equal the name the proxy uses for this server.
     *
     * @return the configured server id, or {@code backend.username} when the
     *         key is blank/absent
     */
    public String getPluginMessagingServerId() {
        return pluginMessagingServerId;
    }

    /**
     * Addresses the proxy connects from, used to pick a plugin-message carrier
     * that is actually connected through the proxy.
     *
     * <p>Empty means "loopback only": a proxy co-located with this server
     * connects from {@code 127.0.0.1}. A remote proxy must be listed here (or
     * kept as a name that resolves to it), otherwise the bridge can only learn
     * a carrier from a downstream payload it has already received.
     *
     * @return the configured addresses, never null
     */
    public java.util.List<String> getPluginMessagingProxyAddresses() {
        return pluginMessagingProxyAddresses;
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
