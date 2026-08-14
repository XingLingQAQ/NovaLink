package com.nova.chat.common.protocol.codec;

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
 * Regression tests for the {@link Varint21FrameDecoder} length-prefix sentinel:
 * a decoded length of -1 (5-byte VarInt FF FF FF FF 0F) used to collide with the
 * internal "not enough bytes" marker, silently consuming five bytes and
 * desynchronizing the stream. Any negative length must throw
 * {@link CorruptedFrameException} instead.
 */
@DisplayName("Varint21FrameDecoder negative length handling")
class FrameDecoderNegativeLengthTest {

    @Test
    @DisplayName("length -1 (FF FF FF FF 0F) throws CorruptedFrameException")
    void minusOneLengthIsCorrupted() {
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        ByteBuf input = Unpooled.wrappedBuffer(new byte[]{
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x0F
        });

        assertThatThrownBy(() -> channel.writeInbound(input))
                .isInstanceOf(CorruptedFrameException.class)
                .hasMessageContaining("Invalid frame length: -1");
        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("other negative lengths still throw CorruptedFrameException")
    void otherNegativeLengthIsCorrupted() {
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        // -2 encoded as 5-byte VarInt: FE FF FF FF 0F
        ByteBuf input = Unpooled.wrappedBuffer(new byte[]{
                (byte) 0xFE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x0F
        });

        assertThatThrownBy(() -> channel.writeInbound(input))
                .isInstanceOf(CorruptedFrameException.class)
                .hasMessageContaining("Invalid frame length: -2");
        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("incomplete VarInt prefix waits without consuming bytes")
    void incompleteVarIntWaitsWithoutConsuming() {
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());

        // First byte of the two-byte VarInt for 300 (0xAC 0x02): incomplete.
        assertThat(channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{(byte) 0xAC}))).isFalse();
        assertThat((Object) channel.readInbound()).isNull();

        // Completing the prefix plus payload must yield the full 300-byte frame,
        // proving the partial prefix byte was not consumed and lost.
        byte[] payload = new byte[300];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        ByteBuf rest = Unpooled.buffer();
        rest.writeByte(0x02);
        rest.writeBytes(payload);

        assertThat(channel.writeInbound(rest)).isTrue();
        ByteBuf frame = channel.readInbound();
        try {
            assertThat(frame.readableBytes()).isEqualTo(300);
            byte[] out = new byte[300];
            frame.readBytes(out);
            assertThat(out).containsExactly(payload);
        } finally {
            frame.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("normal frame still decodes after the sentinel fix")
    void normalFrameUnaffected() {
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        byte[] payload = {0x0A, 0x0B, 0x0C, 0x0D};
        ByteBuf input = Unpooled.buffer();
        VarInt.writeVarInt(input, payload.length);
        input.writeBytes(payload);

        assertThat(channel.writeInbound(input)).isTrue();
        ByteBuf frame = channel.readInbound();
        try {
            assertThat(frame.readableBytes()).isEqualTo(4);
            byte[] out = new byte[4];
            frame.readBytes(out);
            assertThat(out).containsExactly(payload);
        } finally {
            frame.release();
            channel.finishAndReleaseAll();
        }
    }
}
