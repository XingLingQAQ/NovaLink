package com.nova.chat.mod.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigManagerTemplateTest {

    @TempDir
    Path tempDir;

    @Test
    void createsConfigurationFromBundledTemplate() throws Exception {
        ConfigManager manager = new ConfigManager(tempDir);

        ModConfig config = manager.loadConfig();
        Path file = tempDir.resolve("novachat.yml");

        assertThat(config.getBackend().getUsername()).isEqualTo("ModServer");
        assertThat(config.getFormats()).containsKeys("global", "local");
        assertThat(Files.readString(file)).contains("# NovaChat MOD configuration");
        assertThat(Files.readString(file)).contains("config-version: 1");
        assertThat(file.resolveSibling("novachat.yml.bak")).doesNotExist();
    }

    @Test
    void migratesLegacyPathAndPreservesDynamicFormats() throws Exception {
        Path legacy = tempDir.resolve("config/novachat.yml");
        Files.createDirectories(legacy.getParent());
        String original = """
                # legacy operator config
                backend:
                  host: "mod.internal"
                format:
                  channels:
                    custom: "custom format"
                """;
        Files.writeString(legacy, original);

        ModConfig config = new ConfigManager(tempDir).loadConfig();
        Path current = tempDir.resolve("novachat.yml");
        String upgraded = Files.readString(current);

        assertThat(config.getBackend().getHost()).isEqualTo("mod.internal");
        assertThat(config.getFormats()).containsOnlyKeys("custom");
        assertThat(upgraded).contains("# legacy operator config");
        assertThat(upgraded).contains("reconnect-delay: 5");
        assertThat(upgraded).doesNotContain("global: \"&c[全服]");
        assertThat(Files.readString(current.resolveSibling("novachat.yml.bak")))
                .isEqualTo(original);
        assertThat(Files.readString(legacy)).isEqualTo(original);
    }

    @Test
    void failedReloadKeepsPreviousRuntimeConfiguration() throws Exception {
        ConfigManager manager = new ConfigManager(tempDir);
        ModConfig original = manager.loadConfig();
        Path file = tempDir.resolve("novachat.yml");
        Files.writeString(file, "backend: [\n");

        ModConfig afterFailure = manager.loadConfig();

        assertThat(afterFailure).isSameAs(original);
        assertThat(Files.readString(file)).isEqualTo("backend: [\n");
    }
}
