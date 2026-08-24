package com.nova.link.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing tests for the network hardening settings introduced with idle
 * detection / rate limiting / REST offload / channel slow mode:
 * {@code server.idle-timeout-seconds}, {@code server.rest-worker-threads},
 * {@code server.rate-limit.*} and the per-channel {@code slow_mode} field.
 */
@DisplayName("Network hardening config parsing")
class NetworkHardeningConfigTest {

    @TempDir
    Path tempDir;

    private NovaLinkConfig load(String yaml) throws Exception {
        Path file = tempDir.resolve("novalink.yml");
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return new ConfigLoader(file).load();
    }

    @Test
    @DisplayName("explicit values are parsed from the server section")
    void parsesExplicitValues() throws Exception {
        NovaLinkConfig config = load("""
                server:
                  port: 8888
                  idle-timeout-seconds: 120
                  rest-worker-threads: 8
                  rate-limit:
                    messages-per-second: 5
                    burst: 12
                """);

        assertThat(config.getServer().getIdleTimeoutSeconds()).isEqualTo(120);
        assertThat(config.getServer().getRestWorkerThreads()).isEqualTo(8);
        assertThat(config.getServer().getRateLimitMessagesPerSecond()).isEqualTo(5);
        assertThat(config.getServer().getRateLimitBurst()).isEqualTo(12);
    }

    @Test
    @DisplayName("0 disables idle detection and rate limiting")
    void zeroDisables() throws Exception {
        NovaLinkConfig config = load("""
                server:
                  idle-timeout-seconds: 0
                  rate-limit:
                    messages-per-second: 0
                """);

        assertThat(config.getServer().getIdleTimeoutSeconds()).isZero();
        assertThat(config.getServer().getRateLimitMessagesPerSecond()).isZero();
    }

    @Test
    @DisplayName("missing fields are supplied by the bundled template")
    void defaultsComeFromTemplate() throws Exception {
        NovaLinkConfig config = load("""
                server:
                  port: 8888
                """);

        assertThat(config.getServer().getIdleTimeoutSeconds())
                .isEqualTo(90);
        assertThat(config.getServer().getRestWorkerThreads())
                .isEqualTo(4);
        assertThat(config.getServer().getRateLimitMessagesPerSecond())
                .isEqualTo(10);
        assertThat(config.getServer().getRateLimitBurst())
                .isEqualTo(20);
    }

    @Test
    @DisplayName("negative values reject the configuration instead of silently changing it")
    void negativeValuesAreRejected() throws Exception {
        Path file = tempDir.resolve("invalid-values.yml");
        Files.writeString(file, """
                server:
                  idle-timeout-seconds: -5
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ConfigLoader(file).load())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    @DisplayName("slow_mode parses on global and client channels (default 0)")
    void slowModeParsing() throws Exception {
        NovaLinkConfig config = load("""
                global_channels:
                  global:
                    display_name: Global
                    max_capacity: 1000
                    slow_mode: 5
                  free:
                    display_name: Free
                    max_capacity: 1000
                clients:
                  - username: Survival
                    password: pw
                    channels:
                      local:
                        display_name: Local
                        scope: SERVER
                        slow_mode: 3
                      open:
                        display_name: Open
                        scope: SERVER
                """);

        assertThat(config.getGlobalChannels().get("global").getSlowModeSeconds()).isEqualTo(5);
        assertThat(config.getGlobalChannels().get("free").getSlowModeSeconds()).isZero();
        assertThat(config.getClients().get(0).getChannels().get("local").getSlowModeSeconds())
                .isEqualTo(3);
        assertThat(config.getClients().get(0).getChannels().get("open").getSlowModeSeconds())
                .isZero();
    }

    @Test
    @DisplayName("save() round-trips the new fields")
    void roundTrip() throws Exception {
        Path file = tempDir.resolve("roundtrip.yml");
        Files.write(file, """
                server:
                  idle-timeout-seconds: 45
                  rest-worker-threads: 2
                  rate-limit:
                    messages-per-second: 3
                    burst: 6
                global_channels:
                  global:
                    display_name: Global
                    max_capacity: 1000
                    slow_mode: 9
                """.getBytes(StandardCharsets.UTF_8));

        ConfigLoader loader = new ConfigLoader(file);
        loader.load();
        loader.save();

        NovaLinkConfig reloaded = new ConfigLoader(file).load();
        assertThat(reloaded.getServer().getIdleTimeoutSeconds()).isEqualTo(45);
        assertThat(reloaded.getServer().getRestWorkerThreads()).isEqualTo(2);
        assertThat(reloaded.getServer().getRateLimitMessagesPerSecond()).isEqualTo(3);
        assertThat(reloaded.getServer().getRateLimitBurst()).isEqualTo(6);
        assertThat(reloaded.getGlobalChannels().get("global").getSlowModeSeconds()).isEqualTo(9);
    }
}
