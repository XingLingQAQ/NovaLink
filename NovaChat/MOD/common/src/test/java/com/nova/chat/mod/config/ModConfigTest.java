package com.nova.chat.mod.config;

import com.nova.chat.client.network.ClientConnectionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ModConfig} features added with the client-core
 * integration: {@code toClientConnectionConfig()}, the locale field,
 * validation, and the convenience accessors.
 */
@DisplayName("ModConfig")
class ModConfigTest {

    @Nested
    @DisplayName("toClientConnectionConfig()")
    class ToClientConnectionConfig {

        @Test
        @DisplayName("maps backend host/port/username/password and clamps reconnect delay to >= 1")
        void mapsAllBackendFields() {
            ModConfig config = new ModConfig();
            ModConfig.BackendConfig backend = new ModConfig.BackendConfig();
            backend.setHost("10.0.0.5");
            backend.setPort(9999);
            backend.setUsername("mod-server-1");
            backend.setPassword("s3cret");
            backend.setReconnectDelay(7);
            config.setBackend(backend);

            ClientConnectionConfig cc = config.toClientConnectionConfig();

            assertThat(cc.getHost()).isEqualTo("10.0.0.5");
            assertThat(cc.getPort()).isEqualTo(9999);
            assertThat(cc.getUsername()).isEqualTo("mod-server-1");
            assertThat(cc.getPassword()).isEqualTo("s3cret");
            assertThat(cc.getInitialReconnectDelaySeconds()).isEqualTo(7);
        }

        @Test
        @DisplayName("clamps a zero reconnect delay up to 1 (Math.max(1, delay))")
        void clampsZeroReconnectDelayToOne() {
            ModConfig config = new ModConfig();
            config.getBackend().setReconnectDelay(0);

            ClientConnectionConfig cc = config.toClientConnectionConfig();

            assertThat(cc.getInitialReconnectDelaySeconds()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("clamps a negative reconnect delay up to 1")
        void clampsNegativeReconnectDelayToOne() {
            ModConfig config = new ModConfig();
            config.getBackend().setReconnectDelay(-5);

            ClientConnectionConfig cc = config.toClientConnectionConfig();

            assertThat(cc.getInitialReconnectDelaySeconds()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("falls back to a default BackendConfig when backend is null")
        void handlesNullBackend() {
            ModConfig config = new ModConfig();
            config.setBackend(null);

            ClientConnectionConfig cc = config.toClientConnectionConfig();

            // Defaults from BackendConfig(): host 127.0.0.1, port 8888, username ModServer
            assertThat(cc.getHost()).isEqualTo("127.0.0.1");
            assertThat(cc.getPort()).isEqualTo(8888);
            assertThat(cc.getUsername()).isEqualTo("ModServer");
        }
    }

    @Nested
    @DisplayName("locale field")
    class Locale {

        @Test
        @DisplayName("default locale is zh_CN")
        void defaultLocaleIsZhCn() {
            assertThat(new ModConfig().getChat().getLocale()).isEqualTo("zh_CN");
        }

        @Test
        @DisplayName("locale getter/setter round-trips en_US")
        void localeRoundTripEnUs() {
            ModConfig.ChatConfig chat = new ModConfig.ChatConfig();
            chat.setLocale("en_US");
            assertThat(chat.getLocale()).isEqualTo("en_US");
        }

        @Test
        @DisplayName("locale round-trips through the whole ModConfig.ChatConfig holder")
        void localeRoundTripThroughChatConfig() {
            ModConfig config = new ModConfig();
            config.getChat().setLocale("en_US");

            ModConfig.ChatConfig chat = config.getChat();
            assertThat(chat.getLocale()).isEqualTo("en_US");
            // Other chat fields remain independent
            assertThat(chat.getDefaultChannel()).isEqualTo("local");
        }
    }

    @Nested
    @DisplayName("validate()")
    class Validate {

        @Test
        @DisplayName("default config validates (non-empty channel, valid backend defaults)")
        void defaultConfigValidates() {
            assertThat(new ModConfig().validate()).isTrue();
        }

        @Test
        @DisplayName("empty default channel fails validation")
        void emptyDefaultChannelFails() {
            ModConfig config = new ModConfig();
            config.getChat().setDefaultChannel("");
            assertThat(config.validate()).isFalse();
        }

        @Test
        @DisplayName("invalid port fails validation")
        void invalidPortFails() {
            ModConfig config = new ModConfig();
            config.getBackend().setPort(0);
            assertThat(config.validate()).isFalse();
        }

        @Test
        @DisplayName("null backend fails validation")
        void nullBackendFails() {
            ModConfig config = new ModConfig();
            config.setBackend(null);
            assertThat(config.validate()).isFalse();
        }

        @Test
        @DisplayName("empty host fails backend validation")
        void emptyHostFails() {
            ModConfig config = new ModConfig();
            config.getBackend().setHost("");
            assertThat(config.validate()).isFalse();
        }
    }

    @Nested
    @DisplayName("convenience accessors")
    class ConvenienceAccessors {

        @Test
        @DisplayName("getBackendHost/getBackendPort/getUsername delegate to backend")
        void accessorsDelegateToBackend() {
            ModConfig config = new ModConfig();
            config.getBackend().setHost("h");
            config.getBackend().setPort(1234);
            config.getBackend().setUsername("u");

            assertThat(config.getBackendHost()).isEqualTo("h");
            assertThat(config.getBackendPort()).isEqualTo(1234);
            assertThat(config.getUsername()).isEqualTo("u");
        }

        @Test
        @DisplayName("getBackendPort returns DEFAULT_PORT when backend is null")
        void portDefaultWhenBackendNull() {
            ModConfig config = new ModConfig();
            config.setBackend(null);
            assertThat(config.getBackendPort()).isEqualTo(ClientConnectionConfig.DEFAULT_PORT);
        }

        @Test
        @DisplayName("getBackendHost/getUsername return null when backend is null")
        void hostAndUserNullWhenBackendNull() {
            ModConfig config = new ModConfig();
            config.setBackend(null);
            assertThat(config.getBackendHost()).isNull();
            assertThat(config.getUsername()).isNull();
        }
    }
}
