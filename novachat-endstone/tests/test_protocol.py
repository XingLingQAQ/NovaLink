"""
Protocol tests for NovaChat-Endstone.

This module contains unit tests and property-based tests for the
protocol implementation including VarInt and packet serialization.
"""

import pytest
import uuid
from hypothesis import given, strategies as st, settings

from novachat_endstone.protocol.varint import VarInt
from novachat_endstone.protocol.buffer import PacketBuffer
from novachat_endstone.protocol.packet import (
    HandshakePacket,
    HandshakeResponsePacket,
    ChatMessagePacket,
    ChannelActionPacket,
    KeepAlivePacket,
    AnnouncementPacket,
    TitleMessagePacket,
    ChannelUpdatePacket,
)


class TestVarInt:
    """Unit tests for VarInt encoding/decoding."""
    
    def test_encode_zero(self):
        """Test encoding zero."""
        result = VarInt.encode(0)
        assert result == bytes([0])
    
    def test_encode_small_positive(self):
        """Test encoding small positive numbers."""
        assert VarInt.encode(1) == bytes([1])
        assert VarInt.encode(127) == bytes([127])
    
    def test_encode_medium_positive(self):
        """Test encoding medium positive numbers."""
        assert VarInt.encode(128) == bytes([0x80, 0x01])
        assert VarInt.encode(255) == bytes([0xFF, 0x01])
    
    def test_encode_large_positive(self):
        """Test encoding large positive numbers."""
        assert VarInt.encode(16383) == bytes([0xFF, 0x7F])
        assert VarInt.encode(16384) == bytes([0x80, 0x80, 0x01])
    
    def test_encode_negative(self):
        """Test encoding negative numbers."""
        result = VarInt.encode(-1)
        assert len(result) == 5  # Negative numbers use all 5 bytes
    
    def test_decode_zero(self):
        """Test decoding zero."""
        value, consumed = VarInt.decode(bytes([0]))
        assert value == 0
        assert consumed == 1
    
    def test_decode_small_positive(self):
        """Test decoding small positive numbers."""
        value, consumed = VarInt.decode(bytes([127]))
        assert value == 127
        assert consumed == 1
    
    def test_decode_medium_positive(self):
        """Test decoding medium positive numbers."""
        value, consumed = VarInt.decode(bytes([0x80, 0x01]))
        assert value == 128
        assert consumed == 2
    
    def test_size_calculation(self):
        """Test VarInt size calculation."""
        assert VarInt.size(0) == 1
        assert VarInt.size(127) == 1
        assert VarInt.size(128) == 2
        assert VarInt.size(16383) == 2
        assert VarInt.size(16384) == 3


class TestPacketBuffer:
    """Unit tests for PacketBuffer."""
    
    def test_write_read_byte(self):
        """Test byte read/write."""
        buf = PacketBuffer()
        buf.write_byte(42)
        buf.reset_read()
        assert buf.read_byte() == 42
    
    def test_write_read_boolean(self):
        """Test boolean read/write."""
        buf = PacketBuffer()
        buf.write_boolean(True)
        buf.write_boolean(False)
        buf.reset_read()
        assert buf.read_boolean() is True
        assert buf.read_boolean() is False
    
    def test_write_read_int(self):
        """Test int read/write."""
        buf = PacketBuffer()
        buf.write_int(12345678)
        buf.reset_read()
        assert buf.read_int() == 12345678
    
    def test_write_read_long(self):
        """Test long read/write."""
        buf = PacketBuffer()
        buf.write_long(1234567890123456789)
        buf.reset_read()
        assert buf.read_long() == 1234567890123456789
    
    def test_write_read_string(self):
        """Test string read/write."""
        buf = PacketBuffer()
        buf.write_string("Hello, 世界!")
        buf.reset_read()
        assert buf.read_string() == "Hello, 世界!"
    
    def test_write_read_uuid(self):
        """Test UUID read/write."""
        buf = PacketBuffer()
        test_uuid = uuid.uuid4()
        buf.write_uuid(test_uuid)
        buf.reset_read()
        assert buf.read_uuid() == test_uuid


# Property-based tests

