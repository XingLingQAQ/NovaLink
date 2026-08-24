<?php

declare(strict_types=1);

namespace NovaChat\Protocol;

/**
 * Protocol-wide size limits for NovaProtocol (PMMP / PHP fork).
 *
 * This class is the PHP mirror of the JVM
 * `com.nova.chat.common.protocol.ProtocolLimits` single source of truth.
 * Two audit issues drive it:
 *
 * - PROTO-002 — the Java frame codec and the non-JVM receivers
 *   (LeviLamina / PMMP / Endstone) previously hard-coded different frame
 *   ceilings (Java 4 MiB, LeviLamina 1 MiB), so 1-4 MiB packets were
 *   silently dropped by some peers. The frame ceiling now lives here as
 *   MAX_FRAME_LENGTH; every non-JVM implementation MUST mirror this exact
 *   value so cross-language golden tests can cover the 1 MiB / 4 MiB /
 *   over-limit boundary.
 * - PROTO-003 — several packets read string fields with the unbounded
 *   `PacketBuffer::readString()`, letting a single field expand toward
 *   the 4 MiB frame ceiling and exhaust memory. Each field now reads
 *   with a matching constant from this class, so an oversized field is
 *   rejected before allocation.
 *
 * Non-JVM mirror contract. When a constant changes in the Java
 * `ProtocolLimits`, the matching constant here must change in the same
 * commit and the cross-language golden fixtures must be regenerated.
 *
 * All limits are in UTF-8 bytes (the wire unit for
 * `PacketBuffer::writeString`, which encodes a VarInt byte length). A
 * limit that reads as "64" therefore bounds the field to 64 bytes on
 * the wire, not 64 PHP characters. Every constant is <= MAX_FRAME_LENGTH.
 */
final class ProtocolLimits {

    private function __construct() {
        // Constants only — no instances.
    }

    // ==================== Frame ceiling (PROTO-002) ====================

    /**
     * Unified maximum size of a single NovaProtocol frame on the wire, in bytes.
     *
     * Non-JVM mirror: keep this byte-for-byte identical to the JVM
     * `ProtocolLimits::MAX_FRAME_LENGTH`. Frames whose declared length
     * exceeds this are rejected by the receiver's equivalent of the Java
     * `CorruptedFrameException`. Set to 4 MiB: chat/command packets are
     * tiny, but ConfigSync and the display-family payloads can carry a
     * few MiB, and the audit doc (PROTO-002) calls out 1-4 MiB as the
     * exact compatibility band that used to break.
     */
    public const MAX_FRAME_LENGTH = 4 * 1024 * 1024; // 4 MiB

    /**
     * Dedicated size budget for a single ConfigSync JSON payload, in bytes.
     *
     * Well under MAX_FRAME_LENGTH so a ConfigSync frame still has headroom
     * for its VarInt length prefix, packet id, request id and timestamp
     * (PROTO-002 requires a production ConfigSync size budget distinct
     * from the raw frame ceiling). Non-JVM mirror: identical value.
     */
    public const MAX_CONFIG_SYNC_JSON = 2 * 1024 * 1024; // 2 MiB

    // ==================== Identifier fields (PROTO-003) ====================

    /** Max UTF-8 bytes for a channel id (e.g. "global", "pvp-eu-1"). */
    public const MAX_CHANNEL_ID = 64;

    /** Max UTF-8 bytes for a client/server id (e.g. "survival-01"). */
    public const MAX_CLIENT_ID = 64;

    /** Max UTF-8 bytes for a sender display name (Minecraft names are 3-16 chars; 64 bytes is generous for UTF-8). */
    public const MAX_SENDER_NAME = 64;

    /** Max UTF-8 bytes for a target player name (PrivateMessagePacket targetName). */
    public const MAX_TARGET_NAME = 64;

    // ==================== Error / message fields ====================

    /** Max UTF-8 bytes for a machine-readable error code (e.g. "NC-403", "NC-404"). */
    public const MAX_ERROR_CODE = 64;

