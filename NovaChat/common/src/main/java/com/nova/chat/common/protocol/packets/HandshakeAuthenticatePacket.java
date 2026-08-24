package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Handshake authenticate packet — third and final packet of the AUTH-002
 * challenge-response handshake (Client → Server).
 *
 * <p>The client echoes its own nonce (the one sent in
 * {@link HandshakeInitPacket}) and proves possession of the stored password
 * hash by sending an HMAC-SHA-256 over {@code serverNonce + clientNonce},
 * keyed by the SHA-256 hex of the password (the stored credential hash).
 *
 * <p><b>Wire format (payload only; envelope is written by {@link Packet#encode}):</b>
 * <pre>
 *   String  clientId      (≤ 64, must match the init packet's clientId)
 *   String  clientNonce   (≤ 64, must echo the init packet's clientNonce)
 *   String  hmac          (≤ 128, lowercase-hex HMAC-SHA-256)
 * </pre>
 *
 * <p>HMAC computation:
 * <pre>
 *   key      = UTF-8 bytes of sha256hex(password)   // stored credential hash
 *   message  = UTF-8 bytes of (serverNonceHex + clientNonceHex)  // concat
 *   output   = HMAC-SHA-256(key, message) as lowercase hex
 * </pre>
 *
 * <p>Field order is fixed. The server validates the echoed {@code clientNonce}
 * against the init packet and recomputes the HMAC in constant time.
 *
 * Packet ID: 0x17
 * Direction: Client → Server
 */
public class HandshakeAuthenticatePacket extends Packet {

    private String clientId;
    private String clientNonce;
    private String hmac;

    public HandshakeAuthenticatePacket() {
        super();
    }

    public HandshakeAuthenticatePacket(UUID requestId) {
        super(requestId);
    }

    public HandshakeAuthenticatePacket(String clientId, String clientNonce, String hmac) {
        super();
        this.clientId = clientId != null ? clientId : "";
        this.clientNonce = clientNonce != null ? clientNonce : "";
        this.hmac = hmac != null ? hmac : "";
    }

    public HandshakeAuthenticatePacket(UUID requestId, String clientId, String clientNonce, String hmac) {
        super(requestId);
        this.clientId = clientId != null ? clientId : "";
        this.clientNonce = clientNonce != null ? clientNonce : "";
        this.hmac = hmac != null ? hmac : "";
    }

    @Override
    public int getPacketId() {
        return PacketIds.HANDSHAKE_AUTHENTICATE;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeString(buf, clientId);
        PacketBuffer.writeString(buf, clientNonce);
        PacketBuffer.writeString(buf, hmac);
    }

    @Override
    public void read(ByteBuf buf) {
        clientId = PacketBuffer.readString(buf, ProtocolLimits.MAX_CLIENT_ID);
        clientNonce = PacketBuffer.readString(buf, ProtocolLimits.MAX_NONCE);
        hmac = PacketBuffer.readString(buf, ProtocolLimits.MAX_HMAC);
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId != null ? clientId : "";
    }

    public String getClientNonce() {
        return clientNonce;
    }

    public void setClientNonce(String clientNonce) {
        this.clientNonce = clientNonce != null ? clientNonce : "";
    }

    public String getHmac() {
        return hmac;
    }

    public void setHmac(String hmac) {
        this.hmac = hmac != null ? hmac : "";
    }

    @Override
    public String toString() {
        return "HandshakeAuthenticatePacket{" +
                "clientId='" + clientId + '\'' +
                ", clientNonce='" + clientNonce + '\'' +
                ", hmac='" + hmac + '\'' +
                '}';
    }
}
