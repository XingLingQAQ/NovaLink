"""
Packet definitions for NovaProtocol.

This module contains all packet type definitions used in the NovaProtocol
for communication between NovaChat clients and NovaLink backend.
"""

from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import IntEnum
from typing import Optional, Dict, Any

from novachat_endstone.protocol.buffer import PacketBuffer
from novachat_endstone.protocol import protocol_limits as _pl


class ChannelAction:
    """Channel action wire IDs (must match Java ChannelAction enum)."""

    JOIN = 0
    LEAVE = 1
    CREATE = 2
    DELETE = 3
    INVITE = 4
    ACCEPT = 5
    KICK = 6
    MUTE = 7
    UNMUTE = 8
    BAN = 9
    UNBAN = 10
    WHO = 11


class AdminAction:
    """Admin action wire IDs (must match Java AdminAction enum)."""

    AUTH = 0
    LOGOUT = 1
    SPY_START = 2
    SPY_STOP = 3
    RELOAD = 4
    STATUS = 5


class PlatformType:
    """Platform type wire IDs (must match Java PlatformType enum)."""

    BUKKIT = 0
    VELOCITY = 1
    BUNGEECORD = 2
    NUKKIT = 3
    LEVILAMINA = 4
    FABRIC = 5
    NEOFORGE = 6
    QUILT = 7
    FORGE = 8
    POCKETMINE = 9
    ENDSTONE = 10
    POWERNUKKITX = 11
    FOLIA = 13
    SPONGE = 14


class PacketIds(IntEnum):
    """Packet ID constants (must match Java PacketIds)."""
    HANDSHAKE = 0x01
    HANDSHAKE_RESPONSE = 0x02
    CHAT_MESSAGE = 0x03
    CHANNEL_ACTION = 0x04
    CHANNEL_ACTION_RESPONSE = 0x05
    CONFIG_SYNC = 0x06
    KEEP_ALIVE = 0x07
    PLAYER_STATE = 0x08  # reserved
    TITLE = 0x09
    # 0x0A (ANNOUNCEMENT) is intentionally absent: the backend never sends an
    # Announcement packet. Announcements are delivered as AdminAction STATUS
    # with type=ANNOUNCE, routed to 0x03 ChatMessage by the backend. The
    # client-side AnnouncementPacket + handler were dead code (FEATURE-002).
    ADMIN_ACTION = 0x0B
    ADMIN_ACTION_RESPONSE = 0x0C
    CHANNEL_UPDATE = 0x0D  # optional (no Java class yet)
    ITEM_DISPLAY = 0x10
    MENTION = 0x12
    PRIVATE_MESSAGE = 0x14
    # ==================== Challenge-response handshake (AUTH-002) ====================
    HANDSHAKE_INIT = 0x15  # Client -> Server
    HANDSHAKE_CHALLENGE = 0x16  # Server -> Client
    HANDSHAKE_AUTHENTICATE = 0x17  # Client -> Server


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
    """Handshake packet sent by client to authenticate (protocol v2)."""

    protocol_version: int
    client_id: str
    password_hash: str
    platform: int  # Platform identifier byte
    server_version: str = ""

    @property
    def packet_id(self) -> int:
        return PacketIds.HANDSHAKE

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_varint(self.protocol_version)
        buffer.write_string(self.client_id or "")
        buffer.write_string(self.password_hash or "")
        buffer.write_byte(self.platform)
        buffer.write_string(self.server_version or "")

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "HandshakePacket":
        protocol_version = buffer.read_varint()
        client_id = buffer.read_string(_pl.MAX_CLIENT_ID)
        password_hash = buffer.read_string(_pl.MAX_PASSWORD_HASH)
        platform = buffer.read_byte()
        # Optional trailing serverVersion field (protocol v2+).
        server_version = ""
        if buffer.remaining() > 0:
            try:
                server_version = buffer.read_string(_pl.MAX_SERVER_VERSION)
            except Exception:
                server_version = ""
        return cls(
            protocol_version=protocol_version,
            client_id=client_id,
            password_hash=password_hash,
            platform=platform,
            server_version=server_version,
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
            error_code=buffer.read_string(_pl.MAX_ERROR_CODE),
            message=buffer.read_string(_pl.MAX_ERROR_MESSAGE)
        )


