package com.nova.chat.common.chat;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for MentionParser.
 * 
 * Tests the following properties:
 * - Property 1: Mention Parsing Consistency
 * - Property 3: @all Expansion Correctness
 */
class MentionParserPropertyTest {

    private final MentionParser parser = new MentionParser();

    // ==================== Property 1: Mention Parsing Consistency ====================

    /**
     * Property 1: Mention Parsing Consistency
     * 
     * For any message string containing @mentions, the MentionParser should correctly
     * identify all mentioned player names regardless of message content or position.
     * 
     * **Feature: novachat-platform-extensions, Property 1: Mention Parsing Consistency**
     * **Validates: Requirements 11.1**
     */
    @Property(tries = 100)
    void mentionParsingConsistency(
            @ForAll("messagesWithMentions") MessageWithExpectedMentions testCase) {
        
        List<String> parsed = parser.parseMentions(testCase.message);
        
        // All expected mentions should be found
        assertThat(parsed).containsExactlyInAnyOrderElementsOf(testCase.expectedMentions);
    }

    /**
     * Property 1: Mention Parsing Consistency - Position Independence
     * 
     * Mentions should be correctly identified regardless of their position in the message.
     * The mention must be properly delimited (followed by non-word char or end of string).
     * 
     * **Feature: novachat-platform-extensions, Property 1: Mention Parsing Consistency**
     * **Validates: Requirements 11.1**
     */
    @Property(tries = 100)
    void mentionParsingPositionIndependence(
            @ForAll("validPlayerNames") String playerName,
            @ForAll("nonWordPrefixes") String prefix,
            @ForAll("nonWordSuffixes") String suffix) {
        
        String message = prefix + "@" + playerName + suffix;
        List<String> parsed = parser.parseMentions(message);
        
        // The player name should be found regardless of surrounding text
        assertThat(parsed).contains(playerName);
    }

    /**
     * Property 1: Mention Parsing Consistency - Multiple Mentions
     * 
     * All mentions in a message with multiple @mentions should be found.
     * 
     * **Feature: novachat-platform-extensions, Property 1: Mention Parsing Consistency**
     * **Validates: Requirements 11.1**
     */
    @Property(tries = 100)
    void mentionParsingMultipleMentions(
            @ForAll("validPlayerNameLists") List<String> playerNames) {
        
        // Build message with all mentions
        String message = playerNames.stream()
            .map(name -> "@" + name)
            .collect(Collectors.joining(" says hello to "));
        
        List<String> parsed = parser.parseMentions(message);
        
        // All player names should be found
        assertThat(parsed).containsExactlyInAnyOrderElementsOf(playerNames);
    }

    /**
     * Property 1: Mention Parsing Consistency - Count Correctness
     * 
     * The number of parsed mentions should equal the number of @mentions in the message.
     * 
     * **Feature: novachat-platform-extensions, Property 1: Mention Parsing Consistency**
     * **Validates: Requirements 11.1**
     */
    @Property(tries = 100)
    void mentionCountCorrectness(
            @ForAll("validPlayerNameLists") List<String> playerNames) {
        
        // Build message with all mentions
        String message = playerNames.stream()
            .map(name -> "@" + name)
            .collect(Collectors.joining(" "));
        
        int count = parser.countMentions(message);
        
        // Count should match the number of mentions we added
        assertThat(count).isEqualTo(playerNames.size());
    }

    /**
     * Property 1: Mention Parsing Consistency - Empty/Null Safety
     * 
     * Parser should handle null and empty messages gracefully.
     * 
     * **Feature: novachat-platform-extensions, Property 1: Mention Parsing Consistency**
     * **Validates: Requirements 11.1**
     */
    @Property(tries = 100)
    void mentionParsingNullSafety(@ForAll("nullOrEmptyMessages") String message) {
        List<String> parsed = parser.parseMentions(message);
        
        // Should return empty list, not null
        assertThat(parsed).isNotNull();
        assertThat(parsed).isEmpty();
    }

