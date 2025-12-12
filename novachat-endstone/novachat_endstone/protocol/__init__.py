"""
NovaChat Protocol Implementation for Python/Endstone.

This package contains the NovaProtocol implementation including:
- VarInt encoding/decoding
- Packet buffer operations
- Packet type definitions
"""

from novachat_endstone.protocol.varint import VarInt
from novachat_endstone.protocol.buffer import PacketBuffer
from novachat_endstone.protocol.packet import (
    Packet,
    PacketIds,
    HandshakePacket,
    HandshakeResponsePacket,
    ChatMessagePacket,
    ChannelActionPacket,
    KeepAlivePacket,
    AnnouncementPacket,
    TitleMessagePacket,
    ChannelUpdatePacket,
)

__all__ = [
    "VarInt",
    "PacketBuffer",
    "Packet",
    "PacketIds",
    "HandshakePacket",
    "HandshakeResponsePacket",
    "ChatMessagePacket",
    "ChannelActionPacket",
    "KeepAlivePacket",
    "AnnouncementPacket",
    "TitleMessagePacket",
    "ChannelUpdatePacket",
]
