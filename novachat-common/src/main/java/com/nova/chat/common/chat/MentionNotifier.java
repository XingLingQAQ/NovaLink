package com.nova.chat.common.chat;

import com.nova.chat.common.protocol.packets.MentionPacket;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Handles mention notification logic for the NovaChat system.
 * 
 * This class provides the core logic for processing mentions and creating
 * notification packets. Platform-specific implementations should use this
 * class and implement the actual notification delivery (sound, title, etc.).
 * 
 * Requirements: 11.2 - When a player is mentioned, the system SHALL send
 * sound/title notifications to the mentioned player.
 */
public class MentionNotifier {

    private final MentionParser parser;
    
    /** Default sound for mention notifications */
    public static final String DEFAULT_SOUND = "ENTITY_EXPERIENCE_ORB_PICKUP";
    
    /** Default title fade-in time in ticks */
    public static final int DEFAULT_FADE_IN = 10;
    
    /** Default title stay time in ticks */
    public static final int DEFAULT_STAY = 40;
    
    /** Default title fade-out time in ticks */
    public static final int DEFAULT_FADE_OUT = 10;

    /**
     * Creates a new MentionNotifier with a default MentionParser.
     */
    public MentionNotifier() {
        this.parser = new MentionParser();
    }

    /**
     * Creates a new MentionNotifier with a custom MentionParser.
     *
     * @param parser the mention parser to use
     */
    public MentionNotifier(MentionParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
    }

    /**
     * Parses mentions from a message and creates notification packets.
     *
     * @param mentionerId the UUID of the player who sent the message
     * @param mentionerName the display name of the mentioner
     * @param channelId the channel where the message was sent
     * @param message the message content
     * @param playerResolver a function to resolve player names to UUIDs
     * @return list of MentionPackets for each mentioned player
     */
    public List<MentionPacket> createMentionPackets(
            UUID mentionerId,
            String mentionerName,
            String channelId,
            String message,
            PlayerResolver playerResolver) {
        
        Objects.requireNonNull(mentionerId, "mentionerId cannot be null");
        Objects.requireNonNull(channelId, "channelId cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(playerResolver, "playerResolver cannot be null");
        
        List<String> mentionedNames = parser.parseMentions(message);
        long timestamp = System.currentTimeMillis();
        String preview = truncatePreview(message, 100);
        
        return mentionedNames.stream()
            .map(playerResolver::resolvePlayerUUID)
            .filter(Objects::nonNull)
            .filter(uuid -> !uuid.equals(mentionerId)) // Don't notify self
            .distinct()
            .map(mentionedId -> new MentionPacket(
                mentionerId,
                mentionerName != null ? mentionerName : "",
                mentionedId,
                channelId,
                preview,
                timestamp
            ))
            .toList();
    }

    /**
     * Creates mention packets for @all mentions.
     *
     * @param mentionerId the UUID of the player who sent the message
     * @param mentionerName the display name of the mentioner
     * @param channelId the channel where the message was sent
     * @param message the message content
     * @param channelMembers list of all member UUIDs in the channel
     * @return list of MentionPackets for all channel members (excluding sender)
     */
    public List<MentionPacket> createAllMentionPackets(
            UUID mentionerId,
            String mentionerName,
            String channelId,
            String message,
            List<UUID> channelMembers) {
        
        Objects.requireNonNull(mentionerId, "mentionerId cannot be null");
        Objects.requireNonNull(channelId, "channelId cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(channelMembers, "channelMembers cannot be null");
        
        if (!parser.hasAllMention(message)) {
            return List.of();
        }
        
        long timestamp = System.currentTimeMillis();
        String preview = truncatePreview(message, 100);
        
        return channelMembers.stream()
            .filter(uuid -> !uuid.equals(mentionerId)) // Don't notify sender
            .distinct()
            .map(mentionedId -> new MentionPacket(
                mentionerId,
                mentionerName != null ? mentionerName : "",
                mentionedId,
                channelId,
                preview,
                timestamp
            ))
            .toList();
    }

    /**
     * Checks if a message contains any mentions.
     *
     * @param message the message to check
     * @return true if the message contains at least one mention
     */
    public boolean hasMentions(String message) {
        return parser.countMentions(message) > 0;
    }

    /**
     * Checks if a message contains an @all mention.
     *
     * @param message the message to check
     * @return true if the message contains @all
     */
    public boolean hasAllMention(String message) {
        return parser.hasAllMention(message);
    }

    /**
     * Gets the underlying MentionParser.
     *
     * @return the mention parser
     */
    public MentionParser getParser() {
        return parser;
    }

    /**
     * Truncates a message preview to the specified maximum length.
     *
     * @param message the message to truncate
     * @param maxLength the maximum length
     * @return the truncated message with "..." if truncated
     */
    private String truncatePreview(String message, int maxLength) {
        if (message == null || message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength - 3) + "...";
    }

    /**
     * Functional interface for resolving player names to UUIDs.
     */
    @FunctionalInterface
    public interface PlayerResolver {
        /**
         * Resolves a player name to their UUID.
         *
         * @param playerName the player name to resolve
         * @return the player's UUID, or null if not found
         */
        UUID resolvePlayerUUID(String playerName);
    }

    /**
     * Configuration for mention notifications.
     */
    public static class NotificationConfig {
        private boolean enabled = true;
        private String sound = DEFAULT_SOUND;
        private boolean titleEnabled = true;
        private int fadeIn = DEFAULT_FADE_IN;
        private int stay = DEFAULT_STAY;
        private int fadeOut = DEFAULT_FADE_OUT;
        private String highlightColor = "&e";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSound() {
            return sound;
        }

        public void setSound(String sound) {
            this.sound = sound;
        }

        public boolean isTitleEnabled() {
            return titleEnabled;
        }

        public void setTitleEnabled(boolean titleEnabled) {
            this.titleEnabled = titleEnabled;
        }

        public int getFadeIn() {
            return fadeIn;
        }

        public void setFadeIn(int fadeIn) {
            this.fadeIn = fadeIn;
        }

        public int getStay() {
            return stay;
        }

        public void setStay(int stay) {
            this.stay = stay;
        }

        public int getFadeOut() {
            return fadeOut;
        }

        public void setFadeOut(int fadeOut) {
            this.fadeOut = fadeOut;
        }

        public String getHighlightColor() {
            return highlightColor;
        }

        public void setHighlightColor(String highlightColor) {
            this.highlightColor = highlightColor;
        }
    }
}
