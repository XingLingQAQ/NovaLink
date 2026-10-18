package com.nova.chat.velocity.config;

import com.moandjiezana.toml.Toml;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.config.ProxyMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the TOML dialect adapter against the shapes toml4j actually
 * produces: quoted keys keep their quotes, inline tables are maps, and the
 * dotted getters the pre-refactor loader used behave differently.
 */
@DisplayName("TomlConfigSource")
class TomlConfigSourceTest {

    @Test
    @DisplayName("resolves quoted keys and single-line inline tables")
    void resolvesQuotedKeysAndInlineTables() throws Exception {
        Toml toml = new Toml().read("""
                mode = "lite"
                debug = false

                [chat]
                replace_vanilla = true
                default_channel = "global"
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
                "global" = { display-name = "全服", max-capacity = 1000, slow-mode = 5 }
                local = { display-name = "本地" }

                [lite.storage]
                type = "sqlite"
                sqlite = { file-path = "data/novachat.db" }
                """);

        ProxyConfig config = ProxyConfig.from(new TomlConfigSource(toml));

        assertThat(config.getMode()).isEqualTo(ProxyMode.LITE);
        assertThat(config.getDefaultChannel()).isEqualTo("global");
        assertThat(config.getChannelPrefixes()).containsEntry("!", "global");
        assertThat(config.getLiteChannels()).containsOnlyKeys("global", "local");
        assertThat(config.getLiteChannels().get("global").getMaxCapacity()).isEqualTo(1000);
        assertThat(config.getLiteChannels().get("global").getSlowMode()).isEqualTo(5);
        assertThat(config.getLiteChannels().get("local").getDisplayName()).isEqualTo("本地");
        assertThat(config.getLiteStorage().getFilePath()).isEqualTo("data/novachat.db");
    }

    @Test
    @DisplayName("reports absent keys instead of treating an empty table as configured")
    void absentKeysReadAsMissing() throws Exception {
        Toml toml = new Toml().read("mode = \"relay\"\n");
        TomlConfigSource source = new TomlConfigSource(toml);

        assertThat(source.contains("backend")).isFalse();
        assertThat(source.contains("mode")).isTrue();
        assertThat(source.optionalTable("lite.channels")).isNull();
    }

    /**
     * An explicit {@code [lite.channels]} header with no entries parses as an
     * empty table, which the model rejects with the lite "at least one channel"
     * rule rather than a missing-table error.
     */
    @Test
    @DisplayName("an empty lite.channels table is present but empty")
    void emptyChannelTableIsPresent() throws Exception {
        Toml toml = new Toml().read("""
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
        TomlConfigSource source = new TomlConfigSource(toml);

        assertThat(source.contains("lite.channels")).isTrue();
        assertThat(source.requiredTable("lite.channels")).isEmpty();
    }
}
