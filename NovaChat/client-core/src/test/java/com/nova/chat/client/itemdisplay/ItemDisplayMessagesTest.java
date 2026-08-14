package com.nova.chat.client.itemdisplay;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ItemDisplayMessages} and the underlying
 * {@link ItemDisplayInfo} parsing: exact zh_CN (default locale) rendering of
 * the item display chat line, empty-hand placeholder, lenient JSON handling
 * and the hover detail block.
 */
@DisplayName("ItemDisplayMessages")
class ItemDisplayMessagesTest {

    private Locale savedDefault;

    @BeforeEach
    void pinDefaultLocale() {
        savedDefault = I18n.getDefaultLocale();
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
    }

    @AfterEach
    void restoreDefaultLocale() {
        I18n.setDefaultLocale(savedDefault);
    }

    @Nested
    @DisplayName("formatLine")
    class FormatLine {

        @Test
        @DisplayName("single item: sender + prettified id, no count suffix")
        void singleItem() {
            String line = ItemDisplayMessages.formatLine(null, "Steve",
                    "{\"id\":\"minecraft:netherite_sword\",\"count\":1}");

            assertThat(line).isEqualTo("&7Steve &7展示了物品 &f[&bNetherite Sword&f]");
        }

        @Test
        @DisplayName("stacked item: count suffix is rendered")
        void stackedItem() {
            String line = ItemDisplayMessages.formatLine(null, "Alex",
                    "{\"id\":\"minecraft:diamond\",\"count\":32}");

            assertThat(line).isEqualTo("&7Alex &7展示了物品 &f[&bDiamond&f] &7x32");
        }

        @Test
        @DisplayName("custom name wins over the id")
        void customNameWins() {
            String line = ItemDisplayMessages.formatLine(null, "Alex",
                    "{\"id\":\"minecraft:diamond_sword\",\"count\":1,\"name\":\"Excalibur\"}");

            assertThat(line).isEqualTo("&7Alex &7展示了物品 &f[&bExcalibur&f]");
        }

        @Test
        @DisplayName("empty hand (air / zero count / blank payload) renders the empty placeholder")
        void emptyHand() {
            String expected = "&7Alex &7展示了物品 &f[&b&7&o空手&f]";

            assertThat(ItemDisplayMessages.formatLine(null, "Alex",
                    "{\"id\":\"minecraft:air\",\"count\":0}")).isEqualTo(expected);
            assertThat(ItemDisplayMessages.formatLine(null, "Alex", "")).isEqualTo(expected);
            assertThat(ItemDisplayMessages.formatLine(null, "Alex", null)).isEqualTo(expected);
        }

        @Test
        @DisplayName("malformed JSON falls back to the raw payload as the name")
        void malformedJsonFallsBack() {
            String line = ItemDisplayMessages.formatLine(null, "Alex", "not-json");

            assertThat(line).isEqualTo("&7Alex &7展示了物品 &f[&bnot-json&f]");
        }

        @Test
        @DisplayName("null sender renders as empty instead of throwing")
        void nullSender() {
            String line = ItemDisplayMessages.formatLine(null, null,
                    "{\"id\":\"minecraft:stone\",\"count\":1}");

            assertThat(line).isEqualTo("&7 &7展示了物品 &f[&bStone&f]");
        }

        @Test
        @DisplayName("en_US bundle has aligned keys and renders the English line")
        void englishLocale() {
            I18n.setDefaultLocale(LocaleResolver.EN_US);

            String line = ItemDisplayMessages.formatLine(null, "Steve",
                    "{\"id\":\"minecraft:diamond\",\"count\":2}");

            assertThat(line).isEqualTo("&7Steve &7showed an item &f[&bDiamond&f] &7x2");
        }
    }

    @Nested
    @DisplayName("formatHoverDetail")
    class FormatHoverDetail {

        @Test
        @DisplayName("renders name, id and count rows")
        void fullDetail() {
            String hover = ItemDisplayMessages.formatHoverDetail(null,
                    "{\"id\":\"minecraft:diamond\",\"count\":32}");

            assertThat(hover).isEqualTo("&fDiamond\n&7ID: &fminecraft:diamond\n&7数量: &f32");
        }

        @Test
        @DisplayName("empty item renders only the placeholder")
        void emptyDetail() {
            assertThat(ItemDisplayMessages.formatHoverDetail(null, null))
                    .isEqualTo("&7&o空手");
        }
    }

    @Nested
    @DisplayName("ItemDisplayInfo parsing")
    class InfoParsing {

        @Test
        @DisplayName("golden-sample payload parses exactly")
        void goldenPayload() {
            ItemDisplayInfo info = ItemDisplayInfo.fromJson(
                    "{\"id\":\"minecraft:netherite_sword\",\"count\":1}");

            assertThat(info.getId()).isEqualTo("minecraft:netherite_sword");
            assertThat(info.getCount()).isEqualTo(1);
            assertThat(info.getName()).isNull();
            assertThat(info.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("missing count defaults to 1")
        void missingCountDefaults() {
            ItemDisplayInfo info = ItemDisplayInfo.fromJson("{\"id\":\"minecraft:stone\"}");

            assertThat(info.getCount()).isEqualTo(1);
            assertThat(info.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("air id and non-positive count are empty (Bedrock is_empty semantics)")
        void emptySemantics() {
            assertThat(ItemDisplayInfo.fromJson("{\"id\":\"minecraft:air\",\"count\":1}").isEmpty()).isTrue();
            assertThat(ItemDisplayInfo.fromJson("{\"id\":\"AIR\",\"count\":1}").isEmpty()).isTrue();
            assertThat(ItemDisplayInfo.fromJson("{\"id\":\"minecraft:stone\",\"count\":0}").isEmpty()).isTrue();
            assertThat(ItemDisplayInfo.fromJson("{\"id\":\"minecraft:stone\",\"count\":1}").isEmpty()).isFalse();
        }

        @Test
        @DisplayName("prettifyId strips the namespace and capitalizes words")
        void prettify() {
            assertThat(ItemDisplayInfo.prettifyId("minecraft:netherite_sword")).isEqualTo("Netherite Sword");
            assertThat(ItemDisplayInfo.prettifyId("diamond")).isEqualTo("Diamond");
            assertThat(ItemDisplayInfo.prettifyId("")).isEqualTo("Unknown");
            assertThat(ItemDisplayInfo.prettifyId(null)).isEqualTo("Unknown");
        }
    }
}
