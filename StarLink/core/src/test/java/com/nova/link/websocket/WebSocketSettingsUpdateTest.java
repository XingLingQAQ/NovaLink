package com.nova.link.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelSource;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.Channel;
import com.nova.link.config.FeatureConfig;
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
import static org.mockito.Mockito.when;

/**
 * §11.6 Project 20 (proposal 10): WebSocket {@code settings_update} broadcast.
 *
 * <p>Exercises {@link WebSocketMessageHandler#broadcastSettingsUpdate(long, FeatureConfig)}
 * through the real handler (no HTTP server). Mirrors the scaffold of
 * {@link WebSocketChannelUpdateSourceRevisionTest}: real JwtService +
 * MemoryProvider + mocked ServerNetworkHandler, sessions authenticated via the
 * {@code auth} message flow, outbound frames drained from the EmbeddedChannel.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Authenticated, active session receives a {@code settings_update} frame
 *       carrying {@code type}, {@code revision} (monotonic counter),
 *       {@code settingsRevision} (PANEL-010 token), and every feature flag.</li>
 *   <li>{@code features==null} → no broadcast, no exception (the handler must
 *       silently skip rather than NPE on a mid-reload config).</li>
 *   <li>Unauthenticated session receives nothing.</li>
 * </ul>
 */
@DisplayName("PANEL-010/§11.6-20: WS settings_update broadcast")
class WebSocketSettingsUpdateTest {

    private static final String SECRET = "test-secret-key-for-jwt-signing-minimum-32-chars";

    private JwtService jwtService;
    private WebSocketMessageHandler handler;
    private ChannelManager channelManager;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new JwtService(SECRET);
        MemoryProvider db = new MemoryProvider();
        db.initialize();

        channelManager = new ChannelManager();
        channelManager.createChannel(ChannelConfig.builder()
                .id("global").displayName("Global").scope(ChannelScope.GLOBAL).build(),
                ChannelSource.CONFIG);

        ServerNetworkHandler network = mock(ServerNetworkHandler.class);
        when(network.getConnections()).thenReturn(java.util.Set.of());
        when(network.getConnectionCount()).thenReturn(0);

        handler = new WebSocketMessageHandler(
                jwtService,
                new AuthManager(new IpBanManager(5, 60_000)),
                channelManager,
                network,
                new PlayerStateManager(db));
    }

    @Test
    @DisplayName("broadcastSettingsUpdate delivers settings_update to authenticated session")
    void broadcastDeliversToAuthenticatedSession() {
        AuthenticatedSession admin = authenticate("ADMIN");

        FeatureConfig features = new FeatureConfig();
        features.setFilterEnabled(true);
        features.setMessageLogEnabled(false);
        features.setCrossServerChatEnabled(true);
        features.setPrivateMessagesEnabled(true);
        features.setMessageLogRetentionDays(30);

        long settingsRevision = 42L;
        handler.broadcastSettingsUpdate(settingsRevision, features);

        JsonObject message = admin.readJson();
        assertThat(message.get("type").getAsString()).isEqualTo("settings_update");
        // Top-level revision is the monotonic counter (PANEL-008), must be > 0.
        assertThat(message.get("revision").getAsLong()).isPositive();
        // settingsRevision echoes the PANEL-010 token the caller can use as
        // baseRevision on the next PUT /api/settings.
        assertThat(message.get("settingsRevision").getAsLong()).isEqualTo(settingsRevision);
        // Every feature flag is present and matches the input.
        assertThat(message.get("filterEnabled").getAsBoolean()).isTrue();
        assertThat(message.get("messageLogEnabled").getAsBoolean()).isFalse();
        assertThat(message.get("crossServerChatEnabled").getAsBoolean()).isTrue();
        assertThat(message.get("privateMessagesEnabled").getAsBoolean()).isTrue();
        assertThat(message.get("messageLogRetentionDays").getAsInt()).isEqualTo(30);
        assertThat(message.get("timestamp").getAsLong()).isPositive();
    }

    @Test
    @DisplayName("features==null → no broadcast, no exception")
    void nullFeaturesIsSilentlySkipped() {
        AuthenticatedSession admin = authenticate("ADMIN");

        // Must not throw; must not send any frame.
        handler.broadcastSettingsUpdate(7L, null);

        // No outbound frame expected.
        TextWebSocketFrame frame = admin.channel.readOutbound();
        assertThat(frame).isNull();
    }

    @Test
    @DisplayName("unauthenticated session receives nothing")
    void unauthenticatedSessionReceivesNothing() {
        // Register a session but do NOT authenticate it.
        EmbeddedChannel channel = new EmbeddedChannel();
        WebSocketSession session = new WebSocketSession(channel);
        handler.registerSession(session);

        FeatureConfig features = new FeatureConfig();
        features.setFilterEnabled(true);
        features.setMessageLogEnabled(true);
        features.setCrossServerChatEnabled(true);
        features.setPrivateMessagesEnabled(true);
        features.setMessageLogRetentionDays(7);

        handler.broadcastSettingsUpdate(99L, features);

        // No outbound frame expected on the unauthenticated session.
        TextWebSocketFrame frame = channel.readOutbound();
        assertThat(frame).isNull();

        // Sanity: the channel is still active (broadcast must not close it).
        assertThat(channel.isActive()).isTrue();
    }

    private AuthenticatedSession authenticate(String role) {
        EmbeddedChannel channel = new EmbeddedChannel();
        WebSocketSession session = new WebSocketSession(channel);
        handler.registerSession(session);
        String token = jwtService.generateToken("user-" + role, "user-" + role, role);
        handler.handleMessage(session, "{\"type\":\"auth\",\"token\":\"" + token + "\"}");
        // Drain the auth_response so subsequent reads see only the payload
        // produced by the test.
        TextWebSocketFrame authFrame = channel.readOutbound();
        assertThat(authFrame).isNotNull();
        authFrame.release();
        return new AuthenticatedSession(channel, session);
    }

    private record AuthenticatedSession(EmbeddedChannel channel, WebSocketSession session) {
        JsonObject readJson() {
            TextWebSocketFrame frame = channel.readOutbound();
            assertThat(frame).isNotNull();
            try {
                return JsonParser.parseString(frame.text()).getAsJsonObject();
            } finally {
                frame.release();
            }
        }
    }
}
