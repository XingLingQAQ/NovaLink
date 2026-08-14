package com.nova.chat.client.itemdisplay;

import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ItemDisplayTokens}: token detection (aligned with the
 * Bedrock {@code /\[(item|i)\]/i} pattern), the per-player cooldown, and the
 * minimal itemJson / packet construction.
 */
@DisplayName("ItemDisplayTokens")
class ItemDisplayTokensTest {

    private static final UUID PLAYER_A = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID PLAYER_B = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Nested
    @DisplayName("hasItemToken")
    class HasItemToken {

        @Test
        @DisplayName("no token: plain text, null and empty all return false")
        void noToken() {
            assertThat(ItemDisplayTokens.hasItemToken("hello world")).isFalse();
            assertThat(ItemDisplayTokens.hasItemToken("")).isFalse();
            assertThat(ItemDisplayTokens.hasItemToken(null)).isFalse();
        }

        @Test
        @DisplayName("[item] and [i] match, case-insensitively (Bedrock pattern)")
        void tokenVariants() {
            assertThat(ItemDisplayTokens.hasItemToken("look [item]")).isTrue();
            assertThat(ItemDisplayTokens.hasItemToken("look [i]")).isTrue();
            assertThat(ItemDisplayTokens.hasItemToken("look [ITEM]")).isTrue();
            assertThat(ItemDisplayTokens.hasItemToken("look [I]")).isTrue();
            assertThat(ItemDisplayTokens.hasItemToken("look [Item] here")).isTrue();
        }

        @Test
        @DisplayName("multiple tokens in one message still match")
        void multipleTokens() {
            assertThat(ItemDisplayTokens.hasItemToken("[i] and [item] twice")).isTrue();
        }

        @Test
        @DisplayName("near-miss tokens do not match")
        void nearMisses() {
            assertThat(ItemDisplayTokens.hasItemToken("[items]")).isFalse();
            assertThat(ItemDisplayTokens.hasItemToken("[it em]")).isFalse();
            assertThat(ItemDisplayTokens.hasItemToken("item i")).isFalse();
            assertThat(ItemDisplayTokens.hasItemToken("[ii]")).isFalse();
        }
    }

    @Nested
    @DisplayName("tryAcquire (rate limit)")
    class TryAcquire {

        @Test
        @DisplayName("first send passes, second within the window is suppressed")
        void suppressesWithinWindow() {
            ItemDisplayTokens tokens = new ItemDisplayTokens();
            long t0 = 1_000_000L;

            assertThat(tokens.tryAcquire(PLAYER_A, t0)).isTrue();
            assertThat(tokens.tryAcquire(PLAYER_A, t0 + 1)).isFalse();
            assertThat(tokens.tryAcquire(PLAYER_A, t0 + ItemDisplayTokens.COOLDOWN_MS - 1)).isFalse();
        }

        @Test
        @DisplayName("after the window elapses the player may send again")
        void allowsAfterWindow() {
            ItemDisplayTokens tokens = new ItemDisplayTokens();
            long t0 = 1_000_000L;

            assertThat(tokens.tryAcquire(PLAYER_A, t0)).isTrue();
            assertThat(tokens.tryAcquire(PLAYER_A, t0 + ItemDisplayTokens.COOLDOWN_MS)).isTrue();
        }

        @Test
        @DisplayName("cooldowns are per-player: another player is not affected")
        void perPlayerIsolation() {
            ItemDisplayTokens tokens = new ItemDisplayTokens();
            long t0 = 1_000_000L;

            assertThat(tokens.tryAcquire(PLAYER_A, t0)).isTrue();
            assertThat(tokens.tryAcquire(PLAYER_B, t0 + 1)).isTrue();
        }

        @Test
        @DisplayName("clearCooldowns resets the window")
        void clearResets() {
            ItemDisplayTokens tokens = new ItemDisplayTokens();
            long t0 = 1_000_000L;

            assertThat(tokens.tryAcquire(PLAYER_A, t0)).isTrue();
            tokens.clearCooldowns();
            assertThat(tokens.tryAcquire(PLAYER_A, t0 + 1)).isTrue();
        }

        @Test
        @DisplayName("null player id throws")
        void nullPlayerThrows() {
            ItemDisplayTokens tokens = new ItemDisplayTokens();
            assertThatThrownBy(() -> tokens.tryAcquire(null, 0L))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("buildItemJson / buildPacket")
    class Payloads {

        @Test
        @DisplayName("builds id + count, omitting the name when absent")
        void minimalFields() {
            String json = ItemDisplayTokens.buildItemJson("minecraft:netherite_sword", 1, null);

            ItemDisplayInfo info = ItemDisplayInfo.fromJson(json);
            assertThat(info.getId()).isEqualTo("minecraft:netherite_sword");
            assertThat(info.getCount()).isEqualTo(1);
            assertThat(info.getName()).isNull();
            assertThat(json).doesNotContain("\"name\"");
        }

        @Test
        @DisplayName("includes the custom name when present")
        void customName() {
            String json = ItemDisplayTokens.buildItemJson("minecraft:diamond", 5, "&bLucky Diamond");

            ItemDisplayInfo info = ItemDisplayInfo.fromJson(json);
            assertThat(info.getName()).isEqualTo("&bLucky Diamond");
            assertThat(info.getCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("blank id maps to air and negative count clamps to 0")
        void blankIdAndNegativeCount() {
            String json = ItemDisplayTokens.buildItemJson("  ", -3, null);

            ItemDisplayInfo info = ItemDisplayInfo.fromJson(json);
            assertThat(info.getId()).isEqualTo(ItemDisplayTokens.EMPTY_ITEM_ID);
            assertThat(info.getCount()).isZero();
            assertThat(info.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("emptyHandJson renders as the empty item")
        void emptyHand() {
            ItemDisplayInfo info = ItemDisplayInfo.fromJson(ItemDisplayTokens.emptyHandJson());
            assertThat(info.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("buildPacket fills sender/channel/payload and a positive timestamp")
        void packetFields() {
            String json = ItemDisplayTokens.buildItemJson("minecraft:stone", 2, null);
            ItemDisplayPacket packet =
                    ItemDisplayTokens.buildPacket(PLAYER_A, "Steve", "global", json);

            assertThat(packet.getSenderId()).isEqualTo(PLAYER_A);
            assertThat(packet.getSenderName()).isEqualTo("Steve");
            assertThat(packet.getChannelId()).isEqualTo("global");
            assertThat(packet.getItemJson()).isEqualTo(json);
            assertThat(packet.getTimestamp()).isPositive();
        }
    }
}
