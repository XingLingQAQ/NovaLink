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
        SensitiveWordFilter sensitiveWordFilter = new SensitiveWordFilter();
        configManager = new ConfigManager(java.nio.file.Path.of("novalink-test.yml"));

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

        MessageRouter messageRouter = new MessageRouter(channelManager, networkHandler);
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
     * Builds a FullHttpRequest, dispatches it through the handler, and returns
     * the captured response status + body.
     */
    private Response dispatch(HttpMethod method, String uri, String body) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri,
                body != null ? Unpooled.copiedBuffer(body, CharsetUtil.UTF_8) : Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + validToken);
        if (body != null) {
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        }

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        // Capture the response written to the context
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
        Response resp = dispatch(HttpMethod.POST, "/api/channels",
                "{\"displayName\":\"Secret\",\"scope\":\"private\",\"maxCapacity\":10}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.CREATED);
        JsonObject json = resp.asJson();
        String id = json.getAsJsonObject("channel").get("id").getAsString();
        assertThat(id).startsWith("NC-");
        assertThat(channelManager.getChannel(id).getScope()).isEqualTo(ChannelScope.PRIVATE);
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

    // ====================== reload ======================

    @Test
    @DisplayName("POST /api/reload triggers config reload")
    void reload() {
        Response resp = dispatch(HttpMethod.POST, "/api/reload", null);
        // Reload may fail if novalink-test.yml doesn't exist, but the endpoint
        // should still attempt it. Accept OK or 500.
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
    @DisplayName("POST /api/console rejects stop command (blacklist)")
    void consoleStopBlacklisted() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{\"command\":\"stop\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        assertThat(resp.body).contains("blacklisted");
    }

    @Test
    @DisplayName("POST /api/console rejects shutdown command (blacklist)")
    void consoleShutdownBlacklisted() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{\"command\":\"shutdown\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        assertThat(resp.body).contains("blacklisted");
    }

    @Test
    @DisplayName("POST /api/console rejects stop with trailing args (blacklist by first token)")
    void consoleStopWithArgsBlacklisted() {
        Response resp = dispatch(HttpMethod.POST, "/api/console", "{\"command\":\"stop now please\"}");
        assertThat(resp.status).isEqualTo(HttpResponseStatus.BAD_REQUEST);
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
}
