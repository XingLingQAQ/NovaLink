package com.nova.chat.common.chat;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for ItemDisplayParser.
 * 
 * Tests the following property:
 * - Property 4: Item Display Tag Parsing
 */
class ItemDisplayParserPropertyTest {

    private final ItemDisplayParser parser = new ItemDisplayParser();

    // ==================== Property 4: Item Display Tag Parsing ====================

    /**
     * Property 4: Item Display Tag Parsing
     * 
     * For any message containing [item] or [i] tags, the ItemDisplayParser should
     * correctly identify all tags regardless of case or surrounding text.
     * 
     * **Feature: novachat-platform-extensions, Property 4: Item Display Tag Parsing**
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void itemTagParsingConsistency(
            @ForAll("messagesWithItemTags") MessageWithExpectedTags testCase) {
        
        List<String> parsed = parser.parseItemTags(testCase.message);
        
        // Should find exactly the expected number of tags
        assertThat(parsed).hasSize(testCase.expectedCount);
        
        // hasItemTag should return true when tags are present
        assertThat(parser.hasItemTag(testCase.message)).isTrue();
        
        // countItemTags should match
        assertThat(parser.countItemTags(testCase.message)).isEqualTo(testCase.expectedCount);
    }

    /**
     * Property 4: Item Display Tag Parsing - Case Insensitivity
     * 
     * Tags should be recognized regardless of case: [item], [ITEM], [Item], [i], [I].
     * 
     * **Feature: novachat-platform-extensions, Property 4: Item Display Tag Parsing**
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void itemTagParsingCaseInsensitive(@ForAll("itemTagVariants") String tag) {
        String message = "Check out my " + tag + " item!";
        
        assertThat(parser.hasItemTag(message)).isTrue();
        assertThat(parser.countItemTags(message)).isEqualTo(1);
        
        List<String> parsed = parser.parseItemTags(message);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0)).isEqualToIgnoringCase(tag);
    }


    /**
     * Property 4: Item Display Tag Parsing - Position Independence
     * 
     * Tags should be correctly identified regardless of their position in the message.
     * 
     * **Feature: novachat-platform-extensions, Property 4: Item Display Tag Parsing**
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void itemTagParsingPositionIndependence(
            @ForAll("itemTagVariants") String tag,
            @ForAll("textPrefixes") String prefix,
            @ForAll("textSuffixes") String suffix) {
        
        String message = prefix + tag + suffix;
        
        assertThat(parser.hasItemTag(message)).isTrue();
        assertThat(parser.countItemTags(message)).isEqualTo(1);
        
        List<ItemDisplayParser.ItemTagPosition> positions = parser.getItemTagPositions(message);
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getStart()).isEqualTo(prefix.length());
    }

    /**
     * Property 4: Item Display Tag Parsing - Multiple Tags
     * 
     * All tags in a message with multiple item tags should be found.
     * 
     * **Feature: novachat-platform-extensions, Property 4: Item Display Tag Parsing**
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void itemTagParsingMultipleTags(
            @ForAll("itemTagLists") List<String> tags) {
        
        String message = tags.stream()
            .collect(Collectors.joining(" and "));
        
        assertThat(parser.hasItemTag(message)).isTrue();
        assertThat(parser.countItemTags(message)).isEqualTo(tags.size());
        
        List<String> parsed = parser.parseItemTags(message);
        assertThat(parsed).hasSize(tags.size());
    }

    /**
     * Property 4: Item Display Tag Parsing - No Tags Returns Empty
     * 
     * Messages without item tags should return empty results.
     * 
     * **Feature: novachat-platform-extensions, Property 4: Item Display Tag Parsing**
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void itemTagParsingNoTagsReturnsEmpty(
            @ForAll("messagesWithoutItemTags") String message) {
        
        assertThat(parser.hasItemTag(message)).isFalse();
        assertThat(parser.countItemTags(message)).isEqualTo(0);
        assertThat(parser.parseItemTags(message)).isEmpty();
        assertThat(parser.getItemTagPositions(message)).isEmpty();
    }

    /**
     * Property 4: Item Display Tag Parsing - Null/Empty Safety
     * 
     * Parser should handle null and empty messages gracefully.
     * 
     * **Feature: novachat-platform-extensions, Property 4: Item Display Tag Parsing**
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void itemTagParsingNullSafety(@ForAll("nullOrEmptyMessages") String message) {
        assertThat(parser.hasItemTag(message)).isFalse();
        assertThat(parser.countItemTags(message)).isEqualTo(0);
        assertThat(parser.parseItemTags(message)).isNotNull().isEmpty();
        assertThat(parser.getItemTagPositions(message)).isNotNull().isEmpty();
    }

    /**
     * Property 4: Item Display Tag Parsing - Tag Replacement
     * 
     * Replacing tags should replace all occurrences correctly.
     * 
     * **Feature: novachat-platform-extensions, Property 4: Item Display Tag Parsing**
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void itemTagReplacement(
            @ForAll("itemTagLists") List<String> tags,
            @ForAll("replacementStrings") String replacement) {
        
        String message = tags.stream()
            .collect(Collectors.joining(" "));
        
        String replaced = parser.replaceItemTags(message, replacement);
        
        // After replacement, no item tags should remain
        assertThat(parser.hasItemTag(replaced)).isFalse();
        
        // The replacement should appear the correct number of times
        int expectedOccurrences = tags.size();
        int actualOccurrences = countOccurrences(replaced, replacement);
        assertThat(actualOccurrences).isEqualTo(expectedOccurrences);
    }

    /**
     * Property 4: Item Display Tag Parsing - Position Accuracy
     * 
     * Tag positions should accurately reflect where tags appear in the message.
     * 
     * **Feature: novachat-platform-extensions, Property 4: Item Display Tag Parsing**
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void itemTagPositionAccuracy(@ForAll("itemTagVariants") String tag) {
        String prefix = "Look at ";
        String suffix = " please";
        String message = prefix + tag + suffix;
        
        List<ItemDisplayParser.ItemTagPosition> positions = parser.getItemTagPositions(message);
        
        assertThat(positions).hasSize(1);
        ItemDisplayParser.ItemTagPosition pos = positions.get(0);
        
        // Verify position is correct
        assertThat(pos.getStart()).isEqualTo(prefix.length());
        assertThat(pos.getEnd()).isEqualTo(prefix.length() + tag.length());
        
        // Verify we can extract the tag using the positions
        String extracted = message.substring(pos.getStart(), pos.getEnd());
        assertThat(extracted).isEqualTo(tag);
    }

    /**
     * Property 4: Item Display Tag Parsing - Valid Tag Check
     * 
     * isValidItemTag should correctly identify valid item tags.
     * 
     * **Feature: novachat-platform-extensions, Property 4: Item Display Tag Parsing**
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void itemTagValidation(@ForAll("itemTagVariants") String validTag) {
        assertThat(parser.isValidItemTag(validTag)).isTrue();
    }

    /**
     * Property 4: Item Display Tag Parsing - Invalid Tag Check
     * 
     * isValidItemTag should reject invalid strings.
     * 
     * **Feature: novachat-platform-extensions, Property 4: Item Display Tag Parsing**
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void itemTagInvalidation(@ForAll("invalidTags") String invalidTag) {
        assertThat(parser.isValidItemTag(invalidTag)).isFalse();
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<MessageWithExpectedTags> messagesWithItemTags() {
        return itemTagLists().map(tags -> {
            String message = tags.stream()
                .collect(Collectors.joining(" shows "));
            return new MessageWithExpectedTags(message, tags.size());
        });
    }

    @Provide
    Arbitrary<String> itemTagVariants() {
        // All valid case variations of [item] and [i]
        return Arbitraries.of(
            "[item]", "[ITEM]", "[Item]", "[iTem]", "[itEM]",
            "[i]", "[I]"
        );
    }

    @Provide
    Arbitrary<List<String>> itemTagLists() {
        return itemTagVariants()
            .list()
            .ofMinSize(1)
            .ofMaxSize(5);
    }

    @Provide
    Arbitrary<String> textPrefixes() {
        return Arbitraries.oneOf(
            Arbitraries.just(""),
            Arbitraries.just(" "),
            Arbitraries.just("Check out my "),
            Arbitraries.just("Here is "),
            Arbitraries.just("Look at this: "),
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ')
                .ofMinLength(0)
                .ofMaxLength(20)
        );
    }

    @Provide
    Arbitrary<String> textSuffixes() {
        return Arbitraries.oneOf(
            Arbitraries.just(""),
            Arbitraries.just(" "),
            Arbitraries.just(" is cool"),
            Arbitraries.just("!"),
            Arbitraries.just(" - nice item"),
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '!', '.')
                .ofMinLength(0)
                .ofMaxLength(20)
        );
    }

    @Provide
    Arbitrary<String> messagesWithoutItemTags() {
        return Arbitraries.oneOf(
            Arbitraries.just("Hello world!"),
            Arbitraries.just("No tags here"),
            Arbitraries.just("[items] is not valid"),  // [items] is not [item]
            Arbitraries.just("[it] is not valid"),     // [it] is not [i]
            Arbitraries.just("item without brackets"),
            Arbitraries.just("[inventory]"),           // Different tag
            Arbitraries.just("Check [this] out"),
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '!', '.')
                .ofMinLength(1)
                .ofMaxLength(50)
                .filter(s -> !parser.hasItemTag(s))
        );
    }

    @Provide
    Arbitrary<String> nullOrEmptyMessages() {
        return Arbitraries.oneOf(
            Arbitraries.just((String) null),
            Arbitraries.just(""),
            Arbitraries.just("   ")
        );
    }

    @Provide
    Arbitrary<String> replacementStrings() {
        return Arbitraries.oneOf(
            Arbitraries.just("[Diamond Sword]"),
            Arbitraries.just("[Iron Pickaxe]"),
            Arbitraries.just("ITEM_PLACEHOLDER"),
            Arbitraries.just("***"),
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .ofMinLength(1)
                .ofMaxLength(20)
        );
    }

    @Provide
    Arbitrary<String> invalidTags() {
        return Arbitraries.oneOf(
            Arbitraries.just(""),
            Arbitraries.just((String) null),
            Arbitraries.just("item"),           // No brackets
            Arbitraries.just("[items]"),        // Wrong word
            Arbitraries.just("[it]"),           // Wrong word
            Arbitraries.just("[item"),          // Missing closing bracket
            Arbitraries.just("item]"),          // Missing opening bracket
            Arbitraries.just("[]"),             // Empty brackets
            Arbitraries.just("[inventory]"),    // Different tag
            Arbitraries.just("hello [item]"),   // Contains valid tag but isn't just the tag
            Arbitraries.just("[item] world")    // Contains valid tag but isn't just the tag
        );
    }

    // ==================== Helper Methods ====================

    private int countOccurrences(String str, String sub) {
        if (str == null || sub == null || sub.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    // ==================== Helper Classes ====================

    static class MessageWithExpectedTags {
        final String message;
        final int expectedCount;

        MessageWithExpectedTags(String message, int expectedCount) {
            this.message = message;
            this.expectedCount = expectedCount;
        }
    }
}
