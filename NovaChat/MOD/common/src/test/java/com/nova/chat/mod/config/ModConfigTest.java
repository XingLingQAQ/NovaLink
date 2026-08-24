package com.nova.chat.mod.config;

import com.nova.chat.client.network.ClientConnectionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ModConfig} features added with the client-core
 * integration: {@code toClientConnectionConfig()}, the locale field,
 * validation, and the convenience accessors.
 */
@DisplayName("ModConfig")
class ModConfigTest {

    private static ModConfig validConfig() {
        ModConfig config = new ModConfig();
        ModConfig.BackendConfig backend = new ModConfig.BackendConfig();
        backend.setHost("127.0.0.1");
        backend.setPort(8888);
        backend.setUsername("ModServer");
        backend.setPassword("password");
        backend.setReconnectDelay(5);
        config.setBackend(backend);
        config.getChat().setDefaultChannel("local");
        config.getChat().setLocale("zh_CN");
        return config;
    }

    @Nested
    @DisplayName("toClientConnectionConfig()")
    class ToClientConnectionConfig {

        @Test
        @DisplayName("maps backend host, port, credentials, and reconnect delay")
        void mapsAllBackendFields() {
            ModConfig config = validConfig();
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
        @DisplayName("rejects a zero reconnect delay")
        void rejectsZeroReconnectDelay() {
            ModConfig config = validConfig();
            config.getBackend().setReconnectDelay(0);

            assertThatThrownBy(config::toClientConnectionConfig)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("rejects a negative reconnect delay")
        void rejectsNegativeReconnectDelay() {
            ModConfig config = validConfig();
            config.getBackend().setReconnectDelay(-5);

            assertThatThrownBy(config::toClientConnectionConfig)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("rejects a missing backend instead of inventing connection values")
        void rejectsNullBackend() {
            ModConfig config = validConfig();
            config.setBackend(null);

            assertThatThrownBy(config::toClientConnectionConfig)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("locale field")
    class Locale {

        @Test
        @DisplayName("the data model does not inject a locale default")
        void modelDoesNotInjectLocale() {
            assertThat(new ModConfig().getChat().getLocale()).isNull();
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
            ModConfig config = validConfig();
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
        @DisplayName("an unparsed data model is not a valid runtime config")
        void emptyModelIsInvalid() {
            assertThat(new ModConfig().validate()).isFalse();
        }

        @Test
        @DisplayName("empty default channel fails validation")
        void emptyDefaultChannelFails() {
            ModConfig config = validConfig();
            config.getChat().setDefaultChannel("");
            assertThat(config.validate()).isFalse();
        }

        @Test
        @DisplayName("invalid port fails validation")
        void invalidPortFails() {
            ModConfig config = validConfig();
            config.getBackend().setPort(0);
            assertThat(config.validate()).isFalse();
        }

        @Test
        @DisplayName("null backend fails validation")
        void nullBackendFails() {
            ModConfig config = validConfig();
            config.setBackend(null);
            assertThat(config.validate()).isFalse();
        }

        @Test
        @DisplayName("empty host fails backend validation")
        void emptyHostFails() {
            ModConfig config = validConfig();
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
            ModConfig config = validConfig();
            config.getBackend().setHost("h");
            config.getBackend().setPort(1234);
            config.getBackend().setUsername("u");

            assertThat(config.getBackendHost()).isEqualTo("h");
            assertThat(config.getBackendPort()).isEqualTo(1234);
            assertThat(config.getUsername()).isEqualTo("u");
        }

        @Test
        @DisplayName("backend access fails when no parsed backend exists")
        void accessFailsWhenBackendNull() {
            ModConfig config = validConfig();
            config.setBackend(null);
            assertThatThrownBy(config::getBackendPort).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(config::getBackendHost).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(config::getUsername).isInstanceOf(NullPointerException.class);
        }
    }
}
