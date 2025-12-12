package com.nova.chat.common;

/**
 * Common constants shared between NovaLink and NovaChat plugins.
 */
public final class NovaConstants {
    
    private NovaConstants() {
        // Utility class
    }
    
    // Protocol Version
    public static final int PROTOCOL_VERSION = 1;
    
    // Default Ports
    public static final int DEFAULT_PORT = 8888;
    public static final int DEFAULT_WEBSOCKET_PORT = 8889;
    
    // Packet IDs
    public static final byte PACKET_HANDSHAKE = 0x01;
    public static final byte PACKET_HANDSHAKE_RESPONSE = 0x02;
    public static final byte PACKET_CHAT_MESSAGE = 0x03;
    public static final byte PACKET_CHANNEL_ACTION = 0x04;
    public static final byte PACKET_CHANNEL_ACTION_RESPONSE = 0x05;
    public static final byte PACKET_CONFIG_SYNC = 0x06;
    public static final byte PACKET_KEEP_ALIVE = 0x07;
    public static final byte PACKET_PLAYER_STATE = 0x08;
    public static final byte PACKET_TITLE = 0x09;
    public static final byte PACKET_ANNOUNCEMENT = 0x0A;
    public static final byte PACKET_ADMIN_ACTION = 0x0B;
    
    // Error Codes
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
    
    // Limits
    public static final int MAX_MESSAGE_LENGTH = 256;
    public static final int MAX_CHANNEL_NAME_LENGTH = 32;
    public static final int MAX_PASSWORD_LENGTH = 64;
    public static final int INVITATION_CODE_LENGTH = 6;
    public static final int PRIVATE_CHANNEL_ID_LENGTH = 4;
}
