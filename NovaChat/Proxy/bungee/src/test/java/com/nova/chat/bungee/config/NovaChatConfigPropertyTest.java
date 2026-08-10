package com.nova.chat.bungee.config;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.assertj.core.api.Assertions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Property-based tests for NovaChatConfig in BungeeCord module.
 * 
 * Note: These tests use a mock config approach since BungeeCord's Configuration
 * requires the BungeeCord runtime. We test the configuration parsing logic patterns.
 */
class NovaChatConfigPropertyTest {

    /**
     * Simple mock config for testing without BungeeCord dependencies.
     */
    static class MockConfig {
        private final Map<String, Object> data = new LinkedHashMap<>();
        
        public MockConfig() {
            // Set defaults matching NovaChatConfig
            data.put("backend.host", "127.0.0.1");
            data.put("backend.port", 8888);
            data.put("backend.username", "");
            data.put("backend.password", "");
            data.put("backend.reconnect-delay", 5);
            data.put("chat.replace_vanilla", false);
            data.put("chat.default_channel", "local");
            data.put("format.prefix", "&8[&bNovaChat&8]&r ");
            data.put("format.error", "&c错误: {message}");
            data.put("format.success", "&a成功: {message}");
            data.put("format.default", "&7[{channel_name}] {player}&f: {message}");
            data.put("debug", false);
        }
        
        public String getString(String key, String def) {
            Object val = data.get(key);
            return val instanceof String ? (String) val : def;
        }
        
        public int getInt(String key, int def) {
            Object val = data.get(key);
            return val instanceof Number ? ((Number) val).intValue() : def;
        }
        
        public boolean getBoolean(String key, boolean def) {
            Object val = data.get(key);
            return val instanceof Boolean ? (Boolean) val : def;
        }
        
        public void set(String key, Object value) {
            data.put(key, value);
        }
    }

    @Property
    void defaultHostShouldBeLocalhost() {
        MockConfig mock = new MockConfig();
        String host = mock.getString("backend.host", "127.0.0.1");
        Assertions.assertThat(host).isEqualTo("127.0.0.1");
    }

    @Property
    void defaultPortShouldBe8888() {
        MockConfig mock = new MockConfig();
        int port = mock.getInt("backend.port", 8888);
        Assertions.assertThat(port).isEqualTo(8888);
    }

    @Property
    void defaultChannelShouldBeLocal() {
        MockConfig mock = new MockConfig();
        String channel = mock.getString("chat.default_channel", "local");
        Assertions.assertThat(channel).isEqualTo("local");
    }

    @Property
    void portShouldBeInValidRange(@ForAll @IntRange(min = 1, max = 65535) int port) {
        MockConfig mock = new MockConfig();
        mock.set("backend.port", port);
        int configPort = mock.getInt("backend.port", 8888);
        Assertions.assertThat(configPort).isBetween(1, 65535);
    }

    @Property
    void hostShouldBePreserved(@ForAll @StringLength(min = 1, max = 100) String host) {
        MockConfig mock = new MockConfig();
        mock.set("backend.host", host);
        String configHost = mock.getString("backend.host", "127.0.0.1");
        Assertions.assertThat(configHost).isEqualTo(host);
    }

    @Property
    void channelShouldBePreserved(@ForAll @StringLength(min = 1, max = 50) String channel) {
        MockConfig mock = new MockConfig();
        mock.set("chat.default_channel", channel);
        String configChannel = mock.getString("chat.default_channel", "local");
        Assertions.assertThat(configChannel).isEqualTo(channel);
    }

    @Property
    void debugModeShouldBeConfigurable(@ForAll boolean debugMode) {
        MockConfig mock = new MockConfig();
        mock.set("debug", debugMode);
        boolean configDebug = mock.getBoolean("debug", false);
        Assertions.assertThat(configDebug).isEqualTo(debugMode);
    }

    @Property
    void reconnectDelayShouldBePositive(@ForAll @IntRange(min = 1, max = 300) int delay) {
        MockConfig mock = new MockConfig();
        mock.set("backend.reconnect-delay", delay);
        int configDelay = mock.getInt("backend.reconnect-delay", 5);
        Assertions.assertThat(configDelay).isPositive();
    }
}
