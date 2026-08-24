package com.nova.link.database;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Filter criteria for message history queries (GET /api/messages).
 *
 * <p>All fields are optional (null/blank = no constraint):
 * <ul>
 *   <li>{@code channelId} — exact channel match</li>
 *   <li>{@code clientId} — exact client match (the {@code server} query param)</li>
 *   <li>{@code senderName} — case-insensitive substring match ({@code player} param)</li>
 *   <li>{@code contentQuery} — case-insensitive substring match ({@code q} param)</li>
 *   <li>{@code from}/{@code to} — inclusive epoch-millisecond bounds</li>
 * </ul>
 *
 * <p>{@link #matches(ChatMessageRecord)} is the single source of truth for the
 * non-SQL backends (Memory/Redis) so all five providers filter identically.
 */
public class MessageFilter {

    private final String channelId;
    private final String clientId;
    private final String senderName;
    private final String contentQuery;
    private final Long from;
    private final Long to;
    private final Set<String> allowedChannelIds;

    public MessageFilter(String channelId, String clientId, String senderName,
                         String contentQuery, Long from, Long to) {
        this(channelId, clientId, senderName, contentQuery, from, to, null);
    }

    /**
     * Creates a filter constrained to an authorized channel set.
     * A {@code null} set means unrestricted; an empty set matches no rows.
     */
    public MessageFilter(String channelId, String clientId, String senderName,
                         String contentQuery, Long from, Long to,
                         Set<String> allowedChannelIds) {
        this.channelId = blankToNull(channelId);
        this.clientId = blankToNull(clientId);
        this.senderName = blankToNull(senderName);
        this.contentQuery = blankToNull(contentQuery);
        this.from = from;
        this.to = to;
        this.allowedChannelIds = allowedChannelIds == null
                ? null
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowedChannelIds));
    }

    /** @return a filter with no constraints (matches everything) */
    public static MessageFilter any() {
        return new MessageFilter(null, null, null, null, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getContentQuery() {
        return contentQuery;
    }

    public Long getFrom() {
        return from;
    }

    public Long getTo() {
        return to;
    }

    public Set<String> getAllowedChannelIds() {
        return allowedChannelIds;
    }

    /**
     * In-memory evaluation of this filter against a record — used by the
     * Memory and Redis providers (the JDBC providers translate to SQL).
     *
     * @param record the record to test
     * @return true when the record satisfies every set constraint
     */
    public boolean matches(ChatMessageRecord record) {
        if (record == null) {
            return false;
        }
        if (channelId != null && !channelId.equals(record.getChannelId())) {
            return false;
        }
        if (allowedChannelIds != null && !allowedChannelIds.contains(record.getChannelId())) {
            return false;
        }
        if (clientId != null && !clientId.equals(record.getClientId())) {
            return false;
        }
        if (senderName != null && !containsIgnoreCase(record.getSenderName(), senderName)) {
            return false;
        }
        if (contentQuery != null && !containsIgnoreCase(record.getContent(), contentQuery)) {
            return false;
        }
        if (from != null && record.getTimestamp() < from) {
            return false;
        }
        if (to != null && record.getTimestamp() > to) {
            return false;
        }
        return true;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
