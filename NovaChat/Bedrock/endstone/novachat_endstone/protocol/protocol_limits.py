"""
Protocol-wide size limits for NovaProtocol (Endstone / Python fork).

This module is the Python mirror of the JVM
``com.nova.chat.common.protocol.ProtocolLimits`` single source of truth.
Two audit issues drive it:

* **PROTO-002** — the Java frame codec and the non-JVM receivers
  (LeviLamina / PMMP / Endstone) previously hard-coded different frame
  ceilings (Java 4 MiB, LeviLamina 1 MiB), so 1-4 MiB packets were
  silently dropped by some peers. The frame ceiling now lives here as
  :data:`MAX_FRAME_LENGTH`; every non-JVM implementation MUST mirror this
  exact value so cross-language golden tests can cover the 1 MiB / 4 MiB /
  over-limit boundary.
* **PROTO-003** — several packets read string fields with the unbounded
  :meth:`PacketBuffer.read_string`, letting a single field expand toward
  the 4 MiB frame ceiling and exhaust heap memory. Each field now reads
  with a matching constant from this module, so an oversized field is
  rejected before allocation.

**Non-JVM mirror contract.** When a constant changes in the Java
``ProtocolLimits``, the matching constant here must change in the same
commit and the cross-language golden fixtures must be regenerated.

All limits are in **UTF-8 bytes** (the wire unit for
:meth:`PacketBuffer.write_string`, which encodes a VarInt byte length). A
limit that reads as "64" therefore bounds the field to 64 bytes on the
wire, not 64 Python characters. Every constant is ``<= MAX_FRAME_LENGTH``.
"""

from __future__ import annotations

# ==================== Frame ceiling (PROTO-002) ====================

#: Unified maximum size of a single NovaProtocol frame on the wire, in bytes.
#:
#: Endstone enforces this identically to the Java frame codec and the other
#: non-JVM receivers. Chat/command packets are tiny, but ConfigSync and the
#: display-family payloads can carry a few MiB, and the audit doc (PROTO-002)
#: calls out 1-4 MiB as the exact compatibility band that used to break.
#: Non-JVM mirror: keep this byte-for-byte identical.
MAX_FRAME_LENGTH: int = 4 * 1024 * 1024  # 4 MiB

#: Dedicated size budget for a single ConfigSync JSON payload, in bytes.
#:
#: Well under :data:`MAX_FRAME_LENGTH` so a ConfigSync frame still has
#: headroom for its VarInt length prefix, packet id, request id and timestamp
#: (PROTO-002 line 287 requires a production ConfigSync size budget distinct
#: from the raw frame ceiling). Non-JVM mirror: identical value.
MAX_CONFIG_SYNC_JSON: int = 2 * 1024 * 1024  # 2 MiB

# ==================== Identifier fields (PROTO-003) ====================

#: Max UTF-8 bytes for a channel id (e.g. "global", "pvp-eu-1").
MAX_CHANNEL_ID: int = 64

#: Max UTF-8 bytes for a client/server id (e.g. "survival-01").
MAX_CLIENT_ID: int = 64

#: Max UTF-8 bytes for a sender display name (Minecraft names are 3-16 chars;
#: 64 bytes is generous for UTF-8).
MAX_SENDER_NAME: int = 64

#: Max UTF-8 bytes for a target player name (PrivateMessagePacket targetName).
MAX_TARGET_NAME: int = 64

# ==================== Error / message fields ====================

#: Max UTF-8 bytes for a machine-readable error code (e.g. "NC-403", "NC-404").
MAX_ERROR_CODE: int = 64

#: Max UTF-8 bytes for a human-readable error/response message line.
MAX_ERROR_MESSAGE: int = 256

# ==================== Display / content fields ====================

#: Max UTF-8 bytes for a TitlePacket title text (supports color codes, hex, multi-line).
MAX_TITLE: int = 512

#: Max UTF-8 bytes for a TitlePacket subtitle text.
MAX_SUBTITLE: int = 512

#: Max UTF-8 bytes for a MentionPacket message preview (a short snippet).
MAX_MESSAGE_PREVIEW: int = 256

#: Max UTF-8 bytes for a user-authored message body - used by both
#: ``ChatMessagePacket.content`` and ``PrivateMessagePacket.content``. Both are
#: the body of a player-typed message and share the same 2048-byte budget (the
#: precedent set by ChatMessagePacket before PROTO-003). A single constant
#: keeps the two read paths consistent and gives non-JVM forks one value to
#: mirror.
MAX_MESSAGE_CONTENT: int = 2048

# ==================== Auth / handshake fields ====================

#: Max UTF-8 bytes for a stored credential / password hash (SHA-256 hex = 64 chars;
#: 256 bytes is headroom).
MAX_PASSWORD_HASH: int = 256

#: Max UTF-8 bytes for an HMAC value (HMAC-SHA-256 hex = 64 chars; 128 bytes is headroom).
MAX_HMAC: int = 128

#: Max UTF-8 bytes for a handshake nonce (16 random bytes lowercase-hex = 32 chars;
#: 64 bytes is headroom).
MAX_NONCE: int = 64

#: Max UTF-8 bytes for the reported Minecraft server version string (e.g. "1.20.4").
MAX_SERVER_VERSION: int = 64

# ==================== Channel password ====================

#: Max UTF-8 bytes for a channel join password. Matches a generous user password.
MAX_CHANNEL_PASSWORD: int = 256

# ==================== JSON / action payload fields ====================

#: Max UTF-8 bytes for an ItemDisplayPacket itemJson (NBT/JSON serialized item).
MAX_ITEM_JSON: int = 8192

#: Max UTF-8 bytes for a ChannelActionPacket legacy JSON extra field
#: (single-JSON fallback in read()).
MAX_ACTION_JSON: int = 8192

# ==================== Metadata map fields ====================

#: Max UTF-8 bytes for a metadata/extra map key. Mirrors ChatMessagePacket
#: placeholder key precedent.
MAX_METADATA_KEY: int = 128

#: Max UTF-8 bytes for a metadata/extra map value. Mirrors ChatMessagePacket
#: placeholder value precedent.
MAX_METADATA_VALUE: int = 512
