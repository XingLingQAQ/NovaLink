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
