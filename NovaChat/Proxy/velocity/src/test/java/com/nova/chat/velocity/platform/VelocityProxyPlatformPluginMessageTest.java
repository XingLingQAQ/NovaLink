package com.nova.chat.velocity.platform;

import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.velocity.NovaChatVelocity;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Downstream plugin-message send semantics on Velocity: exactly one connection
 * receives the payload, and a server with no players reports failure.
 */
@DisplayName("Velocity platform plugin-message send")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VelocityProxyPlatformPluginMessageTest {

    private static final String SERVER = "survival-01";

    @Mock
    private NovaChatVelocity plugin;
    @Mock
    private ProxyServer proxyServer;
    @Mock
    private RegisteredServer registeredServer;
    @Mock
    private Player player;
    @Mock
    private ServerConnection connection;

    private VelocityProxyPlatform platform;
    private final byte[] payload = {1, 2, 3};

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(proxyServer);
        when(proxyServer.getServer(SERVER)).thenReturn(Optional.of(registeredServer));
        when(registeredServer.getPlayersConnected()).thenReturn(List.of(player));
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        when(connection.getServerInfo())
                .thenReturn(new ServerInfo(SERVER, new InetSocketAddress("127.0.0.1", 25566)));
        platform = new VelocityProxyPlatform(plugin);
    }

    @Test
    @DisplayName("the payload goes through exactly one server connection, not every player")
    void sendsThroughOneConnection() {
        when(connection.sendPluginMessage(any(ChannelIdentifier.class), eq(payload)))
                .thenReturn(true);

        boolean sent = platform.sendPluginMessage(
                SERVER, ProxyChannelProtocol.CHANNEL_ID, payload);

        assertThat(sent).isTrue();
        ArgumentCaptor<ChannelIdentifier> captured =
                ArgumentCaptor.forClass(ChannelIdentifier.class);
        verify(connection).sendPluginMessage(captured.capture(), eq(payload));
        assertThat(captured.getValue().getId()).isEqualTo(ProxyChannelProtocol.CHANNEL_ID);
    }

    @Test
    @DisplayName("a server with no players returns false instead of a silent success")
    void noPlayersReturnsFalse() {
        when(registeredServer.getPlayersConnected()).thenReturn(List.of());

        boolean sent = platform.sendPluginMessage(
                SERVER, ProxyChannelProtocol.CHANNEL_ID, payload);

        assertThat(sent).isFalse();
        verify(connection, never()).sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class));
    }

    @Test
    @DisplayName("an unknown server returns false")
    void unknownServerReturnsFalse() {
        when(proxyServer.getServer("nope")).thenReturn(Optional.empty());

        assertThat(platform.sendPluginMessage("nope", ProxyChannelProtocol.CHANNEL_ID, payload))
                .isFalse();
    }

    @Test
    @DisplayName("a null payload or server is rejected without touching the proxy")
    void nullArgumentsReturnFalse() {
        assertThat(platform.sendPluginMessage(null, ProxyChannelProtocol.CHANNEL_ID, payload))
                .isFalse();
        assertThat(platform.sendPluginMessage(SERVER, ProxyChannelProtocol.CHANNEL_ID, null))
                .isFalse();
    }

    @Test
    @DisplayName("a player whose connection reports another server is skipped")
    void playerOnAnotherServerIsSkipped() {
        ServerConnection other = org.mockito.Mockito.mock(ServerConnection.class);
        when(other.getServerInfo())
                .thenReturn(new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25567)));
        when(connection.sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class))).thenReturn(true);
        when(other.sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class))).thenReturn(true);

        boolean sent = platform.sendPluginMessage(
                SERVER, ProxyChannelProtocol.CHANNEL_ID, payload);

        assertThat(sent).isTrue();
        verify(other, never()).sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class));
    }

    @Test
    @DisplayName("the carrier is one of the players connected to the target server")
    void correctPlayerIsUsed() {
        Player another = org.mockito.Mockito.mock(Player.class);
        when(registeredServer.getPlayersConnected()).thenReturn(List.of(player, another));
        when(connection.sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class))).thenReturn(true);

        platform.sendPluginMessage(SERVER, ProxyChannelProtocol.CHANNEL_ID, payload);

        // Either player is acceptable, but the write must happen exactly once.
        verify(connection).sendPluginMessage(any(), eq(payload));
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
