package com.nova.chat.client.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChannelPrefixResolver}.
 *
 * <p>Covers prefix matching (single and multi character, longest wins),
 * escaping with {@code \}, unknown/blank channel fallbacks, and the
 * non-empty-content rule.
 */
@DisplayName("ChannelPrefixResolver")
class ChannelPrefixResolverTest {

    private static final Set<String> KNOWN = Set.of("global", "trade", "staff");

    private static Map<String, String> prefixes() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("!", "global");
        map.put("$", "trade");
        return map;
    }

    @Nested
    @DisplayName("redirects")
    class Redirects {

        @Test
        @DisplayName("single-character prefix routes to the mapped channel with prefix stripped")
        void singleCharPrefix() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "!hello world", KNOWN);
            assertThat(r.isRedirect()).isTrue();
            assertThat(r.getChannelId()).isEqualTo("global");
            assertThat(r.getMessage()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("prefix works with non-ASCII content")
        void chineseContent() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "!\u5927\u5bb6\u597d", KNOWN);
            assertThat(r.isRedirect()).isTrue();
            assertThat(r.getChannelId()).isEqualTo("global");
            assertThat(r.getMessage()).isEqualTo("\u5927\u5bb6\u597d");
        }

        @Test
        @DisplayName("multi-character prefix is supported")
        void multiCharPrefix() {
            Map<String, String> map = Map.of("!!", "staff");
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(map, "!!ping", KNOWN);
            assertThat(r.isRedirect()).isTrue();
            assertThat(r.getChannelId()).isEqualTo("staff");
            assertThat(r.getMessage()).isEqualTo("ping");
        }

        @Test
        @DisplayName("longest matching prefix wins when several match")
        void longestPrefixWins() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("!", "global");
            map.put("!!", "staff");
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(map, "!!hi", KNOWN);
            assertThat(r.getChannelId()).isEqualTo("staff");
            assertThat(r.getMessage()).isEqualTo("hi");
        }

        @Test
        @DisplayName("content is trimmed after the prefix")
        void contentTrimmed() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "!   spaced   ", KNOWN);
            assertThat(r.isRedirect()).isTrue();
            assertThat(r.getMessage()).isEqualTo("spaced");
        }
    }

    @Nested
    @DisplayName("passthrough cases")
    class Passthrough {

        @Test
        @DisplayName("message without a configured prefix passes through unchanged")
        void noPrefix() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "hello", KNOWN);
            assertThat(r.isRedirect()).isFalse();
            assertThat(r.getChannelId()).isNull();
            assertThat(r.getMessage()).isEqualTo("hello");
        }

        @Test
        @DisplayName("empty content after prefix is treated as a normal message")
        void emptyContent() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "!", KNOWN);
            assertThat(r.isRedirect()).isFalse();
            assertThat(r.getMessage()).isEqualTo("!");
        }

        @Test
        @DisplayName("blank content after prefix is treated as a normal message")
        void blankContent() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "!   ", KNOWN);
            assertThat(r.isRedirect()).isFalse();
            assertThat(r.getMessage()).isEqualTo("!   ");
        }

        @Test
        @DisplayName("unknown target channel falls back to normal message")
        void unknownChannel() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "!hello", Set.of("trade"));
            assertThat(r.isRedirect()).isFalse();
            assertThat(r.getMessage()).isEqualTo("!hello");
        }

        @Test
        @DisplayName("null known-channel view falls back to normal message")
        void nullKnownChannels() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "!hello", null);
            assertThat(r.isRedirect()).isFalse();
            assertThat(r.getMessage()).isEqualTo("!hello");
        }

        @Test
        @DisplayName("null or empty prefix map disables the feature")
        void disabledFeature() {
            assertThat(ChannelPrefixResolver.resolve(null, "!hello", KNOWN).isRedirect()).isFalse();
            assertThat(ChannelPrefixResolver.resolve(Collections.emptyMap(), "!hello", KNOWN)
                    .isRedirect()).isFalse();
        }

        @Test
        @DisplayName("blank mapped channel id is ignored")
        void blankChannelId() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("!", "  ");
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(map, "!hello", KNOWN);
            assertThat(r.isRedirect()).isFalse();
            assertThat(r.getMessage()).isEqualTo("!hello");
        }

        @Test
        @DisplayName("null and empty messages pass through")
        void nullAndEmptyMessage() {
            assertThat(ChannelPrefixResolver.resolve(prefixes(), null, KNOWN).getMessage()).isNull();
            assertThat(ChannelPrefixResolver.resolve(prefixes(), "", KNOWN).getMessage()).isEmpty();
        }
    }

    @Nested
    @DisplayName("escaping")
    class Escaping {

        @Test
        @DisplayName("backslash before a configured prefix sends the literal message")
        void escapedPrefix() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "\\!hello", KNOWN);
            assertThat(r.isRedirect()).isFalse();
            assertThat(r.getMessage()).isEqualTo("!hello");
        }

        @Test
        @DisplayName("backslash before a non-prefix character is left untouched")
        void backslashWithoutPrefix() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "\\hello", KNOWN);
            assertThat(r.isRedirect()).isFalse();
            assertThat(r.getMessage()).isEqualTo("\\hello");
        }

        @Test
        @DisplayName("escape applies even when the prefix map has no matching channel view")
        void escapedPrefixUnknownChannel() {
            ChannelPrefixResolver.Resolution r =
                    ChannelPrefixResolver.resolve(prefixes(), "\\!hello", Set.of());
            assertThat(r.isRedirect()).isFalse();
            assertThat(r.getMessage()).isEqualTo("!hello");
        }
    }
}
