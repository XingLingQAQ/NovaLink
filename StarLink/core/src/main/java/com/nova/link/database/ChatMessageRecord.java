package com.nova.link.database;

/**
 * A persisted chat message row (schema v5 {@code messages} table).
 *
 * <p>The {@code id} is assigned by the storage backend on insert (auto-increment
 * for JDBC, INCR sequence for Redis, atomic counter for Memory) and stamped back
 * via {@link #setId(long)}. {@code timestamp} is epoch milliseconds and maps to
 * the {@code created_at} column — the column name avoids the SQL type keyword
 * {@code timestamp} on purpose.
 *
 * <p>Requirements: message persistence + history query (GET /api/messages)
 */
public class ChatMessageRecord {

    private long id;
    private final String channelId;
    private final String senderId;
    private final String senderName;
    private final String clientId;
    private final String content;
    private final long timestamp;

    public ChatMessageRecord(String channelId, String senderId, String senderName,
                             String clientId, String content, long timestamp) {
        this(0L, channelId, senderId, senderName, clientId, content, timestamp);
    }

    public ChatMessageRecord(long id, String channelId, String senderId, String senderName,
                             String clientId, String content, long timestamp) {
        this.id = id;
        this.channelId = channelId;
        this.senderId = senderId;
        this.senderName = senderName;
        this.clientId = clientId;
        this.content = content;
        this.timestamp = timestamp;
    }

    public long getId() {
        return id;
    }

    /** Stamps the backend-generated id after an insert. */
    public void setId(long id) {
        this.id = id;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getClientId() {
        return clientId;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "ChatMessageRecord{id=" + id + ", channelId='" + channelId + '\''
                + ", senderName='" + senderName + "', timestamp=" + timestamp + '}';
    }
}
