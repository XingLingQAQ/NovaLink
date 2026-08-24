package com.nova.link.config;

import com.nova.link.auth.AuthManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("bundled config template upgrades")
class ConfigTemplateUpgradeTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("save before load uses the bundled template for an empty target file")
    void saveBeforeLoadUsesTemplateForEmptyFile() throws Exception {
        Path file = tempDir.resolve("novalink.yml");
        Files.createFile(file);
        ConfigLoader loader = new ConfigLoader(file);
        java.lang.reflect.Field configField = ConfigLoader.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(loader, NovaLinkConfig.createDefault());

        loader.save();

        String saved = Files.readString(file);
        assertThat(saved).contains("server:");
        assertThat(saved).contains("database:");
        assertThat(new ConfigLoader(file).load()).isNotNull();
    }

    @Test
    @DisplayName("save rejects malformed base YAML without overwriting it")
    void malformedBaseIsNotOverwrittenBySave() throws Exception {
        Path file = tempDir.resolve("novalink.yml");
        String malformed = "server: [\n";
        Files.writeString(file, malformed);
        ConfigLoader loader = new ConfigLoader(file);
        java.lang.reflect.Field configField = ConfigLoader.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(loader, NovaLinkConfig.createDefault());

        assertThatThrownBy(loader::save)
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Comment-preserving");
        assertThat(Files.readString(file)).isEqualTo(malformed);
        assertThat(file.resolveSibling("novalink.yml.bak")).doesNotExist();
    }

    @Test
    @DisplayName("a non-mapping YAML root is rejected without overwriting it")
    void nonMappingRootIsNotOverwritten() throws Exception {
        Path file = tempDir.resolve("novalink.yml");
        String invalidRoot = "valid-yaml-scalar\n";
        Files.writeString(file, invalidRoot);

        assertThatThrownBy(() -> new ConfigLoader(file).load())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("mapping");
        assertThat(Files.readString(file)).isEqualTo(invalidRoot);
        assertThat(file.resolveSibling("novalink.yml.bak")).doesNotExist();
    }

    @Test
    @DisplayName("fixed scalar type errors are rejected without overwriting user values")
    void rejectsWrongScalarTypesWithoutOverwrite() throws Exception {
        Path file = tempDir.resolve("novalink.yml");
        String invalidTypes = """
                server:
                  port: wrong
                debug: wrong
                """;
        Files.writeString(file, invalidTypes);

        assertThatThrownBy(() -> new ConfigLoader(file).load())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("server.port");
        assertThat(Files.readString(file)).isEqualTo(invalidTypes);
        assertThat(file.resolveSibling("novalink.yml.bak")).doesNotExist();
    }

    @Test
    @DisplayName("a new installation copies the bundled YAML template")
    void createsNewConfigFromBundledTemplate() throws Exception {
        Path file = tempDir.resolve("novalink.yml");

        NovaLinkConfig config = new ConfigLoader(file).load();
        String saved = Files.readString(file);

        assertThat(saved).contains("# NovaLink backend configuration");
        assertThat(saved).contains("private-messages-enabled: true");
        assertThat(saved).contains("message-log-retention-days: 30");
        assertThat(config.getGlobalChannels()).containsKey("global");
        assertThat(config.getTemplates()).containsKey("standard_local");
        assertThat(file.resolveSibling("novalink.yml.bak")).doesNotExist();
    }

    @Test
    @DisplayName("missing template fields are appended without overwriting user values or dynamic maps")
    void upgradesLegacyConfigAndPreservesUserContent() throws Exception {
        Path file = tempDir.resolve("novalink.yml");
        String legacy = """
                # operator root comment
                server:
                  # custom port comment
                  port: 9999 # keep this inline comment
                  custom-server-key: retained
                global_channels:
                  custom:
                    display_name: Custom
                    max_capacity: 250
                    custom-channel-key: retained
                custom-top-level: retained
                """;
        Files.writeString(file, legacy);

        NovaLinkConfig config = new ConfigLoader(file).load();
        String upgraded = Files.readString(file);

        assertThat(config.getServer().getPort()).isEqualTo(9999);
        assertThat(config.getGlobalChannels()).containsOnlyKeys("custom");
        assertThat(upgraded).contains("# operator root comment");
        assertThat(upgraded).contains("# custom port comment");
        assertThat(upgraded).contains("port: 9999 # keep this inline comment");
        assertThat(upgraded).contains("custom-server-key: retained");
        assertThat(upgraded).contains("custom-channel-key: retained");
        assertThat(upgraded).contains("custom-top-level: retained");
        assertThat(upgraded).contains("private-messages-enabled: true");
        assertThat(upgraded).contains("message-log-retention-days: 30");
        assertThat(upgraded).doesNotContain("  global:\n");
        assertThat(Files.readString(file.resolveSibling("novalink.yml.bak"))).isEqualTo(legacy);
    }

    @Test
    @DisplayName("runtime saves keep comments and unknown fields while persisting lists and settings")
    void runtimeSavePreservesUnknownContent() throws Exception {
        Path file = tempDir.resolve("novalink.yml");
        Files.writeString(file, """
                # keep root
                server:
                  port: 9000 # keep inline
                  operator-option: yes
                custom-section:
                  enabled: true
                """);
        ConfigLoader loader = new ConfigLoader(file);
        NovaLinkConfig config = loader.load();

        config.getFeatures().setPrivateMessagesEnabled(false);
        config.getFeatures().setMessageLogRetentionDays(14);
        config.getFilter().setWords(java.util.List.of("alpha", "beta"));
        config.getFilter().setPatterns(java.util.List.of("x.*y"));
        loader.save();

        String saved = Files.readString(file);
        assertThat(saved).contains("# keep root");
        assertThat(saved).contains("port: 9000 # keep inline");
        assertThat(saved).contains("operator-option: yes");
        assertThat(saved).contains("custom-section:");
        NovaLinkConfig reloaded = new ConfigLoader(file).load();
        assertThat(reloaded.getFeatures().isPrivateMessagesEnabled()).isFalse();
        assertThat(reloaded.getFeatures().getMessageLogRetentionDays()).isEqualTo(14);
        assertThat(reloaded.getFilter().getWords()).containsExactly("alpha", "beta");
        assertThat(reloaded.getFilter().getPatterns()).containsExactly("x.*y");
    }

    @Test
    @DisplayName("plain panel passwords are replaced by hashes on save")
    void migratesPlainPasswordsOnSave() throws Exception {
        Path file = tempDir.resolve("novalink.yml");
        Files.writeString(file, """
                super-admins:
                  - uuid: 123e4567-e89b-12d3-a456-426614174000
                    username: root-admin
                    password: admin123
                panel-users:
                  - username: viewer
                    password: viewer123
                    role: VIEWER
                """);

        ConfigLoader loader = new ConfigLoader(file);
        loader.load();
        loader.save();
        String saved = Files.readString(file);

        assertThat(saved).doesNotContain("password: admin123");
        assertThat(saved).doesNotContain("password: viewer123");
        assertThat(saved).contains("password-hash: " + AuthManager.hashPassword("admin123"));
        assertThat(saved).contains("password-hash: " + AuthManager.hashPassword("viewer123"));
    }

    @Test
    @DisplayName("wrong section types are rejected without overwriting the file")
    void rejectsWrongSectionTypes() throws Exception {
        Path file = tempDir.resolve("novalink.yml");
        String invalid = """
                server: invalid
                database:
                  mysql: invalid
                features: []
                clients:
                  - invalid
                  - username: valid
                    channels: invalid
                """;
        Files.writeString(file, invalid);

        assertThatThrownBy(() -> new ConfigLoader(file).load())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("server");
        assertThat(Files.readString(file)).isEqualTo(invalid);
        assertThat(file.resolveSibling("novalink.yml.bak")).doesNotExist();
    }
}
