#pragma once

#include <cstdint>

namespace novachat::protocol {

/**
 * Constants for NovaProtocol packet IDs (must match Java PacketIds).
 */
struct PacketIds {
    /** Handshake authentication (Client -> Server) */
    static constexpr uint8_t HANDSHAKE = 0x01;

    /** Handshake response (Server -> Client) */
    static constexpr uint8_t HANDSHAKE_RESPONSE = 0x02;

    /** Chat message (Bidirectional) */
    static constexpr uint8_t CHAT_MESSAGE = 0x03;

    /** Channel action (Client -> Server) */
    static constexpr uint8_t CHANNEL_ACTION = 0x04;

    /** Channel action response (Server -> Client) */
    static constexpr uint8_t CHANNEL_ACTION_RESPONSE = 0x05;

    /** Configuration sync (Server -> Client) */
    static constexpr uint8_t CONFIG_SYNC = 0x06;

    /** Keep-alive heartbeat (Bidirectional) */
    static constexpr uint8_t KEEP_ALIVE = 0x07;

    /** Player state sync (Bidirectional) - reserved orphan, no Java class yet */
    static constexpr uint8_t PLAYER_STATE = 0x08;

    /** Title message (Server -> Client) */
    static constexpr uint8_t TITLE = 0x09;

    /**
     * Announcement message (Server -> Client).
     *
     * FEATURE-002: deprecated orphan. No Java class was ever written for this
     * ID and the backend never sends it — announcements are broadcast via
     * AdminAction STATUS + type=ANNOUNCE extra -> handleAnnounce ->
     * routeMessage (0x03). The LeviLamina client used to register a log-only
     * handler for 0x0A; that handler has been removed. The constant is kept
     * only to preserve the packet-ID numbering; do not register a handler.
     */
    static constexpr uint8_t ANNOUNCEMENT = 0x0A;

    /** Admin action (Client -> Server) */
    static constexpr uint8_t ADMIN_ACTION = 0x0B;

    /** Admin action response (Server -> Client) */
    static constexpr uint8_t ADMIN_ACTION_RESPONSE = 0x0C;

    /** Channel update notification (Server -> Client) - reserved orphan, no Java class yet */
    static constexpr uint8_t CHANNEL_UPDATE = 0x0D;

    /** Item display (Bidirectional) - [item]/[i] tag display */
    static constexpr uint8_t ITEM_DISPLAY = 0x10;

    /** Mention notification (Server -> Client) - @mention highlight + sound/title */
    static constexpr uint8_t MENTION = 0x12;

    /** Private message (Bidirectional) - cross-server /msg + /reply */
    static constexpr uint8_t PRIVATE_MESSAGE = 0x14;

    // ==================== Challenge-response handshake (AUTH-002) ====================
    /** Handshake init (Client -> Server). */
    static constexpr uint8_t HANDSHAKE_INIT = 0x15;
    /** Handshake challenge (Server -> Client). */
    static constexpr uint8_t HANDSHAKE_CHALLENGE = 0x16;
    /** Handshake authenticate (Client -> Server). */
    static constexpr uint8_t HANDSHAKE_AUTHENTICATE = 0x17;
};

/**
 * Platform type wire IDs (must match Java PlatformType enum).
 */
enum class PlatformType : uint8_t {
    BUKKIT = 0,
    VELOCITY = 1,
    BUNGEECORD = 2,
    NUKKIT = 3,
    LEVILAMINA = 4,
    FABRIC = 5,
    NEOFORGE = 6,
    QUILT = 7,
    FORGE = 8,
    POCKETMINE = 9,
    ENDSTONE = 10,
    POWERNUKKITX = 11,
    FOLIA = 13,
    SPONGE = 14,
};

/**
 * Channel action wire IDs (must match Java ChannelAction enum).
 *
 * NOTE: these are 0-based. Legacy implementations that used 1-based IDs are
 * handled by the Java ChannelAction.fromId() fallback, but new clients must
 * emit the canonical 0-based IDs below.
 *
 * On Windows, <winnt.h> defines DELETE as an access-right macro (0x00010000L).
 * We undef it (and a few other candidates that may collide with enum names) so
 * the enum identifiers below compile cleanly.
 */
#ifdef DELETE
#undef DELETE
#endif
#ifdef CREATE
#undef CREATE
#endif
enum class ChannelAction : uint8_t {
    JOIN = 0,
    LEAVE = 1,
    CREATE = 2,
    DELETE = 3,
    INVITE = 4,
    ACCEPT = 5,
    KICK = 6,
    MUTE = 7,
    UNMUTE = 8,
    BAN = 9,
    UNBAN = 10,
    WHO = 11,
};

/**
 * Admin action wire IDs (must match Java AdminAction enum).
 */
enum class AdminAction : uint8_t {
    AUTH = 0,
    LOGOUT = 1,
    SPY_START = 2,
    SPY_STOP = 3,
    RELOAD = 4,
    STATUS = 5,
};

/**
 * Current NovaProtocol version. Must match Java NovaProtocol.PROTOCOL_VERSION.
 * v3 (2026-08): AUTH-002 — the replayable static SHA-256(password) handshake
 * is replaced by the 3-packet challenge-response flow (HandshakeInit 0x15,
 * HandshakeChallenge 0x16, HandshakeAuthenticate 0x17) keyed by an HMAC
 * over the server+client nonces. Hard cutover; v2 clients can no longer
 * authenticate. The legacy HandshakePacket class is retained for golden-byte
 * decode compatibility only.
 */
static constexpr int32_t PROTOCOL_VERSION = 3;

} // namespace novachat::protocol
