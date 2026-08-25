package com.nova.link.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PanelRole;
import com.nova.link.auth.PanelUserCredentials;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VERIFY-011: WebSocket chaos injection (delay / duplicate / reorder / drop)
 * must not cause the client to roll back state, re-expose narrowed resources,
 * or accept stale reconnect-era messages.
 *
 * <p>The production handler ({@link WebSocketMessageHandler}) stamps every
 * outbound payload with a server-wide strictly-increasing {@code revision}
 * (PANEL-008 {@code revisionCounter}) and re-resolves the effective role on
 * every call, so a role downgrade takes effect without reconnecting. The
 * PANEL-008 docstring states clients use the revision to discard
 * out-of-order/stale updates. This test class exercises that contract with an
 * {@link EmbeddedChannel} per session - no real chaos proxy is needed because
 * the chaos is injected at the delivery layer (we capture the frames the
 * handler produces, then feed them to a simulated monotonic client guard in a
 * chaotic order).
 *
 * <p><b>Scope of the audit word "sessionEpoch":</b> the production code has no
 * per-session epoch field; the only monotonic guard the server emits is the
 * global {@code revision} counter (the JWT refresh-token family epoch in
 * {@code JwtService} is a separate, refresh-rotation concept and is not stamped
 * on WS payloads). This class therefore asserts on the {@code revision}
 * monotonic guard that actually exists, and the report flags the absence of a
 * dedicated session-epoch field as a residual gap.
 *
 * <p>Coverage (one @Test per audit scenario):
 * <ol>
 *   <li><b>duplicate sequence</b> - a repeated frame (same revision) is
 *       discarded by the client guard; revision stream stays monotonic; final
 *       snapshot equals a fresh fetch.</li>
 *   <li><b>reorder</b> - a delta (chat, higher revision) delivered before the
 *       snapshot (channel_update, lower revision); the stale snapshot is
 *       discarded; a fresh fetch re-converges the client.</li>
 *   <li><b>drop</b> - a middle frame is lost; the highest-revision frame the
 *       client received still matches a fresh snapshot.</li>
 *   <li><b>permission narrow</b> - after a role downgrade in {@link AuthManager},
 *       the next snapshot excludes the narrowed channel and prunes the
 *       subscription; a stale pre-downgrade frame (lower revision) replayed to
 *       the guard is discarded, so the narrowed resource is not re-exposed.</li>
 *   <li><b>reconnect stale</b> - after the old session's channel is closed,
 *       {@code session.send} is a no-op (channel inactive) and the new session's
 *       first frame carries a revision strictly greater than every frame the
 *       old session produced, so a stale old-session frame is discarded by the
 *       monotonic guard.</li>
 *   <li><b>delay</b> - a delayed lower-revision frame arriving after newer
 *       frames is discarded; final snapshot still converges.</li>
 * </ol>
 *
 * <p>No production source is modified. The only test seam is the
 * {@link MonotonicClientGuard} simulated client, which lives entirely in this
 * test file.
 */
@DisplayName("VERIFY-011: WS chaos (delay/duplicate/reorder/drop) keeps revision monotonic and snapshot consistent")
class WebSocketChaosReorderTest {

    private static final String SECRET = "test-secret-key-for-jwt-signing-minimum-32-chars";

    private JwtService jwtService;
    private AuthManager authManager;
    private ChannelManager channelManager;
    private WebSocketMessageHandler handler;
    private ServerNetworkHandler network;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new JwtService(SECRET);
        authManager = new AuthManager(new IpBanManager(5, 60_000));
        channelManager = new ChannelManager();
        // GLOBAL channel visible to every role.
        channelManager.createChannel(ChannelConfig.builder()
                .id("global").displayName("Global").scope(ChannelScope.GLOBAL).build(),
                ChannelSource.CONFIG);
        // SERVER channel visible to ADMIN+ only.
        channelManager.createChannel(ChannelConfig.builder()
                .id("server-1").displayName("Survival").scope(ChannelScope.SERVER)
                .clientId("Survival").build(),
                ChannelSource.RUNTIME);
        // PRIVATE channel visible to SUPER_ADMIN only.
        channelManager.createChannel(ChannelConfig.builder()
                .id("private-1").displayName("Private").scope(ChannelScope.PRIVATE)
                .clientId("Survival").build(),
                ChannelSource.RUNTIME);

        MemoryProvider db = new MemoryProvider();
        db.initialize();
        network = mock(ServerNetworkHandler.class);
        when(network.getConnections()).thenReturn(java.util.Set.of());
        when(network.getConnectionCount()).thenReturn(0);

