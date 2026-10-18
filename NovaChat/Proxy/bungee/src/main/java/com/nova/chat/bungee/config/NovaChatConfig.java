package com.nova.chat.bungee.config;

import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.common.config.YamlConfigUpdater;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.config.ProxyConfigView;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration wrapper for NovaChat BungeeCord plugin.
 *
 * <p>YAML reading, template installation/upgrade and dialect-level type
 * validation stay here (platform-owned per the proxy layering); the value model
 * and its accessors live in the shared {@link ProxyConfig}.
 */
public class NovaChatConfig implements ProxyConfigView {
    private static final Set<String> DYNAMIC_CONFIG_MAPPINGS = Set.of(
            "chat.channel-prefixes", "format.channels", "world-routing.mappings");

    /** Shared immutable value model produced by {@link #parseConfig(Configuration)}. */
    private ProxyConfig model = new ProxyConfig.Builder().build();

    private YamlConfigUpdater.UpdateResult updateResult;

    /**
     * Creates a new configuration from the given data folder.
     *
     * @param dataFolder the plugin data folder
     */
    public NovaChatConfig(File dataFolder) {
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
     * Parses the YAML configuration into the shared value model.
     */
    private void parseConfig(Configuration config) {
        ProxyConfig.Builder builder = new ProxyConfig.Builder();

        // Backend settings
        Configuration backend = Objects.requireNonNull(config.getSection("backend"),
                "Missing required configuration section: backend");
        builder.backendHost(requiredNonBlankString(backend, "host", "backend.host"));
        builder.backendPort(requiredPort(backend, "port", "backend.port"));
        builder.username(requiredString(backend, "username", "backend.username"));
        builder.password(requiredString(backend, "password", "backend.password"));
        builder.reconnectDelay(requiredPositiveInt(
                backend, "reconnect-delay", "backend.reconnect-delay"));

        // Chat settings
        Configuration chat = Objects.requireNonNull(config.getSection("chat"),
                "Missing required configuration section: chat");
        builder.replaceVanilla(requiredBoolean(chat, "replace_vanilla", "chat.replace_vanilla"));
        builder.defaultChannel(requiredNonBlankString(
                chat, "default_channel", "chat.default_channel"));
        builder.locale(requiredNonBlankString(chat, "locale", "chat.locale"));
        Configuration prefixes = requiredSection(chat, "channel-prefixes", "chat.channel-prefixes");
        for (String key : prefixes.getKeys()) {
            String channelId = requiredString(prefixes, key, "chat.channel-prefixes." + key);
            if (!key.isEmpty() && !channelId.isEmpty()) {
                builder.channelPrefix(key, channelId);
            }
        }

        // Format settings
        Configuration format = Objects.requireNonNull(config.getSection("format"),
                "Missing required configuration section: format");
        builder.prefix(requiredString(format, "prefix", "format.prefix"));
        builder.errorFormat(requiredString(format, "error", "format.error"));
        builder.successFormat(requiredString(format, "success", "format.success"));
        builder.defaultFormat(requiredString(format, "default", "format.default"));

        Configuration channels = requiredSection(format, "channels", "format.channels");
        for (String key : channels.getKeys()) {
            builder.channelFormat(key, requiredString(channels, key, "format.channels." + key));
        }

        // Debug mode
        builder.debug(requiredBoolean(config, "debug", "debug"));

        this.model = builder.build();
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
     * Gets the configured channel-prefix routing map
     * ({@code chat.channel-prefixes}: prefix string → channel ID). Empty map
     * (the default) disables prefix routing.
     *
     * @return the prefix→channelId map, never null
     */
    @Override
    public Map<String, String> getChannelPrefixes() {
        return model.getChannelPrefixes();
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
