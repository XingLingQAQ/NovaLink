package com.nova.chat.channel;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class RenderPacketCodec {
    private static final PacketRegistry REGISTRY = NovaProtocol.createRegistry();

    private RenderPacketCodec() { }

    public static byte[] encode(Packet packet) throws ProxyChannelException {
        ByteBuf buffer = Unpooled.buffer();
        try {
            REGISTRY.encode(packet, buffer);
            if (buffer.readableBytes() <= 0 || buffer.readableBytes() > ProtocolLimits.MAX_FRAME_LENGTH) {
                throw new ProxyChannelException("render packet exceeds backend frame limit");
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } catch (RuntimeException error) {
            throw new ProxyChannelException("invalid render packet", error);
        } finally {
            buffer.release();
        }
    }

    public static Packet decode(byte[] bytes) throws ProxyChannelException {
        if (bytes == null || bytes.length == 0 || bytes.length > ProtocolLimits.MAX_FRAME_LENGTH) {
            throw new ProxyChannelException("invalid render packet length");
        }
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            Packet packet = REGISTRY.decode(buffer);
            if (packet == null || buffer.isReadable()) throw new ProxyChannelException("invalid render packet body");
            return packet;
        } catch (RuntimeException error) {
            throw new ProxyChannelException("invalid render packet", error);
        } finally {
            buffer.release();
        }
    }
}
