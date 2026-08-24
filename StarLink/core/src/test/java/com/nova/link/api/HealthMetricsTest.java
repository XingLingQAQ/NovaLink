package com.nova.link.api;

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
 * Tests for the §11.7 monitoring endpoints: GET /api/health (unauthenticated
 * liveness/readiness JSON) and GET /api/metrics (auth-gated Prometheus text).
 *
 * <p>Backs a real {@link RestApiHandler} with real managers (MemoryProvider DB)
 * so the full Netty dispatch + lazy {@link HealthMetricsService} assembly is
 * exercised. The {@link ServerNetworkHandler} is mocked with a captured
 * authenticated connection so the authenticated-count metric is non-zero.
 */
@DisplayName("GET /api/health + GET /api/metrics (§11.7)")
class HealthMetricsTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    private RestApiHandler handler;
    private JwtService jwtService;
    private MuteManager muteManager;
    private BanManager banManager;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseProvider db = new MemoryProvider();
        db.initialize();

        ChannelManager channelManager = new ChannelManager();
        PlayerStateManager playerStateManager = new PlayerStateManager(db);
        WebhookManager webhookManager = new WebhookManager();
        PermissionManager permissionManager = new PermissionManager();
        muteManager = new MuteManager(db, permissionManager, channelManager);
        banManager = new BanManager(db, permissionManager, channelManager);
        NotificationStore notificationStore = new NotificationStore(db);
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        SensitiveWordFilter sensitiveWordFilter = new SensitiveWordFilter();
        ConfigManager configManager = new ConfigManager(
                java.nio.file.Path.of("novalink-health-test.yml"));

        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("staff")
                .displayName("Staff")
                .scope(ChannelScope.GLOBAL)
                .build());

        ServerNetworkHandler networkHandler = mock(ServerNetworkHandler.class);
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(networkHandler.getConnectionCount()).thenReturn(1);
        when(networkHandler.getConnections()).thenReturn(java.util.Set.of(connection));
        // §11.6 Project 17: prometheusMetrics() iterates getDropCounts() and
        // getDropCountTotal(); on a Mockito mock these return null/0 by default,
        // which would NPE inside new ArrayList<>(dropCounts.keySet()). Stub
        // empty so the packets_dropped_total block emits only the {packet_id="total"} aggregate.
        when(networkHandler.getDropCounts()).thenReturn(java.util.Map.of());
        when(networkHandler.getDropCountTotal()).thenReturn(0L);
        // Queue-depth gauges: stub non-zero capacities so the assertions are meaningful.
        when(networkHandler.getControlQueueDepth()).thenReturn(0);
        when(networkHandler.getControlQueueCapacity()).thenReturn(1024);
        when(networkHandler.getMessageQueueDepth()).thenReturn(0);
        when(networkHandler.getMessageQueueCapacity()).thenReturn(10000);

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
        handler.setAnnouncementManager(
                new com.nova.link.announcement.AnnouncementManager(permissionManager, channelManager));
        // §11.6 Project 17: wire a WebSocketGateway so the ws_sessions_active
        // metric block is emitted. A bare mock is sufficient — its
        // getSessionCount() returns 0 (Mockito default for int), which is the
        // correct empty-state value for the metric.
        handler.setWebSocketGateway(mock(WebSocketGateway.class));

        adminToken = jwtService.generateToken(UUID.randomUUID().toString(), "admin", "SUPER_ADMIN");
    }

    @AfterEach
    void tearDown() {
        muteManager.shutdown();
        banManager.shutdown();
    }

    // ====================== helpers ======================

    private Response dispatch(String token, HttpMethod method, String uri) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri, Unpooled.EMPTY_BUFFER);
        if (token != null) {
            request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
        }

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        // RestApiHandler.channelRead0 stores the per-request id as a channel
        // attribute, so the mock must return a real channel that supports
        // AttributeMap. An EmbeddedChannel is the lightest such implementation.
        when(ctx.channel()).thenReturn(new io.netty.channel.embedded.EmbeddedChannel());
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
            String body = response.content().toString(StandardCharsets.UTF_8);
            return new Response(response.status(), body,
                    response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        }
        return new Response(null, "", null);
    }

    private record Response(HttpResponseStatus status, String body, String contentType) {
        com.google.gson.JsonObject asJson() {
            return com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        }
    }

    // ====================== /api/health ======================

    @Test
    @DisplayName("GET /api/health returns 200 without any auth token")
    void healthIsUnauthenticated() {
        Response resp = dispatch(null, HttpMethod.GET, "/api/health");
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.contentType()).contains("application/json");
    }

    @Test
    @DisplayName("GET /api/health reports up status and version")
    void healthReportsUpStatus() {
        Response resp = dispatch(null, HttpMethod.GET, "/api/health");
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
        com.google.gson.JsonObject json = resp.asJson();
        // All deps are wired in setUp, DB is the in-memory provider (connected) → up.
        assertThat(json.get("status").getAsString()).isEqualTo("up");
        assertThat(json.get("version").getAsString()).isEqualTo("1.0.0");
        assertThat(json.has("uptimeMillis")).isTrue();
        assertThat(json.has("timestamp")).isTrue();
    }

    @Test
    @DisplayName("GET /api/health includes connection/channel/announcement checks")
    void healthIncludesSubsystemChecks() {
        Response resp = dispatch(null, HttpMethod.GET, "/api/health");
        com.google.gson.JsonObject json = resp.asJson();
        com.google.gson.JsonObject checks = json.getAsJsonObject("checks");

        com.google.gson.JsonObject connections = checks.getAsJsonObject("connections");
        assertThat(connections.get("healthy").getAsBoolean()).isTrue();
        assertThat(connections.get("physical").getAsInt()).isEqualTo(1);
        assertThat(connections.get("authenticated").getAsInt()).isEqualTo(1);

        com.google.gson.JsonObject channels = checks.getAsJsonObject("channels");
        assertThat(channels.get("healthy").getAsBoolean()).isTrue();
        assertThat(channels.get("total").getAsInt()).isEqualTo(1);

        assertThat(checks.has("announcements")).isTrue();
        assertThat(checks.has("database")).isTrue();
        assertThat(checks.getAsJsonObject("database").get("healthy").getAsBoolean()).isTrue();

        // §11.6 Project 17: queues / ws / config sub-checks are present.
        com.google.gson.JsonObject queues = checks.getAsJsonObject("queues");
        assertThat(queues).isNotNull();
        assertThat(queues.get("healthy").getAsBoolean()).isTrue();
        assertThat(queues.get("controlQueueDepth").getAsInt()).isZero();
        assertThat(queues.get("controlQueueCapacity").getAsInt()).isEqualTo(1024);
        assertThat(queues.get("messageQueueDepth").getAsInt()).isZero();
        assertThat(queues.get("messageQueueCapacity").getAsInt()).isEqualTo(10000);
        assertThat(queues.get("packetsDroppedTotal").getAsLong()).isZero();

        com.google.gson.JsonObject ws = checks.getAsJsonObject("ws");
        assertThat(ws).isNotNull();
        assertThat(ws.get("healthy").getAsBoolean()).isTrue();
        assertThat(ws.get("sessionsActive").getAsInt()).isZero();

        com.google.gson.JsonObject cfg = checks.getAsJsonObject("config");
        assertThat(cfg).isNotNull();
        assertThat(cfg.get("healthy").getAsBoolean()).isTrue();
        assertThat(cfg.get("revision").getAsLong()).isZero();
    }

    @Test
    @DisplayName("GET /api/health omits secrets, passwords, and webhook URLs")
    void healthDoesNotLeakSecrets() {
        Response resp = dispatch(null, HttpMethod.GET, "/api/health");
        String body = resp.body.toLowerCase();
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("secret");
        assertThat(body).doesNotContain("token");
        assertThat(body).doesNotContain("webhookurl");
        assertThat(body).doesNotContain("url");
    }

    // ====================== /api/metrics ======================

    @Test
    @DisplayName("GET /api/metrics returns 401 without a token")
    void metricsRequiresAuth() {
        Response resp = dispatch(null, HttpMethod.GET, "/api/metrics");
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /api/metrics returns Prometheus text with an admin token")
    void metricsReturnsPrometheusText() {
        Response resp = dispatch(adminToken, HttpMethod.GET, "/api/metrics");
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.contentType()).contains("text/plain");
        assertThat(resp.contentType()).contains("version=0.0.4");
        String body = resp.body;
        assertThat(body).contains("# TYPE nova_link_uptime_seconds gauge");
        assertThat(body).contains("nova_link_uptime_seconds ");
        assertThat(body).contains("# TYPE nova_link_connections_active gauge");
        assertThat(body).contains("nova_link_connections_active{state=\"physical\"} 1");
        assertThat(body).contains("nova_link_connections_active{state=\"authenticated\"} 1");
        assertThat(body).contains("# TYPE nova_link_channels_total gauge");
        assertThat(body).contains("nova_link_channels_total 1");
        assertThat(body).contains("# TYPE nova_link_db_alive gauge");
        assertThat(body).contains("nova_link_db_alive 1");
    }

    @Test
    @DisplayName("GET /api/metrics exposes webhook delivery counters")
    void metricsExposesWebhookCounters() {
        Response resp = dispatch(adminToken, HttpMethod.GET, "/api/metrics");
        String body = resp.body;
        assertThat(body).contains("# TYPE nova_link_webhook_deliveries_total counter");
        assertThat(body).contains("nova_link_webhook_deliveries_total{result=\"accepted\"} 0");
        assertThat(body).contains("nova_link_webhook_deliveries_total{result=\"succeeded\"} 0");
        assertThat(body).contains("nova_link_webhook_deliveries_total{result=\"failed\"} 0");
        assertThat(body).contains("# TYPE nova_link_webhook_delivery_queue_depth gauge");
        assertThat(body).contains("# TYPE nova_link_webhook_pending_retries gauge");
    }

    @Test
    @DisplayName("GET /api/metrics exposes §11.6 Project 17 queue/drop/ws/config metrics")
    void metricsExposesObservabilityMetrics() {
        Response resp = dispatch(adminToken, HttpMethod.GET, "/api/metrics");
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
        String body = resp.body;

        // Control-plane queue depth + capacity.
        assertThat(body).contains("# HELP nova_link_control_queue_depth");
        assertThat(body).contains("# TYPE nova_link_control_queue_depth gauge");
        assertThat(body).contains("nova_link_control_queue_depth 0");
        assertThat(body).contains("# TYPE nova_link_control_queue_capacity gauge");
        assertThat(body).contains("nova_link_control_queue_capacity 1024");

        // Message-plane queue depth + capacity.
        assertThat(body).contains("# TYPE nova_link_message_queue_depth gauge");
        assertThat(body).contains("nova_link_message_queue_depth 0");
        assertThat(body).contains("# TYPE nova_link_message_queue_capacity gauge");
        assertThat(body).contains("nova_link_message_queue_capacity 10000");

        // Packet drop counter: at minimum the {packet_id="total"} aggregate
        // is always emitted (even when no drops have been recorded).
        assertThat(body).contains("# TYPE nova_link_packets_dropped_total counter");
        assertThat(body).contains("nova_link_packets_dropped_total{packet_id=\"total\"} 0");

        // WebSocket active sessions gauge (wired via setWebSocketGateway in setUp).
        assertThat(body).contains("# TYPE nova_link_ws_sessions_active gauge");
        assertThat(body).contains("nova_link_ws_sessions_active 0");

        // Settings revision gauge (ConfigManager is wired; revision is 0 in a
        // fresh test since no settings mutation has run).
        assertThat(body).contains("# TYPE nova_link_config_revision gauge");
        assertThat(body).contains("nova_link_config_revision 0");
    }

    @Test
    @DisplayName("GET /api/metrics allows VIEWER (read-only) tokens")
    void metricsAllowsViewer() {
        String viewerToken = jwtService.generateToken("viewer1", "viewer1", "VIEWER");
        Response resp = dispatch(viewerToken, HttpMethod.GET, "/api/metrics");
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(resp.body).contains("# TYPE nova_link_uptime_seconds gauge");
    }

    @Test
    @DisplayName("GET /api/metrics is reachable while /api/health stays unauthenticated")
    void healthAndMetricsAreSeparate() {
        Response healthNoToken = dispatch(null, HttpMethod.GET, "/api/health");
        assertThat(healthNoToken.status()).isEqualTo(HttpResponseStatus.OK);

        Response metricsNoToken = dispatch(null, HttpMethod.GET, "/api/metrics");
        assertThat(metricsNoToken.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);

        Response metricsWithToken = dispatch(adminToken, HttpMethod.GET, "/api/metrics");
        assertThat(metricsWithToken.status()).isEqualTo(HttpResponseStatus.OK);
    }
}
