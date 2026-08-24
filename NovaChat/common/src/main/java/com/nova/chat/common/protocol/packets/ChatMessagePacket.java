package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chat message packet for sending and receiving chat messages.
 * 
 * Packet ID: 0x03
 * Direction: Bidirectional
 */
public class ChatMessagePacket extends Packet {

    /** UUID of the message sender */
    private UUID senderId;
    
    /** Display name of the sender */
    private String senderName;
    
    /** Client ID the sender is connected through */
    private String clientId;
    
    /** Target channel ID */
    private String channelId;
    
    /** Message content */
    private String content;
    
    /** PlaceholderAPI variables (key-value pairs) */
    private Map<String, String> placeholders;

    public ChatMessagePacket() {
        super();
        this.placeholders = new HashMap<>();
    }

    public ChatMessagePacket(UUID requestId) {
        super(requestId);
        this.placeholders = new HashMap<>();
    }

    public ChatMessagePacket(UUID senderId, String senderName, String clientId, 
                             String channelId, String content) {
        super();
        this.senderId = senderId;
        this.senderName = senderName;
        this.clientId = clientId;
        this.channelId = channelId;
        this.content = content;
        this.placeholders = new HashMap<>();
    }

    @Override
    public int getPacketId() {
        return PacketIds.CHAT_MESSAGE;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeUUID(buf, senderId);
        PacketBuffer.writeString(buf, senderName != null ? senderName : "");
        PacketBuffer.writeString(buf, clientId != null ? clientId : "");
        PacketBuffer.writeString(buf, channelId != null ? channelId : "");
        PacketBuffer.writeString(buf, content != null ? content : "");

        // Write placeholders map (never null after construction / setPlaceholders)
        Map<String, String> map = placeholders != null ? placeholders : Map.of();
        PacketBuffer.writeVarInt(buf, map.size());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            PacketBuffer.writeString(buf, entry.getKey() != null ? entry.getKey() : "");
            PacketBuffer.writeString(buf, entry.getValue() != null ? entry.getValue() : "");
        }
    }


    @Override
    public void read(ByteBuf buf) {
        senderId = PacketBuffer.readUUID(buf);
        // Bound field sizes to protocol limits to resist oversized frames
        // (PROTO-003). Constants live in ProtocolLimits so non-JVM forks
        // mirror the same numeric values.
        senderName = PacketBuffer.readString(buf, ProtocolLimits.MAX_SENDER_NAME);
        clientId = PacketBuffer.readString(buf, ProtocolLimits.MAX_CLIENT_ID);
        channelId = PacketBuffer.readString(buf, ProtocolLimits.MAX_CHANNEL_ID);
        content = PacketBuffer.readString(buf, ProtocolLimits.MAX_MESSAGE_CONTENT);

        // Read placeholders map (optional for legacy clients)
        if (!buf.isReadable()) {
            placeholders = new HashMap<>();
            return;
        }

        int size;
        try {
            size = PacketBuffer.readVarInt(buf);
        } catch (Exception e) {
            // Legacy payload ended after content; treat as no placeholders.
            placeholders = new HashMap<>();
            return;
        }

        if (size < 0 || size > 1000) {
            // Defensive: avoid OOM on corrupted frames.
            placeholders = new HashMap<>();
            return;
        }

        placeholders = new HashMap<>(Math.min(size, 64));
        for (int i = 0; i < size; i++) {
            String key = PacketBuffer.readString(buf, 128);
            String value = PacketBuffer.readString(buf, 512);
            placeholders.put(key, value);
        }
    }

    // Getters and setters

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

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, String> getPlaceholders() {
        return placeholders;
    }

    public void setPlaceholders(Map<String, String> placeholders) {
        this.placeholders = placeholders != null ? placeholders : new HashMap<>();
    }

    public void addPlaceholder(String key, String value) {
        this.placeholders.put(key, value);
    }

    @Override
    public String toString() {
        return "ChatMessagePacket{" +
                "senderId=" + senderId +
                ", senderName='" + senderName + '\'' +
                ", clientId='" + clientId + '\'' +
                ", channelId='" + channelId + '\'' +
                ", content='" + content + '\'' +
                ", placeholders=" + placeholders +
                '}';
    }
}
