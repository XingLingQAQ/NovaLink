package com.nova.chat.velocity.listener;

import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.proxy.channel.ProxyChannelHandler;
import com.nova.chat.velocity.NovaChatVelocity;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetSocketAddress;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Velocity shell security guarantees: the channel is consumed on every source,
 * and the server-it-actually-came-from is what the shared handler verifies.
 */
@DisplayName("Velocity PluginMessageListener")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginMessageListenerTest {

    private static final String SERVER = "survival-01";

    @Mock
    private NovaChatVelocity plugin;
    @Mock
    private ProxyChannelHandler handler;
    @Mock
    private ServerConnection serverConnection;
    @Mock
    private Player player;
    @Mock
    private ChannelMessageSink sink;

    private PluginMessageListener listener;
    private final MinecraftChannelIdentifier identifier =
            MinecraftChannelIdentifier.from(ProxyChannelProtocol.CHANNEL_ID);

    @BeforeEach
    void setUp() {
        when(plugin.getProxyChannelHandler()).thenReturn(handler);
        when(serverConnection.getServerInfo())
                .thenReturn(new ServerInfo(SERVER, new InetSocketAddress("127.0.0.1", 25566)));
        listener = new PluginMessageListener(plugin);
    }

    @Test
    @DisplayName("a server-sourced payload is consumed and handed to the shared handler")
    void serverSourceIsConsumedAndHandled() throws Exception {
        byte[] payload = ProxyChannelProtocol.encode(SERVER, chat());
        PluginMessageEvent event = new PluginMessageEvent(
                serverConnection, sink, identifier, payload);

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed())
                .as("handled() must suppress forwarding")
                .isFalse();
        verify(handler).handleFromServer(SERVER, payload);
        verify(handler, never()).handleFromServer(null, payload);
    }

    @Test
    @DisplayName("a player-sourced payload is consumed and rejected as non-server")
    void playerSourceIsConsumedAndRejected() throws Exception {
        byte[] payload = ProxyChannelProtocol.encode(SERVER, chat());
        PluginMessageEvent event = new PluginMessageEvent(
                player, sink, identifier, payload);

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed()).isFalse();
        verify(handler).handleFromServer(null, payload);
        verify(handler, never()).handleFromServer(SERVER, payload);
    }

    @Test
    @DisplayName("other channels are left untouched")
    void otherChannelsAreIgnored() {
        PluginMessageEvent event = new PluginMessageEvent(
                player, sink, MinecraftChannelIdentifier.from("minecraft:brand"),
                new byte[]{1});

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed())
                .as("unrelated channels keep the default forward result")
                .isTrue();
        verify(handler, never()).handleFromServer(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a missing handler is tolerated (very early event)")
    void missingHandlerIsTolerated() {
        when(plugin.getProxyChannelHandler()).thenReturn(null);
        PluginMessageListener early = new PluginMessageListener(plugin);
        PluginMessageEvent event = new PluginMessageEvent(
                serverConnection, sink, identifier, new byte[]{1});

        early.onPluginMessage(event);

        assertThat(event.getResult().isAllowed()).isFalse();
    }

    private static ChatMessagePacket chat() {
        return new ChatMessagePacket(UUID.randomUUID(), "Alice", SERVER, "global", "hi");
    }
}
