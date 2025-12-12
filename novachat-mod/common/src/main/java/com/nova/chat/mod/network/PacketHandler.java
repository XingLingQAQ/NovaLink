package com.nova.chat.mod.network;

/**
 * Interface for handling incoming packets from the backend
 */
public interface PacketHandler {
    
    /**
     * Handle an incoming packet
     * @param packet the packet to handle (protocol-specific)
     */
    void handlePacket(Object packet);
}
