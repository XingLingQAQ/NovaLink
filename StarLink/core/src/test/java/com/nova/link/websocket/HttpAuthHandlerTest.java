package com.nova.link.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.ClientCredentials;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PanelRole;
import com.nova.link.auth.PanelUserCredentials;
import io.jsonwebtoken.Claims;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Tests for the auth endpoints: credential pooling on login (panel accounts
 * only, game-server clients rejected), the login response contract
 * {@code {token, refreshToken, user:{username, role}}}, refresh-token
 * rotation, logout revocation, and CORS on auth responses.
 */
@DisplayName("HttpAuthHandler: login pooling, refresh rotation, logout")
class HttpAuthHandlerTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    private JwtService jwtService;
    private AuthManager authManager;
    private HttpAuthHandler handler;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET_KEY);
        authManager = new AuthManager(new IpBanManager(5, 60_000));

        // Panel pool: one super-admin + one ADMIN + one VIEWER.
        authManager.registerSuperAdmin("root", AuthManager.hashPassword("rootpass"));
        authManager.registerPanelUser(new PanelUserCredentials(
                "mod", AuthManager.hashPassword("modpass"), PanelRole.ADMIN));
        authManager.registerPanelUser(new PanelUserCredentials(
                "watcher", AuthManager.hashPassword("watchpass"), PanelRole.VIEWER));

        // Game-server client pool: must never be able to log into the panel.
        authManager.registerClient(new ClientCredentials(
                "Survival_Server", AuthManager.hashPassword("gamepass"), "Survival_Server"));

        handler = new HttpAuthHandler(jwtService, authManager);
    }

    // ====================== helpers ======================

    private Response dispatch(HttpAuthHandler target, HttpMethod method, String uri,
                              String body, String bearerToken, String origin) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri,
                body != null ? Unpooled.copiedBuffer(body, CharsetUtil.UTF_8) : Unpooled.EMPTY_BUFFER);
        if (bearerToken != null) {
            request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + bearerToken);
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
            handlerRead(target, ctx, request);
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

    private static void handlerRead(HttpAuthHandler target, ChannelHandlerContext ctx,
                                    FullHttpRequest request) throws Exception {
        target.channelRead0(ctx, request);
    }

    private Response post(String uri, String body) {
        return dispatch(handler, HttpMethod.POST, uri, body, null, null);
    }

    private Response login(String username, String password) {
        return post("/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private record Response(HttpResponseStatus status, String body, HttpHeaders headers) {
        JsonObject asJson() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    // ====================== login: credential pooling ======================

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("super-admin logs in with role SUPER_ADMIN and full contract shape")
        void superAdminLogin() {
            Response resp = login("root", "rootpass");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);

            JsonObject json = resp.asJson();
            assertThat(json.has("token")).isTrue();
            assertThat(json.has("refreshToken")).isTrue();
            JsonObject user = json.getAsJsonObject("user");
            assertThat(user.get("username").getAsString()).isEqualTo("root");
            assertThat(user.get("role").getAsString()).isEqualTo("SUPER_ADMIN");

            // JWT subject is the username (stable operator attribution).
            Claims claims = jwtService.validateToken(json.get("token").getAsString());
            assertThat(claims.getSubject()).isEqualTo("root");
            assertThat(claims.get("role", String.class)).isEqualTo("SUPER_ADMIN");
        }

        @Test
        @DisplayName("panel-user with role ADMIN logs in and the token carries ADMIN")
        void panelAdminLogin() {
            Response resp = login("mod", "modpass");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);

            JsonObject json = resp.asJson();
            assertThat(json.getAsJsonObject("user").get("role").getAsString()).isEqualTo("ADMIN");
            Claims claims = jwtService.validateToken(json.get("token").getAsString());
            assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
            assertThat(claims.getSubject()).isEqualTo("mod");
        }

        @Test
        @DisplayName("panel-user with role VIEWER logs in and the token carries VIEWER")
        void panelViewerLogin() {
            Response resp = login("watcher", "watchpass");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(resp.asJson().getAsJsonObject("user").get("role").getAsString())
                    .isEqualTo("VIEWER");
        }

        @Test
        @DisplayName("game-server client credentials are rejected with 401")
        void gameClientCredentialsRejected() {
            Response resp = login("Survival_Server", "gamepass");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("wrong password gets 401")
        void wrongPassword() {
            assertThat(login("root", "nope").status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("missing fields get 400")
        void missingFields() {
            assertThat(post("/api/auth/login", "{\"username\":\"root\"}").status())
                    .isEqualTo(HttpResponseStatus.BAD_REQUEST);
            assertThat(post("/api/auth/login", "not json").status())
                    .isEqualTo(HttpResponseStatus.BAD_REQUEST);
        }
    }

    // ====================== refresh: rotation ======================

    @Nested
    @DisplayName("refresh rotation")
    class RefreshRotation {

        @Test
        @DisplayName("refresh returns a NEW access + NEW refresh token pair")
        void refreshReturnsNewPair() {
            JsonObject loginJson = login("root", "rootpass").asJson();
            String oldRefresh = loginJson.get("refreshToken").getAsString();

            Response resp = post("/api/auth/refresh", "{\"refreshToken\":\"" + oldRefresh + "\"}");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);

            JsonObject json = resp.asJson();
            String newToken = json.get("token").getAsString();
            String newRefresh = json.get("refreshToken").getAsString();
            assertThat(newRefresh).isNotEqualTo(oldRefresh);

            Claims claims = jwtService.validateToken(newToken);
            assertThat(claims.getSubject()).isEqualTo("root");
            assertThat(claims.get("role", String.class)).isEqualTo("SUPER_ADMIN");
            assertThat(jwtService.validateToken(newRefresh)).isNotNull();
        }

        @Test
        @DisplayName("the old refresh token is revoked after rotation (replay gets 401)")
        void oldRefreshRevokedAfterRotation() {
            JsonObject loginJson = login("root", "rootpass").asJson();
            String oldRefresh = loginJson.get("refreshToken").getAsString();

            assertThat(post("/api/auth/refresh",
                    "{\"refreshToken\":\"" + oldRefresh + "\"}").status())
                    .isEqualTo(HttpResponseStatus.OK);

            // Replay of the rotated-out refresh token must fail.
            assertThat(post("/api/auth/refresh",
                    "{\"refreshToken\":\"" + oldRefresh + "\"}").status())
                    .isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an access token is not accepted as a refresh token")
        void accessTokenRejectedForRefresh() {
            JsonObject loginJson = login("root", "rootpass").asJson();
            String accessToken = loginJson.get("token").getAsString();

            assertThat(post("/api/auth/refresh",
                    "{\"refreshToken\":\"" + accessToken + "\"}").status())
                    .isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("garbage refresh token gets 401")
        void garbageRefreshRejected() {
            assertThat(post("/api/auth/refresh",
                    "{\"refreshToken\":\"garbage.token.value\"}").status())
                    .isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        }
    }

    // ====================== logout: revocation ======================

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("logout revokes the access token (further validation fails)")
        void logoutRevokesAccessToken() {
            JsonObject loginJson = login("root", "rootpass").asJson();
            String accessToken = loginJson.get("token").getAsString();
            assertThat(jwtService.validateToken(accessToken)).isNotNull();

            Response resp = dispatch(handler, HttpMethod.POST, "/api/auth/logout",
                    null, accessToken, null);
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);

            assertThat(jwtService.validateToken(accessToken)).isNull();
        }

        @Test
        @DisplayName("logout with a refresh token in the body revokes it too")
        void logoutRevokesRefreshToken() {
            JsonObject loginJson = login("root", "rootpass").asJson();
            String accessToken = loginJson.get("token").getAsString();
            String refreshToken = loginJson.get("refreshToken").getAsString();

            Response resp = dispatch(handler, HttpMethod.POST, "/api/auth/logout",
                    "{\"refreshToken\":\"" + refreshToken + "\"}", accessToken, null);
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);

            assertThat(jwtService.validateToken(refreshToken)).isNull();
            assertThat(post("/api/auth/refresh",
                    "{\"refreshToken\":\"" + refreshToken + "\"}").status())
                    .isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("logout without a valid Bearer access token gets 401")
        void logoutRequiresAccessToken() {
            assertThat(dispatch(handler, HttpMethod.POST, "/api/auth/logout",
                    null, null, null).status())
                    .isEqualTo(HttpResponseStatus.UNAUTHORIZED);

            // A refresh token in the Authorization header must not work either.
            JsonObject loginJson = login("root", "rootpass").asJson();
            String refreshToken = loginJson.get("refreshToken").getAsString();
            assertThat(dispatch(handler, HttpMethod.POST, "/api/auth/logout",
                    null, refreshToken, null).status())
                    .isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        }
    }

    // ====================== CORS on auth endpoints ======================

    @Nested
    @DisplayName("CORS")
    class Cors {

        @Test
        @DisplayName("default handler sends '*' CORS header")
        void defaultAllowsAll() {
            Response resp = dispatch(handler, HttpMethod.POST, "/api/auth/login",
                    "{\"username\":\"root\",\"password\":\"rootpass\"}", null,
                    "http://anywhere.example");
            assertThat(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isEqualTo("*");
        }

        @Test
        @DisplayName("explicit whitelist echoes matching Origin and omits headers for others")
        void explicitWhitelist() {
            HttpAuthHandler corsHandler = new HttpAuthHandler(jwtService, authManager,
                    List.of("http://localhost:5173"));

            Response match = dispatch(corsHandler, HttpMethod.POST, "/api/auth/login",
                    "{\"username\":\"root\",\"password\":\"rootpass\"}", null,
                    "http://localhost:5173");
            assertThat(match.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isEqualTo("http://localhost:5173");

            Response mismatch = dispatch(corsHandler, HttpMethod.POST, "/api/auth/login",
                    "{\"username\":\"root\",\"password\":\"rootpass\"}", null,
                    "http://evil.example.com");
            assertThat(mismatch.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        }

        @Test
        @DisplayName("OPTIONS preflight respects the whitelist")
        void preflight() {
            HttpAuthHandler corsHandler = new HttpAuthHandler(jwtService, authManager,
                    List.of("http://localhost:5173"));

            Response resp = dispatch(corsHandler, HttpMethod.OPTIONS, "/api/auth/login",
                    null, null, "http://localhost:5173");
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isEqualTo("http://localhost:5173");
        }
    }

    @Test
    @DisplayName("unknown auth path gets 404")
    void unknownPath() {
        assertThat(post("/api/auth/unknown", "{}").status())
                .isEqualTo(HttpResponseStatus.NOT_FOUND);
    }
}
