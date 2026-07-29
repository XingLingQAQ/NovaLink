"""
Packet definitions for NovaProtocol.

This module contains all packet type definitions used in the NovaProtocol
for communication between NovaChat clients and NovaLink backend.
"""

from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import IntEnum
from typing import Optional, Dict, Any

from novachat_endstone.protocol.buffer import PacketBuffer


class PacketIds(IntEnum):
    """Packet ID constants."""
    HANDSHAKE = 0x01
    HANDSHAKE_RESPONSE = 0x02
    CHAT_MESSAGE = 0x03
    CHANNEL_ACTION = 0x04
    CHANNEL_ACTION_RESPONSE = 0x05
    CONFIG_SYNC = 0x06
    KEEP_ALIVE = 0x07
    TITLE = 0x09
    ANNOUNCEMENT = 0x0A  # optional (Java backend currently prefers ChatMessage + placeholder)
    ADMIN_ACTION = 0x0B
    ADMIN_ACTION_RESPONSE = 0x0C
    CHANNEL_UPDATE = 0x0D  # optional


class Packet(ABC):
    """Base class for all packets."""
    
    @property
    @abstractmethod
    def packet_id(self) -> int:
        """Get the packet ID."""
        pass
    
    @abstractmethod
    def encode(self, buffer: PacketBuffer) -> None:
        """Encode the packet to a buffer."""
        pass
    
    @classmethod
    @abstractmethod
    def decode(cls, buffer: PacketBuffer) -> "Packet":
        """Decode a packet from a buffer."""
        pass


@dataclass
class HandshakePacket(Packet):
    """Handshake packet sent by client to authenticate."""
    
    protocol_version: int
    client_id: str
    password_hash: str
    platform: int  # Platform identifier byte
    
    @property
    def packet_id(self) -> int:
        return PacketIds.HANDSHAKE
    
    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_varint(self.protocol_version)
        buffer.write_string(self.client_id)
        buffer.write_string(self.password_hash)
        buffer.write_byte(self.platform)
    
    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "HandshakePacket":
        return cls(
            protocol_version=buffer.read_varint(),
            client_id=buffer.read_string(),
            password_hash=buffer.read_string(),
            platform=buffer.read_byte()
        )


@dataclass
class HandshakeResponsePacket(Packet):
    """Handshake response packet sent by server."""
    
    success: bool
    error_code: str
    message: str
    
    @property
    def packet_id(self) -> int:
        return PacketIds.HANDSHAKE_RESPONSE
    
    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_boolean(self.success)
        buffer.write_string(self.error_code)
        buffer.write_string(self.message)
    
    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "HandshakeResponsePacket":
        return cls(
            success=buffer.read_boolean(),
            error_code=buffer.read_string(),
            message=buffer.read_string()
        )


@dataclass
class ChatMessagePacket(Packet):
    """Chat message packet."""
    
    sender_id: uuid.UUID
    sender_name: str
    client_id: str
    channel_id: str
    content: str
    
    @property
    def packet_id(self) -> int:
        return PacketIds.CHAT_MESSAGE
    
    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_uuid(self.sender_id)
        buffer.write_string(self.sender_name)
        buffer.write_string(self.client_id)
        buffer.write_string(self.channel_id)
        buffer.write_string(self.content)
        # Placeholders map (optional). Keep empty for this client.
        buffer.write_varint(0)
    
    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "ChatMessagePacket":
        pkt = cls(
            sender_id=buffer.read_uuid(),
            sender_name=buffer.read_string(),
            client_id=buffer.read_string(),
            channel_id=buffer.read_string(),
            content=buffer.read_string()
        )
        # Consume optional placeholders map if present (ignore contents).
        try:
            if buffer.remaining() > 0:
                size = buffer.read_varint()
                for _ in range(max(0, size)):
                    _ = buffer.read_string()
                    _ = buffer.read_string()
        except Exception:
            pass
        return pkt


