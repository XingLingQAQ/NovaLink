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

        MapSource put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        @Override
        public String requiredString(String key) throws ConfigException {
            Object value = values.get(key);
            if (!(value instanceof String text)) {
                throw new ConfigException("Configuration value " + key + " must be a string");
            }
            return text;
        }

        @Override
        public int requiredInt(String key) throws ConfigException {
            Object value = values.get(key);
            if (!(value instanceof Number number)) {
                throw new ConfigException("Configuration value " + key + " must be an integer");
            }
            return number.intValue();
        }

        @Override
        public boolean requiredBoolean(String key) throws ConfigException {
            Object value = values.get(key);
            if (!(value instanceof Boolean bool)) {
                throw new ConfigException("Configuration value " + key + " must be a boolean");
            }
            return bool;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Map<String, Object> requiredTable(String key) throws ConfigException {
            Object value = values.get(key);
            if (!(value instanceof Map)) {
                throw new ConfigException("Configuration value " + key + " must be a mapping");
            }
            return (Map<String, Object>) value;
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
}
