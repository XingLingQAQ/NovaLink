<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

use InvalidArgumentException;

/**
 * VarInt encoder/decoder for NovaProtocol.
 * 
 * VarInt is a variable-length integer encoding used in the NovaProtocol
 * to efficiently encode integers of varying sizes.
 * 
 * Requirements:
 * - 9.1: THE NovaChat-PMMP SHALL 实现 VarInt 编解码器
 */
class VarInt {
    
    /** Maximum bytes for a VarInt (5 bytes for 32-bit integers) */
    public const MAX_BYTES = 5;
    
    /** Maximum value for a VarInt */
    public const MAX_VALUE = 2147483647;
    
    /** Minimum value for a VarInt */
    public const MIN_VALUE = -2147483648;
    
    /**
     * Encodes an integer value to VarInt bytes.
     * 
     * @param int $value The integer value to encode
     * @return string The encoded VarInt bytes
     * @throws InvalidArgumentException If the value is out of range
     */
    public static function write(int $value): string {
        if ($value > self::MAX_VALUE || $value < self::MIN_VALUE) {
            throw new InvalidArgumentException("Value out of VarInt range: $value");
        }
        
        $result = "";
        $unsigned = $value & 0xFFFFFFFF;
        
        while (true) {
            if (($unsigned & ~0x7F) === 0) {
                $result .= chr($unsigned);
                break;
            }
            $result .= chr(($unsigned & 0x7F) | 0x80);
            $unsigned >>= 7;
        }
        
        return $result;
    }
    
    /**
     * Reads a VarInt from a buffer at the specified offset.
     * 
     * @param string $buffer The buffer to read from
     * @param int &$offset The offset to start reading from (will be updated)
     * @return int The decoded integer value
     * @throws InvalidArgumentException If the VarInt is malformed
     */
    public static function read(string $buffer, int &$offset): int {
        $result = 0;
        $shift = 0;
        $bytesRead = 0;
        
        while (true) {
            if ($offset >= strlen($buffer)) {
                throw new InvalidArgumentException("Buffer underflow while reading VarInt");
            }
            
            $byte = ord($buffer[$offset++]);
            $bytesRead++;
            
            if ($bytesRead > self::MAX_BYTES) {
                throw new InvalidArgumentException("VarInt is too long");
            }
            
            $result |= ($byte & 0x7F) << $shift;
            
            if (($byte & 0x80) === 0) {
                break;
            }
            
            $shift += 7;
        }
        
        // Convert to signed 32-bit integer
        if ($result > 0x7FFFFFFF) {
            $result -= 0x100000000;
        }
        
        return $result;
    }
    
    /**
     * Calculates the size in bytes of a VarInt-encoded value.
     * 
     * @param int $value The value to calculate size for
     * @return int The number of bytes needed to encode the value
     */
    public static function size(int $value): int {
        $unsigned = $value & 0xFFFFFFFF;
        $size = 0;
        
        do {
            $size++;
            $unsigned >>= 7;
        } while ($unsigned !== 0);
        
        return $size;
    }
}
