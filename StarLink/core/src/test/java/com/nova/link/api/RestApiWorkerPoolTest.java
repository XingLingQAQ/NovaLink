package com.nova.link.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.config.ConfigManager;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.mute.MuteManager;
import com.nova.link.ban.BanManager;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.notification.NotificationStore;
import com.nova.link.websocket.JwtService;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * REST worker-pool offload tests for {@link RestApiHandler}: default inline
 * execution (test/"directExecutor" semantics), async execution on a real
 * pool, saturation → 503, and FullHttpRequest reference-count balance.
 */
@DisplayName("RestApiHandler worker pool offload")
class RestApiWorkerPoolTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    private RestApiHandler handler;
    private JwtService jwtService;
    private String validToken;

    @BeforeEach
    void setUp() {
        ChannelManager channelManager = new ChannelManager();
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("global")
                .displayName("Global")
                .scope(ChannelScope.GLOBAL)
                .build());

        jwtService = new JwtService(SECRET_KEY);
        handler = new RestApiHandler(
                jwtService,
                new AuthManager(new IpBanManager(5, 60_000)),
                channelManager,
                mock(PlayerStateManager.class),
                mock(MessageRouter.class),
                new WebhookManager(),
                mock(MuteManager.class),
                mock(BanManager.class),
                mock(InvitationManager.class),
                mock(ConfigManager.class),
                mock(ServerNetworkHandler.class),
                mock(ConsoleCommandHandler.class),
                mock(NotificationStore.class)
        );
        validToken = jwtService.generateToken(UUID.randomUUID().toString(), "admin", "SUPER_ADMIN");
    }

    private FullHttpRequest buildRequest() {
        // A real (non-EMPTY_BUFFER) content buffer so refCnt assertions are
        // meaningful: Unpooled.EMPTY_BUFFER ignores retain/release entirely.
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/channels", Unpooled.buffer(0));
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + validToken);
        return request;
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
    @DisplayName("no executor (default) keeps requests synchronous — directExecutor semantics")
    void defaultInlineExecution() throws Exception {
        FullHttpRequest request = buildRequest();
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelHandlerContext ctx = mockContext(captured, null);

        handler.channelRead0(ctx, request);

        FullHttpResponse response = (FullHttpResponse) captured.get();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(asJson(response).getAsJsonArray("channels")).hasSize(1);
        // No retain happened in the inline path: refCnt untouched.
        assertThat(request.refCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("explicit direct executor behaves identically and balances refCnt")
    void directExecutorBehavesIdentically() throws Exception {
        handler.setWorkerExecutor(Runnable::run);
        FullHttpRequest request = buildRequest();
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelHandlerContext ctx = mockContext(captured, null);

        handler.channelRead0(ctx, request);

        FullHttpResponse response = (FullHttpResponse) captured.get();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        // retain + release balanced: back to the caller-owned single reference.
        assertThat(request.refCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("async worker pool produces the same response off the IO thread")
    void asyncExecution() throws Exception {
        ExecutorService pool = RestApiHandler.newWorkerPool(2);
        try {
            handler.setWorkerExecutor(pool);
            FullHttpRequest request = buildRequest();
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
    @DisplayName("saturated pool answers 503 immediately with balanced refCnt")
    void saturatedPoolAnswers503() throws Exception {
        handler.setWorkerExecutor(command -> {
            throw new RejectedExecutionException("pool full");
        });
        FullHttpRequest request = buildRequest();
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelHandlerContext ctx = mockContext(captured, null);

        handler.channelRead0(ctx, request);

        FullHttpResponse response = (FullHttpResponse) captured.get();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        assertThat(request.refCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("newWorkerPool builds a bounded fixed pool that aborts when saturated")
    void workerPoolFactory() throws Exception {
        ExecutorService pool = RestApiHandler.newWorkerPool(1);
        try {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) pool;
            assertThat(tpe.getMaximumPoolSize()).isEqualTo(1);
            assertThat(tpe.getQueue().remainingCapacity()).isEqualTo(32);

            // Occupy the single worker, fill the queue, then expect rejection.
            CountDownLatch block = new CountDownLatch(1);
            tpe.execute(() -> {
                try {
                    block.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            for (int i = 0; i < 32; i++) {
                tpe.execute(() -> { });
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tpe.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
            block.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("auth passthrough (/api/auth/*) stays on the IO thread")
    void authPassthroughStaysInline() throws Exception {
        handler.setWorkerExecutor(command -> {
            throw new AssertionError("auth requests must not be offloaded by RestApiHandler");
        });
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/auth/login", Unpooled.buffer(0));
        AtomicReference<Object> fired = new AtomicReference<>();
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        doAnswer(inv -> {
            fired.set(inv.getArgument(0));
            return null;
        }).when(ctx).fireChannelRead(any());

        handler.channelRead0(ctx, request);

        assertThat(fired.get()).isSameAs(request);
        // fireChannelRead(request.retain()) hands one reference to the next handler.
        assertThat(request.refCnt()).isEqualTo(2);
        request.release(2);
    }
}
