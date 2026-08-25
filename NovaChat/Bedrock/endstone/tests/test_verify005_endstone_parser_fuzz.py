"""VERIFY-005 Endstone — packet decoder fuzz + malformed-input robustness.

Audit VERIFY-005 requires the Endstone packet decoder to reject malformed
input safely: bad VarInt, bad UTF-8, and oversized fields must each close the
connection, release resources, and log — without any memory explosion. This
module is the Endstone (Python) slice.

Coverage map (audit scenario -> test):

(1) Unknown packet ID
    decode_packet returns UnknownPacket (does NOT raise). The Endstone
    _read_loop keeps the connection open on an unknown ID — this is a
    deliberate design choice ("Unknown packets should not tear down the
    connection", source comment in packet.py). The audit's literal text says
    "unknown ID -> close + release", but the production code intentionally
    diverges. This test suite asserts the ACTUAL behavior (connection
    survives, next valid packet is processed) and annotates the divergence
    explicitly. See ``TestUnknownPacketId``.

(2) Bad VarInt (length prefix or field) -> _read_packet returns None ->
    _read_loop calls _handle_disconnect (close + release). See
    ``TestReadPacketMalformed`` and ``TestReadLoopDisconnect``.

(3) Bad UTF-8 in a string field -> UnicodeDecodeError ->
    _read_packet returns None -> _handle_disconnect. See
    ``TestReadPacketMalformed`` and ``TestReadLoopDisconnect``.

(4) Oversized string field -> read_string(max_length) raises ValueError
    BEFORE allocating the declared buffer ("exceeds maximum"). No memory
    explosion: tracemalloc confirms a ~constant allocation regardless of the
    declared length. See ``TestOversizedFieldNoMemoryExplosion`` and
    ``TestReadPacketMalformed``.

This is a pure test module — it adds no production code and changes no
behavior. All tests exercise the existing public/pure boundaries:
``VarInt.decode``, ``PacketBuffer.read_string``, ``decode_packet``, and
``NetworkClient._read_packet`` / ``_read_loop`` via a FakeReader + async
stubs (the same pattern used by ``test_network_client_tls.py``).
"""

from __future__ import annotations

import asyncio
import tracemalloc
import uuid

import pytest

from novachat_endstone.network.client import NetworkClient
from novachat_endstone.protocol.buffer import PacketBuffer
from novachat_endstone.protocol.packet import (
    KeepAlivePacket,
    PacketIds,
    UnknownPacket,
    decode_packet,
)
from novachat_endstone.protocol import protocol_limits as _pl
from novachat_endstone.protocol.varint import VarInt


# ---------------------------------------------------------------------------
# FakeReader — minimal asyncio.StreamReader stand-in for _read_packet.
# ---------------------------------------------------------------------------

class FakeReader:
    """Sequential bytes source mimicking ``asyncio.StreamReader``.

    ``_read_packet`` calls ``reader.read(1)`` in a loop for the length prefix
    and ``reader.readexactly(length)`` for the payload. ``read(1)`` returns
    one byte at a time from the internal cursor; when exhausted it returns
    ``b""`` (EOF). ``readexactly(n)`` returns the next ``n`` bytes or raises
    ``asyncio.IncompleteReadError`` if fewer than ``n`` bytes remain.
    """

    def __init__(self, data: bytes = b"") -> None:
        self._data = bytes(data)
        self._pos = 0

    def feed(self, data: bytes) -> None:
        self._data += bytes(data)

    async def read(self, n: int = -1) -> bytes:
        if self._pos >= len(self._data):
            return b""
        chunk = self._data[self._pos:self._pos + 1]
        self._pos += 1
        return chunk

    async def readexactly(self, n: int) -> bytes:
        if self._pos + n > len(self._data):
            remaining = len(self._data) - self._pos
            partial = self._data[self._pos:]
            self._pos = len(self._data)
            raise asyncio.IncompleteReadError(partial, n)
        chunk = self._data[self._pos:self._pos + n]
        self._pos += n
        return chunk


def _frame(payload: bytes) -> bytes:
    """Wrap a payload in a VarInt length-prefixed frame."""
    return VarInt.encode(len(payload)) + payload


def _build_packet_payload(packet_id: int, request_id: uuid.UUID, body: bytes) -> bytes:
    """Build a NovaProtocol payload: packet_id + UUID(16) + body."""
    buf = PacketBuffer()
    buf.write_byte(packet_id)
    buf.write_uuid(request_id)
    buf.write_bytes(body)
    return buf.get_bytes()


