package com.nova.chat.client.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("FormatTemplateEngine")
class FormatTemplateEngineTest {

    private static final String STANDARD =
            "[{channel_name}] {player}: {message}";

    @Nested
    @DisplayName("apply(template, map)")
    class ApplyMap {

        @Test
        @DisplayName("replaces all keys present in the map")
        void replacesPresentKeys() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("player", "Steve");
            map.put("channel", "global");
            map.put("channel_name", "Global");
            map.put("message", "hello");

            String result = FormatTemplateEngine.apply(STANDARD, map);

            assertThat(result).isEqualTo("[Global] Steve: hello");
        }

        @Test
        @DisplayName("leaves missing keys unreplaced")
        void missingKeysUnreplaced() {
            Map<String, String> map = Map.of("player", "Alex");

            String result = FormatTemplateEngine.apply(
                    "{player} in {channel} says {message}", map);

            assertThat(result).isEqualTo("Alex in {channel} says {message}");
        }

        @Test
        @DisplayName("null template returns empty string")
        void nullTemplate() {
            assertThat(FormatTemplateEngine.apply(null, Map.of("player", "x")))
                    .isEqualTo("");
        }

        @Test
        @DisplayName("null map returns template unchanged")
        void nullMap() {
            assertThat(FormatTemplateEngine.apply(STANDARD, null)).isEqualTo(STANDARD);
        }

        @Test
        @DisplayName("empty map returns template unchanged")
        void emptyMap() {
            assertThat(FormatTemplateEngine.apply(STANDARD, Collections.emptyMap()))
                    .isEqualTo(STANDARD);
        }

        @Test
        @DisplayName("null map values become empty string")
        void nullValuesBecomeEmpty() {
            Map<String, String> map = new HashMap<>();
            map.put("player", null);
            map.put("message", "hi");

            String result = FormatTemplateEngine.apply("{player}: {message}", map);

            assertThat(result).isEqualTo(": hi");
        }

        @Test
        @DisplayName("null map keys are skipped")
        void nullKeysSkipped() {
            Map<String, String> map = new HashMap<>();
            map.put(null, "oops");
            map.put("player", "Steve");

            assertThatCode(() -> FormatTemplateEngine.apply("{player}", map))
                    .doesNotThrowAnyException();
            assertThat(FormatTemplateEngine.apply("{player}", map)).isEqualTo("Steve");
        }

        @Test
        @DisplayName("empty key is skipped")
        void emptyKeySkipped() {
            Map<String, String> map = new HashMap<>();
            map.put("", "bad");
            map.put("player", "Steve");

            assertThat(FormatTemplateEngine.apply("{player}{}", map)).isEqualTo("Steve{}");
        }

        @Test
        @DisplayName("extra custom keys are replaced")
        void customKeys() {
            Map<String, String> map = Map.of(
                    "player", "Steve",
                    "world", "world_nether",
                    "server", "lobby-1");

            String result = FormatTemplateEngine.apply(
                    "{player}@{server}/{world}", map);

            assertThat(result).isEqualTo("Steve@lobby-1/world_nether");
        }

        @Test
        @DisplayName("replacement values containing braces are not re-scanned as keys")
        void valuesWithBracesNotRescanned() {
            // Sequential replace: if message is "{player}", and player is later, order matters.
            // Map iteration is insertion order for LinkedHashMap; player first then message.
            Map<String, String> map = new LinkedHashMap<>();
            map.put("player", "Steve");
            map.put("message", "{player}");

            String result = FormatTemplateEngine.apply("{player}: {message}", map);

            // message value is literal "{player}" — already past the player replacement
            assertThat(result).isEqualTo("Steve: {player}");
        }

        @Test
        @DisplayName("dollar and backslash in values are treated literally")
        void specialCharsInValues() {
            Map<String, String> map = Map.of(
                    "player", "$1",
                    "message", "path\\n\\t");

            String result = FormatTemplateEngine.apply("{player}: {message}", map);

            assertThat(result).isEqualTo("$1: path\\n\\t");
        }

        @Test
        @DisplayName("repeated placeholders all get replaced")
        void repeatedPlaceholders() {
            Map<String, String> map = Map.of("player", "Steve");

            assertThat(FormatTemplateEngine.apply("{player} and {player}", map))
                    .isEqualTo("Steve and Steve");
        }