@dataclass
class HandshakeInitPacket(Packet):
    """Handshake init packet — first packet of the AUTH-002 challenge-response
    handshake (Client -> Server). Packet ID: 0x15.

    Replaces the replayable static-hash HandshakePacket. The client sends a
    fresh cryptographically-secure random nonce; the server replies with
    HandshakeChallengePacket carrying its own nonce, and the client proves
    possession of the password via an HMAC in HandshakeAuthenticatePacket.

    Wire (payload only; envelope written by send_packet):
        VarInt  protocol_version  (== PROTOCOL_VERSION, currently 3)
        String  client_id         (<= 64, VarInt-length UTF-8)
        Byte    platform          (PlatformType id; ENDSTONE = 10)
        String  server_version    (<= 64)
        String  client_nonce      (<= 64, 16 random bytes lowercase-hex = 32 chars)

    Field order MUST stay byte-for-byte in lockstep with the JVM
    HandshakeInitPacket (NovaChat/common) and the PHP/C++ forks.
    """

    protocol_version: int
    client_id: str
    platform: int
    server_version: str
    client_nonce: str

    @property
    def packet_id(self) -> int:
        return PacketIds.HANDSHAKE_INIT

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_varint(self.protocol_version)
        buffer.write_string(self.client_id or "")
        buffer.write_byte(self.platform)
        buffer.write_string(self.server_version or "")
        buffer.write_string(self.client_nonce or "")

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "HandshakeInitPacket":
        protocol_version = buffer.read_varint()
        client_id = buffer.read_string(_pl.MAX_CLIENT_ID)
        platform = buffer.read_byte()
        # Trailing fields are optional on decode for partial-frame tolerance,
        # mirroring the JVM HandshakeInitPacket#read.
        server_version = ""
        if buffer.remaining() > 0:
            try:
                server_version = buffer.read_string(_pl.MAX_SERVER_VERSION)
            except Exception:
                server_version = ""
        client_nonce = ""
        if buffer.remaining() > 0:
            try:
                client_nonce = buffer.read_string(_pl.MAX_NONCE)
            except Exception:
                client_nonce = ""
        return cls(
            protocol_version=protocol_version,
            client_id=client_id,
            platform=platform,
            server_version=server_version,
            client_nonce=client_nonce,
        )


@dataclass
class HandshakeChallengePacket(Packet):
    """Handshake challenge packet — second packet of the AUTH-002
    challenge-response handshake (Server -> Client). Packet ID: 0x16.

    The server generates a fresh 16-byte cryptographically-secure random nonce
    and returns it to the client in response to HandshakeInitPacket. The client
    combines this nonce with its own init nonce to compute the HMAC in
    HandshakeAuthenticatePacket.

    Wire (payload only):
        String  server_nonce  (<= 64, 16 random bytes lowercase-hex = 32 chars)
    """

    server_nonce: str

    @property
    def packet_id(self) -> int:
        return PacketIds.HANDSHAKE_CHALLENGE

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_string(self.server_nonce or "")

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "HandshakeChallengePacket":
        return cls(server_nonce=buffer.read_string(_pl.MAX_NONCE))


@dataclass
class HandshakeAuthenticatePacket(Packet):
    """Handshake authenticate packet — third and final packet of the AUTH-002
    challenge-response handshake (Client -> Server). Packet ID: 0x17.

    The client echoes its own nonce (the one sent in HandshakeInitPacket) and
    proves possession of the stored password hash by sending an HMAC-SHA-256
    over (server_nonce + client_nonce), keyed by sha256hex(password).

    Wire (payload only):
        String  client_id       (<= 64, must match the init packet's client_id)
        String  client_nonce    (<= 64, must echo the init packet's client_nonce)
        String  hmac            (<= 128, lowercase-hex HMAC-SHA-256)

    HMAC computation (must match JVM / PHP / C++ forks byte-for-byte):
        key      = utf-8 bytes of sha256hex(password)   # stored credential hash
        message  = utf-8 bytes of (server_nonce_hex + client_nonce_hex)  # concat
        output   = hmac.new(key, message, sha256).hexdigest()  # lowercase hex
    """

    client_id: str
    client_nonce: str
    hmac: str

    @property
    def packet_id(self) -> int:
        return PacketIds.HANDSHAKE_AUTHENTICATE

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_string(self.client_id or "")
        buffer.write_string(self.client_nonce or "")
        buffer.write_string(self.hmac or "")

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "HandshakeAuthenticatePacket":
        return cls(
            client_id=buffer.read_string(_pl.MAX_CLIENT_ID),
            client_nonce=buffer.read_string(_pl.MAX_NONCE),
            hmac=buffer.read_string(_pl.MAX_HMAC),
        )


