package com.nova.chat.common.protocol;

import com.nova.chat.common.protocol.packets.*;

/**
 * NovaProtocol constants and utilities.
 * Provides a pre-configured PacketRegistry with all core packet types.
 */
public final class NovaProtocol {

    /**
     * Current protocol version.
     * Requirements: 27.5 - Go and Java backends must use the same protocol version.
     * IMPORTANT: When updating this value (or the packet set), also update the
     * non-JVM protocol implementations:
     *   - PHP:    NovaChat/Bedrock/pmmp/src/NovaChat/Protocol/ (HandshakePacket.php PROTOCOL_VERSION,
     *             Packet.php packet-id constants + createPacket registry)
     *   - Python: NovaChat/Bedrock/endstone/novachat_endstone/network/client.py (PROTOCOL_VERSION),
     *             novachat_endstone/protocol/packet.py (PacketIds + PACKET_REGISTRY)
     *   - C++:    NovaChat/Bedrock/levilamina/src/protocol/PacketIds.h (PROTOCOL_VERSION + ids),
     *             src/protocol/Packet.h (packet classes),
     *             src/network/NetworkClient.cpp (decodePacket registry)
     *
     * v2 (2026-08): HandshakePacket adds trailing serverVersion field. Old v1
     * clients are rejected with NC-420 to avoid frame-decoder byte drift.
     * v2 (2026-08, additive): PrivateMessagePacket (0x14) added without a
     * version bump — old clients skip unknown packet ids safely (PHP/Python/C++
     * decoders all drop unknown frames without breaking the connection).
     * v3 (2026-08, AUTH-002): HandshakePacket (0x01) is superseded by a
     * replay-resistant 3-packet challenge-response handshake:
     * HANDSHAKE_INIT (0x15), HANDSHAKE_CHALLENGE (0x16),
     * HANDSHAKE_AUTHENTICATE (0x17). The legacy 0x01 packet is still registered
     * so existing integration paths compile, but the live client/server flows
     * now use the new dance. Old v2 clients are rejected with NC-420.
     */
    public static final int PROTOCOL_VERSION = 3;

    private NovaProtocol() {
        // Utility class
    }

    /**
     * Creates a new PacketRegistry with all core packet types registered.
     *
     * <p>Core set (13 ids): HANDSHAKE, HANDSHAKE_RESPONSE, CHAT_MESSAGE,
     * CHANNEL_ACTION, CHANNEL_ACTION_RESPONSE, CONFIG_SYNC, KEEP_ALIVE, TITLE,
     * ADMIN_ACTION, ADMIN_ACTION_RESPONSE, ITEM_DISPLAY, MENTION,
     * PRIVATE_MESSAGE.
     *
     * <p>AUTH-002 challenge-response handshake (3 ids): HANDSHAKE_INIT (0x15),
     * HANDSHAKE_CHALLENGE (0x16), HANDSHAKE_AUTHENTICATE (0x17).
     *
     * <p>Intentionally unregistered orphan ids (no Java packet class yet):
     * PLAYER_STATE (0x08), ANNOUNCEMENT (0x0A), CHANNEL_UPDATE (0x0D),
     * INVENTORY_SNAPSHOT (0x11), IMAGE_DISPLAY (0x13). See {@link PacketIds}.
     *
     * @return a configured PacketRegistry
     */
    public static PacketRegistry createRegistry() {
        PacketRegistry registry = new PacketRegistry();

        // Core packets
        registry.register(PacketIds.HANDSHAKE, HandshakePacket.class, HandshakePacket::new);
        registry.register(PacketIds.HANDSHAKE_RESPONSE, HandshakeResponsePacket.class, HandshakeResponsePacket::new);
        registry.register(PacketIds.CHAT_MESSAGE, ChatMessagePacket.class, ChatMessagePacket::new);
        registry.register(PacketIds.CHANNEL_ACTION, ChannelActionPacket.class, ChannelActionPacket::new);
        registry.register(PacketIds.CHANNEL_ACTION_RESPONSE, ChannelActionResponsePacket.class, ChannelActionResponsePacket::new);
        registry.register(PacketIds.CONFIG_SYNC, ConfigSyncPacket.class, ConfigSyncPacket::new);
        registry.register(PacketIds.KEEP_ALIVE, KeepAlivePacket.class, KeepAlivePacket::new);
        registry.register(PacketIds.TITLE, TitlePacket.class, TitlePacket::new);
        registry.register(PacketIds.ADMIN_ACTION, AdminActionPacket.class, AdminActionPacket::new);
        registry.register(PacketIds.ADMIN_ACTION_RESPONSE, AdminActionResponsePacket.class, AdminActionResponsePacket::new);

        // Display feature packets (implemented)
        registry.register(PacketIds.ITEM_DISPLAY, ItemDisplayPacket.class, ItemDisplayPacket::new);
        registry.register(PacketIds.MENTION, MentionPacket.class, MentionPacket::new);

        // Private message packet (cross-server /msg + /reply)
        registry.register(PacketIds.PRIVATE_MESSAGE, PrivateMessagePacket.class, PrivateMessagePacket::new);

        // AUTH-002 challenge-response handshake (replaces the replayable 0x01 flow).
        // Wire format MUST stay byte-for-byte identical with the PHP/Python/C++ forks.
        registry.register(PacketIds.HANDSHAKE_INIT, HandshakeInitPacket.class, HandshakeInitPacket::new);
        registry.register(PacketIds.HANDSHAKE_CHALLENGE, HandshakeChallengePacket.class, HandshakeChallengePacket::new);
        registry.register(PacketIds.HANDSHAKE_AUTHENTICATE, HandshakeAuthenticatePacket.class, HandshakeAuthenticatePacket::new);

        // Orphans deliberately omitted: PLAYER_STATE, ANNOUNCEMENT, CHANNEL_UPDATE,
        // INVENTORY_SNAPSHOT, IMAGE_DISPLAY — reserved in PacketIds only.

        return registry;
    }
}
