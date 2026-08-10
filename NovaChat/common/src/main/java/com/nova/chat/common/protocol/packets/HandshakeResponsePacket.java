package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Handshake response packet sent by server after authentication attempt.
 * 
 * Packet ID: 0x02
 * Direction: Server → Client
 */
public class HandshakeResponsePacket extends Packet {

    /** Whether authentication was successful */
    private boolean success;
    
    /** Error code if authentication failed (e.g., "NC-401") */
    private String errorCode;
    
    /** Human-readable message */
    private String message;

    public HandshakeResponsePacket() {
        super();
    }

    public HandshakeResponsePacket(UUID requestId) {
        super(requestId);
    }

    public HandshakeResponsePacket(boolean success, String errorCode, String message) {
        super();
        this.success = success;
        this.errorCode = errorCode != null ? errorCode : "";
        this.message = message != null ? message : "";
    }

    /**
     * Creates a successful response.
     */
    public static HandshakeResponsePacket success(String message) {
        return new HandshakeResponsePacket(true, "", message);
    }

    /**
     * Creates a failure response with error code.
     */
    public static HandshakeResponsePacket failure(String errorCode, String message) {
        return new HandshakeResponsePacket(false, errorCode, message);
    }

    @Override
    public int getPacketId() {
        return PacketIds.HANDSHAKE_RESPONSE;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeBoolean(buf, success);
        PacketBuffer.writeString(buf, errorCode);
        PacketBuffer.writeString(buf, message);
    }

    @Override
    public void read(ByteBuf buf) {
        success = PacketBuffer.readBoolean(buf);
        errorCode = PacketBuffer.readString(buf);
        message = PacketBuffer.readString(buf);
    }


    // Getters and setters

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "HandshakeResponsePacket{" +
                "success=" + success +
                ", errorCode='" + errorCode + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
