package com.nova.chat.common.chat;

import com.nova.chat.common.protocol.packets.MentionPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Minimum interval (milliseconds) between duplicate notifications to the
     * same mentioned player from the same mentioner. Within this window, only
     * the first notification is emitted; subsequent ones are suppressed to avoid
     * spamming the recipient (UX-DESIGN §4.2).
     */
    public static final long DEDUP_INTERVAL_MS = 3_000L;

    /** Default sound for mention notifications */
    public static final String DEFAULT_SOUND = "ENTITY_EXPERIENCE_ORB_PICKUP";

    /** Default title fade-in time in ticks */
    public static final int DEFAULT_FADE_IN = 10;

    /** Default title stay time in ticks */
    public static final int DEFAULT_STAY = 40;

    /** Default title fade-out time in ticks */
    public static final int DEFAULT_FADE_OUT = 10;

    /** Default highlight color prefix applied to mentioned names (e.g. {@code "&e"}). */
    public static final String DEFAULT_HIGHLIGHT_COLOR = "&e";

    /**
     * Per-recipient dedup state. Maps the dedup key
     * {@code mentionedId + "|" + mentionerId} to the timestamp of the last
     * notification that was actually emitted. Guarded by this instance's
     * monitor; {@link MentionNotifier} is not expected to be shared across
     * unrelated threads, but the map is concurrent-safe anyway.
     */
    private final Map<String, Long> lastNotifiedAt = new HashMap<>();

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
            .map(name -> {
                try {
                    return playerResolver.resolvePlayerUUID(name);
                } catch (RuntimeException e) {
                    return null;
                }
            })
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
     * Checks if a notification for the given (mentioned, mentioner) pair should
     * be emitted now, suppressing duplicates within {@link #DEDUP_INTERVAL_MS}.
     *
     * <p>This is a side-effecting filter: if the call returns {@code true} the
     * caller is expected to actually deliver the notification, and the last
     * emitted timestamp for this pair is recorded. If it returns {@code false}
     * the pair was notified too recently and should be skipped silently.
     *
     * <p>Dedup is keyed on (mentioned player, mentioner) so that a single burst
     * of repeated mentions from one author to one recipient collapses to one
     * notification, while a different author mentioning the same recipient in
     * the same window still gets through.
     *
     * @param mentionedId the player who would be notified
     * @param mentionerId the player who sent the mention
     * @param now the current time in Unix milliseconds
     * @return true if this notification should be emitted (and has been recorded);
     *         false if it was suppressed as a duplicate
     */
    public boolean shouldNotify(UUID mentionedId, UUID mentionerId, long now) {
        Objects.requireNonNull(mentionedId, "mentionedId cannot be null");
        Objects.requireNonNull(mentionerId, "mentionerId cannot be null");
        String key = dedupKey(mentionedId, mentionerId);
        synchronized (lastNotifiedAt) {
            Long last = lastNotifiedAt.get(key);
            if (last != null && (now - last) < DEDUP_INTERVAL_MS) {
                return false;
            }
            lastNotifiedAt.put(key, now);
            return true;
        }
    }

    /**
     * Convenience overload using {@link System#currentTimeMillis()}.
     *
     * @param mentionedId the player who would be notified
     * @param mentionerId the player who sent the mention
     * @return true if this notification should be emitted; false if suppressed
     */
    public boolean shouldNotify(UUID mentionedId, UUID mentionerId) {
        return shouldNotify(mentionedId, mentionerId, System.currentTimeMillis());
    }

    /**
     * Clears all dedup state. Useful for tests.
     */
    public void clearDedup() {
        synchronized (lastNotifiedAt) {
            lastNotifiedAt.clear();
        }
    }

    private static String dedupKey(UUID mentionedId, UUID mentionerId) {
        return mentionedId + "|" + mentionerId;
    }

    /**
     * Wraps each {@code @name} mention in the message with the given highlight
     * color prefix, so platforms can render mentioned names in a distinct color.
     *
     * <p>The highlight color is a Minecraft color-code prefix in the platform's
     * native form (e.g. {@code "&e"} for legacy section-sign coloring, or an
     * empty string to leave mentions un-highlighted). After wrapping, the
     * returned string still needs the platform's normal color-code translation
     * pass, so the prefix must be in the platform's raw {@code &}-prefixed form
     * (or {@code §}-prefixed form, whichever the platform translates from).
     *
     * <p>Only valid player-name mentions are wrapped; {@code @all} is left as-is
     * (it is not a name to highlight, and {@link MentionParser#getMentionPositions}
     * already excludes it). The original {@code @} sigil is preserved so readers
     * still see it is a mention.
     *
     * <p>Self-mentions: callers may pass {@code mentionerName} as {@code null}
     * to wrap every mention regardless of author. When a recipient's own name
     * matches an mentioned name, the wrapping still applies — this is the
     * intended per-recipient highlight behavior.
     *
     * @param message the raw message (before color translation)
     * @param highlightColor the color prefix to prepend to each mention,
     *                        e.g. {@code "&e"}; may be {@code null} or empty
     *                        to return the message unchanged
     * @return the message with each mention wrapped in the highlight color
     */
    public static String highlightMentions(String message, String highlightColor) {
        if (message == null || message.isEmpty() || highlightColor == null || highlightColor.isEmpty()) {
            return message;
        }
        MentionParser parser = new MentionParser();
        List<MentionParser.MentionPosition> positions = parser.getMentionPositions(message);
        if (positions.isEmpty()) {
            return message;
        }
        // Build right-to-left so indices stay valid as we insert prefixes.
        StringBuilder sb = new StringBuilder(message);
        for (int i = positions.size() - 1; i >= 0; i--) {
            MentionParser.MentionPosition pos = positions.get(i);
            String name = pos.getName();
            if (name == null
                    || MentionParser.ALL_MENTION.equalsIgnoreCase(name)
                    || !MentionParser.isValidPlayerName(name)) {
                continue; // skip @all and invalid names
            }
            sb.insert(pos.getStart(), highlightColor);
        }
        return sb.toString();
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
        int cut = Math.max(0, maxLength - 3);
        return message.substring(0, cut) + "...";
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
        private String highlightColor = DEFAULT_HIGHLIGHT_COLOR;

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
