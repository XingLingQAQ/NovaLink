"""
PacketBuffer implementation for reading and writing packet data.

This module provides a buffer class for serializing and deserializing
packet data using big-endian byte order.
"""

from __future__ import annotations

import struct
import uuid
from typing import Optional

from novachat_endstone.protocol.varint import VarInt


class PacketBuffer:
    """Buffer for reading and writing packet data."""
    
    def __init__(self, data: bytes = b""):
        """
        Initialize a PacketBuffer.
        
        Args:
            data: Initial data for reading, or empty for writing
        """
        self._data = bytearray(data)
        self._read_offset = 0
    
    # Write methods
    
    def write_byte(self, value: int) -> None:
        """Write a single byte."""
        self._data.append(value & 0xFF)
    
    def write_boolean(self, value: bool) -> None:
        """Write a boolean as a single byte."""
        self.write_byte(1 if value else 0)
    
    def write_short(self, value: int) -> None:
        """Write a 16-bit signed integer (big-endian)."""
        self._data.extend(struct.pack(">h", value))
    
    def write_int(self, value: int) -> None:
        """Write a 32-bit signed integer (big-endian)."""
        self._data.extend(struct.pack(">i", value))
    
    def write_long(self, value: int) -> None:
        """Write a 64-bit signed integer (big-endian)."""
        # Handle unsigned values by converting to signed
        if value > 0x7FFFFFFFFFFFFFFF:
            value -= 0x10000000000000000
        self._data.extend(struct.pack(">q", value))
    
    def write_float(self, value: float) -> None:
        """Write a 32-bit float (big-endian)."""
        self._data.extend(struct.pack(">f", value))
    
    def write_double(self, value: float) -> None:
        """Write a 64-bit double (big-endian)."""
        self._data.extend(struct.pack(">d", value))
    
    def write_varint(self, value: int) -> None:
        """Write a VarInt."""
        self._data.extend(VarInt.encode(value))
    
    def write_string(self, value: str) -> None:
        """Write a length-prefixed UTF-8 string."""
        encoded = value.encode("utf-8")
        self.write_varint(len(encoded))
        self._data.extend(encoded)
    
    def write_uuid(self, value: uuid.UUID) -> None:
        """Write a UUID as two 64-bit integers."""
        self.write_long(value.int >> 64)
        self.write_long(value.int & 0xFFFFFFFFFFFFFFFF)
    
    def write_bytes(self, data: bytes) -> None:
        """Write raw bytes."""
        self._data.extend(data)
    
    def write_bytes_with_length(self, data: bytes) -> None:
        """Write bytes with a VarInt length prefix."""
        self.write_varint(len(data))
        self._data.extend(data)
    
    # Read methods
    
    def read_byte(self) -> int:
        """Read a single byte."""
        if self._read_offset >= len(self._data):
            raise ValueError("Buffer underflow")
        value = self._data[self._read_offset]
        self._read_offset += 1
        return value
    
    def read_boolean(self) -> bool:
        """Read a boolean from a single byte."""
        return self.read_byte() != 0
    
    def read_short(self) -> int:
        """Read a 16-bit signed integer (big-endian)."""
        data = self._read_bytes(2)
        return struct.unpack(">h", data)[0]
    
    def read_int(self) -> int:
        """Read a 32-bit signed integer (big-endian)."""
        data = self._read_bytes(4)
        return struct.unpack(">i", data)[0]
    
    def read_long(self) -> int:
        """Read a 64-bit signed integer (big-endian)."""
        data = self._read_bytes(8)
        return struct.unpack(">q", data)[0]
    
    def read_float(self) -> float:
        """Read a 32-bit float (big-endian)."""
        data = self._read_bytes(4)
        return struct.unpack(">f", data)[0]
    
    def read_double(self) -> float:
        """Read a 64-bit double (big-endian)."""
        data = self._read_bytes(8)
        return struct.unpack(">d", data)[0]
    
    def read_varint(self) -> int:
        """Read a VarInt."""
        value, bytes_read = VarInt.decode(bytes(self._data), self._read_offset)
        self._read_offset += bytes_read
        return value
    
    def read_string(self) -> str:
        """Read a length-prefixed UTF-8 string."""
        length = self.read_varint()
        if length < 0:
            raise ValueError(f"Invalid string length: {length}")
        data = self._read_bytes(length)
        return data.decode("utf-8")
    
    def read_uuid(self) -> uuid.UUID:
        """Read a UUID from two 64-bit integers."""
        most_sig = self.read_long()
        least_sig = self.read_long()
        # Handle signed to unsigned conversion
        if most_sig < 0:
            most_sig += 0x10000000000000000
        if least_sig < 0:
            least_sig += 0x10000000000000000
        value = (most_sig << 64) | least_sig
        return uuid.UUID(int=value)
    
    def read_bytes(self, length: int) -> bytes:
        """Read a specific number of bytes."""
        return self._read_bytes(length)
    
    def read_bytes_with_length(self) -> bytes:
        """Read bytes with a VarInt length prefix."""
        length = self.read_varint()
        return self._read_bytes(length)
    
    def _read_bytes(self, length: int) -> bytes:
        """Internal method to read bytes."""
        if self._read_offset + length > len(self._data):
            raise ValueError("Buffer underflow")
        data = bytes(self._data[self._read_offset:self._read_offset + length])
        self._read_offset += length
        return data
    
    # Utility methods
    
    def get_bytes(self) -> bytes:
        """Get the buffer contents as bytes."""
        return bytes(self._data)
    
    def remaining(self) -> int:
        """Get the number of bytes remaining to read."""
        return len(self._data) - self._read_offset
    
    def reset_read(self) -> None:
        """Reset the read offset to the beginning."""
        self._read_offset = 0
    
    def __len__(self) -> int:
        """Get the total buffer length."""
        return len(self._data)
