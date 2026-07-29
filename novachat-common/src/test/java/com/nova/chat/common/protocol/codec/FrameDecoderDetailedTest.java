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
 * Detailed tests for {@link Varint21FrameDecoder}: framing, partial reads,
 * and hard size limits that protect the backend from memory exhaustion.
 */
@DisplayName("Varint21FrameDecoder detailed")
class FrameDecoderDetailedTest {

    @Test
    @DisplayName("single complete frame is emitted")
    void singleFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        byte[] payload = {0x01, 0x02, 0x03};
        ByteBuf input = Unpooled.buffer();
        VarInt.writeVarInt(input, payload.length);
        input.writeBytes(payload);

        assertThat(channel.writeInbound(input)).isTrue();
        ByteBuf frame = channel.readInbound();
        try {
            assertThat(frame.readableBytes()).isEqualTo(3);
            byte[] out = new byte[3];
            frame.readBytes(out);
            assertThat(out).containsExactly(payload);
        } finally {
            frame.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("partial length waits for more bytes")
    void partialLength() {
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        // Start of a multi-byte VarInt length without completing it
        ByteBuf partial = Unpooled.buffer().writeByte(0x80);
        assertThat(channel.writeInbound(partial)).isFalse();
        assertThat((Object) channel.readInbound()).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("partial body waits for remaining payload")
    void partialBody() {
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        ByteBuf input = Unpooled.buffer();
        VarInt.writeVarInt(input, 4);
        input.writeBytes(new byte[]{1, 2}); // only 2 of 4

        assertThat(channel.writeInbound(input)).isFalse();
        assertThat((Object) channel.readInbound()).isNull();

        // deliver the rest
        assertThat(channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{3, 4}))).isTrue();
        ByteBuf frame = channel.readInbound();
        try {
            assertThat(frame.readableBytes()).isEqualTo(4);
        } finally {
            frame.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("two frames back-to-back are split correctly")
    void twoFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        ByteBuf input = Unpooled.buffer();
        VarInt.writeVarInt(input, 1);
        input.writeByte(0x0A);
        VarInt.writeVarInt(input, 2);
        input.writeBytes(new byte[]{0x0B, 0x0C});

        channel.writeInbound(input);
        ByteBuf f1 = channel.readInbound();
        ByteBuf f2 = channel.readInbound();
        try {
            assertThat(f1.readableBytes()).isEqualTo(1);
            assertThat(f1.readByte()).isEqualTo((byte) 0x0A);
            assertThat(f2.readableBytes()).isEqualTo(2);
        } finally {
            f1.release();
            f2.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("frame larger than 4 MiB is rejected")
    void oversizedFrameRejected() {
        EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder());
        ByteBuf input = Unpooled.buffer();
        // Declare absurd length (5 MiB)
        VarInt.writeVarInt(input, 5 * 1024 * 1024);

        assertThatThrownBy(() -> channel.writeInbound(input))
                .isInstanceOf(CorruptedFrameException.class)
                .hasMessageContaining("Invalid frame length");
        channel.finishAndReleaseAll();
    }
}
