package com.nova.chat.velocity.config;

import com.moandjiezana.toml.Toml;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.config.ProxyMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shapes of the bundled {@code config.toml}: the updater only inserts
 * missing template lines, so anything wrong here is written into every new
 * install and into every upgraded one.
 */
@DisplayName("Bundled config.toml")
class BundledTemplateTest {

    private static String template() throws Exception {
        try (InputStream in = BundledTemplateTest.class.getResourceAsStream("/config.toml")) {
            assertThat(in).as("bundled config.toml").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("declares relay as the default mode")
    void declaresRelayDefault() throws Exception {
        assertThat(template()).contains("\nmode = \"relay\"");
    }

    @Test
    @DisplayName("uses one [lite.channels] table with single-line inline entries")
    void usesInlineChannelTable() throws Exception {
        String toml = template();

        assertThat(toml).contains("[lite.channels]");
        // The updater's header pattern only matches single-bracket headers, so a
        // nested array-of-tables would be re-appended on every start.
        assertThat(toml).doesNotContain("[[");
        assertThat(toml).containsPattern("(?m)^global = \\{ display-name = .+\\}$");
    }

    @Test
    @DisplayName("flipping the template's mode to lite yields a valid lite config")
    void liteFlipIsValid() throws Exception {
        String lite = template().replace("mode = \"relay\"", "mode = \"lite\"");

        ProxyConfig config = ProxyConfig.from(new TomlConfigSource(new Toml().read(lite)));

        assertThat(config.getMode()).isEqualTo(ProxyMode.LITE);
        assertThat(config.getLiteChannels()).containsKeys("global", "local");
        assertThat(config.getDefaultChannel()).isEqualTo("local");
        assertThat(config.getLiteStorage().getType().name()).isEqualTo("MEMORY");
    }

    @Test
    @DisplayName("the default template parses as relay")
    void defaultTemplateParsesAsRelay() throws Exception {
        ProxyConfig config = ProxyConfig.from(new TomlConfigSource(new Toml().read(template())));

        assertThat(config.getMode()).isEqualTo(ProxyMode.RELAY);
        assertThat(config.getBackendPort()).isEqualTo(8888);
        assertThat(config.getLiteChannels()).isEmpty();
        assertThat(config.getLiteStorage()).isNull();
    }
}
