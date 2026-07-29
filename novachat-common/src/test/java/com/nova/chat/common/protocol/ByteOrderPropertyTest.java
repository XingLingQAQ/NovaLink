package com.nova.chat.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for byte order consistency.
 * 
 * **Feature: novachat-platform-expansion, Property 3: Byte Order Consistency**
 * **Validates: Requirements 9.3**
 * 
 * This test verifies that all multi-byte values are serialized in big-endian
 * (network byte order) format, ensuring cross-platform compatibility.
 */
class ByteOrderPropertyTest {

    private ByteBuf buf;

    @BeforeProperty
    void setUp() {
        buf = Unpooled.buffer();
    }

    @AfterProperty
    void tearDown() {
        if (buf != null) {
            buf.release();
            buf = null;
        }
    }

    // ==================== Short Big-Endian Property ====================

    /**
     * Property 3: Byte Order Consistency - Short
     * 
     * For any short value, the serialized bytes should match Java's
     * big-endian ByteBuffer representation.
     * 
     * **Feature: novachat-platform-expansion, Property 3: Byte Order Consistency**
     * **Validates: Requirements 9.3**
     */
    @Property(tries = 100)
    void shortByteOrderMatchesBigEndian(@ForAll short value) {
        // Get expected big-endian bytes using Java's ByteBuffer
        ByteBuffer expected = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
        expected.putShort(value);
        byte[] expectedBytes = expected.array();
        
        // Get actual bytes from PacketBuffer
        buf.clear();
        PacketBuffer.writeShort(buf, value);
        byte[] actualBytes = new byte[2];
        buf.readBytes(actualBytes);
        
        // Verify byte-by-byte equality
        assertThat(actualBytes).isEqualTo(expectedBytes);
    }

    // ==================== Int Big-Endian Property ====================

    /**
     * Property 3: Byte Order Consistency - Int
     * 
     * For any int value, the serialized bytes should match Java's
     * big-endian ByteBuffer representation.
     * 
     * **Feature: novachat-platform-expansion, Property 3: Byte Order Consistency**
     * **Validates: Requirements 9.3**
     */
    @Property(tries = 100)
    void intByteOrderMatchesBigEndian(@ForAll int value) {
        // Get expected big-endian bytes using Java's ByteBuffer
        ByteBuffer expected = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        expected.putInt(value);
        byte[] expectedBytes = expected.array();
        
        // Get actual bytes from PacketBuffer
        buf.clear();
        PacketBuffer.writeInt(buf, value);
        byte[] actualBytes = new byte[4];
        buf.readBytes(actualBytes);
        
        // Verify byte-by-byte equality
        assertThat(actualBytes).isEqualTo(expectedBytes);
    }

    // ==================== Long Big-Endian Property ====================

    /**
     * Property 3: Byte Order Consistency - Long
     * 
     * For any long value, the serialized bytes should match Java's
     * big-endian ByteBuffer representation.
     * 
     * **Feature: novachat-platform-expansion, Property 3: Byte Order Consistency**
     * **Validates: Requirements 9.3**
     */
    @Property(tries = 100)
    void longByteOrderMatchesBigEndian(@ForAll long value) {
        // Get expected big-endian bytes using Java's ByteBuffer
        ByteBuffer expected = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        expected.putLong(value);
        byte[] expectedBytes = expected.array();
        
        // Get actual bytes from PacketBuffer
        buf.clear();
        PacketBuffer.writeLong(buf, value);
        byte[] actualBytes = new byte[8];
        buf.readBytes(actualBytes);
        
        // Verify byte-by-byte equality
        assertThat(actualBytes).isEqualTo(expectedBytes);
    }

    // ==================== UUID Big-Endian Property ====================