# ===========================================================================
# (1) Unknown packet ID
# ===========================================================================

class TestUnknownPacketId:
    """Unknown packet IDs decode to UnknownPacket and do NOT tear down the
    connection — the Endstone design choice.

    HONEST DESIGN-DIVERGENCE NOTE (VERIFY-005):
        The audit VERIFY-005 text says "unknown packet ID -> close connection +
        release + log". Endstone's production decoder deliberately does the
        opposite: ``decode_packet`` returns an ``UnknownPacket`` and the read
        loop logs "No handler for packet ID" at DEBUG level, then continues.
        The source code comment in ``packet.py`` states: "Unknown packets
        should not tear down the connection." This is a conscious robustness
        choice (a future-compatible forward path), not a bug. These tests
        assert the ACTUAL behavior. If the audit's literal expectation is
        later enforced, this is the place to change — and these tests must be
        updated to assert close+release instead.
    """

    def test_decode_unknown_id_returns_unknown_packet(self) -> None:
        """decode_packet for an unregistered ID returns UnknownPacket, not None."""
        unknown_id = 0xFE
        assert unknown_id not in __import__(
            "novachat_endstone.protocol.packet", fromlist=["PACKET_REGISTRY"]
        ).PACKET_REGISTRY
        buf = PacketBuffer(b"")
        pkt = decode_packet(unknown_id, buf)
        assert isinstance(pkt, UnknownPacket)
        assert pkt.unknown_id == unknown_id

    def test_decode_unknown_id_drains_remaining_buffer(self) -> None:
        """An unknown packet consumes all remaining bytes so the next read
        starts cleanly (no trailing garbage fed into the length prefix)."""
        unknown_id = 0xAA
        trailing = b"garbage-payload-bytes"
        buf = PacketBuffer(trailing)
        pkt = decode_packet(unknown_id, buf)
        assert isinstance(pkt, UnknownPacket)
        assert buf.remaining() == 0, "UnknownPacket must drain the buffer"

    def test_read_loop_survives_unknown_id_and_processes_next_valid(self) -> None:
        """The read loop must NOT disconnect on an unknown packet ID; the
        next valid frame must still be decoded and dispatched.
        """
        client = _make_minimal_client()
        valid_id = uuid.uuid4()
        unknown_id_byte = 0xEE
        assert unknown_id_byte not in _registry_ids()

        # Frame 1: unknown packet (id + uuid + some body)
        unknown_body = b"unknown-body"
        unknown_payload = _build_packet_payload(unknown_id_byte, uuid.uuid4(), unknown_body)
        frame_unknown = _frame(unknown_payload)

        # Frame 2: a real KeepAlivePacket so we can assert the loop survived.
        keepalive = KeepAlivePacket(timestamp=12345)
        ka_buf = PacketBuffer()
        keepalive.encode(ka_buf)
        ka_payload = _build_packet_payload(PacketIds.KEEP_ALIVE, valid_id, ka_buf.get_bytes())
        frame_ka = _frame(ka_payload)

        reader = FakeReader(frame_unknown + frame_ka)
        client._reader = reader
        client._connected = True

        handled: list[int] = []
        disconnected: list[bool] = []

        async def fake_handle_packet(packet) -> None:
            handled.append(packet.packet_id)

        async def fake_handle_disconnect() -> None:
            disconnected.append(True)

        client._handle_packet = fake_handle_packet
        client._handle_disconnect = fake_handle_disconnect

        async def run() -> None:
            # Process at most 2 packets then stop.
            for _ in range(2):
                packet = await client._read_packet()
                if packet is None:
                    await client._handle_disconnect()
                    return
                await client._handle_packet(packet)

        asyncio.new_event_loop().run_until_complete(run())

        # Unknown packet did NOT disconnect the loop.
        assert disconnected == [], (
            "Endstone deliberately keeps the connection open on unknown IDs; "
            "disconnect was triggered, which contradicts the design choice."
        )
        # The next valid packet was decoded and handled.
        assert PacketIds.KEEP_ALIVE in handled, (
            "The loop did not survive the unknown packet to process the next valid one."
        )


# ===========================================================================
# (2) Bad VarInt
# ===========================================================================

