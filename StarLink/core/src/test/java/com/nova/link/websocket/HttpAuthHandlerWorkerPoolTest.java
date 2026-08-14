package com.nova.link.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Worker-pool offload tests for {@link HttpAuthHandler}: login works inline
 * (default), on a direct executor and on a real pool; a saturated pool
 * answers 503 with a balanced request refCnt.
 */
@DisplayName("HttpAuthHandler worker pool offload")
class HttpAuthHandlerWorkerPoolTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    private HttpAuthHandler handler;

    @BeforeEach
    void setUp() {
        JwtService jwtService = new JwtService(SECRET_KEY);
        AuthManager authManager = new AuthManager(new IpBanManager(5, 60_000));
        authManager.registerSuperAdmin("root", AuthManager.hashPassword("rootpass"));
        handler = new HttpAuthHandler(jwtService, authManager);
    }

    private FullHttpRequest loginRequest() {
        return new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/auth/login",
                Unpooled.copiedBuffer("{\"username\":\"root\",\"password\":\"rootpass\"}",
                        CharsetUtil.UTF_8));
    }

    private ChannelHandlerContext mockContext(AtomicReference<Object> captured, CountDownLatch latch) {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            if (latch != null) {
                latch.countDown();
            }
            return promise;
        }).when(ctx).writeAndFlush(any());
        return ctx;
    }

    private static JsonObject asJson(FullHttpResponse response) {
        return JsonParser.parseString(response.content().toString(StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    @Test
    @DisplayName("default (no executor) login runs inline and succeeds")
    void defaultInlineLogin() throws Exception {
        FullHttpRequest request = loginRequest();
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelHandlerContext ctx = mockContext(captured, null);

        handler.channelRead0(ctx, request);

        FullHttpResponse response = (FullHttpResponse) captured.get();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(asJson(response).has("token")).isTrue();
        assertThat(request.refCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("direct executor login succeeds with balanced refCnt")
    void directExecutorLogin() throws Exception {
        handler.setWorkerExecutor(Runnable::run);
        FullHttpRequest request = loginRequest();
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelHandlerContext ctx = mockContext(captured, null);

        handler.channelRead0(ctx, request);

        FullHttpResponse response = (FullHttpResponse) captured.get();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(request.refCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("async pool login succeeds off the IO thread")
    void asyncPoolLogin() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            handler.setWorkerExecutor(pool);
            FullHttpRequest request = loginRequest();
            AtomicReference<Object> captured = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            ChannelHandlerContext ctx = mockContext(captured, latch);

            handler.channelRead0(ctx, request);

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            FullHttpResponse response = (FullHttpResponse) captured.get();
            assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
            // The worker releases in a finally block after writeAndFlush; give
            // it a moment to reach the release before asserting balance.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (request.refCnt() != 1 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(request.refCnt()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("saturated pool answers 503 with balanced refCnt")
    void saturatedPoolAnswers503() throws Exception {
        handler.setWorkerExecutor(command -> {
            throw new RejectedExecutionException("pool full");
        });
        FullHttpRequest request = loginRequest();
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelHandlerContext ctx = mockContext(captured, null);

        handler.channelRead0(ctx, request);

        FullHttpResponse response = (FullHttpResponse) captured.get();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        assertThat(request.refCnt()).isEqualTo(1);
    }
}
