package com.nova.link.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing + persistence of the new {@code filter} section (custom words/regex
 * patterns) and {@code features.message-log-retention-days}.
 */
@DisplayName("filter section + message-log-retention-days config parsing")
class FilterConfigParsingTest {

    @TempDir
    Path tempDir;

    private NovaLinkConfig loadFromYaml(String yaml) throws Exception {
        Path file = tempDir.resolve("novalink-test.yml");
        Files.writeString(file, yaml);
        return new ConfigLoader(file).load();
    }

    private static final String BASE_SERVER = """
            server:
              bind-address: 0.0.0.0
              port: 8888
              websocket-port: 8889
              secret-key: change-me-in-production
              worker-threads: 4
              locale: zh_CN
            """;

    @Test
    @DisplayName("filter.words/patterns and retention days are parsed")
    void parsesFilterSectionAndRetention() throws Exception {
        NovaLinkConfig config = loadFromYaml(BASE_SERVER + """
                features:
                  filter-enabled: true
                  message-log-enabled: true
                  message-log-retention-days: 7
                filter:
                  words:
                    - badword1
                    - badword2
                  patterns:
                    - "\\\\bspam\\\\b"
                """);

        assertThat(config.getFilter().getWords()).containsExactly("badword1", "badword2");
        assertThat(config.getFilter().getPatterns()).containsExactly("\\bspam\\b");
        assertThat(config.getFeatures().getMessageLogRetentionDays()).isEqualTo(7);
        assertThat(config.getFeatures().isMessageLogEnabled()).isTrue();
    }

    @Test
    @DisplayName("missing filter section defaults to empty lists; retention defaults to 30")
    void defaultsWhenAbsent() throws Exception {
        NovaLinkConfig config = loadFromYaml(BASE_SERVER);

        assertThat(config.getFilter()).isNotNull();
        assertThat(config.getFilter().getWords()).isEmpty();
        assertThat(config.getFilter().getPatterns()).isEmpty();
        assertThat(config.getFeatures().getMessageLogRetentionDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("save/load round-trips the filter section and retention days")
    void saveLoadRoundTrip() throws Exception {
        Path file = tempDir.resolve("roundtrip.yml");
        Files.writeString(file, BASE_SERVER);
        ConfigLoader loader = new ConfigLoader(file);
        NovaLinkConfig config = loader.load();

        config.getFilter().setWords(List.of("alpha", "beta"));
        config.getFilter().setPatterns(List.of("x.*y"));
        config.getFeatures().setMessageLogRetentionDays(14);
        loader.save();

        NovaLinkConfig reloaded = new ConfigLoader(file).load();
        assertThat(reloaded.getFilter().getWords()).containsExactly("alpha", "beta");
        assertThat(reloaded.getFilter().getPatterns()).containsExactly("x.*y");
        assertThat(reloaded.getFeatures().getMessageLogRetentionDays()).isEqualTo(14);
    }
}
