package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/** Server-to-client monitoring delivery: target UUID followed by a chat payload. */
public class SpyMessagePacket extends Packet {
    private UUID targetId;
    private ChatMessagePacket message = new ChatMessagePacket();

    public SpyMessagePacket() {
    }

    public SpyMessagePacket(UUID targetId, ChatMessagePacket message) {
        super(message.getRequestId());
        this.targetId = targetId;
        this.message = message;
    }

    @Override
    public int getPacketId() {
        return PacketIds.SPY_MESSAGE;
    }

    @Override
    public void write(ByteBuf buf) {
        PacketBuffer.writeUUID(buf, targetId);
        message.write(buf);
    }

    @Override
    public void read(ByteBuf buf) {
        targetId = PacketBuffer.readUUID(buf);
        message = new ChatMessagePacket();
        message.read(buf);
    }

    public UUID getTargetId() {
        return targetId;
    }

    public ChatMessagePacket getMessage() {
        return message;
    }
}
