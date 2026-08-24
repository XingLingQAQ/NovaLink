package com.nova.link.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.announcement.AnnouncementManager;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.config.ConfigManager;
import com.nova.link.database.ChatMessageRecord;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.log.MessageLogService;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.notification.NotificationStore;
import com.nova.link.spy.SpyManager;
import com.nova.link.websocket.JwtService;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests for the schema-v5 REST endpoints against the panel
 * contract: GET /api/messages (history pagination), /api/announcements CRUD,
 * GET/PUT /api/filter (runtime apply + disk persist), PUT /api/webhooks/{id}
 * (events/event key compat) and POST /api/webhooks/{id}/test.
 */
@DisplayName("REST v5 endpoints (messages/announcements/filter/webhooks)")
class RestApiV5EndpointsTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    @TempDir
    java.nio.file.Path tempDir;

    private RestApiHandler handler;
    private JwtService jwtService;
    private ChannelManager channelManager;
    private MemoryProvider db;
    private MessageRouter messageRouter;
    private SensitiveWordFilter sensitiveWordFilter;
    private ConfigManager configManager;
    private AnnouncementManager announcementManager;
    private MessageLogService messageLogService;
    private WebhookManager webhookManager;
    private MuteManager muteManager;
    private BanManager banManager;
    private java.nio.file.Path configPath;

    private final List<String> sentAnnouncements = new CopyOnWriteArrayList<>();
    private String validToken;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();

        channelManager = new ChannelManager();
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("global")
                .displayName("Global")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("server-chat")
                .displayName("Server Chat")
                .scope(ChannelScope.SERVER)
                .clientId("Survival")
                .build());
        channelManager.createChannel(com.nova.link.channel.ChannelConfig.builder()
                .id("staff")
                .displayName("Staff")
                .scope(ChannelScope.PRIVATE)
                .clientId("Survival")
                .build());

        PlayerStateManager playerStateManager = new PlayerStateManager(db);
        PermissionManager permissionManager = new PermissionManager();
        muteManager = new MuteManager(db, permissionManager, channelManager);
        banManager = new BanManager(db, permissionManager, channelManager);
        NotificationStore notificationStore = new NotificationStore(db);
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        sensitiveWordFilter = new SensitiveWordFilter();

        configPath = tempDir.resolve("novalink.yml");
        configManager = new ConfigManager(configPath);
        configManager.load();

        ServerNetworkHandler networkHandler = mock(ServerNetworkHandler.class);
        when(networkHandler.getConnections()).thenReturn(Set.of());

        messageRouter = new MessageRouter(channelManager, networkHandler);
        messageRouter.setMuteManager(muteManager);
        messageRouter.setSensitiveWordFilter(sensitiveWordFilter);
        messageRouter.setPermissionChecker((c, p) -> true);
        messageRouter.setSpyManager(new SpyManager(permissionManager, channelManager, networkHandler));

        announcementManager = new AnnouncementManager(permissionManager, channelManager);
        announcementManager.setDatabaseProvider(db);
        announcementManager.initialize();
        announcementManager.setAnnouncementSender(
                (channelId, content) -> sentAnnouncements.add(channelId + ":" + content));

        messageLogService = new MessageLogService(db, 30);

        webhookManager = new WebhookManager();
        webhookManager.setDatabaseProvider(db);

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
                null,
                notificationStore
        );
        handler.setAnnouncementManager(announcementManager);
        handler.setMessageLogService(messageLogService);

        validToken = jwtService.generateToken(UUID.randomUUID().toString(), "root", "SUPER_ADMIN");
    }

    @AfterEach
    void tearDown() {
        announcementManager.shutdown();
        messageLogService.shutdown();
        webhookManager.shutdown();
        muteManager.shutdown();
        banManager.shutdown();
        db.shutdown();
    }

    // ====================== helpers ======================

    private Response dispatch(HttpMethod method, String uri, String body) {
        return dispatch(validToken, method, uri, body);
    }

    private Response dispatch(String token, HttpMethod method, String uri, String body) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri,
                body != null ? Unpooled.copiedBuffer(body, CharsetUtil.UTF_8) : Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);

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
            return new Response(response.status(), response.content().toString(StandardCharsets.UTF_8));
        }
        return new Response(null, "");
    }

    private record Response(HttpResponseStatus status, String body) {
        JsonObject asJson() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    // ====================== GET /api/messages ======================

    @Nested
    @DisplayName("GET /api/messages")
    class Messages {

        private void seed() throws Exception {
            db.saveMessage(new ChatMessageRecord("global", null, "Alice", "Survival", "hello world", 1000));
            db.saveMessage(new ChatMessageRecord("global", null, "Bob", "Creative", "spam offer", 2000));
            db.saveMessage(new ChatMessageRecord("staff", null, "Alice", "Survival", "hello staff", 3000));
        }

        @Test
        @DisplayName("returns {items, page, pageSize, total} newest-first")
        void paginationContract() throws Exception {
            seed();
            Response resp = dispatch(HttpMethod.GET, "/api/messages?page=1&size=2", null);
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            JsonObject json = resp.asJson();
            assertThat(json.get("page").getAsInt()).isEqualTo(1);
            assertThat(json.get("pageSize").getAsInt()).isEqualTo(2);
            assertThat(json.get("total").getAsInt()).isEqualTo(3);
            JsonArray items = json.getAsJsonArray("items");
            assertThat(items).hasSize(2);
            JsonObject first = items.get(0).getAsJsonObject();
            // Newest first + full field contract.
            assertThat(first.get("timestamp").getAsLong()).isEqualTo(3000);
            assertThat(first.get("id").getAsLong()).isPositive();
            assertThat(first.get("channelId").getAsString()).isEqualTo("staff");
            assertThat(first.get("senderName").getAsString()).isEqualTo("Alice");
            assertThat(first.get("clientId").getAsString()).isEqualTo("Survival");
            assertThat(first.get("content").getAsString()).isEqualTo("hello staff");

            Response page2 = dispatch(HttpMethod.GET, "/api/messages?page=2&size=2", null);
            assertThat(page2.asJson().getAsJsonArray("items")).hasSize(1);
        }

        @Test
        @DisplayName("filters: channel, server, player, q, from/to")
        void filterParams() throws Exception {
            seed();
            assertThat(dispatch(HttpMethod.GET, "/api/messages?channel=staff", null)
                    .asJson().get("total").getAsInt()).isEqualTo(1);
            assertThat(dispatch(HttpMethod.GET, "/api/messages?server=Creative", null)
                    .asJson().get("total").getAsInt()).isEqualTo(1);
            assertThat(dispatch(HttpMethod.GET, "/api/messages?player=ali", null)
                    .asJson().get("total").getAsInt()).isEqualTo(2);
            assertThat(dispatch(HttpMethod.GET, "/api/messages?q=hello", null)
                    .asJson().get("total").getAsInt()).isEqualTo(2);
            assertThat(dispatch(HttpMethod.GET, "/api/messages?from=2000&to=3000", null)
                    .asJson().get("total").getAsInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("size is capped at 200 and bad page defaults to 1")
        void sizeCapAndDefaults() throws Exception {
            seed();
            Response resp = dispatch(HttpMethod.GET, "/api/messages?page=abc&size=9999", null);
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(resp.asJson().get("pageSize").getAsInt()).isEqualTo(200);
            assertThat(resp.asJson().get("page").getAsInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("503 when the message log service is not wired")
        void unavailableWithoutService() {
            handler.setMessageLogService(null);
            Response resp = dispatch(HttpMethod.GET, "/api/messages", null);
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("history filtering and total use the authorized channel set")
        void historyUsesAuthorizedChannelSet() throws Exception {
            seed();
            db.saveMessage(new ChatMessageRecord(
                    "server-chat", null, "Carol", "Survival", "server only", 4000));

            String viewer = jwtService.generateToken("viewer", "viewer", "VIEWER");
            String admin = jwtService.generateToken("admin", "admin", "ADMIN");

            Response viewerAll = dispatch(viewer, HttpMethod.GET, "/api/messages?size=20", null);
            assertThat(viewerAll.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(viewerAll.asJson().get("total").getAsInt()).isEqualTo(2);
            assertThat(viewerAll.asJson().getAsJsonArray("items"))
                    .allSatisfy(element -> {
                        JsonObject item = element.getAsJsonObject();
                        assertThat(item.get("channelId").getAsString()).isEqualTo("global");
                        assertThat(item.has("clientId")).isFalse();
                    });

            Response adminAll = dispatch(admin, HttpMethod.GET, "/api/messages?size=20", null);
            assertThat(adminAll.asJson().get("total").getAsInt()).isEqualTo(3);

            Response superAll = dispatch(HttpMethod.GET, "/api/messages?size=20", null);
            assertThat(superAll.asJson().get("total").getAsInt()).isEqualTo(4);

            Response forbiddenPrivate = dispatch(
                    viewer, HttpMethod.GET, "/api/messages?channel=staff", null);
            Response missing = dispatch(
                    viewer, HttpMethod.GET, "/api/messages?channel=missing", null);
            assertThat(forbiddenPrivate.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
            assertThat(missing.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
            // Both 404 bodies carry the same error/message/status, but the
            // requestId field differs per request (PANEL-006 correlation id),
            // so compare the body minus the requestId field.
            JsonObject forbiddenJson = forbiddenPrivate.asJson();
            JsonObject missingJson = missing.asJson();
            forbiddenJson.remove("requestId");
            missingJson.remove("requestId");
            assertThat(forbiddenJson).isEqualTo(missingJson);
        }
    }

    // ====================== /api/announcements ======================

    @Nested
    @DisplayName("/api/announcements")
    class Announcements {

        @Test
        @DisplayName("POST INSTANT sends through the pipeline and returns {sent:true} without persisting")
        void instantSendsImmediately() throws Exception {
            Response resp = dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"INSTANT\",\"channelId\":\"global\",\"content\":\"Now!\"}");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(resp.asJson().get("sent").getAsBoolean()).isTrue();
            assertThat(sentAnnouncements).containsExactly("global:Now!");
            assertThat(db.getAllPersistedAnnouncements()).isEmpty();
        }

        @Test
        @DisplayName("POST JOIN persists and returns the full object (201)")
        void joinPersists() throws Exception {
            Response resp = dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"JOIN\",\"channelId\":\"global\",\"content\":\"Welcome!\"}");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.CREATED);
            JsonObject json = resp.asJson();
            assertThat(json.get("type").getAsString()).isEqualTo("JOIN");
            assertThat(json.get("channelId").getAsString()).isEqualTo("global");
            assertThat(json.get("content").getAsString()).isEqualTo("Welcome!");
            assertThat(json.get("cron").isJsonNull()).isTrue();
            assertThat(json.get("enabled").getAsBoolean()).isTrue();
            assertThat(json.get("createdAt").getAsLong()).isPositive();
            assertThat(db.getAllPersistedAnnouncements()).hasSize(1);
        }

        @Test
        @DisplayName("POST CRON persists with cron; invalid cron → 400")
        void cronValidation() throws Exception {
            Response ok = dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"CRON\",\"channelId\":\"global\",\"content\":\"Vote!\",\"cron\":\"0 12 * * *\"}");
            assertThat(ok.status()).isEqualTo(HttpResponseStatus.CREATED);
            assertThat(ok.asJson().get("cron").getAsString()).isEqualTo("0 12 * * *");

            Response bad = dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"CRON\",\"channelId\":\"global\",\"content\":\"X\",\"cron\":\"garbage\"}");
            assertThat(bad.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);

            Response missing = dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"CRON\",\"channelId\":\"global\",\"content\":\"X\"}");
            assertThat(missing.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("POST validation: bad type, unknown channel, missing content → 400")
        void postValidation() {
            assertThat(dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"WEIRD\",\"channelId\":\"global\",\"content\":\"X\"}").status())
                    .isEqualTo(HttpResponseStatus.BAD_REQUEST);
            assertThat(dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"JOIN\",\"channelId\":\"missing\",\"content\":\"X\"}").status())
                    .isEqualTo(HttpResponseStatus.BAD_REQUEST);
            assertThat(dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"JOIN\",\"channelId\":\"global\"}").status())
                    .isEqualTo(HttpResponseStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("GET lists JOIN/CRON with {items, total}")
        void listAnnouncements() {
            dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"JOIN\",\"channelId\":\"global\",\"content\":\"Welcome!\"}");
            dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"CRON\",\"channelId\":\"global\",\"content\":\"Vote!\",\"cron\":\"0 12 * * *\"}");

            Response resp = dispatch(HttpMethod.GET, "/api/announcements", null);
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            JsonObject json = resp.asJson();
            assertThat(json.get("total").getAsInt()).isEqualTo(2);
            assertThat(json.getAsJsonArray("items")).hasSize(2);
        }

        @Test
        @DisplayName("PUT toggles enabled and returns the updated object; unknown id → 404")
        void putEnabled() {
            String id = dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"CRON\",\"channelId\":\"global\",\"content\":\"Vote!\",\"cron\":\"0 12 * * *\"}")
                    .asJson().get("id").getAsString();

            Response disabled = dispatch(HttpMethod.PUT, "/api/announcements/" + id,
                    "{\"enabled\":false}");
            assertThat(disabled.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(disabled.asJson().get("enabled").getAsBoolean()).isFalse();

            // Missing enabled field → 400.
            assertThat(dispatch(HttpMethod.PUT, "/api/announcements/" + id, "{}").status())
                    .isEqualTo(HttpResponseStatus.BAD_REQUEST);

            assertThat(dispatch(HttpMethod.PUT, "/api/announcements/nope",
                    "{\"enabled\":true}").status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("DELETE removes the announcement; unknown id → 404")
        void deleteAnnouncement() throws Exception {
            String id = dispatch(HttpMethod.POST, "/api/announcements",
                    "{\"type\":\"JOIN\",\"channelId\":\"global\",\"content\":\"Bye\"}")
                    .asJson().get("id").getAsString();

            Response resp = dispatch(HttpMethod.DELETE, "/api/announcements/" + id, null);
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(db.getAllPersistedAnnouncements()).isEmpty();

            assertThat(dispatch(HttpMethod.DELETE, "/api/announcements/" + id, null).status())
                    .isEqualTo(HttpResponseStatus.NOT_FOUND);
        }
    }

    // ====================== /api/filter ======================

    @Nested
    @DisplayName("/api/filter")
    class Filter {

        @Test
        @DisplayName("GET returns {enabled, words, patterns}")
        void getFilterState() {
            sensitiveWordFilter.setCustomWords(List.of("badword"));
            Response resp = dispatch(HttpMethod.GET, "/api/filter", null);
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            JsonObject json = resp.asJson();
            assertThat(json.get("enabled").getAsBoolean()).isTrue();
            assertThat(json.getAsJsonArray("words")).hasSize(1);
            assertThat(json.getAsJsonArray("patterns")).isEmpty();
        }

        @Test
        @DisplayName("PUT applies words/patterns/enabled to runtime and persists to disk")
        void putAppliesAndPersists() throws Exception {
            Response resp = dispatch(HttpMethod.PUT, "/api/filter",
                    "{\"enabled\":false,\"words\":[\"foo\",\"bar\"],\"patterns\":[\"\\\\bspam\\\\b\"]}");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            JsonObject json = resp.asJson();
            assertThat(json.get("enabled").getAsBoolean()).isFalse();
            assertThat(json.getAsJsonArray("words")).hasSize(2);
            assertThat(json.getAsJsonArray("patterns")).hasSize(1);

            // Runtime applied.
            assertThat(sensitiveWordFilter.isEnabled()).isFalse();
            assertThat(sensitiveWordFilter.getCustomWords()).containsExactlyInAnyOrder("foo", "bar");
            assertThat(sensitiveWordFilter.getCustomPatterns()).containsExactly("\\bspam\\b");

            // No disk reload was triggered.
            assertThat(configManager.getReloadCount()).isZero();

            // Persisted: a fresh loader sees the new filter section + toggle.
            ConfigManager reread = new ConfigManager(configPath);
            com.nova.link.config.NovaLinkConfig persisted = reread.load();
            assertThat(persisted.getFilter().getWords()).containsExactlyInAnyOrder("foo", "bar");
            assertThat(persisted.getFilter().getPatterns()).containsExactly("\\bspam\\b");
            assertThat(persisted.getFeatures().isFilterEnabled()).isFalse();
        }

        @Test
        @DisplayName("PUT with only words replaces words and leaves the rest untouched")
        void putPartialUpdate() {
            sensitiveWordFilter.setCustomPatterns(List.of("keep.*me"));
            Response resp = dispatch(HttpMethod.PUT, "/api/filter", "{\"words\":[\"only\"]}");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(sensitiveWordFilter.getCustomWords()).containsExactly("only");
            assertThat(sensitiveWordFilter.getCustomPatterns()).containsExactly("keep.*me");
            assertThat(sensitiveWordFilter.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("PUT with an invalid regex → 400 identifying the offending pattern")
        void putInvalidRegex() {
            Response resp = dispatch(HttpMethod.PUT, "/api/filter",
                    "{\"patterns\":[\"valid.*\",\"[unclosed\"]}");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
            assertThat(resp.body()).contains("[unclosed");
            // Nothing was applied.
            assertThat(sensitiveWordFilter.getCustomPatterns()).isEmpty();
        }

        @Test
        @DisplayName("filtering actually uses the words applied via PUT")
        void putTakesEffectOnFiltering() {
            dispatch(HttpMethod.PUT, "/api/filter", "{\"words\":[\"badstuff\"]}");
            com.nova.link.filter.FilterResult result = sensitiveWordFilter.filter("this is badstuff here");
            assertThat(result.isFiltered()).isTrue();
            assertThat(result.getFilteredMessage()).doesNotContain("badstuff");
        }
    }

    // ====================== /api/webhooks PUT + test ======================

    @Nested
    @DisplayName("/api/webhooks PUT + test")
    class Webhooks {

        @Test
        @DisplayName("GET includes active and lastTriggered (null when never triggered)")
        void getIncludesActiveAndLastTriggered() {
            webhookManager.createWebhook("https://8.8.8.8/h", "message.sent", null);
            Response resp = dispatch(HttpMethod.GET, "/api/webhooks", null);
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            JsonObject hook = resp.asJson().getAsJsonArray("webhooks").get(0).getAsJsonObject();
            assertThat(hook.get("active").getAsBoolean()).isTrue();
            assertThat(hook.get("lastTriggered").isJsonNull()).isTrue();
        }

        @Test
        @DisplayName("PUT accepts the events key (panel contract) and the event key")
        void putAcceptsBothEventKeys() {
            String id = webhookManager.createWebhook("https://8.8.8.8/h", "message.sent", null).getId();

            Response viaEvents = dispatch(HttpMethod.PUT, "/api/webhooks/" + id,
                    "{\"events\":\"player.join\",\"active\":false}");
            assertThat(viaEvents.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(viaEvents.asJson().get("event").getAsString()).isEqualTo("player.join");
            assertThat(viaEvents.asJson().get("active").getAsBoolean()).isFalse();

            Response viaEvent = dispatch(HttpMethod.PUT, "/api/webhooks/" + id,
                    "{\"event\":\"player.leave\",\"url\":\"https://8.8.8.8/x\"}");
            assertThat(viaEvent.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(viaEvent.asJson().get("event").getAsString()).isEqualTo("player.leave");
            assertThat(viaEvent.asJson().get("url").getAsString()).isEqualTo("https://8.8.8.8/x");
            // active untouched by the second update.
            assertThat(viaEvent.asJson().get("active").getAsBoolean()).isFalse();
        }

        @Test
        @DisplayName("PUT unknown id → 404")
        void putUnknownId() {
            assertThat(dispatch(HttpMethod.PUT, "/api/webhooks/nope", "{\"active\":true}").status())
                    .isEqualTo(HttpResponseStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("POST test: success and failure branches; unknown id → 404")
        void testEndpointBothBranches() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", exchange -> {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("ok".getBytes());
                exchange.close();
            });
            server.start();
            try {
                // The sink must be a public IP to pass UrlGuard at creation,
                // then retargeted to the in-process 127.0.0.1 server and
                // allow-loopback-for-test enabled for the actual delivery.
                Webhook okHook = webhookManager.createWebhook(
                        "http://8.8.8.8/hook",
                        "message.sent", null);
                okHook.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
                webhookManager.allowLoopbackForTest();
                String okId = okHook.getId();
                Response ok = dispatch(HttpMethod.POST, "/api/webhooks/" + okId + "/test", null);
                assertThat(ok.status()).isEqualTo(HttpResponseStatus.OK);
                assertThat(ok.asJson().get("success").getAsBoolean()).isTrue();
                assertThat(ok.asJson().get("statusCode").getAsInt()).isEqualTo(200);

                String badId = webhookManager.createWebhook("http://8.8.8.8/x",
                        "message.sent", null).getId();
                // Retarget to a syntactically-broken URL to exercise sendTest's
                // own validation path (createWebhook now itself rejects malformed
                // URLs at the guard layer, so the hook must be created with a
                // valid public URL first).
                webhookManager.getWebhook(badId).setUrl("ht!tp://not a url");
                Response bad = dispatch(HttpMethod.POST, "/api/webhooks/" + badId + "/test", null);
                assertThat(bad.status()).isEqualTo(HttpResponseStatus.OK);
                assertThat(bad.asJson().get("success").getAsBoolean()).isFalse();
                assertThat(bad.asJson().get("error").getAsString()).isNotBlank();

                assertThat(dispatch(HttpMethod.POST, "/api/webhooks/nope/test", null).status())
                        .isEqualTo(HttpResponseStatus.NOT_FOUND);
            } finally {
                server.stop(0);
            }
        }
    }
}