        handler = new WebSocketMessageHandler(
                jwtService,
                authManager,
                channelManager,
                network,
                new PlayerStateManager(db));
    }

    // ============================ Scenarios ============================

    @Test
    @DisplayName("1. duplicate sequence: client guard discards duplicate revision, snapshot stays consistent")
    void duplicateSequenceIsIdempotent() {
        Fixture admin = authenticate("admin", "ADMIN");

        // Snapshot 1 (channel_update, revision R1).
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject snapshot1 = admin.readJson();
        long r1 = snapshot1.get("revision").getAsLong();

        // Mutate to force a second, distinct snapshot (revision R2 > R1).
        channelManager.updateChannel("server-1", "Survival Renamed", null, null);
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject snapshot2 = admin.readJson();
        long r2 = snapshot2.get("revision").getAsLong();
        assertThat(r2).isGreaterThan(r1);

        // Simulate a duplicate of snapshot1 arriving on the wire (same revision).
        MonotonicClientGuard guard = new MonotonicClientGuard();
        assertThat(guard.apply(snapshot2)).isTrue();   // R2 applied first
        assertThat(guard.apply(snapshot1)).isFalse();  // R1 < R2 -> discarded (stale)
        assertThat(guard.apply(snapshot1)).isFalse();  // duplicate of R1 -> discarded

        // The guard's latest channel_update must equal a fresh fetch.
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject fresh = admin.readJson();
        assertThat(guard.latestChannelUpdate().get("revision").getAsLong())
                .isEqualTo(snapshot2.get("revision").getAsLong());
        assertThat(channelIds(guard.latestChannelUpdate()))
                .isEqualTo(channelIds(fresh));
        // Server's produced revisions are strictly monotonic across the stream.
        assertThat(fresh.get("revision").getAsLong()).isGreaterThan(r2);
    }

    @Test
    @DisplayName("2. reorder: delta delivered before snapshot is discarded; fresh fetch re-converges")
    void reorderDeltaBeforeSnapshotConverges() {
        Fixture admin = authenticate("admin", "ADMIN");
        admin.session.subscribe("server-1");

        // Snapshot (channel_update) - revision Rs.
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject snapshot = admin.readJson();
        long rs = snapshot.get("revision").getAsLong();

        // Delta (chat broadcast) - revision Rc > Rs, built after the snapshot.
        handler.broadcastChatMessage("server-1", null, "Alice", "hello");
        JsonObject chat = admin.readJson();
        long rc = chat.get("revision").getAsLong();
        assertThat(rc).isGreaterThan(rs);

        // Reorder: deliver the chat (higher revision) BEFORE the snapshot.
        MonotonicClientGuard guard = new MonotonicClientGuard();
        assertThat(guard.apply(chat)).isTrue();      // Rc applied
        assertThat(guard.apply(snapshot)).isFalse(); // Rs < Rc -> stale snapshot discarded

        // The client has the chat content but its channel_update slot is empty
        // because the stale snapshot was discarded - it must refetch to converge.
        assertThat(guard.latestChannelUpdate()).isNull();

        // Fresh fetch (revision Rf > Rc) re-converges the client.
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject fresh = admin.readJson();
        long rf = fresh.get("revision").getAsLong();
        assertThat(rf).isGreaterThan(rc);
        assertThat(guard.apply(fresh)).isTrue();
        assertThat(channelIds(guard.latestChannelUpdate())).isEqualTo(channelIds(fresh));
    }

    @Test
    @DisplayName("3. drop: a middle frame lost in transit still converges to the latest snapshot")
    void dropMiddleMessageStillConverges() {
        Fixture admin = authenticate("admin", "ADMIN");

        // Three snapshots, strictly increasing revisions.
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject s1 = admin.readJson();
        channelManager.updateChannel("global", "G1", null, null);
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject s2 = admin.readJson();
        channelManager.updateChannel("global", "G2", null, null);
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject s3 = admin.readJson();

        long r1 = s1.get("revision").getAsLong();
        long r2 = s2.get("revision").getAsLong();
        long r3 = s3.get("revision").getAsLong();
        assertThat(r2).isGreaterThan(r1);
        assertThat(r3).isGreaterThan(r2);

        // Drop s2 (middle frame); deliver s1 then s3.
        MonotonicClientGuard guard = new MonotonicClientGuard();
        assertThat(guard.apply(s1)).isTrue();
        // s2 is lost - never delivered.
        assertThat(guard.apply(s3)).isTrue();

        // Client's latest equals s3 (the highest revision it saw).
        assertThat(guard.latestChannelUpdate().get("revision").getAsLong()).isEqualTo(r3);

        // A fresh fetch matches the guard's latest state.
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject fresh = admin.readJson();
        assertThat(channelIds(guard.latestChannelUpdate())).isEqualTo(channelIds(fresh));
        assertThat(fresh.get("revision").getAsLong()).isGreaterThan(r3);
    }

    @Test
    @DisplayName("4. permission narrow: downgraded role excludes channel, prunes subscription, stale frame not re-exposed")
    void permissionNarrowDoesNotReExposeResource() {
        // Start the account at ADMIN so 'server-1' is visible and subscribable.
        authManager.registerPanelUser(new PanelUserCredentials(
                "admin", AuthManager.hashPassword("pw"), PanelRole.ADMIN));
        Fixture admin = authenticate("admin", "ADMIN");

        // Subscribe to server-1 - allowed at ADMIN.
        handler.handleMessage(admin.session,
                "{\"type\":\"subscribe\",\"channels\":[\"global\",\"server-1\"]}");
        admin.readJson(); // drain subscribed confirmation
        assertThat(admin.session.isSubscribed("server-1")).isTrue();

        // Pre-downgrade snapshot includes server-1 (revision R_old).
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject beforeNarrow = admin.readJson();
        assertThat(channelIds(beforeNarrow)).contains("server-1");
        long rOld = beforeNarrow.get("revision").getAsLong();

        // Narrow the role to VIEWER in the live AuthManager. resolveRole runs
        // on every call, so the next snapshot must reflect the downgrade
        // without reconnecting.
        authManager.registerPanelUser(new PanelUserCredentials(
                "admin", AuthManager.hashPassword("pw"), PanelRole.VIEWER));

        // Post-downgrade snapshot: server-1 must be excluded AND the
        // subscription pruned (revision R_new > R_old).
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject afterNarrow = admin.readJson();
        long rNew = afterNarrow.get("revision").getAsLong();
        assertThat(rNew).isGreaterThan(rOld);
        assertThat(channelIds(afterNarrow)).containsExactly("global");
        assertThat(admin.session.isSubscribed("server-1"))
                .as("subscription pruned after role downgrade")
                .isFalse();

        // Replay the stale pre-downgrade frame (which exposes server-1) to a
        // monotonic client guard AFTER it has applied the post-downgrade frame.
        // The guard must discard the stale frame (lower revision), so the
        // narrowed resource is never re-exposed to the client.
        MonotonicClientGuard guard = new MonotonicClientGuard();
        assertThat(guard.apply(afterNarrow)).isTrue();
        assertThat(guard.apply(beforeNarrow)).isFalse();
        assertThat(channelIds(guard.latestChannelUpdate())).containsExactly("global");
        assertThat(guard.latestChannelUpdate().get("revision").getAsLong()).isEqualTo(rNew);

        // A fresh broadcast also excludes server-1 for the downgraded session.
        handler.broadcastChannelUpdate();
        JsonObject broadcast = admin.readJson();
        assertThat(channelIds(broadcast)).containsExactly("global");
    }

    @Test
    @DisplayName("5. reconnect stale: closed session's send is a no-op; new session's revision strictly greater")
    void reconnectStaleMessagesDiscarded() {
        Fixture first = authenticate("root", "SUPER_ADMIN");
        first.session.subscribe("global");

        // Old session produces several frames; capture the max revision.
        handler.handleMessage(first.session, "{\"type\":\"get_channels\"}");
        JsonObject s1 = first.readJson();
        handler.broadcastChatMessage("global", null, "Alice", "stale");
        JsonObject chat1 = first.readJson();
        long oldMax = Math.max(
                s1.get("revision").getAsLong(),
                chat1.get("revision").getAsLong());

        // Reconnect: close the old session's channel. session.send must
        // become a no-op (channel.isActive() == false), so any "in-flight"
        // stale frame produced against the old session is not actually
        // written to the wire.
        first.channel.close().awaitUninterruptibly();
        assertThat(first.session.isActive()).isFalse();
        // A broadcast after close must not enqueue anything on the old channel.
        handler.broadcastChannelUpdate();
        Object staleOutbound = first.channel.readOutbound();
        assertThat(staleOutbound).isNull();

        // New session for the same user (fresh EmbeddedChannel).
        Fixture second = authenticate("root", "SUPER_ADMIN");

        // The new session's first frame must carry a revision strictly
        // greater than every frame the old session produced - the global
        // counter only moves forward - so a stale old-session frame replayed
        // to the new client is discarded by the monotonic guard.
        handler.handleMessage(second.session, "{\"type\":\"get_channels\"}");
        JsonObject newSnapshot = second.readJson();
        long newRev = newSnapshot.get("revision").getAsLong();
        assertThat(newRev).isGreaterThan(oldMax);

        MonotonicClientGuard guard = new MonotonicClientGuard();
        assertThat(guard.apply(newSnapshot)).isTrue();
        // Replay the stale old-session snapshot to the new client guard.
        assertThat(guard.apply(s1)).isFalse();
        assertThat(guard.latestChannelUpdate().get("revision").getAsLong()).isEqualTo(newRev);
    }

    @Test
    @DisplayName("6. delay: a delayed lower-revision frame arriving after newer frames is discarded")
    void delayedMessageDoesNotBreakFinalConsistency() {
        Fixture admin = authenticate("admin", "ADMIN");

        // Produce two snapshots; hold the first back (delay), deliver the
        // second first.
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject first = admin.readJson();
        channelManager.updateChannel("global", "Delayed Update", null, null);
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject second = admin.readJson();

        long rFirst = first.get("revision").getAsLong();
        long rSecond = second.get("revision").getAsLong();
        assertThat(rSecond).isGreaterThan(rFirst);

        // Deliver the newer (second) frame first, then the delayed older one.
        MonotonicClientGuard guard = new MonotonicClientGuard();
        assertThat(guard.apply(second)).isTrue();
        assertThat(guard.apply(first)).isFalse(); // delayed, lower revision -> discarded

        // Final snapshot converges to the newest state.
        handler.handleMessage(admin.session, "{\"type\":\"get_channels\"}");
        JsonObject fresh = admin.readJson();
        assertThat(guard.latestChannelUpdate().get("revision").getAsLong())
                .isEqualTo(second.get("revision").getAsLong());
        assertThat(channelIds(guard.latestChannelUpdate())).isEqualTo(channelIds(fresh));
        assertThat(fresh.get("revision").getAsLong()).isGreaterThan(rSecond);

        // The guard never applied a revision older than its current - monotonic.
        assertThat(guard.lastAppliedRevision()).isEqualTo(rSecond);
    }

    // ============================ Helpers ============================

    /**
     * Simulated panel client that applies the PANEL-008 monotonic-revision
     * guard: a frame is accepted only when its top-level {@code revision} is
     * strictly greater than the last applied revision; otherwise the frame is
     * discarded as stale/duplicate/reordered. The guard remembers the latest
     * accepted frame per {@code type} so the test can assert final state.
     *
     * <p>This mirrors the client-side discard rule described in the
     * {@link WebSocketMessageHandler} class docstring. It lives only in the
     * test - production has no client guard because the panel frontend
     * implements it in JS.
     */
    static final class MonotonicClientGuard {
        private long lastAppliedRevision = 0L;
        private final java.util.Map<String, JsonObject> latestByType = new HashMap<>();

        /** Returns true when the frame was applied, false when discarded. */
        boolean apply(JsonObject frame) {
            if (frame == null || !frame.has("revision")) {
                return false;
            }
            long rev = frame.get("revision").getAsLong();
            if (rev <= lastAppliedRevision) {
                return false; // stale / duplicate / reordered - discard
            }
            lastAppliedRevision = rev;
            String type = frame.has("type") ? frame.get("type").getAsString() : "unknown";
            latestByType.put(type, frame);
            return true;
        }

        JsonObject latestChannelUpdate() {
            return latestByType.get("channel_update");
        }

        long lastAppliedRevision() {
            return lastAppliedRevision;
        }
    }

    private Fixture authenticate(String username, String role) {
        EmbeddedChannel channel = new EmbeddedChannel();
        WebSocketSession session = new WebSocketSession(channel);
        handler.registerSession(session);
        String token = jwtService.generateToken(username, username, role);
        handler.handleMessage(session, "{\"type\":\"auth\",\"token\":\"" + token + "\"}");
        // Drain the auth_response so subsequent reads see only test payloads.
        TextWebSocketFrame authFrame = channel.readOutbound();
        assertThat(authFrame).isNotNull();
        // Assert the auth actually succeeded so a silent auth failure does not
        // masquerade as a chaos-guard success later in the test.
        JsonObject authResponse = JsonParser.parseString(authFrame.text()).getAsJsonObject();
        authFrame.release();
        assertThat(authResponse.get("success").getAsBoolean())
                .as("auth must succeed for %s/%s", username, role)
                .isTrue();
        return new Fixture(channel, session);
    }

    private static Set<String> channelIds(JsonObject channelUpdate) {
        Set<String> ids = new LinkedHashSet<>();
        JsonArray channels = channelUpdate.getAsJsonArray("channels");
        channels.forEach(element -> ids.add(element.getAsJsonObject().get("id").getAsString()));
        return new HashSet<>(ids);
    }

    private record Fixture(EmbeddedChannel channel, WebSocketSession session) {
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
