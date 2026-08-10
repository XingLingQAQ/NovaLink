package com.nova.chat.common.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for @mentions in chat messages.
 * 
 * Supports:
 * - @playername - mentions a specific player
 * - @all - mentions all players in the channel
 * 
 * Player names must be valid Minecraft usernames (alphanumeric and underscores, 3-16 chars).
 */
public class MentionParser {

    /**
     * Pattern for matching @mentions.
     * Matches @ followed by alphanumeric characters and underscores.
     * Also matches the special @all mention.
     * <p>
     * Capture group allows 1-16 for @all and short tokens; player mentions are
     * further validated against Minecraft username rules (3-16) in
     * {@link #isValidPlayerName(String)}.
     * <p>
     * Negative lookahead prevents partial matches of longer tokens
     * (e.g. {@code @ThisNameIs17Chars} must not match the first 16 chars).
     */
    private static final Pattern MENTION_PATTERN =
            Pattern.compile("@([A-Za-z0-9_]{1,16})(?![A-Za-z0-9_])");

    /**
     * Minecraft Java Edition username rules: 3-16 chars, [A-Za-z0-9_].
     */
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    /**
     * The special "all" mention keyword.
     */
    public static final String ALL_MENTION = "all";

    /**
     * Parses all @mentions from a message.
     *
     * @param message the raw message to parse
     * @return list of mentioned player names (excluding @all and invalid names), never null
     */
    public List<String> parseMentions(String message) {
        if (message == null || message.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> mentions = new ArrayList<>();
        Matcher matcher = MENTION_PATTERN.matcher(message);

        while (matcher.find()) {
            String mention = matcher.group(1);
            // Exclude @all from player mentions; only keep valid MC usernames
            if (!ALL_MENTION.equalsIgnoreCase(mention) && isValidPlayerName(mention)) {
                mentions.add(mention);
            }
        }

        return mentions;
    }

    /**
     * Returns true if the name is a valid Minecraft Java username (3-16, [A-Za-z0-9_]).
     */
    public static boolean isValidPlayerName(String name) {
        return name != null && PLAYER_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Checks if the message contains an @all mention.
     * 
     * @param message the raw message to check
     * @return true if @all is present (case-insensitive)
     */
    public boolean hasAllMention(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        
        Matcher matcher = MENTION_PATTERN.matcher(message);
        while (matcher.find()) {
            if (ALL_MENTION.equalsIgnoreCase(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts the total number of mentions in a message (including @all).
     * 
     * @param message the raw message to parse
     * @return the count of all mentions
     */
    public int countMentions(String message) {
        if (message == null || message.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        Matcher matcher = MENTION_PATTERN.matcher(message);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Parses all @mentions from a message, including @all.
     * 
     * @param message the raw message to parse
     * @return list of all mentioned names (including "all" if present), never null
     */
    public List<String> parseAllMentions(String message) {
        if (message == null || message.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<String> mentions = new ArrayList<>();
        Matcher matcher = MENTION_PATTERN.matcher(message);
        
        while (matcher.find()) {
            mentions.add(matcher.group(1));
        }
        
        return mentions;
    }

    /**
     * Expands @all to a list of player names.
     * 
     * @param message the message containing @all
     * @param channelMembers all members in the channel
     * @param senderId the UUID or name of the sender (to exclude from expansion)
     * @return list of player names to notify (excludes sender)
     */
    public List<String> expandAllMention(String message, List<String> channelMembers, String senderId) {
        Objects.requireNonNull(channelMembers, "channelMembers cannot be null");
        
        if (!hasAllMention(message)) {
            return Collections.emptyList();
        }
        
        List<String> expanded = new ArrayList<>();
        for (String member : channelMembers) {
            // Exclude the sender from the notification list
            if (!member.equals(senderId)) {
                expanded.add(member);
            }
        }
        return expanded;
    }

    /**
     * Checks if a specific player is mentioned in the message.
     * 
     * @param message the raw message
     * @param playerName the player name to check for
     * @return true if the player is mentioned (case-insensitive)
     */
    public boolean isPlayerMentioned(String message, String playerName) {
        if (message == null || playerName == null) {
            return false;
        }
        
        List<String> mentions = parseMentions(message);
        for (String mention : mentions) {
            if (mention.equalsIgnoreCase(playerName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the positions of all mentions in the message.
     * Useful for highlighting mentions in the UI.
     * 
     * @param message the raw message
     * @return list of MentionPosition objects
     */
    public List<MentionPosition> getMentionPositions(String message) {
        if (message == null || message.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<MentionPosition> positions = new ArrayList<>();
        Matcher matcher = MENTION_PATTERN.matcher(message);
        
        while (matcher.find()) {
            positions.add(new MentionPosition(
                matcher.start(),
                matcher.end(),
                matcher.group(1)
            ));
        }
        
        return positions;
    }

    /**
     * Represents the position of a mention in a message.
     */
    public static class MentionPosition {
        private final int start;
        private final int end;
        private final String name;

        public MentionPosition(int start, int end, String name) {
            this.start = start;
            this.end = end;
            this.name = name;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MentionPosition that = (MentionPosition) o;
            return start == that.start && end == that.end && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end, name);
        }

        @Override
        public String toString() {
            return "MentionPosition{start=" + start + ", end=" + end + ", name='" + name + "'}";
        }
    }
}
