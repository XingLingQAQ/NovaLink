package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import io.netty.buffer.ByteBuf;

import java.util.Objects;
import java.util.UUID;

/**
 * Item display packet for transmitting item display data across servers.
 * 
 * Packet ID: 0x10
 * Direction: Bidirectional
 * 
 * This packet is used when a player uses [item] or [i] tags in chat to display
 * their held item to other players across the network.
 * 
 * **Feature: novachat-platform-extensions, Property 13: Display Packet Serialization Round-Trip**
 * **Validates: Requirements 19.1**
 */
public class ItemDisplayPacket extends Packet {

    /** UUID of the player who sent the item display */
    private UUID senderId;
    
    /** Display name of the sender */
    private String senderName;
    
    /** Channel ID where the item was displayed */
    private String channelId;
    
    /** Serialized item data (NBT or JSON format) */
    private String itemJson;
    
    /** Timestamp when the item display was sent (Unix milliseconds) */
    private long timestamp;

    /**
     * Default constructor for deserialization.
     */
    public ItemDisplayPacket() {
        super();
    }

    /**
     * Constructor with request ID for response tracking.
     */
    public ItemDisplayPacket(UUID requestId) {
        super(requestId);
    }

    /**
     * Full constructor for creating an item display packet.
     *
     * @param senderId   UUID of the sender
     * @param senderName display name of the sender
     * @param channelId  channel where the item was displayed
     * @param itemJson   serialized item data
     * @param timestamp  timestamp in Unix milliseconds
     */
    public ItemDisplayPacket(UUID senderId, String senderName, String channelId,
                             String itemJson, long timestamp) {
        super();
        this.senderId = senderId;
        this.senderName = senderName;
        this.channelId = channelId;
        this.itemJson = itemJson;
        this.timestamp = timestamp;
    }


    @Override
    public int getPacketId() {
        return PacketIds.ITEM_DISPLAY;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeUUID(buf, senderId);
        PacketBuffer.writeString(buf, senderName != null ? senderName : "");
        PacketBuffer.writeString(buf, channelId != null ? channelId : "");
        PacketBuffer.writeString(buf, itemJson != null ? itemJson : "");
        PacketBuffer.writeLong(buf, timestamp);
    }

    @Override
    public void read(ByteBuf buf) {
        senderId = PacketBuffer.readUUID(buf);
        senderName = PacketBuffer.readString(buf);
        channelId = PacketBuffer.readString(buf);
        itemJson = PacketBuffer.readString(buf);
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

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getItemJson() {
        return itemJson;
    }

    public void setItemJson(String itemJson) {
        this.itemJson = itemJson;
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
        ItemDisplayPacket that = (ItemDisplayPacket) o;
        return timestamp == that.timestamp &&
                Objects.equals(senderId, that.senderId) &&
                Objects.equals(senderName, that.senderName) &&
                Objects.equals(channelId, that.channelId) &&
                Objects.equals(itemJson, that.itemJson);
    }

    @Override
    public int hashCode() {
        return Objects.hash(senderId, senderName, channelId, itemJson, timestamp);
    }

    @Override
    public String toString() {
        return "ItemDisplayPacket{" +
                "senderId=" + senderId +
                ", senderName='" + senderName + '\'' +
                ", channelId='" + channelId + '\'' +
                ", itemJson='" + (itemJson != null && itemJson.length() > 50 
                    ? itemJson.substring(0, 50) + "..." : itemJson) + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
