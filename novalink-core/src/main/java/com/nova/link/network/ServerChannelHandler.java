package com.nova.link.network;

import com.nova.chat.common.protocol.Packet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty channel handler that bridges incoming packets to the ServerNetworkHandler.
 * Handles connection lifecycle events and packet routing.
 */
public class ServerChannelHandler extends SimpleChannelInboundHandler<Packet> {

    private static final Logger logger = LoggerFactory.getLogger(ServerChannelHandler.class);

    private final ServerNetworkHandler networkHandler;
    private ClientConnection connection;

    public ServerChannelHandler(ServerNetworkHandler networkHandler) {
        this.networkHandler = networkHandler;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        connection = new ClientConnection(ctx.channel());
        logger.info("Client connected from {}", connection.getRemoteAddress());
        networkHandler.onClientConnected(connection);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (connection != null) {
            logger.info("Client disconnected from {}", connection.getRemoteAddress());
            networkHandler.onClientDisconnected(connection);
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) throws Exception {
        if (connection != null) {
            networkHandler.handlePacket(connection, packet);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Exception in channel handler for {}", 
                connection != null ? connection.getRemoteAddress() : "unknown", cause);
        ctx.close();
    }
}
