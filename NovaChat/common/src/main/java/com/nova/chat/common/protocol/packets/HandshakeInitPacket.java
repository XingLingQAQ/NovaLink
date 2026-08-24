package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Handshake init packet — first packet of the AUTH-002 challenge-response
 * handshake (Client → Server).
 *
 * <p>Replaces the replayable static-hash {@code HandshakePacket}. The client
 * sends a fresh random nonce; the server replies with
 * {@link HandshakeChallengePacket} carrying its own nonce, and the client
 * proves possession of the password hash via an HMAC in
 * {@link HandshakeAuthenticatePacket}.
 *
 * <p><b>Wire format (payload only; envelope is written by {@link Packet#encode}):</b>
 * <pre>
 *   VarInt  protocolVersion   (== NovaProtocol.PROTOCOL_VERSION, currently 3)
 *   String  clientId          (≤ 64, VarInt-length UTF-8)
 *   u8      platform          (PlatformType.getId() — matches non-JVM forks)
 *   String  serverVersion     (≤ 64)
 *   String  clientNonce       (≤ 64, 16 random bytes lowercase-hex = 32 chars)
 * </pre>
 *
 * <p>Field order is fixed. The platform byte uses {@link PlatformType#getId()}
 * (not {@code ordinal()}) so it matches the legacy {@code HandshakePacket}
 * wire encoding that the PHP/Python/C++ clients already mirror — FOLIA (id 13)
 * and SPONGE (id 14) would otherwise diverge from {@code ordinal()}.
 *
 * Packet ID: 0x15
 * Direction: Client → Server
 */
public class HandshakeInitPacket extends Packet {

    private int protocolVersion;
    private String clientId;
    private PlatformType platform;
    private String serverVersion;
    private String clientNonce;

    public HandshakeInitPacket() {
        super();
    }

    public HandshakeInitPacket(UUID requestId) {
        super(requestId);
    }

    public HandshakeInitPacket(int protocolVersion, String clientId, PlatformType platform,
                               String serverVersion, String clientNonce) {
        super();
        this.protocolVersion = protocolVersion;
        this.clientId = clientId != null ? clientId : "";
        this.platform = platform;
        this.serverVersion = serverVersion != null ? serverVersion : "";
        this.clientNonce = clientNonce != null ? clientNonce : "";
    }

    @Override
    public int getPacketId() {
        return PacketIds.HANDSHAKE_INIT;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeVarInt(buf, protocolVersion);
        PacketBuffer.writeString(buf, clientId);
        // Use getId() for wire-level parity with the legacy HandshakePacket and
        // the non-JVM forks; unknown/missing platform defaults to BUKKIT (id 0).
        buf.writeByte(platform != null ? platform.getId() : PlatformType.BUKKIT.getId());
        PacketBuffer.writeString(buf, serverVersion);
        PacketBuffer.writeString(buf, clientNonce);
    }

    @Override
    public void read(ByteBuf buf) {
        protocolVersion = PacketBuffer.readVarInt(buf);
        clientId = PacketBuffer.readString(buf, ProtocolLimits.MAX_CLIENT_ID);
        int platformId = buf.readUnsignedByte();
        platform = PlatformType.fromId(platformId);
        // Trailing optional fields: tolerate an old peer that stopped writing
        // before serverVersion/clientNonce so a partial frame does not throw.
        if (buf.isReadable()) {
            serverVersion = PacketBuffer.readString(buf, ProtocolLimits.MAX_SERVER_VERSION);
        } else {
            serverVersion = "";
        }
        if (buf.isReadable()) {
            clientNonce = PacketBuffer.readString(buf, ProtocolLimits.MAX_NONCE);
        } else {
            clientNonce = "";
        }
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public PlatformType getPlatform() {
        return platform;
    }

    public void setPlatform(PlatformType platform) {
        this.platform = platform;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion != null ? serverVersion : "";
    }

    public String getClientNonce() {
        return clientNonce;
    }

    public void setClientNonce(String clientNonce) {
        this.clientNonce = clientNonce != null ? clientNonce : "";
    }

    @Override
    public String toString() {
        return "HandshakeInitPacket{" +
                "protocolVersion=" + protocolVersion +
                ", clientId='" + clientId + '\'' +
                ", platform=" + platform +
                ", serverVersion='" + serverVersion + '\'' +
                ", clientNonce='" + clientNonce + '\'' +
                '}';
    }
}
