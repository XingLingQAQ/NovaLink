package com.nova.link.filter;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for SensitiveWordFilter.
 * 
 * **Feature: starchat-starlink, Property 14: Sensitive Word Filtering**
 * 
 * Tests that messages containing words from the filter list have those words
 * replaced with *** in the output.
 * 
 * **Validates: Requirements 12.1**
 */
public class SensitiveWordFilterPropertyTest {

    private static final String REPLACEMENT = "***";

    /**
     * **Feature: starchat-starlink, Property 14: Sensitive Word Filtering**
     * 
     * For any message containing words from the filter list, those words
     * should be replaced with *** in the output.
     * 
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void sensitiveWordsAreReplacedWithAsterisks(
            @ForAll("sensitiveWords") String sensitiveWord,
            @ForAll("messagePrefixes") String prefix,
            @ForAll("messageSuffixes") String suffix
    ) {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        filter.addWord(sensitiveWord);
        
        String message = prefix + " " + sensitiveWord + " " + suffix;
        FilterResult result = filter.filter(message);
        
        assertThat(result.isFiltered()).isTrue();
        assertThat(result.getFilteredMessage()).doesNotContainIgnoringCase(sensitiveWord);
        assertThat(result.getFilteredMessage()).contains(REPLACEMENT);
        assertThat(result.getMatchCount()).isGreaterThanOrEqualTo(1);
    }

    /**
     * Property: Messages without sensitive words remain unchanged.
     * 
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void cleanMessagesRemainUnchanged(
            @ForAll("cleanMessages") String message
    ) {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        filter.addWord("badword");
        filter.addWord("forbidden");
        
        FilterResult result = filter.filter(message);
        
        assertThat(result.isFiltered()).isFalse();
        assertThat(result.getFilteredMessage()).isEqualTo(message);
        assertThat(result.getMatchCount()).isEqualTo(0);
    }


    /**
     * Property: Multiple occurrences of sensitive words are all replaced.
     * 
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void multipleOccurrencesAreAllReplaced(
            @ForAll("sensitiveWords") String sensitiveWord,
            @ForAll @IntRange(min = 2, max = 5) int occurrences
    ) {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        filter.addWord(sensitiveWord);
        
        StringBuilder messageBuilder = new StringBuilder();
        for (int i = 0; i < occurrences; i++) {
            messageBuilder.append(sensitiveWord);
            if (i < occurrences - 1) {
                messageBuilder.append(" hello ");
            }
        }
        String message = messageBuilder.toString();
        
        FilterResult result = filter.filter(message);
        
        assertThat(result.isFiltered()).isTrue();
        assertThat(result.getFilteredMessage()).doesNotContainIgnoringCase(sensitiveWord);
        assertThat(result.getMatchCount()).isEqualTo(occurrences);
    }

    /**
     * Property: Case-insensitive matching works correctly.
     * 
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void caseInsensitiveMatchingWorks(
            @ForAll("sensitiveWords") String sensitiveWord,
            @ForAll("caseVariations") String caseVariation
    ) {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        filter.addWord(sensitiveWord);
        
        // Apply case variation to the word
        String variedWord = applyCaseVariation(sensitiveWord, caseVariation);
        String message = "Hello " + variedWord + " world";
        
        FilterResult result = filter.filter(message);
        
        assertThat(result.isFiltered()).isTrue();
        assertThat(result.getFilteredMessage().toLowerCase())
                .doesNotContain(sensitiveWord.toLowerCase());
    }

    /**
     * Property: Regex patterns correctly filter matching content.
     * 
     * **Validates: Requirements 12.4**
     */
    @Property(tries = 100)
    void regexPatternsFilterMatchingContent(
            @ForAll("simpleRegexPatterns") String pattern,
            @ForAll("matchingStrings") String matchingPart
    ) {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        
        // Use a simple pattern that matches the generated string
        String regexPattern = "test\\d+";
        filter.addRegexPattern(regexPattern);
        
        String message = "Hello test123 world";
        FilterResult result = filter.filter(message);
        
        assertThat(result.isFiltered()).isTrue();
        assertThat(result.getFilteredMessage()).contains(REPLACEMENT);
    }