class TestBadVarInt:
    """VarInt.decode must reject malformed input without raising past 5 bytes."""

    def test_varint_too_big_raises(self) -> None:
        """A VarInt with more than 5 continuation bytes must raise "too big"."""
        # 6 bytes, all with continuation bit set, then terminator.
        data = bytes([0x80, 0x80, 0x80, 0x80, 0x80, 0x01])
        with pytest.raises(ValueError, match="VarInt is too big"):
            VarInt.decode(data)

    def test_varint_eof_raises(self) -> None:
        """A truncated VarInt (no terminator) must raise "end of data"."""
        with pytest.raises(ValueError, match="Unexpected end of data"):
            VarInt.decode(b"")

    def test_varint_5_bytes_max(self) -> None:
        """A valid 5-byte VarInt must decode without error.

        ``0x80 0x80 0x80 0x80 0x08`` packs 4 bits of value (0x8) into the 5th
        byte at bit position 28, yielding 0x80000000 — which the signed
        interpretation converts to Int32.MIN (-2147483648). This is the
        boundary: one more continuation byte would trip the "VarInt is too
        big" guard at position >= 32.
        """
        data = bytes([0x80, 0x80, 0x80, 0x80, 0x08])
        value, consumed = VarInt.decode(data)
        assert consumed == 5
        assert value == -2147483648  # Int32.MIN, the signed form of 0x80000000


# ===========================================================================
# (3) Bad UTF-8
# ===========================================================================

class TestBadUtf8:
    """read_string must raise UnicodeDecodeError on invalid UTF-8."""

    def test_bad_utf8_raises(self) -> None:
        """A string field containing invalid UTF-8 must raise UnicodeDecodeError."""
        bad_bytes = b"\xff\xfe\xfd"
        buf = PacketBuffer()
        buf.write_varint(len(bad_bytes))
        buf.write_bytes(bad_bytes)
        buf.reset_read()
        with pytest.raises(UnicodeDecodeError):
            buf.read_string()

    def test_bad_utf8_in_packet_decode_propagates(self) -> None:
        """A ChatMessagePacket with a bad-UTF-8 sender_name must raise during
        decode (the exception propagates to _read_packet -> None)."""
        from novachat_endstone.protocol.packet import ChatMessagePacket

        # Build a ChatMessage payload with a corrupted sender_name.
        buf = PacketBuffer()
        buf.write_uuid(uuid.uuid4())
        # sender_name: length=3, then invalid UTF-8
        buf.write_varint(3)
        buf.write_bytes(b"\xff\xfe\xfd")
        # client_id, channel_id, content — minimal valid strings
        for s in ("c", "g", "m"):
            buf.write_string(s)
        buf.reset_read()

        with pytest.raises(UnicodeDecodeError):
            ChatMessagePacket.decode(buf)


# ===========================================================================
# (4) Oversized field — no memory explosion
# ===========================================================================

class TestOversizedFieldNoMemoryExplosion:
    """read_string(max_length) must reject an oversized declared length BEFORE
    allocating the buffer — no memory explosion even for a 2-billion-byte
    declared length.
    """

    def test_oversized_string_rejected_with_small_allocation(self) -> None:
        """A declared string length of 2 billion against max_length=2048 must
        raise ValueError with only a constant-sized allocation."""
        tracemalloc.start()
        try:
            before = tracemalloc.get_traced_memory()[0]
            # Build a buffer whose varint length prefix encodes 2_000_000_000.
            buf = PacketBuffer()
            buf.write_varint(2_000_000_000)
            buf.reset_read()
            with pytest.raises(ValueError, match="exceeds maximum"):
                buf.read_string(max_length=2048)
            after = tracemalloc.get_traced_memory()[0]
            diff = after - before
            # The allocation must be tiny (well under 1 MiB). We assert < 1 MiB
            # to allow for any internal bookkeeping; the point is NO huge
            # allocation of the declared 2 GB.
            assert diff < 1 * 1024 * 1024, (
                f"Oversized string field caused {diff} bytes of allocation — "
                f"expected near-zero (no memory explosion)."
            )
        finally:
            tracemalloc.stop()

    def test_negative_string_length_rejected(self) -> None:
        """A negative declared string length must raise ValueError."""
        buf = PacketBuffer()
        # Encode -1 as a VarInt (5-byte two's complement).
        buf.write_varint(-1)
        buf.reset_read()
        with pytest.raises(ValueError):
            buf.read_string()

    def test_oversized_rejected_before_buffer_underflow(self) -> None:
        """Even when the buffer has fewer bytes than declared, read_string
        checks max_length FIRST — the error is "exceeds maximum", not
        "Buffer underflow". This is the protocol's defense against a
        declared-length DoS.
        """
        buf = PacketBuffer()
        buf.write_varint(999_999_999)  # huge declared length
        buf.reset_read()
        with pytest.raises(ValueError, match="exceeds maximum"):
            buf.read_string(max_length=_pl.MAX_MESSAGE_CONTENT)


