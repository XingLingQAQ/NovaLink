package com.nova.link.spy;

import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.network.AdminActionHandler;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SpyAuthorizationTest {
    private PermissionManager permissions;
    private SpyManager spies;
    private ClientConnection adminClient;
    private ClientConnection targetClient;
    private UUID admin;

    @BeforeEach
    void setUp() {
        permissions = new PermissionManager();
        var channels = new ChannelManager();
        channels.createChannel(ChannelConfig.builder().id("watched").scope(ChannelScope.SERVER)
                .clientId("target").build());
        channels.createChannel(ChannelConfig.builder().id("second").scope(ChannelScope.SERVER)
                .clientId("target").build());
        var network = mock(ServerNetworkHandler.class);
        admin = UUID.randomUUID();
        permissions.registerSuperAdmin(new SuperAdminCredentials(admin, "test-hash"));
        permissions.authenticateSuperAdmin(admin, "test-hash");
        adminClient = mock(ClientConnection.class);
        when(adminClient.getSuperAdminUuid()).thenReturn(admin);
        when(adminClient.isAuthenticated()).thenReturn(true);
        when(adminClient.isActive()).thenReturn(true);
        targetClient = mock(ClientConnection.class);
        when(targetClient.isAuthenticated()).thenReturn(true);
        when(targetClient.isActive()).thenReturn(true);
        when(network.getConnections()).thenReturn(Set.of(adminClient, targetClient));
        when(network.findByClientId("target")).thenReturn(targetClient);
        spies = new SpyManager(permissions, channels, network);
        assertThat(spies.startSpying(admin, "watched", null).isSuccess()).isTrue();
        assertThat(spies.startSpying(admin, "second", null).isSuccess()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"revoked,false", "revoked,true", "expired,false", "expired,true"})
    void lostAuthorityStopsReceivingAndSendingAndClearsAllIndexes(String invalidation, boolean sending) {
        if (invalidation.equals("revoked")) {
            permissions.revokeSuperAdminSession(admin);
        } else {
            permissions.setSessionDurationMs(-60_000);
            permissions.authenticateSuperAdmin(admin, "test-hash");
        }
        assertThat(permissions.hasSuperAdminSession(admin)).isFalse();

        if (sending) {
            var result = spies.sendToMonitoredChannel(admin, "watched", "blocked");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("NC-403");
        } else {
            spies.forwardToSpies(message());
        }

        verify(adminClient, never()).sendPacket(any());
        verify(targetClient, never()).sendPacket(any());
        assertNoSessions();
        permissions.setSessionDurationMs(3_600_000);
        permissions.authenticateSuperAdmin(admin, "test-hash");
        spies.forwardToSpies(message());
        verify(adminClient, never()).sendPacket(any());
        assertThat(spies.sendToMonitoredChannel(admin, "watched", "still stopped").isSuccess()).isFalse();
    }

    @Test
    void explicitAdminLogoutClearsAllMonitoringBeforeReauthentication() {
        var handler = new AdminActionHandler(permissions);
        handler.setSpyManager(spies);

        assertThat(handler.handle(AdminActionPacket.createLogoutPacket(admin)).isSuccess()).isTrue();

        assertNoSessions();
        permissions.authenticateSuperAdmin(admin, "test-hash");
        spies.forwardToSpies(message());
        verify(adminClient, never()).sendPacket(any());
    }

    @Test
    void authorizedMonitoringStillReceivesAndCanSend() {
        spies.forwardToSpies(message());
        var captured = org.mockito.ArgumentCaptor.forClass(ChatMessagePacket.class);
        verify(adminClient).sendPacket(captured.capture());
        assertThat(captured.getValue().getContent()).isEqualTo("observed");
        assertThat(captured.getValue().getPlaceholders()).containsEntry("_spy", "true");
        assertThat(spies.sendToMonitoredChannel(admin, "watched", "reply").isSuccess()).isTrue();
        verify(targetClient).sendPacket(any(ChatMessagePacket.class));
        assertThat(spies.getTotalSpySessionCount()).isEqualTo(2);
    }

    private void assertNoSessions() {
        assertThat(spies.getSpySessions(admin)).isEmpty();
        assertThat(spies.getChannelSpies("watched")).isEmpty();
        assertThat(spies.getChannelSpies("second")).isEmpty();
        assertThat(spies.getTotalSpySessionCount()).isZero();
    }

    private ChatMessagePacket message() {
        return new ChatMessagePacket(UUID.randomUUID(), "Sender", "target", "watched", "observed");
    }
}
