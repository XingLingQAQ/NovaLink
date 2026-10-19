package com.nova.chat.bungee.platform;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.channel.ProxyChannelProtocol;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Downstream plugin-message send semantics on BungeeCord: an empty server
 * reports failure (no connection exists to write through) and a populated one
 * delegates to {@code ServerInfo#sendData}.
 */
@DisplayName("Bungee platform plugin-message send")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BungeeProxyPlatformPluginMessageTest {

    private static final String SERVER = "survival-01";

    @Mock
    private NovaChatBungee plugin;
    @Mock
    private ProxyServer proxyServer;
    @Mock
    private ServerInfo serverInfo;
    @Mock
    private ProxiedPlayer player;

    private BungeeProxyPlatform platform;
    private final byte[] payload = {1, 2, 3};

    @BeforeEach
    void setUp() {
        when(plugin.getProxy()).thenReturn(proxyServer);
        when(proxyServer.getServerInfo(SERVER)).thenReturn(serverInfo);
        when(serverInfo.getPlayers()).thenReturn(List.of(player));
        platform = new BungeeProxyPlatform(plugin);
    }

    @Test
    @DisplayName("a populated server delegates to sendData with queueing enabled")
    void populatedServerSends() {
        when(serverInfo.sendData(eq(ProxyChannelProtocol.CHANNEL_ID), eq(payload), eq(true)))
                .thenReturn(true);

        boolean sent = platform.sendPluginMessage(
                SERVER, ProxyChannelProtocol.CHANNEL_ID, payload);

        assertThat(sent).isTrue();
        verify(serverInfo).sendData(ProxyChannelProtocol.CHANNEL_ID, payload, true);
    }

    @Test
    @DisplayName("a server with no players returns false instead of a silent success")
    void noPlayersReturnsFalse() {
        when(serverInfo.getPlayers()).thenReturn(List.of());

        boolean sent = platform.sendPluginMessage(
                SERVER, ProxyChannelProtocol.CHANNEL_ID, payload);

        assertThat(sent).isFalse();
        verify(serverInfo, never()).sendData(any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("an unknown server returns false")
    void unknownServerReturnsFalse() {
        when(proxyServer.getServerInfo("nope")).thenReturn(null);

        assertThat(platform.sendPluginMessage("nope", ProxyChannelProtocol.CHANNEL_ID, payload))
                .isFalse();
    }

    @Test
    @DisplayName("sendData reporting failure is propagated")
    void sendDataFailurePropagates() {
        when(serverInfo.sendData(eq(ProxyChannelProtocol.CHANNEL_ID), eq(payload), eq(true)))
                .thenReturn(false);

        assertThat(platform.sendPluginMessage(SERVER, ProxyChannelProtocol.CHANNEL_ID, payload))
                .isFalse();
    }

    @Test
    @DisplayName("null arguments are rejected without touching the proxy")
    void nullArgumentsReturnFalse() {
        assertThat(platform.sendPluginMessage(null, ProxyChannelProtocol.CHANNEL_ID, payload))
                .isFalse();
        assertThat(platform.sendPluginMessage(SERVER, ProxyChannelProtocol.CHANNEL_ID, null))
                .isFalse();
    }
}
