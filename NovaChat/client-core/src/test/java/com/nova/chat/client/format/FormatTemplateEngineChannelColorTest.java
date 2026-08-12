package com.nova.chat.client.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FormatTemplateEngine} focused on the
 * {@code {channel_color}} placeholder, covering auto-resolution from
 * {@code {channel}} and explicit overrides.
 */
@DisplayName("FormatTemplateEngine {channel_color}")
class FormatTemplateEngineChannelColorTest {

    @Nested
    @DisplayName("apply(template, map)")
    class ApplyMap {

        @Test
        @DisplayName("{channel_color} is auto-resolved from {channel} when absent from map")
        void autoResolvedFromChannel() {
            String template = "[{channel_color}{channel_name}] {player}: {message}";
            Map<String, String> map = new LinkedHashMap<>();
            map.put(FormatTemplateEngine.KEY_CHANNEL, "global");
            map.put(FormatTemplateEngine.KEY_CHANNEL_NAME, "Global");
            map.put(FormatTemplateEngine.KEY_PLAYER, "Steve");
            map.put(FormatTemplateEngine.KEY_MESSAGE, "hi");

            String result = FormatTemplateEngine.apply(template, map);

            String expectedColor = ChannelColorResolver.resolveColor("global");
            assertThat(result).isEqualTo("[" + expectedColor + "Global] Steve: hi");
        }

        @Test
        @DisplayName("explicit channel_color in map overrides auto-resolution")
        void explicitOverridesAuto() {
            String template = "[{channel_color}{channel_name}]";
            Map<String, String> map = new LinkedHashMap<>();
            map.put(FormatTemplateEngine.KEY_CHANNEL, "global");
            map.put(FormatTemplateEngine.KEY_CHANNEL_COLOR, "&e");
            map.put(FormatTemplateEngine.KEY_CHANNEL_NAME, "Global");

            String result = FormatTemplateEngine.apply(template, map);

            assertThat(result).isEqualTo("[&eGlobal]");
        }

        @Test
        @DisplayName("auto-resolution uses the {channel} value actually in the map")
        void usesChannelValueFromMap() {
            String template = "[{channel_color}{channel_name}]";
            Map<String, String> map = new LinkedHashMap<>();
            map.put(FormatTemplateEngine.KEY_CHANNEL, "pvp");
            map.put(FormatTemplateEngine.KEY_CHANNEL_NAME, "PVP");

            String result = FormatTemplateEngine.apply(template, map);

            assertThat(result).isEqualTo(
                    "[" + ChannelColorResolver.resolveColor("pvp") + "PVP]");
        }

        @Test
        @DisplayName("null {channel} value resolves to default gray")
        void nullChannelResolvesDefault() {
            String template = "[{channel_color}x]";
            Map<String, String> map = new LinkedHashMap<>();
            map.put(FormatTemplateEngine.KEY_CHANNEL, null);

            String result = FormatTemplateEngine.apply(template, map);

            assertThat(result).isEqualTo("[" + ChannelColorResolver.DEFAULT_COLOR + "x]");
        }

        @Test
        @DisplayName("missing {channel} key resolves to default gray")
        void missingChannelKeyResolvesDefault() {
            String template = "[{channel_color}x]";
            Map<String, String> map = new LinkedHashMap<>();
            map.put(FormatTemplateEngine.KEY_PLAYER, "Steve");

            String result = FormatTemplateEngine.apply(template, map);

            assertThat(result).isEqualTo("[" + ChannelColorResolver.DEFAULT_COLOR + "x]");
        }

        @Test
        @DisplayName("template without {channel_color} is unaffected")
        void noChannelColorUnaffected() {
            String template = "[{channel_name}] {player}: {message}";
            Map<String, String> map = new LinkedHashMap<>();
            map.put(FormatTemplateEngine.KEY_CHANNEL, "global");
            map.put(FormatTemplateEngine.KEY_CHANNEL_NAME, "Global");
            map.put(FormatTemplateEngine.KEY_PLAYER, "Steve");
            map.put(FormatTemplateEngine.KEY_MESSAGE, "hi");

            String result = FormatTemplateEngine.apply(template, map);

            assertThat(result).isEqualTo("[Global] Steve: hi");
        }

        @Test
        @DisplayName("multiple {channel_color} occurrences all resolve")
        void multipleOccurrences() {
            String template = "{channel_color}{channel_name}{channel_color}";
            Map<String, String> map = new LinkedHashMap<>();
            map.put(FormatTemplateEngine.KEY_CHANNEL, "global");
            map.put(FormatTemplateEngine.KEY_CHANNEL_NAME, "Global");

            String result = FormatTemplateEngine.apply(template, map);

            String color = ChannelColorResolver.resolveColor("global");
            assertThat(result).isEqualTo(color + "Global" + color);
        }

        @Test
        @DisplayName("empty map with {channel_color} resolves default gray")
        void emptyMapResolvesDefault() {
            // empty map → apply returns template unchanged (no placeholders to iterate).
            // But {channel_color} auto-resolution path runs before iteration, so it should
            // still inject the color even with an empty caller map.
            String template = "[{channel_color}x]";
            Map<String, String> map = new LinkedHashMap<>();

            String result = FormatTemplateEngine.apply(template, map);

            // channel is null → default gray
            assertThat(result).isEqualTo("[" + ChannelColorResolver.DEFAULT_COLOR + "x]");
        }
    }

    @Nested
    @DisplayName("apply(template, player, channel, channelName, message)")
    class ApplyWellKnown {

        @Test
        @DisplayName("well-known overload resolves {channel_color} from channel arg")
        void wellKnownResolves() {
            String template = "[{channel_color}{channel_name}] {player}: {message}";

            String result = FormatTemplateEngine.apply(
                    template, "Steve", "global", "Global", "hi");

            assertThat(result).isEqualTo(
                    "[" + ChannelColorResolver.resolveColor("global") + "Global] Steve: hi");
        }

        @Test
        @DisplayName("null channel arg resolves to default gray")
        void nullChannelDefault() {
            String template = "[{channel_color}{channel_name}]";
            String result = FormatTemplateEngine.apply(template, "Steve", null, "Global", "hi");
            assertThat(result).isEqualTo("[" + ChannelColorResolver.DEFAULT_COLOR + "Global]");
        }

        @Test
        @DisplayName("extras can override channel_color")
        void extrasOverrideChannelColor() {
            String template = "[{channel_color}{channel_name}]";
            Map<String, String> extras = new LinkedHashMap<>();
            extras.put(FormatTemplateEngine.KEY_CHANNEL_COLOR, "&d");

            String result = FormatTemplateEngine.apply(
                    template, "Steve", "global", "Global", "hi", extras);

            assertThat(result).isEqualTo("[&dGlobal]");
        }
    }
}
