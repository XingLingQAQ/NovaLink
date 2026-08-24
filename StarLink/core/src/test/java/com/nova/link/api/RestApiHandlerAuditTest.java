package com.nova.link.api;

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
import com.nova.link.database.PlayerState;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * PANEL-006 + PANEL-010 integration tests: verifies that every P1 admin
 * mutation flowing through {@link RestApiHandler} produces an immutable
 * {@link AuditEvent} in the append-only audit store, that {@code GET /api/audit}
 * surfaces those events with pagination and filters, and that optimistic
 * concurrency on {@code PUT /api/settings} rejects stale revisions with 409.
 *
 * <p>Unlike {@code RestApiHandlerTest} (which uses the 13-arg legacy constructor
 * with a null audit store), this test wires the full 16-arg constructor with a
 * real {@link AuditStore} backed by a {@link MemoryProvider} so audit records
 * are actually persisted and queryable.
 *
 * <p>Requirements: PANEL-006 audit log, PANEL-010 settings revision
 */
@DisplayName("RestApiHandler audit hooks + settings optimistic concurrency")
class RestApiHandlerAuditTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    private RestApiHandler handler;
    private JwtService jwtService;
    private MemoryProvider db;
    private AuditStore auditStore;
    private ChannelManager channelManager;
    private MuteManager muteManager;
    private BanManager banManager;
    private ConfigManager configManager;
    private ServerNetworkHandler networkHandler;
    private WebhookManager webhookManager;

    private UUID targetId;
    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();
        auditStore = new AuditStore(db);

        // PANEL-006: some tests create webhooks against http://example.com.
        // In this environment example.com resolves to the RFC 2544 benchmark
        // range (198.18.0.0/15), which UrlGuard rejects. The loopback test
        // escape hatch also covers benchmark addresses.
        com.nova.link.security.UrlGuard.setLoopbackAllowedForTest(true);

        channelManager = new ChannelManager();
        PlayerStateManager playerStateManager = new PlayerStateManager(db);
        webhookManager = new WebhookManager();
        PermissionManager permissionManager = new PermissionManager();
        muteManager = new MuteManager(db, permissionManager, channelManager);
        banManager = new BanManager(db, permissionManager, channelManager);
        NotificationStore notificationStore = new NotificationStore(db);
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        SensitiveWordFilter sensitiveWordFilter = new SensitiveWordFilter();
        configManager = new ConfigManager(tempDir.resolve("novalink-audit-test.yml"));
        // load() materialises the default config so getConfig().getFeatures()
        // is non-null and the settings endpoints are usable.
        configManager.load();

        // Seed channels
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

        // Mock network handler with one captured connection
        networkHandler = mock(ServerNetworkHandler.class);
        ClientConnection capturedClient = mock(ClientConnection.class);
        when(capturedClient.getClientId()).thenReturn("Survival");
        when(capturedClient.isAuthenticated()).thenReturn(true);
        when(capturedClient.isActive()).thenReturn(true);
        when(capturedClient.close()).thenReturn(CompletableFuture.completedFuture(null));
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
        // Full 16-arg constructor: real AuditStore wired so records persist.
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

        // Seed an online target player for mute/ban tests
        targetId = UUID.randomUUID();
        PlayerState state = playerStateManager.getOrCreateState(targetId, "Steve");
        state.setClientId("Survival");
        state.setActiveChannel("survival-chat");
        channelManager.addMember("survival-chat", targetId);
    }

    @AfterEach
    void tearDown() {
        muteManager.shutdown();
        banManager.shutdown();
        com.nova.link.security.UrlGuard.setLoopbackAllowedForTest(false);
    }

    // ====================== helpers ======================

    /**
     * Dispatches a request with the super-admin token and captures the response
     * status, body, and headers (so {@code X-Request-Id} can be asserted).
     */
    private Response dispatch(HttpMethod method, String uri, String body) {
        return dispatch(method, uri, body, null);
    }

    /**
     * Dispatch variant that also sets an {@code X-Request-Id} request header so
     * the correlation-through-audit assertion can verify the same id threads to
     * both the response header and the persisted audit event.
     */
    private Response dispatch(HttpMethod method, String uri, String body, String incomingRequestId) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri,
                body != null ? Unpooled.copiedBuffer(body, CharsetUtil.UTF_8) : Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + superAdminToken);
        if (body != null) {
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        }
        if (incomingRequestId != null) {
            request.headers().set("X-Request-Id", incomingRequestId);
        }

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        // PANEL-006: channelRead0 stores the per-request id as a channel
        // attribute, so the mock must return a real channel that supports
        // AttributeMap. An EmbeddedChannel is the lightest such implementation.
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return promise;
        }).when(ctx).writeAndFlush(any());

        try {
            handler.channelRead0(ctx, request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Object resp = captured.get();
        if (resp instanceof FullHttpResponse response) {
            return new Response(response.status(),
                    response.content().toString(StandardCharsets.UTF_8),
                    response.headers());
        }
        return new Response(null, "", null);
    }

    private static class Response {
        final HttpResponseStatus status;
        final String body;
        final HttpHeaders headers;
        Response(HttpResponseStatus status, String body, HttpHeaders headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
        JsonObject asJson() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    /** All audit events currently persisted, newest-first. */
    private List<AuditEvent> auditEvents() {
        return auditStore.list(0, 100, null, null);
    }

    // ====================== P1 mutation audit hooks ======================

    @Test
    @DisplayName("POST /api/channels records channel.create audit with after-hash")
    void channelCreateRecordsAudit() {
        assertThat(auditEvents()).isEmpty();

        Response resp = dispatch(HttpMethod.POST, "/api/channels",
                "{\"displayName\":\"Announcements\",\"scope\":\"global\",\"maxCapacity\":50}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.CREATED);

        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(1);
        AuditEvent e = events.get(0);
        assertThat(e.getAction()).isEqualTo("channel.create");
        assertThat(e.getResource()).startsWith("channel:");
        assertThat(e.getResult()).isEqualTo("success");
        assertThat(e.getActor()).isEqualTo("root");
        assertThat(e.getRole()).isEqualTo("SUPER_ADMIN");
        // afterHash is the SHA-256 of the channel JSON (no secrets).
        assertThat(e.getAfterHash()).isNotNull().hasSize(64);
        // No before-state for a create.
        assertThat(e.getBeforeHash()).isNull();
    }

    @Test
    @DisplayName("DELETE /api/channels/{id} records channel.delete audit with before-hash")
    void channelDeleteRecordsAudit() {
        // Seed a channel to delete. The REST id is auto-generated as "ch-XXXX"
        // (not the lower-cased display name), so extract the actual id from the
        // create response.
        Response create = dispatch(HttpMethod.POST, "/api/channels",
                "{\"displayName\":\"Temp\",\"scope\":\"global\",\"maxCapacity\":10}");
        assertThat(create.status).isEqualTo(HttpResponseStatus.CREATED);
        String channelId = create.asJson().getAsJsonObject("channel").get("id").getAsString();
        // The create itself produced an audit record; clear the assertion baseline
        // by counting from here.
        int baseline = auditEvents().size();

        Response resp = dispatch(HttpMethod.DELETE, "/api/channels/" + channelId, null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);

        List<AuditEvent> events = auditEvents();
        // One new record for the delete.
        assertThat(events).hasSize(baseline + 1);
        AuditEvent e = events.get(0); // newest first
        assertThat(e.getAction()).isEqualTo("channel.delete");
        assertThat(e.getResource()).startsWith("channel:");
        assertThat(e.getResult()).isEqualTo("success");
        // beforeHash captured from the channel JSON before deletion.
        assertThat(e.getBeforeHash()).isNotNull().hasSize(64);
        // No after-state for a delete.
        assertThat(e.getAfterHash()).isNull();
    }

    @Test
    @DisplayName("POST /api/players/{uuid}/mute records player.mute audit")
    void mutePlayerRecordsAudit() {
        assertThat(auditEvents()).isEmpty();

        Response resp = dispatch(HttpMethod.POST, "/api/players/" + targetId + "/mute",
                "{\"channelId\":\"staff\",\"durationMs\":60000,\"reason\":\"spam\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);

        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(1);
        AuditEvent e = events.get(0);
        assertThat(e.getAction()).isEqualTo("player.mute");
        assertThat(e.getResource()).isEqualTo("player:" + targetId);
        assertThat(e.getResult()).isEqualTo("success");
        assertThat(e.getReason()).isEqualTo("spam");
    }

    @Test
    @DisplayName("POST /api/players/{uuid}/ban records player.ban audit")
    void banPlayerRecordsAudit() {
        assertThat(auditEvents()).isEmpty();

        Response resp = dispatch(HttpMethod.POST, "/api/players/" + targetId + "/ban",
                "{\"durationMs\":0,\"reason\":\"cheating\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);

        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(1);
        AuditEvent e = events.get(0);
        assertThat(e.getAction()).isEqualTo("player.ban");
        assertThat(e.getResource()).isEqualTo("player:" + targetId);
        assertThat(e.getResult()).isEqualTo("success");
        assertThat(e.getReason()).isEqualTo("cheating");
    }

    @Test
    @DisplayName("POST /api/webhooks records webhook.create audit with secret-safe after-hash")
    void webhookCreateRecordsAudit() {
        assertThat(auditEvents()).isEmpty();

        Response resp = dispatch(HttpMethod.POST, "/api/webhooks",
                "{\"url\":\"http://example.com\",\"event\":\"message.sent\",\"secret\":\"topsecret\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.CREATED);

        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(1);
        AuditEvent e = events.get(0);
        assertThat(e.getAction()).isEqualTo("webhook.create");
        assertThat(e.getResource()).startsWith("webhook:");
        assertThat(e.getResult()).isEqualTo("success");
        // afterHash is the SHA-256 of the webhook JSON with the secret stripped
        // (webhookToJson never emits the secret). The hash must NOT contain
        // the literal secret.
        assertThat(e.getAfterHash()).isNotNull().hasSize(64);
        assertThat(e.getAfterHash()).doesNotContain("topsecret");
    }

    @Test
    @DisplayName("DELETE /api/webhooks/{id} records webhook.delete audit with before-hash")
    void webhookDeleteRecordsAudit() {
        // Seed a webhook to delete.
        Response create = dispatch(HttpMethod.POST, "/api/webhooks",
                "{\"url\":\"http://example.com\",\"event\":\"message.sent\"}");
        assertThat(create.status).isEqualTo(HttpResponseStatus.CREATED);
        String webhookId = create.asJson().getAsJsonObject("webhook").get("id").getAsString();
        int baseline = auditEvents().size();

        Response resp = dispatch(HttpMethod.DELETE, "/api/webhooks/" + webhookId, null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);

        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(baseline + 1);
        AuditEvent e = events.get(0); // newest first
        assertThat(e.getAction()).isEqualTo("webhook.delete");
        assertThat(e.getResource()).isEqualTo("webhook:" + webhookId);
        assertThat(e.getResult()).isEqualTo("success");
        assertThat(e.getBeforeHash()).isNotNull().hasSize(64);
        assertThat(e.getAfterHash()).isNull();
    }

    @Test
    @DisplayName("POST /api/reload records config.reload audit")
    void reloadRecordsAudit() {
        assertThat(auditEvents()).isEmpty();

        Response resp = dispatch(HttpMethod.POST, "/api/reload", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);

        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(1);
        AuditEvent e = events.get(0);
        assertThat(e.getAction()).isEqualTo("config.reload");
        assertThat(e.getResource()).isEqualTo("config");
        assertThat(e.getResult()).isEqualTo("success");
        // Reload includes the settings revision in the response body.
        assertThat(resp.asJson().has("settingsRevision")).isTrue();
    }

    @Test
    @DisplayName("PUT /api/settings records settings.update audit with after-hash")
    void settingsUpdateRecordsAudit() {
        assertThat(auditEvents()).isEmpty();

        // Fetch the current revision so the update is not rejected as stale.
        Response get = dispatch(HttpMethod.GET, "/api/settings", null);
        assertThat(get.status).isEqualTo(HttpResponseStatus.OK);
        long revision = get.asJson().get("revision").getAsLong();

        Response resp = dispatch(HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":" + revision + "}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);

        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(1);
        AuditEvent e = events.get(0);
        assertThat(e.getAction()).isEqualTo("settings.update");
        assertThat(e.getResource()).isEqualTo("config:features");
        assertThat(e.getResult()).isEqualTo("success");
        // afterHash is the SHA-256 of the settings JSON (no secrets present).
        assertThat(e.getAfterHash()).isNotNull().hasSize(64);
        assertThat(e.getBeforeHash()).isNull();
    }

    // ====================== GET /api/audit ======================

    @Test
    @DisplayName("GET /api/audit returns paginated, filterable results")
    void getAuditReturnsPaginatedAndFilterable() {
        // Seed three events with distinct actions and actors. Use a separate
        // admin token for "alice" so the actor column differs from "root".
        String aliceToken = jwtService.generateToken(UUID.randomUUID().toString(),
                "alice", "SUPER_ADMIN");
        // Create a channel (alice) → channel.create
        FullHttpRequest r1 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.POST, "/api/channels",
                Unpooled.copiedBuffer(
                        "{\"displayName\":\"A1\",\"scope\":\"global\",\"maxCapacity\":10}",
                        CharsetUtil.UTF_8));
        r1.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + aliceToken);
        r1.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        dispatchRaw(r1);

        // Mute a player (root) → player.mute
        dispatch(HttpMethod.POST, "/api/players/" + targetId + "/mute",
                "{\"channelId\":\"staff\",\"durationMs\":1000,\"reason\":\"t\"}");

        // Reload (root) → config.reload
        dispatch(HttpMethod.POST, "/api/reload", null);

        // Total is 3.
        Response all = dispatch(HttpMethod.GET, "/api/audit?page=1&size=10", null);
        assertThat(all.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject allJson = all.asJson();
        assertThat(allJson.get("total").getAsInt()).isEqualTo(3);
        assertThat(allJson.getAsJsonArray("items").size()).isEqualTo(3);
        assertThat(allJson.get("page").getAsInt()).isEqualTo(1);
        assertThat(allJson.get("pageSize").getAsInt()).isEqualTo(10);
        // Newest first: the reload (last) should be at index 0.
        assertThat(allJson.getAsJsonArray("items").get(0).getAsJsonObject()
                .get("action").getAsString()).isEqualTo("config.reload");

        // Filter by action=channel.create → 1 result (alice's channel).
        Response filtered = dispatch(HttpMethod.GET,
                "/api/audit?page=1&size=10&action=channel.create", null);
        assertThat(filtered.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject fJson = filtered.asJson();
        assertThat(fJson.get("total").getAsInt()).isEqualTo(1);
        assertThat(fJson.getAsJsonArray("items").size()).isEqualTo(1);
        assertThat(fJson.getAsJsonArray("items").get(0).getAsJsonObject()
                .get("action").getAsString()).isEqualTo("channel.create");

        // Filter by actor=alice → 1 result (the channel create).
        Response byActor = dispatch(HttpMethod.GET,
                "/api/audit?page=1&size=10&actor=alice", null);
        assertThat(byActor.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(byActor.asJson().get("total").getAsInt()).isEqualTo(1);
        assertThat(byActor.asJson().getAsJsonArray("items").get(0).getAsJsonObject()
                .get("actor").getAsString()).isEqualTo("alice");

        // Pagination: page 1 with size 1 returns 1 item, total 3.
        Response page1 = dispatch(HttpMethod.GET, "/api/audit?page=1&size=1", null);
        assertThat(page1.asJson().getAsJsonArray("items").size()).isEqualTo(1);
        assertThat(page1.asJson().get("total").getAsInt()).isEqualTo(3);
        assertThat(page1.asJson().get("page").getAsInt()).isEqualTo(1);
        assertThat(page1.asJson().get("pageSize").getAsInt()).isEqualTo(1);

        // Page 2 with size 1 returns the second-newest item.
        Response page2 = dispatch(HttpMethod.GET, "/api/audit?page=2&size=1", null);
        assertThat(page2.asJson().getAsJsonArray("items").size()).isEqualTo(1);
        assertThat(page2.asJson().get("page").getAsInt()).isEqualTo(2);
        assertThat(page2.asJson().getAsJsonArray("items").get(0).getAsJsonObject()
                .get("action").getAsString()).isEqualTo("player.mute");
    }

    /** Dispatches a pre-built request with the super-admin token already set. */
    private void dispatchRaw(FullHttpRequest request) {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return promise;
        }).when(ctx).writeAndFlush(any());
        try {
            handler.channelRead0(ctx, request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ====================== requestId correlation ======================

    @Test
    @DisplayName("X-Request-Id header threads through response and audit event")
    void requestIdThreadsThroughResponseAndAudit() {
        assertThat(auditEvents()).isEmpty();
        String incomingId = "trace-" + UUID.randomUUID();

        Response resp = dispatch(HttpMethod.POST, "/api/channels",
                "{\"displayName\":\"Traced\",\"scope\":\"global\",\"maxCapacity\":5}",
                incomingId);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.CREATED);

        // The response carries the same X-Request-Id.
        assertThat(resp.headers.get("X-Request-Id")).isEqualTo(incomingId);

        // The audit event carries the same requestId.
        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getRequestId()).isEqualTo(incomingId);
    }

    @Test
    @DisplayName("response without incoming X-Request-Id still gets a generated one stamped")
    void requestIdGeneratedWhenNotSupplied() {
        Response resp = dispatch(HttpMethod.GET, "/api/settings", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        // A UUID is generated and stamped on the response.
        String stamped = resp.headers.get("X-Request-Id");
        assertThat(stamped).isNotNull().isNotBlank();
        // It should look like a UUID.
        assertThat(stamped).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    // ====================== PANEL-010: settings optimistic concurrency ======================

    @Test
    @DisplayName("PUT /api/settings with stale baseRevision returns 409 with current values")
    void settingsUpdateStaleRevisionReturns409() {
        // Fetch the current revision.
        Response get = dispatch(HttpMethod.GET, "/api/settings", null);
        assertThat(get.status).isEqualTo(HttpResponseStatus.OK);
        long currentRevision = get.asJson().get("revision").getAsLong();
        // Capture the runtime value before the stale update — a rejected
        // mutation must not change it, regardless of the default (which is
        // false for a Java boolean primitive).
        boolean filterBefore = configManager.getConfig().getFeatures().isFilterEnabled();

        // Attempt an update with a deliberately stale revision.
        long staleRevision = currentRevision - 1;
        Response resp = dispatch(HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":" + staleRevision + "}");

        assertThat(resp.status).isEqualTo(HttpResponseStatus.CONFLICT);
        JsonObject json = resp.asJson();
        assertThat(json.get("error").getAsString()).contains("revision mismatch");
        assertThat(json.get("status").getAsInt()).isEqualTo(409);
        // The conflict body carries both revisions and the current values so
        // the caller can re-merge.
        assertThat(json.get("currentRevision").getAsLong()).isEqualTo(currentRevision);
        assertThat(json.get("clientRevision").getAsLong()).isEqualTo(staleRevision);
        assertThat(json.get("revision").getAsLong()).isEqualTo(currentRevision);
        assertThat(json.has("filterEnabled")).isTrue();

        // No audit event is recorded for a rejected (stale) update — the
        // mutation did not happen, so there is nothing to audit.
        List<AuditEvent> events = auditEvents();
        assertThat(events).isEmpty();
        // ... and the runtime value was NOT changed.
        assertThat(configManager.getConfig().getFeatures().isFilterEnabled()).isEqualTo(filterBefore);
    }

    @Test
    @DisplayName("PUT /api/settings with fresh baseRevision succeeds and increments revision")
    void settingsUpdateFreshRevisionSucceedsAndIncrements() {
        Response get = dispatch(HttpMethod.GET, "/api/settings", null);
        long revisionBefore = get.asJson().get("revision").getAsLong();

        Response resp = dispatch(HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":" + revisionBefore + "}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("success").getAsBoolean()).isTrue();
        assertThat(json.get("filterEnabled").getAsBoolean()).isFalse();

        // The response carries the new (incremented) revision.
        long revisionAfter = json.get("revision").getAsLong();
        assertThat(revisionAfter).isGreaterThan(revisionBefore);

        // A subsequent GET reflects the incremented revision.
        Response get2 = dispatch(HttpMethod.GET, "/api/settings", null);
        assertThat(get2.asJson().get("revision").getAsLong()).isEqualTo(revisionAfter);
        assertThat(get2.asJson().get("filterEnabled").getAsBoolean()).isFalse();

        // The settings update was audited.
        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo("settings.update");
        assertThat(events.get(0).getResult()).isEqualTo("success");
    }

    @Test
    @DisplayName("PUT /api/settings without baseRevision succeeds (backward compat)")
    void settingsUpdateWithoutBaseRevisionSucceeds() {
        // No baseRevision in the body → backward-compat allow.
        Response resp = dispatch(HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().get("success").getAsBoolean()).isTrue();
        assertThat(resp.asJson().get("filterEnabled").getAsBoolean()).isFalse();

        // The update was applied and audited.
        assertThat(configManager.getConfig().getFeatures().isFilterEnabled()).isFalse();
        List<AuditEvent> events = auditEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo("settings.update");
    }

    @Test
    @DisplayName("PUT /api/settings with If-Match header rejects stale revision (HTTP-native)")
    void settingsUpdateIfMatchHeaderRejectsStale() {
        Response get = dispatch(HttpMethod.GET, "/api/settings", null);
        long currentRevision = get.asJson().get("revision").getAsLong();

        // Build a request with If-Match: W/"<stale-revision>".
        long staleRevision = currentRevision - 1;
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.PUT, "/api/settings",
                Unpooled.copiedBuffer("{\"filterEnabled\":false}", CharsetUtil.UTF_8));
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + superAdminToken);
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        request.headers().set(HttpHeaderNames.IF_MATCH, "W/\"" + staleRevision + "\"");

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return promise;
        }).when(ctx).writeAndFlush(any());
        try {
            handler.channelRead0(ctx, request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        FullHttpResponse response = (FullHttpResponse) captured.get();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.CONFLICT);
        JsonObject json = JsonParser.parseString(
                response.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(json.get("status").getAsInt()).isEqualTo(409);
        assertThat(json.get("currentRevision").getAsLong()).isEqualTo(currentRevision);
        assertThat(json.get("clientRevision").getAsLong()).isEqualTo(staleRevision);
    }

    @Test
    @DisplayName("GET /api/settings includes revision field")
    void getSettingsIncludesRevision() {
        Response resp = dispatch(HttpMethod.GET, "/api/settings", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().has("revision")).isTrue();
        assertThat(resp.asJson().get("revision").getAsLong())
                .isEqualTo(configManager.getSettingsRevision());
    }

    @Test
    @DisplayName("reload bumps settings revision so concurrent panel edits conflict")
    void reloadBumpsSettingsRevision() {
        Response getBefore = dispatch(HttpMethod.GET, "/api/settings", null);
        long revBefore = getBefore.asJson().get("revision").getAsLong();

        Response reload = dispatch(HttpMethod.POST, "/api/reload", null);
        assertThat(reload.status).isEqualTo(HttpResponseStatus.OK);
        // The reload response surfaces the new revision.
        long revAfter = reload.asJson().get("settingsRevision").getAsLong();
        assertThat(revAfter).isGreaterThan(revBefore);

        // A subsequent GET confirms the revision was bumped.
        Response getAfter = dispatch(HttpMethod.GET, "/api/settings", null);
        assertThat(getAfter.asJson().get("revision").getAsLong()).isEqualTo(revAfter);

        // An update based on the pre-reload revision must now 409.
        Response stale = dispatch(HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":" + revBefore + "}");
        assertThat(stale.status).isEqualTo(HttpResponseStatus.CONFLICT);
    }
}