@dataclass
class ChannelActionPacket(Packet):
    """Channel action packet for join/leave/create operations."""
    
    action: int  # Action type byte
    channel_id: str
    password: str
    extra: Dict[str, str]
    
    # Action constants
    ACTION_JOIN = 0
    ACTION_LEAVE = 1
    ACTION_CREATE = 2
    ACTION_DELETE = 3
    ACTION_INVITE = 4
    
    @property
    def packet_id(self) -> int:
        return PacketIds.CHANNEL_ACTION
    
    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_byte(self.action)
        buffer.write_string(self.channel_id)
        buffer.write_string(self.password)
        buffer.write_varint(len(self.extra))
        for k, v in self.extra.items():
            buffer.write_string(k)
            buffer.write_string(v)
    
    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "ChannelActionPacket":
        action = buffer.read_byte()
        channel_id = buffer.read_string()
        password = buffer.read_string()

        extra: Dict[str, str] = {}
        if buffer.remaining() > 0:
            try:
                size = buffer.read_varint()
                for _ in range(max(0, size)):
                    k = buffer.read_string()
                    v = buffer.read_string()
                    extra[k] = v
            except Exception:
                extra = {}

        return cls(
            action=action,
            channel_id=channel_id,
            password=password,
            extra=extra,
        )


@dataclass
class ChannelUpdatePacket(Packet):
    """Channel update notification packet."""
    
    channel_id: str
    update_type: int
    data_json: str
    
    @property
    def packet_id(self) -> int:
        return PacketIds.CHANNEL_UPDATE
    
    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_string(self.channel_id)
        buffer.write_byte(self.update_type)
        buffer.write_string(self.data_json)
    
    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "ChannelUpdatePacket":
        return cls(
            channel_id=buffer.read_string(),
            update_type=buffer.read_byte(),
            data_json=buffer.read_string()
        )


@dataclass
class AnnouncementPacket(Packet):
    """Server announcement packet."""
    
    announcement_type: int
    message: str
    
    @property
    def packet_id(self) -> int:
        return PacketIds.ANNOUNCEMENT
    
    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_byte(self.announcement_type)
        buffer.write_string(self.message)
    
    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "AnnouncementPacket":
        return cls(
            announcement_type=buffer.read_byte(),
            message=buffer.read_string()
        )


@dataclass
class KeepAlivePacket(Packet):
    """Keep-alive packet for connection maintenance."""
    
    timestamp: int
    
    @property
    def packet_id(self) -> int:
        return PacketIds.KEEP_ALIVE
    
    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_long(self.timestamp)
    
    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "KeepAlivePacket":
        return cls(
            timestamp=buffer.read_long()
        )


@dataclass
class TitleMessagePacket(Packet):
    """Title message packet for displaying titles to players."""
    
    channel_id: str  # Empty for broadcast
    title: str
    subtitle: str
    fade_in: int
    stay: int
    fade_out: int
    sender_id: uuid.UUID
    
    @property
    def packet_id(self) -> int:
        return PacketIds.TITLE
    
    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_string(self.channel_id)
        buffer.write_string(self.title)
        buffer.write_string(self.subtitle)
        buffer.write_int(self.fade_in)
        buffer.write_int(self.stay)
        buffer.write_int(self.fade_out)
        buffer.write_uuid(self.sender_id)
    
    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "TitleMessagePacket":
        return cls(
            channel_id=buffer.read_string(),
            title=buffer.read_string(),
            subtitle=buffer.read_string(),
            fade_in=buffer.read_int(),
            stay=buffer.read_int(),
            fade_out=buffer.read_int(),
            sender_id=buffer.read_uuid(),
        )


@dataclass
class ChannelActionResponsePacket(Packet):
    """Channel action response packet."""

    success: bool
    action: int
    channel_id: str
    error_code: str
    message: str
    extra: Dict[str, str]

    @property
    def packet_id(self) -> int:
        return PacketIds.CHANNEL_ACTION_RESPONSE

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_boolean(self.success)
        buffer.write_byte(self.action)
        buffer.write_string(self.channel_id)
        buffer.write_string(self.error_code)
        buffer.write_string(self.message)
        buffer.write_varint(len(self.extra))
        for k, v in self.extra.items():
            buffer.write_string(k)
            buffer.write_string(v)

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "ChannelActionResponsePacket":
        success = buffer.read_boolean()
        action = buffer.read_byte()
        channel_id = buffer.read_string()
        error_code = buffer.read_string()
        message = buffer.read_string()

        extra: Dict[str, str] = {}
        if buffer.remaining() > 0:
            try:
                size = buffer.read_varint()
                for _ in range(max(0, size)):
                    k = buffer.read_string()
                    v = buffer.read_string()
                    extra[k] = v
            except Exception:
                extra = {}

        return cls(
            success=success,
            action=action,
            channel_id=channel_id,
            error_code=error_code,
            message=message,
            extra=extra,
        )


