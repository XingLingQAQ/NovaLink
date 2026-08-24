package com.nova.chat.velocity.config;

import com.moandjiezana.toml.Toml;
import com.nova.chat.client.network.ClientConnectionConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration wrapper for NovaChat Velocity plugin.
 * Parses and provides access to config.toml settings.
 */
public class NovaChatConfig {
    private static final Set<String> DYNAMIC_CONFIG_TABLES = Set.of(
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
    private TomlConfigUpdater.UpdateResult updateResult;

    /**
     * Creates a new configuration from the given data folder.
     *
     * @param dataFolder the plugin data folder
     */
    public NovaChatConfig(Path dataFolder) {
        this.channelFormats = new HashMap<>();
        this.channelPrefixes = new HashMap<>();
        loadConfig(dataFolder);
    }

    /**
     * Loads configuration from file, creating default if not exists.
     */
    private void loadConfig(Path dataFolder) {
        Path configPath = dataFolder.resolve("config.toml");
        try (InputStream template = getClass().getResourceAsStream("/config.toml")) {
            if (template == null) {
                throw new IOException("Bundled config.toml template is missing");
            }
            updateResult = TomlConfigUpdater.update(configPath, template,
                    DYNAMIC_CONFIG_TABLES);
            Toml toml = new Toml().read(configPath.toFile());
            parseConfig(toml);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to install, upgrade, or load " + configPath, e);
        }
    }

    /**
     * Parses the TOML configuration.
     */
    private void parseConfig(Toml toml) {
        // Backend settings
        Toml backend = requiredTable(toml, "backend");
        this.backendHost = requiredNonBlankString(backend, "host", "backend.host");
        this.backendPort = requiredPort(backend, "port", "backend.port");
        this.username = requiredString(backend, "username", "backend.username");
        this.password = requiredString(backend, "password", "backend.password");
        this.reconnectDelay = requiredPositiveInt(
                backend, "reconnect-delay", "backend.reconnect-delay");

        // Chat settings
        Toml chat = requiredTable(toml, "chat");
        this.replaceVanilla = requiredBoolean(chat, "replace_vanilla", "chat.replace_vanilla");
        this.defaultChannel = requiredNonBlankString(
                chat, "default_channel", "chat.default_channel");
        this.locale = requiredNonBlankString(chat, "locale", "chat.locale");

        // Channel prefixes (prefix -> channel id; empty map = feature disabled)
        Toml prefixes = requiredTable(chat, "channel-prefixes");
        for (Map.Entry<String, Object> entry : prefixes.entrySet()) {
            if (!(entry.getValue() instanceof String channelId)) {
                throw new IllegalArgumentException("Configuration value chat.channel-prefixes."
                        + entry.getKey() + " must be a string");
            }
            // toml4j keeps the quotes on quoted keys (e.g. "\"!\""); strip them.
            String key = stripQuotes(entry.getKey());
            if (!key.isEmpty() && !channelId.isEmpty()) {
                channelPrefixes.put(key, channelId);
            }
        }

        // Format settings
        Toml format = requiredTable(toml, "format");
        this.prefix = requiredString(format, "prefix", "format.prefix");
        this.errorFormat = requiredString(format, "error", "format.error");
        this.successFormat = requiredString(format, "success", "format.success");
        this.defaultFormat = requiredString(format, "default", "format.default");

        Toml channels = requiredTable(format, "channels");
        for (Map.Entry<String, Object> entry : channels.entrySet()) {
            if ("debug".equals(entry.getKey()) && entry.getValue() instanceof Boolean) {
                // Older templates stored the root debug flag in this table.
                // TomlConfigUpdater copies it to the root during migration.
                continue;
            }
            if (!(entry.getValue() instanceof String channelFormat)) {
                throw new IllegalArgumentException("Configuration value format.channels."
                        + entry.getKey() + " must be a string");
            }
            channelFormats.put(entry.getKey(), channelFormat);
        }

        // Debug mode
        this.debug = requiredBoolean(toml, "debug", "debug");

        toClientConnectionConfig();
    }

    private static Toml requiredTable(Toml parent, String key) {
        return Objects.requireNonNull(parent.getTable(key),
                "Missing required configuration table: " + key);
    }

    private static String requiredString(Toml parent, String key, String path) {
        return Objects.requireNonNull(parent.getString(key),
                "Missing required configuration value: " + path);
    }

    private static String requiredNonBlankString(Toml parent, String key, String path) {
        String value = requiredString(parent, key, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Configuration value " + path + " must not be blank");
        }
        return value;
    }

    private static Long requiredLong(Toml parent, String key, String path) {
        return Objects.requireNonNull(parent.getLong(key),
                "Missing required configuration value: " + path);
    }

    private static int requiredPositiveInt(Toml parent, String key, String path) {
        long value = requiredLong(parent, key, path);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be between 1 and " + Integer.MAX_VALUE);
        }
        return (int) value;
    }

    private static int requiredPort(Toml parent, String key, String path) {
        long value = requiredLong(parent, key, path);
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be between 1 and 65535");
        }
        return (int) value;
    }

    private static boolean requiredBoolean(Toml parent, String key, String path) {
        return Objects.requireNonNull(parent.getBoolean(key),
                "Missing or invalid boolean configuration value: " + path);
    }

    // Getters

    public boolean wasConfigCreated() {
        return updateResult != null && updateResult.created();
    }

    public boolean wasConfigUpdated() {
        return updateResult != null && updateResult.updated();
    }

    public Path getConfigBackupPath() {
        return updateResult != null ? updateResult.backupPath() : null;
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

    /**
     * @return the {@code chat.channel-prefixes} map (message prefix → channel id);
     *         empty when the feature is disabled
     */
    public Map<String, String> getChannelPrefixes() {
        return channelPrefixes;
    }

    /** Strips one layer of surrounding double quotes (toml4j quoted-key form). */
    private static String stripQuotes(String key) {
        if (key == null) {
            return "";
        }
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            return key.substring(1, key.length() - 1);
        }
        return key;
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
