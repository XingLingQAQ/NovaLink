package com.nova.link.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PanelRole;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
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
import com.nova.link.network.NettyServer;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.notification.NotificationStore;
import com.nova.link.spy.SpyManager;
import com.nova.link.websocket.JwtService;
import com.nova.link.websocket.WebSocketGateway;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * RBAC permission-matrix tests for {@link RestApiHandler} (front/back-end
 * contract: VIEWER &lt; ADMIN &lt; SUPER_ADMIN) plus CORS whitelist behavior.
 */
@DisplayName("RestApiHandler RBAC matrix + CORS")
class RestApiRbacTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    private RestApiHandler handler;
    private JwtService jwtService;
    private ChannelManager channelManager;
    private MuteManager muteManager;
    private BanManager banManager;
    private DatabaseProvider db;

    private UUID targetId;
    private String viewerToken;
    private String adminToken;
    private String superAdminToken;
    private String legacyRoleToken;

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
        ConfigManager configManager = new ConfigManager(java.nio.file.Path.of("novalink-rbac-test.yml"));

        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("staff")
                .displayName("Staff")
                .scope(ChannelScope.GLOBAL)
                .build());

        ServerNetworkHandler networkHandler = mock(ServerNetworkHandler.class);
        com.nova.link.network.ClientConnection connection = mock(com.nova.link.network.ClientConnection.class);
        when(connection.getClientId()).thenReturn("Survival");
        when(connection.close()).thenReturn(CompletableFuture.completedFuture(null));
        when(networkHandler.findByClientId("Survival")).thenReturn(connection);

        MessageRouter messageRouter = new MessageRouter(channelManager, networkHandler);
        messageRouter.setMuteManager(muteManager);
        messageRouter.setSensitiveWordFilter(new SensitiveWordFilter());
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
                new SensitiveWordFilter(),
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

        viewerToken = jwtService.generateToken("viewer1", "viewer1", "VIEWER");
        adminToken = jwtService.generateToken("mod", "mod", "ADMIN");
        superAdminToken = jwtService.generateToken("root", "root", "SUPER_ADMIN");
        // Legacy role from pre-RBAC tokens: must not satisfy any matrix level.
        legacyRoleToken = jwtService.generateToken("legacy", "legacy", "CLIENT_ADMIN");

        targetId = UUID.randomUUID();
        PlayerState state = playerStateManager.getOrCreateState(targetId, "Steve");
        state.setActiveChannel("staff");
        channelManager.addMember("staff", targetId);
    }

    @AfterEach
    void tearDown() {
        muteManager.shutdown();
        banManager.shutdown();
    }

    // ====================== helpers ======================

    private Response dispatch(String token, HttpMethod method, String uri, String body) {
        return dispatch(handler, token, method, uri, body, null);
    }

    private Response dispatch(RestApiHandler targetHandler, String token, HttpMethod method,
                              String uri, String body, String origin) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri,
                body != null ? Unpooled.copiedBuffer(body, CharsetUtil.UTF_8) : Unpooled.EMPTY_BUFFER);
        if (token != null) {
            request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
        }
        if (origin != null) {
            request.headers().set(HttpHeaderNames.ORIGIN, origin);
        }

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        AtomicReference<Object> captured = new AtomicReference<>();
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
        if (resp instanceof FullHttpResponse response) {
            return new Response(response.status(), response.content().toString(StandardCharsets.UTF_8),
                    response.headers());
        }
        return new Response(null, "", null);
    }

    private record Response(HttpResponseStatus status, String body, HttpHeaders headers) {
        JsonObject asJson() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    // ====================== requiredRole matrix (unit) ======================

    @Nested
    @DisplayName("requiredRole matrix")
    class RequiredRoleMatrix {

        @Test
        @DisplayName("all GET endpoints require only VIEWER")
        void getsAreViewer() {
            for (String path : List.of("/api/channels", "/api/players", "/api/mutes", "/api/bans",
                    "/api/webhooks", "/api/settings", "/api/notifications", "/api/status",
                    "/api/channels/staff/members", "/api/players/x",
                    "/api/messages", "/api/announcements", "/api/filter")) {
                assertThat(RestApiHandler.requiredRole(path, HttpMethod.GET))
                        .as("GET %s", path)
                        .isEqualTo(PanelRole.VIEWER);
            }
        }

        @Test
        @DisplayName("player punishments, channel CRUD, messages, notifications require ADMIN")
        void adminEndpoints() {
            assertThat(RestApiHandler.requiredRole("/api/players/u/mute", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/players/u/unmute", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/players/u/kick", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/players/u/ban", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/players/u/unban", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/channels", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/channels/staff", HttpMethod.PUT)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/channels/staff", HttpMethod.DELETE)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/channels/staff/invite", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/messages", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/notifications/1/read", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/notifications/read-all", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/notifications", HttpMethod.DELETE)).isEqualTo(PanelRole.ADMIN);
        }

        @Test
        @DisplayName("announcement management and filter updates require ADMIN")
        void announcementAndFilterEndpoints() {
            assertThat(RestApiHandler.requiredRole("/api/announcements", HttpMethod.POST)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/announcements/a1", HttpMethod.PUT)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/announcements/a1", HttpMethod.DELETE)).isEqualTo(PanelRole.ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/filter", HttpMethod.PUT)).isEqualTo(PanelRole.ADMIN);
        }

        @Test
        @DisplayName("console, client disconnect, reload, settings, webhooks require SUPER_ADMIN")
        void superAdminEndpoints() {
            assertThat(RestApiHandler.requiredRole("/api/console", HttpMethod.POST)).isEqualTo(PanelRole.SUPER_ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/clients/Survival", HttpMethod.DELETE)).isEqualTo(PanelRole.SUPER_ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/reload", HttpMethod.POST)).isEqualTo(PanelRole.SUPER_ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/settings", HttpMethod.PUT)).isEqualTo(PanelRole.SUPER_ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/webhooks", HttpMethod.POST)).isEqualTo(PanelRole.SUPER_ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/webhooks/some-id", HttpMethod.DELETE)).isEqualTo(PanelRole.SUPER_ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/webhooks/some-id", HttpMethod.PUT)).isEqualTo(PanelRole.SUPER_ADMIN);
            assertThat(RestApiHandler.requiredRole("/api/webhooks/some-id/test", HttpMethod.POST)).isEqualTo(PanelRole.SUPER_ADMIN);
        }
    }

    // ====================== VIEWER ======================

    @Nested
    @DisplayName("VIEWER")
    class ViewerRole {

        @Test
        @DisplayName("can call GET endpoints")
        void viewerCanRead() {
            assertThat(dispatch(viewerToken, HttpMethod.GET, "/api/channels", null).status())
                    .isEqualTo(HttpResponseStatus.OK);
            assertThat(dispatch(viewerToken, HttpMethod.GET, "/api/players", null).status())
                    .isEqualTo(HttpResponseStatus.OK);
            assertThat(dispatch(viewerToken, HttpMethod.GET, "/api/mutes", null).status())
                    .isEqualTo(HttpResponseStatus.OK);
            assertThat(dispatch(viewerToken, HttpMethod.GET, "/api/status", null).status())
                    .isEqualTo(HttpResponseStatus.OK);
        }

        @Test
        @DisplayName("gets 403 for mute (punishments are ADMIN+)")
        void viewerCannotMute() {
            Response resp = dispatch(viewerToken, HttpMethod.POST,
                    "/api/players/" + targetId + "/mute",
                    "{\"channelId\":\"staff\",\"durationMs\":60000}");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
            assertThat(muteManager.isMuted(targetId, "staff")).isFalse();
        }

        @Test
        @DisplayName("gets 403 for channel create / message send / notification management")
        void viewerCannotMutate() {
            assertThat(dispatch(viewerToken, HttpMethod.POST, "/api/channels",
                    "{\"displayName\":\"X\",\"scope\":\"global\"}").status())
                    .isEqualTo(HttpResponseStatus.FORBIDDEN);
            assertThat(dispatch(viewerToken, HttpMethod.POST, "/api/messages",
                    "{\"channelId\":\"staff\",\"content\":\"hi\"}").status())
                    .isEqualTo(HttpResponseStatus.FORBIDDEN);
            assertThat(dispatch(viewerToken, HttpMethod.DELETE, "/api/notifications", null).status())
                    .isEqualTo(HttpResponseStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("gets 403 for announcement create and filter update (ADMIN+)")
        void viewerCannotManageAnnouncementsOrFilter() {
            assertThat(dispatch(viewerToken, HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"JOIN\",\"channelId\":\"staff\",\"content\":\"x\"}").status())
                    .isEqualTo(HttpResponseStatus.FORBIDDEN);
            assertThat(dispatch(viewerToken, HttpMethod.PUT, "/api/filter",
                    "{\"words\":[\"x\"]}").status())
                    .isEqualTo(HttpResponseStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("gets 403 for SUPER_ADMIN endpoints")
        void viewerCannotSuperAdmin() {
            assertThat(dispatch(viewerToken, HttpMethod.POST, "/api/console",
                    "{\"command\":\"status\"}").status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
            assertThat(dispatch(viewerToken, HttpMethod.POST, "/api/reload", null).status())
                    .isEqualTo(HttpResponseStatus.FORBIDDEN);
        }
    }

    // ====================== ADMIN ======================

    @Nested
    @DisplayName("ADMIN")
    class AdminRole {

        @Test
        @DisplayName("can read and punish players")
        void adminCanPunish() {
            assertThat(dispatch(adminToken, HttpMethod.GET, "/api/channels", null).status())
                    .isEqualTo(HttpResponseStatus.OK);

            Response resp = dispatch(adminToken, HttpMethod.POST,
                    "/api/players/" + targetId + "/mute",
                    "{\"channelId\":\"staff\",\"durationMs\":60000,\"reason\":\"spam\"}");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(muteManager.isMuted(targetId, "staff")).isTrue();

            assertThat(dispatch(adminToken, HttpMethod.POST,
                    "/api/players/" + targetId + "/unmute", "{\"channelId\":\"staff\"}").status())
                    .isEqualTo(HttpResponseStatus.OK);
            assertThat(muteManager.isMuted(targetId, "staff")).isFalse();
        }

        @Test
        @DisplayName("can manage channels and send messages")
        void adminCanManageChannels() {
            Response resp = dispatch(adminToken, HttpMethod.POST, "/api/channels",
                    "{\"displayName\":\"AdminChannel\",\"scope\":\"global\"}");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.CREATED);

            assertThat(dispatch(adminToken, HttpMethod.POST, "/api/messages",
                    "{\"channelId\":\"staff\",\"content\":\"hello\"}").status())
                    .isEqualTo(HttpResponseStatus.OK);
        }

        @Test
        @DisplayName("gets 403 for console (SUPER_ADMIN only)")
        void adminCannotConsole() {
            Response resp = dispatch(adminToken, HttpMethod.POST, "/api/console",
                    "{\"command\":\"status\"}");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("gets 403 for client disconnect / reload / settings / webhook create")
        void adminCannotSuperAdminEndpoints() {
            assertThat(dispatch(adminToken, HttpMethod.DELETE, "/api/clients/Survival", null).status())
                    .isEqualTo(HttpResponseStatus.FORBIDDEN);
            assertThat(dispatch(adminToken, HttpMethod.POST, "/api/reload", null).status())
                    .isEqualTo(HttpResponseStatus.FORBIDDEN);
            assertThat(dispatch(adminToken, HttpMethod.PUT, "/api/settings",
                    "{\"filterEnabled\":false}").status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
            assertThat(dispatch(adminToken, HttpMethod.POST, "/api/webhooks",
                    "{\"url\":\"http://example.com\",\"event\":\"message.sent\"}").status())
                    .isEqualTo(HttpResponseStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("gets 403 for webhook update and test (SUPER_ADMIN only)")
        void adminCannotUpdateOrTestWebhooks() {
            assertThat(dispatch(adminToken, HttpMethod.PUT, "/api/webhooks/some-id",
                    "{\"active\":false}").status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
            assertThat(dispatch(adminToken, HttpMethod.POST, "/api/webhooks/some-id/test", null).status())
                    .isEqualTo(HttpResponseStatus.FORBIDDEN);
        }
    }

    // ====================== SUPER_ADMIN ======================

    @Nested
    @DisplayName("SUPER_ADMIN")
    class SuperAdminRole {

        @Test
        @DisplayName("passes the whole matrix (read, punish, console, webhooks, client disconnect)")
        void superAdminFullAccess() {
            assertThat(dispatch(superAdminToken, HttpMethod.GET, "/api/channels", null).status())
                    .isEqualTo(HttpResponseStatus.OK);

            assertThat(dispatch(superAdminToken, HttpMethod.POST,
                    "/api/players/" + targetId + "/mute",
                    "{\"channelId\":\"staff\",\"durationMs\":60000}").status())
                    .isEqualTo(HttpResponseStatus.OK);

            Response console = dispatch(superAdminToken, HttpMethod.POST, "/api/console",
                    "{\"command\":\"status\"}");
            assertThat(console.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(console.asJson().get("output").getAsString()).isNotBlank();

            assertThat(dispatch(superAdminToken, HttpMethod.POST, "/api/webhooks",
                    "{\"url\":\"http://example.com\",\"event\":\"message.sent\"}").status())
                    .isEqualTo(HttpResponseStatus.CREATED);

            assertThat(dispatch(superAdminToken, HttpMethod.DELETE, "/api/clients/Survival", null).status())
                    .isEqualTo(HttpResponseStatus.OK);
        }
    }

    // ====================== legacy / unknown roles ======================

    @Test
    @DisplayName("legacy CLIENT_ADMIN role tokens are rejected with 403 everywhere")
    void legacyRoleRejected() {
        assertThat(dispatch(legacyRoleToken, HttpMethod.GET, "/api/channels", null).status())
                .isEqualTo(HttpResponseStatus.FORBIDDEN);
        assertThat(dispatch(legacyRoleToken, HttpMethod.POST,
                "/api/players/" + targetId + "/mute", "{}").status())
                .isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    // ====================== CORS ======================

    @Nested
    @DisplayName("CORS whitelist")
    class Cors {

        @Test
        @DisplayName("resolveCorsOrigin: '*' whitelist allows all")
        void wildcardAllowsAll() {
            assertThat(RestApiHandler.resolveCorsOrigin(List.of("*"), "http://evil.example")).isEqualTo("*");
            assertThat(RestApiHandler.resolveCorsOrigin(List.of("*"), null)).isEqualTo("*");
            assertThat(RestApiHandler.resolveCorsOrigin(null, "http://a.example")).isEqualTo("*");
        }

        @Test
        @DisplayName("resolveCorsOrigin: explicit list echoes only matching origins")
        void explicitListMatches() {
            List<String> allowed = List.of("https://panel.example.com", "http://localhost:5173");
            assertThat(RestApiHandler.resolveCorsOrigin(allowed, "https://panel.example.com"))
                    .isEqualTo("https://panel.example.com");
            // Case-insensitive + trailing slash tolerant.
            assertThat(RestApiHandler.resolveCorsOrigin(allowed, "https://PANEL.example.com/"))
                    .isEqualTo("https://PANEL.example.com/");
            assertThat(RestApiHandler.resolveCorsOrigin(allowed, "https://evil.example.com")).isNull();
            assertThat(RestApiHandler.resolveCorsOrigin(allowed, null)).isNull();
        }

        @Test
        @DisplayName("handler with explicit whitelist echoes matching Origin and omits headers otherwise")
        void handlerEchoesMatchingOrigin() {
            RestApiHandler corsHandler = new RestApiHandler(
                    jwtService,
                    new AuthManager(new IpBanManager(5, 60000)),
                    channelManager,
                    new PlayerStateManager(db),
                    null, null, muteManager, banManager, null,
                    null, null, null, null,
                    List.of("http://localhost:5173"));

            Response match = dispatch(corsHandler, superAdminToken, HttpMethod.GET,
                    "/api/status", null, "http://localhost:5173");
            assertThat(match.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(match.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isEqualTo("http://localhost:5173");
            assertThat(match.headers().getAll(HttpHeaderNames.VARY)).contains("Origin");

            Response mismatch = dispatch(corsHandler, superAdminToken, HttpMethod.GET,
                    "/api/status", null, "http://evil.example.com");
            assertThat(mismatch.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(mismatch.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        }

        @Test
        @DisplayName("default handler keeps the legacy '*' behavior")
        void defaultHandlerAllowsAll() {
            Response resp = dispatch(handler, superAdminToken, HttpMethod.GET,
                    "/api/status", null, "http://anywhere.example");
            assertThat(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("*");
        }
    }
}