    /**
     * Property 1: Mention Parsing Consistency - @all Exclusion
     * 
     * The parseMentions method should exclude @all from the player list.
     * 
     * **Feature: novachat-platform-extensions, Property 1: Mention Parsing Consistency**
     * **Validates: Requirements 11.1**
     */
    @Property(tries = 100)
    void mentionParsingExcludesAll(
            @ForAll("validPlayerNames") String playerName) {
        
        String message = "@all @" + playerName + " @ALL @All";
        List<String> parsed = parser.parseMentions(message);
        
        // Should only contain the player name, not "all" variants
        assertThat(parsed).containsExactly(playerName);
        assertThat(parsed).doesNotContain("all", "ALL", "All");
    }

    // ==================== Property 3: @all Expansion Correctness ====================

    /**
     * Property 3: @all Expansion Correctness
     * 
     * For any channel with N members, @all should expand to exactly N-1 mention
     * notifications (excluding the sender).
     * 
     * **Feature: novachat-platform-extensions, Property 3: @all Expansion Correctness**
     * **Validates: Requirements 11.4**
     */
    @Property(tries = 100)
    void allExpansionCorrectness(
            @ForAll("channelMemberLists") List<String> members,
            @ForAll @IntRange(min = 0, max = 9) int senderIndex) {
        
        if (members.isEmpty()) {
            return; // Skip empty member lists
        }
        
        // Pick a sender from the members
        int actualIndex = senderIndex % members.size();
        String sender = members.get(actualIndex);
        
        String message = "Hello @all!";
        List<String> expanded = parser.expandAllMention(message, members, sender);
        
        // Should expand to all members except the sender
        assertThat(expanded).hasSize(members.size() - 1);
        assertThat(expanded).doesNotContain(sender);
        
        // All other members should be included
        for (String member : members) {
            if (!member.equals(sender)) {
                assertThat(expanded).contains(member);
            }
        }
    }

    /**
     * Property 3: @all Expansion Correctness - No @all Returns Empty
     * 
     * If the message doesn't contain @all, expansion should return empty list.
     * 
     * **Feature: novachat-platform-extensions, Property 3: @all Expansion Correctness**
     * **Validates: Requirements 11.4**
     */
    @Property(tries = 100)
    void allExpansionNoAllReturnsEmpty(
            @ForAll("messagesWithoutAll") String message,
            @ForAll("channelMemberLists") List<String> members) {
        
        if (members.isEmpty()) {
            return;
        }
        
        String sender = members.get(0);
        List<String> expanded = parser.expandAllMention(message, members, sender);
        
        // Should return empty list when no @all present
        assertThat(expanded).isEmpty();
    }

    /**
     * Property 3: @all Expansion Correctness - Case Insensitivity
     * 
     * @all, @ALL, @All should all be recognized.
     * 
     * **Feature: novachat-platform-extensions, Property 3: @all Expansion Correctness**
     * **Validates: Requirements 11.4**
     */
    @Property(tries = 100)
    void allExpansionCaseInsensitive(
            @ForAll("allVariants") String allVariant,
            @ForAll("channelMemberLists") List<String> members) {
        
        if (members.isEmpty()) {
            return;
        }
        
        String sender = members.get(0);
        String message = "Hello " + allVariant + "!";
        
        // hasAllMention should detect all case variants
        assertThat(parser.hasAllMention(message)).isTrue();
        
        List<String> expanded = parser.expandAllMention(message, members, sender);
        
        // Should expand correctly regardless of case
        assertThat(expanded).hasSize(members.size() - 1);
    }

