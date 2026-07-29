package com.nova.chat.common.protocol;

import io.netty.buffer.ByteBuf;

/**
 * VarInt encoder/decoder for NovaProtocol.
 * VarInt is a variable-length integer encoding that uses 1-5 bytes.
 * Each byte uses 7 bits for data and 1 bit (MSB) as continuation flag.
 */
public final class VarInt {

    private static final int SEGMENT_BITS = 0x7F;
    private static final int CONTINUE_BIT = 0x80;
    private static final int MAX_VARINT_SIZE = 5;

    private VarInt() {
        // Utility class
    }

    /**
     * Reads a VarInt from the given ByteBuf.
     *
     * @param buf the buffer to read from
     * @return the decoded integer value
     * @throws IllegalArgumentException if the VarInt is too large (more than 5 bytes)
     */
    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        byte currentByte;

        while (true) {
            currentByte = buf.readByte();
            value |= (currentByte & SEGMENT_BITS) << position;

            if ((currentByte & CONTINUE_BIT) == 0) {
                break;
            }

            position += 7;

            if (position >= 32) {
                throw new IllegalArgumentException("VarInt is too big");
            }
        }

        return value;
    }

    /**
     * Writes a VarInt to the given ByteBuf.
     *
     * @param buf   the buffer to write to
     * @param value the integer value to encode
     */
    public static void writeVarInt(ByteBuf buf, int value) {
        while (true) {
            if ((value & ~SEGMENT_BITS) == 0) {
                buf.writeByte(value);
                return;
            }

            buf.writeByte((value & SEGMENT_BITS) | CONTINUE_BIT);
            value >>>= 7;
        }
    }


    /**
     * Calculates the number of bytes needed to encode the given value as a VarInt.
     *
     * @param value the integer value
     * @return the number of bytes needed (1-5)
     */
    public static int getVarIntSize(int value) {
        if ((value & (0xFFFFFFFF << 7)) == 0) {
            return 1;
        }
        if ((value & (0xFFFFFFFF << 14)) == 0) {
            return 2;
        }
        if ((value & (0xFFFFFFFF << 21)) == 0) {
            return 3;
        }
        if ((value & (0xFFFFFFFF << 28)) == 0) {
            return 4;
        }
        return 5;
    }

    /**
     * Encodes an integer value to a byte array as VarInt.
     *
     * @param value the integer value to encode
     * @return the encoded byte array
     */
    public static byte[] encode(int value) {
        byte[] result = new byte[getVarIntSize(value)];
        int index = 0;

        while (true) {
            if ((value & ~SEGMENT_BITS) == 0) {
                result[index] = (byte) value;
                return result;
            }

            result[index++] = (byte) ((value & SEGMENT_BITS) | CONTINUE_BIT);
            value >>>= 7;
        }
    }

    /**
     * Decodes a VarInt from a byte array.
     *
     * @param bytes the byte array to decode from
     * @return the decoded integer value
     * @throws IllegalArgumentException if the VarInt is too large
     */
    public static int decode(byte[] bytes) {
        int value = 0;
        int position = 0;

        for (byte currentByte : bytes) {
            value |= (currentByte & SEGMENT_BITS) << position;

            if ((currentByte & CONTINUE_BIT) == 0) {
                break;
            }

            position += 7;

            if (position >= 32) {
                throw new IllegalArgumentException("VarInt is too big");
            }
        }

        return value;
    }
}
