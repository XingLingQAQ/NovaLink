package com.nova.chat.client.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChannelColorResolver")
class ChannelColorResolverTest {

    @Nested
    @DisplayName("resolveColor")
    class ResolveColor {

        @Test
        @DisplayName("is deterministic: same name → same color across calls")
        void deterministicAcrossCalls() {
            String first = ChannelColorResolver.resolveColor("global");
            String second = ChannelColorResolver.resolveColor("global");

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("is deterministic across instances (no state)")
        void deterministicNoState() {
            // Static pure function — resolution does not depend on prior calls.
            String a = ChannelColorResolver.resolveColor("pvp");
            String b = ChannelColorResolver.resolveColor("trade");
            String c = ChannelColorResolver.resolveColor("pvp");

            assertThat(c).isEqualTo(a);
            assertThat(b).isNotEqualTo(a); // not a hard guarantee, but very likely with distinct names
        }

        @Test
        @DisplayName("different channel names may map to different colors")
        void differentNamesDifferentColors() {
            Set<String> colors = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                colors.add(ChannelColorResolver.resolveColor("channel-" + i));
            }
            // With 100 distinct names and a 10-entry palette, we expect several distinct colors.
            assertThat(colors.size()).isGreaterThan(1);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null/empty channel name returns default gray")
        void nullOrEmptyReturnsDefault(String name) {
            assertThat(ChannelColorResolver.resolveColor(name))
                    .isEqualTo(ChannelColorResolver.DEFAULT_COLOR)
                    .isEqualTo("&7");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "&a", "&b", "&c", "&d", "&e", "&6", "&5", "&9", "&3", "&2"
        })
        @DisplayName("resolved colors stay within the curated palette")
        void colorsWithinPalette(String expected) {
            // Find at least one name that maps to each palette entry (coverage sanity).
            boolean found = false;
            for (int i = 0; i < 5000 && !found; i++) {
                if (expected.equals(ChannelColorResolver.resolveColor("probe-" + i))) {
                    found = true;
                }
            }
            assertThat(found).as("palette entry %s is reachable", expected).isTrue();
        }

        @Test
        @DisplayName("every result across many names is a 2-char &X code from the palette")
        void allResultsArePaletteCodes() {
            Set<String> allowed = Set.of(
                    "&a", "&b", "&c", "&d", "&e", "&6", "&5", "&9", "&3", "&2", "&7");
            for (int i = 0; i < 1000; i++) {
                String color = ChannelColorResolver.resolveColor("name-" + i);
                assertThat(color).hasSize(2);
                assertThat(color.charAt(0)).isEqualTo('&');
                assertThat(allowed).contains(color);
            }
        }

        @Test
        @DisplayName("resolveColor is stable for empty-string channel (not in palette test)")
        void emptyIsGray() {
            assertThat(ChannelColorResolver.resolveColor("")).isEqualTo("&7");
        }

        @Test
        @DisplayName("palette size matches documented value")
        void paletteSize() {
            assertThat(ChannelColorResolver.paletteSize()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("real-world channel names")
    class RealWorld {

        @Test
        @DisplayName("common channel ids resolve to consistent colors")
        void commonChannels() {
            String global = ChannelColorResolver.resolveColor("global");
            String local = ChannelColorResolver.resolveColor("local");
            String pvp = ChannelColorResolver.resolveColor("pvp");
            String trade = ChannelColorResolver.resolveColor("trade");
            String staff = ChannelColorResolver.resolveColor("staff");

            // No exceptions, all valid 2-char codes.
            assertThat(global).hasSize(2);
            assertThat(local).hasSize(2);
            assertThat(pvp).hasSize(2);
            assertThat(trade).hasSize(2);
            assertThat(staff).hasSize(2);
        }
    }
}