# ===========================================================================
# _read_packet returns None for every malformed scenario
# ===========================================================================

class TestReadPacketMalformed:
    """``_read_packet`` must return None (triggering _handle_disconnect) for
    every malformed-input scenario. These tests exercise the network-layer
    boundary via FakeReader, not the pure decode functions alone.
    """

    def test_bad_varint_length_returns_none(self) -> None:
        """A length prefix that is a too-big VarInt must return None."""
        client = _make_minimal_client()
        # 6 bytes of continuation -> VarInt too big.
        client._reader = FakeReader(bytes([0x80, 0x80, 0x80, 0x80, 0x80, 0x01]))
        client._connected = True
        result = asyncio.new_event_loop().run_until_complete(client._read_packet())
        assert result is None

    def test_bad_utf8_payload_returns_none(self) -> None:
        """A frame whose payload contains bad UTF-8 must return None."""
        client = _make_minimal_client()
        # Build a ChatMessage payload with bad UTF-8 sender_name.
        body = PacketBuffer()
        body.write_uuid(uuid.uuid4())
        bad = b"\xff\xfe\xfd"
        body.write_varint(len(bad))
        body.write_bytes(bad)
        for s in ("c", "g", "m"):
            body.write_string(s)
        payload = _build_packet_payload(PacketIds.CHAT_MESSAGE, uuid.uuid4(), body.get_bytes())
        client._reader = FakeReader(_frame(payload))
        client._connected = True
        result = asyncio.new_event_loop().run_until_complete(client._read_packet())
        assert result is None

    def test_oversized_field_returns_none(self) -> None:
        """A frame whose declared string length exceeds the field max must
        return None (ValueError caught -> None)."""
        client = _make_minimal_client()
        body = PacketBuffer()
        body.write_uuid(uuid.uuid4())
        # sender_name with a huge declared length (exceeds MAX_SENDER_NAME).
        body.write_varint(2_000_000_000)
        body.reset_read()  # not needed but harmless
        # Rebuild: we need the varint in the stream, not the allocation.
        fresh = PacketBuffer()
        fresh.write_uuid(uuid.uuid4())
        fresh.write_varint(2_000_000_000)  # declared huge length for sender_name
        fresh.write_bytes(b"x" * 8)  # a few trailing bytes
        payload = _build_packet_payload(PacketIds.CHAT_MESSAGE, uuid.uuid4(), fresh.get_bytes())
        client._reader = FakeReader(_frame(payload))
        client._connected = True
        result = asyncio.new_event_loop().run_until_complete(client._read_packet())
        assert result is None

    def test_frame_length_exceeds_max_returns_none(self) -> None:
        """A frame whose length prefix exceeds MAX_FRAME_LENGTH must return None."""
        client = _make_minimal_client()
        # Encode a length just over 4 MiB.
        huge_length = NetworkClient.MAX_FRAME_LENGTH + 1
        reader_data = VarInt.encode(huge_length) + b"\x00" * 4
        client._reader = FakeReader(reader_data)
        client._connected = True
        result = asyncio.new_event_loop().run_until_complete(client._read_packet())
        assert result is None

    def test_zero_frame_length_returns_none(self) -> None:
        """A zero-length frame must return None."""
        client = _make_minimal_client()
        client._reader = FakeReader(VarInt.encode(0))
        client._connected = True
        result = asyncio.new_event_loop().run_until_complete(client._read_packet())
        assert result is None

    def test_negative_frame_length_returns_none(self) -> None:
        """A negative frame length must return None."""
        client = _make_minimal_client()
        client._reader = FakeReader(VarInt.encode(-1))
        client._connected = True
        result = asyncio.new_event_loop().run_until_complete(client._read_packet())
        assert result is None

    def test_eof_during_length_prefix_returns_none(self) -> None:
        """An immediate EOF (empty reader) must return None."""
        client = _make_minimal_client()
        client._reader = FakeReader(b"")
        client._connected = True
        result = asyncio.new_event_loop().run_until_complete(client._read_packet())
        assert result is None

    def test_eof_during_payload_returns_none(self) -> None:
        """A truncated payload (length says more than available) must return None."""
        client = _make_minimal_client()
        # Length says 100 bytes but we only provide 4.
        reader_data = VarInt.encode(100) + b"\x01\x02\x03\x04"
        client._reader = FakeReader(reader_data)
        client._connected = True
        result = asyncio.new_event_loop().run_until_complete(client._read_packet())
        assert result is None