@dataclass
class ChatMessagePacket(Packet):
    """Chat message packet."""
    
    sender_id: uuid.UUID
    sender_name: str
    client_id: str
    channel_id: str
    content: str
    # PlaceholderAPI variables; dict preserves insertion order on re-encode.
    placeholders: Dict[str, str] = field(default_factory=dict)
    
    @property
    def packet_id(self) -> int:
        return PacketIds.CHAT_MESSAGE
    
    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_uuid(self.sender_id)
        buffer.write_string(self.sender_name)
        buffer.write_string(self.client_id)
        buffer.write_string(self.channel_id)
        buffer.write_string(self.content)
        # Placeholders map, matching the Java encoder byte-for-byte.
        buffer.write_varint(len(self.placeholders))
        for key, value in self.placeholders.items():
            buffer.write_string(key)
            buffer.write_string(value)
    
    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "ChatMessagePacket":
        sender_id = buffer.read_uuid()
        sender_name = buffer.read_string(_pl.MAX_SENDER_NAME)
        client_id = buffer.read_string(_pl.MAX_CLIENT_ID)
        channel_id = buffer.read_string(_pl.MAX_CHANNEL_ID)
        content = buffer.read_string(_pl.MAX_MESSAGE_CONTENT)

        # Placeholders map (optional for legacy peers), kept like Java does.
        placeholders: Dict[str, str] = {}
        if buffer.remaining() > 0:
            try:
                size = buffer.read_varint()
            except Exception:
                # Legacy payload ended after content; treat as no placeholders.
                size = 0
            if 0 <= size <= 1000:  # defensive bound, mirrors Java
                for _ in range(size):
                    key = buffer.read_string(_pl.MAX_METADATA_KEY)
                    placeholders[key] = buffer.read_string(_pl.MAX_METADATA_VALUE)

        return cls(
            sender_id=sender_id,
            sender_name=sender_name,
            client_id=client_id,
            channel_id=channel_id,
            content=content,
            placeholders=placeholders,
        )


@dataclass
class ChannelActionPacket(Packet):
    """Channel action packet for join/leave/create/kick/mute/who operations."""

    action: int  # Action type byte (see ChannelAction)
    channel_id: str
    password: str
    extra: Dict[str, str]

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
        channel_id = buffer.read_string(_pl.MAX_CHANNEL_ID)
        password = buffer.read_string(_pl.MAX_CHANNEL_PASSWORD)

        extra: Dict[str, str] = {}
        if buffer.remaining() > 0:
            try:
                size = buffer.read_varint()
                for _ in range(max(0, size)):
                    k = buffer.read_string(_pl.MAX_METADATA_KEY)
                    v = buffer.read_string(_pl.MAX_METADATA_VALUE)
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
            channel_id=buffer.read_string(_pl.MAX_CHANNEL_ID),
            update_type=buffer.read_byte(),
            data_json=buffer.read_string(_pl.MAX_ACTION_JSON)
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
            channel_id=buffer.read_string(_pl.MAX_CHANNEL_ID),
            title=buffer.read_string(_pl.MAX_TITLE),
            subtitle=buffer.read_string(_pl.MAX_SUBTITLE),
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
        channel_id = buffer.read_string(_pl.MAX_CHANNEL_ID)
        error_code = buffer.read_string(_pl.MAX_ERROR_CODE)
        message = buffer.read_string(_pl.MAX_ERROR_MESSAGE)

        extra: Dict[str, str] = {}
        if buffer.remaining() > 0:
            try:
                size = buffer.read_varint()
                for _ in range(max(0, size)):
                    k = buffer.read_string(_pl.MAX_METADATA_KEY)
                    v = buffer.read_string(_pl.MAX_METADATA_VALUE)
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
        # Java only normalizes null/None to "{}"; an explicit empty string
        # must be preserved on the wire.
        buffer.write_string("{}" if self.config_json is None else self.config_json)
        buffer.write_long(self.timestamp)

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "ConfigSyncPacket":
        return cls(
            config_json=buffer.read_string(_pl.MAX_CONFIG_SYNC_JSON),
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
        password_hash = buffer.read_string(_pl.MAX_PASSWORD_HASH)
        target = buffer.read_string(_pl.MAX_CHANNEL_ID)

        extra: Dict[str, str] = {}
        if buffer.remaining() > 0:
            try:
                size = buffer.read_varint()
                for _ in range(max(0, size)):
                    k = buffer.read_string(_pl.MAX_METADATA_KEY)
                    v = buffer.read_string(_pl.MAX_METADATA_VALUE)
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
            error_code=buffer.read_string(_pl.MAX_ERROR_CODE),
            message=buffer.read_string(_pl.MAX_ERROR_MESSAGE),
        )


