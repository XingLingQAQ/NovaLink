package com.nova.link.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.audit.AuditEvent;
import com.nova.link.audit.AuditStore;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.config.ConfigManager;
import com.nova.link.console.BackendContext;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.NettyServer;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.notification.NotificationStore;
import com.nova.link.spy.SpyManager;
import com.nova.link.websocket.JwtService;
import com.nova.link.websocket.WebSocketGateway;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * §11.6 Project 17 (提案 09) integration tests for {@code POST /api/moderation/batch}:
 * batch mute/unmute/ban/unban with an in-memory idempotency cache, ADMIN RBAC,
 * dry-run preview, per-target partial-failure results, and per-target audit.
 *
 * <p>Mirrors the {@code RestApiHandlerAuditTest} wiring: the full 15-arg
 * {@link RestApiHandler} constructor with a real {@link AuditStore} backed by a
 * {@link MemoryProvider} so audit records persist and are queryable.
 *
 * <p>Requirements: §11.6 Project 17 (batch + observability)
 */
@DisplayName("POST /api/moderation/batch (§11.6 Project 17, 提案 09)")
class BatchModerationTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    private RestApiHandler handler;
    private JwtService jwtService;
    private MuteManager muteManager;
    private BanManager banManager;
    private MemoryProvider db;
    private AuditStore auditStore;
    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        db = spy(new MemoryProvider());
        db.initialize();
        auditStore = new AuditStore(db);

        ChannelManager channelManager = new ChannelManager();
        PlayerStateManager playerStateManager = new PlayerStateManager(db);
        WebhookManager webhookManager = new WebhookManager();
        PermissionManager permissionManager = new PermissionManager();
        muteManager = new MuteManager(db, permissionManager, channelManager);
        banManager = new BanManager(db, permissionManager, channelManager);
        NotificationStore notificationStore = new NotificationStore(db);
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        SensitiveWordFilter sensitiveWordFilter = new SensitiveWordFilter();
        ConfigManager configManager = new ConfigManager(tempDir.resolve("novalink-batch-test.yml"));
        // load() materialises the default config so getConfig().getFeatures()
        // is non-null and the settings-revision metric is usable.
        configManager.load();

        // Seed channels used as batch mute scopes.
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("staff")
                .displayName("Staff")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("survival-chat")
                .displayName("Survival")
                .scope(ChannelScope.SERVER)
                .clientId("Survival")
                .build());

        // Mock network handler with one captured connection.
        ServerNetworkHandler networkHandler = mock(ServerNetworkHandler.class);
        ClientConnection capturedClient = mock(ClientConnection.class);
        when(capturedClient.getClientId()).thenReturn("Survival");
        when(capturedClient.isAuthenticated()).thenReturn(true);
        when(networkHandler.findByClientId("Survival")).thenReturn(capturedClient);
        when(networkHandler.getConnections()).thenReturn(java.util.Set.of(capturedClient));

        MessageRouter messageRouter = new MessageRouter(channelManager, networkHandler);
        messageRouter.setMuteManager(muteManager);
        messageRouter.setSensitiveWordFilter(sensitiveWordFilter);
        messageRouter.setPermissionChecker((c, p) -> true);
        SpyManager spyManager = new SpyManager(permissionManager, channelManager, networkHandler);
        messageRouter.setSpyManager(spyManager);

        BackendContext ctx = new BackendContext(
                configManager,
                new AuthManager(new IpBanManager(5, 60000)),
                permissionManager,
                new com.nova.link.auth.ClientPermissionRegistry(),
                db,
                channelManager,
                playerStateManager,
                webhookManager,
                new com.nova.link.channel.PrivateChannelManager(channelManager),
                invitationManager,
                muteManager,
                banManager,
                notificationStore,
                new com.nova.link.announcement.AnnouncementManager(permissionManager, channelManager),
                sensitiveWordFilter,
                networkHandler,
                messageRouter,
                spyManager,
                mock(NettyServer.class),
                mock(WebSocketGateway.class)
        );
        ConsoleCommandHandler consoleCommandHandler = new ConsoleCommandHandler(ctx);

        jwtService = new JwtService(SECRET_KEY);
        // Full 15-arg constructor: real AuditStore wired so per-target audit
        // records persist and are queryable via auditEvents().
        handler = new RestApiHandler(
                jwtService,
                new AuthManager(new IpBanManager(5, 60000)),
                channelManager,
                playerStateManager,
                messageRouter,
                webhookManager,
                muteManager,
                banManager,
                invitationManager,
                configManager,
                networkHandler,
                consoleCommandHandler,
                notificationStore,
                auditStore,
                List.of("*")
        );

        superAdminToken = jwtService.generateToken(UUID.randomUUID().toString(), "root", "SUPER_ADMIN");
    }

    @AfterEach
    void tearDown() {
        muteManager.shutdown();
        banManager.shutdown();
    }

    // ====================== helpers ======================

    /** Builds a batch moderation request body. */
    private static String batchBody(String action, List<UUID> targetIds, String channelId,
                                    long durationMs, String reason, String idempotencyKey,
                                    boolean dryRun) {
        JsonArray arr = new JsonArray();
        for (UUID id : targetIds) {
            arr.add(id.toString());
        }
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        o.add("targetIds", arr);
        if (channelId != null) {
            o.addProperty("channelId", channelId);
        }
        o.addProperty("durationMs", durationMs);
        o.addProperty("reason", reason);
        o.addProperty("idempotencyKey", idempotencyKey);
        if (dryRun) {
            o.addProperty("dryRun", true);
        }
        return o.toString();
    }

    /** Dispatches with the super-admin token. */
    private Response dispatch(HttpMethod method, String uri, String body) {
        return dispatchWithToken(superAdminToken, method, uri, body);
    }

    /** Dispatches with an explicit token (used by the VIEWER-role test). */
    private Response dispatchWithToken(String token, HttpMethod method, String uri, String body) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri,
                body != null ? Unpooled.copiedBuffer(body, CharsetUtil.UTF_8) : Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        }

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        // channelRead0 stores the per-request id as a channel attribute, so the
        // mock must return a real channel that supports AttributeMap.
        EmbeddedChannel channel = new EmbeddedChannel();
        when(ctx.channel()).thenReturn(channel);
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return promise;
        }).when(ctx).writeAndFlush(any());

        try {
            handler.channelRead0(ctx, request);
            Object resp = captured.get();
            if (resp instanceof FullHttpResponse response) {
                try {
                    return new Response(response.status(), response.content().toString(StandardCharsets.UTF_8));
                } finally {
                    response.release();
                }
            }
            return new Response(null, "");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            request.release();
            channel.finishAndReleaseAll();
        }
    }

    private static class Response {
        final HttpResponseStatus status;
        final String body;
        Response(HttpResponseStatus status, String body) {
            this.status = status;
            this.body = body;
        }
        JsonObject asJson() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }
    /** All audit events currently persisted, newest-first. */
    private List<AuditEvent> auditEvents() {
        return auditStore.list(0, 100, null, null);
    }

    // ====================== tests ======================

    @Test
    @DisplayName("dryRun=true returns a preview with no side effects and no audit")
    void batchMuteDryRunReturnsPreviewNoSideEffect() {
        assertThat(auditEvents()).isEmpty();

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        String body = batchBody("mute", List.of(t1, t2), "staff", 60000, "spam", "dry-1", true);
        Response resp = dispatch(HttpMethod.POST, "/api/moderation/batch", body);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);

        JsonObject json = resp.asJson();
        assertThat(json.get("dryRun").getAsBoolean()).isTrue();
        assertThat(json.get("action").getAsString()).isEqualTo("mute");
        assertThat(json.get("total").getAsInt()).isEqualTo(2);
        assertThat(json.get("succeeded").getAsInt()).isEqualTo(0);
        assertThat(json.get("failed").getAsInt()).isEqualTo(0);
        assertThat(json.get("idempotencyKey").getAsString()).isEqualTo("dry-1");

        // Every per-target entry is flagged "preview".
        JsonArray results = json.getAsJsonArray("results");
        assertThat(results.size()).isEqualTo(2);
        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).getAsJsonObject().get("status").getAsString())
                    .isEqualTo("preview");
        }

        // No side effects: nothing was actually muted, nothing was audited.
        assertThat(muteManager.isMuted(t1, "staff")).isFalse();
        assertThat(muteManager.isMuted(t2, "staff")).isFalse();
        assertThat(auditEvents()).isEmpty();
    }

    @Test
    @DisplayName("dryRun=false applies per-target and returns per-target results (HTTP 200)")
    void batchMuteApplyReturnsPerTargetResults() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        String body = batchBody("mute", List.of(t1, t2), "staff", 60000, "spam", "apply-1", false);
        Response resp = dispatch(HttpMethod.POST, "/api/moderation/batch", body);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);

        JsonObject json = resp.asJson();
        assertThat(json.get("dryRun").getAsBoolean()).isFalse();
        assertThat(json.get("total").getAsInt()).isEqualTo(2);
        assertThat(json.get("succeeded").getAsInt()).isEqualTo(2);
        assertThat(json.get("failed").getAsInt()).isEqualTo(0);

        // Both targets were actually muted in the "staff" channel.
        assertThat(muteManager.isMuted(t1, "staff")).isTrue();
        assertThat(muteManager.isMuted(t2, "staff")).isTrue();
    }

    @Test
    @DisplayName("targetIds exceeding BATCH_MAX_TARGETS (100) is rejected with 400")
    void batchBanUpperBoundRejectsExceeding() {
        List<UUID> targets = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            targets.add(UUID.randomUUID());
        }
        String body = batchBody("ban", targets, null, 0, "mass ban", "over-1", false);
        Response resp = dispatch(HttpMethod.POST, "/api/moderation/batch", body);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        // The error surfaces the bound so the caller knows the limit.
        assertThat(resp.body).contains("100");
    }

    @Test
    @DisplayName("idempotency replay returns the cached response verbatim with no side effects")
    void batchIdempotencyReplayReturnsCachedResult() {
        UUID t1 = UUID.randomUUID();
        String body = batchBody("mute", List.of(t1), "staff", 60000, "spam", "replay-1", false);

        Response r1 = dispatch(HttpMethod.POST, "/api/moderation/batch", body);
        assertThat(r1.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r1.asJson().get("succeeded").getAsInt()).isEqualTo(1);
        String body1 = r1.body;

        // Replay with the same idempotencyKey: returns the cached body verbatim.
        Response r2 = dispatch(HttpMethod.POST, "/api/moderation/batch", body);
        assertThat(r2.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r2.body).isEqualTo(body1);
        String refreshed = jwtService.generateToken(UUID.randomUUID().toString(), "root", "SUPER_ADMIN");
        JsonObject equivalent = JsonParser.parseString(body).getAsJsonObject();
        equivalent.addProperty("ignoredMetadata", "retry");
        assertThat(dispatchWithToken(refreshed, HttpMethod.POST, "/api/moderation/batch", equivalent.toString()).body)
                .isEqualTo(body1);

        // No additional side effects: only one audit event (from the first call)
        // — the replay did not re-mute or re-audit.
        assertThat(auditEvents()).hasSize(1);
        assertThat(auditEvents().get(0).getAction()).isEqualTo("player.batch_mute");

        // The single mute is still in place.
        assertThat(muteManager.isMuted(t1, "staff")).isTrue();
    }

    @Test
    void idempotencyKeysBelongToTheAuthenticatedActor() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String key = "shared-by-two-admins";
        assertThat(dispatch(HttpMethod.POST, "/api/moderation/batch",
                batchBody("mute", List.of(first), "staff", 60000, "first", key, false)).status)
                .isEqualTo(HttpResponseStatus.OK);
        String alice = jwtService.generateToken(UUID.randomUUID().toString(), "alice", "ADMIN");
        Response response = dispatchWithToken(alice, HttpMethod.POST, "/api/moderation/batch",
                batchBody("mute", List.of(second), "staff", 60000, "second", key, false));
        assertThat(response.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.body).contains(second.toString()).doesNotContain(first.toString());
        assertThat(muteManager.isMuted(second, "staff")).isTrue();
        assertThat(auditEvents()).extracting(AuditEvent::getActor).containsExactlyInAnyOrder("root", "alice");
    }

    @Test
    void reusingAKeyWithDifferentModerationInputReturnsConflict() {
        UUID target = UUID.randomUUID();
        String body = batchBody("mute", List.of(target), "staff", 60000, "original", "request-bound", false);
        assertThat(dispatch(HttpMethod.POST, "/api/moderation/batch", body).status).isEqualTo(HttpResponseStatus.OK);
        for (String field : List.of("action", "targetIds", "channelId", "durationMs", "reason", "caseId")) {
            JsonObject changed = JsonParser.parseString(body).getAsJsonObject();
            switch (field) {
                case "action" -> changed.addProperty(field, "ban");
                case "targetIds" -> {
                    JsonArray targets = new JsonArray();
                    targets.add(UUID.randomUUID().toString());
                    changed.add(field, targets);
                }
                case "channelId" -> changed.addProperty(field, "survival-chat");
                case "durationMs" -> changed.addProperty(field, 120000);
                case "reason" -> changed.addProperty(field, "changed");
                case "caseId" -> changed.addProperty(field, "another-case");
            }
            assertThat(dispatch(HttpMethod.POST, "/api/moderation/batch", changed.toString()).status)
                    .as("changed %s", field).isEqualTo(HttpResponseStatus.CONFLICT);
        }
        assertThat(auditEvents()).hasSize(1);
        assertThat(muteManager.getMuteInfo(target, "staff").getReason()).isEqualTo("original");
        assertThat(banManager.isBanned(target, "staff")).isFalse();
    }

    @Test
    void previewWithAnAlreadyAppliedKeyStaysAPreview() {
        UUID applied = UUID.randomUUID();
        UUID previewed = UUID.randomUUID();
        String key = "preview-and-apply";
        dispatch(HttpMethod.POST, "/api/moderation/batch",
                batchBody("mute", List.of(applied), "staff", 60000, "applied", key, false));
        Response preview = dispatch(HttpMethod.POST, "/api/moderation/batch",
                batchBody("ban", List.of(previewed), "staff", 60000, "preview", key, true));
        assertThat(preview.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(preview.asJson().get("dryRun").getAsBoolean()).isTrue();
        assertThat(preview.body).contains(previewed.toString()).doesNotContain(applied.toString());
        assertThat(banManager.isBanned(previewed, "staff")).isFalse();
        assertThat(auditEvents()).hasSize(1);
        String fresh = batchBody("ban", List.of(previewed), "staff", 60000, "preview", "preview-first", true);
        assertThat(dispatch(HttpMethod.POST, "/api/moderation/batch", fresh).asJson().get("dryRun").getAsBoolean()).isTrue();
        JsonObject applying = JsonParser.parseString(fresh).getAsJsonObject();
        applying.addProperty("dryRun", false);
        assertThat(dispatch(HttpMethod.POST, "/api/moderation/batch", applying.toString()).status)
                .isEqualTo(HttpResponseStatus.OK);
        assertThat(banManager.isBanned(previewed, "staff")).isTrue();
        assertThat(auditEvents()).hasSize(2);
    }

    @Test
    void concurrentRetryCannotExecuteTheSameBatchTwice() throws Exception {
        var saving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        doAnswer(invocation -> {
            writes.incrementAndGet();
            saving.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("save gate timed out");
            return invocation.callRealMethod();
        }).when(db).saveMute(any(), any());
        UUID target = UUID.randomUUID();
        String body = batchBody("mute", List.of(target), "staff", 60000, "once", "concurrent", false);
        var worker = Executors.newSingleThreadExecutor();
        var retry = new FutureTask<>(() -> dispatch(HttpMethod.POST, "/api/moderation/batch", body));
        Thread retryThread = new Thread(retry, "batch-retry-test");
        try {
            var original = worker.submit(() -> dispatch(HttpMethod.POST, "/api/moderation/batch", body));
            assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
            retryThread.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!retry.isDone() && retryThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertThat(retry.isDone()).as("retry returns without waiting for another moderation mutation").isTrue();
            assertThat(retry.get(5, TimeUnit.SECONDS).status).isEqualTo(HttpResponseStatus.CONFLICT);
            release.countDown();
            Response committed = original.get(5, TimeUnit.SECONDS);
            assertThat(committed.status).isEqualTo(HttpResponseStatus.OK);
            assertThat(dispatch(HttpMethod.POST, "/api/moderation/batch", body).body).isEqualTo(committed.body);
            assertThat(writes.get()).isEqualTo(1);
            assertThat(auditEvents()).hasSize(1);
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            retryThread.join(5000L);
        }
    }

    @Test
    @DisplayName("VIEWER role is rejected with 403 (ADMIN minimum)")
    void batchMuteRequiresAdminRole() {
        String viewerToken = jwtService.generateToken(UUID.randomUUID().toString(),
                "viewer1", "VIEWER");
        String body = batchBody("mute", List.of(UUID.randomUUID()), "staff", 60000,
                "spam", "role-1", false);
        Response resp = dispatchWithToken(viewerToken, HttpMethod.POST,
                "/api/moderation/batch", body);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("apply records one player.batch_<action> audit event per target")
    void batchMuteAuditsEachTarget() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        String key = "audit-1";
        String body = batchBody("mute", List.of(t1, t2), "staff", 60000, "spam", key, false);
        Response resp = dispatch(HttpMethod.POST, "/api/moderation/batch", body);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);

        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(2);

        // One player.batch_mute per target, resource = player:<uuid>.
        assertThat(events).extracting(AuditEvent::getAction)
                .containsExactlyInAnyOrder("player.batch_mute", "player.batch_mute");
        assertThat(events).extracting(AuditEvent::getResource)
                .containsExactlyInAnyOrder("player:" + t1, "player:" + t2);
        // Every event succeeded.
        assertThat(events).extracting(AuditEvent::getResult)
                .containsOnly("success");
        // Every reason carries the batch key so the batch can be reconstructed
        // from the audit trail alone.
        assertThat(events).allSatisfy(e ->
                assertThat(e.getReason()).contains("batch:" + key));
    }
}
