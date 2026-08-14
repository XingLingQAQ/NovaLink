package com.nova.link.network;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty channel handler that bridges incoming packets to the ServerNetworkHandler.
 * Handles connection lifecycle events and packet routing.
 *
 * <p>Idle handling (paired with the {@code IdleStateHandler} installed by
 * {@link NettyServer}): on write-idle the server proactively pings the client
 * with a KeepAlive (Java-side clients only echo, they never initiate); on
 * read-idle the connection is considered dead and closed, which triggers the
 * regular disconnect cleanup path via {@code channelInactive}.</p>
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
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleEvent) {
            if (idleEvent.state() == IdleState.READER_IDLE) {
                String identity = (connection != null && connection.isAuthenticated()
                        && connection.getClientId() != null)
                        ? "clientId=" + connection.getClientId()
                        : "unauthenticated";
                logger.info("Closing idle connection from {} ({}): no data received within idle timeout",
                        connection != null ? connection.getRemoteAddress() : "unknown", identity);
                // Close triggers channelInactive → networkHandler.onClientDisconnected cleanup.
                ctx.close();
            } else if (idleEvent.state() == IdleState.WRITER_IDLE) {
                KeepAlivePacket ping = new KeepAlivePacket(System.currentTimeMillis());
                if (connection != null) {
                    connection.setPendingKeepAliveId(ping.getRequestId());
                }
                ctx.writeAndFlush(ping);
                logger.debug("Sent server-initiated KeepAlive ping to {}",
                        connection != null ? connection.getRemoteAddress() : "unknown");
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Exception in channel handler for {}", 
                connection != null ? connection.getRemoteAddress() : "unknown", cause);
        ctx.close();
    }
}
