"""
Protocol tests for NovaChat-Endstone.

This module contains unit tests and property-based tests for the
protocol implementation including VarInt, packet serialization, the
protocol-v2 HandshakePacket (with server_version), and the full packet
set aligned with the Java NovaProtocol (ItemDisplay, Mention,
ConfigSync, ChannelActionResponse).
"""

import pytest
import uuid
from hypothesis import given, strategies as st, settings

from novachat_endstone.protocol.varint import VarInt
from novachat_endstone.protocol.buffer import PacketBuffer
from novachat_endstone.protocol import protocol_limits as _pl
from novachat_endstone.protocol.packet import (
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
    TitleMessagePacket,
    ChannelUpdatePacket,
    AdminActionPacket,
    AdminActionResponsePacket,
    ItemDisplayPacket,
    MentionPacket,
)


def _bounded_text(byte_limit: int, min_size: int = 1):
    """Printable text whose UTF-8 byte length is <= ``byte_limit``.

    PROTO-003 bounds each string field by its on-wire UTF-8 byte count, so the
    property tests must generate inputs that fit that byte budget. ``st.text``
    bounds by *character* count, which lets multibyte chars (e.g. ``\\u0800``
    is 3 bytes) overrun the field limit. Filtering by ``len(x.encode("utf-8"))``
    keeps the unicode coverage while honouring the wire bound.
    """
    return st.text(min_size=min_size, max_size=byte_limit).filter(
        lambda x: x.isprintable() and len(x.encode("utf-8")) <= byte_limit
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
        client_id=_bounded_text(_pl.MAX_CLIENT_ID),
        password_hash=_bounded_text(_pl.MAX_PASSWORD_HASH),
        platform=st.integers(min_value=0, max_value=255),
        server_version=_bounded_text(_pl.MAX_SERVER_VERSION, min_size=0),
    )
    @settings(max_examples=100)
    def test_handshake_packet_round_trip(
        self,
        protocol_version: int,
        client_id: str,
        password_hash: str,
        platform: int,
        server_version: str,
    ):
        """
        **Feature: novachat-platform-expansion, Property 2: Packet Serialization Round-Trip (Cross-Language)**

        For any valid HandshakePacket (protocol v2 with server_version),
        serializing and deserializing should produce an equivalent packet.

        **Validates: Requirements 11.2**
        """
        original = HandshakePacket(
            protocol_version=protocol_version,
            client_id=client_id,
            password_hash=password_hash,
            platform=platform,
            server_version=server_version,
        )

        buffer = PacketBuffer()
        original.encode(buffer)

        buffer.reset_read()
        decoded = HandshakePacket.decode(buffer)

        assert decoded.protocol_version == original.protocol_version
        assert decoded.client_id == original.client_id
        assert decoded.password_hash == original.password_hash
        assert decoded.platform == original.platform
        assert decoded.server_version == original.server_version
    
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
        sender_name=_bounded_text(_pl.MAX_SENDER_NAME),
        client_id=_bounded_text(_pl.MAX_CLIENT_ID),
        channel_id=_bounded_text(_pl.MAX_CHANNEL_ID),
        content=_bounded_text(_pl.MAX_MESSAGE_CONTENT, min_size=0),
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


# ==================== Feature-parity alignment tests (protocol v2 + full packet set) ====================