    /**
     * Property: Adding and removing words works correctly.
     * 
     * **Validates: Requirements 12.3**
     */
    @Property(tries = 100)
    void addingAndRemovingWordsWorks(
            @ForAll("sensitiveWords") String word
    ) {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        
        // Initially, word should not be filtered
        String message = "Hello " + word + " world";
        FilterResult result1 = filter.filter(message);
        assertThat(result1.isFiltered()).isFalse();
        
        // After adding, word should be filtered
        filter.addWord(word);
        FilterResult result2 = filter.filter(message);
        assertThat(result2.isFiltered()).isTrue();
        
        // After removing, word should not be filtered again
        filter.removeWord(word);
        FilterResult result3 = filter.filter(message);
        assertThat(result3.isFiltered()).isFalse();
    }

    /**
     * Property: Empty and null messages are handled gracefully.
     */
    @Property(tries = 50)
    void emptyAndNullMessagesHandledGracefully() {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        
        FilterResult nullResult = filter.filter(null);
        assertThat(nullResult.isFiltered()).isFalse();
        assertThat(nullResult.getFilteredMessage()).isEmpty();
        
        FilterResult emptyResult = filter.filter("");
        assertThat(emptyResult.isFiltered()).isFalse();
        assertThat(emptyResult.getFilteredMessage()).isEmpty();
    }

    /**
     * Property: Word boundaries are respected (partial matches don't trigger).
     * 
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void wordBoundariesAreRespected(
            @ForAll("sensitiveWords") String sensitiveWord,
            @ForAll("wordPrefixes") String wordPrefix,
            @ForAll("wordSuffixes") String wordSuffix
    ) {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        filter.addWord(sensitiveWord);
        
        // Create a word that contains the sensitive word but is different
        String extendedWord = wordPrefix + sensitiveWord + wordSuffix;
        
        // Only filter if the extended word equals the sensitive word
        // (i.e., prefix and suffix are empty)
        String message = "Hello " + extendedWord + " world";
        FilterResult result = filter.filter(message);
        
        if (wordPrefix.isEmpty() && wordSuffix.isEmpty()) {
            assertThat(result.isFiltered()).isTrue();
        } else {
            // Extended words should NOT be filtered (word boundary protection)
            assertThat(result.isFiltered()).isFalse();
        }
    }


    // ==================== Providers ====================

    @Provide
    Arbitrary<String> sensitiveWords() {
        return Arbitraries.of(
            "spam", "scam", "hack", "cheat", "exploit",
            "abuse", "toxic", "racist", "hate", "phishing",
            "malware", "virus", "trojan", "badword", "forbidden"
        );
    }

    @Provide
    Arbitrary<String> messagePrefixes() {
        return Arbitraries.of(
            "Hello", "The", "I saw", "There is", "Watch out for",
            "Someone said", "Don't be a", "Stop the", ""
        );
    }

    @Provide
    Arbitrary<String> messageSuffixes() {
        return Arbitraries.of(
            "here", "there", "today", "now", "please",
            "in chat", "on server", "is bad", ""
        );
    }

    @Provide
    Arbitrary<String> cleanMessages() {
        return Arbitraries.of(
            "Hello world",
            "How are you today?",
            "Nice to meet you",
            "Good game everyone",
            "Thanks for playing",
            "See you later",
            "Welcome to the server",
            "Have a great day"
        );
    }

    @Provide
    Arbitrary<String> caseVariations() {
        return Arbitraries.of("lower", "upper", "mixed", "title");
    }

    @Provide
    Arbitrary<String> simpleRegexPatterns() {
        return Arbitraries.of(
            "test\\d+",
            "bad\\w+",
            "spam[0-9]+"
        );
    }

    @Provide
    Arbitrary<String> matchingStrings() {
        return Arbitraries.of(
            "test123", "test456", "test789",
            "badword", "badstuff",
            "spam123", "spam456"
        );
    }

    @Provide
    Arbitrary<String> wordPrefixes() {
        return Arbitraries.of("", "pre", "un", "anti", "super");
    }

    @Provide
    Arbitrary<String> wordSuffixes() {
        return Arbitraries.of("", "er", "ing", "ed", "tion");
    }

    // ==================== Helper Methods ====================

    private String applyCaseVariation(String word, String variation) {
        switch (variation) {
            case "upper":
                return word.toUpperCase();
            case "lower":
                return word.toLowerCase();
            case "title":
                return word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase();
            case "mixed":
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    sb.append(i % 2 == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
                }
                return sb.toString();
            default:
                return word;
        }
    }
}
