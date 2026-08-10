package com.nova.chat.common.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for item display tags in chat messages.
 * 
 * Supports:
 * - [item] - displays the player's held item
 * - [i] - shorthand for [item]
 * 
 * Tags are case-insensitive.
 */
public class ItemDisplayParser {

    /**
     * Pattern for matching item display tags.
     * Matches [item] or [i] (case-insensitive).
     */
    private static final Pattern ITEM_PATTERN = Pattern.compile(
        "\\[(item|i)\\]",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Checks if the message contains any item display tags.
     * 
     * @param message the raw message to check
     * @return true if [item] or [i] is present (case-insensitive)
     */
    public boolean hasItemTag(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        return ITEM_PATTERN.matcher(message).find();
    }

    /**
     * Counts the number of item display tags in the message.
     * 
     * @param message the raw message to parse
     * @return the count of item tags
     */
    public int countItemTags(String message) {
        if (message == null || message.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        Matcher matcher = ITEM_PATTERN.matcher(message);
        while (matcher.find()) {
            count++;
        }
        return count;
    }


    /**
     * Gets all item tag positions in the message.
     * Useful for replacing tags with item displays.
     * 
     * @param message the raw message
     * @return list of ItemTagPosition objects
     */
    public List<ItemTagPosition> getItemTagPositions(String message) {
        if (message == null || message.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<ItemTagPosition> positions = new ArrayList<>();
        Matcher matcher = ITEM_PATTERN.matcher(message);
        
        while (matcher.find()) {
            positions.add(new ItemTagPosition(
                matcher.start(),
                matcher.end(),
                matcher.group(0),
                matcher.group(1)
            ));
        }
        
        return positions;
    }

    /**
     * Parses all item tags from a message.
     * 
     * @param message the raw message to parse
     * @return list of matched tag strings (e.g., "[item]", "[i]", "[ITEM]")
     */
    public List<String> parseItemTags(String message) {
        if (message == null || message.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<String> tags = new ArrayList<>();
        Matcher matcher = ITEM_PATTERN.matcher(message);
        
        while (matcher.find()) {
            tags.add(matcher.group(0));
        }
        
        return tags;
    }

    /**
     * Replaces all item tags in the message with a replacement string.
     * 
     * @param message the raw message
     * @param replacement the string to replace item tags with
     * @return the message with all item tags replaced
     */
    public String replaceItemTags(String message, String replacement) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        Objects.requireNonNull(replacement, "replacement cannot be null");
        
        return ITEM_PATTERN.matcher(message).replaceAll(Matcher.quoteReplacement(replacement));
    }

    /**
     * Checks if a specific string is a valid item tag.
     * 
     * @param tag the string to check
     * @return true if the string is a valid item tag
     */
    public boolean isValidItemTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            return false;
        }
        return ITEM_PATTERN.matcher(tag).matches();
    }

    /**
     * Represents the position of an item tag in a message.
     */
    public static class ItemTagPosition {
        private final int start;
        private final int end;
        private final String fullTag;
        private final String tagType;

        public ItemTagPosition(int start, int end, String fullTag, String tagType) {
            this.start = start;
            this.end = end;
            this.fullTag = fullTag;
            this.tagType = tagType;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        /**
         * Gets the full matched tag (e.g., "[item]", "[I]").
         */
        public String getFullTag() {
            return fullTag;
        }

        /**
         * Gets the tag type without brackets (e.g., "item", "i").
         */
        public String getTagType() {
            return tagType;
        }

        /**
         * Checks if this is the short form [i] tag.
         */
        public boolean isShortForm() {
            return tagType.equalsIgnoreCase("i");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemTagPosition that = (ItemTagPosition) o;
            return start == that.start && 
                   end == that.end && 
                   Objects.equals(fullTag, that.fullTag) &&
                   Objects.equals(tagType, that.tagType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end, fullTag, tagType);
        }

        @Override
        public String toString() {
            return "ItemTagPosition{start=" + start + ", end=" + end + 
                   ", fullTag='" + fullTag + "', tagType='" + tagType + "'}";
        }
    }
}
