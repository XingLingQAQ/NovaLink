package com.nova.chat.bungee.config;

import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.config.ProxyMode;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the YAML dialect adapter, in particular the two md_5 behaviours that
 * differ from toml4j: {@code getSection} invents an empty section for an absent
 * key, and integers arrive as {@code Integer} rather than {@code Long}.
 */
@DisplayName("BungeeConfigSource")
class BungeeConfigSourceTest {

    private static Configuration load(String yaml) {
        return ConfigurationProvider.getProvider(YamlConfiguration.class)
                .load(new StringReader(yaml));
    }

    @Test
    @DisplayName("parses a lite document including nested channel sections")
    void parsesLiteDocument() throws Exception {
        Configuration yaml = load("""
                mode: "lite"
                debug: false

                chat:
                  replace_vanilla: true
                  default_channel: "global"
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
                      max-capacity: 1000
                      slow-mode: 5
                    local:
                      display-name: "本地"
                  storage:
                    type: "sqlite"
                    sqlite:
                      file-path: "data/novachat.db"
                """);

        ProxyConfig config = ProxyConfig.from(new BungeeConfigSource(yaml));

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
    @DisplayName("reports absent sections as missing despite getSection's empty stub")
    void absentSectionsReadAsMissing() throws Exception {
        BungeeConfigSource source = new BungeeConfigSource(load("mode: \"relay\"\n"));

        assertThat(source.contains("backend")).isFalse();
        assertThat(source.contains("backend.host")).isFalse();
        assertThat(source.contains("mode")).isTrue();
        assertThat(source.optionalTable("lite.channels")).isNull();
    }

    @Test
    @DisplayName("an explicit empty channel section is present but empty")
    void emptyChannelSectionIsPresent() throws Exception {
        BungeeConfigSource source = new BungeeConfigSource(load("""
                mode: "lite"
                lite:
                  channels: {}
                """));

        assertThat(source.contains("lite.channels")).isTrue();
        assertThat(source.requiredTable("lite.channels")).isEmpty();
    }
}
