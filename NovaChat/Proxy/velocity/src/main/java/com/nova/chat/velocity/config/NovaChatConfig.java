package com.nova.chat.velocity.config;

import com.moandjiezana.toml.Toml;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.config.ProxyConfigView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration wrapper for NovaChat Velocity plugin.
 *
 * <p>TOML reading, template installation/upgrade and dialect-level type
 * validation stay here (platform-owned per the proxy layering); the value model
 * and its accessors live in the shared {@link ProxyConfig}.
 */
public class NovaChatConfig implements ProxyConfigView {
    private static final Set<String> DYNAMIC_CONFIG_TABLES = Set.of(
            "chat.channel-prefixes", "format.channels", "world-routing.mappings");

    /** Shared immutable value model produced by {@link #parseConfig(Toml)}. */
    private ProxyConfig model = new ProxyConfig.Builder().build();

    private TomlConfigUpdater.UpdateResult updateResult;

    /**
     * Creates a new configuration from the given data folder.
     *
     * @param dataFolder the plugin data folder
     */
    public NovaChatConfig(Path dataFolder) {
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
     * Parses the TOML configuration into the shared value model.
     */
    private void parseConfig(Toml toml) {
        ProxyConfig.Builder builder = new ProxyConfig.Builder();

        // Backend settings
        Toml backend = requiredTable(toml, "backend");
        builder.backendHost(requiredNonBlankString(backend, "host", "backend.host"));
        builder.backendPort(requiredPort(backend, "port", "backend.port"));
        builder.username(requiredString(backend, "username", "backend.username"));
        builder.password(requiredString(backend, "password", "backend.password"));
        builder.reconnectDelay(requiredPositiveInt(
                backend, "reconnect-delay", "backend.reconnect-delay"));

        // Chat settings
        Toml chat = requiredTable(toml, "chat");
        builder.replaceVanilla(requiredBoolean(chat, "replace_vanilla", "chat.replace_vanilla"));
        builder.defaultChannel(requiredNonBlankString(
                chat, "default_channel", "chat.default_channel"));
        builder.locale(requiredNonBlankString(chat, "locale", "chat.locale"));

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
                builder.channelPrefix(key, channelId);
            }
        }

        // Format settings
        Toml format = requiredTable(toml, "format");
        builder.prefix(requiredString(format, "prefix", "format.prefix"));
        builder.errorFormat(requiredString(format, "error", "format.error"));
        builder.successFormat(requiredString(format, "success", "format.success"));
        builder.defaultFormat(requiredString(format, "default", "format.default"));

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
            builder.channelFormat(entry.getKey(), channelFormat);
        }

        // Debug mode
        builder.debug(requiredBoolean(toml, "debug", "debug"));

        this.model = builder.build();
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

    @Override
    public String getBackendHost() {
        return model.getBackendHost();
    }

    @Override
    public int getBackendPort() {
        return model.getBackendPort();
    }

    @Override
    public String getUsername() {
        return model.getUsername();
    }

    @Override
    public String getPassword() {
        return model.getPassword();
    }

    @Override
    public int getReconnectDelay() {
        return model.getReconnectDelay();
    }

    @Override
    public boolean isReplaceVanilla() {
        return model.isReplaceVanilla();
    }

    @Override
    public String getDefaultChannel() {
        return model.getDefaultChannel();
    }

    /**
     * Gets the configured default locale string (e.g. {@code "zh_CN"},
     * {@code "en_US"}). Used to seed {@link com.nova.chat.client.i18n.I18n} at
     * startup; per-player client locales still override this.
     *
     * @return the locale string read from {@code chat.locale}
     */
    @Override
    public String getLocale() {
        return model.getLocale();
    }

    /**
     * @return the {@code chat.channel-prefixes} map (message prefix → channel id);
     *         empty when the feature is disabled
     */
    @Override
    public Map<String, String> getChannelPrefixes() {
        return model.getChannelPrefixes();
    }

    @Override
    public String getPrefix() {
        return model.getPrefix();
    }

    @Override
    public String getErrorFormat() {
        return model.getErrorFormat();
    }

    @Override
    public String getSuccessFormat() {
        return model.getSuccessFormat();
    }

    @Override
    public String getDefaultFormat() {
        return model.getDefaultFormat();
    }

    @Override
    public Map<String, String> getChannelFormats() {
        return model.getChannelFormats();
    }

    /**
     * Gets the format for a specific channel.
     *
     * @param channelId the channel ID
     * @return the format string, or default format if not configured
     */
    @Override
    public String getChannelFormat(String channelId) {
        return model.getChannelFormat(channelId);
    }

    @Override
    public boolean isDebug() {
        return model.isDebug();
    }

    /**
     * Maps platform config to the shared {@link ClientConnectionConfig}.
     *
     * <p>The initial reconnect delay comes from {@code backend.reconnect-delay}.
     */
    @Override
    public ClientConnectionConfig toClientConnectionConfig() {
        return model.toClientConnectionConfig();
    }
}
