package com.nova.chat.common.protocol;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Utility class for reading and writing data to ByteBuf in big-endian format.
 * Provides convenient methods for common data types used in NovaProtocol.
 */
public final class PacketBuffer {

    private PacketBuffer() {
        // Utility class
    }

    // ==================== String Operations ====================

    /**
     * Writes a string to the buffer with a VarInt length prefix.
     * {@code null} is encoded as an empty string to keep the wire format stable.
     *
     * @param buf   the buffer to write to
     * @param value the string to write (null treated as empty)
     */
    public static void writeString(ByteBuf buf, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        VarInt.writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    /**
     * Reads a string from the buffer with a VarInt length prefix.
     *
     * @param buf the buffer to read from
     * @return the decoded string
     */
    public static String readString(ByteBuf buf) {
        int length = VarInt.readVarInt(buf);
        if (length < 0) {
            throw new IllegalArgumentException("Negative string length: " + length);
        }
        if (length > buf.readableBytes()) {
            throw new IllegalArgumentException(
                    "String length " + length + " exceeds remaining readable bytes " + buf.readableBytes());
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Reads a string from the buffer with a maximum length constraint.
     *
     * @param buf       the buffer to read from
     * @param maxLength the maximum allowed length
     * @return the decoded string
     * @throws IllegalArgumentException if the string exceeds maxLength
     */
    public static String readString(ByteBuf buf, int maxLength) {
        int length = VarInt.readVarInt(buf);
        if (length > maxLength) {
            throw new IllegalArgumentException(
                "String length " + length + " exceeds maximum " + maxLength);
        }
        if (length < 0) {
            throw new IllegalArgumentException("Negative string length: " + length);
        }
        if (length > buf.readableBytes()) {
            throw new IllegalArgumentException(
                    "String length " + length + " exceeds remaining readable bytes " + buf.readableBytes());
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }


    // ==================== UUID Operations ====================

    /**
     * Writes a UUID to the buffer (big-endian, 16 bytes).
     * {@code null} is encoded as the nil UUID (all zeros).
     *
     * @param buf  the buffer to write to
     * @param uuid the UUID to write (null treated as 00000000-0000-0000-0000-000000000000)
     */
    public static void writeUUID(ByteBuf buf, UUID uuid) {
        if (uuid == null) {
            buf.writeLong(0L);
            buf.writeLong(0L);
            return;
        }
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    /**
     * Reads a UUID from the buffer (big-endian, 16 bytes).
     *
     * @param buf the buffer to read from
     * @return the decoded UUID
     */
    public static UUID readUUID(ByteBuf buf) {
        long mostSig = buf.readLong();
        long leastSig = buf.readLong();
        return new UUID(mostSig, leastSig);
    }

    // ==================== VarInt Operations ====================

    /**
     * Writes a VarInt to the buffer.
     *
     * @param buf   the buffer to write to
     * @param value the integer value to encode
     */
    public static void writeVarInt(ByteBuf buf, int value) {
        VarInt.writeVarInt(buf, value);
    }

    /**
     * Reads a VarInt from the buffer.
     *
     * @param buf the buffer to read from
     * @return the decoded integer value
     */
    public static int readVarInt(ByteBuf buf) {
        return VarInt.readVarInt(buf);
    }

    // ==================== Boolean Operations ====================

    /**
     * Writes a boolean to the buffer (1 byte).
     *
     * @param buf   the buffer to write to
     * @param value the boolean value
     */
    public static void writeBoolean(ByteBuf buf, boolean value) {
        buf.writeByte(value ? 1 : 0);
    }

    /**
     * Reads a boolean from the buffer (1 byte).
     *
     * @param buf the buffer to read from
     * @return the decoded boolean value
     */
    public static boolean readBoolean(ByteBuf buf) {
        return buf.readByte() != 0;
    }


    // ==================== Byte Array Operations ====================

    /**
     * Writes a byte array to the buffer with a VarInt length prefix.
     * {@code null} is encoded as a zero-length array.
     *
     * @param buf   the buffer to write to
     * @param bytes the byte array to write (null treated as empty)
     */
    public static void writeByteArray(ByteBuf buf, byte[] bytes) {
        if (bytes == null) {
            VarInt.writeVarInt(buf, 0);
            return;
        }
        VarInt.writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    /**
     * Reads a byte array from the buffer with a VarInt length prefix.
     *
     * @param buf the buffer to read from
     * @return the decoded byte array
     * @throws IllegalArgumentException if length is negative or exceeds remaining bytes
     */
    public static byte[] readByteArray(ByteBuf buf) {
        int length = VarInt.readVarInt(buf);
        if (length < 0) {
            throw new IllegalArgumentException("Negative byte array length: " + length);
        }
        if (length > buf.readableBytes()) {
            throw new IllegalArgumentException(
                    "Byte array length " + length + " exceeds remaining readable bytes " + buf.readableBytes());
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return bytes;
    }

    /**
     * Reads a byte array from the buffer with a maximum length constraint.
     *
     * @param buf       the buffer to read from
     * @param maxLength the maximum allowed length
     * @return the decoded byte array
     * @throws IllegalArgumentException if the array is negative or exceeds maxLength/remaining bytes
     */
    public static byte[] readByteArray(ByteBuf buf, int maxLength) {
        int length = VarInt.readVarInt(buf);
        if (length < 0) {
            throw new IllegalArgumentException("Negative byte array length: " + length);
        }
        if (length > maxLength) {
            throw new IllegalArgumentException(
                "Byte array length " + length + " exceeds maximum " + maxLength);
        }
        if (length > buf.readableBytes()) {
            throw new IllegalArgumentException(
                    "Byte array length " + length + " exceeds remaining readable bytes " + buf.readableBytes());
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return bytes;
    }

    // ==================== Numeric Operations (Big-Endian) ====================

    /**
     * Writes a short to the buffer (big-endian, 2 bytes).
     *
     * @param buf   the buffer to write to
     * @param value the short value
     */
    public static void writeShort(ByteBuf buf, short value) {
        buf.writeShort(value);
    }

    /**
     * Reads a short from the buffer (big-endian, 2 bytes).
     *
     * @param buf the buffer to read from
     * @return the decoded short value
     */
    public static short readShort(ByteBuf buf) {
        return buf.readShort();
    }

    /**
     * Writes an int to the buffer (big-endian, 4 bytes).
     *
     * @param buf   the buffer to write to
     * @param value the int value
     */
    public static void writeInt(ByteBuf buf, int value) {
        buf.writeInt(value);
    }

    /**
     * Reads an int from the buffer (big-endian, 4 bytes).
     *
     * @param buf the buffer to read from
     * @return the decoded int value
     */
    public static int readInt(ByteBuf buf) {
        return buf.readInt();
    }

    /**
     * Writes a long to the buffer (big-endian, 8 bytes).
     *
     * @param buf   the buffer to write to
     * @param value the long value
     */
    public static void writeLong(ByteBuf buf, long value) {
        buf.writeLong(value);
    }

    /**
     * Reads a long from the buffer (big-endian, 8 bytes).
     *
     * @param buf the buffer to read from
     * @return the decoded long value
     */
    public static long readLong(ByteBuf buf) {
        return buf.readLong();
    }
}
