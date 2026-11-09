package com.nova.link.spy;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.SpyMessagePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SpyForwardingTest {
    private PermissionManager permissions;
    private SpyManager spies;
    private Set<ClientConnection> connections;

    @BeforeEach
    void setUp() {
        permissions = new PermissionManager();
        var channels = new ChannelManager();
        channels.createChannel(ChannelConfig.builder().id("global").scope(ChannelScope.GLOBAL).build());
        var network = mock(ServerNetworkHandler.class);
        connections = new HashSet<>();
        when(network.getConnections()).thenAnswer(invocation -> Set.copyOf(connections));
        spies = new SpyManager(permissions, channels, network);
    }

    @Test
    void eachSpyReceivesOnlyItsSelectedSource() {
        var alpha = monitor("alpha");
        var beta = monitor("beta");
        var all = monitor(null);
        ChatMessagePacket fromAlpha = message("alpha");
        ChatMessagePacket fromBeta = message("beta");
        ChatMessagePacket unknown = message(null);

        spies.forwardToSpies(fromAlpha);
        spies.forwardToSpies(fromBeta);
        spies.forwardToSpies(unknown);

        var alphaPackets = org.mockito.ArgumentCaptor.forClass(SpyMessagePacket.class);
        verify(alpha).sendPacket(alphaPackets.capture());
        assertThat(alphaPackets.getValue().getMessage().getClientId()).isEqualTo("alpha");
        var betaPackets = org.mockito.ArgumentCaptor.forClass(SpyMessagePacket.class);
        verify(beta).sendPacket(betaPackets.capture());
        assertThat(betaPackets.getValue().getMessage().getClientId()).isEqualTo("beta");
        verify(all, times(3)).sendPacket(any(SpyMessagePacket.class));
        assertThat(spies.getTotalSpySessionCount()).isEqualTo(3);
    }

    @Test
    void emptySourceStillMonitorsAllClients() {
        var all = monitor("");

        spies.forwardToSpies(message("alpha"));
        spies.forwardToSpies(message("beta"));

        verify(all, times(2)).sendPacket(any(SpyMessagePacket.class));
    }

    @Test
    void restartingMonitoringChangesItsSourceWithoutResettingTheSession() {
        var client = monitor("alpha");
        UUID admin = client.getSuperAdminUuid();
        SpySession session = spies.getSpySessions(admin).iterator().next();
        session.setCanSend(false);

        assertThat(spies.startSpying(admin, "global", "beta").isSuccess()).isTrue();
        assertThat(spies.getSpySessions(admin)).containsExactly(session);
        assertThat(session.getTargetClientId()).isEqualTo("beta");
        assertThat(session.canSend()).isFalse();
        assertThat(spies.getTotalSpySessionCount()).isEqualTo(1);
        spies.forwardToSpies(message("alpha"));
        spies.forwardToSpies(message("beta"));

        var received = org.mockito.ArgumentCaptor.forClass(SpyMessagePacket.class);
        verify(client).sendPacket(received.capture());
        assertThat(received.getValue().getMessage().getClientId()).isEqualTo("beta");
    }

    @Test
    void restartingWithNoSourceRestoresMonitoringAllClients() {
        var client = monitor("alpha");

        assertThat(spies.startSpying(client.getSuperAdminUuid(), "global", null).isSuccess()).isTrue();
        spies.forwardToSpies(message("alpha"));
        spies.forwardToSpies(message("beta"));

        verify(client, times(2)).sendPacket(any(SpyMessagePacket.class));
        assertThat(spies.getTotalSpySessionCount()).isEqualTo(1);
    }

    @Test
    void unauthenticatedConnectionCannotReceiveEvenWithAnActiveAdminSession() {
        var client = monitor("alpha");
        when(client.isAuthenticated()).thenReturn(false);

        spies.forwardToSpies(message("alpha"));

        verify(client, never()).sendPacket(any());
        when(client.isAuthenticated()).thenReturn(true);
        spies.forwardToSpies(message("alpha"));
        verify(client).sendPacket(any(SpyMessagePacket.class));
    }

    private ClientConnection monitor(String source) {
        UUID admin = UUID.randomUUID();
        permissions.registerSuperAdmin(new SuperAdminCredentials(admin, "test-hash"));
        permissions.authenticateSuperAdmin(admin, "test-hash");
        var client = mock(ClientConnection.class);
        when(client.getSuperAdminUuid()).thenReturn(admin);
        when(client.isAuthenticated()).thenReturn(true);
        when(client.isActive()).thenReturn(true);
        connections.add(client);
        assertThat(spies.startSpying(admin, "global", source).isSuccess()).isTrue();
        return client;
    }

    private ChatMessagePacket message(String source) {
        return new ChatMessagePacket(UUID.randomUUID(), "Sender", source, "global", "content");
    }
}
