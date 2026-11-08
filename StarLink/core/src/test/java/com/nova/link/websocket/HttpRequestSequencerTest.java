package com.nova.link.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class HttpRequestSequencerTest {
    private FullHttpRequest request(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    @Test
    void asynchronousRestCannotBeOvertakenByAuthOrHealth() {
        var workers = new ArrayList<Runnable>();
        var paths = new ArrayList<String>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestSequencer(),
                new SimpleChannelInboundHandler<FullHttpRequest>() {
                    @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                        String path = request.uri();
                        paths.add(path);
                        Runnable respond = () -> {
                            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                            response.headers().set("X-Path", path);
                            ctx.writeAndFlush(response);
                        };
                        if (path.equals("/api/settings")) workers.add(respond);
                        else respond.run();
                    }
                });
        try {
            channel.writeInbound(request("/api/settings"), request("/api/auth/me"), request("/api/health"));
            assertThat(paths).containsExactly("/api/settings");
            assertThat(channel.config().isAutoRead()).isFalse();
            assertThat((Object) channel.readOutbound()).isNull();
            workers.getFirst().run();
            channel.runPendingTasks();
            for (String path : java.util.List.of("/api/settings", "/api/auth/me", "/api/health")) {
                FullHttpResponse response = channel.readOutbound();
                try { assertThat(response.headers().get("X-Path")).isEqualTo(path); }
                finally { response.release(); }
            }
            assertThat(channel.config().isAutoRead()).isTrue();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void oversizedPipelineClosesAndReleasesQueuedRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestSequencer(),
                new SimpleChannelInboundHandler<FullHttpRequest>() {
                    @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) { }
                });
        var requests = new ArrayList<FullHttpRequest>();
        try {
            channel.writeInbound(request("/api/settings"));
            for (int i = 0; i < 33; i++) {
                FullHttpRequest next = request("/api/health");
                requests.add(next);
                channel.writeInbound(next);
            }
            assertThat(channel.isActive()).isFalse();
            assertThat(requests).allSatisfy(request -> assertThat(request.refCnt()).isZero());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void disconnectReleasesRequestsWaitingForTheWorker() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestSequencer(),
                new SimpleChannelInboundHandler<FullHttpRequest>() {
                    @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) { }
                });
        FullHttpRequest queued = request("/api/health");
        try {
            channel.writeInbound(request("/api/settings"), queued);
            assertThat(queued.refCnt()).isEqualTo(1);
            channel.close();
            assertThat(queued.refCnt()).isZero();
        } finally { channel.finishAndReleaseAll(); }
    }
}