class TestChannelActionIds:
    """ChannelAction wire IDs must match the Java ChannelAction enum exactly."""

    def test_action_ids_match_java(self):
        assert ChannelAction.JOIN == 0
        assert ChannelAction.LEAVE == 1
        assert ChannelAction.CREATE == 2
        assert ChannelAction.DELETE == 3
        assert ChannelAction.INVITE == 4
        assert ChannelAction.ACCEPT == 5
        assert ChannelAction.KICK == 6
        assert ChannelAction.MUTE == 7
        assert ChannelAction.UNMUTE == 8
        assert ChannelAction.BAN == 9
        assert ChannelAction.UNBAN == 10
        assert ChannelAction.WHO == 11

    def test_admin_action_ids_match_java(self):
        assert AdminAction.AUTH == 0
        assert AdminAction.LOGOUT == 1
        assert AdminAction.SPY_START == 2
        assert AdminAction.SPY_STOP == 3
        assert AdminAction.RELOAD == 4
        assert AdminAction.STATUS == 5

    def test_platform_ids_match_java(self):
        assert PlatformType.ENDSTONE == 10
        assert PlatformType.LEVILAMINA == 4
        assert PlatformType.POCKETMINE == 9
        assert PlatformType.BUKKIT == 0

    def test_packet_ids_match_java(self):
        assert PacketIds.HANDSHAKE == 0x01
        assert PacketIds.HANDSHAKE_RESPONSE == 0x02
        assert PacketIds.CHAT_MESSAGE == 0x03
        assert PacketIds.CHANNEL_ACTION == 0x04
        assert PacketIds.CHANNEL_ACTION_RESPONSE == 0x05
        assert PacketIds.CONFIG_SYNC == 0x06
        assert PacketIds.KEEP_ALIVE == 0x07
        assert PacketIds.TITLE == 0x09
        assert PacketIds.ADMIN_ACTION == 0x0B
        assert PacketIds.ADMIN_ACTION_RESPONSE == 0x0C
        assert PacketIds.ITEM_DISPLAY == 0x10
        assert PacketIds.MENTION == 0x12


class TestItemDisplayPacket:
    """ItemDisplayPacket (0x10) round-trip — [item]/[i] tag display."""

    def test_round_trip(self):
        sender_id = uuid.uuid4()
        original = ItemDisplayPacket(
            sender_id=sender_id,
            sender_name="Steve",
            channel_id="global",
            item_json='{"id":"minecraft:diamond"}',
            timestamp=1700000000000,
        )
        buffer = PacketBuffer()
        original.encode(buffer)
        buffer.reset_read()
        decoded = ItemDisplayPacket.decode(buffer)

        assert decoded.packet_id == PacketIds.ITEM_DISPLAY
        assert decoded.sender_id == original.sender_id
        assert decoded.sender_name == "Steve"
        assert decoded.channel_id == "global"
        assert decoded.item_json == '{"id":"minecraft:diamond"}'
        assert decoded.timestamp == 1700000000000


class TestMentionPacket:
    """MentionPacket (0x12) round-trip — @mention highlight notification."""

    def test_round_trip(self):
        mentioner = uuid.uuid4()
        mentioned = uuid.uuid4()
        original = MentionPacket(
            mentioner_id=mentioner,
            mentioner_name="Alex",
            mentioned_id=mentioned,
            channel_id="local",
            message_preview="hey @you",
            timestamp=1700000000123,
        )
        buffer = PacketBuffer()
        original.encode(buffer)
        buffer.reset_read()
        decoded = MentionPacket.decode(buffer)

        assert decoded.packet_id == PacketIds.MENTION
        assert decoded.mentioner_id == mentioner
        assert decoded.mentioner_name == "Alex"
        assert decoded.mentioned_id == mentioned
        assert decoded.channel_id == "local"
        assert decoded.message_preview == "hey @you"
        assert decoded.timestamp == 1700000000123


class TestConfigSyncPacket:
    """ConfigSyncPacket (0x06) round-trip — backend channel config sync."""

    def test_round_trip(self):
        config_json = '{"channels":["global","local"]}'
        original = ConfigSyncPacket(config_json=config_json, timestamp=1700000000000)
        buffer = PacketBuffer()
        original.encode(buffer)
        buffer.reset_read()
        decoded = ConfigSyncPacket.decode(buffer)

        assert decoded.packet_id == PacketIds.CONFIG_SYNC
        assert decoded.config_json == config_json
        assert decoded.timestamp == 1700000000000

    def test_null_config_json_defaults_to_empty_object(self):
        original = ConfigSyncPacket(config_json=None, timestamp=0)
        buffer = PacketBuffer()
        original.encode(buffer)
        buffer.reset_read()
        decoded = ConfigSyncPacket.decode(buffer)
        assert decoded.config_json == "{}"


