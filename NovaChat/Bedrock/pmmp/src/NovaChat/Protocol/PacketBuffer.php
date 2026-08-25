<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

use InvalidArgumentException;

/**
 * Buffer for reading and writing packet data.
 * 
 * This class provides methods for reading and writing various data types
 * in big-endian byte order as required by NovaProtocol.
 * 
 * Requirements:
 * - 9.3: THE NovaChat-PMMP SHALL 使用大端序进行网络传输
 */
class PacketBuffer {
    
    /** @var string The buffer data */
    private string $buffer;
    
    /** @var int Current read/write position */
    private int $position = 0;
    
    /**
     * Creates a new PacketBuffer.
     * 
     * @param string $data Initial buffer data
     */
    public function __construct(string $data = "") {
        $this->buffer = $data;
    }
    
    /**
     * Gets the buffer data.
     * 
     * @return string The buffer data
     */
    public function getBuffer(): string {
        return $this->buffer;
    }
    
    /**
     * Gets the current position.
     * 
     * @return int The current position
     */
    public function getPosition(): int {
        return $this->position;
    }
    
    /**
     * Sets the current position.
     * 
     * @param int $position The new position
     */
    public function setPosition(int $position): void {
        $this->position = $position;
    }
    
    /**
     * Gets the remaining bytes in the buffer.
     * 
     * @return int The number of remaining bytes
     */
    public function remaining(): int {
        return strlen($this->buffer) - $this->position;
    }
    
    /**
     * Resets the buffer position to the beginning.
     */
    public function reset(): void {
        $this->position = 0;
    }
    
    // ==================== Write Methods ====================
    
    /**
     * Writes a byte to the buffer.
     * 
     * @param int $value The byte value (0-255)
     */
    public function writeByte(int $value): void {
        $this->buffer .= chr($value & 0xFF);
    }
    
    /**
     * Writes a boolean to the buffer.
     * 
     * @param bool $value The boolean value
     */
    public function writeBoolean(bool $value): void {
        $this->writeByte($value ? 1 : 0);
    }
    
    /**
     * Writes a short (16-bit) to the buffer in big-endian order.
     * 
     * @param int $value The short value
     */
    public function writeShort(int $value): void {
        $this->buffer .= pack("n", $value);
    }
    
    /**
     * Writes an int (32-bit) to the buffer in big-endian order.
     * 
     * @param int $value The int value
     */
    public function writeInt(int $value): void {
        $this->buffer .= pack("N", $value);
    }
    
    /**
     * Writes a long (64-bit) to the buffer in big-endian order.
     * 
     * @param int $value The long value
     */
    public function writeLong(int $value): void {
        // Big-endian 64-bit: pack as two 32-bit segments to avoid machine-endian formats ("J").
        $hi = ($value >> 32) & 0xFFFFFFFF;
        $lo = $value & 0xFFFFFFFF;
        $this->buffer .= pack("N2", $hi, $lo);
    }
    
    /**
     * Writes a VarInt to the buffer.
     * 
     * @param int $value The VarInt value
     */
    public function writeVarInt(int $value): void {
        $this->buffer .= VarInt::write($value);
    }
    
    /**
     * Writes a string to the buffer (VarInt length prefix + UTF-8 bytes).
     * 
     * @param string $value The string value
     */
    public function writeString(string $value): void {
        $bytes = $value;
        $this->writeVarInt(strlen($bytes));
        $this->buffer .= $bytes;
    }
    
    /**
     * Writes a UUID to the buffer (two 64-bit longs).
     * 
     * @param string $uuid The UUID string (with or without dashes)
     */
    public function writeUUID(string $uuid): void {
        // Remove dashes from UUID
        $hex = str_replace("-", "", $uuid);
        if (strlen($hex) !== 32) {
            throw new InvalidArgumentException("Invalid UUID format: $uuid");
        }
        
        // Convert hex to bytes directly to avoid float conversion issues
        $bytes = hex2bin($hex);
        if ($bytes === false) {
            throw new InvalidArgumentException("Invalid UUID hex: $uuid");
        }
        
        $this->buffer .= $bytes;
    }
    
    /**
     * Writes raw bytes to the buffer.
     * 
     * @param string $bytes The bytes to write
     */
    public function writeBytes(string $bytes): void {
        $this->buffer .= $bytes;
    }
    
    // ==================== Read Methods ====================
    
    /**
     * Reads a byte from the buffer.
     * 
     * @return int The byte value (0-255)
     */
    public function readByte(): int {
        if ($this->position >= strlen($this->buffer)) {
            throw new InvalidArgumentException("Buffer underflow");
        }
        return ord($this->buffer[$this->position++]);
    }
    
    /**
     * Reads a boolean from the buffer.
     * 
     * @return bool The boolean value
     */
    public function readBoolean(): bool {
        return $this->readByte() !== 0;
    }
    
    /**
     * Reads a short (16-bit) from the buffer in big-endian order.
     * 
     * @return int The short value
     */
    public function readShort(): int {
        if ($this->remaining() < 2) {
            throw new InvalidArgumentException("Buffer underflow");
        }
        $data = substr($this->buffer, $this->position, 2);
        $this->position += 2;
        return unpack("n", $data)[1];
    }
    
