package com.nova.chat.bungee.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovaChatConfigTemplateTest {

    /** A lite document with no backend section at all. */
    private static final String MINIMAL_LITE_YAML = """
            mode: "lite"

            chat:
              replace_vanilla: false
              default_channel: "local"
              locale: "zh_CN"
              channel-prefixes:
                "!": "global"

            format:
              prefix: ""
              error: "{message}"
              success: "{message}"
              default: "{message}"
              channels: {}

            lite:
              server-id: "proxy-1"
              channels:
                global:
                  display-name: "全服"
                  permission: "novachat.channel.global"
                  max-capacity: 1000
                  slow-mode: 0
                local:
                  display-name: "本地"
              storage:
                type: "memory"
            """;

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
    void templateDefaultsToRelayMode() throws Exception {
        NovaChatConfig config = new NovaChatConfig(tempDir.toFile());

        assertThat(config.getMode().name()).isEqualTo("RELAY");
        assertThat(Files.readString(tempDir.resolve("config.yml")))
                .contains("mode: \"relay\"");
    }

    @Test
    void upgradesAnOldConfigToExplicitRelayMode() throws Exception {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, """
                # proxy operator config
                backend:
                  host: "proxy.internal"
                format:
                  channels:
                    staff: "staff format"
                """);

        NovaChatConfig config = new NovaChatConfig(tempDir.toFile());

        assertThat(config.getUpdateResult().updated()).isTrue();
        assertThat(config.getMode().name()).isEqualTo("RELAY");
        // The upgrade must not silently switch an existing install to lite.
        assertThat(Files.readString(file)).contains("mode: \"relay\"");
        assertThat(config.getBackendHost()).isEqualTo("proxy.internal");
    }

    @Test
    void liteModeSkipsTheBackendSection() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), MINIMAL_LITE_YAML);

        NovaChatConfig config = new NovaChatConfig(tempDir.toFile());

        assertThat(config.getMode().name()).isEqualTo("LITE");
        assertThat(config.getClientId()).isEqualTo("proxy-1");
        assertThat(config.getLiteChannels()).containsKeys("global", "local");
        assertThat(config.getLiteStorage().getType().name()).isEqualTo("MEMORY");
        assertThatThrownBy(config::toClientConnectionConfig)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void relayModeRepairsAMissingBackendSectionFromTheTemplate() throws Exception {
        // The template updater re-appends the backend section, so a relay file
        // that lost it still starts (this is why lite must ignore the section
        // rather than require its removal).
        Files.writeString(tempDir.resolve("config.yml"), """
                mode: "relay"
                chat:
                  replace_vanilla: false
                  default_channel: "local"
                  locale: "zh_CN"
                  channel-prefixes: {}
                format:
                  prefix: ""
                  error: "{message}"
                  success: "{message}"
                  default: "{message}"
                  channels: {}
                """);

        NovaChatConfig config = new NovaChatConfig(tempDir.toFile());

        assertThat(config.getBackendHost()).isEqualTo("127.0.0.1");
        assertThat(config.getBackendPort()).isEqualTo(8888);
    }

    @Test
    void relayModeRejectsAnInvalidBackendSection() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), """
                mode: "relay"
                backend:
                  host: "  "
                  port: 8888
                  username: ""
                  password: ""
                  reconnect-delay: 5
                chat:
                  replace_vanilla: false
                  default_channel: "local"
                  locale: "zh_CN"
                  channel-prefixes: {}
                format:
                  prefix: ""
                  error: "{message}"
                  success: "{message}"
                  default: "{message}"
                  channels: {}
                """);

        assertThatThrownBy(() -> new NovaChatConfig(tempDir.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backend.host")
                .hasMessageContaining("must not be blank");
    }

    @Test
    void unsupportedModeIsRejected() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"),
                MINIMAL_LITE_YAML.replace("mode: \"lite\"", "mode: \"bridge\""));

        assertThatThrownBy(() -> new NovaChatConfig(tempDir.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported")
                .hasMessageContaining("bridge");
    }

    @Test
    void liteModeRequiresAtLeastOneChannel() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), """
                mode: "lite"
                chat:
                  replace_vanilla: false
                  default_channel: "global"
                  locale: "zh_CN"
                  channel-prefixes: {}
                format:
                  prefix: ""
                  error: "{message}"
                  success: "{message}"
                  default: "{message}"
                  channels: {}
                lite:
                  server-id: "proxy-1"
                  channels: {}
                  storage:
                    type: "memory"
                """);

        assertThatThrownBy(() -> new NovaChatConfig(tempDir.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lite.channels")
                .hasMessageContaining("at least one channel");
    }

    @Test
    void liteModeRejectsUndeclaredDefaultChannel() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"),
                MINIMAL_LITE_YAML.replace("default_channel: \"local\"",
                        "default_channel: \"trade\""));

        assertThatThrownBy(() -> new NovaChatConfig(tempDir.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat.default_channel")
                .hasMessageContaining("undefined channel");
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
