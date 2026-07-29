package com.nova.chat.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boundary value tests for VarInt encoding/decoding.
 * Tests critical boundary values: 0, 127, 128, 16383, 16384, max values.
 * 
 * VarInt encoding uses 7 bits per byte with MSB as continuation flag:
 * - 1 byte: 0 to 127 (0x00 to 0x7F)
 * - 2 bytes: 128 to 16383 (0x80 to 0x3FFF)
 * - 3 bytes: 16384 to 2097151 (0x4000 to 0x1FFFFF)
 * - 4 bytes: 2097152 to 268435455 (0x200000 to 0xFFFFFFF)
 * - 5 bytes: 268435456 to max int (0x10000000 to 0x7FFFFFFF) and negative values
 * 
 * _Requirements: 21.3_
 */
class VarIntBoundaryTest {

    private ByteBuf buf;

    @BeforeEach
    void setUp() {
        buf = Unpooled.buffer();
    }

    @AfterEach
    void tearDown() {
        if (buf != null) {
            buf.release();
        }
    }

    // ==================== 1-Byte Boundary Tests ====================

    @Nested
    @DisplayName("1-Byte Boundary (0 to 127)")
    class OneByteTests {

        @Test
        @DisplayName("Should encode 0 as single byte")
        void shouldEncodeZeroAsSingleByte() {
            byte[] encoded = VarInt.encode(0);
            
            assertThat(encoded).hasSize(1);
            assertThat(encoded[0]).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("Should decode 0 correctly")
        void shouldDecodeZeroCorrectly() {
            VarInt.writeVarInt(buf, 0);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isZero();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should encode 127 as single byte")
        void shouldEncode127AsSingleByte() {
            byte[] encoded = VarInt.encode(127);
            
            assertThat(encoded).hasSize(1);
            assertThat(encoded[0]).isEqualTo((byte) 0x7F);
        }

        @Test
        @DisplayName("Should decode 127 correctly")
        void shouldDecode127Correctly() {
            VarInt.writeVarInt(buf, 127);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(127);
        }

        @Test
        @DisplayName("Should report size 1 for values 0-127")
        void shouldReportSize1ForSmallValues() {
            assertThat(VarInt.getVarIntSize(0)).isEqualTo(1);
            assertThat(VarInt.getVarIntSize(1)).isEqualTo(1);
            assertThat(VarInt.getVarIntSize(63)).isEqualTo(1);
            assertThat(VarInt.getVarIntSize(127)).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 63, 64, 126, 127})
        @DisplayName("Should round-trip all 1-byte boundary values")
        void shouldRoundTripOneByteValues(int value) {
            VarInt.writeVarInt(buf, value);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(value);
            assertThat(VarInt.getVarIntSize(value)).isEqualTo(1);
        }
    }

    // ==================== 2-Byte Boundary Tests ====================

    @Nested
    @DisplayName("2-Byte Boundary (128 to 16383)")
    class TwoByteTests {

        @Test
        @DisplayName("Should encode 128 as two bytes")
        void shouldEncode128AsTwoBytes() {
            byte[] encoded = VarInt.encode(128);
            
            assertThat(encoded).hasSize(2);
            // 128 = 0x80 = 0b10000000
            // VarInt: 0b10000000 0b00000001 = 0x80 0x01
            assertThat(encoded[0]).isEqualTo((byte) 0x80);
            assertThat(encoded[1]).isEqualTo((byte) 0x01);
        }

        @Test
        @DisplayName("Should decode 128 correctly")
        void shouldDecode128Correctly() {
            VarInt.writeVarInt(buf, 128);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(128);
        }

        @Test
        @DisplayName("Should encode 16383 as two bytes")
        void shouldEncode16383AsTwoBytes() {
            byte[] encoded = VarInt.encode(16383);
            
            assertThat(encoded).hasSize(2);
            // 16383 = 0x3FFF = 0b11111111111111
            // VarInt: 0b11111111 0b01111111 = 0xFF 0x7F
            assertThat(encoded[0]).isEqualTo((byte) 0xFF);
            assertThat(encoded[1]).isEqualTo((byte) 0x7F);
        }

        @Test
        @DisplayName("Should decode 16383 correctly")
        void shouldDecode16383Correctly() {
            VarInt.writeVarInt(buf, 16383);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(16383);
        }

        @Test
        @DisplayName("Should report size 2 for values 128-16383")
        void shouldReportSize2ForMediumValues() {
            assertThat(VarInt.getVarIntSize(128)).isEqualTo(2);
            assertThat(VarInt.getVarIntSize(255)).isEqualTo(2);
            assertThat(VarInt.getVarIntSize(1000)).isEqualTo(2);
            assertThat(VarInt.getVarIntSize(16383)).isEqualTo(2);
        }

