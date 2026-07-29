package com.nova.chat.bungee.network;

import com.nova.chat.client.network.CoreClientChannelHandler;
import com.nova.chat.client.network.CoreNetworkClient;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Thin compatibility shim kept so any residual references to the Bungee-local
 * channel handler type still resolve. The live pipeline uses
 * {@link CoreClientChannelHandler} inside {@link CoreNetworkClient}.
 *
 * @deprecated Prefer {@link CoreClientChannelHandler}; this class is unused by
 *             the current pipeline and will be removed once all call sites are gone.
 */
@Deprecated
public class ClientChannelHandler extends ChannelInboundHandlerAdapter {

    private final CoreClientChannelHandler delegate;

    /**
     * Creates a shim that forwards to the shared core handler.
     *
     * @param networkClient the Bungee facade
     */
    public ClientChannelHandler(NetworkClient networkClient) {
        CoreNetworkClient core = networkClient.core();
        this.delegate = new CoreClientChannelHandler(core);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        delegate.channelRead(ctx, msg);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        delegate.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        delegate.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        delegate.exceptionCaught(ctx, cause);
    }
}
