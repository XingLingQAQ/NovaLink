package com.nova.chat.client.channel;

import java.util.Map;
import java.util.Set;

/**
 * Resolves chat channel routing from configured message prefixes.
 *
 * <p>Platforms map single (or multi) character prefixes to channel ids via the
 * {@code chat.channel-prefixes} config section (e.g. {@code "!" -> "global"}).
 * When an outbound chat message starts with a configured prefix, the message
 * is routed to the mapped channel with the prefix stripped, without requiring
 * the player to switch their active channel first.
 *
 * <p>Rules:
 * <ul>
 *   <li>Only the start of the message is matched; when several configured
 *       prefixes match, the longest one wins.</li>
 *   <li>The remaining content after the prefix must be non-blank, otherwise
 *       the message is treated as a normal message.</li>
 *   <li>The mapped channel must exist in the caller-supplied known-channel
 *       view (from {@link KnownChannelRegistry}), otherwise the message is
 *       treated as a normal message.</li>
 *   <li>A leading {@code \} escapes a prefix: {@code \!hello} sends the
 *       literal {@code !hello} to the player's active channel (the escape
 *       character is stripped). A {@code \} not followed by a configured
 *       prefix is left untouched.</li>
 * </ul>
 *
 * <p>Channel permission/membership rules are enforced by the backend; this
 * resolver performs no permission checks.
 */
public final class ChannelPrefixResolver {

    /** Escape character that suppresses prefix routing for one message. */
    public static final char ESCAPE_CHAR = '\\';

    private ChannelPrefixResolver() {
    }

    /**
     * Resolves prefix routing for an outbound chat message.
     *
     * @param prefixes         configured prefix-to-channel-id map (may be null or
     *                         empty to disable the feature)
     * @param message          the raw outbound message
     * @param knownChannelIds  the set of channel ids currently known from the
     *                         backend ConfigSync (may be null when unavailable)
     * @return the resolution; never null. When no prefix applies, the result is
     *         a passthrough carrying the original message.
     */
    public static Resolution resolve(Map<String, String> prefixes, String message, Set<String> knownChannelIds) {
        if (message == null || message.isEmpty() || prefixes == null || prefixes.isEmpty()) {
            return Resolution.passthrough(message);
        }

        if (message.charAt(0) == ESCAPE_CHAR) {
            String literal = message.substring(1);
            // Strip the escape only when it actually escapes a configured prefix.
            if (matchPrefix(prefixes, literal) != null) {
                return Resolution.passthrough(literal);
            }
            return Resolution.passthrough(message);
        }

        String prefix = matchPrefix(prefixes, message);
        if (prefix == null) {
            return Resolution.passthrough(message);
        }

        String channelId = prefixes.get(prefix);
        if (channelId == null || channelId.trim().isEmpty()) {
            return Resolution.passthrough(message);
        }

        String content = message.substring(prefix.length()).trim();
        if (content.isEmpty()) {
            return Resolution.passthrough(message);
        }

        if (knownChannelIds == null || !knownChannelIds.contains(channelId)) {
            return Resolution.passthrough(message);
        }

        return Resolution.redirect(channelId, content);
    }

    /**
     * Finds the longest configured prefix that the message starts with.
     */
    private static String matchPrefix(Map<String, String> prefixes, String message) {
        String best = null;
        for (String prefix : prefixes.keySet()) {
            if (prefix == null || prefix.isEmpty()) {
                continue;
            }
            if (message.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best;
    }

    /**
     * Result of a prefix resolution. Always carries the effective outbound
     * message: the stripped content on a redirect, the unescaped literal when
     * an escape applied, or the original message otherwise.
     */
    public static final class Resolution {

        private final String channelId;
        private final String message;

        private Resolution(String channelId, String message) {
            this.channelId = channelId;
            this.message = message;
        }

        static Resolution passthrough(String message) {
            return new Resolution(null, message);
        }

        static Resolution redirect(String channelId, String message) {
            return new Resolution(channelId, message);
        }

        /**
         * @return true if the message should be routed to {@link #getChannelId()}
         *         instead of the player's active channel
         */
        public boolean isRedirect() {
            return channelId != null;
        }

        /**
         * @return the target channel id, or null when not a redirect
         */
        public String getChannelId() {
            return channelId;
        }

        /**
         * @return the effective outbound message (prefix/escape stripped as needed)
         */
        public String getMessage() {
            return message;
        }
    }
}
