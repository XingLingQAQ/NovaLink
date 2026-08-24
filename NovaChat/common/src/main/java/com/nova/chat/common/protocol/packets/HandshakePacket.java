package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Handshake packet sent by client to authenticate with the backend.
 * 
 * Packet ID: 0x01
 * Direction: Client → Server
 */
public class HandshakePacket extends Packet {

    /** Protocol version for compatibility checking */
    private int protocolVersion;
    
    /** Client identifier (username) */
    private String clientId;
    
    /** SHA-256 hash of the password */
    private String passwordHash;
    
    /** Platform type of the client */
    private PlatformType platform;

    /** Minecraft server version reported by the client (e.g. "1.20.4"). */
    private String serverVersion;

    public HandshakePacket() {
        super();
    }

    public HandshakePacket(UUID requestId) {
        super(requestId);
    }

    public HandshakePacket(int protocolVersion, String clientId, String passwordHash, PlatformType platform) {
        this(protocolVersion, clientId, passwordHash, platform, "");
    }

    public HandshakePacket(int protocolVersion, String clientId, String passwordHash,
                           PlatformType platform, String serverVersion) {
        super();
        this.protocolVersion = protocolVersion;
        this.clientId = clientId;
        this.passwordHash = passwordHash;
        this.platform = platform;
        this.serverVersion = serverVersion != null ? serverVersion : "";
    }

    @Override
    public int getPacketId() {
        return PacketIds.HANDSHAKE;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeVarInt(buf, protocolVersion);
        PacketBuffer.writeString(buf, clientId != null ? clientId : "");
        PacketBuffer.writeString(buf, passwordHash != null ? passwordHash : "");
        // Unknown/missing platform defaults to BUKKIT (id 0) for wire stability
        buf.writeByte(platform != null ? platform.getId() : PlatformType.BUKKIT.getId());
        // Trailing optional field (protocol v2+): server Minecraft version.
        // Always written by v2 clients; old v1 backends never reach here because
        // PROTOCOL_VERSION mismatch is rejected before read() matters.
        PacketBuffer.writeString(buf, serverVersion != null ? serverVersion : "");
    }

    @Override
    public void read(ByteBuf buf) {
        protocolVersion = PacketBuffer.readVarInt(buf);
        // Bound field sizes to protocol limits (PROTO-003): the inline numeric
        // literals now reference ProtocolLimits so non-JVM forks mirror the
        // same values. Behavior is unchanged (same max lengths).
        clientId = PacketBuffer.readString(buf, ProtocolLimits.MAX_CLIENT_ID);
        passwordHash = PacketBuffer.readString(buf, ProtocolLimits.MAX_PASSWORD_HASH);
        int platformId = buf.readUnsignedByte();
        platform = PlatformType.fromId(platformId);
        // Optional trailing field: old clients/backends may not send serverVersion.
        if (buf.isReadable()) {
            serverVersion = PacketBuffer.readString(buf, ProtocolLimits.MAX_SERVER_VERSION);
        } else {
            serverVersion = "";
        }
    }


    // Getters and setters

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

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
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

    @Override
    public String toString() {
        return "HandshakePacket{" +
                "protocolVersion=" + protocolVersion +
                ", clientId='" + clientId + '\'' +
                ", platform=" + platform +
                ", serverVersion='" + serverVersion + '\'' +
                '}';
    }
}
