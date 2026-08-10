package com.nova.chat.common.protocol.codec;

import io.netty.util.AttributeKey;

/**
 * Netty channel attributes used by NovaProtocol codecs.
 */
public final class ProtocolAttributes {

    private ProtocolAttributes() {}

    /**
     * When true, the connection uses the legacy frame format:
     * | Length (VarInt) | PacketId (Byte) | Payload |
     *
     * When false (or unset), the connection uses the current format:
     * | Length (VarInt) | PacketId (Byte) | RequestId (UUID) | Payload |
     */
    public static final AttributeKey<Boolean> LEGACY_NO_REQUEST_ID =
            AttributeKey.valueOf("novachat.protocol.legacyNoRequestId");
}


