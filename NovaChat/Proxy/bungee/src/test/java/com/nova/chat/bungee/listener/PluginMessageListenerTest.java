package com.nova.chat.bungee.listener;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.proxy.channel.ProxyChannelHandler;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BungeeCord shell security guarantees: the channel is cancelled on every
 * source, and the server it actually came from is what the shared handler
 * verifies.
 */
@DisplayName("Bungee PluginMessageListener")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginMessageListenerTest {

    private static final String SERVER = "survival-01";

    @Mock
    private NovaChatBungee plugin;
    @Mock
    private ProxyChannelHandler handler;
    @Mock
    private Server server;
    @Mock
    private ServerInfo serverInfo;
    @Mock
    private ProxiedPlayer player;
    @Mock
    private Connection receiver;

    private PluginMessageListener listener;

    @BeforeEach
    void setUp() {
        when(plugin.getProxyChannelHandler()).thenReturn(handler);
        when(server.getInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn(SERVER);
        listener = new PluginMessageListener(plugin);
    }

    @Test
    @DisplayName("a server-sourced payload is cancelled and handed to the shared handler")
    void serverSourceIsCancelledAndHandled() throws Exception {
        byte[] payload = ProxyChannelProtocol.encode(SERVER, chat());
        PluginMessageEvent event = new PluginMessageEvent(server, receiver,
                ProxyChannelProtocol.CHANNEL_ID, payload);

        listener.onPluginMessage(event);

        assertThat(event.isCancelled())
                .as("cancelling is what stops the payload reaching the client")
                .isTrue();
        verify(handler).handleFromServer(SERVER, payload);
        verify(handler, never()).handleFromServer(null, payload);
    }

    @Test
    @DisplayName("a player-sourced payload is cancelled and rejected as non-server")
    void playerSourceIsCancelledAndRejected() throws Exception {
        byte[] payload = ProxyChannelProtocol.encode(SERVER, chat());
        PluginMessageEvent event = new PluginMessageEvent(player, receiver,
                ProxyChannelProtocol.CHANNEL_ID, payload);

        listener.onPluginMessage(event);

        assertThat(event.isCancelled()).isTrue();
        verify(handler).handleFromServer(null, payload);
        verify(handler, never()).handleFromServer(SERVER, payload);
    }

    @Test
    @DisplayName("other channels are left untouched")
    void otherChannelsAreIgnored() {
        PluginMessageEvent event = new PluginMessageEvent(player, receiver,
                "BungeeCord", new byte[]{1});

        listener.onPluginMessage(event);

        assertThat(event.isCancelled()).isFalse();
        verify(handler, never()).handleFromServer(any(), any());
    }

    @Test
    @DisplayName("a missing handler is tolerated (very early event)")
    void missingHandlerIsTolerated() {
        when(plugin.getProxyChannelHandler()).thenReturn(null);
        PluginMessageListener early = new PluginMessageListener(plugin);
        PluginMessageEvent event = new PluginMessageEvent(server, receiver,
                ProxyChannelProtocol.CHANNEL_ID, new byte[]{1});

        early.onPluginMessage(event);

        assertThat(event.isCancelled()).isTrue();
    }

    private static ChatMessagePacket chat() {
        return new ChatMessagePacket(UUID.randomUUID(), "Alice", SERVER, "global", "hi");
    }
}
