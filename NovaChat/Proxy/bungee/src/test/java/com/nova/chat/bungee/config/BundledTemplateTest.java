package com.nova.chat.bungee.config;

import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.config.ProxyMode;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shapes of the bundled {@code config.yml}: the updater only inserts
 * missing template nodes, so anything wrong here is written into every new
 * install and into every upgraded one.
 */
@DisplayName("Bundled config.yml")
class BundledTemplateTest {

    private static String template() throws Exception {
        try (InputStream in = BundledTemplateTest.class.getResourceAsStream("/config.yml")) {
            assertThat(in).as("bundled config.yml").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Configuration load(String yaml) {
        return ConfigurationProvider.getProvider(YamlConfiguration.class)
                .load(new StringReader(yaml));
    }

    @Test
    @DisplayName("declares relay as the default mode")
    void declaresRelayDefault() throws Exception {
        assertThat(template()).contains("mode: \"relay\"");
    }

    @Test
    @DisplayName("flipping the template's mode to lite yields a valid lite config")
    void liteFlipIsValid() throws Exception {
        String lite = template().replace("mode: \"relay\"", "mode: \"lite\"");

        ProxyConfig config = ProxyConfig.from(new BungeeConfigSource(load(lite)));

        assertThat(config.getMode()).isEqualTo(ProxyMode.LITE);
        assertThat(config.getLiteChannels()).containsKeys("global", "local");
        assertThat(config.getDefaultChannel()).isEqualTo("local");
        assertThat(config.getLiteStorage().getType().name()).isEqualTo("MEMORY");
    }

    @Test
    @DisplayName("the default template parses as relay")
    void defaultTemplateParsesAsRelay() throws Exception {
        ProxyConfig config = ProxyConfig.from(new BungeeConfigSource(load(template())));

        assertThat(config.getMode()).isEqualTo(ProxyMode.RELAY);
        assertThat(config.getBackendPort()).isEqualTo(8888);
        assertThat(config.getLiteChannels()).isEmpty();
        assertThat(config.getLiteStorage()).isNull();
    }
}