    /** Max UTF-8 bytes for a human-readable error/response message line. */
    public const MAX_ERROR_MESSAGE = 256;

    // ==================== Display / content fields ====================

    /** Max UTF-8 bytes for a TitlePacket title text (supports color codes, hex, multi-line). */
    public const MAX_TITLE = 512;

    /** Max UTF-8 bytes for a TitlePacket subtitle text. */
    public const MAX_SUBTITLE = 512;

    /** Max UTF-8 bytes for a MentionPacket message preview (a short snippet). */
    public const MAX_MESSAGE_PREVIEW = 256;

    /**
     * Max UTF-8 bytes for a user-authored message body — used by both
     * `ChatMessagePacket->content` and `PrivateMessagePacket->content`.
     * Both are the body of a player-typed message and share the same
     * 2048-byte budget (the precedent set by ChatMessagePacket before
     * PROTO-003). A single constant keeps the two read paths consistent
     * and gives non-JVM forks one value to mirror.
     */
    public const MAX_MESSAGE_CONTENT = 2048;

    // ==================== Auth / handshake fields ====================

    /** Max UTF-8 bytes for a stored credential / password hash (SHA-256 hex = 64 chars; 256 bytes is headroom). */
    public const MAX_PASSWORD_HASH = 256;

    /** Max UTF-8 bytes for an HMAC value (HMAC-SHA-256 hex = 64 chars; 128 bytes is headroom). */
    public const MAX_HMAC = 128;

    /** Max UTF-8 bytes for a handshake nonce (16 random bytes lowercase-hex = 32 chars; 64 bytes is headroom). */
    public const MAX_NONCE = 64;

    /** Max UTF-8 bytes for the reported Minecraft server version string (e.g. "1.20.4"). */
    public const MAX_SERVER_VERSION = 64;

    // ==================== Channel password ====================

    /** Max UTF-8 bytes for a channel join password. Matches a generous user password. */
    public const MAX_CHANNEL_PASSWORD = 256;

    // ==================== JSON / action payload fields ====================

    /** Max UTF-8 bytes for an ItemDisplayPacket itemJson (NBT/JSON serialized item). */
    public const MAX_ITEM_JSON = 8192;

    /** Max UTF-8 bytes for a ChannelActionPacket legacy JSON extra field (single-JSON fallback in read()). */
    public const MAX_ACTION_JSON = 8192;

    // ==================== Metadata map fields ====================

    /** Max UTF-8 bytes for a metadata/extra map key. Mirrors ChatMessagePacket placeholder key precedent. */
    public const MAX_METADATA_KEY = 128;

    /** Max UTF-8 bytes for a metadata/extra map value. Mirrors ChatMessagePacket placeholder value precedent. */
    public const MAX_METADATA_VALUE = 512;

    /**
     * All per-field limit constants (everything except the two frame-level
     * constants MAX_FRAME_LENGTH / MAX_CONFIG_SYNC_JSON). Used by the
     * invariant test that asserts each per-field limit is <= MAX_FRAME_LENGTH.
     *
     * @return list<int>
     */
    public static function allFieldLimits(): array {
        return [
            self::MAX_CONFIG_SYNC_JSON,
            self::MAX_CHANNEL_ID,
            self::MAX_CLIENT_ID,
            self::MAX_SENDER_NAME,
            self::MAX_TARGET_NAME,
            self::MAX_ERROR_CODE,
            self::MAX_ERROR_MESSAGE,
            self::MAX_TITLE,
            self::MAX_SUBTITLE,
            self::MAX_MESSAGE_PREVIEW,
            self::MAX_MESSAGE_CONTENT,
            self::MAX_PASSWORD_HASH,
            self::MAX_HMAC,
            self::MAX_NONCE,
            self::MAX_SERVER_VERSION,
            self::MAX_CHANNEL_PASSWORD,
            self::MAX_ITEM_JSON,
            self::MAX_ACTION_JSON,
            self::MAX_METADATA_KEY,
            self::MAX_METADATA_VALUE,
        ];
    }
}
