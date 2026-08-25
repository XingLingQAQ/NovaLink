package com.nova.link.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.config.ConfigManager;
import com.nova.link.console.BackendContext;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.Notification;
import com.nova.link.database.Invitation;
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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the REST API handler's admin endpoints.
 *
 * <p>Builds a real {@link RestApiHandler} backed by real managers (MemoryProvider
 * DB) so each endpoint is exercised end-to-end without a live server. The
 * {@link ServerNetworkHandler} is mocked (with a captured connection) so client
 * disconnect can be asserted. JWT tokens are generated with a real
 * {@link JwtService} so the auth path is exercised.
 */
@DisplayName("RestApiHandler admin endpoints")
class RestApiHandlerTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    private RestApiHandler handler;
    private JwtService jwtService;
    private ChannelManager channelManager;
    private PlayerStateManager playerStateManager;
    private MuteManager muteManager;
    private BanManager banManager;
    private NotificationStore notificationStore;
    private InvitationManager invitationManager;
    private ConfigManager configManager;
    private ServerNetworkHandler networkHandler;
    private ConsoleCommandHandler consoleCommandHandler;
    private DatabaseProvider db;
    private MessageRouter messageRouter;
    private SensitiveWordFilter sensitiveWordFilter;

    private UUID targetId;
    private ClientConnection capturedClient;
    private String validToken;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();

        channelManager = new ChannelManager();
        playerStateManager = new PlayerStateManager(db);
        WebhookManager webhookManager = new WebhookManager();
        PermissionManager permissionManager = new PermissionManager();
        muteManager = new MuteManager(db, permissionManager, channelManager);
        banManager = new BanManager(db, permissionManager, channelManager);
        notificationStore = new NotificationStore(db);
        invitationManager = new InvitationManager(db, channelManager);
        sensitiveWordFilter = new SensitiveWordFilter();
        configManager = new ConfigManager(tempDir.resolve("novalink-test.yml"));

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
        capturedClient = mock(ClientConnection.class);
        when(capturedClient.getClientId()).thenReturn("Survival");
        when(capturedClient.close()).thenReturn(CompletableFuture.completedFuture(null));
        when(networkHandler.findByClientId("Survival")).thenReturn(capturedClient);
        when(networkHandler.findByClientId("nonexistent")).thenReturn(null);

        messageRouter = new MessageRouter(channelManager, networkHandler);
        messageRouter.setMuteManager(muteManager);
        messageRouter.setSensitiveWordFilter(sensitiveWordFilter);
        messageRouter.setPermissionChecker((c, p) -> true);

        SpyManager spyManager = new SpyManager(permissionManager, channelManager, networkHandler);
        messageRouter.setSpyManager(spyManager);

        // Build BackendContext for ConsoleCommandHandler (tcpServer/ws gateway
        // unused by dispatch; pass mocks).
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
                sensitiveWordsFilter(),
                networkHandler,
                messageRouter,
                spyManager,
                mock(NettyServer.class),
                mock(WebSocketGateway.class)
        );
        consoleCommandHandler = new ConsoleCommandHandler(ctx);

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

        // PANEL-007: wire the moderation manager so the case/appeal endpoints
        // can dispatch to it. Backed by the same in-memory DB + a fresh audit
        // store (the manager records its own audit internally).
        handler.setModerationManager(
                new com.nova.link.moderation.ModerationManager(
                        db, new com.nova.link.audit.AuditStore(db)));

        // Generate a valid JWT token for auth
        validToken = jwtService.generateToken(UUID.randomUUID().toString(), "admin", "SUPER_ADMIN");

        // Seed an online target player
        targetId = UUID.randomUUID();
        PlayerState state = playerStateManager.getOrCreateState(targetId, "Steve");
        state.setClientId("Survival");
        state.setActiveChannel("survival-chat");
        channelManager.addMember("survival-chat", targetId);
    }

    private static SensitiveWordFilter sensitiveWordsFilter() {
        return new SensitiveWordFilter();
    }

    @AfterEach
    void tearDown() {
        muteManager.shutdown();
        banManager.shutdown();
    }

    // ====================== helper: dispatch a request ======================

    /**
     * Builds a FullHttpRequest, dispatches it through the default handler, and
     * returns the captured response status + body.
     */
    private Response dispatch(HttpMethod method, String uri, String body) {
        return dispatch(handler, method, uri, body);
    }

    /**
     * Dispatch variant for tests that need a handler with different wiring
     * (e.g. a loaded ConfigManager for the settings endpoint).
     */
    private Response dispatch(RestApiHandler targetHandler, HttpMethod method, String uri, String body) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri,
                body != null ? Unpooled.copiedBuffer(body, CharsetUtil.UTF_8) : Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + validToken);
        if (body != null) {
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        }

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        // PANEL-006: channelRead0 stores the per-request id as a channel
        // attribute, so the mock must return a real channel that supports
        // AttributeMap. An EmbeddedChannel is the lightest such implementation.
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        // Capture the response written to the context
        java.util.concurrent.atomic.AtomicReference<Object> captured = new java.util.concurrent.atomic.AtomicReference<>();
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return promise;
        }).when(ctx).writeAndFlush(any());

        try {
            targetHandler.channelRead0(ctx, request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Object resp = captured.get();
        if (resp instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) resp;
            String responseBody = response.content().toString(StandardCharsets.UTF_8);
            return new Response(response.status(), responseBody);
        }
        // If fireChannelRead was called (e.g. auth passthrough), no response captured
        return new Response(null, "");
    }

    /** Simple response holder. */
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

    // ====================== channel create/delete/update ======================

    @Test
    @DisplayName("POST /api/channels creates a global channel")
    void createGlobalChannel() {
        Response resp = dispatch(HttpMethod.POST, "/api/channels",
                "{\"displayName\":\"Announcements\",\"scope\":\"global\",\"maxCapacity\":50}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.CREATED);
        JsonObject json = resp.asJson();
        assertThat(json.get("success").getAsBoolean()).isTrue();
        assertThat(json.getAsJsonObject("channel").get("displayName").getAsString()).isEqualTo("Announcements");
        assertThat(json.getAsJsonObject("channel").get("scope").getAsString()).isEqualTo("GLOBAL");
        assertThat(channelManager.channelExists(json.getAsJsonObject("channel").get("id").getAsString())).isTrue();
    }

    @Test
    @DisplayName("POST /api/channels creates a private channel with auto-generated id")
    void createPrivateChannel() {
        // PANEL-003: SERVER/PRIVATE scope now requires a real connected client
        // (validated via networkHandler.findByClientId). The test fixture stubs
        // findByClientId("Survival") -> capturedClient, so we supply that clientId.
        Response resp = dispatch(HttpMethod.POST, "/api/channels",
                "{\"displayName\":\"Secret\",\"scope\":\"private\",\"clientId\":\"Survival\",\"maxCapacity\":10}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.CREATED);
        JsonObject json = resp.asJson();
        String id = json.getAsJsonObject("channel").get("id").getAsString();
        assertThat(id).startsWith("NC-");
        assertThat(channelManager.getChannel(id).getScope()).isEqualTo(ChannelScope.PRIVATE);
    }

    @Test
    @DisplayName("POST /api/channels rejects PRIVATE scope without a clientId")
    void createPrivateChannelRejectsMissingClientId() {
        Response resp = dispatch(HttpMethod.POST, "/api/channels",
                "{\"displayName\":\"Secret\",\"scope\":\"private\",\"maxCapacity\":10}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/channels rejects SERVER/PRIVATE scope with unknown clientId")
    void createChannelRejectsUnknownClientId() {
        Response resp = dispatch(HttpMethod.POST, "/api/channels",
                "{\"displayName\":\"Secret\",\"scope\":\"private\",\"clientId\":\"nonexistent\",\"maxCapacity\":10}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/channels rejects invalid scope")
    void createChannelInvalidScope() {
        Response resp = dispatch(HttpMethod.POST, "/api/channels",
                "{\"displayName\":\"X\",\"scope\":\"bogus\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("DELETE /api/channels/{id} deletes a channel and clears members")
    void deleteChannel() {
        // Add a member to verify it gets cleared
        assertThat(channelManager.getChannel("survival-chat").isMember(targetId)).isTrue();

        Response resp = dispatch(HttpMethod.DELETE, "/api/channels/survival-chat", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().get("success").getAsBoolean()).isTrue();
        assertThat(channelManager.channelExists("survival-chat")).isFalse();
    }

    @Test
    @DisplayName("DELETE /api/channels/{id} returns 404 for missing channel")
    void deleteChannelNotFound() {
        Response resp = dispatch(HttpMethod.DELETE, "/api/channels/nope", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("PUT /api/channels/{id} updates displayName and maxCapacity")
    void updateChannel() {
        Response resp = dispatch(HttpMethod.PUT, "/api/channels/staff",
                "{\"displayName\":\"Staff Chat\",\"maxCapacity\":200}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("success").getAsBoolean()).isTrue();
        Channel ch = channelManager.getChannel("staff");
        assertThat(ch.getDisplayName()).isEqualTo("Staff Chat");
        assertThat(ch.getMaxCapacity()).isEqualTo(200);
    }

    @Test
    @DisplayName("PUT /api/channels/{id} updates permission")
    void updateChannelPermission() {
        Response resp = dispatch(HttpMethod.PUT, "/api/channels/staff",
                "{\"permission\":\"novachat.staff\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(channelManager.getChannel("staff").getPermission()).isEqualTo("novachat.staff");
    }

    @Test
    @DisplayName("PUT /api/channels/{id} returns 404 for missing channel")
    void updateChannelNotFound() {
        Response resp = dispatch(HttpMethod.PUT, "/api/channels/nope", "{\"displayName\":\"X\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("PUT /api/channels/{id} sets slowModeSeconds and GET echoes it")
    void updateChannelSlowMode() {
        Response resp = dispatch(HttpMethod.PUT, "/api/channels/staff",
                "{\"slowModeSeconds\":15}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().getAsJsonObject("channel").get("slowModeSeconds").getAsInt())
                .isEqualTo(15);
        assertThat(channelManager.getChannel("staff").getSlowModeSeconds()).isEqualTo(15);

        Response get = dispatch(HttpMethod.GET, "/api/channels/staff", null);
        assertThat(get.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(get.asJson().get("slowModeSeconds").getAsInt()).isEqualTo(15);

        // Setting 0 disables slow mode again.
        Response disable = dispatch(HttpMethod.PUT, "/api/channels/staff",
                "{\"slowModeSeconds\":0}");
        assertThat(disable.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(channelManager.getChannel("staff").getSlowModeSeconds()).isZero();
    }

    @Test
    @DisplayName("PUT /api/channels/{id} rejects negative slowModeSeconds with 400")
    void updateChannelSlowModeNegative() {
        Response resp = dispatch(HttpMethod.PUT, "/api/channels/staff",
                "{\"slowModeSeconds\":-1}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        assertThat(channelManager.getChannel("staff").getSlowModeSeconds()).isZero();
    }

    @Test
    @DisplayName("PUT /api/channels/{id} without slowModeSeconds leaves it unchanged")
    void updateChannelSlowModeUntouched() {
        channelManager.getChannel("staff").setSlowModeSeconds(7);
        Response resp = dispatch(HttpMethod.PUT, "/api/channels/staff",
                "{\"displayName\":\"Staff Chat\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(channelManager.getChannel("staff").getSlowModeSeconds()).isEqualTo(7);
    }

    // ====================== mute / unmute / getMutes ======================

    @Test
    @DisplayName("POST /api/players/{uuid}/mute mutes a player in a channel")
    void mutePlayer() {
        Response resp = dispatch(HttpMethod.POST, "/api/players/" + targetId + "/mute",
                "{\"channelId\":\"survival-chat\",\"durationMs\":60000,\"reason\":\"spam\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().get("success").getAsBoolean()).isTrue();
        assertThat(muteManager.isMuted(targetId, "survival-chat")).isTrue();
    }

    @Test
    @DisplayName("POST /api/players/{uuid}/mute mutes globally when channelId omitted")
    void mutePlayerGlobal() {
        Response resp = dispatch(HttpMethod.POST, "/api/players/" + targetId + "/mute",
                "{\"durationMs\":0,\"reason\":\"permaban\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(muteManager.isMuted(targetId, null)).isTrue();
    }

    @Test
    @DisplayName("POST /api/players/{uuid}/mute returns 404 for missing channel")
    void mutePlayerChannelNotFound() {
        Response resp = dispatch(HttpMethod.POST, "/api/players/" + targetId + "/mute",
                "{\"channelId\":\"nope\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/players/{uuid}/mute returns 400 for invalid UUID")
    void mutePlayerInvalidUuid() {
        Response resp = dispatch(HttpMethod.POST, "/api/players/not-a-uuid/mute",
                "{\"channelId\":\"survival-chat\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/players/{uuid}/unmute unmutes a player")
    void unmutePlayer() {
        // Mute first
        muteManager.mutePlayer(new UUID(0, 0), targetId, "survival-chat", 60000, "spam", null);
        assertThat(muteManager.isMuted(targetId, "survival-chat")).isTrue();

        Response resp = dispatch(HttpMethod.POST, "/api/players/" + targetId + "/unmute",
                "{\"channelId\":\"survival-chat\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().get("success").getAsBoolean()).isTrue();
        assertThat(muteManager.isMuted(targetId, "survival-chat")).isFalse();
    }

    @Test
    @DisplayName("GET /api/mutes lists active mutes")
    void getMutes() {
        muteManager.mutePlayer(new UUID(0, 0), targetId, "survival-chat", 60000, "spam", null);

        Response resp = dispatch(HttpMethod.GET, "/api/mutes", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        JsonArray mutes = json.getAsJsonArray("mutes");
        assertThat(mutes.size()).isGreaterThanOrEqualTo(1);
        boolean foundTarget = false;
        for (int i = 0; i < mutes.size(); i++) {
            JsonObject m = mutes.get(i).getAsJsonObject();
            if (m.get("playerId").getAsString().equals(targetId.toString())) {
                foundTarget = true;
                assertThat(m.get("channelId").getAsString()).isEqualTo("survival-chat");
                assertThat(m.get("reason").getAsString()).isEqualTo("spam");
            }
        }
        assertThat(foundTarget).isTrue();
    }

    @Test
    @DisplayName("REST mute records the panel operator; GET /api/mutes shows the operator column")
    void muteAttributesPanelOperator() {
        Response muteResp = dispatch(HttpMethod.POST, "/api/players/" + targetId + "/mute",
                "{\"channelId\":\"survival-chat\",\"durationMs\":60000,\"reason\":\"spam\"}");
        assertThat(muteResp.status).isEqualTo(HttpResponseStatus.OK);

        UUID expectedOperator = UUID.nameUUIDFromBytes(
                "panel:admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Response resp = dispatch(HttpMethod.GET, "/api/mutes", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonArray mutes = resp.asJson().getAsJsonArray("mutes");
        boolean found = false;
        for (int i = 0; i < mutes.size(); i++) {
            JsonObject m = mutes.get(i).getAsJsonObject();
            if (m.get("playerId").getAsString().equals(targetId.toString())) {
                found = true;
                // Stable name-derived operator UUID + panel username in the operator column.
                assertThat(m.get("operatorId").getAsString()).isEqualTo(expectedOperator.toString());
                assertThat(m.get("operator").getAsString()).isEqualTo("admin");
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("GET /api/mutes returns empty list when no mutes")
    void getMutesEmpty() {
        Response resp = dispatch(HttpMethod.GET, "/api/mutes", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().getAsJsonArray("mutes").size()).isEqualTo(0);
    }

    // ====================== kick ======================

    @Test
    @DisplayName("POST /api/players/{uuid}/kick kicks a player from a channel")
    void kickPlayer() {
        assertThat(channelManager.getChannel("survival-chat").isMember(targetId)).isTrue();

        Response resp = dispatch(HttpMethod.POST, "/api/players/" + targetId + "/kick",
                "{\"channelId\":\"survival-chat\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().get("success").getAsBoolean()).isTrue();
        assertThat(channelManager.getChannel("survival-chat").isMember(targetId)).isFalse();
    }

    @Test
    @DisplayName("POST /api/players/{uuid}/kick returns 400 when channelId missing")
    void kickPlayerNoChannel() {
        Response resp = dispatch(HttpMethod.POST, "/api/players/" + targetId + "/kick", "{}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/players/{uuid}/kick returns 404 for missing channel")
    void kickPlayerChannelNotFound() {
        Response resp = dispatch(HttpMethod.POST, "/api/players/" + targetId + "/kick",
                "{\"channelId\":\"nope\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/players/{uuid}/kick returns 400 when player not in channel")
    void kickPlayerNotMember() {
        UUID other = UUID.randomUUID();
        Response resp = dispatch(HttpMethod.POST, "/api/players/" + other + "/kick",
                "{\"channelId\":\"survival-chat\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    // ====================== invite ======================

    @Test
    @DisplayName("POST /api/channels/{id}/invite creates an invitation code")
    void inviteChannel() {
        // Need a private channel for invite (InvitationManager just requires channel exists)
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("NC-TEST")
                .displayName("Private Test")
                .scope(ChannelScope.PRIVATE)
                .clientId("console")
                .build());

        Response resp = dispatch(HttpMethod.POST, "/api/channels/NC-TEST/invite",
                "{\"ttlMillis\":3600000}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("success").getAsBoolean()).isTrue();
        assertThat(json.get("code").getAsString()).isNotBlank();
        assertThat(json.get("channelId").getAsString()).isEqualTo("NC-TEST");
        assertThat(json.get("expireTime").getAsLong()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    @DisplayName("POST /api/channels/{id}/invite returns 404 for missing channel")
    void inviteChannelNotFound() {
        Response resp = dispatch(HttpMethod.POST, "/api/channels/nope/invite", "{}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/channels/{id}/invite uses default TTL when omitted")
    void inviteChannelDefaultTtl() {
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("NC-DEF")
                .displayName("Default TTL")
                .scope(ChannelScope.PRIVATE)
                .clientId("console")
                .build());

        Response resp = dispatch(HttpMethod.POST, "/api/channels/NC-DEF/invite", "{}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        long expireTime = resp.asJson().get("expireTime").getAsLong();
        // Default TTL is 24h; expireTime should be ~24h from now
        long delta = Math.abs((expireTime - System.currentTimeMillis()) - InvitationManager.DEFAULT_TTL_MILLIS);
        assertThat(delta).isLessThan(60_000L);
    }

    // ====================== invitation revoke ======================

    @Test
    @DisplayName("DELETE /api/channels/{id}/invitations/{code} revokes an invitation")
    void revokeInvitation() throws Exception {
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("NC-REV")
                .displayName("Revoke Test")
                .scope(ChannelScope.PRIVATE)
                .clientId("console")
                .build());
        // Create an invitation via the API (or directly via invitationManager).
        Invitation invitation = invitationManager.createInvitation("NC-REV", java.util.UUID.randomUUID(), 3600000L);
        Response resp = dispatch(HttpMethod.DELETE, "/api/channels/NC-REV/invitations/" + invitation.getCode(), null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("success").getAsBoolean()).isTrue();
        assertThat(json.get("revoked").getAsBoolean()).isTrue();
        assertThat(json.get("code").getAsString()).isEqualTo(invitation.getCode());
        // Verify it is now revoked.
        assertThat(invitationManager.getInvitation(invitation.getCode()).get().isRevoked()).isTrue();
    }

    @Test
    @DisplayName("DELETE /api/channels/{id}/invitations/{code} returns 404 for missing channel")
    void revokeInvitationMissingChannel() {
        Response resp = dispatch(HttpMethod.DELETE, "/api/channels/nope/invitations/CODE1", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DELETE /api/channels/{id}/invitations/{code} returns 404 for missing code")
    void revokeInvitationMissingCode() {
        Response resp = dispatch(HttpMethod.DELETE, "/api/channels/staff/invitations/NOPE0", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DELETE /api/channels/{id}/invitations/{code} returns 404 on channel mismatch")
    void revokeInvitationChannelMismatch() throws Exception {
        // Invitation for NC-A, but DELETE targets NC-B.
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("NC-A").displayName("A").scope(ChannelScope.PRIVATE).clientId("console").build());
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("NC-B").displayName("B").scope(ChannelScope.PRIVATE).clientId("console").build());
        Invitation invitation = invitationManager.createInvitation("NC-A", java.util.UUID.randomUUID(), 3600000L);
        Response resp = dispatch(HttpMethod.DELETE, "/api/channels/NC-B/invitations/" + invitation.getCode(), null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
        // The invitation must NOT have been revoked.
        assertThat(invitationManager.getInvitation(invitation.getCode()).get().isRevoked()).isFalse();
    }

    // ====================== reload ======================

    @Test
    @DisplayName("POST /api/reload triggers config reload")
    void reload() {
        Response resp = dispatch(HttpMethod.POST, "/api/reload", null);
        // The temporary config is created on first reload. The endpoint should
        // still report a server error if the environment rejects the write.
        assertThat(resp.status).isIn(HttpResponseStatus.OK, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        if (resp.status == HttpResponseStatus.OK) {
            assertThat(resp.asJson().get("success").getAsBoolean()).isTrue();
        }
    }

    // ====================== client disconnect ======================

    @Test
    @DisplayName("DELETE /api/clients/{clientId} disconnects a game-server client")
    void disconnectClient() {
        Response resp = dispatch(HttpMethod.DELETE, "/api/clients/Survival", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().get("success").getAsBoolean()).isTrue();
        verify(capturedClient).close();
    }

    @Test
    @DisplayName("DELETE /api/clients/{clientId} returns 404 for missing client")
    void disconnectClientNotFound() {
        Response resp = dispatch(HttpMethod.DELETE, "/api/clients/nonexistent", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    // ====================== console ======================

    @Test
    @DisplayName("POST /api/console executes a status command")
    void consoleStatus() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{\"command\":\"status\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("success").getAsBoolean()).isTrue();
        assertThat(json.get("output").getAsString()).isNotBlank();
    }

    @Test
    @DisplayName("POST /api/console executes a channels command")
    void consoleChannels() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{\"command\":\"channels\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().get("output").getAsString()).contains("staff");
    }

    @Test
    @DisplayName("POST /api/console rejects stop command (not on the whitelist)")
    void consoleStopNotWhitelisted() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{\"command\":\"stop\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.FORBIDDEN);
        assertThat(resp.body).contains("stop");
    }

    @Test
    @DisplayName("POST /api/console rejects shutdown command (not on the whitelist)")
    void consoleShutdownNotWhitelisted() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{\"command\":\"shutdown\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.FORBIDDEN);
        assertThat(resp.body).contains("shutdown");
    }

    @Test
    @DisplayName("POST /api/console rejects stop with trailing args (whitelist by first token)")
    void consoleStopWithArgsNotWhitelisted() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{\"command\":\"stop now please\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /api/console rejects spy command (reserved for the real console)")
    void consoleSpyNotWhitelisted() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{\"command\":\"spy start staff\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /api/console returns 400 for missing command")
    void consoleMissingCommand() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/console returns 400 for blank command")
    void consoleBlankCommand() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{\"command\":\"  \"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    // ====================== auth ======================

    @Test
    @DisplayName("Endpoints without auth return 401")
    void noAuthReturns401() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/channels");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        java.util.concurrent.atomic.AtomicReference<Object> captured = new java.util.concurrent.atomic.AtomicReference<>();
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

        FullHttpResponse resp = (FullHttpResponse) captured.get();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Refresh tokens are rejected as API bearer tokens")
    void refreshTokenRejected() {
        String refreshToken = jwtService.generateRefreshToken(
                UUID.randomUUID().toString(), "admin", "SUPER_ADMIN");

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/channels");
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + refreshToken);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        java.util.concurrent.atomic.AtomicReference<Object> captured = new java.util.concurrent.atomic.AtomicReference<>();
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

        FullHttpResponse resp = (FullHttpResponse) captured.get();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
    }

    // ====================== routing ======================

    @Test
    @DisplayName("Unknown endpoint returns 404")
    void unknownEndpoint() {
        Response resp = dispatch(HttpMethod.GET, "/api/unknown", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    // ====================== settings ======================

    @Test
    @DisplayName("PUT /api/settings applies to runtime, persists to disk, and never reloads from disk")
    void updateSettingsAppliesAndPersistsWithoutReload() throws Exception {
        java.nio.file.Path configPath = tempDir.resolve("novalink.yml");
        ConfigManager liveConfigManager = new ConfigManager(configPath);
        // Creates the default config on disk: filterEnabled=true,
        // messageLogEnabled=false, crossServerChatEnabled=true.
        liveConfigManager.load();

        RestApiHandler settingsHandler = new RestApiHandler(
                jwtService,
                new AuthManager(new IpBanManager(5, 60000)),
                channelManager,
                playerStateManager,
                messageRouter,
                new WebhookManager(),
                muteManager,
                banManager,
                invitationManager,
                liveConfigManager,
                networkHandler,
                consoleCommandHandler,
                notificationStore
        );
        java.util.concurrent.atomic.AtomicBoolean privateMessagesEnabled =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        com.nova.link.log.MessageLogService messageLogService =
                new com.nova.link.log.MessageLogService(db, 30);
        settingsHandler.setPrivateMessagesEnabledFlag(privateMessagesEnabled);
        settingsHandler.setMessageLogService(messageLogService);

        assertThat(sensitiveWordFilter.isEnabled()).isTrue();

        Response getBeforeUpdate = dispatch(settingsHandler, HttpMethod.GET, "/api/settings", null);
        assertThat(getBeforeUpdate.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(getBeforeUpdate.asJson().get("privateMessagesEnabled").getAsBoolean()).isTrue();
        assertThat(getBeforeUpdate.asJson().get("messageLogRetentionDays").getAsInt()).isEqualTo(30);

        Response resp = dispatch(settingsHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"messageLogEnabled\":true,"
                        + "\"crossServerChatEnabled\":false,\"privateMessagesEnabled\":false,"
                        + "\"messageLogRetentionDays\":14}");

        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        // Response echoes the values that actually took effect.
        assertThat(json.get("filterEnabled").getAsBoolean()).isFalse();
        assertThat(json.get("messageLogEnabled").getAsBoolean()).isTrue();
        assertThat(json.get("crossServerChatEnabled").getAsBoolean()).isFalse();
        assertThat(json.get("privateMessagesEnabled").getAsBoolean()).isFalse();
        assertThat(json.get("messageLogRetentionDays").getAsInt()).isEqualTo(14);

        // Applied to the live runtime component (the old code triggered a disk
        // reload which reverted the just-made change).
        assertThat(sensitiveWordFilter.isEnabled()).isFalse();
        assertThat(privateMessagesEnabled).isFalse();
        assertThat(messageLogService.getRetentionDays()).isEqualTo(14);
        // No reload happened — the fix must not re-read the config from disk.
        assertThat(liveConfigManager.getReloadCount()).isZero();
        // Live in-memory config carries the new values.
        assertThat(liveConfigManager.getConfig().getFeatures().isFilterEnabled()).isFalse();
        assertThat(liveConfigManager.getConfig().getFeatures().isMessageLogEnabled()).isTrue();
        assertThat(liveConfigManager.getConfig().getFeatures().isCrossServerChatEnabled()).isFalse();
        assertThat(liveConfigManager.getConfig().getFeatures().isPrivateMessagesEnabled()).isFalse();
        assertThat(liveConfigManager.getConfig().getFeatures().getMessageLogRetentionDays()).isEqualTo(14);

        // Persisted: a fresh loader reading the same file sees the new values.
        ConfigManager reread = new ConfigManager(configPath);
        com.nova.link.config.NovaLinkConfig persisted = reread.load();
        assertThat(persisted.getFeatures().isFilterEnabled()).isFalse();
        assertThat(persisted.getFeatures().isMessageLogEnabled()).isTrue();
        assertThat(persisted.getFeatures().isCrossServerChatEnabled()).isFalse();
        assertThat(persisted.getFeatures().isPrivateMessagesEnabled()).isFalse();
        assertThat(persisted.getFeatures().getMessageLogRetentionDays()).isEqualTo(14);
        messageLogService.shutdown();
    }

    // ====================== config history / rollback (§11.6 Project 20) ======================

    /**
     * Builds a {@link RestApiHandler} backed by a loaded {@link ConfigManager}
     * so the config-history endpoints can persist + read back real snapshots.
     * Reuses the shared {@link #db} (a MemoryProvider) so the lazy
     * {@code configHistoryService()} finds a provider via playerStateManager.
     */
    private RestApiHandler buildHistoryHandler() throws Exception {
        java.nio.file.Path configPath = tempDir.resolve("novalink-history.yml");
        ConfigManager liveConfigManager = new ConfigManager(configPath);
        liveConfigManager.load();
        // Touch the live config so a save() writes a file (otherwise the
        // filter/features defaults already match the template).
        liveConfigManager.getConfig().getFeatures().setFilterEnabled(true);

        RestApiHandler historyHandler = new RestApiHandler(
                jwtService,
                new AuthManager(new IpBanManager(5, 60000)),
                channelManager,
                playerStateManager,
                messageRouter,
                new WebhookManager(),
                muteManager,
                banManager,
                invitationManager,
                liveConfigManager,
                networkHandler,
                consoleCommandHandler,
                notificationStore
        );
        return historyHandler;
    }

    @Test
    @DisplayName("GET /api/settings/history lists snapshots newest first after settings updates")
    void configHistoryListsSnapshotsAfterSave() throws Exception {
        RestApiHandler historyHandler = buildHistoryHandler();
        // Trigger two snapshots via PUT /api/settings (the save() inside writes
        // a masked snapshot via the auto-wired ConfigHistoryService).
        dispatch(historyHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":0}");
        dispatch(historyHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":true,\"baseRevision\":1}");

        Response resp = dispatch(historyHandler, HttpMethod.GET, "/api/settings/history?limit=10", null);
        assertThat(resp.status).isEqualTo(HttpHttpResponseStatusOK());
        JsonObject json = resp.asJson();
        com.google.gson.JsonArray items = json.getAsJsonArray("items");
        // At least the two snapshots we just recorded (rollback may add more).
        assertThat(items.size()).isGreaterThanOrEqualTo(2);
        // Newest first: the first item is the most recent save.
        long firstRev = items.get(0).getAsJsonObject().get("revision").getAsLong();
        long secondRev = items.get(1).getAsJsonObject().get("revision").getAsLong();
        assertThat(firstRev).isGreaterThan(secondRev);
        // History list must NOT carry the snapshot_json payload.
        assertThat(items.get(0).getAsJsonObject().has("snapshot")).isFalse();
        // Exactly one row is active.
        long activeCount = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getAsJsonObject().get("active").getAsBoolean()) {
                activeCount++;
            }
        }
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/settings/history rejects VIEWER tokens (ADMIN+)")
    void configHistoryRejectsViewer() throws Exception {
        RestApiHandler historyHandler = buildHistoryHandler();
        String viewerToken = jwtService.generateToken(UUID.randomUUID().toString(), "viewer", "VIEWER");

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/settings/history", Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + viewerToken);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        java.util.concurrent.atomic.AtomicReference<Object> captured = new java.util.concurrent.atomic.AtomicReference<>();
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return promise;
        }).when(ctx).writeAndFlush(any());
        historyHandler.channelRead0(ctx, request);

        Object resp = captured.get();
        assertThat(resp).isInstanceOf(FullHttpResponse.class);
        assertThat(((FullHttpResponse) resp).status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /api/settings/snapshots/{revision} returns the masked payload, 404 when absent")
    void configSnapshotFetch() throws Exception {
        RestApiHandler historyHandler = buildHistoryHandler();
        // Record one snapshot via a settings update.
        Response putResp = dispatch(historyHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":0}");
        long revision = putResp.asJson().get("revision").getAsLong();

        Response ok = dispatch(historyHandler, HttpMethod.GET,
                "/api/settings/snapshots/" + revision, null);
        assertThat(ok.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = ok.asJson();
        assertThat(json.get("revision").getAsLong()).isEqualTo(revision);
        assertThat(json.has("snapshot")).isTrue();
        // The snapshot is a structured object (the masked config tree).
        assertThat(json.get("snapshot").isJsonObject()).isTrue();
        // The snapshot carries the features block (it's the full NovaLinkConfig).
        assertThat(json.getAsJsonObject("snapshot").has("features")).isTrue();

        Response notFound = dispatch(historyHandler, HttpMethod.GET,
                "/api/settings/snapshots/999999", null);
        assertThat(notFound.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /api/settings/diff reports changed settings between two revisions")
    void configDiffReportsChanges() throws Exception {
        RestApiHandler historyHandler = buildHistoryHandler();
        Response r1 = dispatch(historyHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":0}");
        long from = r1.asJson().get("revision").getAsLong();
        Response r2 = dispatch(historyHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":true,\"baseRevision\":" + from + "}");
        long to = r2.asJson().get("revision").getAsLong();

        Response resp = dispatch(historyHandler, HttpMethod.GET,
                "/api/settings/diff?from=" + from + "&to=" + to, null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("fromRevision").getAsLong()).isEqualTo(from);
        assertThat(json.get("toRevision").getAsLong()).isEqualTo(to);
        com.google.gson.JsonArray changed = json.getAsJsonArray("changed");
        // At least the filterEnabled leaf changed between the two snapshots.
        assertThat(changed.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/settings/diff 404s when a revision does not exist")
    void configDiffMissingRevision() throws Exception {
        RestApiHandler historyHandler = buildHistoryHandler();
        Response r1 = dispatch(historyHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":0}");
        long from = r1.asJson().get("revision").getAsLong();
        Response resp = dispatch(historyHandler, HttpMethod.GET,
                "/api/settings/diff?from=" + from + "&to=999999", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/settings/rollback restores a prior revision and appends a rollback row")
    void rollbackRestoresPriorRevision() throws Exception {
        RestApiHandler historyHandler = buildHistoryHandler();
        // r1: filterEnabled=false; r2: filterEnabled=true.
        Response r1 = dispatch(historyHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":0}");
        long from = r1.asJson().get("revision").getAsLong();
        dispatch(historyHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":true,\"baseRevision\":" + from + "}");

        // Roll back to r1 (filterEnabled=false).
        Response rollback = dispatch(historyHandler, HttpMethod.POST, "/api/settings/rollback",
                "{\"targetRevision\":" + from + "}");
        assertThat(rollback.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = rollback.asJson();
        assertThat(json.get("success").getAsBoolean()).isTrue();
        assertThat(json.get("rolledBackTo").getAsLong()).isEqualTo(from);
        long newRevision = json.get("revision").getAsLong();
        assertThat(newRevision).isGreaterThan(from);

        // The live features were rolled back to r1's value (false).
        Response get = dispatch(historyHandler, HttpMethod.GET, "/api/settings", null);
        assertThat(get.asJson().get("filterEnabled").getAsBoolean()).isFalse();

        // History now contains the rollback row (newest, active).
        Response history = dispatch(historyHandler, HttpMethod.GET, "/api/settings/history", null);
        com.google.gson.JsonArray items = history.asJson().getAsJsonArray("items");
        assertThat(items.get(0).getAsJsonObject().get("revision").getAsLong()).isEqualTo(newRevision);
        assertThat(items.get(0).getAsJsonObject().get("active").getAsBoolean()).isTrue();
    }

    @Test
    @DisplayName("POST /api/settings/rollback 404s when the target revision is absent")
    void rollbackMissingTarget() throws Exception {
        RestApiHandler historyHandler = buildHistoryHandler();
        Response resp = dispatch(historyHandler, HttpMethod.POST, "/api/settings/rollback",
                "{\"targetRevision\":999999}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/settings/rollback 400s when the target is already active")
    void rollbackTargetAlreadyActive() throws Exception {
        RestApiHandler historyHandler = buildHistoryHandler();
        Response r1 = dispatch(historyHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":0}");
        long active = r1.asJson().get("revision").getAsLong();

        Response resp = dispatch(historyHandler, HttpMethod.POST, "/api/settings/rollback",
                "{\"targetRevision\":" + active + "}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("snapshots mask secrets: database redis password is never persisted as plaintext")
    void snapshotsMaskSecrets() throws Exception {
        RestApiHandler historyHandler = buildHistoryHandler();
        // Stash a real-looking secret on the live config so the snapshot would
        // contain it if masking were broken.
        com.nova.link.config.NovaLinkConfig live = historyHandler.configManager().getConfig();
        live.getDatabase().getRedis().setPassword("super-secret-redis-password-123");
        live.getServer().setSecretKey("super-secret-server-key-456");

        Response r1 = dispatch(historyHandler, HttpMethod.PUT, "/api/settings",
                "{\"filterEnabled\":false,\"baseRevision\":0}");
        long revision = r1.asJson().get("revision").getAsLong();

        Response snap = dispatch(historyHandler, HttpMethod.GET,
                "/api/settings/snapshots/" + revision, null);
        String body = snap.body;
        // No plaintext secret survives masking into the snapshot.
        assertThat(body).doesNotContain("super-secret-redis-password-123");
        assertThat(body).doesNotContain("super-secret-server-key-456");
        // The mask sentinel IS present for both fields.
        assertThat(body).contains("\"***\"");
    }

    private static HttpResponseStatus HttpHttpResponseStatusOK() {
        return HttpResponseStatus.OK;
    }

    // ====================== offline moderation visibility ======================

    @Test
    @DisplayName("GET /api/mutes lists mutes of offline players (not in the online state cache)")
    void getMutesIncludesOfflinePlayers() {
        UUID offlineId = UUID.randomUUID();
        muteManager.mutePlayer(new UUID(0, 0), offlineId, null, 0, "offline mute", null);

        Response resp = dispatch(HttpMethod.GET, "/api/mutes", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonArray mutes = resp.asJson().getAsJsonArray("mutes");
        boolean found = false;
        for (int i = 0; i < mutes.size(); i++) {
            JsonObject m = mutes.get(i).getAsJsonObject();
            if (m.get("playerId").getAsString().equals(offlineId.toString())) {
                found = true;
                assertThat(m.get("channelId").getAsString()).isEqualTo("(global)");
                assertThat(m.get("reason").getAsString()).isEqualTo("offline mute");
                assertThat(m.get("permanent").getAsBoolean()).isTrue();
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("GET /api/bans lists bans of offline players (not in the online state cache)")
    void getBansIncludesOfflinePlayers() {
        UUID offlineId = UUID.randomUUID();
        banManager.banPlayer(new UUID(0, 0), offlineId, null, 0, "offline ban", null);

        Response resp = dispatch(HttpMethod.GET, "/api/bans", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonArray bans = JsonParser.parseString(resp.body).getAsJsonArray();
        boolean found = false;
        for (int i = 0; i < bans.size(); i++) {
            JsonObject entry = bans.get(i).getAsJsonObject();
            if (entry.get("playerId").getAsString().equals(offlineId.toString())) {
                found = true;
                JsonArray playerBans = entry.getAsJsonArray("bans");
                assertThat(playerBans.size()).isEqualTo(1);
                assertThat(playerBans.get(0).getAsJsonObject().get("reason").getAsString())
                        .isEqualTo("offline ban");
            }
        }
        assertThat(found).isTrue();
    }

    // ====================== notification pagination ======================

    @Test
    @DisplayName("GET /api/notifications reports the real total, not the page size")
    void getNotificationsReportsRealTotal() {
        notificationStore.createNotification("t1", "m1", "info");
        notificationStore.createNotification("t2", "m2", "info");
        notificationStore.createNotification("t3", "m3", "info");

        Response resp = dispatch(HttpMethod.GET, "/api/notifications?page=1&size=2", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.getAsJsonArray("items").size()).isEqualTo(2);
        assertThat(json.get("total").getAsInt()).isEqualTo(3);
        assertThat(json.get("page").getAsInt()).isEqualTo(1);
        assertThat(json.get("pageSize").getAsInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("PANEL-014: per-user markRead does not affect another admin's unread count")
    void perUserMarkReadIsolatesAdmins() {
        // Two broadcast notifications: both admins start with unreadCount=2.
        notificationStore.createNotification("t1", "m1", "info");
        notificationStore.createNotification("t2", "m2", "info");

        // admin (SUPER_ADMIN) marks one read.
        Response r1 = dispatch(HttpMethod.POST, "/api/notifications/1/read", null);
        assertThat(r1.status).isEqualTo(HttpResponseStatus.OK);

        // A second admin token reveals the same notification is still unread
        // for them (per-user state isolation).
        String admin2Token = jwtService.generateToken(UUID.randomUUID().toString(), "admin2", "ADMIN");
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/notifications?page=1&size=10&unreadOnly=true", Unpooled.EMPTY_BUFFER);
        req.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + admin2Token);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        java.util.concurrent.atomic.AtomicReference<Object> captured = new java.util.concurrent.atomic.AtomicReference<>();
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> { captured.set(inv.getArgument(0)); return promise; }).when(ctx).writeAndFlush(any());
        try { handler.channelRead0(ctx, req); } catch (Exception e) { throw new RuntimeException(e); }
        FullHttpResponse resp = (FullHttpResponse) captured.get();
        JsonObject body = JsonParser.parseString(resp.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
        // admin2's unreadCount is still 2 (admin's markRead was per-user).
        assertThat(body.get("unreadCount").getAsInt()).isEqualTo(2);
        assertThat(body.getAsJsonArray("items").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("PANEL-014: DELETE /api/notifications only clears directed notifications for the caller")
    void perUserClearOnlyDeletesDirectedNotifications() throws Exception {
        // One broadcast + one directed notification for "admin".
        notificationStore.createNotification("b1", "broadcast", "info");
        Notification directed = new Notification("d1", "directed", "info");
        directed.setRecipient("admin");
        db.saveNotification(directed);

        Response resp = dispatch(HttpMethod.DELETE, "/api/notifications", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject body = resp.asJson();
        // Only the directed notification was deleted.
        assertThat(body.get("cleared").getAsInt()).isEqualTo(1);

        // Broadcast is still visible.
        Response list = dispatch(HttpMethod.GET, "/api/notifications?page=1&size=10", null);
        JsonObject listBody = list.asJson();
        assertThat(listBody.get("total").getAsInt()).isEqualTo(1);
        assertThat(listBody.getAsJsonArray("items").get(0).getAsJsonObject().get("title").getAsString())
                .isEqualTo("b1");
    }

    @Test
    @DisplayName("PANEL-014: SUPER_ADMIN can clear broadcast via /api/notifications/broadcast")
    void superAdminCanClearBroadcast() {
        notificationStore.createNotification("b1", "broadcast", "info");
        notificationStore.createNotification("b2", "broadcast", "info");

        Response resp = dispatch(HttpMethod.DELETE, "/api/notifications/broadcast", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject body = resp.asJson();
        // Both broadcast notifications deleted.
        assertThat(body.get("cleared").getAsInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("PANEL-014: ADMIN cannot clear broadcast (SUPER_ADMIN only)")
    void adminCannotClearBroadcast() {
        notificationStore.createNotification("b1", "broadcast", "info");
        String adminToken = jwtService.generateToken(UUID.randomUUID().toString(), "admin2", "ADMIN");
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.DELETE,
                "/api/notifications/broadcast", Unpooled.EMPTY_BUFFER);
        req.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + adminToken);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        java.util.concurrent.atomic.AtomicReference<Object> captured = new java.util.concurrent.atomic.AtomicReference<>();
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> { captured.set(inv.getArgument(0)); return promise; }).when(ctx).writeAndFlush(any());
        try { handler.channelRead0(ctx, req); } catch (Exception e) { throw new RuntimeException(e); }
        FullHttpResponse resp = (FullHttpResponse) captured.get();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    // ====================== POST /api/messages (BACK-002 / BACK-003) ======================

    @Test
    @DisplayName("POST /api/messages derives sender identity from JWT, not the body")
    void sendMessageIgnoresBodySenderName() {
        // The capturedClient (Survival) is the only online connection; the
        // "staff" channel is GLOBAL so it routes to every authenticated client.
        when(capturedClient.isAuthenticated()).thenReturn(true);
        when(capturedClient.isActive()).thenReturn(true);
        when(networkHandler.getConnections()).thenReturn(java.util.Set.of(capturedClient));

        // Body tries to forge a sender name; it must NOT become the sender identity.
        Response resp = dispatch(HttpMethod.POST, "/api/messages",
                "{\"channelId\":\"staff\",\"senderName\":\"Forged\",\"content\":\"hi\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject body = resp.asJson();
        assertThat(body.get("success").getAsBoolean()).isTrue();
        // recipientCount is surfaced on success (BACK-003).
        assertThat(body.get("recipientCount").getAsInt()).isEqualTo(1);
        // The forged body senderName is echoed as display-only metadata with its source.
        assertThat(body.get("senderName").getAsString()).isEqualTo("Forged");
        assertThat(body.get("senderNameSource").getAsString()).isEqualTo("body");
        // The routed packet's senderName is the JWT-derived "admin", not "Forged".
        java.util.concurrent.atomic.AtomicReference<Object> sent = new java.util.concurrent.atomic.AtomicReference<>();
        when(capturedClient.sendPacket(any())).thenAnswer(inv -> {
            sent.set(inv.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        dispatch(HttpMethod.POST, "/api/messages",
                "{\"channelId\":\"staff\",\"senderName\":\"Forged\",\"content\":\"hi2\"}");
        com.nova.chat.common.protocol.packets.ChatMessagePacket pkt =
                (com.nova.chat.common.protocol.packets.ChatMessagePacket) sent.get();
        assertThat(pkt.getSenderName()).isEqualTo("admin");
        // Authenticated sender UUID is the stable panel-derived UUID, not 0000...0000.
        UUID expectedOperator = UUID.nameUUIDFromBytes(
                "panel:admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(pkt.getSenderId()).isEqualTo(expectedOperator);
    }

    @Test
    @DisplayName("POST /api/messages returns 404 when the channel does not exist (BACK-003)")
    void sendMessageChannelNotFoundReturns404() {
        Response resp = dispatch(HttpMethod.POST, "/api/messages",
                "{\"channelId\":\"nope\",\"content\":\"hi\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
        // NC error code mirrors the existing NC-4xx idiom.
        assertThat(resp.body).contains("NC-404");
    }

    @Test
    @DisplayName("POST /api/messages returns 404 when routing produced zero recipients (BACK-003)")
    void sendMessageZeroRecipientsReturns404() {
        // "staff" exists (RBAC canSend passes for SUPER_ADMIN) but no client is
        // online, so routeMessage yields success-with-zero-recipients.
        when(networkHandler.getConnections()).thenReturn(java.util.Set.of());

        Response resp = dispatch(HttpMethod.POST, "/api/messages",
                "{\"channelId\":\"staff\",\"content\":\"hi\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
        assertThat(resp.body).contains("NC-404");
    }

    @Test
    @DisplayName("POST /api/messages returns 400 when content is missing")
    void sendMessageMissingContent() {
        Response resp = dispatch(HttpMethod.POST, "/api/messages",
                "{\"channelId\":\"staff\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/messages returns 400 when channelId is missing")
    void sendMessageMissingChannel() {
        Response resp = dispatch(HttpMethod.POST, "/api/messages",
                "{\"content\":\"hi\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/messages on a SERVER channel routes to the bound client and reports recipientCount")
    void sendMessageServerChannelReportsRecipientCount() {
        when(capturedClient.isAuthenticated()).thenReturn(true);
        when(capturedClient.isActive()).thenReturn(true);
        when(networkHandler.getConnections()).thenReturn(java.util.Set.of(capturedClient));

        Response resp = dispatch(HttpMethod.POST, "/api/messages",
                "{\"channelId\":\"survival-chat\",\"content\":\"hi\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().get("recipientCount").getAsInt()).isEqualTo(1);
    }

    // ====================== PANEL-003 / PANEL-004 ======================

    @Test
    @DisplayName("PUT /api/channels/{id} rejects update of a CONFIG-managed channel")
    void updateChannelRejectsConfigSource() {
        // Mark the seed "staff" channel as config-managed, then attempt an
        // update — PANEL-004 requires a 403 because config channels are read-only.
        channelManager.getChannel("staff").setSource(com.nova.link.channel.ChannelSource.CONFIG);
        Response resp = dispatch(HttpMethod.PUT, "/api/channels/staff",
                "{\"displayName\":\"Staff Chat\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("DELETE /api/channels/{id} rejects deletion of a CONFIG-managed channel")
    void deleteChannelRejectsConfigSource() {
        channelManager.getChannel("staff").setSource(com.nova.link.channel.ChannelSource.CONFIG);
        Response resp = dispatch(HttpMethod.DELETE, "/api/channels/staff", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PUT /api/channels/{id} clears permission when permissionPresent=true and permission=null")
    void updateChannelClearsPermission() {
        // Seed a permission on "staff", then clear it via permissionPresent.
        channelManager.getChannel("staff").setPermission("novachat.staff");
        Response resp = dispatch(HttpMethod.PUT, "/api/channels/staff",
                "{\"permissionPresent\":true,\"permission\":null}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(channelManager.getChannel("staff").getPermission()).isNull();
    }

    @Test
    @DisplayName("PUT /api/channels/{id} rejects non-positive maxCapacity with 400")
    void updateChannelRejectsNonPositiveMaxCapacity() {
        Response resp = dispatch(HttpMethod.PUT, "/api/channels/staff",
                "{\"maxCapacity\":0}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /api/channels/{id} returns source and revision")
    void getChannelReturnsSourceAndRevision() {
        channelManager.getChannel("staff").setSource(com.nova.link.channel.ChannelSource.CONFIG);
        Response resp = dispatch(HttpMethod.GET, "/api/channels/staff", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("source").getAsString()).isEqualTo("CONFIG");
        assertThat(json.has("revision")).isTrue();
    }

    // ====================== PANEL-007 moderation case/appeal workflow ======================

    @Test
    @DisplayName("POST /api/reports creates a moderation case and returns 201 with caseId+status OPEN")
    void createReportCreatesCase() {
        Response resp = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-1\",\"reasonCode\":\"SPAM\","
                        + "\"reasonText\":\"repeated spam\",\"originChannelId\":\"survival-chat\","
                        + "\"evidenceSnapshot\":\"<log>\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.CREATED);
        JsonObject json = resp.asJson();
        assertThat(json.has("caseId")).isTrue();
        assertThat(json.get("status").getAsString()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("GET /api/moderation/cases lists the filed case and reports total/page/pageSize")
    void listModerationCasesReturnsFiledCase() {
        dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-2\",\"reasonText\":\"toxicity\"}");
        Response resp = dispatch(HttpMethod.GET, "/api/moderation/cases?page=1&size=10", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.getAsJsonArray("items").size()).isGreaterThan(0);
        assertThat(json.get("page").getAsInt()).isEqualTo(1);
        assertThat(json.get("pageSize").getAsInt()).isEqualTo(10);
        assertThat(json.get("total").getAsInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/moderation/cases/{id} returns the case detail with split reasonCode/reasonText")
    void getModerationCaseReturnsDetail() {
        Response created = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-3\",\"reasonCode\":\"HARASSMENT\","
                        + "\"reasonText\":\"slurs in chat\"}");
        String caseId = created.asJson().get("caseId").getAsString();
        Response resp = dispatch(HttpMethod.GET, "/api/moderation/cases/" + caseId, null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject detail = resp.asJson();
        assertThat(detail.get("caseId").getAsString()).isEqualTo(caseId);
        assertThat(detail.get("reasonCode").getAsString()).isEqualTo("HARASSMENT");
        assertThat(detail.get("reasonText").getAsString()).isEqualTo("slurs in chat");
    }

    @Test
    @DisplayName("GET /api/moderation/cases/{id} returns 404 for a missing case")
    void getModerationCaseMissingReturns404() {
        Response resp = dispatch(HttpMethod.GET, "/api/moderation/cases/does-not-exist", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/moderation/cases/{id}/assign assigns a moderator and returns 200")
    void assignModeratorCaseReturns200() {
        String caseId = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-4\",\"reasonText\":\"ads\"}")
                .asJson().get("caseId").getAsString();
        Response resp = dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/assign",
                "{\"moderator\":\"mod-alice\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("success").getAsBoolean()).isTrue();
        assertThat(json.get("assignedModerator").getAsString()).isEqualTo("mod-alice");
    }

    @Test
    @DisplayName("POST /api/moderation/cases/{id}/resolve with mute action resolves the case to MUTED")
    void resolveModerationCaseMuteReturns200() {
        String caseId = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-5\",\"reasonText\":\"caps\"}")
                .asJson().get("caseId").getAsString();
        Response resp = dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/resolve",
                "{\"action\":\"mute\",\"reason\":\"30m mute\",\"targetChannelId\":\"survival-chat\","
                        + "\"durationMs\":1800000}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.get("caseId").getAsString()).isEqualTo(caseId);
        assertThat(json.get("action").getAsString()).isEqualTo("mute");
        // Verify the case moved to RESOLVED with a MUTED resolutionAction.
        Response detail = dispatch(HttpMethod.GET, "/api/moderation/cases/" + caseId, null);
        assertThat(detail.asJson().get("status").getAsString()).isEqualTo("RESOLVED");
        assertThat(detail.asJson().get("resolutionAction").getAsString()).isEqualTo("MUTED");
    }

    @Test
    @DisplayName("POST /api/moderation/cases/{id}/resolve rejects an unknown action with 400")
    void resolveModerationCaseBadActionReturns400() {
        String caseId = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-6\",\"reasonText\":\"x\"}")
                .asJson().get("caseId").getAsString();
        Response resp = dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/resolve",
                "{\"action\":\"banana\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/moderation/cases/{id}/evidence attaches evidence and returns 201")
    void addCaseEvidenceReturns201() {
        String caseId = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-7\",\"reasonText\":\"scam\"}")
                .asJson().get("caseId").getAsString();
        Response resp = dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/evidence",
                "{\"evidenceType\":\"CHAT_LOG\",\"contentPayload\":\"<snip>\",\"description\":\"excerpt\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.CREATED);
        JsonObject json = resp.asJson();
        assertThat(json.has("evidenceId")).isTrue();
        assertThat(json.get("caseId").getAsString()).isEqualTo(caseId);
    }

    @Test
    @DisplayName("GET /api/moderation/cases/{id}/evidence lists the attached evidence")
    void getCaseEvidenceListsAttached() {
        String caseId = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-8\",\"reasonText\":\"grief\"}")
                .asJson().get("caseId").getAsString();
        dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/evidence",
                "{\"evidenceType\":\"CHAT_LOG\",\"contentPayload\":\"hi\",\"description\":\"hi\"}");
        Response resp = dispatch(HttpMethod.GET, "/api/moderation/cases/" + caseId + "/evidence", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.getAsJsonArray("items").size()).isEqualTo(1);
        JsonObject item = json.getAsJsonArray("items").get(0).getAsJsonObject();
        assertThat(item.get("evidenceType").getAsString()).isEqualTo("CHAT_LOG");
        assertThat(item.get("contentSnapshot").getAsString()).isEqualTo("hi");
    }

    @Test
    @DisplayName("POST /api/appeals against a resolved case creates an appeal with status PENDING (201)")
    void createAppealAgainstResolvedCaseReturns201() {
        // File + resolve a case so it is appealable.
        String caseId = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-9\",\"reasonText\":\"toxic\"}")
                .asJson().get("caseId").getAsString();
        dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/resolve",
                "{\"action\":\"warn\",\"reason\":\"warning issued\"}");
        Response resp = dispatch(HttpMethod.POST, "/api/appeals",
                "{\"caseId\":\"" + caseId + "\",\"appellantId\":\"player-9\","
                        + "\"reason\":\"unfair warning\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.CREATED);
        JsonObject json = resp.asJson();
        assertThat(json.has("appealId")).isTrue();
        assertThat(json.get("status").getAsString()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("POST /api/appeals against a non-resolved case is rejected with 403")
    void createAppealAgainstOpenCaseReturns403() {
        String caseId = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-10\",\"reasonText\":\"x\"}")
                .asJson().get("caseId").getAsString();
        Response resp = dispatch(HttpMethod.POST, "/api/appeals",
                "{\"caseId\":\"" + caseId + "\",\"appellantId\":\"player-10\","
                        + "\"reason\":\"premature\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /api/appeals lists the filed appeal")
    void listAppealsReturnsFiledAppeal() {
        String caseId = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-11\",\"reasonText\":\"r\"}")
                .asJson().get("caseId").getAsString();
        // A DISMISSED case is CLOSED (no adverse action) and not appealable;
        // use a WARN resolution so the case is RESOLVED and can be appealed.
        dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/resolve",
                "{\"action\":\"warn\",\"reason\":\"warning issued\"}");
        dispatch(HttpMethod.POST, "/api/appeals",
                "{\"caseId\":\"" + caseId + "\",\"appellantId\":\"player-11\","
                        + "\"reason\":\"please review\"}");
        Response resp = dispatch(HttpMethod.GET, "/api/appeals?page=1&size=10", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.getAsJsonArray("items").size()).isGreaterThan(0);
        assertThat(json.get("total").getAsInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/appeals/{id}/review by a different reviewer returns 200 with the reviewed status")
    void reviewAppealByDifferentReviewerReturns200() {
        // File + assign (to mod-bob) + resolve so the case has an assigned
        // moderator that differs from the reviewer.
        String caseId = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-12\",\"reasonText\":\"x\"}")
                .asJson().get("caseId").getAsString();
        dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/assign",
                "{\"moderator\":\"mod-bob\"}");
        dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/resolve",
                "{\"action\":\"ban\",\"reason\":\"2d ban\"}");
        String appealId = dispatch(HttpMethod.POST, "/api/appeals",
                "{\"caseId\":\"" + caseId + "\",\"appellantId\":\"player-12\","
                        + "\"reason\":\"appeal ban\"}")
                .asJson().get("appealId").getAsString();
        // Reviewer is "admin" (the SUPER_ADMIN token subject), not mod-bob.
        Response resp = dispatch(HttpMethod.POST, "/api/appeals/" + appealId + "/review",
                "{\"decision\":\"APPROVED\",\"note\":\"upheld\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.asJson().get("status").getAsString()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("POST /api/appeals/{id}/review by the case moderator returns 403 (self-review isolation)")
    void reviewAppealSelfReviewReturns403() {
        // Assign to "admin" (the test token's subject) then attempt to review
        // the resulting appeal as that same "admin" — the hard 403 fires.
        String caseId = dispatch(HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-13\",\"reasonText\":\"x\"}")
                .asJson().get("caseId").getAsString();
        dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/assign",
                "{\"moderator\":\"admin\"}");
        dispatch(HttpMethod.POST, "/api/moderation/cases/" + caseId + "/resolve",
                "{\"action\":\"kick\",\"reason\":\"kick\"}");
        String appealId = dispatch(HttpMethod.POST, "/api/appeals",
                "{\"caseId\":\"" + caseId + "\",\"appellantId\":\"player-13\","
                        + "\"reason\":\"appeal\"}")
                .asJson().get("appealId").getAsString();
        Response resp = dispatch(HttpMethod.POST, "/api/appeals/" + appealId + "/review",
                "{\"decision\":\"DENIED\",\"note\":\"upheld\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    // ====================== pagination offset overflow ======================

    /**
     * Builds a handler wired like the production one (moderation manager +
     * audit store over the shared MemoryProvider) so the audit/cases/appeals
     * pagination endpoints are exercisable. The default setUp() handler has no
     * AuditStore, and GET /api/audit 503s without one.
     */
    private RestApiHandler newFullyWiredHandler() {
        com.nova.link.audit.AuditStore auditStore = new com.nova.link.audit.AuditStore(db);
        RestApiHandler wired = new RestApiHandler(
                jwtService,
                new AuthManager(new IpBanManager(5, 60000)),
                channelManager,
                playerStateManager,
                messageRouter,
                new WebhookManager(),
                muteManager,
                banManager,
                invitationManager,
                configManager,
                networkHandler,
                consoleCommandHandler,
                notificationStore,
                auditStore,
                java.util.List.of("*")
        );
        wired.setModerationManager(
                new com.nova.link.moderation.ModerationManager(db, auditStore));
        return wired;
    }

    @Test
    @DisplayName("GET /api/audit with an overflowing page returns 200 with an empty list, not a negative OFFSET")
    void auditListingOverflowPageReturnsEmptyPage() {
        RestApiHandler wired = newFullyWiredHandler();
        // (page-1)*size = 21474837*100 = 2147483700 overflows int and wraps to
        // -2147483596; the endpoint must return an empty page (HTTP 200),
        // never pass the wrapped negative value into the store.
        Response resp = dispatch(wired, HttpMethod.GET, "/api/audit?page=21474838&size=100", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.getAsJsonArray("items")).isEmpty();

        // Regression: a normal page still lists events (the moderation manager
        // records its own audit on the filed case).
        dispatch(wired, HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-of\",\"reasonText\":\"overflow fixture\"}");
        Response normal = dispatch(wired, HttpMethod.GET, "/api/audit?page=1&size=10", null);
        assertThat(normal.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(normal.asJson().getAsJsonArray("items").size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("GET /api/audit with a huge in-range page returns 200 with an empty list")
    void auditListingOutOfRangePageReturnsEmptyPage() {
        RestApiHandler wired = newFullyWiredHandler();
        // offset 2147483*100 = 214,748,300 fits an int but exceeds any real
        // result count; the endpoint must return 200 + empty items.
        Response resp = dispatch(wired, HttpMethod.GET, "/api/audit?page=2147484&size=100", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.getAsJsonArray("items")).isEmpty();
    }

    @Test
    @DisplayName("GET /api/moderation/cases with an overflowing page returns 200 with an empty list")
    void casesListingOverflowPageReturnsEmptyPage() {
        RestApiHandler wired = newFullyWiredHandler();
        dispatch(wired, HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-cf\",\"reasonText\":\"fixture\"}");

        Response resp = dispatch(wired, HttpMethod.GET, "/api/moderation/cases?page=21474838&size=100", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.getAsJsonArray("items")).isEmpty();

        // Regression: page 1 still lists the filed case.
        Response normal = dispatch(wired, HttpMethod.GET, "/api/moderation/cases?page=1&size=10", null);
        assertThat(normal.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject body = normal.asJson();
        assertThat(body.getAsJsonArray("items").size()).isGreaterThan(0);
        assertThat(body.get("total").getAsInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/appeals with an overflowing page returns 200 with an empty list")
    void appealsListingOverflowPageReturnsEmptyPage() {
        RestApiHandler wired = newFullyWiredHandler();
        Response resp = dispatch(wired, HttpMethod.GET, "/api/appeals?page=21474838&size=100", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.getAsJsonArray("items")).isEmpty();

        // Regression: a normal page still works after the change.
        String caseId = dispatch(wired, HttpMethod.POST, "/api/reports",
                "{\"reportedPlayerId\":\"player-af\",\"reasonText\":\"r\"}")
                .asJson().get("caseId").getAsString();
        dispatch(wired, HttpMethod.POST, "/api/moderation/cases/" + caseId + "/resolve",
                "{\"action\":\"warn\",\"reason\":\"warning issued\"}");
        dispatch(wired, HttpMethod.POST, "/api/appeals",
                "{\"caseId\":\"" + caseId + "\",\"appellantId\":\"player-af\","
                        + "\"reason\":\"please review\"}");
        Response normal = dispatch(wired, HttpMethod.GET, "/api/appeals?page=1&size=10", null);
        assertThat(normal.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(normal.asJson().getAsJsonArray("items").size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("GET /api/messages with an overflowing page returns 200 with an empty list")
    void messagesListingOverflowPageReturnsEmptyPage() throws Exception {
        // The default handler has no MessageLogService wired; use the same
        // settings-handler harness as updateSettingsAppliesAndPersistsWithoutReload.
        java.nio.file.Path configPath = tempDir.resolve("novalink-msg.yml");
        ConfigManager liveConfigManager = new ConfigManager(configPath);
        liveConfigManager.load();
        RestApiHandler messagesHandler = new RestApiHandler(
                jwtService,
                new AuthManager(new IpBanManager(5, 60000)),
                channelManager,
                playerStateManager,
                messageRouter,
                new WebhookManager(),
                muteManager,
                banManager,
                invitationManager,
                liveConfigManager,
                networkHandler,
                consoleCommandHandler,
                notificationStore
        );
        com.nova.link.log.MessageLogService messageLogService =
                new com.nova.link.log.MessageLogService(db, 30);
        messagesHandler.setMessageLogService(messageLogService);

        Response resp = dispatch(messagesHandler, HttpMethod.GET,
                "/api/messages?page=21474838&size=100", null);
        assertThat(resp.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject json = resp.asJson();
        assertThat(json.getAsJsonArray("items")).isEmpty();

        // Regression: a normal page still works (empty log => 200 with empty
        // items; the overflow request must behave identically, not 500).
        Response normal = dispatch(messagesHandler, HttpMethod.GET,
                "/api/messages?page=1&size=50", null);
        assertThat(normal.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(normal.asJson().getAsJsonArray("items")).isEmpty();
    }
}
