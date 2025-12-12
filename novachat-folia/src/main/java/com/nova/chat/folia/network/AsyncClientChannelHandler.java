package com.nova.chat.folia.network;

import com.nova.chat.common.protocol.Packet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Netty channel handler for the Folia async network client.
 * Handles incoming packets and connection lifecycle events.
 * 
 * Requirements: 2.2
 */
public class AsyncClientChannelHandler extends SimpleChannelInboundHandler<Packet> {
    
    private final AsyncNetworkClient client;
    
    /**
     * Creates a new AsyncClientChannelHandler.
     *
     * @param client the network client
     */
    public AsyncClientChannelHandler(AsyncNetworkClient client) {
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
        // Log the exception but don't close - let channelInactive handle cleanup
        cause.printStackTrace();
    }
}
