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
     * IMPORTANT: When updating this value, also update:
     *   - novalink-go/pkg/protocol/packet.go (ProtocolVersion)
     *   - novachat-pmmp/src/NovaChat/Protocol/HandshakePacket.php (PROTOCOL_VERSION)
     *   - novachat-endstone/novachat_endstone/network/client.py (PROTOCOL_VERSION)
     */
    public static final int PROTOCOL_VERSION = 1;

    private NovaProtocol() {
        // Utility class
    }

    /**
     * Creates a new PacketRegistry with all core packet types registered.
     *
     * @return a configured PacketRegistry
     */
    public static PacketRegistry createRegistry() {
        PacketRegistry registry = new PacketRegistry();
        
        // Register core packets
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
        
        // Display feature packets
        registry.register(PacketIds.ITEM_DISPLAY, ItemDisplayPacket.class, ItemDisplayPacket::new);
        registry.register(PacketIds.MENTION, MentionPacket.class, MentionPacket::new);
        
        return registry;
    }
}
