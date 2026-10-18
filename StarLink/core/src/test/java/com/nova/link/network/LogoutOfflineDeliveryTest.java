package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end logout → private-message routing with the real
 * {@link PlayerStateManager} (no mocked cache).
 *
 * <p>The bug this guards against: a proxy holds one shared TCP connection for
 * every downstream server, so {@code networkHandler.findByClientId} stays live
 * after a player logs out. Private messages were therefore delivered to the
 * proxy, which could only echo them back to the sender — a fake "sent"
 * confirmation. The fix has two halves and this test exercises both:
 *
 * <ol>
 *   <li>a {@code logout=true} LEAVE evicts the player from the online cache
 *       (the network-wide roster consulted by the DM handler);</li>
 *   <li>the DM handler treats "not in the cache" as offline, regardless of the
 *       shared connection being alive.</li>
 * </ol>
 *
 * <p>The direct-mode cases (one connection per game server) assert the
 * connection leg still decides when the player is cached, so that deployment
 * shape does not regress.
 */
@DisplayName("logout eviction → private message offline routing (real cache)")
class LogoutOfflineDeliveryTest {

    /** The single shared connection a proxy authenticates with. */
    private static final String PROXY_CLIENT_ID = "velocity-proxy";
    /** The per-server connection a direct deployment authenticates with. */
    private static final String SERVER_CLIENT_ID = "survival-01";

    private MemoryProvider databaseProvider;
    private ChannelManager channelManager;
    private PlayerStateManager playerStateManager;
    private ChannelActionHandler channelActionHandler;

    private ServerNetworkHandler networkHandler;
    private ClientConnection proxyConnection;
    private ClientConnection directServerConnection;
    private PrivateMessageHandler privateMessageHandler;

    private UUID steveId;
    private UUID alexId;

    @BeforeEach
    void setUp() throws Exception {
        databaseProvider = new MemoryProvider();
        databaseProvider.initialize();
        channelManager = new ChannelManager();
        playerStateManager = new PlayerStateManager(databaseProvider);

        channelActionHandler = new ChannelActionHandler(
                channelManager,
                playerStateManager,
                databaseProvider,
                new PrivateChannelManager(channelManager),
                new InvitationManager(databaseProvider, channelManager),
                new PermissionManager());

        networkHandler = mock(ServerNetworkHandler.class);
        proxyConnection = mockConnection(PROXY_CLIENT_ID);
        directServerConnection = mockConnection(SERVER_CLIENT_ID);
        when(networkHandler.findByClientId(PROXY_CLIENT_ID)).thenReturn(proxyConnection);
        when(networkHandler.findByClientId(SERVER_CLIENT_ID)).thenReturn(directServerConnection);

        privateMessageHandler = new PrivateMessageHandler(networkHandler, playerStateManager,
                null, null, () -> true, null);

        channelManager.createChannel(ChannelConfig.builder()
                .id("global")
                .scope(ChannelScope.GLOBAL)
                .build());

        steveId = UUID.randomUUID();
        alexId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        databaseProvider.shutdown();
    }

