package com.nova.chat.proxy.lite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching rules for the lite sensitive-word filter.
 *
 * <p>These pin the semantics the backend already implements, because the two
 * modes must censor identically on the same network. The word-boundary case is
 * the one that matters most: a naive {@code String.replace} would rewrite
 * "class" to "cl***", which is the classic bug in this feature.
 */
@DisplayName("Lite word filter")
class LiteWordFilterTest {

    @Test
    @DisplayName("a configured word is replaced")
    void wordIsReplaced() {
        LiteWordFilter filter = LiteWordFilter.of(List.of("damn"), null);

        assertThat(filter.filter("that is damn rude")).isEqualTo("that is *** rude");
    }

    @Test
    @DisplayName("matching is case-insensitive")
    void matchingIsCaseInsensitive() {
        LiteWordFilter filter = LiteWordFilter.of(List.of("damn"), null);

        assertThat(filter.filter("DAMN it")).isEqualTo("*** it");
        assertThat(filter.filter("Damn it")).isEqualTo("*** it");
    }

    @Test
    @DisplayName("a word inside another word is left alone")
    void wordBoundariesAreRespected() {
        LiteWordFilter filter = LiteWordFilter.of(List.of("ass"), null);

        assertThat(filter.filter("a class assignment"))
                .as("word boundaries stop 'ass' matching inside 'class'")
                .isEqualTo("a class assignment");
        assertThat(filter.filter("you ass")).isEqualTo("you ***");
    }

    @Test
    @DisplayName("every occurrence is replaced, not just the first")
    void everyOccurrenceIsReplaced() {
        LiteWordFilter filter = LiteWordFilter.of(List.of("spam"), null);

        assertThat(filter.filter("spam and spam and spam"))
                .isEqualTo("*** and *** and ***");
    }

    @Test
    @DisplayName("several words match in one message")
    void severalWordsMatch() {
        LiteWordFilter filter = LiteWordFilter.of(List.of("alpha", "beta"), null);

        assertThat(filter.filter("alpha meets beta")).isEqualTo("*** meets ***");
    }

    @Test
    @DisplayName("a regex pattern is applied case-insensitively")
    void regexIsCaseInsensitive() {
        LiteWordFilter filter = LiteWordFilter.of(null, List.of("https?://\\S+"));

        assertThat(filter.filter("see HTTP://example.com now"))
                .isEqualTo("see *** now");
    }

    @Test
    @DisplayName("an invalid regex is skipped without disabling the others")
    void invalidRegexIsSkipped() {
        LiteWordFilter filter = LiteWordFilter.of(List.of("bad"), List.of("([unclosed", "\\d{3,}"));

        assertThat(filter.getPatterns()).as("the broken pattern is dropped")
                .containsExactly("\\d{3,}");
        assertThat(filter.filter("12345 bad")).isEqualTo("*** ***");
    }

    @Test
    @DisplayName("a replacement is not re-scanned by later rules")
    void replacementIsNotRescanned() {
        // "***" must never match a word rule, or repeated passes would grow text.
        LiteWordFilter filter = LiteWordFilter.of(List.of("star"), List.of("\\*+"));

        assertThat(filter.filter("star")).isEqualTo("***");
    }

    @Test
    @DisplayName("text with no match is returned unchanged")
    void cleanTextIsUntouched() {
        LiteWordFilter filter = LiteWordFilter.of(List.of("bad"), null);

        assertThat(filter.filter("perfectly fine")).isEqualTo("perfectly fine");
    }

    @Test
    @DisplayName("an empty filter is a no-op")
    void emptyFilterIsNoOp() {
        LiteWordFilter filter = LiteWordFilter.of(null, null);

        assertThat(filter.isEmpty()).isTrue();
        assertThat(filter.filter("anything at all")).isEqualTo("anything at all");
        assertThat(filter.countMatches("anything at all")).isZero();
    }

    @Test
    @DisplayName("a match closes over the configured words")
    void countMatchesReportsTheNumberOfReplacements() {
        LiteWordFilter filter = LiteWordFilter.of(List.of("one"), null);

        assertThat(filter.countMatches("one two one")).isEqualTo(2);
    }

    @Test
    @DisplayName("null and empty messages are handled")
    void nullAndEmptyMessagesAreHandled() {
        LiteWordFilter filter = LiteWordFilter.of(List.of("x"), null);

        assertThat(filter.filter(null)).isEmpty();
        assertThat(filter.filter("")).isEmpty();
        assertThat(filter.countMatches(null)).isZero();
    }
}
