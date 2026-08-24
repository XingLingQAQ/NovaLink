#pragma once

#include <cstddef>
#include <cstdint>

namespace novachat::protocol {

/**
 * Protocol-wide size limits for NovaProtocol (LeviLamina / C++ fork).
 *
 * This header is the C++ mirror of the JVM
 * `com.nova.chat.common.protocol.ProtocolLimits` single source of truth.
 * Two audit issues drive it:
 *
 * - PROTO-002 — the Java frame codec and the non-JVM receivers
 *   (LeviLamina / PMMP / Endstone) previously hard-coded different frame
 *   ceilings (Java 4 MiB, LeviLamina 1 MiB), so 1-4 MiB packets were
 *   silently dropped by some peers. The frame ceiling now lives here as
 *   MAX_FRAME_LENGTH; every non-JVM implementation MUST mirror this exact
 *   value so cross-language golden tests can cover the 1 MiB / 4 MiB /
 *   over-limit boundary. NetworkClient::processReceivedData() references
 *   this constant (was hard-coded 1048576 = 1 MiB).
 * - PROTO-003 — several packets read string fields with the unbounded
 *   `PacketBuffer::readString()`, letting a single field expand toward
 *   the 4 MiB frame ceiling and exhaust heap memory. Each field now reads
 *   with a matching constant from this header, so an oversized field is
 *   rejected before allocation.
 *
 * Non-JVM mirror contract. When a constant changes in the Java
 * `ProtocolLimits`, the matching constant here must change in the same
 * commit and the cross-language golden fixtures must be regenerated.
 *
 * All limits are in UTF-8 bytes (the wire unit for
 * `PacketBuffer::writeString`, which encodes a VarInt byte length). A
 * limit that reads as "64" therefore bounds the field to 64 bytes on the
 * wire, not 64 `char`s. Every constant is <= MAX_FRAME_LENGTH.
 */
struct ProtocolLimits {
    ProtocolLimits() = delete;

    // ==================== Frame ceiling (PROTO-002) ====================

    /**
     * Unified maximum size of a single NovaProtocol frame on the wire, in bytes.
     *
     * This is the value NetworkClient::processReceivedData() enforces on the
     * declared packet length (PROTO-002: was hard-coded 1 MiB, now 4 MiB to
     * match the Java Varint21FrameDecoder ceiling). Frames whose declared
     * length exceeds this are dropped and the connection is reset.
     */
    static constexpr size_t MAX_FRAME_LENGTH = 4 * 1024 * 1024; // 4 MiB

    /**
     * Dedicated size budget for a single ConfigSync JSON payload, in bytes.
     *
     * Well under MAX_FRAME_LENGTH so a ConfigSync frame still has headroom
     * for its VarInt length prefix, packet id, request id and timestamp
     * (PROTO-002 requires a production ConfigSync size budget distinct
     * from the raw frame ceiling). Non-JVM mirror: identical value.
     */
    static constexpr size_t MAX_CONFIG_SYNC_JSON = 2 * 1024 * 1024; // 2 MiB

    // ==================== Identifier fields (PROTO-003) ====================

    /** Max UTF-8 bytes for a channel id (e.g. "global", "pvp-eu-1"). */
    static constexpr size_t MAX_CHANNEL_ID = 64;

    /** Max UTF-8 bytes for a client/server id (e.g. "survival-01"). */
    static constexpr size_t MAX_CLIENT_ID = 64;

    /** Max UTF-8 bytes for a sender display name (Minecraft names are 3-16 chars; 64 bytes is generous for UTF-8). */
    static constexpr size_t MAX_SENDER_NAME = 64;

    /** Max UTF-8 bytes for a target player name (PrivateMessagePacket targetName). */
    static constexpr size_t MAX_TARGET_NAME = 64;

    // ==================== Error / message fields ====================

    /** Max UTF-8 bytes for a machine-readable error code (e.g. "NC-403", "NC-404"). */
    static constexpr size_t MAX_ERROR_CODE = 64;

    /** Max UTF-8 bytes for a human-readable error/response message line. */
    static constexpr size_t MAX_ERROR_MESSAGE = 256;

    // ==================== Display / content fields ====================

    /** Max UTF-8 bytes for a TitlePacket title text (supports color codes, hex, multi-line). */
    static constexpr size_t MAX_TITLE = 512;

    /** Max UTF-8 bytes for a TitlePacket subtitle text. */
    static constexpr size_t MAX_SUBTITLE = 512;

    /** Max UTF-8 bytes for a MentionPacket message preview (a short snippet). */
    static constexpr size_t MAX_MESSAGE_PREVIEW = 256;

    /**
     * Max UTF-8 bytes for a user-authored message body — used by both
     * `ChatMessagePacket::mContent` and `PrivateMessagePacket::mContent`.
     * Both are the body of a player-typed message and share the same
     * 2048-byte budget (the precedent set by ChatMessagePacket before
     * PROTO-003). A single constant keeps the two read paths consistent
     * and gives non-JVM forks one value to mirror.
     */
    static constexpr size_t MAX_MESSAGE_CONTENT = 2048;

    // ==================== Auth / handshake fields ====================

    /** Max UTF-8 bytes for a stored credential / password hash (SHA-256 hex = 64 chars; 256 bytes is headroom). */
    static constexpr size_t MAX_PASSWORD_HASH = 256;

    /** Max UTF-8 bytes for an HMAC value (HMAC-SHA-256 hex = 64 chars; 128 bytes is headroom). */
    static constexpr size_t MAX_HMAC = 128;

    /** Max UTF-8 bytes for a handshake nonce (16 random bytes lowercase-hex = 32 chars; 64 bytes is headroom). */
    static constexpr size_t MAX_NONCE = 64;

    /** Max UTF-8 bytes for the reported Minecraft server version string (e.g. "1.20.4"). */
    static constexpr size_t MAX_SERVER_VERSION = 64;

    // ==================== Channel password ====================

    /** Max UTF-8 bytes for a channel join password. Matches a generous user password. */
    static constexpr size_t MAX_CHANNEL_PASSWORD = 256;

    // ==================== JSON / action payload fields ====================

    /** Max UTF-8 bytes for an ItemDisplayPacket itemJson (NBT/JSON serialized item). */
    static constexpr size_t MAX_ITEM_JSON = 8192;

    /** Max UTF-8 bytes for a ChannelActionPacket legacy JSON extra field (single-JSON fallback in read()). */
    static constexpr size_t MAX_ACTION_JSON = 8192;

    // ==================== Metadata map fields ====================

    /** Max UTF-8 bytes for a metadata/extra map key. Mirrors ChatMessagePacket placeholder key precedent. */
    static constexpr size_t MAX_METADATA_KEY = 128;

    /** Max UTF-8 bytes for a metadata/extra map value. Mirrors ChatMessagePacket placeholder value precedent. */
    static constexpr size_t MAX_METADATA_VALUE = 512;
};

} // namespace novachat::protocol
