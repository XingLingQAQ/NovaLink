package com.nova.chat.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Detailed VarInt encode/decode and size tests covering boundary values
 * used by NovaProtocol frame length prefixes.
 */
@DisplayName("VarInt detailed")
class VarIntDetailedTest {

    @ParameterizedTest(name = "value={0} size={1}")
    @CsvSource({
            "0, 1",
            "1, 1",
            "127, 1",
            "128, 2",
            "16383, 2",
            "16384, 3",
            "2097151, 3",
            "2097152, 4",
            "268435455, 4",
            "268435456, 5",
            "-1, 5"
    })
    @DisplayName("getVarIntSize matches expected widths")
    void size(int value, int expectedSize) {
        assertThat(VarInt.getVarIntSize(value)).isEqualTo(expectedSize);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 127, 128, 255, 300, 16383, 16384, 1_000_000, Integer.MAX_VALUE, -1, Integer.MIN_VALUE})
    @DisplayName("ByteBuf round-trip for representative values")
    void byteBufRoundTrip(int value) {
        ByteBuf buf = Unpooled.buffer();
        try {
            VarInt.writeVarInt(buf, value);
            assertThat(buf.readableBytes()).isEqualTo(VarInt.getVarIntSize(value));
            assertThat(VarInt.readVarInt(buf)).isEqualTo(value);
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 127, 128, 30000, -50, Integer.MAX_VALUE})
    @DisplayName("byte[] encode/decode round-trip")
    void arrayRoundTrip(int value) {
        byte[] encoded = VarInt.encode(value);
        assertThat(encoded).hasSize(VarInt.getVarIntSize(value));
        assertThat(VarInt.decode(encoded)).isEqualTo(value);
    }

    @Test
    @DisplayName("overlong VarInt (>5 bytes) is rejected on read")
    void overlongRejected() {
        ByteBuf buf = Unpooled.buffer();
        try {
            // 5 continuation bytes + would need a 6th -> too big at position>=32
            for (int i = 0; i < 5; i++) {
                buf.writeByte(0x80);
            }
            buf.writeByte(0x01);
            assertThatThrownBy(() -> VarInt.readVarInt(buf))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too big");
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("encode then writeVarInt produce identical bytes")
    void encodeMatchesWrite() {
        int value = 300000;
        byte[] encoded = VarInt.encode(value);
        ByteBuf buf = Unpooled.buffer();
        try {
            VarInt.writeVarInt(buf, value);
            byte[] written = new byte[buf.readableBytes()];
            buf.readBytes(written);
            assertThat(written).containsExactly(encoded);
        } finally {
            buf.release();
        }
    }
}
