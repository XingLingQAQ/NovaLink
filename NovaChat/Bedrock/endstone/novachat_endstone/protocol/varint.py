"""
VarInt encoding/decoding implementation.

VarInt is a variable-length integer encoding used in the NovaProtocol.
It uses 7 bits per byte for the value and 1 bit to indicate continuation.
"""

from __future__ import annotations

import struct
from typing import Tuple


class VarInt:
    """VarInt encoder/decoder for NovaProtocol."""
    
    MAX_BYTES = 5
    SEGMENT_BITS = 0x7F
    CONTINUE_BIT = 0x80
    
    @staticmethod
    def encode(value: int) -> bytes:
        """
        Encode an integer as a VarInt.
        
        Args:
            value: The integer to encode (32-bit signed)
            
        Returns:
            The encoded bytes
            
        Raises:
            ValueError: If the value is out of range
        """
        if value < -2147483648 or value > 2147483647:
            raise ValueError(f"VarInt value out of range: {value}")
        
        # Handle negative numbers using two's complement
        if value < 0:
            value = value & 0xFFFFFFFF
        
        result = bytearray()
        
        while True:
            if (value & ~VarInt.SEGMENT_BITS) == 0:
                result.append(value & 0xFF)
                break
            
            result.append((value & VarInt.SEGMENT_BITS) | VarInt.CONTINUE_BIT)
            value >>= 7
        
        return bytes(result)
    
    @staticmethod
    def decode(data: bytes, offset: int = 0) -> Tuple[int, int]:
        """
        Decode a VarInt from bytes.
        
        Args:
            data: The bytes to decode from
            offset: The starting offset in the data
            
        Returns:
            A tuple of (decoded value, bytes consumed)
            
        Raises:
            ValueError: If the VarInt is malformed or too long
        """
        value = 0
        position = 0
        bytes_read = 0
        
        while True:
            if offset + bytes_read >= len(data):
                raise ValueError("Unexpected end of data while reading VarInt")
            
            current_byte = data[offset + bytes_read]
            bytes_read += 1
            
            value |= (current_byte & VarInt.SEGMENT_BITS) << position
            
            if (current_byte & VarInt.CONTINUE_BIT) == 0:
                break
            
            position += 7
            
            if position >= 32:
                raise ValueError("VarInt is too big")
        
        # Convert to signed 32-bit integer
        if value > 0x7FFFFFFF:
            value -= 0x100000000
        
        return value, bytes_read
    
    @staticmethod
    def size(value: int) -> int:
        """
        Calculate the number of bytes needed to encode a VarInt.
        
        Args:
            value: The integer value
            
        Returns:
            The number of bytes needed
        """
        if value < 0:
            value = value & 0xFFFFFFFF
        
        if value == 0:
            return 1
        
        size = 0
        while value > 0:
            size += 1
            value >>= 7
        
        return size