    /**
     * Reads an int (32-bit) from the buffer in big-endian order.
     * 
     * @return int The int value
     */
    public function readInt(): int {
        if ($this->remaining() < 4) {
            throw new InvalidArgumentException("Buffer underflow");
        }
        $data = substr($this->buffer, $this->position, 4);
        $this->position += 4;
        $value = unpack("N", $data)[1];
        // Convert to signed
        if ($value > 0x7FFFFFFF) {
            $value -= 0x100000000;
        }
        return $value;
    }
    
    /**
     * Reads a long (64-bit) from the buffer in big-endian order.
     * 
     * @return int The long value
     */
    public function readLong(): int {
        if ($this->remaining() < 8) {
            throw new InvalidArgumentException("Buffer underflow");
        }
        $data = substr($this->buffer, $this->position, 8);
        $this->position += 8;
        $parts = unpack("Nhi/Nlo", $data);
        $hi = $parts["hi"];
        $lo = $parts["lo"];
        // Convert high part to signed 32-bit to preserve sign for 64-bit composition.
        if ($hi > 0x7FFFFFFF) {
            $hi -= 0x100000000;
        }
        return ($hi << 32) | $lo;
    }
    
    /**
     * Reads a VarInt from the buffer.
     * 
     * @return int The VarInt value
     */
    public function readVarInt(): int {
        return VarInt::read($this->buffer, $this->position);
    }
    
    /**
     * Reads a string from the buffer (VarInt length prefix + UTF-8 bytes).
     *
     * PROTO-003: this overload is the bounded counterpart to the JVM
     * `PacketBuffer.readString(buf, max)`. PHP has no arity overloading, so
     * the optional `$maxLength` parameter selects the path:
     *   - `readString()` / `readString(null)` preserves the legacy unbounded
     *     path for backward compatibility (e.g. golden-byte decoders that
     *     read arbitrary fields, and any external callers that do not yet
     *     bound their fields).
     *   - `readString($maxLength)` bounds the declared wire length before any
     *     bytes are allocated, mirroring the JVM contract. An oversized field
     *     is rejected with an `InvalidArgumentException` whose message contains
     *     `"exceeds maximum"` so the non-JVM forks stay byte-for-byte
     *     consistent with the Java error contract.
     *
     * A hard cap at `ProtocolLimits::MAX_FRAME_LENGTH` applies on both paths,
     * mirroring the JVM `Varint21FrameDecoder` ceiling, so an absurd declared
     * length is rejected before the `substr()` allocation.
     *
     * @param int|null $maxLength Optional maximum UTF-8 byte length for this field.
     * @return string The string value
     * @throws InvalidArgumentException If the declared length is negative,
     *         exceeds `MAX_FRAME_LENGTH`, exceeds `$maxLength` (when given),
     *         exceeds the remaining bytes, or the decoded bytes are not valid
     *         UTF-8 (VERIFY-005 R3).
     */
    public function readString(?int $maxLength = null): string {
        $length = $this->readVarInt();
        // Hard cap at the frame ceiling so an absurd declared length is
        // rejected before the `remaining()` / substr() path is reached,
        // mirroring the JVM Varint21FrameDecoder ceiling.
        if ($length < 0 || $length > ProtocolLimits::MAX_FRAME_LENGTH) {
            throw new InvalidArgumentException("Invalid string length: $length");
        }
        if ($maxLength !== null && $length > $maxLength) {
            throw new InvalidArgumentException(
                "String length $length exceeds maximum $maxLength"
            );
        }
        if ($this->remaining() < $length) {
            throw new InvalidArgumentException("Invalid string length: $length");
        }
        $value = substr($this->buffer, $this->position, $length);
        $this->position += $length;
        // VERIFY-005 R3: every string field in the NovaChat protocol is text
        // (senderName, channelId, errorCode, message, configJson, itemJson,
        // placeholders...). Reject non-UTF-8 bytes at the decode layer so a
        // malformed frame is caught here and bubbled up to handlePacket's
        // try/catch (R1), which closes+releases+reconnects. mb_check_encoding
        // returns false for invalid UTF-8 sequences (e.g. lone \xFF\xFE).
        if (!mb_check_encoding($value, 'UTF-8')) {
            throw new InvalidArgumentException("Invalid UTF-8 in string field");
        }
        return $value;
    }
    
    /**
     * Reads a UUID from the buffer (16 bytes).
     * 
     * @return string The UUID string with dashes
     */
    public function readUUID(): string {
        if ($this->remaining() < 16) {
            throw new InvalidArgumentException("Buffer underflow");
        }
        
        $bytes = substr($this->buffer, $this->position, 16);
        $this->position += 16;
        
        $hex = bin2hex($bytes);
        return sprintf(
            "%s-%s-%s-%s-%s",
            substr($hex, 0, 8),
            substr($hex, 8, 4),
            substr($hex, 12, 4),
            substr($hex, 16, 4),
            substr($hex, 20, 12)
        );
    }
    
    /**
     * Reads raw bytes from the buffer.
     * 
     * @param int $length The number of bytes to read
     * @return string The bytes read
     */
    public function readBytes(int $length): string {
        if ($this->remaining() < $length) {
            throw new InvalidArgumentException("Buffer underflow");
        }
        $value = substr($this->buffer, $this->position, $length);
        $this->position += $length;
        return $value;
    }
}
