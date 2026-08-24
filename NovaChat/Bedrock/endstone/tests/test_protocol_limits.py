"""
PROTO-002 / PROTO-003 contract tests for the Endstone ProtocolLimits mirror.

Mirrors the JVM ``ProtocolLimitsTest`` (constant pinning + invariant that the
ConfigSync budget is strictly under the frame ceiling and every per-field
limit is <= MAX_FRAME_LENGTH) and ``StringFieldLimitTest`` (for a
representative set of packets, a bounded string field round-trips at ``max-1``
and ``max`` and is rejected at ``max+1`` with a ValueError whose message
contains ``"exceeds maximum"``).

The byte values MUST stay byte-for-byte identical to the Java
``com.nova.chat.common.protocol.ProtocolLimits`` source of truth; this suite
pins them so a drift is caught on the next Python test run.
"""

from __future__ import annotations

import uuid

import pytest

from novachat_endstone.protocol.buffer import PacketBuffer
from novachat_endstone.protocol.packet import (
    ChannelActionResponsePacket,
    ConfigSyncPacket,
    ItemDisplayPacket,
    PrivateMessagePacket,
    TitleMessagePacket,
)
from novachat_endstone.protocol.protocol_limits import (
    MAX_ACTION_JSON,
    MAX_CHANNEL_ID,
    MAX_CHANNEL_PASSWORD,
    MAX_CLIENT_ID,
    MAX_CONFIG_SYNC_JSON,
    MAX_ERROR_CODE,
    MAX_ERROR_MESSAGE,
    MAX_FRAME_LENGTH,
    MAX_HMAC,
    MAX_ITEM_JSON,
    MAX_MESSAGE_CONTENT,
    MAX_MESSAGE_PREVIEW,
    MAX_METADATA_KEY,
    MAX_METADATA_VALUE,
    MAX_NONCE,
    MAX_PASSWORD_HASH,
    MAX_SENDER_NAME,
    MAX_SERVER_VERSION,
    MAX_SUBTITLE,
    MAX_TARGET_NAME,
    MAX_TITLE,
)


class TestProtocolLimitsConstants:
    """PROTO-002 / PROTO-003 constant pinning — mirrors the JVM test."""

    def test_max_frame_length_is_4_mib(self):
        assert MAX_FRAME_LENGTH == 4 * 1024 * 1024

    def test_max_config_sync_json_is_2_mib(self):
        assert MAX_CONFIG_SYNC_JSON == 2 * 1024 * 1024

    def test_config_sync_budget_under_frame_ceiling(self):
        assert MAX_CONFIG_SYNC_JSON < MAX_FRAME_LENGTH

    def test_identifier_fields_are_64(self):
        assert MAX_CHANNEL_ID == 64
        assert MAX_CLIENT_ID == 64
        assert MAX_SENDER_NAME == 64
        assert MAX_TARGET_NAME == 64
        assert MAX_NONCE == 64
        assert MAX_SERVER_VERSION == 64

    def test_error_fields(self):
        assert MAX_ERROR_CODE == 64
        assert MAX_ERROR_MESSAGE == 256
        assert MAX_MESSAGE_PREVIEW == 256
        assert MAX_CHANNEL_PASSWORD == 256
        assert MAX_PASSWORD_HASH == 256

    def test_display_and_auth_fields(self):
        assert MAX_TITLE == 512
        assert MAX_SUBTITLE == 512
        assert MAX_HMAC == 128

    def test_message_content_is_2048(self):
        assert MAX_MESSAGE_CONTENT == 2048

    def test_json_fields_are_8192(self):
        assert MAX_ITEM_JSON == 8192
        assert MAX_ACTION_JSON == 8192

    def test_metadata_fields(self):
        assert MAX_METADATA_KEY == 128
        assert MAX_METADATA_VALUE == 512

    def test_every_per_field_limit_under_frame_ceiling(self):
        all_field_limits = [
            MAX_CONFIG_SYNC_JSON,
            MAX_CHANNEL_ID,
            MAX_CLIENT_ID,
            MAX_SENDER_NAME,
            MAX_TARGET_NAME,
            MAX_ERROR_CODE,
            MAX_ERROR_MESSAGE,
            MAX_TITLE,
            MAX_SUBTITLE,
            MAX_MESSAGE_PREVIEW,
            MAX_MESSAGE_CONTENT,
            MAX_PASSWORD_HASH,
            MAX_HMAC,
            MAX_NONCE,
            MAX_SERVER_VERSION,
            MAX_CHANNEL_PASSWORD,
            MAX_ITEM_JSON,
            MAX_ACTION_JSON,
            MAX_METADATA_KEY,
            MAX_METADATA_VALUE,
        ]
        for limit in all_field_limits:
            assert limit <= MAX_FRAME_LENGTH, (
                f"field limit {limit} must not exceed MAX_FRAME_LENGTH"
            )


