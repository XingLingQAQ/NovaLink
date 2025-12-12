package com.nova.link.network;

import com.nova.chat.common.protocol.Packet;

/**
 * Functional interface for handling specific packet types.
 *
 * @param <T> the packet type this handler processes
 */
@FunctionalInterface
public interface PacketHandler<T extends Packet> {

    /**
     * Handles a packet from a client connection.
     *
     * @param connection the client connection that sent the packet
     * @param packet     the packet to handle
     */
    void handle(ClientConnection connection, T packet);
}
