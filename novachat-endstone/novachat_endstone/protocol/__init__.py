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
    ChannelAction,
    AdminAction,
    PlatformType,
    HandshakePacket,
    HandshakeResponsePacket,
    ChatMessagePacket,
    ChannelActionPacket,
    ChannelActionResponsePacket,
    ConfigSyncPacket,
    KeepAlivePacket,
    AnnouncementPacket,
    TitleMessagePacket,
    ChannelUpdatePacket,
    AdminActionPacket,
    AdminActionResponsePacket,
    ItemDisplayPacket,
    MentionPacket,
    decode_packet,
)

__all__ = [
    "VarInt",
    "PacketBuffer",
    "Packet",
    "PacketIds",
    "ChannelAction",
    "AdminAction",
    "PlatformType",
    "HandshakePacket",
    "HandshakeResponsePacket",
    "ChatMessagePacket",
    "ChannelActionPacket",
    "ChannelActionResponsePacket",
    "ConfigSyncPacket",
    "KeepAlivePacket",
    "AnnouncementPacket",
    "TitleMessagePacket",
    "ChannelUpdatePacket",
    "AdminActionPacket",
    "AdminActionResponsePacket",
    "ItemDisplayPacket",
    "MentionPacket",
    "decode_packet",
]
