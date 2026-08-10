package com.nova.chat.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests to verify byte order (endianness) correctness in NovaProtocol.
 * NovaProtocol uses big-endian (network byte order) for all multi-byte values.
 * 
 * Big-endian means the most significant byte is stored first (at the lowest address).
 * 
 * _Requirements: 21.4_
 */
class ByteOrderTest {

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

    // ==================== Short (2 bytes) Big-Endian Tests ====================

    @Nested
    @DisplayName("Short Big-Endian Byte Order")
    class ShortByteOrderTests {

        @Test
        @DisplayName("Should write short 0x1234 as [0x12, 0x34] (big-endian)")
        void shouldWriteShortInBigEndian() {
            short value = 0x1234;
            PacketBuffer.writeShort(buf, value);
            
            byte[] bytes = new byte[2];
            buf.readBytes(bytes);
            
            // Big-endian: MSB first
            assertThat(bytes[0]).isEqualTo((byte) 0x12);
            assertThat(bytes[1]).isEqualTo((byte) 0x34);
        }

        @Test
        @DisplayName("Should read [0x12, 0x34] as short 0x1234 (big-endian)")
        void shouldReadShortInBigEndian() {
            buf.writeByte(0x12);
            buf.writeByte(0x34);
            
            short result = PacketBuffer.readShort(buf);
            
            assertThat(result).isEqualTo((short) 0x1234);
        }

        @Test
        @DisplayName("Should write short 0xFF00 as [0xFF, 0x00] (big-endian)")
        void shouldWriteShortWithHighByte() {
            short value = (short) 0xFF00;
            PacketBuffer.writeShort(buf, value);
            
            byte[] bytes = new byte[2];
            buf.readBytes(bytes);
            
            assertThat(bytes[0]).isEqualTo((byte) 0xFF);
            assertThat(bytes[1]).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("Should write short 0x00FF as [0x00, 0xFF] (big-endian)")
        void shouldWriteShortWithLowByte() {
            short value = 0x00FF;
            PacketBuffer.writeShort(buf, value);
            
            byte[] bytes = new byte[2];
            buf.readBytes(bytes);
            
            assertThat(bytes[0]).isEqualTo((byte) 0x00);
            assertThat(bytes[1]).isEqualTo((byte) 0xFF);
        }
    }

    // ==================== Int (4 bytes) Big-Endian Tests ====================

    @Nested
    @DisplayName("Int Big-Endian Byte Order")
    class IntByteOrderTests {

        @Test
        @DisplayName("Should write int 0x12345678 as [0x12, 0x34, 0x56, 0x78] (big-endian)")
        void shouldWriteIntInBigEndian() {
            int value = 0x12345678;
            PacketBuffer.writeInt(buf, value);
            
            byte[] bytes = new byte[4];
            buf.readBytes(bytes);
            
            // Big-endian: MSB first
            assertThat(bytes[0]).isEqualTo((byte) 0x12);
            assertThat(bytes[1]).isEqualTo((byte) 0x34);
            assertThat(bytes[2]).isEqualTo((byte) 0x56);
            assertThat(bytes[3]).isEqualTo((byte) 0x78);
        }

        @Test
        @DisplayName("Should read [0x12, 0x34, 0x56, 0x78] as int 0x12345678 (big-endian)")
        void shouldReadIntInBigEndian() {
            buf.writeByte(0x12);
            buf.writeByte(0x34);
            buf.writeByte(0x56);
            buf.writeByte(0x78);
            
            int result = PacketBuffer.readInt(buf);
            
            assertThat(result).isEqualTo(0x12345678);
        }

