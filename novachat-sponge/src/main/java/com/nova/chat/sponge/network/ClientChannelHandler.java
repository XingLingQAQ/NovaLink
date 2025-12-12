package com.nova.chat.sponge.network;

import com.nova.chat.common.protocol.Packet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Netty channel handler for processing incoming packets from NovaLink backend.
 * 
 * Requirements: 3.1, 3.5
 */
public class ClientChannelHandler extends SimpleChannelInboundHandler<Packet> {

    private final NetworkClient client;

    /**
     * Creates a new ClientChannelHandler.
     *
     * @param client the network client
     */
    public ClientChannelHandler(NetworkClient client) {
        this.client = client;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
        client.handlePacket(packet);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        client.onDisconnect();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Log the exception but don't close the channel immediately
        // The reconnection logic will handle it
        cause.printStackTrace();
        ctx.close();
    }
}
