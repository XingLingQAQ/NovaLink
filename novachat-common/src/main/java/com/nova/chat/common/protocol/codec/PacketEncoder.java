package com.nova.chat.common.protocol.codec;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Encodes Packet objects into ByteBuf using the PacketRegistry.
 */
public class PacketEncoder extends MessageToByteEncoder<Packet> {

    private final PacketRegistry registry;

    public PacketEncoder(PacketRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Packet msg, ByteBuf out) throws Exception {
        if (msg == null) {
            return;
        }

        Boolean legacy = ctx.channel().attr(ProtocolAttributes.LEGACY_NO_REQUEST_ID).get();
        if (Boolean.TRUE.equals(legacy)) {
            // Legacy frame: | packetId | payload |
            out.writeByte(msg.getPacketId());
            msg.write(out);
            return;
        }

        // Modern frame: | packetId | requestId | payload |
        registry.encode(msg, out);
    }
}
