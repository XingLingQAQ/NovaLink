package com.nova.chat.common.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MentionNotifier#highlightMentions(String, String)}.
 *
 * <p>UX-DESIGN §4.2: chat rendering wraps {@code @name} mentions in a highlight
 * color (e.g. {@code &e} yellow) before the platform translates color codes.
 */
@DisplayName("MentionNotifier highlight")
class MentionNotifierHighlightTest {

    @Nested
    @DisplayName("highlightMentions")
    class Highlight {

        @Test
        @DisplayName("null message returns null")
        void nullMessage() {
            assertThat(MentionNotifier.highlightMentions(null, "&e")).isNull();
        }

        @Test
        @DisplayName("empty color returns message unchanged")
        void emptyColorNoOp() {
            String msg = "hello @Steve world";
            assertThat(MentionNotifier.highlightMentions(msg, "")).isEqualTo(msg);
            assertThat(MentionNotifier.highlightMentions(msg, null)).isEqualTo(msg);
        }

        @Test
        @DisplayName("single mention is wrapped with the color prefix")
        void singleMention() {
            String result = MentionNotifier.highlightMentions("hello @Steve world", "&e");
            assertThat(result).isEqualTo("hello &e@Steve world");
        }

        @Test
        @DisplayName("multiple mentions each get the prefix")
        void multipleMentions() {
            String result = MentionNotifier.highlightMentions("@Alex hi @Bob", "&e");
            assertThat(result).isEqualTo("&e@Alex hi &e@Bob");
        }

        @Test
        @DisplayName("@all is NOT highlighted")
        void allNotHighlighted() {
            String msg = "hey @all listen";
            assertThat(MentionNotifier.highlightMentions(msg, "&e")).isEqualTo(msg);
        }

        @Test
        @DisplayName("@all alongside a real mention only highlights the real one")
        void allAndRealMention() {
            String result = MentionNotifier.highlightMentions("@all and @Steve", "&e");
            assertThat(result).isEqualTo("@all and &e@Steve");
        }

        @Test
        @DisplayName("mention at end of message is wrapped")
        void mentionAtEnd() {
            String result = MentionNotifier.highlightMentions("ping @Steve", "&e");
            assertThat(result).isEqualTo("ping &e@Steve");
        }

        @Test
        @DisplayName("mention at start of message is wrapped")
        void mentionAtStart() {
            String result = MentionNotifier.highlightMentions("@Steve ping", "&e");
            assertThat(result).isEqualTo("&e@Steve ping");
        }

        @Test
        @DisplayName("message with no mentions is unchanged")
        void noMentions() {
            String msg = "just a normal message";
            assertThat(MentionNotifier.highlightMentions(msg, "&e")).isEqualTo(msg);
        }

        @Test
        @DisplayName("short token under 3 chars is not highlighted (invalid name)")
        void shortTokenNotHighlighted() {
            String msg = "x @ab y";
            assertThat(MentionNotifier.highlightMentions(msg, "&e")).isEqualTo(msg);
        }
    }
}
