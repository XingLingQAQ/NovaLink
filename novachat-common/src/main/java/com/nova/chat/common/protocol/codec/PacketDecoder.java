package com.nova.chat.common.protocol.codec;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.UUID;
import java.util.List;

/**
 * Decodes ByteBuf frames into Packet objects using the PacketRegistry.
 */
public class PacketDecoder extends MessageToMessageDecoder<ByteBuf> {

    private final PacketRegistry registry;

    public PacketDecoder(PacketRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        if (msg == null || !msg.isReadable()) {
            return;
        }

        // Packet ID is always the first byte (VarInt encodings with IDs < 128 are 1 byte, so still compatible).
        int packetId = msg.readUnsignedByte();
        Packet packet = registry.createPacket(packetId);
        if (packet == null) {
            return;
        }

        Boolean legacy = ctx.channel().attr(ProtocolAttributes.LEGACY_NO_REQUEST_ID).get();
        if (Boolean.TRUE.equals(legacy)) {
            // Legacy frame: no requestId in the wire format.
            packet.setRequestId(UUID.randomUUID());
            packet.read(msg);
            out.add(packet);
            return;
        }

        // Unknown or modern: prefer modern decode; for first-handshake we can auto-detect legacy.
        if (legacy == null && packetId == PacketIds.HANDSHAKE) {
            if (tryDecodeHandshakeWithRequestId(ctx, msg, packet)) {
                out.add(packet);
                return;
            }

            // Fall back to legacy handshake (no requestId).
            Packet legacyPacket = registry.createPacket(packetId);
            if (legacyPacket == null) {
                return;
            }
            legacyPacket.setRequestId(UUID.randomUUID());
            legacyPacket.read(msg);
            ctx.channel().attr(ProtocolAttributes.LEGACY_NO_REQUEST_ID).set(true);
            out.add(legacyPacket);
            return;
        }

        // Modern decode path
        packet.decode(msg);
        // If it wasn't determined yet (e.g. server-side pipeline), mark as modern after any successful decode.
        if (legacy == null) {
            ctx.channel().attr(ProtocolAttributes.LEGACY_NO_REQUEST_ID).set(false);
        }
        out.add(packet);
    }

    private boolean tryDecodeHandshakeWithRequestId(ChannelHandlerContext ctx, ByteBuf msg, Packet packet) {
        int mark = msg.readerIndex();
        try {
            packet.decode(msg);
            if (!(packet instanceof HandshakePacket handshake)) {
                ctx.channel().attr(ProtocolAttributes.LEGACY_NO_REQUEST_ID).set(false);
                return true;
            }

            // Heuristic validation: avoid mis-detecting legacy frames as modern.
            int protocolVersion = handshake.getProtocolVersion();
            String clientId = handshake.getClientId();
            String passwordHash = handshake.getPasswordHash();

            boolean plausibleProtocol = protocolVersion >= 0 && protocolVersion <= 100;
            boolean plausibleClient = clientId != null && !clientId.isBlank() && clientId.length() <= 64;
            boolean plausibleHash = passwordHash != null && !passwordHash.isBlank() && passwordHash.length() <= 256;
            boolean plausiblePlatform = handshake.getPlatform() != null;

            if (plausibleProtocol && plausibleClient && plausibleHash && plausiblePlatform) {
                ctx.channel().attr(ProtocolAttributes.LEGACY_NO_REQUEST_ID).set(false);
                return true;
            }

            // Not plausible: treat as failure and retry legacy.
            msg.readerIndex(mark);
            return false;
        } catch (Exception e) {
            msg.readerIndex(mark);
            return false;
        }
    }
}
