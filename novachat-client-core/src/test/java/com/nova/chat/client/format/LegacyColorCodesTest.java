package com.nova.chat.client.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("LegacyColorCodes")
class LegacyColorCodesTest {

    @Nested
    @DisplayName("toAmpersandX")
    class ToAmpersandX {

        @Test
        @DisplayName("converts &#RRGGBB to &x&R&R&G&G&B&B (lowercase)")
        void convertsHashHex() {
            assertThat(LegacyColorCodes.toAmpersandX("&#FF5500"))
                    .isEqualTo("&x&f&f&5&5&0&0");
        }

        @Test
        @DisplayName("handles lowercase and mixed-case hex")
        void mixedCase() {
            assertThat(LegacyColorCodes.toAmpersandX("&#aAbBcC"))
                    .isEqualTo("&x&a&a&b&b&c&c");
        }

        @Test
        @DisplayName("converts multiple hex codes in one string")
        void multiple() {
            assertThat(LegacyColorCodes.toAmpersandX("&#FF0000red &#00FF00green"))
                    .isEqualTo("&x&f&f&0&0&0&0red &x&0&0&f&f&0&0green");
        }

        @Test
        @DisplayName("leaves non-hex text unchanged")
        void leavesOtherText() {
            assertThat(LegacyColorCodes.toAmpersandX("&aHello {player}"))
                    .isEqualTo("&aHello {player}");
        }

        @Test
        @DisplayName("does not touch incomplete hex")
        void incompleteHex() {
            assertThat(LegacyColorCodes.toAmpersandX("&#FFF")).isEqualTo("&#FFF");
            assertThat(LegacyColorCodes.toAmpersandX("&#GGGGGG")).isEqualTo("&#GGGGGG");
            assertThat(LegacyColorCodes.toAmpersandX("&#12345")).isEqualTo("&#12345");
        }

