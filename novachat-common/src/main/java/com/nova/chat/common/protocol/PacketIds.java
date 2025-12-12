package com.nova.chat.common.protocol;

/**
 * Constants for NovaProtocol packet IDs.
 */
public final class PacketIds {

    private PacketIds() {
        // Constants class
    }

    /** Handshake authentication (Client → Server) */
    public static final int HANDSHAKE = 0x01;

    /** Handshake response (Server → Client) */
    public static final int HANDSHAKE_RESPONSE = 0x02;

    /** Chat message (Bidirectional) */
    public static final int CHAT_MESSAGE = 0x03;

    /** Channel action (Client → Server) */
    public static final int CHANNEL_ACTION = 0x04;

    /** Channel action response (Server → Client) */
    public static final int CHANNEL_ACTION_RESPONSE = 0x05;

    /** Configuration sync (Server → Client) */
    public static final int CONFIG_SYNC = 0x06;

    /** Keep-alive heartbeat (Bidirectional) */
    public static final int KEEP_ALIVE = 0x07;

    /** Player state sync (Bidirectional) */
    public static final int PLAYER_STATE = 0x08;

    /** Title message (Server → Client) */
    public static final int TITLE = 0x09;

    /** Announcement message (Server → Client) */
    public static final int ANNOUNCEMENT = 0x0A;

    /** Admin action (Client → Server) */
    public static final int ADMIN_ACTION = 0x0B;

    /** Admin action response (Server → Client) */
    public static final int ADMIN_ACTION_RESPONSE = 0x0C;

    /** Channel update notification (Server → Client) */
    public static final int CHANNEL_UPDATE = 0x0D;

    /** Title message (Server → Client) - alias for TITLE */
    public static final int TITLE_MESSAGE = TITLE;

    // ==================== Display Feature Packets ====================

    /** Item display packet (Bidirectional) */
    public static final int ITEM_DISPLAY = 0x10;

    /** Inventory snapshot packet (Bidirectional) */
    public static final int INVENTORY_SNAPSHOT = 0x11;

    /** Mention notification packet (Server → Client) */
    public static final int MENTION = 0x12;

    /** Image display packet (Bidirectional) */
    public static final int IMAGE_DISPLAY = 0x13;
}