@dataclass
class ConfigSyncPacket(Packet):
    """Configuration sync packet."""

    config_json: str
    timestamp: int

    @property
    def packet_id(self) -> int:
        return PacketIds.CONFIG_SYNC

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_string(self.config_json or "{}")
        buffer.write_long(self.timestamp)

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "ConfigSyncPacket":
        return cls(
            config_json=buffer.read_string(),
            timestamp=buffer.read_long(),
        )


@dataclass
class AdminActionPacket(Packet):
    """Admin action packet."""

    action: int
    player_id: uuid.UUID
    password_hash: str
    target: str
    extra: Dict[str, str]

    @property
    def packet_id(self) -> int:
        return PacketIds.ADMIN_ACTION

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_byte(self.action)
        buffer.write_uuid(self.player_id)
        buffer.write_string(self.password_hash)
        buffer.write_string(self.target)
        buffer.write_varint(len(self.extra))
        for k, v in self.extra.items():
            buffer.write_string(k)
            buffer.write_string(v)

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "AdminActionPacket":
        action = buffer.read_byte()
        player_id = buffer.read_uuid()
        password_hash = buffer.read_string()
        target = buffer.read_string()

        extra: Dict[str, str] = {}
        if buffer.remaining() > 0:
            try:
                size = buffer.read_varint()
                for _ in range(max(0, size)):
                    k = buffer.read_string()
                    v = buffer.read_string()
                    extra[k] = v
            except Exception:
                extra = {}

        return cls(
            action=action,
            player_id=player_id,
            password_hash=password_hash,
            target=target,
            extra=extra,
        )


@dataclass
class AdminActionResponsePacket(Packet):
    """Admin action response packet."""

    action: int
    success: bool
    error_code: str
    message: str

    @property
    def packet_id(self) -> int:
        return PacketIds.ADMIN_ACTION_RESPONSE

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_byte(self.action)
        buffer.write_boolean(self.success)
        buffer.write_string(self.error_code)
        buffer.write_string(self.message)

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "AdminActionResponsePacket":
        return cls(
            action=buffer.read_byte(),
            success=buffer.read_boolean(),
            error_code=buffer.read_string(),
            message=buffer.read_string(),
        )


@dataclass
class UnknownPacket(Packet):
    """Represents an unknown/unsupported packet type."""

    unknown_id: int

    @property
    def packet_id(self) -> int:
        return self.unknown_id

    def encode(self, buffer: PacketBuffer) -> None:
        return

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "UnknownPacket":
        return cls(unknown_id=-1)


# Packet registry for decoding
PACKET_REGISTRY: Dict[int, type] = {
    PacketIds.HANDSHAKE: HandshakePacket,
    PacketIds.HANDSHAKE_RESPONSE: HandshakeResponsePacket,
    PacketIds.CHAT_MESSAGE: ChatMessagePacket,
    PacketIds.CHANNEL_ACTION: ChannelActionPacket,
    PacketIds.ANNOUNCEMENT: AnnouncementPacket,
    PacketIds.CHANNEL_ACTION_RESPONSE: ChannelActionResponsePacket,
    PacketIds.CONFIG_SYNC: ConfigSyncPacket,
    PacketIds.KEEP_ALIVE: KeepAlivePacket,
    PacketIds.TITLE: TitleMessagePacket,
    PacketIds.ADMIN_ACTION: AdminActionPacket,
    PacketIds.ADMIN_ACTION_RESPONSE: AdminActionResponsePacket,
    PacketIds.CHANNEL_UPDATE: ChannelUpdatePacket,
}


def decode_packet(packet_id: int, buffer: PacketBuffer) -> Packet:
    """
    Decode a packet from a buffer given its ID.
    
    Args:
        packet_id: The packet ID
        buffer: The buffer containing packet data
        
    Returns:
        The decoded packet
        
    Raises:
        ValueError: If the packet ID is unknown
    """
    packet_class = PACKET_REGISTRY.get(packet_id)
    if packet_class is None:
        # Unknown packets should not tear down the connection.
        _ = buffer.read_bytes(buffer.remaining())
        return UnknownPacket(unknown_id=packet_id)
    return packet_class.decode(buffer)
