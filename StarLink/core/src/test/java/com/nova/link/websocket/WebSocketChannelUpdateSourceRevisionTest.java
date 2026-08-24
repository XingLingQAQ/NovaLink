package com.nova.link.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelSource;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.network.ServerNetworkHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PANEL-003 residual correctness: the WebSocket {@code channel_update} payload
 * must carry per-channel {@code source} (CONFIG/DATABASE/RUNTIME) and
 * {@code revision} (long) so the frontend adapter does not default
 * config-managed channels to RUNTIME/0 on every 30s broadcast (which makes
 * the read-only badge and edit/delete button hiding flicker off).
 *
 * <p>The top-level {@code revision} field on the message is the snapshot
 * revision (a different concept) and is not asserted here — see
 * {@code WebSocketAuthTokenTest} for the PANEL-008 monotonic snapshot
 * revision guard.</p>
 */
@DisplayName("PANEL-003: WS channel_update carries per-channel source and revision")
class WebSocketChannelUpdateSourceRevisionTest {

    private static final String SECRET = "test-secret-key-for-jwt-signing-minimum-32-chars";

    private JwtService jwtService;
    private ChannelManager channelManager;
    private WebSocketMessageHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new JwtService(SECRET);
        MemoryProvider db = new MemoryProvider();
        db.initialize();

        channelManager = new ChannelManager();
        // CONFIG channel (read-only in the Panel): a GLOBAL channel declared
        // in the config file and loaded by the config loader.
        channelManager.createChannel(ChannelConfig.builder()
                .id("global").displayName("Global").scope(ChannelScope.GLOBAL).build(),
                ChannelSource.CONFIG);
        // RUNTIME channel (dynamic, editable): a SERVER channel created via the
        // REST API / console at runtime.
        channelManager.createChannel(ChannelConfig.builder()
                .id("runtime-chat").displayName("Runtime").scope(ChannelScope.SERVER)
                .clientId("Survival").build(),
                ChannelSource.RUNTIME);

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
    @DisplayName("channel_update carries source and revision per channel (get_channels)")
    void channelUpdateCarriesSourceAndRevision() {
        AuthenticatedSession root = authenticate("SUPER_ADMIN");

        handler.handleMessage(root.session, "{\"type\":\"get_channels\"}");
        JsonObject message = root.readJson();

        assertThat(message.get("type").getAsString()).isEqualTo("channel_update");

        JsonArray channels = message.getAsJsonArray("channels");
        assertThat(channels).hasSize(2);

        // Index channels by id so the test does not depend on iteration order.
        Map<String, JsonObject> byId = new HashMap<>();
        channels.forEach(element -> {
            JsonObject ch = element.getAsJsonObject();
            byId.put(ch.get("id").getAsString(), ch);
        });

        // CONFIG channel: source + revision must match the live Channel object.
        Channel configChannel = channelManager.getChannel("global");
        JsonObject configJson = byId.get("global");
        assertThat(configJson).isNotNull();
        assertThat(configJson.get("source").getAsString())
                .isEqualTo(ChannelSource.CONFIG.name());
        assertThat(configJson.get("revision").getAsLong())
                .isEqualTo(configChannel.getRevision());

        // RUNTIME channel: source + revision must match the live Channel object.
        Channel runtimeChannel = channelManager.getChannel("runtime-chat");
        JsonObject runtimeJson = byId.get("runtime-chat");
        assertThat(runtimeJson).isNotNull();
        assertThat(runtimeJson.get("source").getAsString())
                .isEqualTo(ChannelSource.RUNTIME.name());
        assertThat(runtimeJson.get("revision").getAsLong())
                .isEqualTo(runtimeChannel.getRevision());
    }

    @Test
    @DisplayName("broadcast channel_update (broadcastChannelUpdate) also carries source/revision")
    void broadcastChannelUpdateCarriesSourceAndRevision() {
        AuthenticatedSession root = authenticate("SUPER_ADMIN");

        // broadcastChannelUpdate sends to every authenticated+active session.
        handler.broadcastChannelUpdate();
        JsonObject message = root.readJson();

        assertThat(message.get("type").getAsString()).isEqualTo("channel_update");

        JsonArray channels = message.getAsJsonArray("channels");
        Map<String, JsonObject> byId = new HashMap<>();
        channels.forEach(element -> {
            JsonObject ch = element.getAsJsonObject();
            byId.put(ch.get("id").getAsString(), ch);
        });

        Channel configChannel = channelManager.getChannel("global");
        JsonObject configJson = byId.get("global");
        assertThat(configJson).isNotNull();
        assertThat(configJson.get("source").getAsString())
                .isEqualTo(ChannelSource.CONFIG.name());
        assertThat(configJson.get("revision").getAsLong())
                .isEqualTo(configChannel.getRevision());

        Channel runtimeChannel = channelManager.getChannel("runtime-chat");
        JsonObject runtimeJson = byId.get("runtime-chat");
        assertThat(runtimeJson).isNotNull();
        assertThat(runtimeJson.get("source").getAsString())
                .isEqualTo(ChannelSource.RUNTIME.name());
        assertThat(runtimeJson.get("revision").getAsLong())
                .isEqualTo(runtimeChannel.getRevision());
    }

    @Test
    @DisplayName("per-channel revision reflects mutation (bumpRevision) and stays in sync with WS payload")
    void revisionReflectsMutation() {
        AuthenticatedSession root = authenticate("SUPER_ADMIN");

        // Mutate the runtime channel — ChannelManager.updateChannel bumps revision.
        channelManager.updateChannel("runtime-chat", "Runtime Renamed", null, null);

        handler.handleMessage(root.session, "{\"type\":\"get_channels\"}");
        JsonObject message = root.readJson();
        JsonArray channels = message.getAsJsonArray("channels");

        JsonObject runtimeJson = null;
        for (int i = 0; i < channels.size(); i++) {
            JsonObject ch = channels.get(i).getAsJsonObject();
            if ("runtime-chat".equals(ch.get("id").getAsString())) {
                runtimeJson = ch;
                break;
            }
        }
        assertThat(runtimeJson).isNotNull();

        Channel runtimeChannel = channelManager.getChannel("runtime-chat");
        assertThat(runtimeChannel.getRevision()).isPositive();
        assertThat(runtimeJson.get("revision").getAsLong())
                .isEqualTo(runtimeChannel.getRevision());
        // Source is unchanged by updateChannel.
        assertThat(runtimeJson.get("source").getAsString())
                .isEqualTo(ChannelSource.RUNTIME.name());
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
