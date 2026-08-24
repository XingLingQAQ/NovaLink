package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Channel action response packet for responding to channel operations.
 * 
 * Packet ID: 0x05
 * Direction: Server → Client
 */
public class ChannelActionResponsePacket extends Packet {

    /** Whether the action was successful */
    private boolean success;
    
    /** The action that was performed */
    private ChannelAction action;
    
    /** Target channel ID */
    private String channelId;
    
    /** Error code if failed (e.g., NC-403, NC-404) */
    private String errorCode;
    
    /** Human-readable message */
    private String message;
    
    /** Extra data from the response */
    private Map<String, String> extra;

    public ChannelActionResponsePacket() {
        super();
        this.extra = new HashMap<>();
    }

    public ChannelActionResponsePacket(UUID requestId) {
        super(requestId);
        this.extra = new HashMap<>();
    }

    public ChannelActionResponsePacket(boolean success, ChannelAction action, String channelId, 
                                       String errorCode, String message) {
        super();
        this.success = success;
        this.action = action;
        this.channelId = channelId;
        this.errorCode = errorCode != null ? errorCode : "";
        this.message = message != null ? message : "";
        this.extra = new HashMap<>();
    }

    @Override
    public int getPacketId() {
        return PacketIds.CHANNEL_ACTION_RESPONSE;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeBoolean(success);
        buf.writeByte(action != null ? action.getId() : 0);
        PacketBuffer.writeString(buf, channelId != null ? channelId : "");
        PacketBuffer.writeString(buf, errorCode);
        PacketBuffer.writeString(buf, message);
        
        // Write extra map
        PacketBuffer.writeVarInt(buf, extra.size());
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            PacketBuffer.writeString(buf, entry.getKey());
            PacketBuffer.writeString(buf, entry.getValue());
        }
    }

    @Override
    public void read(ByteBuf buf) {
        success = buf.readBoolean();
        action = ChannelAction.fromId(buf.readByte());
        // PROTO-003: bound each field so a single oversized string cannot
        // approach the 4 MiB frame ceiling.
        channelId = PacketBuffer.readString(buf, ProtocolLimits.MAX_CHANNEL_ID);
        errorCode = PacketBuffer.readString(buf, ProtocolLimits.MAX_ERROR_CODE);
        message = PacketBuffer.readString(buf, ProtocolLimits.MAX_ERROR_MESSAGE);

        // Read extra map (optional for legacy implementations)
        if (!buf.isReadable()) {
            extra = new HashMap<>();
            return;
        }

        int size;
        try {
            size = PacketBuffer.readVarInt(buf);
        } catch (Exception e) {
            extra = new HashMap<>();
            return;
        }

        if (size < 0 || size > 1000) {
            extra = new HashMap<>();
            return;
        }

        extra = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = PacketBuffer.readString(buf, ProtocolLimits.MAX_METADATA_KEY);
            String value = PacketBuffer.readString(buf, ProtocolLimits.MAX_METADATA_VALUE);
            extra.put(key, value);
        }
    }

    // Getters and setters

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public ChannelAction getAction() {
        return action;
    }

    public void setAction(ChannelAction action) {
        this.action = action;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode != null ? errorCode : "";
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message != null ? message : "";
    }

    public Map<String, String> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, String> extra) {
        this.extra = extra != null ? extra : new HashMap<>();
    }

    public void addExtra(String key, String value) {
        this.extra.put(key, value);
    }

    public String getExtra(String key) {
        return this.extra.get(key);
    }

    @Override
    public String toString() {
        return "ChannelActionResponsePacket{" +
                "success=" + success +
                ", action=" + action +
                ", channelId='" + channelId + '\'' +
                ", errorCode='" + errorCode + '\'' +
                ", message='" + message + '\'' +
                ", extra=" + extra +
                '}';
    }
}
