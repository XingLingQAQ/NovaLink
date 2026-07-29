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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PacketBuffer read/write operations.
 * Covers all data types used in NovaProtocol.
 * 
 * _Requirements: 21.1_
 */
class PacketBufferTest {

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

    // ==================== String Operations ====================

    @Nested
    @DisplayName("String Operations")
    class StringOperations {

        @Test
        @DisplayName("Should write and read empty string")
        void shouldWriteAndReadEmptyString() {
            PacketBuffer.writeString(buf, "");
            String result = PacketBuffer.readString(buf);
            
            assertThat(result).isEmpty();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read simple ASCII string")
        void shouldWriteAndReadSimpleAsciiString() {
            String original = "Hello, World!";
            PacketBuffer.writeString(buf, original);
            String result = PacketBuffer.readString(buf);
            
            assertThat(result).isEqualTo(original);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read UTF-8 string with Chinese characters")
        void shouldWriteAndReadUtf8StringWithChinese() {
            String original = "你好世界";
            PacketBuffer.writeString(buf, original);
            String result = PacketBuffer.readString(buf);
            
            assertThat(result).isEqualTo(original);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read UTF-8 string with emoji")
        void shouldWriteAndReadUtf8StringWithEmoji() {
            String original = "Hello 🌍🎮";
            PacketBuffer.writeString(buf, original);
            String result = PacketBuffer.readString(buf);
            
            assertThat(result).isEqualTo(original);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read string with color codes")
        void shouldWriteAndReadStringWithColorCodes() {
            String original = "&c[PVP] &7Player&f: Hello";
            PacketBuffer.writeString(buf, original);
            String result = PacketBuffer.readString(buf);
            
            assertThat(result).isEqualTo(original);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read string with hex color codes")
        void shouldWriteAndReadStringWithHexColorCodes() {
            String original = "&#FF5555Red &#00AA00Green";
            PacketBuffer.writeString(buf, original);
            String result = PacketBuffer.readString(buf);
            
            assertThat(result).isEqualTo(original);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should read string with max length constraint")
        void shouldReadStringWithMaxLengthConstraint() {
            String original = "Short";
            PacketBuffer.writeString(buf, original);
            String result = PacketBuffer.readString(buf, 100);
            
            assertThat(result).isEqualTo(original);
        }

        @Test
        @DisplayName("Should throw exception when string exceeds max length")
        void shouldThrowExceptionWhenStringExceedsMaxLength() {
            String original = "This is a long string";
            PacketBuffer.writeString(buf, original);
            
            assertThatThrownBy(() -> PacketBuffer.readString(buf, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum");
        }
    }

    // ==================== UUID Operations ====================

    @Nested
    @DisplayName("UUID Operations")
    class UUIDOperations {

        @Test
        @DisplayName("Should write and read random UUID")
        void shouldWriteAndReadRandomUuid() {
            UUID original = UUID.randomUUID();
            PacketBuffer.writeUUID(buf, original);
            UUID result = PacketBuffer.readUUID(buf);
            
            assertThat(result).isEqualTo(original);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read nil UUID")
        void shouldWriteAndReadNilUuid() {
            UUID original = new UUID(0, 0);
            PacketBuffer.writeUUID(buf, original);
            UUID result = PacketBuffer.readUUID(buf);
            
            assertThat(result).isEqualTo(original);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read max UUID")
        void shouldWriteAndReadMaxUuid() {
            UUID original = new UUID(-1L, -1L);
            PacketBuffer.writeUUID(buf, original);
            UUID result = PacketBuffer.readUUID(buf);
            
            assertThat(result).isEqualTo(original);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("UUID should be written as 16 bytes")
        void uuidShouldBeWrittenAs16Bytes() {
            UUID original = UUID.randomUUID();
            PacketBuffer.writeUUID(buf, original);
            
            assertThat(buf.readableBytes()).isEqualTo(16);
        }
    }

    // ==================== VarInt Operations ====================

    @Nested
    @DisplayName("VarInt Operations")
    class VarIntOperations {

        @Test
        @DisplayName("Should write and read zero")
        void shouldWriteAndReadZero() {
            PacketBuffer.writeVarInt(buf, 0);
            int result = PacketBuffer.readVarInt(buf);
            
            assertThat(result).isZero();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read positive value")
        void shouldWriteAndReadPositiveValue() {
            PacketBuffer.writeVarInt(buf, 12345);
            int result = PacketBuffer.readVarInt(buf);
            
            assertThat(result).isEqualTo(12345);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read negative value")
        void shouldWriteAndReadNegativeValue() {
            PacketBuffer.writeVarInt(buf, -1);
            int result = PacketBuffer.readVarInt(buf);
            
            assertThat(result).isEqualTo(-1);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read max int value")
        void shouldWriteAndReadMaxIntValue() {
            PacketBuffer.writeVarInt(buf, Integer.MAX_VALUE);
            int result = PacketBuffer.readVarInt(buf);
            
            assertThat(result).isEqualTo(Integer.MAX_VALUE);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read min int value")
        void shouldWriteAndReadMinIntValue() {
            PacketBuffer.writeVarInt(buf, Integer.MIN_VALUE);
            int result = PacketBuffer.readVarInt(buf);
            
            assertThat(result).isEqualTo(Integer.MIN_VALUE);
            assertThat(buf.readableBytes()).isZero();
        }
    }

    // ==================== Boolean Operations ====================

    @Nested
    @DisplayName("Boolean Operations")
    class BooleanOperations {

        @Test
        @DisplayName("Should write and read true")
        void shouldWriteAndReadTrue() {
            PacketBuffer.writeBoolean(buf, true);
            boolean result = PacketBuffer.readBoolean(buf);
            
            assertThat(result).isTrue();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read false")
        void shouldWriteAndReadFalse() {
            PacketBuffer.writeBoolean(buf, false);
            boolean result = PacketBuffer.readBoolean(buf);
            
            assertThat(result).isFalse();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Boolean should be written as 1 byte")
        void booleanShouldBeWrittenAs1Byte() {
            PacketBuffer.writeBoolean(buf, true);
            
            assertThat(buf.readableBytes()).isEqualTo(1);
        }
    }

    // ==================== Byte Array Operations ====================

    @Nested
    @DisplayName("Byte Array Operations")
    class ByteArrayOperations {

        @Test
        @DisplayName("Should write and read empty byte array")
        void shouldWriteAndReadEmptyByteArray() {
            byte[] original = new byte[0];
            PacketBuffer.writeByteArray(buf, original);
            byte[] result = PacketBuffer.readByteArray(buf);
            
            assertThat(result).isEmpty();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read byte array with data")
        void shouldWriteAndReadByteArrayWithData() {
            byte[] original = {0x01, 0x02, 0x03, (byte) 0xFF, 0x00};
            PacketBuffer.writeByteArray(buf, original);
            byte[] result = PacketBuffer.readByteArray(buf);
            
            assertThat(result).isEqualTo(original);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should read byte array with max length constraint")
        void shouldReadByteArrayWithMaxLengthConstraint() {
            byte[] original = {0x01, 0x02, 0x03};
            PacketBuffer.writeByteArray(buf, original);
            byte[] result = PacketBuffer.readByteArray(buf, 100);
            
            assertThat(result).isEqualTo(original);
        }

        @Test
        @DisplayName("Should throw exception when byte array exceeds max length")
        void shouldThrowExceptionWhenByteArrayExceedsMaxLength() {
            byte[] original = {0x01, 0x02, 0x03, 0x04, 0x05};
            PacketBuffer.writeByteArray(buf, original);
            
            assertThatThrownBy(() -> PacketBuffer.readByteArray(buf, 3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum");
        }
    }

    // ==================== Numeric Operations ====================

    @Nested
    @DisplayName("Short Operations")
    class ShortOperations {

        @Test
        @DisplayName("Should write and read zero short")
        void shouldWriteAndReadZeroShort() {
            PacketBuffer.writeShort(buf, (short) 0);
            short result = PacketBuffer.readShort(buf);
            
            assertThat(result).isZero();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read positive short")
        void shouldWriteAndReadPositiveShort() {
            PacketBuffer.writeShort(buf, (short) 12345);
            short result = PacketBuffer.readShort(buf);
            
            assertThat(result).isEqualTo((short) 12345);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read negative short")
        void shouldWriteAndReadNegativeShort() {
            PacketBuffer.writeShort(buf, (short) -12345);
            short result = PacketBuffer.readShort(buf);
            
            assertThat(result).isEqualTo((short) -12345);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read max short value")
        void shouldWriteAndReadMaxShortValue() {
            PacketBuffer.writeShort(buf, Short.MAX_VALUE);
            short result = PacketBuffer.readShort(buf);
            
            assertThat(result).isEqualTo(Short.MAX_VALUE);
        }

        @Test
        @DisplayName("Should write and read min short value")
        void shouldWriteAndReadMinShortValue() {
            PacketBuffer.writeShort(buf, Short.MIN_VALUE);
            short result = PacketBuffer.readShort(buf);
            
            assertThat(result).isEqualTo(Short.MIN_VALUE);
        }

        @Test
        @DisplayName("Short should be written as 2 bytes")
        void shortShouldBeWrittenAs2Bytes() {
            PacketBuffer.writeShort(buf, (short) 1);
            
            assertThat(buf.readableBytes()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Int Operations")
    class IntOperations {

        @Test
        @DisplayName("Should write and read zero int")
        void shouldWriteAndReadZeroInt() {
            PacketBuffer.writeInt(buf, 0);
            int result = PacketBuffer.readInt(buf);
            
            assertThat(result).isZero();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read positive int")
        void shouldWriteAndReadPositiveInt() {
            PacketBuffer.writeInt(buf, 123456789);
            int result = PacketBuffer.readInt(buf);
            
            assertThat(result).isEqualTo(123456789);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read negative int")
        void shouldWriteAndReadNegativeInt() {
            PacketBuffer.writeInt(buf, -123456789);
            int result = PacketBuffer.readInt(buf);
            
            assertThat(result).isEqualTo(-123456789);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read max int value")
        void shouldWriteAndReadMaxIntValue() {
            PacketBuffer.writeInt(buf, Integer.MAX_VALUE);
            int result = PacketBuffer.readInt(buf);
            
            assertThat(result).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("Should write and read min int value")
        void shouldWriteAndReadMinIntValue() {
            PacketBuffer.writeInt(buf, Integer.MIN_VALUE);
            int result = PacketBuffer.readInt(buf);
            
            assertThat(result).isEqualTo(Integer.MIN_VALUE);
        }

        @Test
        @DisplayName("Int should be written as 4 bytes")
        void intShouldBeWrittenAs4Bytes() {
            PacketBuffer.writeInt(buf, 1);
            
            assertThat(buf.readableBytes()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Long Operations")
    class LongOperations {

        @Test
        @DisplayName("Should write and read zero long")
        void shouldWriteAndReadZeroLong() {
            PacketBuffer.writeLong(buf, 0L);
            long result = PacketBuffer.readLong(buf);
            
            assertThat(result).isZero();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read positive long")
        void shouldWriteAndReadPositiveLong() {
            PacketBuffer.writeLong(buf, 1234567890123456789L);
            long result = PacketBuffer.readLong(buf);
            
            assertThat(result).isEqualTo(1234567890123456789L);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read negative long")
        void shouldWriteAndReadNegativeLong() {
            PacketBuffer.writeLong(buf, -1234567890123456789L);
            long result = PacketBuffer.readLong(buf);
            
            assertThat(result).isEqualTo(-1234567890123456789L);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should write and read max long value")
        void shouldWriteAndReadMaxLongValue() {
            PacketBuffer.writeLong(buf, Long.MAX_VALUE);
            long result = PacketBuffer.readLong(buf);
            
            assertThat(result).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("Should write and read min long value")
        void shouldWriteAndReadMinLongValue() {
            PacketBuffer.writeLong(buf, Long.MIN_VALUE);
            long result = PacketBuffer.readLong(buf);
            
            assertThat(result).isEqualTo(Long.MIN_VALUE);
        }

        @Test
        @DisplayName("Long should be written as 8 bytes")
        void longShouldBeWrittenAs8Bytes() {
            PacketBuffer.writeLong(buf, 1L);
            
            assertThat(buf.readableBytes()).isEqualTo(8);
        }
    }

    // ==================== Multiple Operations ====================

    @Nested
    @DisplayName("Multiple Operations")
    class MultipleOperations {

        @Test
        @DisplayName("Should write and read multiple values in order")
        void shouldWriteAndReadMultipleValuesInOrder() {
            String str = "Hello";
            UUID uuid = UUID.randomUUID();
            int varInt = 12345;
            boolean bool = true;
            short shortVal = 100;
            int intVal = 999999;
            long longVal = 1234567890L;

            // Write all values
            PacketBuffer.writeString(buf, str);
            PacketBuffer.writeUUID(buf, uuid);
            PacketBuffer.writeVarInt(buf, varInt);
            PacketBuffer.writeBoolean(buf, bool);
            PacketBuffer.writeShort(buf, shortVal);
            PacketBuffer.writeInt(buf, intVal);
            PacketBuffer.writeLong(buf, longVal);

            // Read all values in same order
            assertThat(PacketBuffer.readString(buf)).isEqualTo(str);
            assertThat(PacketBuffer.readUUID(buf)).isEqualTo(uuid);
            assertThat(PacketBuffer.readVarInt(buf)).isEqualTo(varInt);
            assertThat(PacketBuffer.readBoolean(buf)).isEqualTo(bool);
            assertThat(PacketBuffer.readShort(buf)).isEqualTo(shortVal);
            assertThat(PacketBuffer.readInt(buf)).isEqualTo(intVal);
            assertThat(PacketBuffer.readLong(buf)).isEqualTo(longVal);

            // Buffer should be fully consumed
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should handle complex nested data")
        void shouldHandleComplexNestedData() {
            // Simulate a packet with multiple strings and UUIDs
            String[] strings = {"channel1", "player_name", "Hello World"};
            UUID[] uuids = {UUID.randomUUID(), UUID.randomUUID()};

            // Write count and data
            PacketBuffer.writeVarInt(buf, strings.length);
            for (String s : strings) {
                PacketBuffer.writeString(buf, s);
            }
            PacketBuffer.writeVarInt(buf, uuids.length);
            for (UUID u : uuids) {
                PacketBuffer.writeUUID(buf, u);
            }

            // Read and verify
            int stringCount = PacketBuffer.readVarInt(buf);
            assertThat(stringCount).isEqualTo(strings.length);
            for (int i = 0; i < stringCount; i++) {
                assertThat(PacketBuffer.readString(buf)).isEqualTo(strings[i]);
            }

            int uuidCount = PacketBuffer.readVarInt(buf);
            assertThat(uuidCount).isEqualTo(uuids.length);
            for (int i = 0; i < uuidCount; i++) {
                assertThat(PacketBuffer.readUUID(buf)).isEqualTo(uuids[i]);
            }

            assertThat(buf.readableBytes()).isZero();
        }
    }
}
