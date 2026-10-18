package com.nova.link.network;

import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.i18n.I18n;
import com.nova.link.log.ChatLogger;
import com.nova.link.mute.MuteManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validation-chain and dual-delivery tests for {@link PrivateMessageHandler}:
 * authentication, shared rate-limit bucket, feature toggle, global mute,
 * target resolution (case-insensitive; offline -> NC-404), self-message
 * rejection, packet completion (targetId / senderClientId / timestamp) and the
 * target + sender-echo delivery set, plus [DM] audit.
 *
 * <p>Item-18 (提案 08) server-authoritative ignore enforcement: when an
 * {@link PrivateMessageHandler.IgnoreLookup} is injected the DM is rejected
 * before delivery if either party ignores the other. Sender-ignores-target
 * answers NC-403 {@code ignored_by_sender} (the sender's own choice, no leak);
 * target-ignores-sender answers NC-404 {@code not_online} reusing the existing
 * offline wording so it is indistinguishable from the target being offline
 * (no-leak). A null lookup (legacy 6-arg wiring) and a lookup that throws both
 * fail open: the DM is still delivered, never blocked on a persistence gap.
 */
@DisplayName("PrivateMessageHandler routing")
class PrivateMessageHandlerTest {

    private ServerNetworkHandler networkHandler;
    private PlayerStateManager playerStateManager;
    private MuteManager muteManager;
    private ChatLogger chatLogger;
    private AtomicBoolean featureEnabled;

    private ClientConnection survivalConn;
    private ClientConnection creativeConn;

    private UUID steveId;
    private UUID alexId;
    private PlayerState steveState;
    private PlayerState alexState;

    @BeforeEach
    void setUp() {
        networkHandler = mock(ServerNetworkHandler.class);
        playerStateManager = mock(PlayerStateManager.class);
        muteManager = mock(MuteManager.class);
        chatLogger = mock(ChatLogger.class);
        featureEnabled = new AtomicBoolean(true);

        survivalConn = mockConn("Survival", true, true);
        creativeConn = mockConn("Creative", true, true);
        when(networkHandler.findByClientId("Survival")).thenReturn(survivalConn);
        when(networkHandler.findByClientId("Creative")).thenReturn(creativeConn);

        steveId = UUID.randomUUID();
        alexId = UUID.randomUUID();
        steveState = new PlayerState(steveId, "Steve");
        steveState.setClientId("Survival");
        alexState = new PlayerState(alexId, "Alex");
        alexState.setClientId("Creative");
        when(playerStateManager.getAllPlayerStates()).thenReturn(List.of(steveState, alexState));
        // Online players are cached; PrivateMessageHandler consults this leg
        // explicitly to detect a player who logged out behind a proxy.
        when(playerStateManager.getCachedState(steveId))
                .thenReturn(java.util.Optional.of(steveState));
        when(playerStateManager.getCachedState(alexId))
                .thenReturn(java.util.Optional.of(alexState));
    }

    private ClientConnection mockConn(String clientId, boolean auth, boolean active) {
        ClientConnection c = mock(ClientConnection.class);
        when(c.getClientId()).thenReturn(clientId);
        when(c.isAuthenticated()).thenReturn(auth);
        when(c.isActive()).thenReturn(active);
        when(c.getConnectionId()).thenReturn("conn-" + clientId);
        when(c.sendPacket(any())).thenReturn(CompletableFuture.completedFuture(null));
        return c;
    }

    private PrivateMessageHandler handler(RateLimiter rateLimiter) {
        return new PrivateMessageHandler(networkHandler, playerStateManager, muteManager,
                rateLimiter, featureEnabled::get, chatLogger);
    }

    /** 7-arg overload with an injected {@link PrivateMessageHandler.IgnoreLookup}. */
    private PrivateMessageHandler handler(RateLimiter rateLimiter,
                                          PrivateMessageHandler.IgnoreLookup ignoreLookup) {
        return new PrivateMessageHandler(networkHandler, playerStateManager, muteManager,
                rateLimiter, featureEnabled::get, chatLogger, ignoreLookup);
    }

    /** C->S form packet: nil targetId, no timestamp, Steve -> targetName. */
    private PrivateMessagePacket packet(String targetName) {
        return new PrivateMessagePacket(steveId, "Steve", "Survival",
                targetName, new UUID(0L, 0L), "hi there", 0L);
    }

    private ChannelActionResponsePacket capturedError(ClientConnection sender) {
        ArgumentCaptor<ChannelActionResponsePacket> captor =
                ArgumentCaptor.forClass(ChannelActionResponsePacket.class);
        verify(sender).sendPacket(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("unauthenticated sender is dropped silently")
    void unauthenticatedDropped() {
        ClientConnection unauth = mockConn(null, false, true);

        handler(null).handle(unauth, packet("Alex"));

        verify(unauth, never()).sendPacket(any());
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("private messages share the chat token bucket (NC-429, throttled notice)")
    void rateLimitSharedBucket() {
        // Frozen clock: 2-token bucket, no refill.
        RateLimiter limiter = new RateLimiter(10, 2, () -> 0L);
        // Simulate two chat messages consuming the sender's bucket.
        assertThat(limiter.tryAcquire(survivalConn.getConnectionId())).isTrue();
        assertThat(limiter.tryAcquire(survivalConn.getConnectionId())).isTrue();

        handler(limiter).handle(survivalConn, packet("Alex"));

        verify(creativeConn, never()).sendPacket(any());
        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-429");
        assertThat(error.getExtra("reason")).isEqualTo("private_message");

        // A second violation within the notify window stays silent.
        handler(limiter).handle(survivalConn, packet("Alex"));
        verify(survivalConn).sendPacket(any()); // still exactly one packet total
    }

    @Test
    @DisplayName("feature toggle off answers NC-403")
    void featureDisabled() {
        featureEnabled.set(false);

        handler(null).handle(survivalConn, packet("Alex"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.isSuccess()).isFalse();
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
        assertThat(error.getExtra("reason")).isEqualTo("private_message");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("globally muted sender is rejected with NC-403")
    void globallyMutedSenderRejected() {
        when(muteManager.isMuted(steveId, null)).thenReturn(true);

        handler(null).handle(survivalConn, packet("Alex"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("channel-only mute does not block private messages")
    void channelMuteDoesNotBlock() {
        // Only the global (null-channel) lookup is consulted.
        when(muteManager.isMuted(steveId, null)).thenReturn(false);

        handler(null).handle(survivalConn, packet("Alex"));

        verify(creativeConn).sendPacket(any(PrivateMessagePacket.class));
    }

    @Test
    @DisplayName("unknown target name answers NC-404")
    void unknownTarget() {
        handler(null).handle(survivalConn, packet("Nobody"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
        assertThat(error.getExtra("reason")).isEqualTo("private_message");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("target whose client is not connected answers NC-404 (offline)")
    void offlineTarget() {
        when(networkHandler.findByClientId("Creative")).thenReturn(null);

        handler(null).handle(survivalConn, packet("Alex"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
    }

    // ==================== proxy logout: cache-based offline detection ====================

    @Test
    @DisplayName("proxy logout: target missing from the player cache answers NC-404, not a fake success")
    void loggedOutTargetBehindSharedProxy() {
        // Behind a proxy all players share ONE authenticated TCP connection,
        // so findByClientId("Creative") stays live after the player logs out.
        // Only the player cache knows the target is gone.
        when(playerStateManager.getCachedState(alexId)).thenReturn(java.util.Optional.empty());

        handler(null).handle(survivalConn, packet("Alex"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.isSuccess()).isFalse();
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
        assertThat(error.getExtra("reason")).isEqualTo("private_message");
        assertThat(error.getExtra("detail")).isEqualTo("not_online");
        assertThat(error.getMessage()).isEqualTo(I18n.tr("network.error.player_not_online", "Alex"));
        // No delivery to the shared proxy connection and no audit line.
        verify(creativeConn, never()).sendPacket(any());
        verify(chatLogger, never()).logPrivateMessage(anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a target evicted between name resolution and delivery is still rejected")
    void targetEvictedAfterResolution() {
        // Resolution scans getAllPlayerStates(); the cache re-check by id closes
        // the race where a logout lands in between. Simulate the two views
        // disagreeing: the name scan still sees the state, the id lookup does not.
        when(playerStateManager.getAllPlayerStates()).thenReturn(List.of(steveState, alexState));
        when(playerStateManager.getCachedState(alexId)).thenReturn(java.util.Optional.empty());

        handler(null).handle(survivalConn, packet("Alex"));

        assertThat(capturedError(survivalConn).getExtra("detail")).isEqualTo("not_online");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("direct mode: cached target whose server connection is down answers NC-404")
    void directModeOfflineServerStillRejected() {
        // Direct (non-proxy) deployments have one connection per game server.
        // The player is still cached (their world/server did not report a
        // logout) but the server's connection dropped: the connection leg must
        // keep rejecting so direct-mode offline detection does not regress.
        when(networkHandler.findByClientId("Creative")).thenReturn(null);

        handler(null).handle(survivalConn, packet("Alex"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
        assertThat(error.getExtra("detail")).isEqualTo("not_online");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("direct mode: cached target on its own live connection still receives the DM")
    void directModeOnlineDelivers() {
        // Regression guard for the same path: both legs pass, so delivery is
        // unchanged from the pre-fix behavior.
        handler(null).handle(survivalConn, packet("Alex"));

        verify(creativeConn).sendPacket(any(PrivateMessagePacket.class));
        verify(chatLogger).logPrivateMessage(steveId.toString(), "Steve",
                alexId.toString(), "Alex", "hi there");
    }

    @Test
    @DisplayName("target on an inactive connection answers NC-404 (offline)")
    void inactiveTargetConnection() {
        when(creativeConn.isActive()).thenReturn(false);

        handler(null).handle(survivalConn, packet("Alex"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
    }

    @Test
    @DisplayName("self-message is rejected with NC-403")
    void selfMessageRejected() {
        handler(null).handle(survivalConn, packet("Steve"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("self-message match is case-insensitive too")
    void selfMessageCaseInsensitive() {
        handler(null).handle(survivalConn, packet("sTeVe"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
    }

    @Test
    @DisplayName("success: dual delivery (target client + sender echo) with completed fields")
    void successDualDelivery() {
        PrivateMessagePacket packet = packet("alex"); // case-insensitive lookup

        handler(null).handle(survivalConn, packet);

        // Same completed packet instance goes to both connections.
        verify(creativeConn).sendPacket(packet);
        verify(survivalConn).sendPacket(packet);

        // Backend completed the packet.
        assertThat(packet.getTargetId()).isEqualTo(alexId);
        assertThat(packet.getTargetName()).isEqualTo("Alex"); // canonical casing
        assertThat(packet.getSenderClientId()).isEqualTo("Survival");
        assertThat(packet.getTimestamp()).isPositive();
    }

    // ==================== H3 fix: sender-identity spoofing containment ====================

    @Test
    @DisplayName("forged senderId is overwritten with the server-authoritative UUID (name lookup)")
    void forgedSenderIdOverwritten() {
        // Attacker forges senderId = a random UUID (e.g. an admin's id) to bypass
        // mute/ignore checks. The server's player cache keys authority on the
        // packet's own senderName, so the forged UUID must be replaced by
        // Steve's real UUID before any consumer reads it.
        UUID forgedId = UUID.randomUUID();
        PrivateMessagePacket packet = new PrivateMessagePacket(forgedId, "Steve", "Survival",
                "Alex", new UUID(0L, 0L), "claiming to be someone else", 0L);

        handler(null).handle(survivalConn, packet);

        assertThat(packet.getSenderId()).isEqualTo(steveId);
        verify(creativeConn).sendPacket(packet);
        // Audit line must record the server-authoritative id, not the forged one.
        verify(chatLogger).logPrivateMessage(steveId.toString(), "Steve",
                alexId.toString(), "Alex", "claiming to be someone else");
    }

    @Test
    @DisplayName("forged senderName casing is canonicalised via the player cache")
    void forgedSenderNameCanonicalised() {
        // Casing-only forgery: "sTeVe". Resolution is case-insensitive, so the
        // handler must still find Steve and stamp the canonical name.
        PrivateMessagePacket packet = new PrivateMessagePacket(UUID.randomUUID(), "sTeVe", "Survival",
                "Alex", new UUID(0L, 0L), "hi", 0L);

        handler(null).handle(survivalConn, packet);

        assertThat(packet.getSenderId()).isEqualTo(steveId);
        assertThat(packet.getSenderName()).isEqualTo("Steve");
        verify(creativeConn).sendPacket(packet);
    }

    @Test
    @DisplayName("unresolvable senderName is silently dropped (no leak)")
    void unresolvableSenderNameDropped() {
        // "Eve" is not in the network-wide player cache — the handler has no
        // authoritative id for her and silently drops the packet (no error
        // reply, no delivery, no audit; never leak whether a player exists).
        PrivateMessagePacket packet = new PrivateMessagePacket(UUID.randomUUID(), "Eve", "Survival",
                "Alex", new UUID(0L, 0L), "hi", 0L);

        handler(null).handle(survivalConn, packet);

        verify(survivalConn, never()).sendPacket(any());
        verify(creativeConn, never()).sendPacket(any());
        verify(chatLogger, never()).logPrivateMessage(anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("globally muted sender rejected even when packet carries another (bypass) id")
    void forgedSenderIdDoesNotBypassMute() {
        // Real sender Steve is muted. Attacker sets senderName="Steve" but
        // forges senderId to a non-muted random UUID. The handler must still
        // consult the mute manager with Steve's real id and reject.
        when(muteManager.isMuted(steveId, null)).thenReturn(true);
        UUID forgedId = UUID.randomUUID();
        PrivateMessagePacket packet = new PrivateMessagePacket(forgedId, "Steve", "Survival",
                "Alex", new UUID(0L, 0L), "hi", 0L);

        handler(null).handle(survivalConn, packet);

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("target-ignores-sender enforced against the server-authoritative sender id")
    void forgedSenderIdDoesNotBypassIgnore() {
        // Alex ignores the real Steve. Attacker forges senderId to a known-unblocked
        // UUID. The ignore lookup must be queried with Steve's real id and reject.
        PrivateMessageHandler.IgnoreLookup lookup = (src, tgt) ->
                alexId.equals(src) && steveId.equals(tgt);
        UUID forgedId = UUID.randomUUID();
        PrivateMessagePacket packet = new PrivateMessagePacket(forgedId, "Steve", "Survival",
                "Alex", new UUID(0L, 0L), "hi", 0L);

        handler(null, lookup).handle(survivalConn, packet);

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
        assertThat(error.getExtra("detail")).isEqualTo("not_online");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("sender ignoring target cannot bypass by forging senderId either")
    void forgedSenderIdCannotBypassSenderSideIgnore() {
        // Real Steve ignores Alex. Attacker forges senderId so the lookup
        // (if it had used the packet's claim) would return false.
        PrivateMessageHandler.IgnoreLookup lookup = (src, tgt) ->
                steveId.equals(src) && alexId.equals(tgt);
        UUID forgedId = UUID.randomUUID();
        PrivateMessagePacket packet = new PrivateMessagePacket(forgedId, "Steve", "Survival",
                "Alex", new UUID(0L, 0L), "hi", 0L);

        handler(null, lookup).handle(survivalConn, packet);

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
        assertThat(error.getExtra("detail")).isEqualTo("ignored_by_sender");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("self-message via forged senderId is rejected as self (canonical id comparison)")
    void selfMessageViaForgedIdStillRejected() {
        // senderName="Steve" resolves to steveId; forging senderId to steveId
        // makes targetId == resolved senderId == steveId when targetName="Steve".
        // The self check uses the authoritative id, so it still rejects as self.
        PrivateMessagePacket packet = new PrivateMessagePacket(steveId, "Steve", "Survival",
                "Steve", new UUID(0L, 0L), "note to self", 0L);

        handler(null).handle(survivalConn, packet);

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getExtra("detail")).isEqualTo("self");
        verify(survivalConn).sendPacket(any()); // only the error
    }

    @Test
    @DisplayName("same-connection sender and target receive a single packet")
    void sameConnectionSingleDelivery() {
        alexState.setClientId("Survival"); // both players on the Survival client

        PrivateMessagePacket packet = packet("Alex");
        handler(null).handle(survivalConn, packet);

        verify(survivalConn).sendPacket(packet); // exactly once
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("delivered private message is audited via ChatLogger with the DM marker")
    void deliveredMessageAudited() {
        handler(null).handle(survivalConn, packet("Alex"));

        verify(chatLogger).logPrivateMessage(steveId.toString(), "Steve",
                alexId.toString(), "Alex", "hi there");
    }

    @Test
    @DisplayName("rejected private message is not audited")
    void rejectedMessageNotAudited() {
        handler(null).handle(survivalConn, packet("Nobody"));

        verify(chatLogger, never()).logPrivateMessage(anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    // ==================== item-18 server-authoritative ignore enforcement ====================

    @Test
    @DisplayName("sender ignoring target is rejected with NC-403 ignored_by_sender")
    void senderIgnoresTargetRejected() {
        // Only the sender->target direction is blocked.
        PrivateMessageHandler.IgnoreLookup lookup = (src, tgt) ->
                steveId.equals(src) && alexId.equals(tgt);

        handler(null, lookup).handle(survivalConn, packet("Alex"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.isSuccess()).isFalse();
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
        assertThat(error.getExtra("reason")).isEqualTo("private_message");
        assertThat(error.getExtra("detail")).isEqualTo("ignored_by_sender");
        assertThat(error.getMessage()).contains("Alex");
        // Target is never disturbed and the DM is never audited.
        verify(creativeConn, never()).sendPacket(any());
        verify(chatLogger, never()).logPrivateMessage(anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("target ignoring sender is rejected as NC-404 not_online (no leak)")
    void targetIgnoresSenderNoLeak() {
        // Only the target->sender direction is blocked; sender does not ignore target.
        PrivateMessageHandler.IgnoreLookup lookup = (src, tgt) ->
                alexId.equals(src) && steveId.equals(tgt);

        handler(null, lookup).handle(survivalConn, packet("Alex"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
        assertThat(error.getExtra("detail")).isEqualTo("not_online");
        // Reuses the offline wording — no distinct "you are blocked" message.
        assertThat(error.getMessage()).isEqualTo(I18n.tr("network.error.player_not_online", "Alex"));
        // No delivery, no audit — same observables as the offline branch.
        verify(creativeConn, never()).sendPacket(any());
        verify(chatLogger, never()).logPrivateMessage(anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("mutual block still answers NC-404 not_online (target privacy wins)")
    void mutualBlockNoLeak() {
        // Both directions blocked: the target-privacy path must win so the
        // sender never learns the target blocked them back.
        PrivateMessageHandler.IgnoreLookup lookup = (src, tgt) -> true;

        handler(null, lookup).handle(survivalConn, packet("Alex"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
        assertThat(error.getExtra("detail")).isEqualTo("not_online");
        verify(creativeConn, never()).sendPacket(any());
        verify(chatLogger, never()).logPrivateMessage(anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("null ignoreLookup (legacy 6-arg wiring) still delivers the DM")
    void nullIgnoreLookupLegacyDelivers() {
        // Construct via the 6-arg factory (null lookup) — identical to the
        // existing success path, asserting legacy wiring is unaffected.
        handler(null).handle(survivalConn, packet("Alex"));

        verify(creativeConn).sendPacket(any(PrivateMessagePacket.class));
        verify(chatLogger).logPrivateMessage(steveId.toString(), "Steve",
                alexId.toString(), "Alex", "hi there");
    }

    @Test
    @DisplayName("ignoreLookup that throws still delivers (fail-open, never block DM)")
    void ignoreLookupThrowsFailOpen() {
        // Non-throwing IgnoreLookup cannot declare DatabaseException, so the
        // caller wraps it in a RuntimeException inside the lambda. The handler
        // must swallow it and treat the relation as unknown (allow delivery).
        PrivateMessageHandler.IgnoreLookup lookup = (src, tgt) -> {
            throw new RuntimeException("simulated persistence gap");
        };

        handler(null, lookup).handle(survivalConn, packet("Alex"));

        verify(creativeConn).sendPacket(any(PrivateMessagePacket.class));
        verify(chatLogger).logPrivateMessage(steveId.toString(), "Steve",
                alexId.toString(), "Alex", "hi there");
    }

    @Test
    @DisplayName("self-message still answers NC-403 self when the lookup would match")
    void selfMessageStillBeforeIgnore() {
        // senderId == targetId; the ignore check guards against self-relations
        // (safeIsIgnored returns false for sourceId.equals(targetId)), so the
        // clearer self-message error wins.
        PrivateMessageHandler.IgnoreLookup lookup = (src, tgt) -> true;

        handler(null, lookup).handle(survivalConn, packet("Steve"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
        assertThat(error.getExtra("detail")).isEqualTo("self");
        verify(creativeConn, never()).sendPacket(any());
    }
}
