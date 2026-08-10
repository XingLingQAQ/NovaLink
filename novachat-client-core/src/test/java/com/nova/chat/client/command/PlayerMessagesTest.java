package com.nova.chat.client.command;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.ChatModeDescriptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PlayerMessages}, asserting exact colored rendering of
 * join/leave acknowledgements, the current-channel bar, chat-toggle text, and
 * argument validation.
 */
@DisplayName("PlayerMessages")
class PlayerMessagesTest {

    @Test
    @DisplayName("joining renders exact colored ack")
    void joiningExact() {
        assertThat(PlayerMessages.joining("global"))
                .isEqualTo("正在加入频道 &eglobal&7...");
    }

    @Test
    @DisplayName("joined renders exact colored confirmation")
    void joinedExact() {
        assertThat(PlayerMessages.joined("trade"))
                .isEqualTo("已加入频道 &etrade");
    }

    @Test
    @DisplayName("leaving renders exact colored ack")
    void leavingExact() {
        assertThat(PlayerMessages.leaving("staff"))
                .isEqualTo("正在离开频道 &estaff&7...");
    }

    @Test
    @DisplayName("left renders exact colored confirmation with default fallback")
    void leftExact() {
        assertThat(PlayerMessages.left("staff", "global"))
                .isEqualTo("已离开频道 &estaff&7，已切换到默认频道: &eglobal");
    }

    @Test
    @DisplayName("currentChannelBar reuses ChatModeDescriptions.modeName for HYBRID")
    void currentChannelBarHybrid() {
        assertThat(PlayerMessages.currentChannelBar("global", ChatMode.HYBRID))
                .isEqualTo("&7当前频道：&bglobal &7（" + ChatModeDescriptions.HYBRID_MODE_NAME + "）");
        assertThat(PlayerMessages.currentChannelBar("global", ChatMode.HYBRID))
                .contains(ChatModeDescriptions.modeName(ChatMode.HYBRID));
    }

    @Test
    @DisplayName("currentChannelBar reuses ChatModeDescriptions.modeName for REPLACE")
    void currentChannelBarReplace() {
        assertThat(PlayerMessages.currentChannelBar("trade", ChatMode.REPLACE))
                .isEqualTo("&7当前频道：&btrade &7（" + ChatModeDescriptions.REPLACE_MODE_NAME + "）");
        assertThat(PlayerMessages.currentChannelBar("trade", ChatMode.REPLACE))
                .contains(ChatModeDescriptions.modeName(ChatMode.REPLACE));
    }

    @Test
    @DisplayName("chatOn / chatOff are exact plain text")
    void chatToggleExact() {
        assertThat(PlayerMessages.chatOn()).isEqualTo("聊天已开启");
        assertThat(PlayerMessages.chatOff()).isEqualTo("聊天已关闭");
    }

    @Test
    @DisplayName("null/blank channel arguments throw")
    void channelValidation() {
        assertThatThrownBy(() -> PlayerMessages.joining(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> PlayerMessages.joined("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> PlayerMessages.leaving(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> PlayerMessages.left("a", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultChannel");
        assertThatThrownBy(() -> PlayerMessages.currentChannelBar(null, ChatMode.HYBRID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    @DisplayName("currentChannelBar(null mode) throws via modeName")
    void nullModeThrows() {
        assertThatThrownBy(() -> PlayerMessages.currentChannelBar("global", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }
}
