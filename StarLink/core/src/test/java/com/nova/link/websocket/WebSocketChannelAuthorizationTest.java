package com.nova.link.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.PanelRole;
import com.nova.link.auth.PanelUserCredentials;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("WebSocket channel resource authorization")
class WebSocketChannelAuthorizationTest {

    private static final String SECRET = "test-secret-key-for-jwt-signing-minimum-32-chars";

    private JwtService jwtService;
    private AuthManager authManager;
    private WebSocketMessageHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new JwtService(SECRET);
        authManager = new AuthManager();
        ChannelManager channels = new ChannelManager();
        channels.createChannel(ChannelConfig.builder()
                .id("global").displayName("Global").scope(ChannelScope.GLOBAL).build());
        channels.createChannel(ChannelConfig.builder()
                .id("server").displayName("Server").scope(ChannelScope.SERVER)
                .clientId("Survival").build());
        channels.createChannel(ChannelConfig.builder()
                .id("private").displayName("Private").scope(ChannelScope.PRIVATE)
                .clientId("Survival").build());

        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ServerNetworkHandler network = mock(ServerNetworkHandler.class);
        when(network.getConnections()).thenReturn(Set.of());
        when(network.getConnectionCount()).thenReturn(0);
        handler = new WebSocketMessageHandler(
                jwtService, authManager, channels, network, new PlayerStateManager(db));
    }

    @Test
    @DisplayName("subscribe accepts only role-visible channels without revealing existence")
    void subscribeFiltersRequestedChannels() {
        SessionFixture viewer = authenticated("viewer", "VIEWER");

        handler.handleMessage(viewer.session,
                "{\"type\":\"subscribe\",\"channels\":[\"global\",\"server\",\"private\",\"missing\"]}");
        JsonObject response = viewer.readJson();

        assertThat(strings(response.getAsJsonArray("channels"))).containsExactly("global");
        assertThat(strings(response.getAsJsonArray("rejectedChannels")))
                .containsExactly("server", "private", "missing");
        assertThat(response.get("errorCode").getAsString()).isEqualTo("CHANNEL_NOT_ACCESSIBLE");
        assertThat(viewer.session.getSubscribedChannels()).containsExactly("global");
    }

    @Test
    @DisplayName("channel snapshots use the same role/scope matrix as subscriptions")
    void channelSnapshotsAreFiltered() {
        SessionFixture viewer = authenticated("viewer", "VIEWER");
        handler.handleMessage(viewer.session, "{\"type\":\"get_channels\"}");
        JsonObject viewerSnapshot = viewer.readJson();
        assertThat(channelIds(viewerSnapshot)).containsExactly("global");

        SessionFixture admin = authenticated("admin", "ADMIN");
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        assertThat(channelIds(admin.readJson())).containsExactlyInAnyOrder("global", "server");

        SessionFixture root = authenticated("root", "SUPER_ADMIN");
        handler.handleMessage(root.session, "{\"type\":\"get_channels\"}");
        assertThat(channelIds(root.readJson()))
                .containsExactlyInAnyOrder("global", "server", "private");
    }

    @Test
    @DisplayName("broadcast rechecks forged and role-downgraded subscriptions")
    void broadcastRechecksAuthorization() {
        SessionFixture viewer = authenticated("viewer", "VIEWER");
        viewer.session.subscribe("private");
        handler.broadcastChatMessage("private", null, "Alice", "secret");
        Object viewerOutbound = viewer.channel.readOutbound();
        assertThat(viewerOutbound).isNull();
        assertThat(viewer.session.isSubscribed("private")).isFalse();

        authManager.registerPanelUser(new PanelUserCredentials(
                "root", AuthManager.hashPassword("pw"), PanelRole.SUPER_ADMIN));
        SessionFixture root = authenticated("root", "SUPER_ADMIN");
        handler.handleMessage(root.session,
                "{\"type\":\"subscribe\",\"channels\":[\"private\"]}");
        root.readJson();
        assertThat(root.session.isSubscribed("private")).isTrue();

        authManager.registerPanelUser(new PanelUserCredentials(
                "root", AuthManager.hashPassword("pw"), PanelRole.VIEWER));
        handler.broadcastChatMessage("private", null, "Alice", "secret");
        Object rootOutbound = root.channel.readOutbound();
        assertThat(rootOutbound).isNull();
        assertThat(root.session.isSubscribed("private")).isFalse();
    }

    @Test
    @DisplayName("connectionId and remoteAddress are visible only to SUPER_ADMIN")
    void clientFieldsAreRedacted() throws Exception {
        ServerNetworkHandler network = mock(ServerNetworkHandler.class);
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn("Survival");
        when(connection.getConnectionId()).thenReturn("conn-secret");
        when(connection.getRemoteAddress()).thenReturn("10.0.0.5");
        when(network.getConnections()).thenReturn(Set.of(connection));
        when(network.getConnectionCount()).thenReturn(1);
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ChannelManager channels = new ChannelManager();
        WebSocketMessageHandler fieldHandler = new WebSocketMessageHandler(
                jwtService, authManager, channels, network, new PlayerStateManager(db));

        SessionFixture viewer = authenticated(fieldHandler, "viewer", "VIEWER");
        fieldHandler.handleMessage(viewer.session, "{\"type\":\"get_clients\"}");
        JsonObject viewerClient = viewer.readJson().getAsJsonArray("clients").get(0).getAsJsonObject();
        assertThat(viewerClient.has("connectionId")).isFalse();
        assertThat(viewerClient.has("remoteAddress")).isFalse();

        SessionFixture root = authenticated(fieldHandler, "super", "SUPER_ADMIN");
        fieldHandler.handleMessage(root.session, "{\"type\":\"get_clients\"}");
        JsonObject rootClient = root.readJson().getAsJsonArray("clients").get(0).getAsJsonObject();
        assertThat(rootClient.get("connectionId").getAsString()).isEqualTo("conn-secret");
        assertThat(rootClient.get("remoteAddress").getAsString()).isEqualTo("10.0.0.5");
    }

    private SessionFixture authenticated(String username, String role) {
        return authenticated(handler, username, role);
    }

    private SessionFixture authenticated(WebSocketMessageHandler target, String username, String role) {
        EmbeddedChannel channel = new EmbeddedChannel();
        WebSocketSession session = new WebSocketSession(channel);
        target.registerSession(session);
        String token = jwtService.generateToken(username, username, role);
        target.handleMessage(session, "{\"type\":\"auth\",\"token\":\"" + token + "\"}");
        SessionFixture fixture = new SessionFixture(channel, session);
        assertThat(fixture.readJson().get("success").getAsBoolean()).isTrue();
        return fixture;
    }

    private static Set<String> channelIds(JsonObject response) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        response.getAsJsonArray("channels").forEach(
                element -> ids.add(element.getAsJsonObject().get("id").getAsString()));
        return ids;
    }

    private static java.util.List<String> strings(JsonArray array) {
        java.util.List<String> values = new java.util.ArrayList<>();
        array.forEach(element -> values.add(element.getAsString()));
        return values;
    }

    private record SessionFixture(EmbeddedChannel channel, WebSocketSession session) {
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