# ==================== StringFieldLimitTest mirror ====================
#
# Each case writes a VarInt-prefixed ASCII string of exactly byteLength 'a'
# bytes (one byte per char, so the on-wire length equals the string length),
# then decodes the packet and asserts:
#   - max-1 and max round-trip;
#   - max+1 raises ValueError whose message contains "exceeds maximum" and the
#     limit value (mirrors the Java IllegalArgumentException contract).

def _write_string(buf: PacketBuffer, byte_length: int) -> None:
    data = b"a" * byte_length
    buf.write_varint(len(data))
    buf.write_bytes(data)


class TestConfigSyncConfigJsonBoundary:
    """ConfigSyncPacket.configJson bounded by MAX_CONFIG_SYNC_JSON."""

    def _encode(self, json_len: int) -> PacketBuffer:
        buf = PacketBuffer()
        buf.write_string("a" * json_len)
        buf.write_long(42)
        return buf

    def test_below_max_round_trips(self):
        n = MAX_CONFIG_SYNC_JSON - 1
        buf = self._encode(n)
        packet = ConfigSyncPacket.decode(buf)
        assert len(packet.config_json) == n
        assert packet.timestamp == 42

    def test_at_max_round_trips(self):
        n = MAX_CONFIG_SYNC_JSON
        buf = self._encode(n)
        packet = ConfigSyncPacket.decode(buf)
        assert len(packet.config_json) == n

    def test_above_max_rejected(self):
        n = MAX_CONFIG_SYNC_JSON + 1
        buf = self._encode(n)
        with pytest.raises(ValueError) as exc_info:
            ConfigSyncPacket.decode(buf)
        assert "exceeds maximum" in str(exc_info.value)
        assert str(MAX_CONFIG_SYNC_JSON) in str(exc_info.value)


class TestChannelActionResponseChannelIdBoundary:
    """ChannelActionResponsePacket.channelId bounded by MAX_CHANNEL_ID."""

    def _encode(self, channel_id_len: int) -> PacketBuffer:
        buf = PacketBuffer()
        buf.write_boolean(True)
        buf.write_byte(0)  # ChannelAction.JOIN
        buf.write_string("a" * channel_id_len)
        buf.write_string("")  # errorCode
        buf.write_string("")  # message
        buf.write_varint(0)  # empty extra map
        return buf

    def test_below_max_round_trips(self):
        n = MAX_CHANNEL_ID - 1
        buf = self._encode(n)
        packet = ChannelActionResponsePacket.decode(buf)
        assert len(packet.channel_id) == n

    def test_at_max_round_trips(self):
        n = MAX_CHANNEL_ID
        buf = self._encode(n)
        packet = ChannelActionResponsePacket.decode(buf)
        assert len(packet.channel_id) == n

    def test_above_max_rejected(self):
        n = MAX_CHANNEL_ID + 1
        buf = self._encode(n)
        with pytest.raises(ValueError) as exc_info:
            ChannelActionResponsePacket.decode(buf)
        assert "exceeds maximum" in str(exc_info.value)
        assert str(MAX_CHANNEL_ID) in str(exc_info.value)


