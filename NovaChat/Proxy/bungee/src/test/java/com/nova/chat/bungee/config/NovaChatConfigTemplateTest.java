package com.nova.chat.bungee.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovaChatConfigTemplateTest {

    @TempDir
    Path tempDir;

    @Test
    void createsConfigurationFromBundledTemplate() throws Exception {
        NovaChatConfig config = new NovaChatConfig(tempDir.toFile());
        Path file = tempDir.resolve("config.yml");

        assertThat(config.getUpdateResult().created()).isTrue();
        assertThat(config.getBackendPort()).isEqualTo(8888);
        assertThat(Files.readString(file)).contains("# NovaChat BungeeCord");
        assertThat(Files.readString(file)).contains("config-version: 1");
    }

    @Test
    void upgradesOldConfigurationWithoutRestoringRemovedFormatExamples() throws Exception {
        Path file = tempDir.resolve("config.yml");
        String original = """
                # proxy operator config
                backend:
                  host: "proxy.internal"
                format:
                  channels:
                    staff: "staff format"
                """;
        Files.writeString(file, original);

        NovaChatConfig config = new NovaChatConfig(tempDir.toFile());
        String upgraded = Files.readString(file);

        assertThat(config.getUpdateResult().updated()).isTrue();
        assertThat(config.getBackendHost()).isEqualTo("proxy.internal");
        assertThat(config.getChannelFormats()).containsOnlyKeys("staff");
        assertThat(upgraded).contains("# proxy operator config");
        assertThat(upgraded).contains("reconnect-delay: 5");
        assertThat(upgraded).doesNotContain("global: \"&c[全服]");
        assertThat(Files.readString(file.resolveSibling("config.yml.bak")))
                .isEqualTo(original);
    }

    @Test
    void malformedConfigurationIsNotOverwritten() throws Exception {
        Path file = tempDir.resolve("config.yml");
        String malformed = "backend: [\n";
        Files.writeString(file, malformed);

        assertThatThrownBy(() -> new NovaChatConfig(tempDir.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to install, upgrade, or load");
        assertThat(Files.readString(file)).isEqualTo(malformed);
        assertThat(file.resolveSibling("config.yml.bak")).doesNotExist();
    }
}
