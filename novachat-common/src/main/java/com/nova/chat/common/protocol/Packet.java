package com.nova.chat.common.protocol;

import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Abstract base class for all NovaProtocol packets.
 * 
 * NovaProtocol Frame Structure:
 * | Length (VarInt) | PacketID (Byte) | RequestID (UUID) | Payload (Byte[]) |
 */
public abstract class Packet {

    /**
     * The unique request ID for tracking this packet.
     */
    protected UUID requestId;

    /**
     * Creates a new packet with a random request ID.
     */
    protected Packet() {
        this.requestId = UUID.randomUUID();
    }

    /**
     * Creates a new packet with the specified request ID.
     *
     * @param requestId the request ID
     */
    protected Packet(UUID requestId) {
        this.requestId = requestId;
    }

    /**
     * Gets the packet type ID.
     *
     * @return the packet ID (0x01-0xFF)
     */
    public abstract int getPacketId();

    /**
     * Writes the packet payload to the buffer.
     * Subclasses must implement this to serialize their specific data.
     *
     * @param buf the buffer to write to
     */
    public abstract void write(ByteBuf buf);

    /**
     * Reads the packet payload from the buffer.
     * Subclasses must implement this to deserialize their specific data.
     *
     * @param buf the buffer to read from
     */
    public abstract void read(ByteBuf buf);


    /**
     * Gets the request ID for this packet.
     *
     * @return the request ID
     */
    public UUID getRequestId() {
        return requestId;
    }

    /**
     * Sets the request ID for this packet.
     *
     * @param requestId the request ID
     */
    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    /**
     * Encodes the complete packet (including header) to a ByteBuf.
     *
     * @param buf the buffer to write to
     */
    public void encode(ByteBuf buf) {
        // Write packet ID
        buf.writeByte(getPacketId());
        
        // Write request ID (UUID as two longs, big-endian)
        buf.writeLong(requestId.getMostSignificantBits());
        buf.writeLong(requestId.getLeastSignificantBits());
        
        // Write payload
        write(buf);
    }

    /**
     * Decodes the packet header and payload from a ByteBuf.
     * Note: The packet ID should already be read to determine the packet type.
     *
     * @param buf the buffer to read from
     */
    public void decode(ByteBuf buf) {
        // Read request ID (UUID as two longs, big-endian)
        long mostSig = buf.readLong();
        long leastSig = buf.readLong();
        this.requestId = new UUID(mostSig, leastSig);
        
        // Read payload
        read(buf);
    }
}