        @Test
        @DisplayName("does not re-expand already expanded &x form")
        void alreadyExpanded() {
            String expanded = "&x&f&f&0&0&0&0";
            assertThat(LegacyColorCodes.toAmpersandX(expanded)).isEqualTo(expanded);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null/empty passthrough")
        void nullEmpty(String input) {
            assertThat(LegacyColorCodes.toAmpersandX(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("adjacent hex codes")
        void adjacent() {
            assertThat(LegacyColorCodes.toAmpersandX("&#AABBCC&#DDEEFF"))
                    .isEqualTo("&x&a&a&b&b&c&c&x&d&d&e&e&f&f");
        }
    }

    @Nested
    @DisplayName("toSectionX")
    class ToSectionX {

        @Test
        @DisplayName("converts &#RRGGBB to §x§R§R§G§G§B§B")
        void converts() {
            assertThat(LegacyColorCodes.toSectionX("&#FF5500"))
                    .isEqualTo("§x§f§f§5§5§0§0");
        }

        @Test
        @DisplayName("preserves surrounding text and legacy codes")
        void preservesSurrounding() {
            assertThat(LegacyColorCodes.toSectionX("&7[&#00AAFFinfo&7]"))
                    .isEqualTo("&7[§x§0§0§a§a§f§finfo&7]");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null/empty passthrough")
        void nullEmpty(String input) {
            assertThat(LegacyColorCodes.toSectionX(input)).isEqualTo(input);
        }
    }

    @Nested
    @DisplayName("ampersandToSection")
    class AmpersandToSection {

        @Test
        @DisplayName("expands hash hex then converts to section form")
        void hashAndLegacy() {
            assertThat(LegacyColorCodes.ampersandToSection("&#FF0000&lBold"))
                    .isEqualTo("§x§f§f§0§0§0§0§lBold");
        }

        @Test
        @DisplayName("converts simple legacy codes")
        void simpleLegacy() {
            assertThat(LegacyColorCodes.ampersandToSection("&aGreen &cRed &rReset"))
                    .isEqualTo("§aGreen §cRed §rReset");
        }

        @Test
        @DisplayName("converts already-expanded &x form")
        void expandedX() {
            assertThat(LegacyColorCodes.ampersandToSection("&x&f&f&f&f&f&f"))
                    .isEqualTo("§x§f§f§f§f§f§f");
        }

        @Test
        @DisplayName("leaves bare ampersand alone")
        void bareAmpersand() {
            assertThat(LegacyColorCodes.ampersandToSection("A & B"))
                    .isEqualTo("A & B");
        }

        @Test
        @DisplayName("handles format codes k-o and r")
        void formatCodes() {
            assertThat(LegacyColorCodes.ampersandToSection("&k&l&m&n&o&r"))
                    .isEqualTo("§k§l§m§n§o§r");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null/empty passthrough")
        void nullEmpty(String input) {
            assertThat(LegacyColorCodes.ampersandToSection(input)).isEqualTo(input);
        }
    }

    @Nested
    @DisplayName("strip")
    class Strip {

        @Test
        @DisplayName("strips hash hex")
        void stripHash() {
            assertThat(LegacyColorCodes.strip("&#FF0000Hello")).isEqualTo("Hello");
        }

        @Test
        @DisplayName("strips ampersand-x form")
        void stripAmpX() {
            assertThat(LegacyColorCodes.strip("&x&f&f&0&0&0&0Hello")).isEqualTo("Hello");
        }

        @Test
        @DisplayName("strips section-x form")
        void stripSecX() {
            assertThat(LegacyColorCodes.strip("§x§f§f§0§0§0§0Hello")).isEqualTo("Hello");
        }

        @Test
        @DisplayName("strips simple legacy codes")
        void stripLegacy() {
            assertThat(LegacyColorCodes.strip("&aHi &lthere&r!")).isEqualTo("Hi there!");
            assertThat(LegacyColorCodes.strip("§aHi §lthere§r!")).isEqualTo("Hi there!");
        }

        @Test
        @DisplayName("strips mixed forms")
        void stripMixed() {
            assertThat(LegacyColorCodes.strip("&#AABBCC&aHello &x&d&d&e&e&f&fWorld§r!"))
                    .isEqualTo("Hello World!");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null/empty passthrough")
        void nullEmpty(String input) {
            assertThat(LegacyColorCodes.strip(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("plain text unchanged")
        void plain() {
            assertThat(LegacyColorCodes.strip("no colors here")).isEqualTo("no colors here");
        }
    }

    @Nested
    @DisplayName("integration with FormatTemplateEngine")
    class WithTemplateEngine {

        @Test
        @DisplayName("template first, then color expand")
        void pipeline() {
            String format = "&#55FFFF[{channel_name}] &f{player}&7: &r{message}";
            String filled = FormatTemplateEngine.apply(
                    format, "Steve", "global", "Global", "Hello");
            String colors = LegacyColorCodes.toAmpersandX(filled);

            assertThat(colors).isEqualTo(
                    "&x&5&5&f&f&f&f[Global] &fSteve&7: &rHello");
        }

        @Test
        @DisplayName("full ampersand-to-section pipeline")
        void fullPipeline() {
            String format = "&#FF5555{player}&r: {message}";
            String filled = FormatTemplateEngine.apply(format, "Alex", "g", "G", "hi");
            String section = LegacyColorCodes.ampersandToSection(filled);

            assertThat(section).isEqualTo("§x§f§f§5§5§5§5Alex§r: hi");
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @ParameterizedTest
        @ValueSource(strings = {
                "&#",
                "&#ZZZZZZ",
                "&x",
                "&x&f",
                "§",
                "&&&&",
                "&#12345g",
                "text with $1 and \\ backslash"
        })
        @DisplayName("never throws on edge inputs")
        void neverThrows(String input) {
            assertThatCode(() -> {
                LegacyColorCodes.toAmpersandX(input);
                LegacyColorCodes.toSectionX(input);
                LegacyColorCodes.ampersandToSection(input);
                LegacyColorCodes.strip(input);
            }).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @CsvSource({
                "&#000000, &x&0&0&0&0&0&0",
                "&#FFFFFF, &x&f&f&f&f&f&f",
                "&#abcdef, &x&a&b&c&d&e&f",
                "&#ABCDEF, &x&a&b&c&d&e&f"
        })
        @DisplayName("known hex vectors")
        void knownVectors(String input, String expected) {
            assertThat(LegacyColorCodes.toAmpersandX(input)).isEqualTo(expected);
        }
    }
}
