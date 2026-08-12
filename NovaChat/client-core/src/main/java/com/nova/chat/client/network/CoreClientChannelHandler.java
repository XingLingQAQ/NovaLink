package com.nova.chat.client.network;

import com.nova.chat.common.protocol.Packet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Shared Netty inbound handler for {@link CoreNetworkClient}.
 *
 * <p>Delegates packet dispatch and disconnect notification to the core client.
 * Platform modules should not need a local copy of this handler when they own
 * a {@link CoreNetworkClient} instance.
 */
public final class CoreClientChannelHandler extends SimpleChannelInboundHandler<Packet> {

    private final CoreNetworkClient client;

    public CoreClientChannelHandler(CoreNetworkClient client) {
        this.client = client;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
        client.handlePacket(packet);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        client.logger().debug("Channel active: " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        client.logger().debug("Channel inactive: " + ctx.channel().remoteAddress());
        client.onDisconnect();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        client.logger().warn("Network error: " + (cause != null ? cause.getMessage() : "unknown"));
        if (cause != null) {
            client.logger().debug("Network exception details: " + cause);
        }
        ctx.close();
    }
}
