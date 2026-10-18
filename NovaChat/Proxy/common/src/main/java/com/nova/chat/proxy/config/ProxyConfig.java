package com.nova.chat.proxy.config;

import com.nova.chat.client.network.ClientConnectionConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration model shared by every NovaChat proxy plugin.
 *
 * <p>Parses the common schema ({@code backend} / {@code chat} / {@code format} /
 * {@code debug}) out of a dialect-neutral {@link ConfigSource}, so Velocity's
 * {@code config.toml} and BungeeCord's {@code config.yml} are validated by one
 * implementation instead of two copies that must be kept in step.
 *
 * <p>Validation rules (identical to the historical per-platform loaders):
 * <ul>
 *   <li>{@code backend.host} — required, non-blank</li>
 *   <li>{@code backend.port} — required, 1..65535</li>
 *   <li>{@code backend.username} / {@code backend.password} — required strings
 *       (may be empty)</li>
 *   <li>{@code backend.reconnect-delay} — required, positive</li>
 *   <li>{@code chat.replace_vanilla} — required boolean</li>
 *   <li>{@code chat.default_channel} / {@code chat.locale} — required, non-blank</li>
 *   <li>{@code chat.channel-prefixes} — string map, empty disables prefix routing</li>
 *   <li>{@code format.prefix|error|success|default} — required strings</li>
 *   <li>{@code format.channels} — string map; the legacy {@code debug} entry is
 *       ignored (older templates stored the root debug flag there)</li>
 *   <li>{@code debug} — required boolean</li>
 * </ul>
 *
 * <p>The instance is treated as an immutable snapshot: the proxy swap the whole
 * object on reload rather than mutating it, matching the existing hot-path reads
 * in the chat listener and command handler.
 */
public final class ProxyConfig implements ProxyConfigView {

    /** The legacy {@code format.channels.debug} key ignored during parsing. */
    private static final String LEGACY_DEBUG_KEY = "debug";

    private final String backendHost;
    private final int backendPort;
    private final String username;
    private final String password;
    private final int reconnectDelay;
    private final boolean replaceVanilla;
    private final String defaultChannel;
    private final String locale;
    private final Map<String, String> channelPrefixes;
    private final String prefix;
    private final String errorFormat;
    private final String successFormat;
    private final String defaultFormat;
    private final Map<String, String> channelFormats;
    private final boolean debug;