        @ParameterizedTest
        @ValueSource(ints = {128, 129, 255, 256, 1000, 8191, 8192, 16382, 16383})
        @DisplayName("Should round-trip all 2-byte boundary values")
        void shouldRoundTripTwoByteValues(int value) {
            VarInt.writeVarInt(buf, value);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(value);
            assertThat(VarInt.getVarIntSize(value)).isEqualTo(2);
        }
    }

    // ==================== 3-Byte Boundary Tests ====================

    @Nested
    @DisplayName("3-Byte Boundary (16384 to 2097151)")
    class ThreeByteTests {

        @Test
        @DisplayName("Should encode 16384 as three bytes")
        void shouldEncode16384AsThreeBytes() {
            byte[] encoded = VarInt.encode(16384);
            
            assertThat(encoded).hasSize(3);
            // 16384 = 0x4000 = 0b100000000000000
            // VarInt: 0b10000000 0b10000000 0b00000001 = 0x80 0x80 0x01
            assertThat(encoded[0]).isEqualTo((byte) 0x80);
            assertThat(encoded[1]).isEqualTo((byte) 0x80);
            assertThat(encoded[2]).isEqualTo((byte) 0x01);
        }

        @Test
        @DisplayName("Should decode 16384 correctly")
        void shouldDecode16384Correctly() {
            VarInt.writeVarInt(buf, 16384);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(16384);
        }

        @Test
        @DisplayName("Should encode 2097151 as three bytes")
        void shouldEncode2097151AsThreeBytes() {
            byte[] encoded = VarInt.encode(2097151);
            
            assertThat(encoded).hasSize(3);
            // 2097151 = 0x1FFFFF = max 3-byte value
        }

        @Test
        @DisplayName("Should decode 2097151 correctly")
        void shouldDecode2097151Correctly() {
            VarInt.writeVarInt(buf, 2097151);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(2097151);
        }

        @Test
        @DisplayName("Should report size 3 for values 16384-2097151")
        void shouldReportSize3ForLargeValues() {
            assertThat(VarInt.getVarIntSize(16384)).isEqualTo(3);
            assertThat(VarInt.getVarIntSize(100000)).isEqualTo(3);
            assertThat(VarInt.getVarIntSize(2097151)).isEqualTo(3);
        }

        @ParameterizedTest
        @ValueSource(ints = {16384, 16385, 100000, 1048575, 1048576, 2097150, 2097151})
        @DisplayName("Should round-trip all 3-byte boundary values")
        void shouldRoundTripThreeByteValues(int value) {
            VarInt.writeVarInt(buf, value);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(value);
            assertThat(VarInt.getVarIntSize(value)).isEqualTo(3);
        }
    }

    // ==================== 4-Byte Boundary Tests ====================

    @Nested
    @DisplayName("4-Byte Boundary (2097152 to 268435455)")
    class FourByteTests {

        @Test
        @DisplayName("Should encode 2097152 as four bytes")
        void shouldEncode2097152AsFourBytes() {
            byte[] encoded = VarInt.encode(2097152);
            
            assertThat(encoded).hasSize(4);
        }

        @Test
        @DisplayName("Should decode 2097152 correctly")
        void shouldDecode2097152Correctly() {
            VarInt.writeVarInt(buf, 2097152);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(2097152);
        }

        @Test
        @DisplayName("Should encode 268435455 as four bytes")
        void shouldEncode268435455AsFourBytes() {
            byte[] encoded = VarInt.encode(268435455);
            
            assertThat(encoded).hasSize(4);
        }

        @Test
        @DisplayName("Should decode 268435455 correctly")
        void shouldDecode268435455Correctly() {
            VarInt.writeVarInt(buf, 268435455);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(268435455);
        }

        @Test
        @DisplayName("Should report size 4 for values 2097152-268435455")
        void shouldReportSize4ForVeryLargeValues() {
            assertThat(VarInt.getVarIntSize(2097152)).isEqualTo(4);
            assertThat(VarInt.getVarIntSize(100000000)).isEqualTo(4);
            assertThat(VarInt.getVarIntSize(268435455)).isEqualTo(4);
        }

        @ParameterizedTest
        @ValueSource(ints = {2097152, 2097153, 100000000, 134217727, 134217728, 268435454, 268435455})
        @DisplayName("Should round-trip all 4-byte boundary values")
        void shouldRoundTripFourByteValues(int value) {
            VarInt.writeVarInt(buf, value);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(value);
            assertThat(VarInt.getVarIntSize(value)).isEqualTo(4);
        }
    }

    // ==================== 5-Byte Boundary Tests (Max Values) ====================

    @Nested
    @DisplayName("5-Byte Boundary (268435456 to MAX_VALUE and negative)")
    class FiveByteTests {

