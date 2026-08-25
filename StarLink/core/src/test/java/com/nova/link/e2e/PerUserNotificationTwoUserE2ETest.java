package com.nova.link.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.announcement.AnnouncementManager;
import com.nova.link.api.RestApiHandler;
import com.nova.link.api.WebhookManager;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.ClientPermissionRegistry;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.audit.AuditStore;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.config.ConfigManager;
import com.nova.link.console.BackendContext;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.Notification;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.database.SQLiteProvider;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.moderation.ModerationManager;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.NettyServer;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.notification.NotificationStore;
import com.nova.link.spy.SpyManager;
import com.nova.link.websocket.JwtService;
import com.nova.link.websocket.WebSocketGateway;
import com.nova.link.websocket.WebSocketMessageHandler;
import com.nova.link.websocket.WebSocketSession;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * VERIFY-013 §7 — 双用户 API/WS E2E slice (per-user notification isolation).
 *
 * <p>PANEL-014 landed a per-user notification state model:
 * <ul>
 *   <li>{@code notification_read} table keyed by (notification_id, user_id);</li>
 *   <li>{@code notifications.recipient} column (null = broadcast, non-null = directed);</li>
 *   <li>{@link RestApiHandler} uses {@code panelUsername(claims)} as the per-user
 *       identity for markRead / markAllRead / clearAll / unreadCount / list.</li>
 * </ul>
 *
 * <p>This test verifies per-user notification isolation across two distinct
 * admin JWTs on three dimensions (unreadCount / archive / retention) using
 * real {@link JwtService} / {@link AuthManager} / {@link NotificationStore} /
 * {@link MemoryProvider} / {@link SQLiteProvider} and {@link EmbeddedChannel}
 * as two WS clients — no browser, no mock shells. It is the E2E companion to
 * the per-unit {@code RestApiHandlerTest.perUserMarkReadIsolatesAdmins} /
 * {@code adminCannotClearBroadcast} tests: those exercise single-axis
 * isolation against the in-memory provider; these exercise the full
 * three-axis matrix across two users end-to-end, including a real SQLite
 * persistence slice (scenario 4) modeled on
 * {@code SocialRelationsJdbcPersistenceTest}.
 *
 * <p>Scenarios:
 * <ol>
 *   <li><b>API isolation</b> — admin1 mark-read N → admin2 unreadCount
 *       unchanged + items still contain N unread; admin1 archive own
 *       directed → admin2 unaffected.</li>
 *   <li><b>WS differentiated delivery [RED]</b> — directed notification
 *       (recipient=admin1) via WS → admin1 receives, admin2 does not;
 *       broadcast → both receive. <em>Expected RED:</em> the current
 *       {@link WebSocketMessageHandler#broadcastNotification} sends to ALL
 *       authenticated sessions with NO recipient filtering, and there is no
 *       directed-delivery method. This is a per-user WS isolation defect;
 *       the test asserts the correct expected behavior and is marked RED
 *       rather than silently fixing the main source.</li>
 *   <li><b>retention/clear isolation</b> — admin1 clear own directed → does
 *       not delete admin2's; SUPER_ADMIN clear broadcast → global cleanup
 *       + audit records; normal ADMIN clear broadcast → 403.
 *       <em>Additional RED:</em> {@code NotificationStore.clearAll()} (no-arg,
 *       used by the broadcast-clear endpoint) calls
 *       {@code databaseProvider.clearNotifications()} which deletes ALL
 *       notifications (broadcast AND directed), not just broadcasts as the
 *       handler Javadoc claims. This violates per-user isolation: a
 *       SUPER_ADMIN clearing broadcasts also deletes other admins' directed
 *       notifications.</li>
 *   <li><b>read state persistence</b> — real {@link SQLiteProvider} with
 *       {@code @TempDir}; two users' read state is independently persisted
 *       across a provider reopen.</li>
 * </ol>
 *
 * <p>Hard constraints honored: only adds a test file in a new {@code e2e}
 * package; does not touch main source. Isolation defects found in scenarios 2
 * and 3 are written as failing tests and reported RED rather than silently
 * fixed. No secrets committed (the SECRET constant is a test-only signing key
 * with no production value).
 *
 * <p>Requirements: VERIFY-013 §7 — 双用户 API/WS E2E slice
 */
@DisplayName("VERIFY-013 §7: per-user notification two-user E2E isolation")
class PerUserNotificationTwoUserE2ETest {

    private static final String SECRET = "test-secret-key-for-jwt-signing-minimum-32-chars";

    @TempDir
    Path tempDir;

    private JwtService jwtService;
    private AuthManager authManager;
    private MemoryProvider db;
    private NotificationStore notificationStore;
    private ChannelManager channelManager;
    private MuteManager muteManager;
    private BanManager banManager;
    private RestApiHandler handler;
    private WebSocketMessageHandler wsHandler;
    private AuditStore auditStore;

    // Two admin tokens: admin1 = SUPER_ADMIN, admin2 = ADMIN.
    private String admin1Token;
    private String admin2Token;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();

        jwtService = new JwtService(SECRET);
        authManager = new AuthManager(new IpBanManager(5, 60_000));

        channelManager = new ChannelManager();
        PlayerStateManager playerStateManager = new PlayerStateManager(db);
        WebhookManager webhookManager = new WebhookManager();
        PermissionManager permissionManager = new PermissionManager();
        muteManager = new MuteManager(db, permissionManager, channelManager);
        banManager = new BanManager(db, permissionManager, channelManager);
        notificationStore = new NotificationStore(db);
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        SensitiveWordFilter sensitiveWordFilter = new SensitiveWordFilter();
        ConfigManager configManager = new ConfigManager(tempDir.resolve("novalink-e2e-test.yml"));

        ServerNetworkHandler networkHandler = mock(ServerNetworkHandler.class);
        MessageRouter messageRouter = new MessageRouter(channelManager, networkHandler);
        messageRouter.setMuteManager(muteManager);
        messageRouter.setSensitiveWordFilter(sensitiveWordFilter);
        messageRouter.setPermissionChecker((c, p) -> true);

        SpyManager spyManager = new SpyManager(permissionManager, channelManager, networkHandler);
        messageRouter.setSpyManager(spyManager);

        BackendContext ctx = new BackendContext(
                configManager,
                authManager,
                permissionManager,
                new ClientPermissionRegistry(),
                db,
                channelManager,
                playerStateManager,
                webhookManager,
                new PrivateChannelManager(channelManager),
                invitationManager,
                muteManager,
                banManager,
                notificationStore,
                new AnnouncementManager(permissionManager, channelManager),
                sensitiveWordFilter,
                networkHandler,
                messageRouter,
                spyManager,
                mock(NettyServer.class),
                mock(WebSocketGateway.class));
        ConsoleCommandHandler consoleCommandHandler = new ConsoleCommandHandler(ctx);

        // Full constructor with AuditStore so scenario 3 can assert audit
        // records via a real AuditStore backed by the same in-memory DB.
        auditStore = new AuditStore(db);
        handler = new RestApiHandler(
                jwtService,
                authManager,
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
                List.of("*"));
        handler.setModerationManager(new ModerationManager(db, auditStore));

        // Real WS handler backed by real JwtService/AuthManager so the two
        // EmbeddedChannel WS clients exercise the auth + broadcast path.
        wsHandler = new WebSocketMessageHandler(
                jwtService, authManager, channelManager, networkHandler, playerStateManager);

        admin1Token = jwtService.generateToken(UUID.randomUUID().toString(), "admin1", "SUPER_ADMIN");
        admin2Token = jwtService.generateToken(UUID.randomUUID().toString(), "admin2", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        muteManager.shutdown();
        banManager.shutdown();
    }

    // ====================== helpers ======================

    /**
     * Dispatch a REST request through the real {@link RestApiHandler} using
     * the given token. Captures the response written to the context.
     *
     * <p>Uses {@code handler.channelRead(ctx, request)} (public, inherited
     * from {@link io.netty.channel.SimpleChannelInboundHandler}) instead of
     * {@code channelRead0} (protected) because this test lives in a different
     * package ({@code com.nova.link.e2e}) than the handler
     * ({@code com.nova.link.api}).
     */
    private Response dispatch(String token, HttpMethod method, String uri, String body) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri,
                body != null ? Unpooled.copiedBuffer(body, StandardCharsets.UTF_8) : Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        }

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(new EmbeddedChannel());
        AtomicReference<Object> captured = new AtomicReference<>();
        ChannelPromise promise = mock(ChannelPromise.class);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return promise;
        }).when(ctx).writeAndFlush(any());

        try {
            handler.channelRead(ctx, request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Object resp = captured.get();
        if (resp instanceof FullHttpResponse response) {
            String responseBody = response.content().toString(StandardCharsets.UTF_8);
            return new Response(response.status(), responseBody);
        }
        return new Response(null, "");
    }

    /** admin1 (SUPER_ADMIN) dispatch. */
    private Response asAdmin1(HttpMethod method, String uri, String body) {
        return dispatch(admin1Token, method, uri, body);
    }

    /** admin2 (ADMIN) dispatch. */
    private Response asAdmin2(HttpMethod method, String uri, String body) {
        return dispatch(admin2Token, method, uri, body);
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

    /**
     * Authenticate an EmbeddedChannel-backed WS session for the given token.
     * Drains the auth_response frame so subsequent reads only see payloads
     * produced by the test.
     */
    private AuthenticatedSession authenticateWs(String token) {
        EmbeddedChannel channel = new EmbeddedChannel();
        WebSocketSession session = new WebSocketSession(channel);
        wsHandler.registerSession(session);
        wsHandler.handleMessage(session, "{\"type\":\"auth\",\"token\":\"" + token + "\"}");
        TextWebSocketFrame authFrame = channel.readOutbound();
        assertThat(authFrame).as("auth_response frame must be sent").isNotNull();
        authFrame.release();
        return new AuthenticatedSession(channel, session);
    }

    private record AuthenticatedSession(EmbeddedChannel channel, WebSocketSession session) {
        /** Read next outbound frame as JSON, releasing the frame. */
        JsonObject readJson() {
            TextWebSocketFrame frame = channel.readOutbound();
            assertThat(frame).as("expected an outbound WS frame").isNotNull();
            try {
                return JsonParser.parseString(frame.text()).getAsJsonObject();
            } finally {
                frame.release();
            }
        }

        /** Read next outbound frame as JSON, or null if none pending. */
        JsonObject readJsonOrNull() {
            TextWebSocketFrame frame = channel.readOutbound();
            if (frame == null) {
                return null;
            }
            try {
                return JsonParser.parseString(frame.text()).getAsJsonObject();
            } finally {
                frame.release();
            }
        }
    }

    // ====================== Scenario 1: API isolation ======================

    @Test
    @DisplayName("scenario 1: admin1 mark-read does not affect admin2 unreadCount/items; admin1 archive does not affect admin2")
    void scenario1_apiIsolation() throws DatabaseException {
        // Seed 2 broadcast notifications — visible to both admins.
        notificationStore.createNotification("t1", "m1", "info");
        notificationStore.createNotification("t2", "m2", "info");

        // admin1 marks one as read.
        Response r1 = asAdmin1(HttpMethod.POST, "/api/notifications/1/read", null);
        assertThat(r1.status).isEqualTo(HttpResponseStatus.OK);

        // admin2's unreadCount is still 2 (admin1's markRead was per-user).
        Response r2 = asAdmin2(HttpMethod.GET, "/api/notifications?page=1&size=10&unreadOnly=true", null);
        assertThat(r2.status).isEqualTo(HttpResponseStatus.OK);
        JsonObject admin2Body = r2.asJson();
        assertThat(admin2Body.get("unreadCount").getAsInt())
                .as("admin2 unreadCount must be unaffected by admin1 markRead")
                .isEqualTo(2);
        assertThat(admin2Body.getAsJsonArray("items").size())
                .as("admin2 must still see both unread notifications")
                .isEqualTo(2);

        // admin1's own unreadCount dropped to 1.
        Response r3 = asAdmin1(HttpMethod.GET, "/api/notifications?page=1&size=10&unreadOnly=true", null);
        assertThat(r3.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r3.asJson().get("unreadCount").getAsInt())
                .as("admin1 unreadCount must reflect own markRead")
                .isEqualTo(1);

        // Archive axis: admin1 clears own directed notifications (there are
        // none → cleared=0), does not affect admin2's view.
        Response r4 = asAdmin1(HttpMethod.DELETE, "/api/notifications", null);
        assertThat(r4.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r4.asJson().get("cleared").getAsInt()).isEqualTo(0);

        // admin2 still sees both broadcasts.
        Response r5 = asAdmin2(HttpMethod.GET, "/api/notifications?page=1&size=10", null);
        assertThat(r5.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r5.asJson().getAsJsonArray("items").size()).isEqualTo(2);

        // Now seed a directed notification for admin1 only.
        Notification directed = new Notification("d1", "directed-to-admin1", "info");
        directed.setRecipient("admin1");
        db.saveNotification(directed);

        // admin2 must NOT see the directed notification (recipient filter).
        Response r6 = asAdmin2(HttpMethod.GET, "/api/notifications?page=1&size=10", null);
        assertThat(r6.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r6.asJson().getAsJsonArray("items").size())
                .as("admin2 must not see admin1's directed notification")
                .isEqualTo(2);

        // admin1 sees the directed notification (2 broadcasts + 1 directed).
        Response r7 = asAdmin1(HttpMethod.GET, "/api/notifications?page=1&size=10", null);
        assertThat(r7.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r7.asJson().getAsJsonArray("items").size()).isEqualTo(3);

        // admin1 archives own directed → cleared=1; admin2's broadcasts intact.
        Response r8 = asAdmin1(HttpMethod.DELETE, "/api/notifications", null);
        assertThat(r8.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r8.asJson().get("cleared").getAsInt()).isEqualTo(1);

        Response r9 = asAdmin2(HttpMethod.GET, "/api/notifications?page=1&size=10", null);
        assertThat(r9.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r9.asJson().getAsJsonArray("items").size())
                .as("admin2 broadcasts must survive admin1 archive")
                .isEqualTo(2);
    }

    // ====================== Scenario 2: WS differentiated delivery (RED) ======================

    @Test
    @DisplayName("scenario 2 [RED]: WS directed notification (recipient=admin1) must reach admin1 only, broadcast reaches both")
    void scenario2_wsDifferentiatedDelivery() throws DatabaseException {
        // Two authenticated WS sessions.
        AuthenticatedSession admin1Ws = authenticateWs(admin1Token);
        AuthenticatedSession admin2Ws = authenticateWs(admin2Token);

        // Persist a directed notification for admin1 directly via the DB
        // (bypassing NotificationStore.createNotification which always
        // broadcasts). Then invoke the WS delivery path and assert the
        // directed notification reaches admin1 ONLY.
        Notification directed = new Notification("directed", "for admin1", "info");
        directed.setRecipient("admin1");
        db.saveNotification(directed);

        // The only WS delivery API in the current main source is
        // broadcastNotification(title, message, level) which sends to EVERY
        // authenticated session regardless of recipient. There is no
        // directed-delivery method. We call it to exercise the real WS
        // plumbing and assert the EXPECTED isolation behavior.
        wsHandler.broadcastNotification(
                directed.getTitle(), directed.getMessage(), directed.getLevel());

        // admin1 should receive the notification.
        JsonObject admin1Frame = admin1Ws.readJson();
        assertThat(admin1Frame.get("type").getAsString()).isEqualTo("notification");
        assertThat(admin1Frame.get("title").getAsString()).isEqualTo("directed");

        // admin2 must NOT receive the directed notification.
        // EXPECTED RED: broadcastNotification sends to ALL sessions with no
        // recipient check, so admin2 WILL have a frame. This is a per-user
        // WS isolation defect (WebSocketMessageHandler.broadcastNotification
        // line 662-678). Per the hard constraint, the test asserts the
        // correct expected behavior and is marked RED; the main source is
        // NOT modified.
        JsonObject admin2Frame = admin2Ws.readJsonOrNull();
        assertThat(admin2Frame)
                .as("RED: admin2 must NOT receive admin1's directed WS notification. "
                        + "Current WebSocketMessageHandler.broadcastNotification sends to ALL "
                        + "authenticated sessions with NO recipient filtering — this is a "
                        + "per-user WS isolation defect.")
                .isNull();

        // Broadcast notification reaches both (this part exercises the
        // correct broadcast behavior).
        wsHandler.broadcastNotification("broadcast", "for everyone", "info");

        JsonObject admin1Bcast = admin1Ws.readJson();
        assertThat(admin1Bcast.get("type").getAsString()).isEqualTo("notification");
        assertThat(admin1Bcast.get("title").getAsString()).isEqualTo("broadcast");

        JsonObject admin2Bcast = admin2Ws.readJson();
        assertThat(admin2Bcast.get("type").getAsString()).isEqualTo("notification");
        assertThat(admin2Bcast.get("title").getAsString()).isEqualTo("broadcast");
    }

    // ====================== Scenario 3: retention / clear isolation ======================

    @Test
    @DisplayName("scenario 3: admin1 clear own directed does not delete admin2's; SUPER_ADMIN clear broadcast + audit; ADMIN clear broadcast → 403")
    void scenario3_retentionClearIsolation() throws DatabaseException {
        // Seed directed notifications for admin1 and admin2.
        Notification d1 = new Notification("d-admin1", "for admin1", "info");
        d1.setRecipient("admin1");
        db.saveNotification(d1);

        Notification d2 = new Notification("d-admin2", "for admin2", "info");
        d2.setRecipient("admin2");
        db.saveNotification(d2);

        // admin1 clears own directed → only admin1's is deleted.
        Response r1 = asAdmin1(HttpMethod.DELETE, "/api/notifications", null);
        assertThat(r1.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r1.asJson().get("cleared").getAsInt()).isEqualTo(1);

        // admin2's directed notification survives admin1's clear.
        Response r2 = asAdmin2(HttpMethod.GET, "/api/notifications?page=1&size=10", null);
        assertThat(r2.status).isEqualTo(HttpResponseStatus.OK);
        JsonArray admin2Items = r2.asJson().getAsJsonArray("items");
        assertThat(admin2Items.size()).isEqualTo(1);
        assertThat(admin2Items.get(0).getAsJsonObject().get("title").getAsString())
                .isEqualTo("d-admin2");

        // Seed broadcasts for the global-retention slice.
        notificationStore.createNotification("b1", "broadcast-1", "info");
        notificationStore.createNotification("b2", "broadcast-2", "info");

        // ADMIN (admin2) cannot clear broadcasts → 403.
        Response r3 = asAdmin2(HttpMethod.DELETE, "/api/notifications/broadcast", null);
        assertThat(r3.status)
                .as("ADMIN must be forbidden from clearing broadcasts")
                .isEqualTo(HttpResponseStatus.FORBIDDEN);

        // Broadcasts still present after forbidden attempt (d-admin2 + b1 + b2).
        Response r4 = asAdmin2(HttpMethod.GET, "/api/notifications?page=1&size=10", null);
        assertThat(r4.status).isEqualTo(HttpResponseStatus.OK);
        assertThat(r4.asJson().getAsJsonArray("items").size())
                .as("broadcasts must survive forbidden clear attempt")
                .isEqualTo(3);

        // SUPER_ADMIN (admin1) clears broadcasts globally.
        Response r5 = asAdmin1(HttpMethod.DELETE, "/api/notifications/broadcast", null);
        assertThat(r5.status).isEqualTo(HttpResponseStatus.OK);

        // Audit record was written for the broadcast clear action.
        assertThat(auditStore.count(null, "notification.clear_broadcast"))
                .as("SUPER_ADMIN clear broadcast must produce an audit record")
                .isEqualTo(1);

        // The handler Javadoc states "deletes every notification where
        // recipient is NULL" — i.e., only broadcasts. Assert that the
        // cleared count matches the 2 broadcasts (NOT the 3 total).
        // EXPECTED RED: NotificationStore.clearAll() calls
        // databaseProvider.clearNotifications() which deletes ALL
        // notifications (DELETE FROM notifications, no recipient filter),
        // not just broadcasts. This is a per-user isolation defect: a
        // SUPER_ADMIN clearing broadcasts also deletes other admins'
        // directed notifications.
        assertThat(r5.asJson().get("cleared").getAsInt())
                .as("RED: clear broadcast must delete only the 2 broadcasts, not "
                        + "admin2's directed notification. Current clearAll() deletes "
                        + "ALL notifications (clearNotifications() with no recipient "
                        + "filter), violating per-user isolation.")
                .isEqualTo(2);

        // admin2's directed notification must survive the global broadcast clear.
        Response r6 = asAdmin2(HttpMethod.GET, "/api/notifications?page=1&size=10", null);
        assertThat(r6.status).isEqualTo(HttpResponseStatus.OK);
        JsonArray remaining = r6.asJson().getAsJsonArray("items");
        assertThat(remaining.size())
                .as("RED: admin2's directed notification must survive the global "
                        + "broadcast clear. Current implementation deletes it.")
                .isEqualTo(1);
        assertThat(remaining.get(0).getAsJsonObject().get("title").getAsString())
                .isEqualTo("d-admin2");
    }

    // ====================== Scenario 4: read state persistence (real SQLite) ======================

    @Test
    @DisplayName("scenario 4: real SQLiteProvider persists two users' read state independently across reopen")
    void scenario4_readStatePersistenceSqlite() throws DatabaseException {
        // Use a real SQLite provider on a temp file — follows the
        // SocialRelationsJdbcPersistenceTest idiom.
        SQLiteProvider sqlite = new SQLiteProvider(
                tempDir.resolve("per-user-e2e.db").toString(), 5);
        sqlite.initialize();
        try {
            // Seed two broadcast notifications.
            Notification n1 = new Notification("t1", "m1", "info");
            sqlite.saveNotification(n1);
            Notification n2 = new Notification("t2", "m2", "info");
            sqlite.saveNotification(n2);

            // admin1 marks both read.
            sqlite.markNotificationRead(n1.getId(), "admin1");
            sqlite.markNotificationRead(n2.getId(), "admin1");

            // admin1 unread = 0, admin2 unread = 2 (independent per-user state).
            assertThat(sqlite.getUnreadCount("admin1"))
                    .as("admin1 should have 0 unread after marking both read")
                    .isEqualTo(0);
            assertThat(sqlite.getUnreadCount("admin2"))
                    .as("admin2 should have 2 unread (independent of admin1)")
                    .isEqualTo(2);

            // admin2 marks n1 read.
            sqlite.markNotificationRead(n1.getId(), "admin2");
            assertThat(sqlite.getUnreadCount("admin2")).isEqualTo(1);

            // Directed notification for admin1 only.
            Notification d = new Notification("d1", "directed", "info");
            d.setRecipient("admin1");
            sqlite.saveNotification(d);

            // admin1 sees 3 total (2 broadcasts + 1 directed), admin2 sees 2.
            List<Notification> admin1All = sqlite.getNotifications(0, 100, false, "admin1");
            assertThat(admin1All).hasSize(3);
            List<Notification> admin2All = sqlite.getNotifications(0, 100, false, "admin2");
            assertThat(admin2All).hasSize(2);

            // admin1 clear own directed → only directed deleted.
            int cleared = sqlite.clearNotifications("admin1");
            assertThat(cleared).isEqualTo(1);
            assertThat(sqlite.getNotifications(0, 100, false, "admin1"))
                    .as("admin1 broadcasts survive own directed clear")
                    .hasSize(2);
            assertThat(sqlite.getNotifications(0, 100, false, "admin2"))
                    .as("admin2 view unaffected by admin1 clear")
                    .hasSize(2);

            // Shutdown + reopen on the same file — read state must survive.
            sqlite.shutdown();
            SQLiteProvider reopened = new SQLiteProvider(
                    tempDir.resolve("per-user-e2e.db").toString(), 5);
            reopened.initialize();
            try {
                // Broadcasts + read state persisted across reopen.
                assertThat(reopened.getUnreadCount("admin1"))
                        .as("admin1 read state must persist across reopen")
                        .isEqualTo(0);
                assertThat(reopened.getUnreadCount("admin2"))
                        .as("admin2 read state must persist across reopen")
                        .isEqualTo(1);
                // Directed notification was deleted by admin1 clear, must NOT
                // reappear after reopen.
                List<Notification> admin1Reopened = reopened.getNotifications(0, 100, false, "admin1");
                assertThat(admin1Reopened)
                        .as("admin1 directed clear must persist across reopen")
                        .hasSize(2);
                List<Notification> admin2Reopened = reopened.getNotifications(0, 100, false, "admin2");
                assertThat(admin2Reopened).hasSize(2);
            } finally {
                reopened.shutdown();
            }
        } finally {
            // sqlite already shut down in the happy path; safe to call again.
            try {
                sqlite.shutdown();
            } catch (Exception ignored) {
                // already closed
            }
        }
    }
}
