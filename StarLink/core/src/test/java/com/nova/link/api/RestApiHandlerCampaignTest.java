package com.nova.link.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.announcement.Campaign;
import com.nova.link.announcement.CampaignManager;
import com.nova.link.announcement.CampaignStatus;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.config.ConfigManager;
import com.nova.link.console.BackendContext;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.database.DatabaseProvider;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * HTTP-level tests for the campaign REST endpoints (§11.6 提案 06 — slice A).
 *
 * <p>Exercises the create → schedule → revoke flow end-to-end through the real
 * {@link RestApiHandler} dispatch path (no HTTP server). Mirrors the scaffold
 * of {@link RestApiHandlerTest}: real JwtService + MemoryProvider + real
 * managers, mocked {@link ServerNetworkHandler}, captured writeAndFlush.
 *
 * <p>Coverage:
 * <ul>
 *   <li>POST /api/campaigns (201, campaign in PREVIEW)</li>
 *   <li>POST /api/campaigns/{id}/schedule (200, transitions to ACTIVE because
 *       startAt=0 takes the immediate-activation path)</li>
 *   <li>POST /api/campaigns/{id}/revoke (200, SUPER_ADMIN-only, transitions to REVOKED)</li>
 *   <li>GET /api/campaigns/{id} (200, returns the campaign JSON)</li>
 *   <li>GET /api/campaigns (200, returns the list)</li>
 *   <li>POST /api/campaigns/{id}/revoke without SUPER_ADMIN token (403)</li>
 * </ul>
 */
