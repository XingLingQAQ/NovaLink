package com.nova.chat.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for VarInt encoding/decoding.
 * 
 * **Feature: starchat-starlink, Property: VarInt Encoding Round-Trip**
 * **Validates: NovaProtocol Specification**
 */
class VarIntPropertyTest {

    /**
     * Property: VarInt Encoding Round-Trip
     * 
     * For any integer value, encoding to VarInt and decoding back should produce
     * the original value.
     * 
     * **Feature: starchat-starlink, Property: VarInt Encoding Round-Trip**
     * **Validates: NovaProtocol Specification**
     */
    @Property(tries = 100)
    void varIntRoundTrip_ByteArray(@ForAll int value) {
        // Encode
        byte[] encoded = VarInt.encode(value);
        
        // Decode
        int decoded = VarInt.decode(encoded);
        
        // Verify round-trip
        assertThat(decoded).isEqualTo(value);
    }

    /**
     * Property: VarInt ByteBuf Round-Trip
     * 
     * For any integer value, writing to ByteBuf and reading back should produce
     * the original value.
     * 
     * **Feature: starchat-starlink, Property: VarInt Encoding Round-Trip**
     * **Validates: NovaProtocol Specification**
     */
    @Property(tries = 100)
    void varIntRoundTrip_ByteBuf(@ForAll int value) {
        ByteBuf buf = Unpooled.buffer();
        try {
            // Write
            VarInt.writeVarInt(buf, value);
            
            // Read
            int decoded = VarInt.readVarInt(buf);
            
            // Verify round-trip
            assertThat(decoded).isEqualTo(value);
            
            // Verify buffer is fully consumed
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }


    /**
     * Property: VarInt Size Correctness
     * 
     * For any integer value, the calculated size should match the actual encoded size.
     * 
     * **Feature: starchat-starlink, Property: VarInt Encoding Round-Trip**
     * **Validates: NovaProtocol Specification**
     */
    @Property(tries = 100)
    void varIntSizeMatchesEncodedLength(@ForAll int value) {
        int calculatedSize = VarInt.getVarIntSize(value);
        byte[] encoded = VarInt.encode(value);
        
        assertThat(encoded.length).isEqualTo(calculatedSize);
    }

    /**
     * Property: VarInt Size Bounds
     * 
     * For any integer value, the VarInt size should be between 1 and 5 bytes.
     * 
     * **Feature: starchat-starlink, Property: VarInt Encoding Round-Trip**
     * **Validates: NovaProtocol Specification**
     */
    @Property(tries = 100)
    void varIntSizeWithinBounds(@ForAll int value) {
        int size = VarInt.getVarIntSize(value);
        
        assertThat(size).isBetween(1, 5);
    }

    /**
     * Property: Small values use fewer bytes
     * 
     * Values that fit in 7 bits should use exactly 1 byte.
     * 
     * **Feature: starchat-starlink, Property: VarInt Encoding Round-Trip**
     * **Validates: NovaProtocol Specification**
     */
    @Property(tries = 100)
    void smallValuesUseOneByte(@ForAll @IntRange(min = 0, max = 127) int value) {
        assertThat(VarInt.getVarIntSize(value)).isEqualTo(1);
    }

    /**
     * Property: Multiple writes and reads preserve order
     * 
     * Writing multiple VarInts and reading them back should preserve order.
     * 
     * **Feature: starchat-starlink, Property: VarInt Encoding Round-Trip**
     * **Validates: NovaProtocol Specification**
     */
    @Property(tries = 100)
    void multipleVarIntsPreserveOrder(
            @ForAll int value1,
            @ForAll int value2,
            @ForAll int value3) {
        ByteBuf buf = Unpooled.buffer();
        try {
            // Write multiple values
            VarInt.writeVarInt(buf, value1);
            VarInt.writeVarInt(buf, value2);
            VarInt.writeVarInt(buf, value3);
            
            // Read them back
            int read1 = VarInt.readVarInt(buf);
            int read2 = VarInt.readVarInt(buf);
            int read3 = VarInt.readVarInt(buf);
            
            // Verify order preserved
            assertThat(read1).isEqualTo(value1);
            assertThat(read2).isEqualTo(value2);
            assertThat(read3).isEqualTo(value3);
        } finally {
            buf.release();
        }
    }
}
