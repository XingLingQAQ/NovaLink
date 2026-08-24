package com.nova.chat.common.protocol.codec;

import com.nova.chat.common.protocol.ProtocolLimits;
import com.nova.chat.common.protocol.VarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PROTO-002 boundary tests for {@link Varint21FrameDecoder}.
 *
 * <p>The audit doc (PROTO-002 line 287) requires cross-language golden tests
 * covering {@code 1 MiB}, {@code 4 MiB} (the exact ceiling) and an over-limit
 * value. The non-JVM side is a separate deferred slice; this is the Java
 * half. It asserts:
 * <ul>
 *   <li>a 1 MiB frame decodes;</li>
 *   <li>a 4 MiB frame (exactly {@link ProtocolLimits#MAX_FRAME_LENGTH})
 *       decodes;</li>
 *   <li>a 4 MiB + 1 frame is rejected with {@link CorruptedFrameException}.</li>
 * </ul>
 *
 * <p>These tests deliberately allocate multi-MiB buffers, so they are kept as
 * plain {@code @Test} methods (not property-based) and release their buffers
 * in {@code finally} blocks.
 */
@DisplayName("Varint21FrameDecoder frame-limit boundaries (PROTO-002)")
class FrameLimitBoundaryTest {

    private static final int ONE_MIB = 1024 * 1024;

    @Test
    @DisplayName("1 MiB frame is accepted")
    void oneMibFrameAccepted() {
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        ByteBuf input = Unpooled.buffer();
        VarInt.writeVarInt(input, ONE_MIB);
        byte[] payload = new byte[ONE_MIB];
        // Non-zero so the slice is not optimized away.
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        input.writeBytes(payload);

        try {
            assertThat(channel.writeInbound(input)).isTrue();
            ByteBuf frame = channel.readInbound();
            try {
                assertThat(frame.readableBytes()).isEqualTo(ONE_MIB);
            } finally {
                frame.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("4 MiB frame (exact MAX_FRAME_LENGTH boundary) is accepted")
    void fourMibFrameAccepted() {
        int length = ProtocolLimits.MAX_FRAME_LENGTH;
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        ByteBuf input = Unpooled.buffer();
        VarInt.writeVarInt(input, length);
        // Write the payload in chunks to avoid one giant temp byte[].
        byte[] chunk = new byte[64 * 1024];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (i & 0xFF);
        }
        int remaining = length;
        while (remaining > 0) {
            int n = Math.min(chunk.length, remaining);
            input.writeBytes(chunk, 0, n);
            remaining -= n;
        }

        try {
            assertThat(channel.writeInbound(input)).isTrue();
            ByteBuf frame = channel.readInbound();
            try {
                assertThat(frame.readableBytes()).isEqualTo(length);
            } finally {
                frame.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("frame of MAX_FRAME_LENGTH + 1 is rejected (CorruptedFrameException)")
    void overLimitFrameRejected() {
        int length = ProtocolLimits.MAX_FRAME_LENGTH + 1;
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        ByteBuf input = Unpooled.buffer();
        VarInt.writeVarInt(input, length);

        try {
            assertThatThrownBy(() -> channel.writeInbound(input))
                    .isInstanceOf(CorruptedFrameException.class)
                    .hasMessageContaining("Invalid frame length")
                    .hasMessageContaining("max=" + ProtocolLimits.MAX_FRAME_LENGTH);
        } finally {
            // writeInbound releases the inbound buffer on the exception path;
            // only release if still referenced to avoid IllegalReferenceCountException.
            if (input.refCnt() > 0) {
                input.release();
            }
            channel.finishAndReleaseAll();
        }
    }
}
