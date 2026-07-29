package com.nova.chat.common.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive unit tests for {@link PlatformType}.
 * Ensures every supported Minecraft client platform has a unique stable wire id.
 */
@DisplayName("PlatformType")
class PlatformTypeTest {

    @Nested
    @DisplayName("Identity and uniqueness")
    class Identity {

        @Test
        @DisplayName("all platform IDs are unique")
        void allIdsAreUnique() {
            Set<Integer> ids = new HashSet<>();
            for (PlatformType type : PlatformType.values()) {
                assertThat(ids.add(type.getId()))
                        .as("Duplicate platform id for %s: %d", type, type.getId())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("ids form a contiguous range starting at 0")
        void idsAreContiguousFromZero() {
            int max = 0;
            for (PlatformType type : PlatformType.values()) {
                max = Math.max(max, type.getId());
            }
            assertThat(PlatformType.values()).hasSize(max + 1);
            for (int i = 0; i <= max; i++) {
                assertThat(PlatformType.isKnown(i)).as("missing id %d", i).isTrue();
            }
        }

        @ParameterizedTest
        @EnumSource(PlatformType.class)
        @DisplayName("fromId(getId(t)) == t for every platform")
        void roundTrip(PlatformType type) {
            assertThat(PlatformType.fromId(type.getId())).isEqualTo(type);
        }
    }

    @Nested
    @DisplayName("Known platforms (post 1.21 expansion)")
    class KnownPlatforms {

        @Test
        @DisplayName("Java Edition plugins and mods are present")
        void javaEditionPlatforms() {
            assertThat(PlatformType.BUKKIT.getId()).isZero();
            assertThat(PlatformType.VELOCITY.getId()).isEqualTo(1);
            assertThat(PlatformType.BUNGEECORD.getId()).isEqualTo(2);
            assertThat(PlatformType.FABRIC.getId()).isEqualTo(5);
            assertThat(PlatformType.NEOFORGE.getId()).isEqualTo(6);
            assertThat(PlatformType.QUILT.getId()).isEqualTo(7);
            assertThat(PlatformType.FORGE.getId()).isEqualTo(8);
            assertThat(PlatformType.MULTIPAPER.getId()).isEqualTo(12);
            assertThat(PlatformType.FOLIA.getId()).isEqualTo(13);
            assertThat(PlatformType.SPONGE.getId()).isEqualTo(14);
        }

        @Test
        @DisplayName("Bedrock platforms are present")
        void bedrockPlatforms() {
            assertThat(PlatformType.NUKKIT.getId()).isEqualTo(3);
            assertThat(PlatformType.LEVILAMINA.getId()).isEqualTo(4);
            assertThat(PlatformType.POCKETMINE.getId()).isEqualTo(9);
            assertThat(PlatformType.ENDSTONE.getId()).isEqualTo(10);
            assertThat(PlatformType.POWERNUKKITX.getId()).isEqualTo(11);
        }
    }

    @Nested
    @DisplayName("fromId edge cases")
    class FromId {

        @Test
        @DisplayName("unknown id throws IllegalArgumentException")
        void unknownIdThrows() {
            assertThatThrownBy(() -> PlatformType.fromId(255))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("255");
        }

        @Test
        @DisplayName("negative wire byte is normalized via & 0xFF before lookup")
        void negativeByteNormalized() {
            // Platform 5 (FABRIC) written as signed byte would still be 5
            assertThat(PlatformType.fromId(5)).isEqualTo(PlatformType.FABRIC);
            // 0x85 = 133 is unknown
            assertThat(PlatformType.isKnown(0x85)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 100, 200, 999})
        @DisplayName("isKnown returns false for unknown ids")
        void isKnownFalse(int id) {
            assertThat(PlatformType.isKnown(id)).isFalse();
        }

        @Test
        @DisplayName("isKnown returns true for every defined platform")
        void isKnownTrueForAll() {
            for (PlatformType type : PlatformType.values()) {
                assertThat(PlatformType.isKnown(type.getId())).isTrue();
            }
        }
    }
}
