package com.nova.link.bukkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BackendDefaultsTest {

    @Test
    void seedsLoopbackWhenFileIsAbsent(@TempDir Path dir) throws Exception {
        BackendDefaults.SeedResult result = BackendDefaults.seedIfAbsent(dir, true);

        assertThat(result).isEqualTo(BackendDefaults.SeedResult.SEEDED_LOOPBACK);
        String yaml = Files.readString(dir.resolve("novalink.yml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains("bind-address: 127.0.0.1");
        assertThat(yaml).doesNotContain("bind-address: 0.0.0.0");
        // AUTH-002 fails closed; a loopback-only seed must also acknowledge
        // plaintext or a fresh install never starts.
        assertThat(yaml).contains("insecure-allow-plaintext: true");
    }

    @Test
    void writesTemplateVerbatimWhenLoopbackIsDisabled(@TempDir Path dir) throws Exception {
        BackendDefaults.SeedResult result = BackendDefaults.seedIfAbsent(dir, false);

        assertThat(result).isEqualTo(BackendDefaults.SeedResult.SEEDED_UNMODIFIED);
        String yaml = Files.readString(dir.resolve("novalink.yml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains("bind-address: 0.0.0.0");
        // Public bind: the plaintext gate must stay closed.
        assertThat(yaml).contains("insecure-allow-plaintext: false");
    }

    @Test
    void neverTouchesAnExistingFile(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("novalink.yml");
        Files.writeString(config, "server:\n  bind-address: 0.0.0.0\n", StandardCharsets.UTF_8);

        BackendDefaults.SeedResult result = BackendDefaults.seedIfAbsent(dir, true);

        assertThat(result).isEqualTo(BackendDefaults.SeedResult.ALREADY_PRESENT);
        assertThat(Files.readString(config, StandardCharsets.UTF_8))
                .isEqualTo("server:\n  bind-address: 0.0.0.0\n");
    }

    @Test
    void rewritePreservesIndentationAndLeavesSiblingKeysAlone(@TempDir Path dir) throws Exception {
        BackendDefaults.seedIfAbsent(dir, true);

        String yaml = Files.readString(dir.resolve("novalink.yml"), StandardCharsets.UTF_8);
        // The core template is CRLF; normalise before asserting on layout.
        assertThat(yaml.replace("\r\n", "\n")).contains("\n  bind-address: 127.0.0.1\n");
        // The rewrite must not switch the file's line endings.
        assertThat(yaml).doesNotContain("\r\r");
        assertThat(yaml.replace("\r\n", "")).doesNotContain("\r");
        // The port keys directly below bind-address must survive the rewrite.
        assertThat(yaml).contains("port: 8888");
        assertThat(yaml).contains("websocket-port: 8889");
    }

    @Test
    void recognisesLoopbackForms() {
        assertThat(BackendDefaults.isLoopback("127.0.0.1")).isTrue();
        assertThat(BackendDefaults.isLoopback("::1")).isTrue();
        assertThat(BackendDefaults.isLoopback("localhost")).isTrue();
        assertThat(BackendDefaults.isLoopback("0.0.0.0")).isFalse();
        assertThat(BackendDefaults.isLoopback("10.0.0.5")).isFalse();
        assertThat(BackendDefaults.isLoopback(null)).isFalse();
    }
}