        @Test
        @DisplayName("Should encode 268435456 as five bytes")
        void shouldEncode268435456AsFiveBytes() {
            byte[] encoded = VarInt.encode(268435456);
            
            assertThat(encoded).hasSize(5);
        }

        @Test
        @DisplayName("Should decode 268435456 correctly")
        void shouldDecode268435456Correctly() {
            VarInt.writeVarInt(buf, 268435456);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(268435456);
        }

        @Test
        @DisplayName("Should encode Integer.MAX_VALUE as five bytes")
        void shouldEncodeMaxIntAsFiveBytes() {
            byte[] encoded = VarInt.encode(Integer.MAX_VALUE);
            
            assertThat(encoded).hasSize(5);
        }

        @Test
        @DisplayName("Should decode Integer.MAX_VALUE correctly")
        void shouldDecodeMaxIntCorrectly() {
            VarInt.writeVarInt(buf, Integer.MAX_VALUE);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("Should encode Integer.MIN_VALUE as five bytes")
        void shouldEncodeMinIntAsFiveBytes() {
            byte[] encoded = VarInt.encode(Integer.MIN_VALUE);
            
            assertThat(encoded).hasSize(5);
        }

        @Test
        @DisplayName("Should decode Integer.MIN_VALUE correctly")
        void shouldDecodeMinIntCorrectly() {
            VarInt.writeVarInt(buf, Integer.MIN_VALUE);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(Integer.MIN_VALUE);
        }

        @Test
        @DisplayName("Should encode -1 as five bytes")
        void shouldEncodeNegativeOneAsFiveBytes() {
            byte[] encoded = VarInt.encode(-1);
            
            assertThat(encoded).hasSize(5);
            // -1 in two's complement = 0xFFFFFFFF
            // VarInt: all continuation bits set
        }

        @Test
        @DisplayName("Should decode -1 correctly")
        void shouldDecodeNegativeOneCorrectly() {
            VarInt.writeVarInt(buf, -1);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should report size 5 for large positive and all negative values")
        void shouldReportSize5ForMaxAndNegativeValues() {
            assertThat(VarInt.getVarIntSize(268435456)).isEqualTo(5);
            assertThat(VarInt.getVarIntSize(Integer.MAX_VALUE)).isEqualTo(5);
            assertThat(VarInt.getVarIntSize(-1)).isEqualTo(5);
            assertThat(VarInt.getVarIntSize(-100)).isEqualTo(5);
            assertThat(VarInt.getVarIntSize(Integer.MIN_VALUE)).isEqualTo(5);
        }

        @ParameterizedTest
        @ValueSource(ints = {268435456, 500000000, Integer.MAX_VALUE, -1, -100, -1000000, Integer.MIN_VALUE})
        @DisplayName("Should round-trip all 5-byte boundary values")
        void shouldRoundTripFiveByteValues(int value) {
            VarInt.writeVarInt(buf, value);
            int result = VarInt.readVarInt(buf);
            
            assertThat(result).isEqualTo(value);
            assertThat(VarInt.getVarIntSize(value)).isEqualTo(5);
        }
    }

    // ==================== Byte Array vs ByteBuf Consistency ====================

    @Nested
    @DisplayName("Byte Array and ByteBuf Consistency")
    class ConsistencyTests {

        @ParameterizedTest
        @ValueSource(ints = {0, 127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456, 
                            Integer.MAX_VALUE, -1, Integer.MIN_VALUE})
        @DisplayName("Byte array and ByteBuf encoding should produce same results")
        void byteArrayAndByteBufShouldProduceSameResults(int value) {
            // Encode using byte array
            byte[] arrayEncoded = VarInt.encode(value);
            
            // Encode using ByteBuf
            VarInt.writeVarInt(buf, value);
            byte[] bufEncoded = new byte[buf.readableBytes()];
            buf.readBytes(bufEncoded);
            
            assertThat(bufEncoded).isEqualTo(arrayEncoded);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456,
                            Integer.MAX_VALUE, -1, Integer.MIN_VALUE})
        @DisplayName("Byte array and ByteBuf decoding should produce same results")
        void byteArrayAndByteBufDecodingShouldProduceSameResults(int value) {
            // Encode
            byte[] encoded = VarInt.encode(value);
            
            // Decode using byte array
            int arrayDecoded = VarInt.decode(encoded);
            
            // Decode using ByteBuf
            buf.writeBytes(encoded);
            int bufDecoded = VarInt.readVarInt(buf);
            
            assertThat(arrayDecoded).isEqualTo(value);
            assertThat(bufDecoded).isEqualTo(value);
            assertThat(arrayDecoded).isEqualTo(bufDecoded);
        }
    }
}