        @Test
        @DisplayName("Should write int 0xFF000000 as [0xFF, 0x00, 0x00, 0x00] (big-endian)")
        void shouldWriteIntWithHighByte() {
            int value = 0xFF000000;
            PacketBuffer.writeInt(buf, value);
            
            byte[] bytes = new byte[4];
            buf.readBytes(bytes);
            
            assertThat(bytes[0]).isEqualTo((byte) 0xFF);
            assertThat(bytes[1]).isEqualTo((byte) 0x00);
            assertThat(bytes[2]).isEqualTo((byte) 0x00);
            assertThat(bytes[3]).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("Should write int 0x000000FF as [0x00, 0x00, 0x00, 0xFF] (big-endian)")
        void shouldWriteIntWithLowByte() {
            int value = 0x000000FF;
            PacketBuffer.writeInt(buf, value);
            
            byte[] bytes = new byte[4];
            buf.readBytes(bytes);
            
            assertThat(bytes[0]).isEqualTo((byte) 0x00);
            assertThat(bytes[1]).isEqualTo((byte) 0x00);
            assertThat(bytes[2]).isEqualTo((byte) 0x00);
            assertThat(bytes[3]).isEqualTo((byte) 0xFF);
        }

        @Test
        @DisplayName("Should correctly handle negative int -1 (0xFFFFFFFF)")
        void shouldHandleNegativeInt() {
            int value = -1; // 0xFFFFFFFF
            PacketBuffer.writeInt(buf, value);
            
            byte[] bytes = new byte[4];
            buf.readBytes(bytes);
            
            assertThat(bytes[0]).isEqualTo((byte) 0xFF);
            assertThat(bytes[1]).isEqualTo((byte) 0xFF);
            assertThat(bytes[2]).isEqualTo((byte) 0xFF);
            assertThat(bytes[3]).isEqualTo((byte) 0xFF);
        }
    }

    // ==================== Long (8 bytes) Big-Endian Tests ====================

    @Nested
    @DisplayName("Long Big-Endian Byte Order")
    class LongByteOrderTests {

        @Test
        @DisplayName("Should write long 0x123456789ABCDEF0 as big-endian bytes")
        void shouldWriteLongInBigEndian() {
            long value = 0x123456789ABCDEF0L;
            PacketBuffer.writeLong(buf, value);
            
            byte[] bytes = new byte[8];
            buf.readBytes(bytes);
            
            // Big-endian: MSB first
            assertThat(bytes[0]).isEqualTo((byte) 0x12);
            assertThat(bytes[1]).isEqualTo((byte) 0x34);
            assertThat(bytes[2]).isEqualTo((byte) 0x56);
            assertThat(bytes[3]).isEqualTo((byte) 0x78);
            assertThat(bytes[4]).isEqualTo((byte) 0x9A);
            assertThat(bytes[5]).isEqualTo((byte) 0xBC);
            assertThat(bytes[6]).isEqualTo((byte) 0xDE);
            assertThat(bytes[7]).isEqualTo((byte) 0xF0);
        }

        @Test
        @DisplayName("Should read big-endian bytes as long 0x123456789ABCDEF0")
        void shouldReadLongInBigEndian() {
            buf.writeByte(0x12);
            buf.writeByte(0x34);
            buf.writeByte(0x56);
            buf.writeByte(0x78);
            buf.writeByte(0x9A);
            buf.writeByte(0xBC);
            buf.writeByte(0xDE);
            buf.writeByte(0xF0);
            
            long result = PacketBuffer.readLong(buf);
            
            assertThat(result).isEqualTo(0x123456789ABCDEF0L);
        }

        @Test
        @DisplayName("Should write long 0xFF00000000000000 with high byte first")
        void shouldWriteLongWithHighByte() {
            long value = 0xFF00000000000000L;
            PacketBuffer.writeLong(buf, value);
            
            byte[] bytes = new byte[8];
            buf.readBytes(bytes);
            
            assertThat(bytes[0]).isEqualTo((byte) 0xFF);
            for (int i = 1; i < 8; i++) {
                assertThat(bytes[i]).isEqualTo((byte) 0x00);
            }
        }

        @Test
        @DisplayName("Should write long 0x00000000000000FF with low byte last")
        void shouldWriteLongWithLowByte() {
            long value = 0x00000000000000FFL;
            PacketBuffer.writeLong(buf, value);
            
            byte[] bytes = new byte[8];
            buf.readBytes(bytes);
            
            for (int i = 0; i < 7; i++) {
                assertThat(bytes[i]).isEqualTo((byte) 0x00);
            }
            assertThat(bytes[7]).isEqualTo((byte) 0xFF);
        }
    }

    // ==================== UUID (16 bytes) Big-Endian Tests ====================

    @Nested
    @DisplayName("UUID Big-Endian Byte Order")
    class UUIDByteOrderTests {