@DisplayName("RestApiHandler — campaign endpoints (create/schedule/revoke)")
class RestApiHandlerCampaignTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    private RestApiHandler handler;
    private JwtService jwtService;
    private ChannelManager channelManager;
    private MuteManager muteManager;
    private BanManager banManager;
    private CampaignManager campaignManager;
    private DatabaseProvider db;

    private String validToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();

        channelManager = new ChannelManager();
        PlayerStateManager playerStateManager = new PlayerStateManager(db);
        WebhookManager webhookManager = new WebhookManager();
        PermissionManager permissionManager = new PermissionManager();
        muteManager = new MuteManager(db, permissionManager, channelManager);
        banManager = new BanManager(db, permissionManager, channelManager);
        NotificationStore notificationStore = new NotificationStore(db);
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        SensitiveWordFilter sensitiveWordFilter = new SensitiveWordFilter();
        ConfigManager configManager = new ConfigManager(java.nio.file.Path.of("novalink-test.yml"));

        // Seed channels — one GLOBAL (staff), one SERVER (survival-chat).
        channelManager.createChannel(ChannelConfig.builder()
                .id("staff")
                .displayName("Staff")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("survival-chat")
                .displayName("Survival")
                .scope(ChannelScope.SERVER)
                .clientId("Survival")
                .build());

        // Mock network handler with one captured connection (unused by campaign
        // endpoints, required by the MessageRouter wiring).
        ServerNetworkHandler networkHandler = mock(ServerNetworkHandler.class);
        ClientConnection capturedClient = mock(ClientConnection.class);
        when(capturedClient.getClientId()).thenReturn("Survival");
        when(capturedClient.close()).thenReturn(CompletableFuture.completedFuture(null));
        when(networkHandler.findByClientId("Survival")).thenReturn(capturedClient);

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
                notificationStore
        );

        // Wire the CampaignManager (slice A: not wired in the constructor).
        // Backed by the same in-memory DB + a fresh canonical AuditStore (the
        // manager records its own audit internally, mirroring ModerationManager).
        campaignManager = new CampaignManager(permissionManager, channelManager);
        campaignManager.setAuditStore(new com.nova.link.audit.AuditStore(db));
        campaignManager.initialize();
        handler.setCampaignManager(campaignManager);

        // SUPER_ADMIN token (for create/schedule/revoke).
        validToken = jwtService.generateToken(UUID.randomUUID().toString(), "admin", "SUPER_ADMIN");
        // Plain ADMIN token (for the 403-revoke denial test).
        adminToken = jwtService.generateToken(UUID.randomUUID().toString(), "mod", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        campaignManager.shutdown();
        muteManager.shutdown();
        banManager.shutdown();
    }

    // ====================== helper: dispatch ======================

    private Response dispatch(HttpMethod method, String uri, String body) {
        return dispatch(handler, method, uri, body, validToken);
    }

    private Response dispatch(RestApiHandler target, HttpMethod method, String uri,
                              String body, String token) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri,
                body != null ? Unpooled.copiedBuffer(body, CharsetUtil.UTF_8) : Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        }

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        // PANEL-006: channelRead0 stores the per-request id as a channel
        // attribute via ctx.channel().attr(...).set(...). A plain mock returns
        // null from ctx.channel(), which NPEs at that line. An EmbeddedChannel
        // is the lightest AttributeMap implementation (same approach as
        // RestApiHandlerTest).
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return promise;
        }).when(ctx).writeAndFlush(any());

        try {
            target.channelRead0(ctx, request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Object resp = captured.get();
        if (resp instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) resp;
            String responseBody = response.content().toString(StandardCharsets.UTF_8);
            return new Response(response.status(), responseBody);
        }
        return new Response(null, "");
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

    // ====================== tests ======================

    @Test
    @DisplayName("POST /api/campaigns creates a PREVIEW campaign (201)")
    void createCampaign() {
        Response resp = dispatch(HttpMethod.POST, "/api/campaigns",
                "{\"channelId\":\"staff\",\"content\":\"Hello campaign\","
                        + "\"platforms\":[\"survival\"],\"deliveryPolicy\":\"INSTANT\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.CREATED);
        JsonObject json = resp.asJson();
        assertThat(json.get("id").getAsString()).startsWith(Campaign.ID_PREFIX);
        assertThat(json.get("channelId").getAsString()).isEqualTo("staff");
        assertThat(json.get("content").getAsString()).isEqualTo("Hello campaign");
        assertThat(json.get("status").getAsString()).isEqualTo("PREVIEW");
        assertThat(json.get("deliveryPolicy").getAsString()).isEqualTo("INSTANT");
        assertThat(json.get("scheduleRevision").getAsLong()).isZero();
        // Manager state reflects the creation.
        assertThat(campaignManager.getCampaignCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/campaigns rejects invalid body (400)")
    void createCampaignInvalidBody() {
        // Missing channelId.
        Response resp = dispatch(HttpMethod.POST, "/api/campaigns",
                "{\"content\":\"x\",\"platforms\":[\"survival\"]}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);

        // Empty platforms.
        Response resp2 = dispatch(HttpMethod.POST, "/api/campaigns",
                "{\"channelId\":\"staff\",\"content\":\"x\",\"platforms\":[]}");
        assertThat(resp2.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);

        // Unknown channel.
        Response resp3 = dispatch(HttpMethod.POST, "/api/campaigns",
                "{\"channelId\":\"no-such\",\"content\":\"x\",\"platforms\":[\"survival\"]}");
        assertThat(resp3.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /api/campaigns/{id} returns the campaign (200)")
    void getCampaign() {
        String id = createCampaignId();
        Response resp = dispatch(HttpMethod.GET, "/api/campaigns/" + id, null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().get("id").getAsString()).isEqualTo(id);
    }

    @Test
    @DisplayName("GET /api/campaigns/{id} returns 404 for unknown campaign")
    void getCampaignNotFound() {
        Response resp = dispatch(HttpMethod.GET, "/api/campaigns/CMP-deadbeef", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /api/campaigns lists campaigns (200)")
    void listCampaigns() {
        createCampaignId();
        createCampaignId();
        Response resp = dispatch(HttpMethod.GET, "/api/campaigns", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("total").getAsInt()).isEqualTo(2);
        assertThat(json.getAsJsonArray("items").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("GET /api/campaigns?channelId=... filters by channel (200)")
    void listCampaignsFilteredByChannel() {
        // Create one campaign in "staff", one in "survival-chat".
        createCampaignIdFor("staff");
        createCampaignIdFor("survival-chat");
        Response resp = dispatch(HttpMethod.GET, "/api/campaigns?channelId=staff", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("total").getAsInt()).isEqualTo(1);
        assertThat(json.getAsJsonArray("items").get(0).getAsJsonObject()
                .get("channelId").getAsString()).isEqualTo("staff");
    }

    @Test
    @DisplayName("POST /api/campaigns/{id}/schedule transitions PREVIEW → ACTIVE (200, startAt=0)")
    void scheduleCampaignImmediateActivation() {
        String id = createCampaignId();
        Response resp = dispatch(HttpMethod.POST, "/api/campaigns/" + id + "/schedule", "{}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("status").getAsString()).isEqualTo("ACTIVE");
        assertThat(json.get("scheduleRevision").getAsLong()).isGreaterThan(0L);
        // Manager state reflects the transition.
        assertThat(campaignManager.getCampaign(id).getStatus()).isEqualTo(CampaignStatus.ACTIVE);
    }

    @Test
    @DisplayName("POST /api/campaigns/{id}/schedule on unknown campaign returns 404")
    void scheduleUnknownCampaign() {
        Response resp = dispatch(HttpMethod.POST, "/api/campaigns/CMP-deadbeef/schedule", "{}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/campaigns/{id}/activate transitions SCHEDULED → ACTIVE (200)")
    void activateCampaign() {
        // Create with a future startAt, schedule (→ SCHEDULED, arms task),
        // then activate manually.
        long futureStart = System.currentTimeMillis() + 3_600_000L;
        Response createResp = dispatch(HttpMethod.POST, "/api/campaigns",
                "{\"channelId\":\"staff\",\"content\":\"future\","
                        + "\"platforms\":[\"survival\"],\"startAt\":" + futureStart + "}");
        assertThat(createResp.status).isEqualTo(HttpResponseStatus.CREATED);
        String id = createResp.asJson().get("id").getAsString();

        Response scheduleResp = dispatch(HttpMethod.POST, "/api/campaigns/" + id + "/schedule", "{}");
        assertThat(scheduleResp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(scheduleResp.asJson().get("status").getAsString()).isEqualTo("SCHEDULED");

        Response activateResp = dispatch(HttpMethod.POST, "/api/campaigns/" + id + "/activate", "{}");
        assertThat(activateResp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(activateResp.asJson().get("status").getAsString()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("POST /api/campaigns/{id}/revoke transitions to REVOKED (200, SUPER_ADMIN)")
    void revokeCampaign() {
        String id = createCampaignId();
        Response resp = dispatch(HttpMethod.POST, "/api/campaigns/" + id + "/revoke", "{}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("status").getAsString()).isEqualTo("REVOKED");
        assertThat(json.get("revokedAt").getAsLong()).isGreaterThan(0L);
        assertThat(json.get("revokedBy").getAsString()).isNotNull();
        // Manager state reflects the revoke + scheduled task cancellation.
        assertThat(campaignManager.getCampaign(id).getStatus()).isEqualTo(CampaignStatus.REVOKED);
        assertThat(campaignManager.getScheduledTaskCount()).isZero();
    }

    @Test
    @DisplayName("POST /api/campaigns/{id}/revoke with ADMIN token returns 403")
    void revokeCampaignRequiresSuperAdmin() {
        String id = createCampaignId();
        Response resp = dispatch(handler, HttpMethod.POST, "/api/campaigns/" + id + "/revoke",
                "{}", adminToken);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.FORBIDDEN);
        // Campaign is still PREVIEW (revoke was denied at the RBAC gate).
        assertThat(campaignManager.getCampaign(id).getStatus()).isEqualTo(CampaignStatus.PREVIEW);
    }

    @Test
    @DisplayName("POST /api/campaigns/{id}/revoke on unknown campaign returns 404")
    void revokeUnknownCampaign() {
        Response resp = dispatch(HttpMethod.POST, "/api/campaigns/CMP-deadbeef/revoke", "{}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Full create → schedule → revoke flow")
    void fullCreateScheduleRevokeFlow() {
        // 1. Create.
        Response createResp = dispatch(HttpMethod.POST, "/api/campaigns",
                "{\"channelId\":\"staff\",\"content\":\"flow\","
                        + "\"platforms\":[\"survival\",\"creative\"]}");
        assertThat(createResp.status).isEqualTo(HttpResponseStatus.CREATED);
        String id = createResp.asJson().get("id").getAsString();

        // 2. Schedule (startAt=0 → ACTIVE).
        Response scheduleResp = dispatch(HttpMethod.POST, "/api/campaigns/" + id + "/schedule", "{}");
        assertThat(scheduleResp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(scheduleResp.asJson().get("status").getAsString()).isEqualTo("ACTIVE");

        // 3. Revoke (SUPER_ADMIN).
        Response revokeResp = dispatch(HttpMethod.POST, "/api/campaigns/" + id + "/revoke", "{}");
        assertThat(revokeResp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(revokeResp.asJson().get("status").getAsString()).isEqualTo("REVOKED");

        // 4. Further schedule/activate on REVOKED is rejected (400).
        Response scheduleAgain = dispatch(HttpMethod.POST, "/api/campaigns/" + id + "/schedule", "{}");
        assertThat(scheduleAgain.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Campaign endpoints 503 when CampaignManager is not wired")
    void campaignEndpoints503WhenNotWired() {
        // Build a handler without calling setCampaignManager. Uses the same
        // 13-param constructor shape as the main setUp().
        MessageRouter bareRouter = new MessageRouter(channelManager, mock(ServerNetworkHandler.class));
        RestApiHandler bareHandler = new RestApiHandler(
                jwtService,
                new AuthManager(new IpBanManager(5, 60000)),
                channelManager,
                new PlayerStateManager(db),
                bareRouter,
                new WebhookManager(),
                muteManager,
                banManager,
                new InvitationManager(db, channelManager),
                new ConfigManager(java.nio.file.Path.of("novalink-test.yml")),
                mock(ServerNetworkHandler.class),
                mock(ConsoleCommandHandler.class),
                new NotificationStore(db)
        );
        Response resp = dispatch(bareHandler, HttpMethod.POST, "/api/campaigns",
                "{\"channelId\":\"staff\",\"content\":\"x\",\"platforms\":[\"survival\"]}",
                validToken);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
    }

    // ====================== helpers ======================

    /** Creates a campaign via the REST endpoint and returns its ID. */
    private String createCampaignId() {
        return createCampaignIdFor("staff");
    }

    private String createCampaignIdFor(String channelId) {
        Response resp = dispatch(HttpMethod.POST, "/api/campaigns",
                "{\"channelId\":\"" + channelId + "\",\"content\":\"c-" + channelId + "\","
                        + "\"platforms\":[\"survival\"]}");
        assertThat(resp.status)
                .as("createCampaign helper should succeed: %s", resp.body)
                .isEqualTo(HttpResponseStatus.CREATED);
        return resp.asJson().get("id").getAsString();
    }
}
