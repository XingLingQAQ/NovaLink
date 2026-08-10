package com.nova.chat.client.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ChatModeDescriptions}, covering HYBRID/REPLACE
 * description mapping and null rejection.
 */
@DisplayName("ChatModeDescriptions")
class ChatModeDescriptionsTest {

    @Test
    @DisplayName("HYBRID maps to the hybrid description")
    void hybridDescription() {
        assertThat(ChatModeDescriptions.describe(ChatMode.HYBRID))
                .isEqualTo(ChatModeDescriptions.HYBRID_DESCRIPTION);
    }

    @Test
    @DisplayName("REPLACE maps to the replace description")
    void replaceDescription() {
        assertThat(ChatModeDescriptions.describe(ChatMode.REPLACE))
                .isEqualTo(ChatModeDescriptions.REPLACE_DESCRIPTION);
    }

    @Test
    @DisplayName("descriptions are non-blank and distinct")
    void descriptionsAreNonBlankAndDistinct() {
        String hybrid = ChatModeDescriptions.HYBRID_DESCRIPTION;
        String replace = ChatModeDescriptions.REPLACE_DESCRIPTION;

        assertThat(hybrid).isNotBlank();
        assertThat(replace).isNotBlank();
        assertThat(hybrid).isNotEqualTo(replace);
    }

    @Test
    @DisplayName("describe(null) throws")
    void nullThrows() {
        assertThatThrownBy(() -> ChatModeDescriptions.describe(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }

    @Test
    @DisplayName("covers both enum values")
    void coversAllModes() {
        for (ChatMode mode : ChatMode.values()) {
            assertThat(ChatModeDescriptions.describe(mode)).isNotBlank();
            assertThat(ChatModeDescriptions.modeName(mode)).isNotBlank();
        }
    }

    @Test
    @DisplayName("HYBRID modeName is 混合模式")
    void hybridModeName() {
        assertThat(ChatModeDescriptions.modeName(ChatMode.HYBRID))
                .isEqualTo(ChatModeDescriptions.HYBRID_MODE_NAME)
                .isEqualTo("混合模式");
    }

    @Test
    @DisplayName("REPLACE modeName is 频道模式")
    void replaceModeName() {
        assertThat(ChatModeDescriptions.modeName(ChatMode.REPLACE))
                .isEqualTo(ChatModeDescriptions.REPLACE_MODE_NAME)
                .isEqualTo("频道模式");
    }

    @Test
    @DisplayName("modeName(null) throws")
    void modeNameNullThrows() {
        assertThatThrownBy(() -> ChatModeDescriptions.modeName(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }
}