        @Test
        @DisplayName("Should write UUID with most significant bits first")
        void shouldWriteUUIDInBigEndian() {
            // UUID with known bit patterns
            long mostSig = 0x123456789ABCDEF0L;
            long leastSig = 0xFEDCBA9876543210L;
            UUID uuid = new UUID(mostSig, leastSig);
            
            PacketBuffer.writeUUID(buf, uuid);
            
            byte[] bytes = new byte[16];
            buf.readBytes(bytes);
            
            // First 8 bytes: most significant bits (big-endian)
            assertThat(bytes[0]).isEqualTo((byte) 0x12);
            assertThat(bytes[1]).isEqualTo((byte) 0x34);
            assertThat(bytes[2]).isEqualTo((byte) 0x56);
            assertThat(bytes[3]).isEqualTo((byte) 0x78);
            assertThat(bytes[4]).isEqualTo((byte) 0x9A);
            assertThat(bytes[5]).isEqualTo((byte) 0xBC);
            assertThat(bytes[6]).isEqualTo((byte) 0xDE);
            assertThat(bytes[7]).isEqualTo((byte) 0xF0);
            
            // Last 8 bytes: least significant bits (big-endian)
            assertThat(bytes[8]).isEqualTo((byte) 0xFE);
            assertThat(bytes[9]).isEqualTo((byte) 0xDC);
            assertThat(bytes[10]).isEqualTo((byte) 0xBA);
            assertThat(bytes[11]).isEqualTo((byte) 0x98);
            assertThat(bytes[12]).isEqualTo((byte) 0x76);
            assertThat(bytes[13]).isEqualTo((byte) 0x54);
            assertThat(bytes[14]).isEqualTo((byte) 0x32);
            assertThat(bytes[15]).isEqualTo((byte) 0x10);
        }

        @Test
        @DisplayName("Should read UUID with most significant bits first")
        void shouldReadUUIDInBigEndian() {
            // Write known byte pattern
            byte[] mostSigBytes = {0x12, 0x34, 0x56, 0x78, (byte) 0x9A, (byte) 0xBC, (byte) 0xDE, (byte) 0xF0};
            byte[] leastSigBytes = {(byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0x98, 0x76, 0x54, 0x32, 0x10};
            buf.writeBytes(mostSigBytes);
            buf.writeBytes(leastSigBytes);
            
            UUID result = PacketBuffer.readUUID(buf);
            
            assertThat(result.getMostSignificantBits()).isEqualTo(0x123456789ABCDEF0L);
            assertThat(result.getLeastSignificantBits()).isEqualTo(0xFEDCBA9876543210L);
        }

        @Test
        @DisplayName("Should correctly handle nil UUID (all zeros)")
        void shouldHandleNilUUID() {
            UUID nilUuid = new UUID(0, 0);
            PacketBuffer.writeUUID(buf, nilUuid);
            
            byte[] bytes = new byte[16];
            buf.readBytes(bytes);
            
            for (byte b : bytes) {
                assertThat(b).isEqualTo((byte) 0x00);
            }
        }

        @Test
        @DisplayName("Should correctly handle max UUID (all ones)")
        void shouldHandleMaxUUID() {
            UUID maxUuid = new UUID(-1L, -1L);
            PacketBuffer.writeUUID(buf, maxUuid);
            
            byte[] bytes = new byte[16];
            buf.readBytes(bytes);
            
            for (byte b : bytes) {
                assertThat(b).isEqualTo((byte) 0xFF);
            }
        }
    }

    // ==================== VarInt Byte Order Tests ====================

    @Nested
    @DisplayName("VarInt Byte Order")
    class VarIntByteOrderTests {

        @Test
        @DisplayName("VarInt 128 should be encoded as [0x80, 0x01]")
        void varInt128ShouldBeEncodedCorrectly() {
            // 128 = 0b10000000
            // VarInt encoding: 0b10000000 (continuation) 0b00000001 (final)
            VarInt.writeVarInt(buf, 128);
            
            byte[] bytes = new byte[2];
            buf.readBytes(bytes);
            
            assertThat(bytes[0]).isEqualTo((byte) 0x80);
            assertThat(bytes[1]).isEqualTo((byte) 0x01);
        }