    /**
     * Property 3: @all Expansion Correctness - Sender Always Excluded
     * 
     * The sender should never be in the expanded list.
     * 
     * **Feature: novachat-platform-extensions, Property 3: @all Expansion Correctness**
     * **Validates: Requirements 11.4**
     */
    @Property(tries = 100)
    void allExpansionSenderExcluded(
            @ForAll("validPlayerNames") String sender) {
        
        List<String> members = Arrays.asList(sender, "Player1", "Player2", "Player3");
        String message = "@all";
        
        List<String> expanded = parser.expandAllMention(message, members, sender);
        
        // Sender should never be in the list
        assertThat(expanded).doesNotContain(sender);
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<MessageWithExpectedMentions> messagesWithMentions() {
        return validPlayerNameLists().map(names -> {
            String message = names.stream()
                .map(name -> "@" + name)
                .collect(Collectors.joining(" hello "));
            return new MessageWithExpectedMentions(message, names);
        });
    }

    @Provide
    Arbitrary<String> validPlayerNames() {
        // Valid Minecraft usernames: 3-16 chars, alphanumeric and underscore
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withCharRange('0', '9')
            .withChars('_')
            .ofMinLength(3)
            .ofMaxLength(16)
            .filter(s -> !s.equalsIgnoreCase("all")); // Exclude "all" as it's special
    }

    @Provide
    Arbitrary<List<String>> validPlayerNameLists() {
        return validPlayerNames()
            .list()
            .ofMinSize(1)
            .ofMaxSize(5)
            .map(list -> list.stream().distinct().collect(Collectors.toList()));
    }

    @Provide
    Arbitrary<String> messageContexts() {
        return Arbitraries.oneOf(
            Arbitraries.just(""),
            Arbitraries.just(" "),
            Arbitraries.just("Hello "),
            Arbitraries.just(" says hi"),
            Arbitraries.just(", welcome!"),
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', ',', '!')
                .ofMinLength(0)
                .ofMaxLength(20)
        );
    }

    @Provide
    Arbitrary<String> nonWordPrefixes() {
        // Prefixes that don't end with word characters (so @ starts a new token)
        return Arbitraries.oneOf(
            Arbitraries.just(""),
            Arbitraries.just(" "),
            Arbitraries.just("Hello "),
            Arbitraries.just("Say hi to "),
            Arbitraries.just(", "),
            Arbitraries.just("! "),
            Arbitraries.just(": ")
        );
    }

    @Provide
    Arbitrary<String> nonWordSuffixes() {
        // Suffixes that don't start with word characters (so mention ends properly)
        return Arbitraries.oneOf(
            Arbitraries.just(""),
            Arbitraries.just(" "),
            Arbitraries.just(" says hi"),
            Arbitraries.just(", welcome!"),
            Arbitraries.just("!"),
            Arbitraries.just("."),
            Arbitraries.just("?"),
            Arbitraries.just(" is here")
        );
    }

    @Provide
    Arbitrary<String> nullOrEmptyMessages() {
        return Arbitraries.oneOf(
            Arbitraries.just((String) null),
            Arbitraries.just(""),
            Arbitraries.just("   "),
            Arbitraries.just("no mentions here")
        );
    }

    @Provide
    Arbitrary<List<String>> channelMemberLists() {
        return validPlayerNames()
            .list()
            .ofMinSize(1)
            .ofMaxSize(10)
            .map(list -> list.stream().distinct().collect(Collectors.toList()));
    }

    @Provide
    Arbitrary<String> messagesWithoutAll() {
        return Arbitraries.oneOf(
            Arbitraries.just("Hello everyone!"),
            Arbitraries.just("@Player1 @Player2"),
            Arbitraries.just("No mentions here"),
            validPlayerNames().map(name -> "@" + name + " hello")
        );
    }

    @Provide
    Arbitrary<String> allVariants() {
        return Arbitraries.of("@all", "@ALL", "@All", "@aLl", "@alL");
    }

    // ==================== Helper Classes ====================

    static class MessageWithExpectedMentions {
        final String message;
        final List<String> expectedMentions;

        MessageWithExpectedMentions(String message, List<String> expectedMentions) {
            this.message = message;
            this.expectedMentions = expectedMentions;
        }
    }
}