# **Feature: novachat-platform-expansion, Property 1: VarInt Encoding Round-Trip (Cross-Language)**
class TestVarIntProperties:
    """Property-based tests for VarInt."""
    
    @given(st.integers(min_value=-2147483648, max_value=2147483647))
    @settings(max_examples=100)
    def test_varint_round_trip(self, value: int):
        """
        **Feature: novachat-platform-expansion, Property 1: VarInt Encoding Round-Trip (Cross-Language)**
        
        For any valid integer value within VarInt range, encoding and then
        decoding should produce the original value.
        
        **Validates: Requirements 11.1**
        """
        encoded = VarInt.encode(value)
        decoded, _ = VarInt.decode(encoded)
        assert decoded == value


# **Feature: novachat-platform-expansion, Property 2: Packet Serialization Round-Trip (Cross-Language)**
class TestPacketProperties:
    """Property-based tests for packet serialization."""
    
    @given(
        protocol_version=st.integers(min_value=1, max_value=100),
        client_id=st.text(min_size=1, max_size=50).filter(lambda x: x.isprintable()),
        password_hash=st.text(min_size=1, max_size=64).filter(lambda x: x.isprintable()),
        platform=st.integers(min_value=0, max_value=255)
    )
    @settings(max_examples=100)
    def test_handshake_packet_round_trip(
        self,
        protocol_version: int,
        client_id: str,
        password_hash: str,
        platform: int
    ):
        """
        **Feature: novachat-platform-expansion, Property 2: Packet Serialization Round-Trip (Cross-Language)**
        
        For any valid HandshakePacket, serializing and deserializing should
        produce an equivalent packet.
        
        **Validates: Requirements 11.2**
        """
        original = HandshakePacket(
            protocol_version=protocol_version,
            client_id=client_id,
            password_hash=password_hash,
            platform=platform
        )
        
        buffer = PacketBuffer()
        original.encode(buffer)
        
        buffer.reset_read()
        decoded = HandshakePacket.decode(buffer)
        
        assert decoded.protocol_version == original.protocol_version
        assert decoded.client_id == original.client_id
        assert decoded.password_hash == original.password_hash
        assert decoded.platform == original.platform
    
    @given(
        timestamp=st.integers(min_value=0, max_value=2**63 - 1)
    )
    @settings(max_examples=100)
    def test_keepalive_packet_round_trip(self, timestamp: int):
        """
        **Feature: novachat-platform-expansion, Property 2: Packet Serialization Round-Trip (Cross-Language)**
        
        For any valid KeepAlivePacket, serializing and deserializing should
        produce an equivalent packet.
        
        **Validates: Requirements 11.2**
        """
        original = KeepAlivePacket(timestamp=timestamp)
        
        buffer = PacketBuffer()
        original.encode(buffer)
        
        buffer.reset_read()
        decoded = KeepAlivePacket.decode(buffer)
        
        assert decoded.timestamp == original.timestamp
    
    @given(
        sender_name=st.text(min_size=1, max_size=16).filter(lambda x: x.isprintable()),
        client_id=st.text(min_size=1, max_size=50).filter(lambda x: x.isprintable()),
        channel_id=st.text(min_size=1, max_size=50).filter(lambda x: x.isprintable()),
        content=st.text(min_size=0, max_size=256).filter(lambda x: x.isprintable())
    )
    @settings(max_examples=100)
    def test_chat_message_packet_round_trip(
        self,
        sender_name: str,
        client_id: str,
        channel_id: str,
        content: str
    ):
        """
        **Feature: novachat-platform-expansion, Property 2: Packet Serialization Round-Trip (Cross-Language)**
        
        For any valid ChatMessagePacket, serializing and deserializing should
        produce an equivalent packet.
        
        **Validates: Requirements 11.2**
        """
        sender_id = uuid.uuid4()
        original = ChatMessagePacket(
            sender_id=sender_id,
            sender_name=sender_name,
            client_id=client_id,
            channel_id=channel_id,
            content=content
        )
        
        buffer = PacketBuffer()
        original.encode(buffer)
        
        buffer.reset_read()
        decoded = ChatMessagePacket.decode(buffer)
        
        assert decoded.sender_id == original.sender_id
        assert decoded.sender_name == original.sender_name
        assert decoded.client_id == original.client_id
        assert decoded.channel_id == original.channel_id
        assert decoded.content == original.content
