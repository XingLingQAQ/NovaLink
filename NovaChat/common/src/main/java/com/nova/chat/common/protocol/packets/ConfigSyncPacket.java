package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Configuration sync packet for hot reload mechanism.
 * Sent from server to clients when configuration changes.
 * 
 * Packet ID: 0x06
 * Direction: Server → Client
 * 
 * Requirements: 4.5, 18.1, 18.2
 */
public class ConfigSyncPacket extends Packet {

    /** JSON string containing the configuration data */
    private String configJson;
    
    /** Timestamp when the configuration was updated */
    private long timestamp;

    public ConfigSyncPacket() {
        super();
    }

    public ConfigSyncPacket(UUID requestId) {
        super(requestId);
    }

    public ConfigSyncPacket(String configJson, long timestamp) {
        super();
        this.configJson = configJson;
        this.timestamp = timestamp;
    }

    @Override
    public int getPacketId() {
        return PacketIds.CONFIG_SYNC;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeString(buf, configJson != null ? configJson : "{}");
        buf.writeLong(timestamp);
    }

    @Override
    public void read(ByteBuf buf) {
        // PROTO-003: bounded by the dedicated ConfigSync JSON budget so a single
        // field cannot approach the 4 MiB frame ceiling. Non-JVM receivers
        // mirror this constant.
        configJson = PacketBuffer.readString(buf, ProtocolLimits.MAX_CONFIG_SYNC_JSON);
        timestamp = buf.readLong();
    }

    // Getters and setters

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "ConfigSyncPacket{" +
                "configJson='" + (configJson != null ? configJson.substring(0, Math.min(50, configJson.length())) + "..." : "null") + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
