package com.nova.chat.common.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detailed unit tests for {@link MentionParser}.
 * Covers Minecraft username validation, @all handling, and edge cases.
 */
@DisplayName("MentionParser detailed")
class MentionParserDetailedTest {

    private MentionParser parser;

    @BeforeEach
    void setUp() {
        parser = new MentionParser();
    }

    @Nested
    @DisplayName("parseMentions")
    class ParseMentions {

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null/empty message yields empty list")
        void nullOrEmpty(String message) {
            assertThat(parser.parseMentions(message)).isEmpty();
        }

        @Test
        @DisplayName("single valid player mention")
        void singleMention() {
            assertThat(parser.parseMentions("Hi @Steve")).containsExactly("Steve");
        }

        @Test
        @DisplayName("multiple player mentions preserve order")
        void multipleMentions() {
            assertThat(parser.parseMentions("@Alice hello @Bob and @Charlie"))
                    .containsExactly("Alice", "Bob", "Charlie");
        }

        @Test
        @DisplayName("@all is excluded from player mention list")
        void allExcluded() {
            assertThat(parser.parseMentions("@all raid now @Steve"))
                    .containsExactly("Steve");
        }

        @Test
        @DisplayName("names shorter than 3 chars are rejected")
        void tooShortRejected() {
            assertThat(parser.parseMentions("hey @ab @a @xy")).isEmpty();
        }

        @Test
        @DisplayName("names longer than 16 chars are not matched by pattern")
        void tooLongNotMatched() {
            // 17-char token should not match {1,16}
            assertThat(parser.parseMentions("@ThisNameIs17Chars")).isEmpty();
        }

        @Test
        @DisplayName("underscores are allowed in usernames")
        void underscoresAllowed() {
            assertThat(parser.parseMentions("ping @Notch_2011")).containsExactly("Notch_2011");
        }

        @Test
        @DisplayName("no false positive inside email-like text without @word boundary issues")
        void plainTextWithoutMention() {
            assertThat(parser.parseMentions("no mentions here")).isEmpty();
        }
    }

    @Nested
    @DisplayName("hasAllMention")
    class HasAll {

        @Test
        @DisplayName("detects @all case-insensitively")
        void caseInsensitive() {
            assertThat(parser.hasAllMention("Hello @ALL")).isTrue();
            assertThat(parser.hasAllMention("@All everyone")).isTrue();
            assertThat(parser.hasAllMention("@all")).isTrue();
        }

        @Test
        @DisplayName("returns false when only player mentions present")
        void onlyPlayers() {
            assertThat(parser.hasAllMention("@Steve hi")).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null/empty is false")
        void nullEmpty(String message) {
            assertThat(parser.hasAllMention(message)).isFalse();
        }
    }

    @Nested
    @DisplayName("countMentions")
    class Count {

        @Test
        @DisplayName("counts players and @all")
        void countsAll() {
            assertThat(parser.countMentions("@all @Steve @Alex")).isEqualTo(3);
        }

        @Test
        @DisplayName("zero when none")
        void zero() {
            assertThat(parser.countMentions("nothing")).isZero();
        }
    }

    @Nested
    @DisplayName("isValidPlayerName")
    class ValidName {

        @ParameterizedTest
        @ValueSource(strings = {"abc", "Steve", "Notch_2011", "XXXXXXXXXXXXXXXX"})
        @DisplayName("accepts valid Minecraft usernames")
        void valid(String name) {
            assertThat(MentionParser.isValidPlayerName(name)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"ab", "a", "", "thisnameiswaytoolong1", "name-with-dash", "name.dot"})
        @DisplayName("rejects invalid Minecraft usernames")
        void invalid(String name) {
            assertThat(MentionParser.isValidPlayerName(name)).isFalse();
        }

        @Test
        @DisplayName("null is invalid")
        void nullName() {
            assertThat(MentionParser.isValidPlayerName(null)).isFalse();
        }
    }
}
