package com.nova.chat.client.network;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoreNetworkClient pure helpers")
class CoreNetworkClientTest {

    private static final class RecordingLogger implements ClientLogger {
        final List<String> infos = new ArrayList<>();
        final List<String> warns = new ArrayList<>();
        final List<String> debugs = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warn(String message) {
            warns.add(message);
        }

        @Override
        public void debug(String message) {
            debugs.add(message);
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }
    }

    private static final class NoOpScheduler implements SchedulerBridge {
        final AtomicInteger laterCalls = new AtomicInteger();

        @Override
        public void runAsync(Runnable task) {
            // pure tests never need execution
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            laterCalls.incrementAndGet();
            // do not run — pure policy tests only
        }
    }

    private static CoreNetworkClient newClient(
            ClientConnectionConfig config,
            PlatformType platform,
            String credentialsFile,
            java.util.function.Function<String, String> transformer
    ) {
        return new CoreNetworkClient(
                config,
                platform,
                new NoOpScheduler(),
                new RecordingLogger(),
                credentialsFile,
                transformer
        );
    }

    private static ClientConnectionConfig basicConfig() {
        return ClientConnectionConfig.builder()
                .host("127.0.0.1")
                .port(8888)
                .username("proxy")
                .password("s3cret")
                .build();
    }

    @Nested
    @DisplayName("handshake construction")
    class HandshakeConstruction {

        @Test
        @DisplayName("buildHandshakePacket uses SHA-256 password and platform type")
        void hashesPasswordAndSetsPlatform() {
            CoreNetworkClient client = newClient(
                    basicConfig(), PlatformType.VELOCITY, "config.toml", null
            );

            HandshakePacket packet = client.buildHandshakePacket();

            assertThat(packet.getProtocolVersion()).isEqualTo(NovaProtocol.PROTOCOL_VERSION);
            assertThat(packet.getClientId()).isEqualTo("proxy");
            assertThat(packet.getPasswordHash()).isEqualTo(PasswordHasher.sha256Hex("s3cret"));
            assertThat(packet.getPlatform()).isEqualTo(PlatformType.VELOCITY);
        }

        @Test
        @DisplayName("usernameTransformer rewrites handshake client id")
        void usernameTransformerApplied() {
            CoreNetworkClient client = newClient(
                    basicConfig(),
                    PlatformType.BUKKIT,
                    "config.yml",
                    u -> u + "@node1"
            );

            HandshakePacket packet = client.buildHandshakePacket();
            assertThat(packet.getClientId()).isEqualTo("proxy@node1");
        }
    }

    @Nested
    @DisplayName("reconnect policy integration")
    class ReconnectPolicyIntegration {

        @Test
        @DisplayName("evaluateReconnect matches ExponentialBackoff defaults")
        void defaultBackoffSchedule() {
            CoreNetworkClient client = newClient(
                    basicConfig(), PlatformType.VELOCITY, "config.toml", null
            );

            ReconnectPolicy.Decision first = client.evaluateReconnect(1);
            assertThat(first.shouldRetry()).isTrue();
            assertThat(first.delaySeconds()).isEqualTo(1);

            ReconnectPolicy.Decision sixth = client.evaluateReconnect(6);
            assertThat(sixth.shouldRetry()).isTrue();
            assertThat(sixth.delaySeconds()).isEqualTo(30);

            ReconnectPolicy.Decision eleventh = client.evaluateReconnect(11);
            assertThat(eleventh.shouldRetry()).isFalse();
        }

        @Test
        @DisplayName("custom max attempts stop reconnection")
        void customMaxAttempts() {
            ClientConnectionConfig config = ClientConnectionConfig.builder()
                    .host("127.0.0.1")
                    .port(8888)
                    .username("u")
                    .password("p")
                    .maxReconnectAttempts(2)
                    .initialReconnectDelaySeconds(1)
                    .maxReconnectDelaySeconds(30)
                    .build();

            CoreNetworkClient client = newClient(
                    config, PlatformType.BUNGEECORD, "config.yml", null
            );

            assertThat(client.evaluateReconnect(2).shouldRetry()).isTrue();
            assertThat(client.evaluateReconnect(3).shouldRetry()).isFalse();
        }
    }

    @Nested
    @DisplayName("construction surface")
    class Construction {

        @Test
        @DisplayName("exposes config, platform, registry, and policy")
        void exposesInjectedState() {
            ClientConnectionConfig config = basicConfig();
            CoreNetworkClient client = newClient(
                    config, PlatformType.VELOCITY, "config.toml", null
            );

            assertThat(client.getConnectionConfig()).isEqualTo(config);
            assertThat(client.getPlatformType()).isEqualTo(PlatformType.VELOCITY);
            assertThat(client.getPacketRegistry()).isNotNull();
            assertThat(client.getReconnectPolicy().maxAttempts())
                    .isEqualTo(ClientConnectionConfig.DEFAULT_MAX_RECONNECT_ATTEMPTS);
            assertThat(client.isConnected()).isFalse();
            assertThat(client.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("resetReconnectBudget clears attempt counter")
        void resetBudget() {
            CoreNetworkClient client = newClient(
                    basicConfig(), PlatformType.VELOCITY, "config.toml", null
            );
            client.resetReconnectBudget();
            // no exception; counter starts at 0 and stays usable
            assertThat(client.evaluateReconnect(1).shouldRetry()).isTrue();
        }
    }
}
