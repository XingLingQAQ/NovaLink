package com.nova.link.network;

import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.mute.MuteManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Channel-routing tests for {@link ItemDisplayHandler}: authentication,
 * channel boundary, mute/ban rejection with error responses, GLOBAL/SERVER
 * fan-out target sets, cross-server switch and the shared rate-limit bucket.
 */
@DisplayName("ItemDisplayHandler routing")
class ItemDisplayHandlerTest {

    private ChannelManager channelManager;
    private ServerNetworkHandler networkHandler;
    private MuteManager muteManager;
    private BanManager banManager;
    private AtomicBoolean crossServerEnabled;

    private ClientConnection survivalConn;
    private ClientConnection creativeConn;
    private UUID senderId;

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
        networkHandler = mock(ServerNetworkHandler.class);
        muteManager = mock(MuteManager.class);
        banManager = mock(BanManager.class);
        crossServerEnabled = new AtomicBoolean(true);
        senderId = UUID.randomUUID();

        channelManager.createChannel(ChannelConfig.builder()
                .id("global").displayName("Global").scope(ChannelScope.GLOBAL).build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("staff").displayName("Staff").scope(ChannelScope.GLOBAL)
                .permission("novachat.channel.staff").build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("local").displayName("Local").scope(ChannelScope.SERVER)
                .clientId("Survival").build());

        survivalConn = mockConn("Survival", true, true);
        creativeConn = mockConn("Creative", true, true);
        Set<ClientConnection> conns = new LinkedHashSet<>();
        conns.add(survivalConn);
        conns.add(creativeConn);
        when(networkHandler.getConnections()).thenReturn(conns);
        when(networkHandler.findByClientId("Survival")).thenReturn(survivalConn);
        when(networkHandler.findByClientId("Creative")).thenReturn(creativeConn);
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

    private ItemDisplayHandler handler(RateLimiter rateLimiter) {
        return new ItemDisplayHandler(channelManager, networkHandler, muteManager, banManager,
                rateLimiter, (clientId, perm) -> "Creative".equals(clientId),
                crossServerEnabled::get);
    }

    private ItemDisplayPacket packet(String channelId) {
        return new ItemDisplayPacket(senderId, "Steve", channelId, "{\"id\":\"minecraft:stone\"}",
                System.currentTimeMillis());
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

        handler(null).handle(unauth, packet("global"));

        verify(unauth, never()).sendPacket(any());
        verify(survivalConn, never()).sendPacket(any());
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("unknown channel answers NC-404")
    void unknownChannel() {
        handler(null).handle(survivalConn, packet("missing"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.isSuccess()).isFalse();
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
        assertThat(error.getExtra("reason")).isEqualTo("item_display");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("SERVER channel rejects a foreign client with NC-403")
    void crossClientDenied() {
        handler(null).handle(creativeConn, packet("local"));

        ChannelActionResponsePacket error = capturedError(creativeConn);
        assertThat(error.isSuccess()).isFalse();
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
        verify(survivalConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("muted sender is rejected with NC-403")
    void mutedSenderRejected() {
        when(muteManager.isMuted(senderId, "global")).thenReturn(true);

        handler(null).handle(survivalConn, packet("global"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("banned sender is rejected with NC-403")
    void bannedSenderRejected() {
        when(banManager.isBanned(senderId, "global")).thenReturn(true);

        handler(null).handle(survivalConn, packet("global"));

        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-403");
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("GLOBAL fan-out reaches every authenticated client including the source")
    void globalFanOutIncludesSource() {
        ItemDisplayPacket packet = packet("global");

        handler(null).handle(survivalConn, packet);

        verify(survivalConn).sendPacket(packet);
        verify(creativeConn).sendPacket(packet);
    }

    @Test
    @DisplayName("GLOBAL fan-out honors the channel permission node")
    void globalFanOutHonorsPermission() {
        // permissionChecker only grants "Creative" the staff node.
        ItemDisplayPacket packet = packet("staff");

        handler(null).handle(survivalConn, packet);

        verify(creativeConn).sendPacket(packet);
        verify(survivalConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("GLOBAL fan-out is suppressed when cross-server chat is disabled")
    void crossServerDisabledSuppressesGlobal() {
        crossServerEnabled.set(false);

        handler(null).handle(survivalConn, packet("global"));

        verify(survivalConn, never()).sendPacket(any());
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("SERVER channel fan-out targets only the owning client (source echo)")
    void serverFanOutTargetsBoundClient() {
        ItemDisplayPacket packet = packet("local");

        handler(null).handle(survivalConn, packet);

        verify(survivalConn).sendPacket(packet);
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("inactive connections are skipped in GLOBAL fan-out")
    void inactiveConnectionsSkipped() {
        when(creativeConn.isActive()).thenReturn(false);
        ItemDisplayPacket packet = packet("global");

        handler(null).handle(survivalConn, packet);

        verify(survivalConn).sendPacket(packet);
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("item displays share the chat token bucket and are dropped when exhausted")
    void rateLimitSharedBucket() {
        // Frozen clock: 2-token bucket, no refill.
        RateLimiter limiter = new RateLimiter(10, 2, () -> 0L);
        // Simulate two chat messages consuming the sender's bucket.
        assertThat(limiter.tryAcquire(survivalConn.getConnectionId())).isTrue();
        assertThat(limiter.tryAcquire(survivalConn.getConnectionId())).isTrue();

        handler(limiter).handle(survivalConn, packet("global"));

        // Dropped: no fan-out; the sender got the throttled NC-429 notice.
        verify(creativeConn, never()).sendPacket(any());
        ChannelActionResponsePacket error = capturedError(survivalConn);
        assertThat(error.getErrorCode()).isEqualTo("NC-429");

        // A second violation within the notify window stays silent.
        handler(limiter).handle(survivalConn, packet("global"));
        verify(survivalConn).sendPacket(any()); // still exactly one packet total
    }
}
