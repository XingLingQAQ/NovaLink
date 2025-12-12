package com.nova.chat.nukkit.network;

import com.nova.chat.common.protocol.Packet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Netty channel handler for the NovaChat Nukkit client.
 * Handles incoming packets and connection lifecycle events.
 * 
 * Adapted from Bukkit version for Nukkit.
 */
public class ClientChannelHandler extends SimpleChannelInboundHandler<Packet> {

    private final NetworkClient networkClient;

    /**
     * Creates a new ClientChannelHandler.
     *
     * @param networkClient the network client instance
     */
    public ClientChannelHandler(NetworkClient networkClient) {
        this.networkClient = networkClient;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
        networkClient.handlePacket(packet);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        networkClient.onDisconnect();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Log the exception but don't close - let channelInactive handle cleanup
        cause.printStackTrace();
    }
}
