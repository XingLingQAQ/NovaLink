package com.nova.chat.client.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlayerChannelState and ChatMode")
class PlayerChannelStateTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("initializes with default channel as active and joined")
        void initializes() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);

            assertThat(state.getPlayerId()).isEqualTo(PLAYER);
            assertThat(state.getActiveChannel()).isEqualTo("global");
            assertThat(state.getJoinedChannels()).containsExactly("global");
            assertThat(state.getJoinedChannelCount()).isEqualTo(1);
            assertThat(state.isJoined("global")).isTrue();
            assertThat(state.getChatMode()).isEqualTo(ChatMode.HYBRID);
            assertThat(state.isModeOverridden()).isFalse();
            assertThat(state.isForwardingEnabled()).isTrue();
            assertThat(state.getCurrentServer()).isNull();
        }

        @Test
        @DisplayName("rejects null playerId / defaultMode and blank channel")
        void rejectsInvalidArgs() {
            assertThatThrownBy(() -> new PlayerChannelState(null, "global", ChatMode.HYBRID))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("playerId");
            assertThatThrownBy(() -> new PlayerChannelState(PLAYER, "global", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("defaultMode");
            assertThatThrownBy(() -> new PlayerChannelState(PLAYER, "", ChatMode.HYBRID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("defaultChannel");
            assertThatThrownBy(() -> new PlayerChannelState(PLAYER, "   ", ChatMode.HYBRID))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PlayerChannelState(PLAYER, null, ChatMode.HYBRID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("channel membership")
    class Membership {

        @Test
        @DisplayName("setActiveChannel updates active and joins if needed")
        void setActiveJoins() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);

            state.setActiveChannel("trade");

            assertThat(state.getActiveChannel()).isEqualTo("trade");
            assertThat(state.getJoinedChannels()).containsExactly("global", "trade");
            assertThat(state.isJoined("trade")).isTrue();
        }

        @Test
        @DisplayName("setActiveChannel rejects blank")
        void setActiveRejectsBlank() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            assertThatThrownBy(() -> state.setActiveChannel(" "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> state.setActiveChannel(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("setActiveChannelIfJoined changes active without creating membership")
        void setActiveOnlyIfJoined() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            state.joinChannel("trade");
            state.setActiveChannel("trade");
            state.leaveChannel("global");

            assertThat(state.setActiveChannelIfJoined("global")).isFalse();
            assertThat(state.getActiveChannel()).isEqualTo("trade");
            assertThat(state.isJoined("global")).isFalse();

            assertThat(state.setActiveChannelIfJoined("trade")).isTrue();
            assertThat(state.getActiveChannel()).isEqualTo("trade");
            assertThat(state.getJoinedChannels()).containsExactly("trade");
        }

        @Test
        @DisplayName("setActiveChannelIfJoined rejects blank")
        void setActiveIfJoinedRejectsBlank() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            assertThatThrownBy(() -> state.setActiveChannelIfJoined(" "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> state.setActiveChannelIfJoined(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("joinChannel is idempotent and does not change active")
        void joinIdempotent() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);

            assertThat(state.joinChannel("staff")).isTrue();
            assertThat(state.joinChannel("staff")).isFalse();
            assertThat(state.getActiveChannel()).isEqualTo("global");
            assertThat(state.getJoinedChannels()).containsExactly("global", "staff");
        }

        @Test
        @DisplayName("joinChannel rejects blank")
        void joinRejectsBlank() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            assertThatThrownBy(() -> state.joinChannel(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("leaveChannel removes membership and reassigns active when needed")
        void leaveReassignsActive() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            state.joinChannel("trade");
            state.setActiveChannel("trade");

            assertThat(state.leaveChannel("trade")).isTrue();
            assertThat(state.isJoined("trade")).isFalse();
            // falls back to first remaining (global)
            assertThat(state.getActiveChannel()).isEqualTo("global");
            assertThat(state.getJoinedChannels()).containsExactly("global");
        }

        @Test
        @DisplayName("leaving a non-active channel keeps active unchanged")
        void leaveNonActive() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            state.joinChannel("trade");

            assertThat(state.leaveChannel("trade")).isTrue();
            assertThat(state.getActiveChannel()).isEqualTo("global");
        }

        @Test
        @DisplayName("leaving the last channel sets active to null")
        void leaveLast() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);

            assertThat(state.leaveChannel("global")).isTrue();
            assertThat(state.getJoinedChannels()).isEmpty();
            assertThat(state.getActiveChannel()).isNull();
            assertThat(state.getJoinedChannelCount()).isZero();
        }

        @Test
        @DisplayName("leaveChannel returns false when not a member")
        void leaveUnknown() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            assertThat(state.leaveChannel("nope")).isFalse();
            assertThat(state.getActiveChannel()).isEqualTo("global");
        }

        @Test
        @DisplayName("copy preserves an empty membership with null active channel")
        void copyAfterLeavingLastChannel() {
            PlayerChannelState original = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            original.leaveChannel("global");

            PlayerChannelState copy = original.copy();

            assertThat(copy.getActiveChannel()).isNull();
            assertThat(copy.getJoinedChannels()).isEmpty();
            assertThat(copy.getPlayerId()).isEqualTo(PLAYER);
        }

        @Test
        @DisplayName("joinedChannels view is unmodifiable")
        void joinedViewUnmodifiable() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            Set<String> view = state.getJoinedChannels();
            assertThatThrownBy(() -> view.add("hack"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("isJoined is false for null")
        void isJoinedNull() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            assertThat(state.isJoined(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("chat mode")
    class Mode {

        @Test
        @DisplayName("toggleMode alternates and marks overridden")
        void toggleAlternates() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);

            assertThat(state.toggleMode()).isEqualTo(ChatMode.REPLACE);
            assertThat(state.isModeOverridden()).isTrue();
            assertThat(state.getChatMode()).isEqualTo(ChatMode.REPLACE);

            assertThat(state.toggleMode()).isEqualTo(ChatMode.HYBRID);
            assertThat(state.isModeOverridden()).isTrue();
        }

        @Test
        @DisplayName("toggle from REPLACE goes to HYBRID")
        void toggleFromReplace() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.REPLACE);
            assertThat(state.toggleMode()).isEqualTo(ChatMode.HYBRID);
        }

        @Test
        @DisplayName("setChatMode rejects null")
        void setModeNull() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            assertThatThrownBy(() -> state.setChatMode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("setChatMode does not implicitly mark overridden")
        void setModeDoesNotOverrideFlag() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            state.setChatMode(ChatMode.REPLACE);
            assertThat(state.getChatMode()).isEqualTo(ChatMode.REPLACE);
            assertThat(state.isModeOverridden()).isFalse();
        }

        @Test
        @DisplayName("resetMode restores default and clears override")
        void resetMode() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            state.toggleMode();
            assertThat(state.isModeOverridden()).isTrue();

            state.resetMode(ChatMode.HYBRID);
            assertThat(state.getChatMode()).isEqualTo(ChatMode.HYBRID);
            assertThat(state.isModeOverridden()).isFalse();
        }

        @Test
        @DisplayName("forwarding enabled is settable")
        void forwardingEnabled() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);

            state.setForwardingEnabled(false);

            assertThat(state.isForwardingEnabled()).isFalse();
        }

        @Test
        @DisplayName("copy preserves forwarding enabled with independent state")
        void copyPreservesIndependentForwardingEnabled() {
            PlayerChannelState original = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            original.setForwardingEnabled(false);

            PlayerChannelState copy = original.copy();

            assertThat(copy).isNotSameAs(original);
            assertThat(copy.isForwardingEnabled()).isFalse();
            copy.setForwardingEnabled(true);
            assertThat(copy.isForwardingEnabled()).isTrue();
            assertThat(original.isForwardingEnabled()).isFalse();
        }

        @Test
        @DisplayName("currentServer is settable")
        void currentServer() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            state.setCurrentServer("lobby-1");
            assertThat(state.getCurrentServer()).isEqualTo("lobby-1");
            state.setCurrentServer(null);
            assertThat(state.getCurrentServer()).isNull();
        }

        @Test
        @DisplayName("toString includes key fields")
        void toStringContainsFields() {
            PlayerChannelState state = new PlayerChannelState(PLAYER, "global", ChatMode.HYBRID);
            state.setCurrentServer("hub");
            assertThat(state.toString())
                    .contains(PLAYER.toString())
                    .contains("global")
                    .contains("HYBRID")
                    .contains("hub");
        }
    }

    @Nested
    @DisplayName("ChatMode")
    class ChatModeTests {

        @Test
        @DisplayName("has exactly two values with stable ordinals")
        void values() {
            assertThat(ChatMode.values()).containsExactly(ChatMode.HYBRID, ChatMode.REPLACE);
            assertThat(ChatMode.HYBRID.ordinal()).isZero();
            assertThat(ChatMode.REPLACE.ordinal()).isEqualTo(1);
        }

        @Test
        @DisplayName("toggled flips between modes")
        void toggled() {
            assertThat(ChatMode.HYBRID.toggled()).isEqualTo(ChatMode.REPLACE);
            assertThat(ChatMode.REPLACE.toggled()).isEqualTo(ChatMode.HYBRID);
            assertThat(ChatMode.HYBRID.toggled().toggled()).isEqualTo(ChatMode.HYBRID);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t"})
        @DisplayName("fromConfig blank/null defaults to HYBRID")
        void fromConfigBlank(String raw) {
            assertThat(ChatMode.fromConfig(raw)).isEqualTo(ChatMode.HYBRID);
        }

        @ParameterizedTest
        @CsvSource({
                "hybrid, HYBRID",
                "HYBRID, HYBRID",
                "vanilla, HYBRID",
                "false, HYBRID",
                "0, HYBRID",
                "off, HYBRID",
                "replace, REPLACE",
                "REPLACE, REPLACE",
                "replace_vanilla, REPLACE",
                "replace-vanilla, REPLACE",
                "true, REPLACE",
                "1, REPLACE",
                "yes, REPLACE",
                "on, REPLACE"
        })
        @DisplayName("fromConfig accepts common aliases")
        void fromConfigAliases(String raw, ChatMode expected) {
            assertThat(ChatMode.fromConfig(raw)).isEqualTo(expected);
        }

        @Test
        @DisplayName("fromConfig rejects unknown values")
        void fromConfigUnknown() {
            assertThatThrownBy(() -> ChatMode.fromConfig("something-else"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown chat mode");
        }
    }
}
