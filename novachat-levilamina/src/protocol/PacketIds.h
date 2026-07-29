#pragma once

#include <cstdint>

namespace novachat::protocol {

/**
 * Constants for NovaProtocol packet IDs.
 */
struct PacketIds {
    /** Handshake authentication (Client → Server) */
    static constexpr uint8_t HANDSHAKE = 0x01;

    /** Handshake response (Server → Client) */
    static constexpr uint8_t HANDSHAKE_RESPONSE = 0x02;

    /** Chat message (Bidirectional) */
    static constexpr uint8_t CHAT_MESSAGE = 0x03;

    /** Channel action (Client → Server) */
    static constexpr uint8_t CHANNEL_ACTION = 0x04;

    /** Channel action response (Server → Client) */
    static constexpr uint8_t CHANNEL_ACTION_RESPONSE = 0x05;

    /** Configuration sync (Server → Client) */
    static constexpr uint8_t CONFIG_SYNC = 0x06;

    /** Keep-alive heartbeat (Bidirectional) */
    static constexpr uint8_t KEEP_ALIVE = 0x07;

    /** Player state sync (Bidirectional) */
    static constexpr uint8_t PLAYER_STATE = 0x08;

    /** Title message (Server → Client) */
    static constexpr uint8_t TITLE = 0x09;

    /** Announcement message (Server → Client) */
    static constexpr uint8_t ANNOUNCEMENT = 0x0A;

    /** Admin action (Client → Server) */
    static constexpr uint8_t ADMIN_ACTION = 0x0B;

    /** Admin action response (Server → Client) */
    static constexpr uint8_t ADMIN_ACTION_RESPONSE = 0x0C;
};

/**
 * Platform type identifiers
 */
enum class PlatformType : uint8_t {
    BUKKIT = 0,
    VELOCITY = 1,
    BUNGEECORD = 2,
    NUKKIT = 3,
    LEVILAMINA = 4
};

/**
 * Channel action types
 */
enum class ChannelAction : uint8_t {
    JOIN = 0,
    LEAVE = 1,
    CREATE = 2,
    DELETE = 3,
    INVITE = 4,
    KICK = 5
};

} // namespace novachat::protocol