        @Test
        @DisplayName("never throws on arbitrary input")
        void neverThrows() {
            Map<String, String> weird = new HashMap<>();
            weird.put("a", null);
            weird.put(null, "b");
            weird.put("", "c");
            weird.put("x", "y");

            assertThatCode(() -> FormatTemplateEngine.apply(null, weird))
                    .doesNotThrowAnyException();
            assertThatCode(() -> FormatTemplateEngine.apply("{x}{y}{", weird))
                    .doesNotThrowAnyException();
            assertThatCode(() -> FormatTemplateEngine.apply("{x}", (Map<String, String>) null))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("apply(template, player, channel, channelName, message)")
    class ApplyWellKnown {

        @Test
        @DisplayName("replaces the four standard keys")
        void replacesFour() {
            String result = FormatTemplateEngine.apply(
                    "[{channel}/{channel_name}] {player}: {message}",
                    "Steve", "global", "Global Chat", "hi there");

            assertThat(result).isEqualTo("[global/Global Chat] Steve: hi there");
        }

        @Test
        @DisplayName("null values become empty strings")
        void nullValuesEmpty() {
            String result = FormatTemplateEngine.apply(
                    "{player}|{channel}|{channel_name}|{message}",
                    null, null, null, null);

            assertThat(result).isEqualTo("|||");
        }

        @Test
        @DisplayName("null template returns empty string")
        void nullTemplate() {
            assertThat(FormatTemplateEngine.apply(null, "a", "b", "c", "d"))
                    .isEqualTo("");
        }

        @Test
        @DisplayName("unknown placeholders remain")
        void unknownRemain() {
            String result = FormatTemplateEngine.apply(
                    "{player} {unknown}",
                    "Steve", "g", "G", "m");

            assertThat(result).isEqualTo("Steve {unknown}");
        }
    }

    @Nested
    @DisplayName("apply(template, player, channel, channelName, message, extras)")
    class ApplyWithExtras {

        @Test
        @DisplayName("applies well-known and extras")
        void wellKnownAndExtras() {
            Map<String, String> extras = Map.of(
                    "display_name", "SteveTheGreat",
                    "world", "overworld");

            String result = FormatTemplateEngine.apply(
                    "{display_name} ({player}) in {world} #{channel}: {message}",
                    "Steve", "trade", "Trade", "selling diamonds", extras);

            assertThat(result).isEqualTo(
                    "SteveTheGreat (Steve) in overworld #trade: selling diamonds");
        }

        @Test
        @DisplayName("extras override well-known keys on clash")
        void extrasOverride() {
            Map<String, String> extras = Map.of("player", "Overridden");

            String result = FormatTemplateEngine.apply(
                    "{player}: {message}",
                    "Original", "c", "C", "hi", extras);

            assertThat(result).isEqualTo("Overridden: hi");
        }

        @Test
        @DisplayName("null extras is fine")
        void nullExtras() {
            String result = FormatTemplateEngine.apply(
                    "{player}: {message}",
                    "Steve", "c", "C", "hi", null);

            assertThat(result).isEqualTo("Steve: hi");
        }

        @Test
        @DisplayName("null values in extras become empty")
        void nullExtraValues() {
            Map<String, String> extras = new HashMap<>();
            extras.put("world", null);

            String result = FormatTemplateEngine.apply(
                    "{player}@{world}",
                    "Steve", "c", "C", "m", extras);

            assertThat(result).isEqualTo("Steve@");
        }
    }

    @Nested
    @DisplayName("standardPlaceholders")
    class StandardPlaceholders {

        @Test
        @DisplayName("returns unmodifiable map of four keys")
        void fourKeys() {
            Map<String, String> map = FormatTemplateEngine.standardPlaceholders(
                    "p", "c", "cn", "m");

            assertThat(map)
                    .containsEntry(FormatTemplateEngine.KEY_PLAYER, "p")
                    .containsEntry(FormatTemplateEngine.KEY_CHANNEL, "c")
                    .containsEntry(FormatTemplateEngine.KEY_CHANNEL_NAME, "cn")
                    .containsEntry(FormatTemplateEngine.KEY_MESSAGE, "m")
                    .hasSize(4);

            assertThatCode(() -> map.put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("nulls become empty strings")
        void nullsEmpty() {
            Map<String, String> map = FormatTemplateEngine.standardPlaceholders(
                    null, null, null, null);

            assertThat(map.values()).containsOnly("");
        }
    }

    @Nested
    @DisplayName("containsPlaceholder")
    class ContainsPlaceholder {

        @Test
        @DisplayName("detects present key")
        void present() {
            assertThat(FormatTemplateEngine.containsPlaceholder(STANDARD, "player")).isTrue();
            assertThat(FormatTemplateEngine.containsPlaceholder(STANDARD, "channel_name")).isTrue();
        }

        @Test
        @DisplayName("returns false for absent key")
        void absent() {
            assertThat(FormatTemplateEngine.containsPlaceholder(STANDARD, "world")).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("false for null/empty template or key")
        void nullEmpty(String blank) {
            assertThat(FormatTemplateEngine.containsPlaceholder(blank, "player")).isFalse();
            assertThat(FormatTemplateEngine.containsPlaceholder(STANDARD, blank)).isFalse();
        }

        @Test
        @DisplayName("does not match partial key names")
        void noPartial() {
            // "{channel}" should not satisfy key "channel_name"
            assertThat(FormatTemplateEngine.containsPlaceholder("{channel}", "channel_name"))
                    .isFalse();
            assertThat(FormatTemplateEngine.containsPlaceholder("{channel_name}", "channel"))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("real-world format strings")
    class RealWorld {

        @Test
        @DisplayName("typical channel format with colors left intact")
        void withColorCodesIntact() {
            String format = "&7[&b{channel_name}&7] &f{player}&7: &r{message}";

            String result = FormatTemplateEngine.apply(
                    format, "Steve", "global", "Global", "Hello &aworld");

            assertThat(result).isEqualTo("&7[&bGlobal&7] &fSteve&7: &rHello &aworld");
        }

        @Test
        @DisplayName("hex color codes in template are left intact")
        void hexIntact() {
            String format = "&#FF5555{player}&r: {message}";

            String result = FormatTemplateEngine.apply(
                    format, "Alex", "g", "G", "hi");

            assertThat(result).isEqualTo("&#FF5555Alex&r: hi");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "",
                "no placeholders here",
                "{{{player}}}",
                "{ player }",
                "player"
        })
        @DisplayName("edge templates do not throw")
        void edgeTemplates(String template) {
            assertThatCode(() -> FormatTemplateEngine.apply(
                    template, "p", "c", "cn", "m"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("triple-brace keeps outer braces")
        void tripleBrace() {
            // "{player}" is the token; outer braces remain
            String result = FormatTemplateEngine.apply(
                    "{{{player}}}", "Steve", "c", "cn", "m");
            assertThat(result).isEqualTo("{{Steve}}");
        }
    }
}