class TestTitleTitleBoundary:
    """TitleMessagePacket.title bounded by MAX_TITLE."""

    def _encode(self, title_len: int) -> PacketBuffer:
        buf = PacketBuffer()
        buf.write_string("global")  # channelId
        buf.write_string("a" * title_len)
        buf.write_string("")  # subtitle
        buf.write_int(10)  # fadeIn
        buf.write_int(70)  # stay
        buf.write_int(20)  # fadeOut
        sender = uuid.UUID(int=(1 << 64) | 2)
        buf.write_uuid(sender)
        return buf

    def test_below_max_round_trips(self):
        n = MAX_TITLE - 1
        buf = self._encode(n)
        packet = TitleMessagePacket.decode(buf)
        assert len(packet.title) == n

    def test_at_max_round_trips(self):
        n = MAX_TITLE
        buf = self._encode(n)
        packet = TitleMessagePacket.decode(buf)
        assert len(packet.title) == n

    def test_above_max_rejected(self):
        n = MAX_TITLE + 1
        buf = self._encode(n)
        with pytest.raises(ValueError) as exc_info:
            TitleMessagePacket.decode(buf)
        assert "exceeds maximum" in str(exc_info.value)
        assert str(MAX_TITLE) in str(exc_info.value)


class TestPrivateMessageContentBoundary:
    """PrivateMessagePacket.content bounded by MAX_MESSAGE_CONTENT."""

    def _encode(self, content_len: int) -> PacketBuffer:
        buf = PacketBuffer()
        sender = uuid.UUID(int=(1 << 64) | 2)
        buf.write_uuid(sender)
        buf.write_string("Alice")  # senderName
        buf.write_string("srv-1")  # senderClientId
        buf.write_string("Bob")  # targetName
        target = uuid.UUID(int=0)
        buf.write_uuid(target)
        buf.write_string("a" * content_len)
        buf.write_long(0)
        return buf

    def test_below_max_round_trips(self):
        n = MAX_MESSAGE_CONTENT - 1
        buf = self._encode(n)
        packet = PrivateMessagePacket.decode(buf)
        assert len(packet.content) == n

    def test_at_max_round_trips(self):
        n = MAX_MESSAGE_CONTENT
        buf = self._encode(n)
        packet = PrivateMessagePacket.decode(buf)
        assert len(packet.content) == n

    def test_above_max_rejected(self):
        n = MAX_MESSAGE_CONTENT + 1
        buf = self._encode(n)
        with pytest.raises(ValueError) as exc_info:
            PrivateMessagePacket.decode(buf)
        assert "exceeds maximum" in str(exc_info.value)
        assert str(MAX_MESSAGE_CONTENT) in str(exc_info.value)


class TestItemDisplayItemJsonBoundary:
    """ItemDisplayPacket.itemJson bounded by MAX_ITEM_JSON."""

    def _encode(self, item_json_len: int) -> PacketBuffer:
        buf = PacketBuffer()
        sender = uuid.UUID(int=(1 << 64) | 2)
        buf.write_uuid(sender)
        buf.write_string("Sender")  # senderName
        buf.write_string("global")  # channelId
        buf.write_string("a" * item_json_len)
        buf.write_long(0)
        return buf

    def test_below_max_round_trips(self):
        n = MAX_ITEM_JSON - 1
        buf = self._encode(n)
        packet = ItemDisplayPacket.decode(buf)
        assert len(packet.item_json) == n

    def test_at_max_round_trips(self):
        n = MAX_ITEM_JSON
        buf = self._encode(n)
        packet = ItemDisplayPacket.decode(buf)
        assert len(packet.item_json) == n

    def test_above_max_rejected(self):
        n = MAX_ITEM_JSON + 1
        buf = self._encode(n)
        with pytest.raises(ValueError) as exc_info:
            ItemDisplayPacket.decode(buf)
        assert "exceeds maximum" in str(exc_info.value)
        assert str(MAX_ITEM_JSON) in str(exc_info.value)
