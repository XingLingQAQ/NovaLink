package com.nova.chat.mod.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PlatformType}, covering the {@code toCommon()} mapping
 * onto the shared protocol enum and the display-name accessor.
 */
@DisplayName("PlatformType")
class PlatformTypeTest {

    @Test
    @DisplayName("FABRIC maps to common FABRIC")
    void fabricMapsToCommon() {
        assertThat(PlatformType.FABRIC.toCommon())
                .isEqualTo(com.nova.chat.common.protocol.PlatformType.FABRIC);
    }

    @Test
    @DisplayName("NEOFORGE maps to common NEOFORGE")
    void neoforgeMapsToCommon() {
        assertThat(PlatformType.NEOFORGE.toCommon())
                .isEqualTo(com.nova.chat.common.protocol.PlatformType.NEOFORGE);
    }

    @Test
    @DisplayName("QUILT maps to common QUILT")
    void quiltMapsToCommon() {
        assertThat(PlatformType.QUILT.toCommon())
                .isEqualTo(com.nova.chat.common.protocol.PlatformType.QUILT);
    }

    @Test
    @DisplayName("FORGE maps to common FORGE")
    void forgeMapsToCommon() {
        assertThat(PlatformType.FORGE.toCommon())
                .isEqualTo(com.nova.chat.common.protocol.PlatformType.FORGE);
    }

    @Test
    @DisplayName("every mod PlatformType maps to a distinct common PlatformType")
    void allMappingsAreDistinct() {
        java.util.Set<com.nova.chat.common.protocol.PlatformType> seen = new java.util.HashSet<>();
        for (PlatformType type : PlatformType.values()) {
            com.nova.chat.common.protocol.PlatformType common = type.toCommon();
            assertThat(common).as("%s maps to non-null common type", type).isNotNull();
            assertThat(seen.add(common))
                    .as("%s maps to a distinct common type (no collisions)", type)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("getDisplayName returns the constructor display name")
    void displayNameIsConstructorValue() {
        assertThat(PlatformType.FABRIC.getDisplayName()).isEqualTo("Fabric");
        assertThat(PlatformType.NEOFORGE.getDisplayName()).isEqualTo("NeoForge");
        assertThat(PlatformType.QUILT.getDisplayName()).isEqualTo("Quilt");
        assertThat(PlatformType.FORGE.getDisplayName()).isEqualTo("Forge");
    }

    @Test
    @DisplayName("mod FABRIC and common FABRIC share the same wire id")
    void fabricWireIdMatchesCommon() {
        // The handshake uses toCommon(); ensure the common enum's id is the
        // FABRIC id (5) so wire framing stays stable.
        assertThat(PlatformType.FABRIC.toCommon().getId()).isEqualTo(5);
        assertThat(PlatformType.NEOFORGE.toCommon().getId()).isEqualTo(6);
        assertThat(PlatformType.QUILT.toCommon().getId()).isEqualTo(7);
        assertThat(PlatformType.FORGE.toCommon().getId()).isEqualTo(8);
    }
}
