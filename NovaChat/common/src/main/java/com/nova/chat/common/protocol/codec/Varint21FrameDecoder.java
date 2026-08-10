package com.nova.chat.common.protocol.codec;

import com.nova.chat.common.protocol.VarInt;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;

/**
 * Decodes incoming bytes into frames based on VarInt length prefix.
 * This decoder handles packet boundary detection for NovaProtocol.
 * 
 * Frame format: [Length (VarInt)] [Packet Data]
 */
public class Varint21FrameDecoder extends ByteToMessageDecoder {

    /**
     * Hard limit for a single frame size to prevent memory exhaustion attacks.
     *
     * NovaProtocol packets are expected to be small (chat/commands/config diffs).
     * Keep this large enough for future extensions, but bounded.
     */
    private static final int MAX_FRAME_LENGTH = 4 * 1024 * 1024; // 4 MiB

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        in.markReaderIndex();

        // Try to read the length prefix
        int length = readVarIntOrReset(in);
        if (length == -1) {
            return; // Not enough bytes for length
        }

        if (length < 0 || length > MAX_FRAME_LENGTH) {
            throw new CorruptedFrameException("Invalid frame length: " + length + " (max=" + MAX_FRAME_LENGTH + ")");
        }

        // Check if we have enough bytes for the packet
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        // Read the packet data
        out.add(in.readRetainedSlice(length));
    }

    /**
     * Attempts to read a VarInt from the buffer.
     * If not enough bytes are available, resets the reader index and returns -1.
     *
     * @param buf the buffer to read from
     * @return the decoded VarInt, or -1 if not enough bytes
     */
    private int readVarIntOrReset(ByteBuf buf) {
        int value = 0;
        int position = 0;

        while (buf.isReadable()) {
            byte currentByte = buf.readByte();
            value |= (currentByte & 0x7F) << position;

            if ((currentByte & 0x80) == 0) {
                return value;
            }

            position += 7;

            if (position >= 32) {
                throw new CorruptedFrameException("VarInt length is too big");
            }
        }

        // Not enough bytes
        buf.resetReaderIndex();
        return -1;
    }
}