        @Test
        @DisplayName("VarInt 300 should be encoded as [0xAC, 0x02]")
        void varInt300ShouldBeEncodedCorrectly() {
            // 300 = 0b100101100
            // VarInt encoding: 0b10101100 (continuation) 0b00000010 (final)
            VarInt.writeVarInt(buf, 300);
            
            byte[] bytes = new byte[2];
            buf.readBytes(bytes);
            
            assertThat(bytes[0]).isEqualTo((byte) 0xAC);
            assertThat(bytes[1]).isEqualTo((byte) 0x02);
        }

        @Test
        @DisplayName("VarInt 16384 should be encoded as [0x80, 0x80, 0x01]")
        void varInt16384ShouldBeEncodedCorrectly() {
            // 16384 = 0b100000000000000
            // VarInt encoding: 0b10000000 0b10000000 0b00000001
            VarInt.writeVarInt(buf, 16384);
            
            byte[] bytes = new byte[3];
            buf.readBytes(bytes);
            
            assertThat(bytes[0]).isEqualTo((byte) 0x80);
            assertThat(bytes[1]).isEqualTo((byte) 0x80);
            assertThat(bytes[2]).isEqualTo((byte) 0x01);
        }
    }

    // ==================== Cross-Platform Compatibility Tests ====================

    @Nested
    @DisplayName("Cross-Platform Byte Sequence Compatibility")
    class CrossPlatformTests {

        @Test
        @DisplayName("Known byte sequence should decode to expected values")
        void knownByteSequenceShouldDecodeCorrectly() {
            // This test verifies that a known byte sequence (that could come from
            // another implementation like Go or PHP) decodes correctly
            
            // Short: 0x1234
            buf.writeByte(0x12);
            buf.writeByte(0x34);
            assertThat(PacketBuffer.readShort(buf)).isEqualTo((short) 0x1234);
            
            // Int: 0x12345678
            buf.writeByte(0x12);
            buf.writeByte(0x34);
            buf.writeByte(0x56);
            buf.writeByte(0x78);
            assertThat(PacketBuffer.readInt(buf)).isEqualTo(0x12345678);
            
            // Long: 0x123456789ABCDEF0
            buf.writeByte(0x12);
            buf.writeByte(0x34);
            buf.writeByte(0x56);
            buf.writeByte(0x78);
            buf.writeByte(0x9A);
            buf.writeByte(0xBC);
            buf.writeByte(0xDE);
            buf.writeByte(0xF0);
            assertThat(PacketBuffer.readLong(buf)).isEqualTo(0x123456789ABCDEF0L);
        }

        @Test
        @DisplayName("Encoded values should produce expected byte sequences")
        void encodedValuesShouldProduceExpectedByteSequences() {
            // This test verifies that our encoding produces byte sequences
            // that other implementations (Go, PHP, Python) would expect
            
            // Short: 0x1234 -> [0x12, 0x34]
            PacketBuffer.writeShort(buf, (short) 0x1234);
            assertThat(buf.readByte()).isEqualTo((byte) 0x12);
            assertThat(buf.readByte()).isEqualTo((byte) 0x34);
            
            // Int: 0x12345678 -> [0x12, 0x34, 0x56, 0x78]
            PacketBuffer.writeInt(buf, 0x12345678);
            assertThat(buf.readByte()).isEqualTo((byte) 0x12);
            assertThat(buf.readByte()).isEqualTo((byte) 0x34);
            assertThat(buf.readByte()).isEqualTo((byte) 0x56);
            assertThat(buf.readByte()).isEqualTo((byte) 0x78);
            
            // Long: 0x123456789ABCDEF0 -> [0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0]
            PacketBuffer.writeLong(buf, 0x123456789ABCDEF0L);
            assertThat(buf.readByte()).isEqualTo((byte) 0x12);
            assertThat(buf.readByte()).isEqualTo((byte) 0x34);
            assertThat(buf.readByte()).isEqualTo((byte) 0x56);
            assertThat(buf.readByte()).isEqualTo((byte) 0x78);
            assertThat(buf.readByte()).isEqualTo((byte) 0x9A);
            assertThat(buf.readByte()).isEqualTo((byte) 0xBC);
            assertThat(buf.readByte()).isEqualTo((byte) 0xDE);
            assertThat(buf.readByte()).isEqualTo((byte) 0xF0);
        }
    }
}
