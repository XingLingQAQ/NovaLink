package com.nova.chat.common.protocol.codec;

import com.nova.chat.common.protocol.VarInt;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.CorruptedFrameException;

/**
 * Prepends a VarInt length field to outgoing packets.
 * This encoder adds packet boundary markers for NovaProtocol.
 * 
 * Frame format: [Length (VarInt)] [Packet Data]
 */
public class Varint21LengthFieldPrepender extends MessageToByteEncoder<ByteBuf> {

    private static final int MAX_FRAME_LENGTH = 4 * 1024 * 1024; // 4 MiB (must match decoder)

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        int bodyLength = msg.readableBytes();
        if (bodyLength < 0 || bodyLength > MAX_FRAME_LENGTH) {
            throw new CorruptedFrameException("Outbound frame too large: " + bodyLength + " (max=" + MAX_FRAME_LENGTH + ")");
        }
        int headerLength = VarInt.getVarIntSize(bodyLength);

        out.ensureWritable(headerLength + bodyLength);
        VarInt.writeVarInt(out, bodyLength);
        out.writeBytes(msg);
    }
}
