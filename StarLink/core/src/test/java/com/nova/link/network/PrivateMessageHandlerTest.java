package com.nova.link.network;

import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
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
}
