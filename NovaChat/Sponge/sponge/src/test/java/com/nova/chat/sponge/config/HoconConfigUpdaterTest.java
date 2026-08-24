package com.nova.chat.sponge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoconConfigUpdaterTest {

    private static final Set<String> DYNAMIC_MAPPINGS = Set.of(
            "chat.channel-prefixes", "format.channels", "world-routing.mappings");

    @TempDir
    Path tempDir;

    @Test
    void createsConfigurationFromBundledTemplateWithoutBackup() throws Exception {
        Path file = tempDir.resolve("novachat.conf");

        HoconConfigUpdater.UpdateResult result;
        try (InputStream template = template()) {
            result = HoconConfigUpdater.update(file, template, DYNAMIC_MAPPINGS);
        }

        assertThat(result.created()).isTrue();
        assertThat(result.updated()).isFalse();
        assertThat(Files.readString(file)).contains("config-version = 1");
        assertThat(file.resolveSibling("novachat.conf.bak")).doesNotExist();
    }

    @Test
    void upgradesMissingEntriesAndPreservesCommentsUnknownValuesAndDynamicMappings() throws Exception {
        Path file = tempDir.resolve("novachat.conf");
        String original = """
                # operator configuration
                operator-setting = "retained"
                backend {
                    host = "sponge.internal" # keep host
                    port = "wrong type"
                }
                chat {
                    channel-prefixes {
                        "!" = "staff"
                    }
                }
                format {
                    channels {
                        custom = "custom format"
                    }
                }
                """;
        Files.writeString(file, original);

        HoconConfigUpdater.UpdateResult first;
        try (InputStream template = template()) {
            first = HoconConfigUpdater.update(file, template, DYNAMIC_MAPPINGS);
        }
        String upgraded = Files.readString(file);
        HoconConfigUpdater.UpdateResult second;
        try (InputStream template = template()) {
            second = HoconConfigUpdater.update(file, template, DYNAMIC_MAPPINGS);
        }
        CommentedConfigurationNode root = HoconConfigurationLoader.builder().path(file).build().load();

        assertThat(first.updated()).isTrue();
        assertThat(first.backupPath()).isEqualTo(file.resolveSibling("novachat.conf.bak"));
        assertThat(root.node("backend", "host").getString()).isEqualTo("sponge.internal");
        assertThat(root.node("backend", "port").getString()).isEqualTo("wrong type");
        assertThatThrownBy(() -> new NovaChatConfig(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backend.port");
        assertThat(root.node("operator-setting").getString()).isEqualTo("retained");
        assertThat(root.node("chat", "channel-prefixes").childrenMap()).containsOnlyKeys("!");
        assertThat(root.node("format", "channels").childrenMap()).containsOnlyKeys("custom");
        assertThat(upgraded).contains("# operator configuration");
        assertThat(upgraded).contains("# keep host");
        assertThat(Files.readString(first.backupPath())).isEqualTo(original);
        assertThat(second.updated()).isFalse();
        assertThat(Files.readString(file)).isEqualTo(upgraded);
    }

    @Test
    void migratesLegacyYamlAndRetainsOriginalFile() throws Exception {
        Path legacy = tempDir.resolve("config.yml");
        Path current = tempDir.resolve("novachat.conf");
        String original = """
                # legacy operator configuration
                backend:
                  host: "legacy.internal"
                format:
                  channels:
                    custom: "legacy format"
                """;
        Files.writeString(legacy, original);

        boolean migrated;
        try (InputStream template = template()) {
            migrated = HoconConfigUpdater.migrateLegacyYaml(
                    legacy, current, template, DYNAMIC_MAPPINGS);
        }
        CommentedConfigurationNode root = HoconConfigurationLoader.builder().path(current).build().load();

        assertThat(migrated).isTrue();
        assertThat(root.node("backend", "host").getString()).isEqualTo("legacy.internal");
        assertThat(root.node("backend", "port").getInt()).isEqualTo(8888);
        assertThat(root.node("format", "channels").childrenMap()).containsOnlyKeys("custom");
        assertThat(Files.readString(legacy)).isEqualTo(original);
    }

    @Test
    void malformedConfigurationIsNotOverwritten() throws Exception {
        Path file = tempDir.resolve("novachat.conf");
        String malformed = "backend { host = [ }\n";
        Files.writeString(file, malformed);

        assertThatThrownBy(() -> {
            try (InputStream template = template()) {
                HoconConfigUpdater.update(file, template, DYNAMIC_MAPPINGS);
            }
        }).isInstanceOf(IOException.class)
                .hasMessageContaining("invalid HOCON");
        assertThat(Files.readString(file)).isEqualTo(malformed);
        assertThat(file.resolveSibling("novachat.conf.bak")).doesNotExist();
    }

    private InputStream template() throws IOException {
        InputStream input = HoconConfigUpdaterTest.class
                .getResourceAsStream("/default-novachat.conf");
        if (input == null) {
            throw new IOException("Missing test template");
        }
        return input;
    }
}