class TestChannelActionResponsePacket:
    """ChannelActionResponsePacket (0x05) round-trip — async response routing."""

    def test_round_trip_success(self):
        original = ChannelActionResponsePacket(
            success=True,
            action=ChannelAction.JOIN,
            channel_id="global",
            error_code="",
            message="OK",
            extra={"key": "value"},
        )
        buffer = PacketBuffer()
        original.encode(buffer)
        buffer.reset_read()
        decoded = ChannelActionResponsePacket.decode(buffer)

        assert decoded.packet_id == PacketIds.CHANNEL_ACTION_RESPONSE
        assert decoded.success is True
        assert decoded.action == ChannelAction.JOIN
        assert decoded.channel_id == "global"
        assert decoded.error_code == ""
        assert decoded.message == "OK"
        assert decoded.extra == {"key": "value"}

    def test_round_trip_failure_with_error_code(self):
        original = ChannelActionResponsePacket(
            success=False,
            action=ChannelAction.WHO,
            channel_id="missing",
            error_code="NC-404",
            message="Not found",
            extra={},
        )
        buffer = PacketBuffer()
        original.encode(buffer)
        buffer.reset_read()
        decoded = ChannelActionResponsePacket.decode(buffer)

        assert decoded.success is False
        assert decoded.action == ChannelAction.WHO
        assert decoded.error_code == "NC-404"
        assert decoded.message == "Not found"


class TestHandshakeV2:
    """HandshakePacket protocol v2 — server_version field + platform byte."""

    def test_encode_includes_server_version(self):
        pkt = HandshakePacket(
            protocol_version=2,
            client_id="srv",
            password_hash="abc",
            platform=PlatformType.ENDSTONE,
            server_version="1.20.4",
        )
        buffer = PacketBuffer()
        pkt.encode(buffer)
        # protocol_version(varint=1) + clientId(len+3) + passwordHash(len+3) + platform(1) + serverVersion(len+6)
        data = buffer.get_bytes()
        # platform byte is right before the server_version varint-length
        # protocol_version=2 -> 0x02 ; clientId "srv" -> 0x03 's' 'r' 'v'
        assert data[0] == 0x02  # protocol version
        assert data[1] == 0x03  # clientId length
        # platform byte at offset 1+1+3+1+3 = 9
        assert data[9] == PlatformType.ENDSTONE
        # server_version length at offset 10
        assert data[10] == 0x06  # "1.20.4" length
        # total length: 1 + (1+3) + (1+3) + 1 + (1+6) = 17
        assert len(data) == 17

    def test_decode_without_server_version_is_backward_compatible(self):
        # Simulate a v1-style payload (no trailing server_version).
        buffer = PacketBuffer()
        buffer.write_varint(2)
        buffer.write_string("srv")
        buffer.write_string("abc")
        buffer.write_byte(PlatformType.ENDSTONE)
        buffer.reset_read()
        decoded = HandshakePacket.decode(buffer)
        assert decoded.server_version == ""


class TestI18nProvider:
    """I18n message lookup keyed on the Java client-core bundle keys."""

    def test_zh_cn_default_lookup(self):
        from novachat_endstone.i18n import I18n
        i18n = I18n()
        msg = i18n.get("chat.join.joined", "zh_CN", "global")
        assert "已加入频道" in msg
        assert "global" in msg

    def test_en_us_lookup(self):
        from novachat_endstone.i18n import I18n
        i18n = I18n()
        msg = i18n.get("chat.join.joined", "en_US", "global")
        assert "Joined channel" in msg
        assert "global" in msg

    def test_falls_back_to_zh_cn_for_missing_en_key(self):
        from novachat_endstone.i18n import I18n
        i18n = I18n()
        # A key that exists only in zh_CN (none in our trimmed set, but the
        # mechanism is: en_US missing -> zh_CN -> key itself).
        msg = i18n.get("nonexistent.key", "en_US")
        # Falls back to the key itself when absent from both bundles.
        assert msg == "nonexistent.key"

    def test_error_message_combines_message_and_suggestion(self):
        from novachat_endstone.i18n import I18n
        i18n = I18n()
        msg = i18n.error_message("NC-404", "zh_CN")
        assert "资源不存在" in msg
        assert "请检查频道ID或玩家名称是否正确" in msg

    def test_kick_mute_notice_keys_exist(self):
        from novachat_endstone.i18n import I18n
        i18n = I18n()
        assert "踢出" in i18n.get("chat.notice.kick_title", "zh_CN")
        assert "禁言" in i18n.get("chat.notice.mute_title", "zh_CN")
        assert "kicked" in i18n.get("chat.notice.kick_title", "en_US").lower()
        assert "muted" in i18n.get("chat.notice.mute_title", "en_US").lower()
