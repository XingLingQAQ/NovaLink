package com.nova.chat.proxy.config;

import com.nova.chat.client.network.ClientConnectionConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration model shared by every NovaChat proxy plugin.
 *
 * <p>Parses the common schema ({@code mode} / {@code backend} / {@code chat} /
 * {@code format} / {@code debug} / {@code lite}) out of a dialect-neutral
 * {@link ConfigSource}, so Velocity's {@code config.toml} and BungeeCord's
 * {@code config.yml} are validated by one implementation instead of two copies
 * that must be kept in step.
 *
 * <p>Validation rules (identical to the historical per-platform loaders):
 * <ul>
 *   <li>{@code mode} — {@code lite} or {@code relay} (case-insensitive);
 *       missing means {@link ProxyMode#RELAY}</li>
 *   <li>{@code backend.host} — required in relay, non-blank</li>
 *   <li>{@code backend.port} — required in relay, 1..65535</li>
 *   <li>{@code backend.username} / {@code backend.password} — required strings
 *       in relay (may be empty)</li>
 *   <li>{@code backend.reconnect-delay} — required in relay, positive</li>
 *   <li>{@code chat.replace_vanilla} — required boolean</li>
 *   <li>{@code chat.default_channel} / {@code chat.locale} — required, non-blank</li>
 *   <li>{@code chat.channel-prefixes} — string map, empty disables prefix routing</li>
 *   <li>{@code format.prefix|error|success|default} — required strings</li>
 *   <li>{@code format.channels} — string map; the legacy {@code debug} entry is
 *       ignored (older templates stored the root debug flag there)</li>
 *   <li>{@code debug} — required boolean</li>
 *   <li>{@code lite.channels} — required in lite, at least one entry, ids
 *       non-blank; {@code chat.default_channel} and every
 *       {@code chat.channel-prefixes} target must be one of them</li>
 *   <li>{@code lite.storage} — required in lite; validated per selected type</li>
 * </ul>
 *
 * <p>The {@code backend} section is parsed only in {@link ProxyMode#RELAY}.
 * Both bundled templates keep the section (the template update process cannot
 * remove keys), so lite ignores it rather than requiring operators to delete it.
 *
 * <p>The instance is treated as an immutable snapshot: the proxy swap the whole
 * object on reload rather than mutating it, matching the existing hot-path reads
 * in the chat listener and command handler.
 */
public final class ProxyConfig implements ProxyConfigView {

    /** The legacy {@code format.channels.debug} key ignored during parsing. */
    private static final String LEGACY_DEBUG_KEY = "debug";

    private final ProxyMode mode;
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
    private final String serverId;
    private final Map<String, LiteChannelConfig> liteChannels;
    private final LiteStorageConfig liteStorage;

    private ProxyConfig(Builder builder) {
        this.mode = builder.mode;
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
        this.serverId = builder.serverId;
        this.liteChannels = Collections.unmodifiableMap(new LinkedHashMap<>(builder.liteChannels));
        this.liteStorage = builder.liteStorage;
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
        builder.mode = ProxyMode.parse(source.optionalString("mode"));

        if (builder.mode == ProxyMode.RELAY) {
            builder.backendHost = nonBlank(source.requiredString("backend.host"), "backend.host");
            builder.backendPort = port(source.requiredInt("backend.port"), "backend.port");
            builder.username = source.requiredString("backend.username");
            builder.password = source.requiredString("backend.password");
            builder.reconnectDelay = positive(
                    source.requiredInt("backend.reconnect-delay"), "backend.reconnect-delay");
        }

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

        if (builder.mode == ProxyMode.LITE) {
            parseLite(source, builder);
        }
        return builder.build();
    }

    /**
     * Parses the {@code lite.*} section and enforces the cross-field rules.
     *
     * @param source the dialect accessor
     * @param builder the builder receiving the parsed values
     * @throws ConfigSource.ConfigException when the section is missing or invalid
     */
    private static void parseLite(ConfigSource source, Builder builder)
            throws ConfigSource.ConfigException {
        builder.serverId = nonBlank(
                source.requiredString("lite.server-id"), "lite.server-id");

        Map<String, Object> channels = source.requiredTable("lite.channels");
        if (channels.isEmpty()) {
            throw new ConfigSource.ConfigException(
                    "Configuration value lite.channels must declare at least one channel");
        }
        for (Map.Entry<String, Object> entry : channels.entrySet()) {
            String channelId = stripQuotes(entry.getKey());
            if (channelId.isBlank()) {
                throw new ConfigSource.ConfigException(
                        "Configuration value lite.channels contains a blank channel id");
            }
            if (builder.liteChannels.containsKey(channelId)) {
                throw new ConfigSource.ConfigException(
                        "Configuration value lite.channels." + channelId + " is declared twice");
            }
            builder.liteChannels.put(channelId, parseLiteChannel(channelId, entry.getValue()));
        }

        if (!builder.liteChannels.containsKey(builder.defaultChannel)) {
            throw new ConfigSource.ConfigException(
                    "Configuration value chat.default_channel refers to undefined channel: "
                            + builder.defaultChannel + " (declared in lite.channels: "
                            + String.join(", ", builder.liteChannels.keySet()) + ")");
        }
        for (String target : builder.channelPrefixes.values()) {
            if (!builder.liteChannels.containsKey(target)) {
                throw new ConfigSource.ConfigException(
                        "Configuration value chat.channel-prefixes refers to undefined channel: "
                                + target + " (declared in lite.channels: "
                                + String.join(", ", builder.liteChannels.keySet()) + ")");
            }
        }

        builder.liteStorage = LiteStorageConfig.parse(source);
    }

    /**
     * Parses one {@code lite.channels.<id>} inline table.
     *
     * @param channelId the channel id taken from the entry key
     * @param raw the raw entry value
     * @return the parsed channel
     * @throws ConfigSource.ConfigException when the entry is not a table or a
     *         field is invalid
     */
    private static LiteChannelConfig parseLiteChannel(String channelId, Object raw)
            throws ConfigSource.ConfigException {
        Map<String, Object> values = asStringMap(raw);
        if (values == null) {
            throw new ConfigSource.ConfigException(
                    "Configuration value lite.channels." + channelId + " must be a table");
        }

        String path = "lite.channels." + channelId;
        String displayName = optionalString(values, "display-name", path);
        if (displayName != null && displayName.isBlank()) {
            throw new ConfigSource.ConfigException(
                    "Configuration value " + path + ".display-name must not be blank");
        }
        String permission = optionalString(values, "permission", path);
        int maxCapacity = optionalNonNegativeInt(values, "max-capacity", path);
        int slowMode = optionalNonNegativeInt(values, "slow-mode", path);

        return new LiteChannelConfig(
                channelId,
                displayName != null ? displayName : channelId,
                permission,
                maxCapacity,
                slowMode);
    }

    /**
     * Normalises a raw table value into a string-keyed map.
     *
     * <p>Implementations return either a {@code Map} (toml4j's {@code toMap}
     * view) or a dialect section object; both are reduced to a map here so the
     * value checks live once. {@code null} means the value is not a table.
     *
     * @param raw the raw value
     * @return the entries, or null when {@code raw} is not a table
     */
    private static Map<String, Object> asStringMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    values.put(stripQuotes(key), entry.getValue());
                }
            }
            return values;
        }
        return null;
    }

    private static String optionalString(Map<String, Object> values, String key, String path)
            throws ConfigSource.ConfigException {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new ConfigSource.ConfigException(
                    "Configuration value " + path + "." + key + " must be a string");
        }
        return text;
    }

    private static int optionalNonNegativeInt(Map<String, Object> values, String key, String path)
            throws ConfigSource.ConfigException {
        Object value = values.get(key);
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Number number)) {
            throw new ConfigSource.ConfigException(
                    "Configuration value " + path + "." + key + " must be an integer");
        }
        int parsed = number.intValue();
        if (parsed < 0) {
            throw new ConfigSource.ConfigException(
                    "Configuration value " + path + "." + key + " must not be negative");
        }
        return parsed;
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

    @Override
    public ProxyMode getMode() {
        return mode;
    }

    /**
     * @return the lite client identity ({@code lite.server-id}); null in relay
     *         mode, where {@link #getUsername()} is the identity
     */
    public String getServerId() {
        return serverId;
    }

    /**
     * Returns this proxy's client identity independent of the mode: the backend
     * username in relay, {@code lite.server-id} in lite.
     *
     * @return the client identity, never null
     */
    @Override
    public String getClientId() {
        return mode == ProxyMode.LITE ? serverId : username;
    }

    /**
     * @return the {@code lite.channels} declarations (id → channel); empty in
     *         relay mode
     */
    public Map<String, LiteChannelConfig> getLiteChannels() {
        return liteChannels;
    }

    /**
     * @return the parsed {@code lite.storage} settings, or null in relay mode
     */
    public LiteStorageConfig getLiteStorage() {
        return liteStorage;
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
     * @throws IllegalStateException when called in {@link ProxyMode#LITE} — there
     *         is no backend to connect to, so reaching this is a programming
     *         error rather than an operator mistake. The config loader skips the
     *         call in lite mode; this guard makes a missed branch loud instead of
     *         silently producing a connection config for an unvalidated backend.
     */
    public ClientConnectionConfig toClientConnectionConfig() {
        if (mode != ProxyMode.RELAY) {
            throw new IllegalStateException(
                    "toClientConnectionConfig() is only available in relay mode (mode=" + mode + ")");
        }
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
        private ProxyMode mode = ProxyMode.RELAY;
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
        private String serverId;
        private final Map<String, LiteChannelConfig> liteChannels = new LinkedHashMap<>();
        private LiteStorageConfig liteStorage;

        /**
         * @param value the operating mode (null means {@link ProxyMode#RELAY})
         * @return this builder
         */
        public Builder mode(ProxyMode value) {
            this.mode = value != null ? value : ProxyMode.RELAY;
            return this;
        }

        /**
         * @param value the lite client identity
         * @return this builder
         */
        public Builder serverId(String value) {
            this.serverId = value;
            return this;
        }

        /**
         * @param channel the lite channel to declare
         * @return this builder
         */
        public Builder liteChannel(LiteChannelConfig channel) {
            this.liteChannels.put(channel.getId(), channel);
            return this;
        }

        /**
         * @param value the lite storage settings
         * @return this builder
         */
        public Builder liteStorage(LiteStorageConfig value) {
            this.liteStorage = value;
            return this;
        }

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
