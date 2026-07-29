package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.PlatformType;
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

    public HandshakePacket() {
        super();
    }

    public HandshakePacket(UUID requestId) {
        super(requestId);
    }

    public HandshakePacket(int protocolVersion, String clientId, String passwordHash, PlatformType platform) {
        super();
        this.protocolVersion = protocolVersion;
        this.clientId = clientId;
        this.passwordHash = passwordHash;
        this.platform = platform;
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
    }

    @Override
    public void read(ByteBuf buf) {
        protocolVersion = PacketBuffer.readVarInt(buf);
        clientId = PacketBuffer.readString(buf, 64);
        passwordHash = PacketBuffer.readString(buf, 256);
        int platformId = buf.readUnsignedByte();
        platform = PlatformType.fromId(platformId);
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

    @Override
    public String toString() {
        return "HandshakePacket{" +
                "protocolVersion=" + protocolVersion +
                ", clientId='" + clientId + '\'' +
                ", platform=" + platform +
                '}';
    }
}
