package com.nova.chat.common.protocol;

/**
 * Protocol-wide size limits for NovaProtocol.
 *
 * <p>This is the single source of truth for the frame ceiling and per-field
 * string maximum lengths. Two audit issues drive this class:
 * <ul>
 *   <li><b>PROTO-002</b> — the Java frame decoder/prepender and the non-JVM
 *       receivers (LeviLamina/PMMP/Endstone) previously hard-coded different
 *       frame ceilings (Java 4 MiB, LeviLamina 1 MiB), so 1&ndash;4 MiB
 *       packets were silently dropped by some peers. The frame ceiling now
 *       lives here as {@link #MAX_FRAME_LENGTH}; non-JVM implementations
 *       MUST mirror this exact value so cross-language golden tests can cover
 *       the 1 MiB / 4 MiB / over-limit boundary in a separate slice.</li>
 *   <li><b>PROTO-003</b> — several packets read string fields with the
 *       unbounded {@link PacketBuffer#readString(ByteBuf)} overload, letting a
 *       single field expand toward the 4 MiB frame ceiling and exhaust heap
 *       memory. Each field now reads with a matching constant from this
 *       class, so an oversized field is rejected before allocation.</li>
 * </ul>
 *
 * <p><b>Non-JVM mirror contract.</b> The PHP, Python and C++ protocol forks
 * must reference these same numeric values. When a constant changes here,
 * the matching constant in the non-JVM forks must change in the same
 * commit and the cross-language golden fixtures must be regenerated.
 *
 * <p>All limits are in <b>UTF-8 bytes</b> (the wire unit for
 * {@code PacketBuffer.writeString}, which encodes a VarInt byte length). A
 * limit that reads as "64" therefore bounds the field to 64 bytes on the
 * wire, not 64 Java {@code char}s. Every constant is {@code <= MAX_FRAME_LENGTH}.
 */
public final class ProtocolLimits {

    private ProtocolLimits() {
        // Utility class — constants only.
    }

    // ==================== Frame ceiling (PROTO-002) ====================

    /**
     * Unified maximum size of a single NovaProtocol frame on the wire, in bytes.
     *
     * <p>This is the value both {@link codec.Varint21FrameDecoder} and
     * {@link codec.Varint21LengthFieldPrepender} enforce, and the value the
     * non-JVM receivers MUST enforce identically. Frames whose declared
     * length exceeds this are rejected with
     * {@code io.netty.handler.codec.CorruptedFrameException} (Java) / the
     * peer-equivalent error (non-JVM).
     *
     * <p>Set to 4 MiB: chat/command packets are tiny, but ConfigSync and the
     * future display-family payloads can carry a few MiB, and the audit doc
     * (PROTO-002) calls out 1&ndash;4 MiB as the exact compatibility band
     * that used to break. Non-JVM mirror: keep this byte-for-byte identical.
     */
    public static final int MAX_FRAME_LENGTH = 4 * 1024 * 1024; // 4 MiB

    /**
     * Dedicated size budget for a single ConfigSync JSON payload, in bytes.
     *
     * <p>Well under {@link #MAX_FRAME_LENGTH} so a ConfigSync frame still has
     * headroom for its VarInt length prefix, packet id, request id and
     * timestamp. The audit doc (PROTO-002 line 287) requires "生产 ConfigSync
     * 有独立大小预算" — a production ConfigSync size budget — distinct from
     * the raw frame ceiling, so a misbehaving backend cannot push a 4 MiB
     * JSON blob that just barely fits the frame and leaves no room for the
     * envelope. Non-JVM mirror: identical value.
     */
    public static final int MAX_CONFIG_SYNC_JSON = 2 * 1024 * 1024; // 2 MiB

    // ==================== Identifier fields (PROTO-003) ====================

    /** Max UTF-8 bytes for a channel id (e.g. "global", "pvp-eu-1"). Mirrors ChatMessagePacket precedent. */
    public static final int MAX_CHANNEL_ID = 64;

    /** Max UTF-8 bytes for a client/server id (game-server identifier, e.g. "survival-01"). */
    public static final int MAX_CLIENT_ID = 64;

    /** Max UTF-8 bytes for a sender display name (Minecraft names are 3&ndash;16 chars; 64 bytes is generous for UTF-8). */
    public static final int MAX_SENDER_NAME = 64;

    /** Max UTF-8 bytes for a target player name (PrivateMessagePacket targetName). */
    public static final int MAX_TARGET_NAME = 64;

    // ==================== Error / message fields ====================

    /** Max UTF-8 bytes for a machine-readable error code (e.g. "NC-403", "NC-404"). */
    public static final int MAX_ERROR_CODE = 64;

    /** Max UTF-8 bytes for a human-readable error/response message line. */
    public static final int MAX_ERROR_MESSAGE = 256;

    // ==================== Display / content fields ====================

    /** Max UTF-8 bytes for a TitlePacket title text (supports color codes, hex, multi-line). */
    public static final int MAX_TITLE = 512;

    /** Max UTF-8 bytes for a TitlePacket subtitle text. */
    public static final int MAX_SUBTITLE = 512;

    /** Max UTF-8 bytes for a MentionPacket message preview (a short snippet of the mentioning message). */
    public static final int MAX_MESSAGE_PREVIEW = 256;

    /**
     * Max UTF-8 bytes for a user-authored message body — used by both
     * {@code ChatMessagePacket.content} and {@code PrivateMessagePacket.content}.
     * Both are the body of a player-typed message and share the same 2048-byte
     * budget (the precedent set by ChatMessagePacket before PROTO-003). A
     * single constant keeps the two read paths consistent and gives non-JVM
     * forks one value to mirror.
     */
    public static final int MAX_MESSAGE_CONTENT = 2048;

    // ==================== Auth / handshake fields ====================

    /** Max UTF-8 bytes for a stored credential / password hash (SHA-256 hex = 64 chars; 256 bytes is headroom). */
    public static final int MAX_PASSWORD_HASH = 256;

    /** Max UTF-8 bytes for an HMAC value (HMAC-SHA-256 hex = 64 chars; 128 bytes is headroom). */
    public static final int MAX_HMAC = 128;

    /** Max UTF-8 bytes for a handshake nonce (16 random bytes lowercase-hex = 32 chars; 64 bytes is headroom). */
    public static final int MAX_NONCE = 64;

    /** Max UTF-8 bytes for the reported Minecraft server version string (e.g. "1.20.4"). */
    public static final int MAX_SERVER_VERSION = 64;

    // ==================== Channel password ====================

    /** Max UTF-8 bytes for a channel join password (ChannelActionPacket password). Matches a generous user password. */
    public static final int MAX_CHANNEL_PASSWORD = 256;

    // ==================== JSON / action payload fields ====================

    /** Max UTF-8 bytes for an ItemDisplayPacket itemJson (NBT/JSON serialized item; can be large with lore/enchants). */
    public static final int MAX_ITEM_JSON = 8192;

    /** Max UTF-8 bytes for a ChannelActionPacket legacy JSON extra field (single-JSON fallback in read()). */
    public static final int MAX_ACTION_JSON = 8192;

    // ==================== Metadata map fields ====================

    /** Max UTF-8 bytes for a metadata/extra map key. Mirrors ChatMessagePacket placeholder key precedent. */
    public static final int MAX_METADATA_KEY = 128;

    /** Max UTF-8 bytes for a metadata/extra map value. Mirrors ChatMessagePacket placeholder value precedent. */
    public static final int MAX_METADATA_VALUE = 512;
}
