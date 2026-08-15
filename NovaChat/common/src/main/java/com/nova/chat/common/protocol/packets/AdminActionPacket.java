package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Admin action packet for administrative operations.
 * 
 * Packet ID: 0x0B
 * Direction: Client → Server
 * 
 * Requirements:
 * - 2.2: Super admin authentication via `/nc auth <password>`
 */
public class AdminActionPacket extends Packet {

    /** The admin action to perform */
    private AdminAction action;
    
    /** Player UUID performing the action */
    private UUID playerId;
    
    /** Password hash (for AUTH action) */
    private String passwordHash;
    
    /** Target (channel ID for spy, server name, etc.) */
    private String target;
    
    /** Extra data for the action */
    private Map<String, String> extra;

    public AdminActionPacket() {
        super();
        this.extra = new HashMap<>();
    }

    public AdminActionPacket(UUID requestId) {
        super(requestId);
        this.extra = new HashMap<>();
    }

    /**
     * Creates an AUTH action packet for super admin authentication.
     *
     * @param playerId     the player UUID
     * @param passwordHash the SHA-256 password hash
     * @return the admin action packet
     */
    public static AdminActionPacket createAuthPacket(UUID playerId, String passwordHash) {
        AdminActionPacket packet = new AdminActionPacket();
        packet.action = AdminAction.AUTH;
        packet.playerId = playerId;
        packet.passwordHash = passwordHash;
        packet.target = "";
        return packet;
    }

    /**
     * Creates a LOGOUT action packet to revoke super admin session.
     *
     * @param playerId the player UUID
     * @return the admin action packet
     */
    public static AdminActionPacket createLogoutPacket(UUID playerId) {
        AdminActionPacket packet = new AdminActionPacket();
        packet.action = AdminAction.LOGOUT;
        packet.playerId = playerId;
        packet.passwordHash = "";
        packet.target = "";
        return packet;
    }

    /**
     * Creates a SPY_START action packet.
     *
     * @param playerId  the player UUID
     * @param channelId the channel to spy on
     * @return the admin action packet
     */
    public static AdminActionPacket createSpyStartPacket(UUID playerId, String channelId) {
        AdminActionPacket packet = new AdminActionPacket();
        packet.action = AdminAction.SPY_START;
        packet.playerId = playerId;
        packet.passwordHash = "";
        packet.target = channelId;
        return packet;
    }

    @Override
    public int getPacketId() {
        return PacketIds.ADMIN_ACTION;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeByte(action.getId());
        PacketBuffer.writeUUID(buf, playerId);
        PacketBuffer.writeString(buf, passwordHash != null ? passwordHash : "");
        PacketBuffer.writeString(buf, target != null ? target : "");
        
        // Write extra map
        PacketBuffer.writeVarInt(buf, extra.size());
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            PacketBuffer.writeString(buf, entry.getKey());
            PacketBuffer.writeString(buf, entry.getValue());
        }
    }

    @Override
    public void read(ByteBuf buf) {
        action = AdminAction.fromId(buf.readByte());
        playerId = PacketBuffer.readUUID(buf);
        passwordHash = PacketBuffer.readString(buf);
        target = PacketBuffer.readString(buf);

        // Read extra map with a size guard matching ChannelActionPacket so a
        // malformed/garbled size cannot cause an oversized allocation.
        int size = PacketBuffer.readVarInt(buf);
        if (size < 0 || size > 64) {
            extra = new HashMap<>();
            return;
        }
        extra = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = PacketBuffer.readString(buf);
            String value = PacketBuffer.readString(buf);
            extra.put(key, value);
        }
    }

    // Getters and setters

    public AdminAction getAction() {
        return action;
    }

    public void setAction(AdminAction action) {
        this.action = action;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public Map<String, String> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, String> extra) {
        this.extra = extra != null ? extra : new HashMap<>();
    }

    public void addExtra(String key, String value) {
        this.extra.put(key, value);
    }

    public String getExtra(String key) {
        return this.extra.get(key);
    }

    @Override
    public String toString() {
        return "AdminActionPacket{" +
                "action=" + action +
                ", playerId=" + playerId +
                ", passwordHash='" + (passwordHash != null && !passwordHash.isEmpty() ? "<hidden>" : "<none>") + '\'' +
                ", target='" + target + '\'' +
                ", extra=" + extra +
                '}';
    }
}