@dataclass
class ItemDisplayPacket(Packet):
    """Item display packet for [item]/[i] tag display across servers.

    Packet ID: 0x10, Direction: Bidirectional.
    """

    sender_id: uuid.UUID
    sender_name: str
    channel_id: str
    item_json: str
    timestamp: int

    @property
    def packet_id(self) -> int:
        return PacketIds.ITEM_DISPLAY

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_uuid(self.sender_id)
        buffer.write_string(self.sender_name or "")
        buffer.write_string(self.channel_id or "")
        buffer.write_string(self.item_json or "")
        buffer.write_long(self.timestamp)

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "ItemDisplayPacket":
        return cls(
            sender_id=buffer.read_uuid(),
            sender_name=buffer.read_string(_pl.MAX_SENDER_NAME),
            channel_id=buffer.read_string(_pl.MAX_CHANNEL_ID),
            item_json=buffer.read_string(_pl.MAX_ITEM_JSON),
            timestamp=buffer.read_long(),
        )


@dataclass
class MentionPacket(Packet):
    """Mention notification packet for @mention highlight + sound/title.

    Packet ID: 0x12, Direction: Server -> Client.
    """

    mentioner_id: uuid.UUID
    mentioner_name: str
    mentioned_id: uuid.UUID
    channel_id: str
    message_preview: str
    timestamp: int

    @property
    def packet_id(self) -> int:
        return PacketIds.MENTION

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_uuid(self.mentioner_id)
        buffer.write_string(self.mentioner_name or "")
        buffer.write_uuid(self.mentioned_id)
        buffer.write_string(self.channel_id or "")
        buffer.write_string(self.message_preview or "")
        buffer.write_long(self.timestamp)

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "MentionPacket":
        return cls(
            mentioner_id=buffer.read_uuid(),
            mentioner_name=buffer.read_string(_pl.MAX_SENDER_NAME),
            mentioned_id=buffer.read_uuid(),
            channel_id=buffer.read_string(_pl.MAX_CHANNEL_ID),
            message_preview=buffer.read_string(_pl.MAX_MESSAGE_PREVIEW),
            timestamp=buffer.read_long(),
        )


@dataclass
class PrivateMessagePacket(Packet):
    """Private message packet for cross-server /msg + /reply.

    Packet ID: 0x14, Direction: Bidirectional.

    Client -> Server: sender fields + target_name are filled; target_id may be
    the nil UUID — the backend resolves the target by name. Server -> Client:
    the backend fills the real target_id and the authoritative timestamp; the
    plugin renders the sent/received line depending on which local player
    matches sender_id/target_id.
    """

    sender_id: uuid.UUID
    sender_name: str
    sender_client_id: str
    target_name: str
    target_id: uuid.UUID
    content: str
    timestamp: int

    @property
    def packet_id(self) -> int:
        return PacketIds.PRIVATE_MESSAGE

    def encode(self, buffer: PacketBuffer) -> None:
        buffer.write_uuid(self.sender_id)
        buffer.write_string(self.sender_name or "")
        buffer.write_string(self.sender_client_id or "")
        buffer.write_string(self.target_name or "")
        buffer.write_uuid(self.target_id)
        buffer.write_string(self.content or "")
        buffer.write_long(self.timestamp)

    @classmethod
    def decode(cls, buffer: PacketBuffer) -> "PrivateMessagePacket":
        return cls(
            sender_id=buffer.read_uuid(),
            sender_name=buffer.read_string(_pl.MAX_SENDER_NAME),
            sender_client_id=buffer.read_string(_pl.MAX_CLIENT_ID),
            target_name=buffer.read_string(_pl.MAX_TARGET_NAME),
            target_id=buffer.read_uuid(),
            content=buffer.read_string(_pl.MAX_MESSAGE_CONTENT),
            timestamp=buffer.read_long(),
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
    PacketIds.HANDSHAKE_INIT: HandshakeInitPacket,
    PacketIds.HANDSHAKE_CHALLENGE: HandshakeChallengePacket,
    PacketIds.HANDSHAKE_AUTHENTICATE: HandshakeAuthenticatePacket,
    PacketIds.CHAT_MESSAGE: ChatMessagePacket,
    PacketIds.CHANNEL_ACTION: ChannelActionPacket,
    PacketIds.CHANNEL_ACTION_RESPONSE: ChannelActionResponsePacket,
    PacketIds.CONFIG_SYNC: ConfigSyncPacket,
    PacketIds.KEEP_ALIVE: KeepAlivePacket,
    PacketIds.TITLE: TitleMessagePacket,
    PacketIds.ADMIN_ACTION: AdminActionPacket,
    PacketIds.ADMIN_ACTION_RESPONSE: AdminActionResponsePacket,
    PacketIds.CHANNEL_UPDATE: ChannelUpdatePacket,
    PacketIds.ITEM_DISPLAY: ItemDisplayPacket,
    PacketIds.MENTION: MentionPacket,
    PacketIds.PRIVATE_MESSAGE: PrivateMessagePacket,
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
