package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.common.protocol.Packet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Netty channel handler for the NovaChat client.
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
        NovaChatBukkit.getInstance().debug("Channel active: " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        NovaChatBukkit.getInstance().debug("Channel inactive: " + ctx.channel().remoteAddress());
        networkClient.onDisconnect();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        NovaChatBukkit plugin = NovaChatBukkit.getInstance();
        if (plugin != null) {
            plugin.getLogger().warning("Network error: " + cause.getMessage());
            plugin.debug("Network exception details:", cause);
        }
        ctx.close();
    }
}
