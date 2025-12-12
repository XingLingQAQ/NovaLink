package com.nova.chat.velocity.chat;

import org.junit.jupiter.api.Test;
import org.assertj.core.api.Assertions;

/**
 * Unit tests for ChatMode enum in Velocity module.
 */
class ChatModeTest {

    @Test
    void shouldHaveTwoModes() {
        Assertions.assertThat(ChatMode.values()).hasSize(2);
    }

    @Test
    void shouldContainHybridMode() {
        Assertions.assertThat(ChatMode.valueOf("HYBRID")).isEqualTo(ChatMode.HYBRID);
    }

    @Test
    void shouldContainReplaceMode() {
        Assertions.assertThat(ChatMode.valueOf("REPLACE")).isEqualTo(ChatMode.REPLACE);
    }

    @Test
    void hybridAndReplaceShouldBeDifferent() {
        Assertions.assertThat(ChatMode.HYBRID).isNotEqualTo(ChatMode.REPLACE);
    }

    @Test
    void ordinalsShouldBeConsistent() {
        Assertions.assertThat(ChatMode.HYBRID.ordinal()).isEqualTo(0);
        Assertions.assertThat(ChatMode.REPLACE.ordinal()).isEqualTo(1);
    }
}
