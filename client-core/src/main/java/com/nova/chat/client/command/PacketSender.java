package com.nova.chat.client.command;

import com.nova.chat.common.protocol.packets.ChannelActionPacket;

/**
 * Abstraction for sending a {@link ChannelActionPacket} to the backend.
 *
 * <p>Defined in the command package so {@link ChannelCommandService} stays free
 * of platform NetworkClient types. Platforms adapt their client, e.g.
 * {@code packet -> networkClient.sendPacket(packet)}.
 *
 * @return {@code true} if the packet was accepted for send (connection active);
 *         {@code false} if send was refused (disconnected, etc.)
 */
@FunctionalInterface
public interface PacketSender {

    /**
     * Attempts to send a channel-action packet.
     *
     * @param packet non-null packet to transmit
     * @return whether the send was accepted
     */
    boolean send(ChannelActionPacket packet);
}