    /**
     * Property 3: Byte Order Consistency - UUID
     * 
     * For any UUID, the serialized bytes should be the concatenation of
     * most significant bits and least significant bits, both in big-endian.
     * 
     * **Feature: novachat-platform-expansion, Property 3: Byte Order Consistency**
     * **Validates: Requirements 9.3**
     */
    @Property(tries = 100)
    void uuidByteOrderMatchesBigEndian(@ForAll("uuids") UUID uuid) {
        // Get expected big-endian bytes
        ByteBuffer expected = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        expected.putLong(uuid.getMostSignificantBits());
        expected.putLong(uuid.getLeastSignificantBits());
        byte[] expectedBytes = expected.array();
        
        // Get actual bytes from PacketBuffer
        buf.clear();
        PacketBuffer.writeUUID(buf, uuid);
        byte[] actualBytes = new byte[16];
        buf.readBytes(actualBytes);
        
        // Verify byte-by-byte equality
        assertThat(actualBytes).isEqualTo(expectedBytes);
    }

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.longs().tuple2()
                .map(t -> new UUID(t.get1(), t.get2()));
    }

    // ==================== Cross-Implementation Consistency ====================

    /**
     * Property 3: Byte Order Consistency - Round Trip
     * 
     * For any multi-byte value, writing and reading should preserve the value,
     * confirming that read and write use consistent byte ordering.
     * 
     * **Feature: novachat-platform-expansion, Property 3: Byte Order Consistency**
     * **Validates: Requirements 9.3**
     */
    @Property(tries = 100)
    void multiByteValuesRoundTripCorrectly(
            @ForAll short shortVal,
            @ForAll int intVal,
            @ForAll long longVal,
            @ForAll("uuids") UUID uuidVal) {
        
        buf.clear();
        
        // Write all values
        PacketBuffer.writeShort(buf, shortVal);
        PacketBuffer.writeInt(buf, intVal);
        PacketBuffer.writeLong(buf, longVal);
        PacketBuffer.writeUUID(buf, uuidVal);
        
        // Read all values back
        short readShort = PacketBuffer.readShort(buf);
        int readInt = PacketBuffer.readInt(buf);
        long readLong = PacketBuffer.readLong(buf);
        UUID readUuid = PacketBuffer.readUUID(buf);
        
        // Verify all values match
        assertThat(readShort).isEqualTo(shortVal);
        assertThat(readInt).isEqualTo(intVal);
        assertThat(readLong).isEqualTo(longVal);
        assertThat(readUuid).isEqualTo(uuidVal);
        
        // Buffer should be fully consumed
        assertThat(buf.readableBytes()).isZero();
    }

    // ==================== MSB First Property ====================

    /**
     * Property 3: Byte Order Consistency - MSB First
     * 
     * For any positive value with a single high bit set, the first byte
     * should contain that bit (big-endian property).
     * 
     * **Feature: novachat-platform-expansion, Property 3: Byte Order Consistency**
     * **Validates: Requirements 9.3**
     */
    @Property(tries = 100)
    void msbIsWrittenFirst(@ForAll("highBitPositions") int bitPosition) {
        // Create a value with only one bit set at the given position
        int value = 1 << bitPosition;
        
        buf.clear();
        PacketBuffer.writeInt(buf, value);
        byte[] bytes = new byte[4];
        buf.readBytes(bytes);
        
        // Calculate which byte should contain the set bit
        int expectedByteIndex = 3 - (bitPosition / 8);
        int expectedBitInByte = bitPosition % 8;
        
        // Verify the bit is in the expected position
        assertThat((bytes[expectedByteIndex] >> expectedBitInByte) & 1).isEqualTo(1);
        
        // Verify all other bytes are zero (except the one with the bit)
        for (int i = 0; i < 4; i++) {
            if (i != expectedByteIndex) {
                assertThat(bytes[i]).isEqualTo((byte) 0);
            }
        }
    }

    @Provide
    Arbitrary<Integer> highBitPositions() {
        return Arbitraries.integers().between(0, 30); // 0-30 for positive int values
    }

    // ==================== Byte Sequence Determinism ====================

    /**
     * Property 3: Byte Order Consistency - Determinism
     * 
     * For any value, serializing it multiple times should always produce
     * the same byte sequence.
     * 
     * **Feature: novachat-platform-expansion, Property 3: Byte Order Consistency**
     * **Validates: Requirements 9.3**
     */
    @Property(tries = 100)
    void serializationIsDeterministic(@ForAll long value) {
        // First serialization
        buf.clear();
        PacketBuffer.writeLong(buf, value);
        byte[] first = new byte[8];
        buf.readBytes(first);
        
        // Second serialization
        buf.clear();
        PacketBuffer.writeLong(buf, value);
        byte[] second = new byte[8];
        buf.readBytes(second);
        
        // Third serialization
        buf.clear();
        PacketBuffer.writeLong(buf, value);
        byte[] third = new byte[8];
        buf.readBytes(third);
        
        // All should be identical
        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(third);
    }
}
