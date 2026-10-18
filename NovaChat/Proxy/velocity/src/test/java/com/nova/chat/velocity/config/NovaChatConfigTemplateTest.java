package com.nova.chat.velocity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovaChatConfigTemplateTest {

    /** A lite document with no backend section at all. */
    private static final String MINIMAL_LITE_TOML = """
            mode = "lite"

            [chat]
            replace_vanilla = false
            default_channel = "local"
            locale = "zh_CN"

            [chat.channel-prefixes]
            "!" = "global"

            [format]
            prefix = ""
            error = "{message}"
            success = "{message}"
            default = "{message}"

            [format.channels]

            [lite]
            server-id = "proxy-1"

            [lite.channels]
            global = { display-name = "全服", permission = "novachat.channel.global", max-capacity = 1000, slow-mode = 0 }
            local = { display-name = "本地" }

            [lite.storage]
            type = "memory"
            """;

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
    void templateDefaultsToRelayMode() throws Exception {
        NovaChatConfig config = new NovaChatConfig(tempDir);

        assertThat(config.getMode().name()).isEqualTo("RELAY");
        assertThat(Files.readString(tempDir.resolve("config.toml")))
                .contains("mode = \"relay\"");
    }

    @Test
    void upgradesAnOldConfigToExplicitRelayMode() throws Exception {
        Path file = tempDir.resolve("config.toml");
        Files.writeString(file, """
                # operator config
                [backend]
                host = "velocity.internal"

                [format.channels]
                custom = "custom format"
                """);

        NovaChatConfig config = new NovaChatConfig(tempDir);

        assertThat(config.wasConfigUpdated()).isTrue();
        assertThat(config.getMode().name()).isEqualTo("RELAY");
        // The upgrade must not silently switch an existing install to lite.
        assertThat(Files.readString(file)).contains("mode = \"relay\"");
        assertThat(config.getBackendHost()).isEqualTo("velocity.internal");
    }

    @Test
    void liteModeSkipsTheBackendSection() throws Exception {
        Files.writeString(tempDir.resolve("config.toml"), MINIMAL_LITE_TOML);

        NovaChatConfig config = new NovaChatConfig(tempDir);

        assertThat(config.getMode().name()).isEqualTo("LITE");
        assertThat(config.getClientId()).isEqualTo("proxy-1");
        assertThat(config.getLiteChannels()).containsKeys("global", "local");
        assertThat(config.getLiteStorage().getType().name()).isEqualTo("MEMORY");
        // No backend was validated or parsed; the connection config is refused.
        assertThatThrownBy(config::toClientConnectionConfig)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void relayModeRepairsAMissingBackendSectionFromTheTemplate() throws Exception {
        // The template updater re-appends the backend section, so a relay file
        // that lost it still starts (this is why lite must ignore the section
        // rather than require its removal).
        Files.writeString(tempDir.resolve("config.toml"), """
                mode = "relay"
                [chat]
                replace_vanilla = false
                default_channel = "local"
                locale = "zh_CN"
                [chat.channel-prefixes]
                [format]
                prefix = ""
                error = "{message}"
                success = "{message}"
                default = "{message}"
                [format.channels]
                """);

        NovaChatConfig config = new NovaChatConfig(tempDir);

        assertThat(config.getBackendHost()).isEqualTo("127.0.0.1");
        assertThat(config.getBackendPort()).isEqualTo(8888);
    }

    @Test
    void relayModeRejectsAnInvalidBackendSection() throws Exception {
        Files.writeString(tempDir.resolve("config.toml"), """
                mode = "relay"
                [backend]
                host = "  "
                port = 8888
                username = ""
                password = ""
                reconnect-delay = 5
                [chat]
                replace_vanilla = false
                default_channel = "local"
                locale = "zh_CN"
                [chat.channel-prefixes]
                [format]
                prefix = ""
                error = "{message}"
                success = "{message}"
                default = "{message}"
                [format.channels]
                """);

        assertThatThrownBy(() -> new NovaChatConfig(tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backend.host")
                .hasMessageContaining("must not be blank");
    }

    @Test
    void unsupportedModeIsRejected() throws Exception {
        Files.writeString(tempDir.resolve("config.toml"),
                MINIMAL_LITE_TOML.replace("mode = \"lite\"", "mode = \"bridge\""));

        assertThatThrownBy(() -> new NovaChatConfig(tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported")
                .hasMessageContaining("bridge");
    }

    @Test
    void liteModeRequiresAtLeastOneChannel() throws Exception {
        Files.writeString(tempDir.resolve("config.toml"), """
                mode = "lite"
                [chat]
                replace_vanilla = false
                default_channel = "global"
                locale = "zh_CN"
                [chat.channel-prefixes]
                [format]
                prefix = ""
                error = "{message}"
                success = "{message}"
                default = "{message}"
                [format.channels]
                [lite]
                server-id = "proxy-1"
                [lite.channels]
                [lite.storage]
                type = "memory"
                """);

        assertThatThrownBy(() -> new NovaChatConfig(tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lite.channels")
                .hasMessageContaining("at least one channel");
    }

    @Test
    void liteModeRejectsUndeclaredDefaultChannel() throws Exception {
        Files.writeString(tempDir.resolve("config.toml"),
                MINIMAL_LITE_TOML.replace("default_channel = \"local\"",
                        "default_channel = \"trade\""));

        assertThatThrownBy(() -> new NovaChatConfig(tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat.default_channel")
                .hasMessageContaining("undefined channel");
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
