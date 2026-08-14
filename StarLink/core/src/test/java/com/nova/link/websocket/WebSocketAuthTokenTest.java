package com.nova.link.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.network.ServerNetworkHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WebSocket auth contract: any valid panel role can authenticate (read-only
 * side), refresh-type tokens are rejected, and revoked tokens are rejected.
 */
@DisplayName("WebSocket auth: role acceptance + refresh/revoked token rejection")
class WebSocketAuthTokenTest {

    private static final String SECRET = "test-secret-key-for-jwt-signing-minimum-32-chars";

    private JwtService jwtService;
    private WebSocketMessageHandler messageHandler;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new JwtService(SECRET);
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        messageHandler = new WebSocketMessageHandler(
                jwtService,
                new AuthManager(new IpBanManager(5, 60_000)),
                new ChannelManager(),
                mock(ServerNetworkHandler.class),
                new PlayerStateManager(db));
    }

    private JsonObject authenticate(String token) {
        EmbeddedChannel channel = new EmbeddedChannel();
        WebSocketSession session = new WebSocketSession(channel);
        messageHandler.registerSession(session);

        messageHandler.handleMessage(session, "{\"type\":\"auth\",\"token\":\"" + token + "\"}");

        TextWebSocketFrame frame = channel.readOutbound();
        assertThat(frame).isNotNull();
        JsonObject response = JsonParser.parseString(frame.text()).getAsJsonObject();
        frame.release();
        return response;
    }

    @Test
    @DisplayName("VIEWER / ADMIN / SUPER_ADMIN access tokens all authenticate")
    void allPanelRolesAccepted() {
        for (String role : new String[]{"VIEWER", "ADMIN", "SUPER_ADMIN"}) {
            String token = jwtService.generateToken("user-" + role, "user-" + role, role);
            JsonObject response = authenticate(token);
            assertThat(response.get("type").getAsString()).isEqualTo("auth_response");
            assertThat(response.get("success").getAsBoolean())
                    .as("role %s should authenticate", role)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("refresh tokens are rejected for WebSocket auth")
    void refreshTokenRejected() {
        String refresh = jwtService.generateRefreshToken("root", "root", "SUPER_ADMIN");
        JsonObject response = authenticate(refresh);
        assertThat(response.get("success").getAsBoolean()).isFalse();
    }

    @Test
    @DisplayName("revoked access tokens are rejected for WebSocket auth")
    void revokedTokenRejected() {
        String token = jwtService.generateToken("root", "root", "SUPER_ADMIN");
        jwtService.revokeToken(token);
        JsonObject response = authenticate(token);
        assertThat(response.get("success").getAsBoolean()).isFalse();
    }

    @Test
    @DisplayName("garbage tokens are rejected")
    void garbageTokenRejected() {
        JsonObject response = authenticate("garbage.token.value");
        assertThat(response.get("success").getAsBoolean()).isFalse();
    }
}
