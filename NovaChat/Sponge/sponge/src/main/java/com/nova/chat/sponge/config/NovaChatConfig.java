package com.nova.chat.sponge.config;

import com.nova.chat.client.network.ClientConnectionConfig;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration wrapper for NovaChat Sponge plugin.
 * Parses and provides access to config.yml settings using Configurate.
 * 
 * Requirements: 3.1
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
     * Creates a new configuration from the given ConfigurationNode.
     *
     * @param rootNode the root configuration node, already completed from the bundled template
     */
    public NovaChatConfig(CommentedConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "Configuration root cannot be null");

        // Backend settings
        CommentedConfigurationNode backendNode = requireMap(rootNode, "backend", "backend");
        this.backendHost = requireNonBlankString(backendNode, "host", "backend.host");
        this.backendPort = requirePort(backendNode, "port", "backend.port");
        this.username = requireNonBlankString(backendNode, "username", "backend.username");
        this.password = requireString(backendNode, "password", "backend.password");
        this.reconnectDelay = requirePositiveInt(
                backendNode, "reconnect-delay", "backend.reconnect-delay");

        // Chat settings
        CommentedConfigurationNode chatNode = requireMap(rootNode, "chat", "chat");
        this.replaceVanilla = requireBoolean(
                chatNode, "replace_vanilla", "chat.replace_vanilla");
        this.defaultChannel = requireNonBlankString(
                chatNode, "default_channel", "chat.default_channel");
        this.locale = requireNonBlankString(chatNode, "locale", "chat.locale");

        // Channel-prefix routing (prefix string -> channel ID); empty = disabled
        this.channelPrefixes = new HashMap<>();
        CommentedConfigurationNode prefixesNode = chatNode.node("channel-prefixes");
        if (!prefixesNode.isMap()) {
            throw new IllegalArgumentException(
                    "Configuration value chat.channel-prefixes must be a mapping");
        }
        if (!prefixesNode.virtual()) {
            for (Map.Entry<Object, CommentedConfigurationNode> entry : prefixesNode.childrenMap().entrySet()) {
                String key = entry.getKey().toString();
                Object rawValue = entry.getValue().raw();
                if (!(rawValue instanceof String value)) {
                    throw new IllegalArgumentException(
                            "Configuration value chat.channel-prefixes." + key + " must be a string");
                }
                if (!key.isEmpty() && !value.isEmpty()) {
                    channelPrefixes.put(key, value);
                }
            }
        }

        // Format settings
        CommentedConfigurationNode formatNode = requireMap(rootNode, "format", "format");
        this.prefix = requireString(formatNode, "prefix", "format.prefix");
        this.errorFormat = requireString(formatNode, "error", "format.error");
        this.successFormat = requireString(formatNode, "success", "format.success");
        this.defaultFormat = requireString(formatNode, "default", "format.default");

        // Channel formats
        this.channelFormats = new HashMap<>();
        CommentedConfigurationNode channelsNode = formatNode.node("channels");
        if (!channelsNode.isMap()) {
            throw new IllegalArgumentException(
                    "Configuration value format.channels must be a mapping");
        }
        if (!channelsNode.virtual()) {
            for (Map.Entry<Object, CommentedConfigurationNode> entry : channelsNode.childrenMap().entrySet()) {
                String key = entry.getKey().toString();
                Object rawValue = entry.getValue().raw();
                if (!(rawValue instanceof String value)) {
                    throw new IllegalArgumentException(
                            "Configuration value format.channels." + key + " must be a string");
                }
                channelFormats.put(key, value);
            }
        }

        // Debug mode
        this.debug = requireBoolean(rootNode, "debug", "debug");

        toClientConnectionConfig();
    }

    private static CommentedConfigurationNode requireMap(
            CommentedConfigurationNode parent, String key, String path) {
        CommentedConfigurationNode value = parent.node(key);
        if (!value.isMap()) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a mapping");
        }
        return value;
    }

    private static String requireString(
            CommentedConfigurationNode parent, String key, String path) {
        Object value = parent.node(key).raw();
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a string");
        }
        return stringValue;
    }

    private static String requireNonBlankString(
            CommentedConfigurationNode parent, String key, String path) {
        String value = requireString(parent, key, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Configuration value " + path + " must not be blank");
        }
        return value;
    }

    private static int requireInt(
            CommentedConfigurationNode parent, String key, String path) {
        Object value = parent.node(key).raw();
        if (!(value instanceof Number numberValue)
                || !Double.isFinite(numberValue.doubleValue())
                || numberValue.doubleValue() != Math.rint(numberValue.doubleValue())
                || numberValue.doubleValue() < Integer.MIN_VALUE
                || numberValue.doubleValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Configuration value " + path + " must be an integer");
        }
        return numberValue.intValue();
    }

    private static int requirePositiveInt(
            CommentedConfigurationNode parent, String key, String path) {
        int value = requireInt(parent, key, path);
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be greater than 0");
        }
        return value;
    }

    private static int requirePort(
            CommentedConfigurationNode parent, String key, String path) {
        int value = requireInt(parent, key, path);
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be between 1 and 65535");
        }
        return value;
    }

    private static boolean requireBoolean(
            CommentedConfigurationNode parent, String key, String path) {
        Object value = parent.node(key).raw();
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a boolean");
        }
        return booleanValue;
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
     * @return the locale tag read from {@code chat.locale}, used to seed
     *         {@link com.nova.chat.client.i18n.I18n#setDefaultLocale} at startup
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
