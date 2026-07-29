package com.nova.chat.common.protocol;

import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registry for packet types in NovaProtocol.
 * Maps packet IDs to packet classes for encoding/decoding.
 */
public class PacketRegistry {

    private final Map<Integer, Supplier<? extends Packet>> packetFactories = new HashMap<>();
    private final Map<Class<? extends Packet>, Integer> packetIds = new HashMap<>();

    /**
     * Registers a packet type with its ID and factory.
     *
     * @param packetId    the packet ID
     * @param packetClass the packet class
     * @param factory     the factory to create new instances
     * @param <T>         the packet type
     */
    public <T extends Packet> void register(int packetId, Class<T> packetClass, Supplier<T> factory) {
        if (packetFactories.containsKey(packetId)) {
            throw new IllegalArgumentException("Packet ID " + packetId + " is already registered");
        }
        packetFactories.put(packetId, factory);
        packetIds.put(packetClass, packetId);
    }

    /**
     * Creates a new packet instance for the given packet ID.
     *
     * @param packetId the packet ID
     * @return a new packet instance, or null if not registered
     */
    public Packet createPacket(int packetId) {
        Supplier<? extends Packet> factory = packetFactories.get(packetId);
        return factory != null ? factory.get() : null;
    }

    /**
     * Gets the packet ID for the given packet class.
     *
     * @param packetClass the packet class
     * @return the packet ID, or -1 if not registered
     */
    public int getPacketId(Class<? extends Packet> packetClass) {
        return packetIds.getOrDefault(packetClass, -1);
    }


    /**
     * Checks if a packet ID is registered.
     *
     * @param packetId the packet ID
     * @return true if registered
     */
    public boolean isRegistered(int packetId) {
        return packetFactories.containsKey(packetId);
    }

    /**
     * Decodes a packet from the buffer.
     * The buffer should be positioned at the packet ID byte.
     *
     * @param buf the buffer to read from
     * @return the decoded packet, or null if the packet ID is not registered
     */
    public Packet decode(ByteBuf buf) {
        int packetId = buf.readByte() & 0xFF;
        Packet packet = createPacket(packetId);
        if (packet != null) {
            packet.decode(buf);
        }
        return packet;
    }

    /**
     * Encodes a packet to the buffer.
     *
     * @param packet the packet to encode
     * @param buf    the buffer to write to
     */
    public void encode(Packet packet, ByteBuf buf) {
        packet.encode(buf);
    }
}
