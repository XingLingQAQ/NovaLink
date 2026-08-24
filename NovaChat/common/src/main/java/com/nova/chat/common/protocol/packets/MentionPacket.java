package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.Objects;
import java.util.UUID;

/**
 * Mention notification packet for notifying players when they are mentioned.
 * 
 * Packet ID: 0x12
 * Direction: Server → Client
 * 
 * **Feature: novachat-platform-extensions, Property 14: Mention Packet Serialization Round-Trip**
 * **Validates: Requirements 20.1-20.2**
 */
public class MentionPacket extends Packet {

    /** UUID of the player who sent the mention */
    private UUID mentionerId;
    
    /** Display name of the mentioner */
    private String mentionerName;
    
    /** UUID of the player being mentioned */
    private UUID mentionedId;
    
    /** Channel ID where the mention occurred */
    private String channelId;
    
    /** Preview of the message containing the mention */
    private String messagePreview;
    
    /** Timestamp when the mention was sent (Unix milliseconds) */
    private long timestamp;

    /**
     * Default constructor for deserialization.
     */
    public MentionPacket() {
        super();
    }

    /**
     * Constructor with request ID for response tracking.
     */
    public MentionPacket(UUID requestId) {
        super(requestId);
    }

    /**
     * Full constructor for creating a mention notification.
     */
    public MentionPacket(UUID mentionerId, String mentionerName, UUID mentionedId,
                         String channelId, String messagePreview, long timestamp) {
        super();
        this.mentionerId = mentionerId;
        this.mentionerName = mentionerName;
        this.mentionedId = mentionedId;
        this.channelId = channelId;
        this.messagePreview = messagePreview;
        this.timestamp = timestamp;
    }

    @Override
    public int getPacketId() {
        return PacketIds.MENTION;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeUUID(buf, mentionerId);
        PacketBuffer.writeString(buf, mentionerName != null ? mentionerName : "");
        PacketBuffer.writeUUID(buf, mentionedId);
        PacketBuffer.writeString(buf, channelId != null ? channelId : "");
        PacketBuffer.writeString(buf, messagePreview != null ? messagePreview : "");
        PacketBuffer.writeLong(buf, timestamp);
    }

    @Override
    public void read(ByteBuf buf) {
        mentionerId = PacketBuffer.readUUID(buf);
        // PROTO-003: bound each field so a single oversized string cannot
        // approach the 4 MiB frame ceiling.
        mentionerName = PacketBuffer.readString(buf, ProtocolLimits.MAX_SENDER_NAME);
        mentionedId = PacketBuffer.readUUID(buf);
        channelId = PacketBuffer.readString(buf, ProtocolLimits.MAX_CHANNEL_ID);
        messagePreview = PacketBuffer.readString(buf, ProtocolLimits.MAX_MESSAGE_PREVIEW);
        timestamp = PacketBuffer.readLong(buf);
    }

    // ==================== Getters and Setters ====================

    public UUID getMentionerId() {
        return mentionerId;
    }

    public void setMentionerId(UUID mentionerId) {
        this.mentionerId = mentionerId;
    }

    public String getMentionerName() {
        return mentionerName;
    }

    public void setMentionerName(String mentionerName) {
        this.mentionerName = mentionerName;
    }

    public UUID getMentionedId() {
        return mentionedId;
    }

    public void setMentionedId(UUID mentionedId) {
        this.mentionedId = mentionedId;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getMessagePreview() {
        return messagePreview;
    }

    public void setMessagePreview(String messagePreview) {
        this.messagePreview = messagePreview;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MentionPacket that = (MentionPacket) o;
        return timestamp == that.timestamp &&
                Objects.equals(mentionerId, that.mentionerId) &&
                Objects.equals(mentionerName, that.mentionerName) &&
                Objects.equals(mentionedId, that.mentionedId) &&
                Objects.equals(channelId, that.channelId) &&
                Objects.equals(messagePreview, that.messagePreview);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mentionerId, mentionerName, mentionedId, channelId, messagePreview, timestamp);
    }

    @Override
    public String toString() {
        return "MentionPacket{" +
                "mentionerId=" + mentionerId +
                ", mentionerName='" + mentionerName + '\'' +
                ", mentionedId=" + mentionedId +
                ", channelId='" + channelId + '\'' +
                ", messagePreview='" + messagePreview + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
