package com.nova.chat.velocity.config;

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
        NovaChatConfig config = new NovaChatConfig(tempDir);
        Path file = tempDir.resolve("config.toml");

        assertThat(config.wasConfigCreated()).isTrue();
        assertThat(config.isDebug()).isFalse();
        assertThat(config.getChannelFormats()).containsKeys("global", "local");
        assertThat(config.getChannelFormats()).doesNotContainKey("debug");
        assertThat(Files.readString(file)).contains("config-version = 1");
        assertThat(file.resolveSibling("config.toml.bak")).doesNotExist();
    }

    @Test
    void upgradesInPlacePreservesCommentsAndMigratesLegacyDebug() throws Exception {
        Path file = tempDir.resolve("config.toml");
        String original = """
                # operator config
                operator-setting = "retained"

                [backend]
                host = "velocity.internal" # keep inline

                [format.channels]
                custom = "custom format"
                debug = true
                """;
        Files.writeString(file, original);

        NovaChatConfig first = new NovaChatConfig(tempDir);
        String upgraded = Files.readString(file);
        NovaChatConfig second = new NovaChatConfig(tempDir);

        assertThat(first.wasConfigUpdated()).isTrue();
        assertThat(first.isDebug()).isTrue();
        assertThat(first.getBackendHost()).isEqualTo("velocity.internal");
        assertThat(first.getChannelFormats()).containsOnlyKeys("custom");
        assertThat(upgraded).contains("# operator config");
        assertThat(upgraded).contains("operator-setting = \"retained\"");
        assertThat(upgraded).contains("host = \"velocity.internal\" # keep inline");
        assertThat(upgraded).contains("debug = true");
        assertThat(upgraded).doesNotContain("global = \"&c[全服]");
        assertThat(Files.readString(file.resolveSibling("config.toml.bak")))
                .isEqualTo(original);
        assertThat(second.wasConfigUpdated()).isFalse();
        assertThat(Files.readString(file)).isEqualTo(upgraded);
    }

    @Test
    void malformedTomlIsNotOverwritten() throws Exception {
        Path file = tempDir.resolve("config.toml");
        String malformed = "[backend\nhost = \"broken\"\n";
        Files.writeString(file, malformed);

        assertThatThrownBy(() -> new NovaChatConfig(tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to install, upgrade, or load");
        assertThat(Files.readString(file)).isEqualTo(malformed);
        assertThat(file.resolveSibling("config.toml.bak")).doesNotExist();
    }
}
