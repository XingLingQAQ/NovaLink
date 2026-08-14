package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import io.netty.buffer.ByteBuf;

import java.util.Objects;
import java.util.UUID;

/**
 * Private message packet for cross-server whispers ({@code /msg}, {@code /reply}).
 *
 * Packet ID: 0x14
 * Direction: Bidirectional
 *
 * <p>Client → Server: the sender's plugin fills sender fields and
 * {@code targetName}; {@code targetId} may be the nil UUID
 * (00000000-0000-0000-0000-000000000000) — the backend resolves the target by
 * name across the whole network. Server → Client: the backend fills the real
 * {@code targetId} and the authoritative {@code timestamp}, then delivers the
 * completed packet to the target's client connection and echoes it back to the
 * sender's client (the receiving plugin renders the sent/received line
 * depending on which local player matches senderId/targetId).
 *
 * <p>Wire: uuid senderId | string senderName | string senderClientId |
 * string targetName | uuid targetId | string content | long timestamp
 */
public class PrivateMessagePacket extends Packet {

    /** UUID of the sending player */
    private UUID senderId;

    /** Display name of the sending player */
    private String senderName;

    /** Client (game server) ID the sender is connected through */
    private String senderClientId;

    /** Target player name as typed by the sender */
    private String targetName;

    /** Target player UUID (nil UUID C→S when unknown; real value S→C) */
    private UUID targetId;

    /** Message content */
    private String content;

    /** Timestamp in Unix milliseconds (server-authoritative S→C) */
    private long timestamp;

    /**
     * Default constructor for deserialization.
     */
    public PrivateMessagePacket() {
        super();
    }

    /**
     * Constructor with request ID for response tracking.
     */
    public PrivateMessagePacket(UUID requestId) {
        super(requestId);
    }

    /**
     * Full constructor.
     */
    public PrivateMessagePacket(UUID senderId, String senderName, String senderClientId,
                                String targetName, UUID targetId, String content,
                                long timestamp) {
        super();
        this.senderId = senderId;
        this.senderName = senderName;
        this.senderClientId = senderClientId;
        this.targetName = targetName;
        this.targetId = targetId;
        this.content = content;
        this.timestamp = timestamp;
    }

    @Override
    public int getPacketId() {
        return PacketIds.PRIVATE_MESSAGE;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeUUID(buf, senderId);
        PacketBuffer.writeString(buf, senderName != null ? senderName : "");
        PacketBuffer.writeString(buf, senderClientId != null ? senderClientId : "");
        PacketBuffer.writeString(buf, targetName != null ? targetName : "");
        PacketBuffer.writeUUID(buf, targetId);
        PacketBuffer.writeString(buf, content != null ? content : "");
        PacketBuffer.writeLong(buf, timestamp);
    }

    @Override
    public void read(ByteBuf buf) {
        senderId = PacketBuffer.readUUID(buf);
        senderName = PacketBuffer.readString(buf);
        senderClientId = PacketBuffer.readString(buf);
        targetName = PacketBuffer.readString(buf);
        targetId = PacketBuffer.readUUID(buf);
        content = PacketBuffer.readString(buf);
        timestamp = PacketBuffer.readLong(buf);
    }

    // ==================== Getters and Setters ====================

    public UUID getSenderId() {
        return senderId;
    }

    public void setSenderId(UUID senderId) {
        this.senderId = senderId;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getSenderClientId() {
        return senderClientId;
    }

    public void setSenderClientId(String senderClientId) {
        this.senderClientId = senderClientId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public void setTargetId(UUID targetId) {
        this.targetId = targetId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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
        PrivateMessagePacket that = (PrivateMessagePacket) o;
        return timestamp == that.timestamp &&
                Objects.equals(senderId, that.senderId) &&
                Objects.equals(senderName, that.senderName) &&
                Objects.equals(senderClientId, that.senderClientId) &&
                Objects.equals(targetName, that.targetName) &&
                Objects.equals(targetId, that.targetId) &&
                Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(senderId, senderName, senderClientId, targetName, targetId,
                content, timestamp);
    }

    @Override
    public String toString() {
        return "PrivateMessagePacket{" +
                "senderId=" + senderId +
                ", senderName='" + senderName + '\'' +
                ", senderClientId='" + senderClientId + '\'' +
                ", targetName='" + targetName + '\'' +
                ", targetId=" + targetId +
                ", content='" + content + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