    private ClientConnection mockConnection(String clientId) {
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.getClientId()).thenReturn(clientId);
        when(connection.getConnectionId()).thenReturn("conn-" + clientId);
        when(connection.isActive()).thenReturn(true);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.sendPacket(any())).thenReturn(CompletableFuture.completedFuture(null));
        return connection;
    }

    private ChannelActionPacket channelAction(ChannelAction action, UUID playerId,
                                              String playerName, String channelId, boolean logout) {
        ChannelActionPacket packet = new ChannelActionPacket(action, channelId);
        packet.addExtra("playerId", playerId.toString());
        packet.addExtra("playerName", playerName);
        if (logout) {
            packet.addExtra("logout", "true");
        }
        return packet;
    }

    private ChannelActionResponsePacket join(ClientConnection connection, UUID playerId,
                                             String playerName, String channelId) {
        return channelActionHandler.handle(connection,
                channelAction(ChannelAction.JOIN, playerId, playerName, channelId, false));
    }

    private ChannelActionResponsePacket leave(ClientConnection connection, UUID playerId,
                                              String playerName, String channelId, boolean logout) {
        return channelActionHandler.handle(connection,
                channelAction(ChannelAction.LEAVE, playerId, playerName, channelId, logout));
    }

    private PrivateMessagePacket dmFromSteveTo(String targetName) {
        // senderClientId is rewritten by the handler; the sender is resolved from
        // the player cache by name.
        return new PrivateMessagePacket(steveId, "Steve", "",
                targetName, new UUID(0L, 0L), "are you there?", 0L);
    }

    private ChannelActionResponsePacket expectError(ClientConnection sender) {
        ArgumentCaptor<ChannelActionResponsePacket> captor =
                ArgumentCaptor.forClass(ChannelActionResponsePacket.class);
        verify(sender).sendPacket(captor.capture());
        return captor.getValue();
    }

    // ==================== proxy: one shared connection ====================

    @Test
    @DisplayName("proxy: DM to a player who logged out answers NC-404 instead of a fake delivery")
    void loggedOutPlayerIsOfflineBehindProxy() {
        assertThat(join(proxyConnection, steveId, "Steve", "global").isSuccess()).isTrue();
        assertThat(join(proxyConnection, alexId, "Alex", "global").isSuccess()).isTrue();

        // Alex logs out. The proxy keeps its single connection up for Steve, so
        // findByClientId(PROXY_CLIENT_ID) still resolves to a live connection.
        assertThat(leave(proxyConnection, alexId, "Alex", "global", true).isSuccess()).isTrue();
        assertThat(playerStateManager.getCachedState(alexId)).isEmpty();

        privateMessageHandler.handle(proxyConnection, dmFromSteveTo("Alex"));

        ChannelActionResponsePacket error = expectError(proxyConnection);
        assertThat(error.isSuccess()).isFalse();
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
        assertThat(error.getExtra("reason")).isEqualTo("private_message");
        assertThat(error.getExtra("detail")).isEqualTo("not_online");
    }

    @Test
    @DisplayName("proxy: a logout that lands after resolution still rejects (no stale-cache delivery)")
    void logoutBetweenResolutionAndDelivery() {
        join(proxyConnection, steveId, "Steve", "global");
        join(proxyConnection, alexId, "Alex", "global");

        // Capture the state object the name resolution would have returned,
        // then log the player out before the (re)check runs.
        assertThat(playerStateManager.getCachedState(alexId)).isPresent();
        leave(proxyConnection, alexId, "Alex", "global", true);

        privateMessageHandler.handle(proxyConnection, dmFromSteveTo("alex"));

        assertThat(expectError(proxyConnection).getExtra("detail")).isEqualTo("not_online");
        verify(proxyConnection, never()).sendPacket(any(com.nova.chat.common.protocol.packets.PrivateMessagePacket.class));
    }

    @Test
    @DisplayName("proxy: a plain /nc leave keeps the player cache and DMs still deliver")
    void plainLeaveDoesNotMakePlayerOffline() {
        join(proxyConnection, steveId, "Steve", "global");
        join(proxyConnection, alexId, "Alex", "global");

        assertThat(leave(proxyConnection, alexId, "Alex", "global", false).isSuccess()).isTrue();
        assertThat(playerStateManager.getCachedState(alexId)).isPresent();

        privateMessageHandler.handle(proxyConnection, dmFromSteveTo("Alex"));

        // Both players share the proxy connection, so exactly one packet is sent
        // and it is the completed DM, not an error.
        ArgumentCaptor<PrivateMessagePacket> captor =
                ArgumentCaptor.forClass(PrivateMessagePacket.class);
        verify(proxyConnection).sendPacket(captor.capture());
        assertThat(captor.getValue().getTargetId()).isEqualTo(alexId);
    }

    // ==================== direct mode: one connection per server ====================

    @Test
    @DisplayName("direct mode: cached player on a live server connection still receives DMs")
    void directModeOnlineDelivers() {
        join(directServerConnection, steveId, "Steve", "global");
        join(directServerConnection, alexId, "Alex", "global");

        privateMessageHandler.handle(directServerConnection, dmFromSteveTo("Alex"));

        ArgumentCaptor<PrivateMessagePacket> captor =
                ArgumentCaptor.forClass(PrivateMessagePacket.class);
        verify(directServerConnection).sendPacket(captor.capture());
        assertThat(captor.getValue().getTargetId()).isEqualTo(alexId);
    }

    @Test
    @DisplayName("direct mode: cached player whose server connection vanished is offline (no regression)")
    void directModeServerGoneIsOffline() {
        join(directServerConnection, steveId, "Steve", "global");
        join(directServerConnection, alexId, "Alex", "global");
        // The downstream server's connection dropped without a logout LEAVE
        // (crash/network loss): the player is still cached but unreachable.
        when(networkHandler.findByClientId(SERVER_CLIENT_ID)).thenReturn(null);

        privateMessageHandler.handle(directServerConnection, dmFromSteveTo("Alex"));

        ChannelActionResponsePacket error = expectError(directServerConnection);
        assertThat(error.getErrorCode()).isEqualTo("NC-404");
        assertThat(error.getExtra("detail")).isEqualTo("not_online");
    }

    @Test
    @DisplayName("direct mode: a target server whose connection is not authenticated is offline")
    void directModeUnauthenticatedServerIsOffline() {
        // Steve is on survival-01; Alex is on a second server whose connection is
        // up but has not authenticated. The target leg must reject.
        join(directServerConnection, steveId, "Steve", "global");
        ClientConnection halfOpenServer = mockConnection("creative-01");
        when(networkHandler.findByClientId("creative-01")).thenReturn(halfOpenServer);
        join(halfOpenServer, alexId, "Alex", "global");
        when(halfOpenServer.isAuthenticated()).thenReturn(false);

        privateMessageHandler.handle(directServerConnection, dmFromSteveTo("Alex"));

        assertThat(expectError(directServerConnection).getErrorCode()).isEqualTo("NC-404");
        verify(halfOpenServer, never()).sendPacket(any(PrivateMessagePacket.class));
    }
}
