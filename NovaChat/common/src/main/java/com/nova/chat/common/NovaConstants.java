package com.nova.chat.common;

import com.nova.chat.common.protocol.NovaProtocol;

/**
 * Common constants shared between NovaLink and NovaChat plugins.
 */
public final class NovaConstants {

    private NovaConstants() {
        // Utility class
    }

    // Protocol Version — single source is NovaProtocol.PROTOCOL_VERSION; this
    // alias is kept only for the NovaConstants public API surface. v3 (AUTH-002):
    // challenge-response handshake replaces the replayable static-hash 0x01 flow;
    // see NovaProtocol for the change history.
    public static final int PROTOCOL_VERSION = NovaProtocol.PROTOCOL_VERSION;
    
    // Default Ports
    public static final int DEFAULT_PORT = 8888;
    public static final int DEFAULT_WEBSOCKET_PORT = 8889;
    
    // Packet IDs — legacy alias set. The authoritative, complete registry is
    // com.nova.chat.common.protocol.PacketIds (which also covers HANDSHAKE_INIT /
    // HANDSHAKE_CHALLENGE / HANDSHAKE_AUTHENTICATE / PRIVATE_MESSAGE not mirrored
    // here). These byte aliases predate PacketIds and are kept for the public
    // NovaConstants API surface; see PacketIds for per-id registration status.
    public static final byte PACKET_HANDSHAKE = 0x01;
    public static final byte PACKET_HANDSHAKE_RESPONSE = 0x02;
    public static final byte PACKET_CHAT_MESSAGE = 0x03;
    public static final byte PACKET_CHANNEL_ACTION = 0x04;
    public static final byte PACKET_CHANNEL_ACTION_RESPONSE = 0x05;
    public static final byte PACKET_CONFIG_SYNC = 0x06;
    public static final byte PACKET_KEEP_ALIVE = 0x07;
    // No Java packet class; ID 0x08 reserved and mirrored by PHP/Python/C++ clients.
    public static final byte PACKET_PLAYER_STATE = 0x08;
    public static final byte PACKET_TITLE = 0x09;
    // No Java packet class; ID 0x0A deprecated (announcements route via
    // AdminAction STATUS). PHP/C++ keep the constant for numbering; Python omits it.
    public static final byte PACKET_ANNOUNCEMENT = 0x0A;
    public static final byte PACKET_ADMIN_ACTION = 0x0B;
    public static final byte PACKET_ADMIN_ACTION_RESPONSE = 0x0C;
    // No Java packet class; ID 0x0D used by PHP/Python clients (ChannelUpdatePacket).
    public static final byte PACKET_CHANNEL_UPDATE = 0x0D;
    public static final byte PACKET_ITEM_DISPLAY = 0x10;
    public static final byte PACKET_MENTION = 0x12;

    // Error Codes
    // These are the protocol-layer wire constants (NC-XXX strings). The
    // authoritative, renderable client-facing definition lives in the shared
    // enum com.nova.chat.client.error.ErrorCode (novachat-client-core); treat
    // that enum as the single source of truth for message/suggestion text.
    // These constants are retained here only because novalink-core cannot
    // depend on novachat-client-core (Architecture B boundary).
    public static final String ERROR_BAD_REQUEST = "NC-400";
    public static final String ERROR_UNAUTHORIZED = "NC-401";
    public static final String ERROR_FORBIDDEN = "NC-403";
    public static final String ERROR_NOT_FOUND = "NC-404";
    public static final String ERROR_CONFLICT = "NC-409";
    public static final String ERROR_INVITATION_EXPIRED = "NC-410";
    public static final String ERROR_INVITATION_USED = "NC-411";
    public static final String ERROR_TOO_MANY_REQUESTS = "NC-429";
    public static final String ERROR_INTERNAL = "NC-500";
    public static final String ERROR_SERVICE_UNAVAILABLE = "NC-503";
    public static final String ERROR_PROTOCOL_MISMATCH = "NC-420";
    public static final String ERROR_CHANNEL_FULL = "NC-431";
    public static final String ERROR_NOT_IN_CHANNEL = "NC-433";
    public static final String ERROR_INCORRECT_PASSWORD = "NC-434";
    public static final String ERROR_WORLD_RESTRICTED = "NC-435";
    public static final String ERROR_DATABASE_ERROR = "NC-510";

    // Channel Scopes
    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_SERVER = "SERVER";
    public static final String SCOPE_PRIVATE = "PRIVATE";
    
    // Permission Nodes
    public static final String PERMISSION_ADMIN = "novachat.admin";
    public static final String PERMISSION_BYPASS_WORLD = "novachat.bypass.world";
    public static final String PERMISSION_CHANNEL_PREFIX = "novachat.channel.";
    
    // Timeouts
    public static final int HEARTBEAT_INTERVAL_MS = 30000;
    public static final int CONNECTION_TIMEOUT_MS = 10000;
    public static final int AUTH_TIMEOUT_MS = 5000;
    /**
     * TTL for in-flight channel/admin action requests awaiting an asynchronous
     * response packet. Shared by the shared {@code ChannelResponseTracker} and
     * the Bukkit pending-request map. Distinct from {@link #HEARTBEAT_INTERVAL_MS}
     * (which paces keepalive), though both happen to be 30s.
     */
    public static final long PENDING_REQUEST_TIMEOUT_MS = 30_000L;
    
    // Limits
    public static final int MAX_MESSAGE_LENGTH = 256;
    public static final int MAX_CHANNEL_NAME_LENGTH = 32;
    public static final int MAX_PASSWORD_LENGTH = 64;
    public static final int INVITATION_CODE_LENGTH = 6;
    public static final int PRIVATE_CHANNEL_ID_LENGTH = 4;

    /**
     * Well-known all-zero sentinel UUID used for console/RCON-originated
     * packets. The backend treats this UUID as {@code SUPER_ADMIN} (see
     * {@code com.nova.link.console.ConsoleSentinel} on the StarLink core
     * side). Use this string via {@code UUID.fromString} when a {@code UUID}
     * instance is needed; use it raw when the packet API wants a string.
     */
    public static final String CONSOLE_SENTINEL_UUID = "00000000-0000-0000-0000-000000000000";
}
