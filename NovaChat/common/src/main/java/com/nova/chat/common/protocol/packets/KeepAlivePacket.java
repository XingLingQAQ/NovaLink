package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Keep-alive packet for maintaining connection heartbeat.
 * 
 * Packet ID: 0x07
 * Direction: Bidirectional
 */
public class KeepAlivePacket extends Packet {

    /** Timestamp when the packet was sent (for latency calculation) */
    private long timestamp;

    public KeepAlivePacket() {
        super();
        this.timestamp = System.currentTimeMillis();
    }

    public KeepAlivePacket(UUID requestId) {
        super(requestId);
        this.timestamp = System.currentTimeMillis();
    }

    public KeepAlivePacket(long timestamp) {
        super();
        this.timestamp = timestamp;
    }

    @Override
    public int getPacketId() {
        return PacketIds.KEEP_ALIVE;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeLong(buf, timestamp);
    }

    @Override
    public void read(ByteBuf buf) {
        timestamp = PacketBuffer.readLong(buf);
    }

    // Getters and setters

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Calculates the round-trip latency in milliseconds.
     * 
     * @return latency in milliseconds
     */
    public long getLatency() {
        return System.currentTimeMillis() - timestamp;
    }

    @Override
    public String toString() {
        return "KeepAlivePacket{" +
                "timestamp=" + timestamp +
                '}';
    }
}
