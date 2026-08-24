package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Admin action response packet.
 * 
 * Packet ID: 0x0C (new ID for response)
 * Direction: Server → Client
 * 
 * Requirements:
 * - 2.2: Super admin authentication response
 */
public class AdminActionResponsePacket extends Packet {

    /** The action this is responding to */
    private AdminAction action;
    
    /** Whether the action was successful */
    private boolean success;
    
    /** Error code (if failed) */
    private String errorCode;
    
    /** Response message */
    private String message;

    public AdminActionResponsePacket() {
        super();
    }

    public AdminActionResponsePacket(UUID requestId) {
        super(requestId);
    }

    /**
     * Creates a successful response.
     *
     * @param action  the action that was performed
     * @param message the success message
     * @return the response packet
     */
    public static AdminActionResponsePacket success(AdminAction action, String message) {
        AdminActionResponsePacket packet = new AdminActionResponsePacket();
        packet.action = action;
        packet.success = true;
        packet.errorCode = "";
        packet.message = message;
        return packet;
    }

    /**
     * Creates a failed response.
     *
     * @param action    the action that failed
     * @param errorCode the error code
     * @param message   the error message
     * @return the response packet
     */
    public static AdminActionResponsePacket failure(AdminAction action, String errorCode, String message) {
        AdminActionResponsePacket packet = new AdminActionResponsePacket();
        packet.action = action;
        packet.success = false;
        packet.errorCode = errorCode;
        packet.message = message;
        return packet;
    }

    @Override
    public int getPacketId() {
        // Using 0x0C for admin action response
        return 0x0C;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeByte(action != null ? action.getId() : 0);
        buf.writeBoolean(success);
        PacketBuffer.writeString(buf, errorCode != null ? errorCode : "");
        PacketBuffer.writeString(buf, message != null ? message : "");
    }

    @Override
    public void read(ByteBuf buf) {
        action = AdminAction.fromId(buf.readByte());
        success = buf.readBoolean();
        // PROTO-003: bound each field so a single oversized string cannot
        // approach the 4 MiB frame ceiling.
        errorCode = PacketBuffer.readString(buf, ProtocolLimits.MAX_ERROR_CODE);
        message = PacketBuffer.readString(buf, ProtocolLimits.MAX_ERROR_MESSAGE);
    }

    // Getters and setters

    public AdminAction getAction() {
        return action;
    }

    public void setAction(AdminAction action) {
        this.action = action;
    }

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
        return "AdminActionResponsePacket{" +
                "action=" + action +
                ", success=" + success +
                ", errorCode='" + errorCode + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
