package com.nova.chat.velocity.network;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.velocity.NovaChatVelocity;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Netty channel handler for the NovaChat Velocity client.
 * Handles incoming packets and connection lifecycle events.
 */
public class ClientChannelHandler extends SimpleChannelInboundHandler<Packet> {

    private final NetworkClient networkClient;

    /**
     * Creates a new ClientChannelHandler.
     *
     * @param networkClient the network client
     */
    public ClientChannelHandler(NetworkClient networkClient) {
        this.networkClient = networkClient;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
        // Delegate packet handling to the network client
        networkClient.handlePacket(packet);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        NovaChatVelocity plugin = NovaChatVelocity.getInstance();
        if (plugin != null) {
            plugin.debug("Channel active: " + ctx.channel().remoteAddress());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        NovaChatVelocity plugin = NovaChatVelocity.getInstance();
        if (plugin != null) {
            plugin.debug("Channel inactive: " + ctx.channel().remoteAddress());
        }
        networkClient.onDisconnect();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        NovaChatVelocity plugin = NovaChatVelocity.getInstance();
        if (plugin != null) {
            plugin.getLogger().warn("Network error: " + cause.getMessage());
            plugin.debug("Network exception details: " + cause);
        }
        ctx.close();
    }
}
