package com.nova.chat.common.protocol;

/**
 * Constants for NovaProtocol packet IDs.
 *
 * <p>Core IDs that have Java implementations are registered in
 * {@link NovaProtocol#createRegistry()}. Orphan IDs below are reserved for
 * cross-platform / future features and intentionally lack a Java packet class
 * until an implementation lands.
 */
public final class PacketIds {

    private PacketIds() {
        // Constants class
    }

    // ==================== Core packets (registered in NovaProtocol) ====================

    /** Handshake authentication (Client → Server). Registered: {@code HandshakePacket}. */
    public static final int HANDSHAKE = 0x01;

    /** Handshake response (Server → Client). Registered: {@code HandshakeResponsePacket}. */
    public static final int HANDSHAKE_RESPONSE = 0x02;

    /** Chat message (Bidirectional). Registered: {@code ChatMessagePacket}. */
    public static final int CHAT_MESSAGE = 0x03;

    /** Channel action (Client → Server). Registered: {@code ChannelActionPacket}. */
    public static final int CHANNEL_ACTION = 0x04;

    /** Channel action response (Server → Client). Registered: {@code ChannelActionResponsePacket}. */
    public static final int CHANNEL_ACTION_RESPONSE = 0x05;

    /** Configuration sync (Server → Client). Registered: {@code ConfigSyncPacket}. */
    public static final int CONFIG_SYNC = 0x06;

    /** Keep-alive heartbeat (Bidirectional). Registered: {@code KeepAlivePacket}. */
    public static final int KEEP_ALIVE = 0x07;

    /** Title message (Server → Client). Registered: {@code TitlePacket}. */
    public static final int TITLE = 0x09;

    /** Admin action (Client → Server). Registered: {@code AdminActionPacket}. */
    public static final int ADMIN_ACTION = 0x0B;

    /** Admin action response (Server → Client). Registered: {@code AdminActionResponsePacket}. */
    public static final int ADMIN_ACTION_RESPONSE = 0x0C;

    /** Title message (Server → Client) - alias for {@link #TITLE}. Not a distinct packet id. */
    public static final int TITLE_MESSAGE = TITLE;

    // ==================== Display feature packets (registered in NovaProtocol) ====================

    /** Item display packet (Bidirectional). Registered: {@code ItemDisplayPacket}. */
    public static final int ITEM_DISPLAY = 0x10;

    /** Mention notification packet (Server → Client). Registered: {@code MentionPacket}. */
    public static final int MENTION = 0x12;

    // ==================== Reserved / orphan IDs (no Java packet class yet) ====================
    // These constants are kept for protocol-ID stability with PHP/Python clients and
    // future Java support. They are intentionally NOT registered in NovaProtocol until
    // a full packet implementation exists under protocol/packets/.

    /**
     * Player state sync (Bidirectional).
     * <p>ORPHAN: reserved id — no Java {@code Packet} implementation. Not registered.
     */
    public static final int PLAYER_STATE = 0x08;

    /**
     * Announcement message (Server → Client).
     * <p>ORPHAN: reserved id — implemented on PMMP/Endstone clients only; no Java packet class.
     * Not registered in {@link NovaProtocol#createRegistry()}.
     */
    public static final int ANNOUNCEMENT = 0x0A;

    /**
     * Channel update notification (Server → Client).
     * <p>ORPHAN: reserved id — implemented on PMMP client only; no Java packet class.
     * Not registered in {@link NovaProtocol#createRegistry()}.
     */
    public static final int CHANNEL_UPDATE = 0x0D;

    /**
     * Inventory snapshot packet (Bidirectional).
     * <p>ORPHAN: reserved id for a planned inventory-share feature — no Java packet class.
     * Not registered.
     */
    public static final int INVENTORY_SNAPSHOT = 0x11;

    /**
     * Image display packet (Bidirectional).
     * <p>ORPHAN: reserved id for a planned image-display feature — no Java packet class.
     * Not registered.
     */
    public static final int IMAGE_DISPLAY = 0x13;
}
