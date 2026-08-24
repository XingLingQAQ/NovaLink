package com.nova.chat.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlConfigUpdaterTest {

    private static final String TEMPLATE = """
            # template header
            config-version: 1
            backend:
              # backend host
              host: "127.0.0.1"
              port: 8888
            chat:
              channel-prefixes:
                "!": global
            format:
              channels:
                global: "global format"
                local: "local format"
            feature:
              enabled: true
            """;

    @TempDir
    Path tempDir;

    @Test
    void newInstallationCopiesTemplateExactly() throws Exception {
        Path config = tempDir.resolve("config.yml");

        YamlConfigUpdater.UpdateResult result =
                YamlConfigUpdater.update(config, TEMPLATE, Set.of());

        assertThat(result.created()).isTrue();
        assertThat(result.updated()).isFalse();
        assertThat(Files.readString(config)).isEqualTo(TEMPLATE);
        assertThat(config.resolveSibling("config.yml.bak")).doesNotExist();
    }

    @Test
    void upgradePreservesValuesCommentsUnknownKeysAndDynamicMappings() throws Exception {
        Path config = tempDir.resolve("config.yml");
        String original = """
                # operator header
                backend:
                  host: "chat.internal" # keep inline
                  custom-timeout: 42
                chat:
                  channel-prefixes:
                    "$": trade
                format:
                  channels:
                    custom: "custom format"
                custom-root: retained
                """;
        Files.writeString(config, original);

        YamlConfigUpdater.UpdateResult result = YamlConfigUpdater.update(config, TEMPLATE,
                Set.of("chat.channel-prefixes", "format.channels"));
        String upgraded = Files.readString(config);

        assertThat(result.updated()).isTrue();
        assertThat(upgraded).contains("# operator header");
        assertThat(upgraded).contains("host: \"chat.internal\" # keep inline");
        assertThat(upgraded).contains("custom-timeout: 42");
        assertThat(upgraded).contains("custom-root: retained");
        assertThat(upgraded).contains("port: 8888");
        assertThat(upgraded).contains("config-version: 1");
        assertThat(upgraded).contains("custom: \"custom format\"");
        assertThat(upgraded).doesNotContain("global: \"global format\"");
        assertThat(upgraded).doesNotContain("\"!\": global");
        assertThat(Files.readString(result.backupPath())).isEqualTo(original);
    }

    @Test
    void preservesStructuralTypeErrorsAndIsIdempotent() throws Exception {
        Path config = tempDir.resolve("config.yml");
        Files.writeString(config, "backend: invalid\nfeature: []\n");

        YamlConfigUpdater.UpdateResult first =
                YamlConfigUpdater.update(config, TEMPLATE, Set.of());
        String upgraded = Files.readString(config);
        YamlConfigUpdater.UpdateResult second =
                YamlConfigUpdater.update(config, TEMPLATE, Set.of());

        assertThat(first.updated()).isTrue();
        assertThat(upgraded).contains("backend: invalid");
        assertThat(upgraded).contains("feature: [");
        assertThat(upgraded).doesNotContain("host: \"127.0.0.1\"");
        assertThat(upgraded).doesNotContain("enabled: true");
        assertThat(second.changed()).isFalse();
        assertThat(Files.readString(config)).isEqualTo(upgraded);
    }

    @Test
    void malformedYamlIsNeverOverwritten() throws Exception {
        Path config = tempDir.resolve("config.yml");
        String malformed = "backend: [\n";
        Files.writeString(config, malformed);

        assertThatThrownBy(() -> YamlConfigUpdater.update(config, TEMPLATE, Set.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid YAML");
        assertThat(Files.readString(config)).isEqualTo(malformed);
        assertThat(config.resolveSibling("config.yml.bak")).doesNotExist();
    }
}
