package com.nova.chat.bungee.config;

import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.common.config.YamlConfigUpdater;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration wrapper for NovaChat BungeeCord plugin.
 * Parses and provides access to config.yml settings.
 */
public class NovaChatConfig {
    private static final Set<String> DYNAMIC_CONFIG_MAPPINGS = Set.of(
            "chat.channel-prefixes", "format.channels", "world-routing.mappings");

    // Backend connection settings
    private String backendHost;
    private int backendPort;
    private String username;
    private String password;
    private int reconnectDelay;

    // Chat settings
    private boolean replaceVanilla;
    private String defaultChannel;
    private String locale;
    private Map<String, String> channelPrefixes;

    // Format settings
    private String prefix;
    private String errorFormat;
    private String successFormat;
    private String defaultFormat;
    private Map<String, String> channelFormats;

    // Debug mode
    private boolean debug;
    private YamlConfigUpdater.UpdateResult updateResult;

    /**
     * Creates a new configuration from the given data folder.
     *
     * @param dataFolder the plugin data folder
     */
    public NovaChatConfig(File dataFolder) {
        this.channelFormats = new HashMap<>();
        this.channelPrefixes = new HashMap<>();
        loadConfig(dataFolder);
    }

    /**
     * Loads configuration from file, creating default if not exists.
     */
    private void loadConfig(File dataFolder) {
        File configFile = new File(dataFolder, "config.yml");
        try (InputStream template = getClass().getResourceAsStream("/config.yml")) {
            if (template == null) {
                throw new IOException("Bundled config.yml template is missing");
            }
            updateResult = YamlConfigUpdater.update(configFile.toPath(), template,
                    DYNAMIC_CONFIG_MAPPINGS);
            Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class)
                .load(configFile);
            parseConfig(config);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to install, upgrade, or load " + configFile, e);
        }
    }

    /**
     * Parses the YAML configuration.
     */
    private void parseConfig(Configuration config) {
        // Backend settings
        Configuration backend = Objects.requireNonNull(config.getSection("backend"),
                "Missing required configuration section: backend");
        this.backendHost = requiredNonBlankString(backend, "host", "backend.host");
        this.backendPort = requiredPort(backend, "port", "backend.port");
        this.username = requiredString(backend, "username", "backend.username");
        this.password = requiredString(backend, "password", "backend.password");
        this.reconnectDelay = requiredPositiveInt(
                backend, "reconnect-delay", "backend.reconnect-delay");

        // Chat settings
        Configuration chat = Objects.requireNonNull(config.getSection("chat"),
                "Missing required configuration section: chat");
        this.replaceVanilla = requiredBoolean(chat, "replace_vanilla", "chat.replace_vanilla");
        this.defaultChannel = requiredNonBlankString(
                chat, "default_channel", "chat.default_channel");
        this.locale = requiredNonBlankString(chat, "locale", "chat.locale");
        Configuration prefixes = requiredSection(chat, "channel-prefixes", "chat.channel-prefixes");
        for (String key : prefixes.getKeys()) {
            String channelId = requiredString(prefixes, key, "chat.channel-prefixes." + key);
            if (!key.isEmpty() && !channelId.isEmpty()) {
                channelPrefixes.put(key, channelId);
            }
        }

        // Format settings
        Configuration format = Objects.requireNonNull(config.getSection("format"),
                "Missing required configuration section: format");
        this.prefix = requiredString(format, "prefix", "format.prefix");
        this.errorFormat = requiredString(format, "error", "format.error");
        this.successFormat = requiredString(format, "success", "format.success");
        this.defaultFormat = requiredString(format, "default", "format.default");

        Configuration channels = requiredSection(format, "channels", "format.channels");
        for (String key : channels.getKeys()) {
            channelFormats.put(key, requiredString(channels, key, "format.channels." + key));
        }

        // Debug mode
        this.debug = requiredBoolean(config, "debug", "debug");

        toClientConnectionConfig();
    }

    private static Configuration requiredSection(Configuration parent, String key, String path) {
        Configuration section = parent.getSection(key);
        if (section == null) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a mapping");
        }
        return section;
    }

    private static String requiredString(Configuration parent, String key, String path) {
        Object value = parent.get(key);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a string");
        }
        return stringValue;
    }

    private static String requiredNonBlankString(Configuration parent, String key, String path) {
        String value = requiredString(parent, key, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Configuration value " + path + " must not be blank");
        }
        return value;
    }

    private static int requiredInt(Configuration parent, String key, String path) {
        Object value = parent.get(key);
        if (!(value instanceof Number numberValue)
                || !Double.isFinite(numberValue.doubleValue())
                || numberValue.doubleValue() != Math.rint(numberValue.doubleValue())
                || numberValue.doubleValue() < Integer.MIN_VALUE
                || numberValue.doubleValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Configuration value " + path + " must be an integer");
        }
        return numberValue.intValue();
    }

    private static int requiredPositiveInt(Configuration parent, String key, String path) {
        int value = requiredInt(parent, key, path);
        if (value <= 0) {
            throw new IllegalArgumentException("Configuration value " + path + " must be greater than 0");
        }
        return value;
    }

    private static int requiredPort(Configuration parent, String key, String path) {
        int value = requiredInt(parent, key, path);
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be between 1 and 65535");
        }
        return value;
    }

    private static boolean requiredBoolean(Configuration parent, String key, String path) {
        Object value = parent.get(key);
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a boolean");
        }
        return booleanValue;
    }

    // Getters

    public YamlConfigUpdater.UpdateResult getUpdateResult() {
        return updateResult;
    }

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
     * Gets the configured default locale string (e.g. {@code "zh_CN"},
     * {@code "en_US"}). Used to seed {@link com.nova.chat.client.i18n.I18n} at
     * startup; per-player client locales still override this.
     *
     * @return the locale string read from {@code chat.locale}
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