    private ProxyConfig(Builder builder) {
        this.backendHost = builder.backendHost;
        this.backendPort = builder.backendPort;
        this.username = builder.username;
        this.password = builder.password;
        this.reconnectDelay = builder.reconnectDelay;
        this.replaceVanilla = builder.replaceVanilla;
        this.defaultChannel = builder.defaultChannel;
        this.locale = builder.locale;
        this.channelPrefixes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.channelPrefixes));
        this.prefix = builder.prefix;
        this.errorFormat = builder.errorFormat;
        this.successFormat = builder.successFormat;
        this.defaultFormat = builder.defaultFormat;
        this.channelFormats = Collections.unmodifiableMap(new LinkedHashMap<>(builder.channelFormats));
        this.debug = builder.debug;
    }

    /**
     * Parses and validates the shared schema out of a dialect source.
     *
     * @param source the dialect accessor
     * @return the parsed immutable snapshot
     * @throws ConfigSource.ConfigException when a value is missing or invalid
     */
    public static ProxyConfig from(ConfigSource source) throws ConfigSource.ConfigException {
        Objects.requireNonNull(source, "source");

        Builder builder = new Builder();
        builder.backendHost = nonBlank(source.requiredString("backend.host"), "backend.host");
        builder.backendPort = port(source.requiredInt("backend.port"), "backend.port");
        builder.username = source.requiredString("backend.username");
        builder.password = source.requiredString("backend.password");
        builder.reconnectDelay = positive(
                source.requiredInt("backend.reconnect-delay"), "backend.reconnect-delay");

        builder.replaceVanilla = source.requiredBoolean("chat.replace_vanilla");
        builder.defaultChannel = nonBlank(
                source.requiredString("chat.default_channel"), "chat.default_channel");
        builder.locale = nonBlank(source.requiredString("chat.locale"), "chat.locale");
        // Prefix routing: empty map (the default) disables the feature. Both
        // dialects keep surrounding quotes on quoted keys (e.g. "\"!\""); strip
        // them so "!" and "!"-quoted forms both route.
        for (Map.Entry<String, Object> entry
                : source.requiredTable("chat.channel-prefixes").entrySet()) {
            String path = "chat.channel-prefixes." + entry.getKey();
            if (!(entry.getValue() instanceof String channelId)) {
                throw new ConfigSource.ConfigException(
                        "Configuration value " + path + " must be a string");
            }
            String key = stripQuotes(entry.getKey());
            if (!key.isEmpty() && !channelId.isEmpty()) {
                builder.channelPrefixes.put(key, channelId);
            }
        }

        builder.prefix = source.requiredString("format.prefix");
        builder.errorFormat = source.requiredString("format.error");
        builder.successFormat = source.requiredString("format.success");
        builder.defaultFormat = source.requiredString("format.default");
        for (Map.Entry<String, Object> entry
                : source.requiredTable("format.channels").entrySet()) {
            // Older templates stored the root debug flag inside [format.channels]
            // as a boolean; the dialect updaters copy it to the root during
            // migration, so a boolean here is skipped rather than rejected.
            if (LEGACY_DEBUG_KEY.equals(entry.getKey())
                    && entry.getValue() instanceof Boolean) {
                continue;
            }
            if (!(entry.getValue() instanceof String channelFormat)) {
                throw new ConfigSource.ConfigException(
                        "Configuration value format.channels." + entry.getKey()
                                + " must be a string");
            }
            builder.channelFormats.put(entry.getKey(), channelFormat);
        }

        builder.debug = source.requiredBoolean("debug");
        return builder.build();
    }

    /**
     * Strips one layer of surrounding double/single quotes; toml4j and the
     * BungeeCord YAML loader both report quoted map keys with their quotes.
     *
     * @param key the raw key, may be null
     * @return the unquoted key, never null
     */
    private static String stripQuotes(String key) {
        if (key == null) {
            return "";
        }
        if (key.length() >= 2) {
            char first = key.charAt(0);
            char last = key.charAt(key.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return key.substring(1, key.length() - 1);
            }
        }
        return key;
    }

    private static String nonBlank(String value, String path) throws ConfigSource.ConfigException {
        if (value == null || value.isBlank()) {
            throw new ConfigSource.ConfigException(
                    "Configuration value " + path + " must not be blank");
        }
        return value;
    }

    private static int port(int value, String path) throws ConfigSource.ConfigException {
        if (value < 1 || value > 65535) {
            throw new ConfigSource.ConfigException(
                    "Configuration value " + path + " must be between 1 and 65535");
        }
        return value;
    }

    private static int positive(int value, String path) throws ConfigSource.ConfigException {
        if (value <= 0) {
            throw new ConfigSource.ConfigException(
                    "Configuration value " + path + " must be greater than 0");
        }
        return value;
    }

    // ---------------------------------------------------------------- accessors

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
     * @return the configured default locale string (e.g. {@code "zh_CN"});
     *         per-player client locales still override it
     */
    public String getLocale() {
        return locale;
    }

    /**
     * @return the {@code chat.channel-prefixes} map (prefix → channel id);
     *         empty when prefix routing is disabled
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
     * @param channelId the channel id
     * @return the configured format for the channel, or the default format
     */
    public String getChannelFormat(String channelId) {
        return channelFormats.getOrDefault(channelId, defaultFormat);
    }

    public boolean isDebug() {
        return debug;
    }

    /**
     * Maps the proxy settings onto the shared {@link ClientConnectionConfig};
     * the initial reconnect delay comes from {@code backend.reconnect-delay}.
     *
     * @return the connection config for the shared network client
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

    /** Programmatic builder used by {@link #from(ConfigSource)} and by tests. */
    public static final class Builder {
        private String backendHost = "127.0.0.1";
        private int backendPort = ClientConnectionConfig.DEFAULT_PORT;
        private String username = "";
        private String password = "";
        private int reconnectDelay = ClientConnectionConfig.DEFAULT_INITIAL_RECONNECT_DELAY_SECONDS;
        private boolean replaceVanilla;
        private String defaultChannel = "local";
        private String locale = "zh_CN";
        private final Map<String, String> channelPrefixes = new LinkedHashMap<>();
        private String prefix = "";
        private String errorFormat = "{message}";
        private String successFormat = "{message}";
        private String defaultFormat = "{message}";
        private final Map<String, String> channelFormats = new LinkedHashMap<>();
        private boolean debug;

        public Builder backendHost(String value) {
            this.backendHost = value;
            return this;
        }

        public Builder backendPort(int value) {
            this.backendPort = value;
            return this;
        }

        public Builder username(String value) {
            this.username = value;
            return this;
        }

        public Builder password(String value) {
            this.password = value;
            return this;
        }

        public Builder reconnectDelay(int value) {
            this.reconnectDelay = value;
            return this;
        }

        public Builder replaceVanilla(boolean value) {
            this.replaceVanilla = value;
            return this;
        }

        public Builder defaultChannel(String value) {
            this.defaultChannel = value;
            return this;
        }

        public Builder locale(String value) {
            this.locale = value;
            return this;
        }

        /**
         * @param key prefix string
         * @param channelId target channel id
         * @return this builder
         */
        public Builder channelPrefix(String key, String channelId) {
            this.channelPrefixes.put(key, channelId);
            return this;
        }

        public Builder prefix(String value) {
            this.prefix = value;
            return this;
        }

        public Builder errorFormat(String value) {
            this.errorFormat = value;
            return this;
        }

        public Builder successFormat(String value) {
            this.successFormat = value;
            return this;
        }

        public Builder defaultFormat(String value) {
            this.defaultFormat = value;
            return this;
        }

        /**
         * @param channelId channel id
         * @param format format template
         * @return this builder
         */
        public Builder channelFormat(String channelId, String format) {
            this.channelFormats.put(channelId, format);
            return this;
        }

        public Builder debug(boolean value) {
            this.debug = value;
            return this;
        }

        public ProxyConfig build() {
            return new ProxyConfig(this);
        }
    }
}
