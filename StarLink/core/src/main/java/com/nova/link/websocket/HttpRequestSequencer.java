package com.nova.link.websocket;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

import java.util.ArrayDeque;

/** Keeps HTTP/1.1 responses ordered across the REST and authentication workers. */
final class HttpRequestSequencer extends ChannelDuplexHandler {
    private static final int MAX_PENDING_REQUESTS = 32;
    private final ArrayDeque<FullHttpRequest> pending = new ArrayDeque<>();
    private boolean inFlight;
    private boolean pausedReads;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(message);
            return;
        }
        if (inFlight) {
            if (pending.size() == MAX_PENDING_REQUESTS) {
                request.release();
                ctx.close();
            } else {
                pending.add(request);
            }
            return;
        }
        inFlight = true;
        pausedReads = ctx.channel().config().isAutoRead();
        if (pausedReads) { ctx.channel().config().setAutoRead(false); }
        ctx.fireChannelRead(request);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (message instanceof FullHttpResponse response
                && (response.status().code() >= 200 || response.status().code() == 101)) {
            promise.addListener(result -> {
                if (result.isSuccess()) { ctx.executor().execute(() -> advance(ctx)); }
                else { ctx.close(); }
            });
        }
        ctx.write(message, promise);
    }

    private void advance(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) { return; }
        FullHttpRequest next = pending.poll();
        if (next != null) {
            ctx.fireChannelRead(next);
        } else {
            inFlight = false;
            if (pausedReads) {
                pausedReads = false;
                ctx.channel().config().setAutoRead(true);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        FullHttpRequest request;
        while ((request = pending.poll()) != null) { request.release(); }
        ctx.fireChannelInactive();
    }
}