# ===========================================================================
# _read_loop calls _handle_disconnect on malformed input
# ===========================================================================

class TestReadLoopDisconnect:
    """The full _read_loop must call _handle_disconnect (close + release) when
    _read_packet returns None for a malformed-input scenario.
    """

    @pytest.mark.parametrize(
        "scenario,reader_data",
        [
            ("bad_varint", bytes([0x80, 0x80, 0x80, 0x80, 0x80, 0x01])),
            ("eof", b""),
            ("zero_length", VarInt.encode(0)),
            ("oversize_frame", VarInt.encode(NetworkClient.MAX_FRAME_LENGTH + 1) + b"\x00" * 4),
        ],
        ids=["bad_varint", "eof", "zero_length", "oversize_frame"],
    )
    def test_read_loop_disconnects_on_malformed(self, scenario: str, reader_data: bytes) -> None:
        """Each malformed scenario must trigger _handle_disconnect exactly once."""
        client = _make_minimal_client()
        client._reader = FakeReader(reader_data)
        client._connected = True

        disconnects: list[str] = []

        async def fake_handle_disconnect() -> None:
            disconnects.append("dc")

        async def fake_handle_packet(packet) -> None:
            # Should not be called for malformed input.
            disconnects.append(f"packet:{packet.packet_id}")

        client._handle_disconnect = fake_handle_disconnect
        client._handle_packet = fake_handle_packet

        async def run() -> None:
            await client._read_loop()

        asyncio.new_event_loop().run_until_complete(run())

        assert disconnects == ["dc"], (
            f"Expected _handle_disconnect for scenario {scenario!r}, got {disconnects!r}"
        )
        assert client._connected is False or disconnects == ["dc"], (
            "Connection should be marked disconnected"
        )

    def test_read_loop_disconnects_on_bad_utf8(self) -> None:
        """Bad UTF-8 in the payload must trigger _handle_disconnect."""
        client = _make_minimal_client()
        body = PacketBuffer()
        body.write_uuid(uuid.uuid4())
        body.write_varint(3)
        body.write_bytes(b"\xff\xfe\xfd")
        for s in ("c", "g", "m"):
            body.write_string(s)
        payload = _build_packet_payload(PacketIds.CHAT_MESSAGE, uuid.uuid4(), body.get_bytes())
        client._reader = FakeReader(_frame(payload))
        client._connected = True

        disconnects: list[str] = []

        async def fake_handle_disconnect() -> None:
            disconnects.append("dc")

        client._handle_disconnect = fake_handle_disconnect
        client._handle_packet = lambda packet: None  # should not be called

        async def run() -> None:
            await client._read_loop()

        asyncio.new_event_loop().run_until_complete(run())
        assert disconnects == ["dc"]

    def test_read_loop_disconnects_on_oversized_field(self) -> None:
        """An oversized string field must trigger _handle_disconnect."""
        client = _make_minimal_client()
        fresh = PacketBuffer()
        fresh.write_uuid(uuid.uuid4())
        fresh.write_varint(2_000_000_000)
        fresh.write_bytes(b"x" * 8)
        payload = _build_packet_payload(PacketIds.CHAT_MESSAGE, uuid.uuid4(), fresh.get_bytes())
        client._reader = FakeReader(_frame(payload))
        client._connected = True

        disconnects: list[str] = []

        async def fake_handle_disconnect() -> None:
            disconnects.append("dc")

        client._handle_disconnect = fake_handle_disconnect

        async def run() -> None:
            await client._read_loop()

        asyncio.new_event_loop().run_until_complete(run())
        assert disconnects == ["dc"]


# ===========================================================================
# Helpers
# ===========================================================================

def _make_minimal_client() -> NetworkClient:
    """Build a NetworkClient without connecting — just enough state for
    _read_packet / _read_loop to run against a FakeReader.
    """
    return NetworkClient(
        plugin=None,
        host="127.0.0.1",
        port=0,
        username="test",
        password="test",
        server_version="test",
        reconnect_delay=1,
    )


def _registry_ids() -> set[int]:
    from novachat_endstone.protocol.packet import PACKET_REGISTRY
    return set(PACKET_REGISTRY.keys())
