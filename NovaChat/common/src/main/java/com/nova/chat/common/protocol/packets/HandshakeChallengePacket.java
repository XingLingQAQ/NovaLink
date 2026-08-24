package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Handshake challenge packet — second packet of the AUTH-002 challenge-response
 * handshake (Server → Client).
 *
 * <p>The server generates a fresh 16-byte random nonce and sends it back to the
 * client in response to {@link HandshakeInitPacket}. The client must combine
 * this nonce with the nonce it sent in the init packet to compute the HMAC in
 * {@link HandshakeAuthenticatePacket}.
 *
 * <p><b>Wire format (payload only; envelope is written by {@link Packet#encode}):</b>
 * <pre>
 *   String  serverNonce   (≤ 64, 16 random bytes lowercase-hex = 32 chars)
 * </pre>
 *
 * Packet ID: 0x16
 * Direction: Server → Client
 */
public class HandshakeChallengePacket extends Packet {

    private String serverNonce;

    public HandshakeChallengePacket() {
        super();
    }

    public HandshakeChallengePacket(UUID requestId) {
        super(requestId);
    }

    public HandshakeChallengePacket(String serverNonce) {
        super();
        this.serverNonce = serverNonce != null ? serverNonce : "";
    }

    public HandshakeChallengePacket(UUID requestId, String serverNonce) {
        super(requestId);
        this.serverNonce = serverNonce != null ? serverNonce : "";
    }

    @Override
    public int getPacketId() {
        return PacketIds.HANDSHAKE_CHALLENGE;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeString(buf, serverNonce);
    }

    @Override
    public void read(ByteBuf buf) {
        serverNonce = PacketBuffer.readString(buf, ProtocolLimits.MAX_NONCE);
    }

    public String getServerNonce() {
        return serverNonce;
    }

    public void setServerNonce(String serverNonce) {
        this.serverNonce = serverNonce != null ? serverNonce : "";
    }

    @Override
    public String toString() {
        return "HandshakeChallengePacket{" +
                "serverNonce='" + serverNonce + '\'' +
                '}';
    }
}
