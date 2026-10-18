package com.nova.chat.proxy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the shared proxy configuration model.
 *
 * <p>Both dialects feed {@link ProxyConfig#from(ConfigSource)}, so this suite
 * pins the validation rules once for Velocity's TOML and Bungee's YAML.
 */
@DisplayName("ProxyConfig shared model")
class ProxyConfigTest {

    /** In-memory ConfigSource mirroring the bundled template defaults. */
    private static final class MapSource implements ConfigSource {
        private final Map<String, Object> values = new LinkedHashMap<>();

        MapSource templateDefaults() {
            values.put("mode", "relay");
            values.put("backend.host", "127.0.0.1");
            values.put("backend.port", 8888L);
            values.put("backend.username", "");
            values.put("backend.password", "");
            values.put("backend.reconnect-delay", 5L);
            values.put("chat.replace_vanilla", false);
            values.put("chat.default_channel", "local");
            values.put("chat.locale", "zh_CN");
            values.put("chat.channel-prefixes", new LinkedHashMap<String, Object>());
            values.put("format.prefix", "&8[&bNovaChat&8]&r ");
            values.put("format.error", "&c错误: {message}");
            values.put("format.success", "&a成功: {message}");
            values.put("format.default", "&7[{channel_name}] {player}&f: {message}");
            values.put("format.channels", new LinkedHashMap<>(Map.of(
                    "global", "&c[全服] &7{player}&f: {message}")));
            values.put("debug", false);
            return this;
        }

        /** Replaces the schema with the lite sections declared by the template. */
        MapSource liteDefaults() {
            templateDefaults();
            values.remove("backend.host");
            values.remove("backend.port");
            values.remove("backend.username");
            values.remove("backend.password");
            values.remove("backend.reconnect-delay");
            values.put("mode", "lite");
            values.put("lite.server-id", "proxy-1");
            values.put("lite.channels", new LinkedHashMap<>(Map.of(
                    "global", new LinkedHashMap<String, Object>(Map.of(
                            "display-name", "全服",
                            "permission", "novachat.channel.global",
                            "max-capacity", 1000L,
                            "slow-mode", 0L)),
                    "local", new LinkedHashMap<String, Object>(Map.of(
                            "display-name", "本地")))));
            values.put("lite.storage", new LinkedHashMap<>(Map.of("type", "memory")));
            return this;
        }

        MapSource put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        /**
         * Resolves a dot path.
         *
         * <p>The source is a flat map of dotted keys whose values may themselves
         * be nested maps. The longest flat prefix wins, then the remaining
         * segments are walked through nested maps — so both
         * {@code put("lite.storage", map)} and a nested {@code lite} map work.
         */
        private Object lookup(String key) {
            String[] segments = key.split("\\.");
            for (int end = segments.length; end >= 1; end--) {
                String prefix = String.join(".", java.util.Arrays.copyOfRange(segments, 0, end));
                if (!values.containsKey(prefix)) {
                    continue;
                }
                Object current = values.get(prefix);
                for (int i = end; i < segments.length; i++) {
                    if (!(current instanceof Map<?, ?> map) || !map.containsKey(segments[i])) {
                        return null;
                    }
                    current = map.get(segments[i]);
                }
                return current;
            }
            return null;
        }

        @Override
        public String requiredString(String key) throws ConfigException {
            Object value = lookup(key);
            if (value == null) {
                throw new ConfigException("Missing required configuration value: " + key);
            }
            if (!(value instanceof String text)) {
                throw new ConfigException("Configuration value " + key + " must be a string");
            }
            return text;
        }

        @Override
        public int requiredInt(String key) throws ConfigException {
            Object value = lookup(key);
            if (value == null) {
                throw new ConfigException("Missing required configuration value: " + key);
            }
            if (!(value instanceof Number number)) {
                throw new ConfigException("Configuration value " + key + " must be an integer");
            }
            return number.intValue();
        }

        @Override
        public boolean requiredBoolean(String key) throws ConfigException {
            Object value = lookup(key);
            if (value == null) {
                throw new ConfigException(
                        "Missing or invalid boolean configuration value: " + key);
            }
            if (!(value instanceof Boolean bool)) {
                throw new ConfigException("Configuration value " + key + " must be a boolean");
            }
            return bool;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Map<String, Object> requiredTable(String key) throws ConfigException {
            Object value = lookup(key);
            if (value == null) {
                throw new ConfigException("Missing required configuration table: " + key);
            }
            if (!(value instanceof Map)) {
                throw new ConfigException("Configuration value " + key + " must be a mapping");
            }
            return (Map<String, Object>) value;
        }

        @Override
        public boolean contains(String key) {
            return lookup(key) != null;
        }
    }

    @Test
    @DisplayName("parses the bundled template defaults")
    void parsesTemplateDefaults() throws Exception {
        ProxyConfig config = ProxyConfig.from(new MapSource().templateDefaults());

        assertThat(config.getBackendHost()).isEqualTo("127.0.0.1");
        assertThat(config.getBackendPort()).isEqualTo(8888);
        assertThat(config.getReconnectDelay()).isEqualTo(5);
        assertThat(config.isReplaceVanilla()).isFalse();
        assertThat(config.getDefaultChannel()).isEqualTo("local");
        assertThat(config.getLocale()).isEqualTo("zh_CN");
        assertThat(config.getChannelPrefixes()).isEmpty();
        assertThat(config.getChannelFormats()).containsOnlyKeys("global");
        assertThat(config.getChannelFormat("missing")).isEqualTo(config.getDefaultFormat());
        assertThat(config.isDebug()).isFalse();
    }

    @Test
    @DisplayName("strips the quotes toml4j keeps on quoted channel-prefix keys")
    void stripsQuotesFromPrefixKeys() throws Exception {
        MapSource source = new MapSource().templateDefaults();
        source.values.put("chat.channel-prefixes",
                new LinkedHashMap<>(Map.of("\"!\"", "global", "$", "trade")));

        ProxyConfig config = ProxyConfig.from(source);

        assertThat(config.getChannelPrefixes()).containsEntry("!", "global")
                .containsEntry("$", "trade");
    }

    @Test
    @DisplayName("ignores the legacy format.channels.debug entry")
    void ignoresLegacyDebugChannelEntry() throws Exception {
        MapSource source = new MapSource().templateDefaults();
        source.values.put("format.channels", new LinkedHashMap<>(Map.of(
                "global", "g", "debug", true)));

        ProxyConfig config = ProxyConfig.from(source);

        assertThat(config.getChannelFormats()).containsOnlyKeys("global");
    }

    @Test
    @DisplayName("rejects a blank default channel")
    void rejectsBlankDefaultChannel() {
        MapSource source = new MapSource().templateDefaults().put("chat.default_channel", "  ");

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("chat.default_channel")
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("rejects an out-of-range port")
    void rejectsOutOfRangePort() {
        MapSource source = new MapSource().templateDefaults().put("backend.port", 70000L);

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("backend.port")
                .hasMessageContaining("between 1 and 65535");
    }

    @Test
    @DisplayName("rejects a non-positive reconnect delay")
    void rejectsNonPositiveReconnectDelay() {
        MapSource source = new MapSource().templateDefaults()
                .put("backend.reconnect-delay", 0L);

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("backend.reconnect-delay");
    }

    @Test
    @DisplayName("rejects a non-string channel format")
    void rejectsNonStringChannelFormat() {
        MapSource source = new MapSource().templateDefaults();
        source.values.put("format.channels", new LinkedHashMap<>(Map.of("global", 42)));

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("format.channels.global")
                .hasMessageContaining("must be a string");

    }

    @Test
    @DisplayName("maps onto the shared ClientConnectionConfig")
    void mapsOntoClientConnectionConfig() throws Exception {
        ProxyConfig config = ProxyConfig.from(new MapSource().templateDefaults()
                .put("backend.host", "backend.example.test")
                .put("backend.port", 9999L)
                .put("backend.username", "proxy-1")
                .put("backend.reconnect-delay", 7L));

        assertThat(config.toClientConnectionConfig().getHost())
                .isEqualTo("backend.example.test");
        assertThat(config.toClientConnectionConfig().getPort()).isEqualTo(9999);
        assertThat(config.toClientConnectionConfig().getUsername()).isEqualTo("proxy-1");
        assertThat(config.toClientConnectionConfig().getInitialReconnectDelaySeconds())
                .isEqualTo(7);
    }

    // ------------------------------------------------------------------ mode

    @Test
    @DisplayName("missing mode defaults to relay")
    void missingModeDefaultsToRelay() throws Exception {
        MapSource source = new MapSource().templateDefaults();
        source.values.remove("mode");

        ProxyConfig config = ProxyConfig.from(source);

        assertThat(config.getMode()).isEqualTo(ProxyMode.RELAY);
    }

    @Test
    @DisplayName("mode is matched case-insensitively")
    void modeIsCaseInsensitive() throws Exception {
        assertThat(ProxyConfig.from(new MapSource().templateDefaults()
                .put("mode", "ReLaY")).getMode()).isEqualTo(ProxyMode.RELAY);
        assertThat(ProxyConfig.from(new MapSource().liteDefaults()
                .put("mode", "LiTe")).getMode()).isEqualTo(ProxyMode.LITE);
    }

    @Test
    @DisplayName("rejects an unsupported mode value")
    void rejectsUnsupportedMode() {
        MapSource source = new MapSource().templateDefaults().put("mode", "bridge");

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("mode")
                .hasMessageContaining("unsupported");
    }

    @Test
    @DisplayName("relay still requires the backend section")
    void relayRequiresBackend() {
        MapSource source = new MapSource().templateDefaults();
        source.values.remove("backend.host");

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("backend.host");
    }

    @Test
    @DisplayName("lite does not require the backend section")
    void liteDoesNotRequireBackend() throws Exception {
        ProxyConfig config = ProxyConfig.from(new MapSource().liteDefaults());

        assertThat(config.getMode()).isEqualTo(ProxyMode.LITE);
        assertThat(config.getLiteChannels()).containsOnlyKeys("global", "local");
        assertThat(config.getLiteStorage().getType()).isEqualTo(LiteStorageConfig.Type.MEMORY);
    }

    @Test
    @DisplayName("lite rejects toClientConnectionConfig as a programming error")
    void liteRejectsClientConnectionConfig() throws Exception {
        ProxyConfig config = ProxyConfig.from(new MapSource().liteDefaults());

        assertThatThrownBy(config::toClientConnectionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relay");
    }

    @Test
    @DisplayName("client id is the backend username in relay and the server id in lite")
    void clientIdFollowsMode() throws Exception {
        ProxyConfig relay = ProxyConfig.from(new MapSource().templateDefaults()
                .put("backend.username", "proxy-1"));
        assertThat(relay.getClientId()).isEqualTo("proxy-1");

        ProxyConfig lite = ProxyConfig.from(new MapSource().liteDefaults()
                .put("lite.server-id", "proxy-2"));
        assertThat(lite.getClientId()).isEqualTo("proxy-2");
    }

    @Test
    @DisplayName("lite requires a non-blank server-id")
    void liteRequiresServerId() {
        MapSource source = new MapSource().liteDefaults().put("lite.server-id", "  ");

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("lite.server-id")
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("lite requires at least one channel")
    void liteRequiresAtLeastOneChannel() {
        MapSource source = new MapSource().liteDefaults()
                .put("lite.channels", new LinkedHashMap<String, Object>());

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("lite.channels")
                .hasMessageContaining("at least one channel");
    }

    @Test
    @DisplayName("lite rejects a blank channel id")
    void liteRejectsBlankChannelId() {
        MapSource source = new MapSource().liteDefaults();
        source.values.put("lite.channels", new LinkedHashMap<>(Map.of(
                " ", new LinkedHashMap<String, Object>(Map.of("display-name", "blank")))));

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("blank channel id");
    }

    @Test
    @DisplayName("lite rejects a default channel that is not declared")
    void liteRejectsUnknownDefaultChannel() {
        MapSource source = new MapSource().liteDefaults().put("chat.default_channel", "trade");

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("chat.default_channel")
                .hasMessageContaining("undefined channel");
    }

    @Test
    @DisplayName("lite rejects a channel prefix pointing at an undeclared channel")
    void liteRejectsUnknownPrefixChannel() {
        MapSource source = new MapSource().liteDefaults().put("chat.channel-prefixes",
                new LinkedHashMap<>(Map.of("!", "trade")));

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("chat.channel-prefixes")
                .hasMessageContaining("undefined channel");
    }

    @Test
    @DisplayName("lite parses channel fields with defaults")
    void liteParsesChannelFields() throws Exception {
        ProxyConfig config = ProxyConfig.from(new MapSource().liteDefaults());

        LiteChannelConfig global = config.getLiteChannels().get("global");
        assertThat(global.getDisplayName()).isEqualTo("全服");
        assertThat(global.getPermission()).isEqualTo("novachat.channel.global");
        assertThat(global.getMaxCapacity()).isEqualTo(1000);
        assertThat(global.getSlowMode()).isZero();

        LiteChannelConfig local = config.getLiteChannels().get("local");
        assertThat(local.getDisplayName()).isEqualTo("本地");
        assertThat(local.getPermission()).isNull();
        assertThat(local.getMaxCapacity()).isZero();
        assertThat(local.getSlowMode()).isZero();
    }

    @Test
    @DisplayName("lite channel display name defaults to the id")
    void liteChannelDisplayNameDefaultsToId() throws Exception {
        MapSource source = new MapSource().liteDefaults();
        source.values.put("lite.channels", new LinkedHashMap<>(Map.of(
                "trade", new LinkedHashMap<String, Object>())));
        // The default channel must reference a declared channel (lite rule).
        source.values.put("chat.default_channel", "trade");

        ProxyConfig config = ProxyConfig.from(source);

        assertThat(config.getLiteChannels().get("trade").getDisplayName()).isEqualTo("trade");
    }

    @Test
    @DisplayName("lite rejects a negative capacity or slow mode")
    void liteRejectsNegativeNumbers() {
        MapSource capacity = new MapSource().liteDefaults();
        capacity.values.put("lite.channels", new LinkedHashMap<>(Map.of(
                "global", new LinkedHashMap<String, Object>(Map.of("max-capacity", -1L)))));
        assertThatThrownBy(() -> ProxyConfig.from(capacity))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("max-capacity");

        MapSource slow = new MapSource().liteDefaults();
        slow.values.put("lite.channels", new LinkedHashMap<>(Map.of(
                "global", new LinkedHashMap<String, Object>(Map.of("slow-mode", -1L)))));
        assertThatThrownBy(() -> ProxyConfig.from(slow))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("slow-mode");
    }

    @Test
    @DisplayName("lite requires the storage section")
    void liteRequiresStorage() {
        MapSource source = new MapSource().liteDefaults();
        source.values.remove("lite.storage");

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("lite.storage.type");
    }

    @Test
    @DisplayName("lite rejects an unsupported storage type")
    void liteRejectsUnsupportedStorageType() {
        MapSource source = new MapSource().liteDefaults().put("lite.storage",
                new LinkedHashMap<>(Map.of("type", "redis")));

        assertThatThrownBy(() -> ProxyConfig.from(source))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("lite.storage.type")
                .hasMessageContaining("unsupported");
    }

    @Test
    @DisplayName("lite sqlite requires a non-blank file path")
    void liteSqliteRequiresFilePath() throws Exception {
        MapSource valid = new MapSource().liteDefaults().put("lite.storage",
                new LinkedHashMap<>(Map.of(
                        "type", "sqlite",
                        "sqlite", new LinkedHashMap<String, Object>(Map.of(
                                "file-path", "data/novachat.db")))));
        assertThat(ProxyConfig.from(valid).getLiteStorage().getFilePath())
                .isEqualTo("data/novachat.db");

        MapSource blank = new MapSource().liteDefaults().put("lite.storage",
                new LinkedHashMap<>(Map.of(
                        "type", "sqlite",
                        "sqlite", new LinkedHashMap<String, Object>(Map.of(
                                "file-path", " ")))));
        assertThatThrownBy(() -> ProxyConfig.from(blank))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("lite.storage.sqlite.file-path");
    }

    @Test
    @DisplayName("lite mysql validates host, port, database and pool-size")
    void liteMysqlValidation() throws Exception {
        MapSource valid = new MapSource().liteDefaults().put("lite.storage",
                mysqlStorage("127.0.0.1", 3306L, "novachat", 10L));
        LiteStorageConfig storage = ProxyConfig.from(valid).getLiteStorage();

        assertThat(storage.getType()).isEqualTo(LiteStorageConfig.Type.MYSQL);
        assertThat(storage.getHost()).isEqualTo("127.0.0.1");
        assertThat(storage.getPort()).isEqualTo(3306);
        assertThat(storage.getDatabase()).isEqualTo("novachat");
        assertThat(storage.getPoolSize()).isEqualTo(10);

        assertThatThrownBy(() -> ProxyConfig.from(new MapSource().liteDefaults()
                .put("lite.storage", mysqlStorage(" ", 3306L, "novachat", 10L))))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("lite.storage.mysql.host");

        assertThatThrownBy(() -> ProxyConfig.from(new MapSource().liteDefaults()
                .put("lite.storage", mysqlStorage("127.0.0.1", 70000L, "novachat", 10L))))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("lite.storage.mysql.port")
                .hasMessageContaining("between 1 and 65535");

        assertThatThrownBy(() -> ProxyConfig.from(new MapSource().liteDefaults()
                .put("lite.storage", mysqlStorage("127.0.0.1", 3306L, " ", 10L))))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("lite.storage.mysql.database");

        assertThatThrownBy(() -> ProxyConfig.from(new MapSource().liteDefaults()
                .put("lite.storage", mysqlStorage("127.0.0.1", 3306L, "novachat", 0L))))
                .isInstanceOf(ConfigSource.ConfigException.class)
                .hasMessageContaining("lite.storage.mysql.pool-size");
    }

    @Test
    @DisplayName("lite postgresql uses its own section")
    void litePostgresqlValidation() throws Exception {
        MapSource source = new MapSource().liteDefaults().put("lite.storage",
                new LinkedHashMap<>(Map.of(
                        "type", "postgresql",
                        "postgresql", new LinkedHashMap<String, Object>(Map.of(
                                "host", "db.internal",
                                "port", 5432L,
                                "database", "novachat",
                                "pool-size", 5L)))));

        LiteStorageConfig storage = ProxyConfig.from(source).getLiteStorage();

        assertThat(storage.getType()).isEqualTo(LiteStorageConfig.Type.POSTGRESQL);
        assertThat(storage.getHost()).isEqualTo("db.internal");
        assertThat(storage.getPort()).isEqualTo(5432);
    }

    @Test
    @DisplayName("lite ignores backend values that are present but invalid")
    void liteIgnoresInvalidBackendValues() throws Exception {
        MapSource source = new MapSource().liteDefaults()
                .put("backend.host", " ")
                .put("backend.port", 0L);

        ProxyConfig config = ProxyConfig.from(source);

        assertThat(config.getMode()).isEqualTo(ProxyMode.LITE);
    }

    /** @return a mysql storage map with the given values */
    private static Map<String, Object> mysqlStorage(String host, long port,
                                                    String database, long poolSize) {
        return new LinkedHashMap<>(Map.of(
                "type", "mysql",
                "mysql", new LinkedHashMap<String, Object>(Map.of(
                        "host", host,
                        "port", port,
                        "database", database,
                        "pool-size", poolSize))));
    }
}
