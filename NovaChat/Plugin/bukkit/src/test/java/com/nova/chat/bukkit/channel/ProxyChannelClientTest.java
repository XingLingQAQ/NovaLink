package com.nova.chat.bukkit.channel;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sub-server half of the channel: registration, upstream send semantics
 * (including the no-players case) and hostile downstream payload handling.
 */
@DisplayName("Bukkit ProxyChannelClient")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProxyChannelClientTest {

    private static final String SERVER_ID = "survival-01";
    private static final String CHANNEL = ProxyChannelProtocol.CHANNEL_ID;

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private Server server;
    @Mock
    private Messenger messenger;
    @Mock
    private Player carrier;
    @Mock
    private Player listenerPlayer;

    private final List<Captured> received = new ArrayList<>();
    private ProxyChannelClient client;

    @BeforeEach
    void setUp() {
        // This module runs JUnit per_class, so the instance (and this list)
        // outlive one test method.
        received.clear();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(server.getMessenger()).thenReturn(messenger);
        when(config.isPluginMessagingEnabled()).thenReturn(true);
        when(config.getPluginMessagingServerId()).thenReturn(SERVER_ID);
        when(server.getOnlinePlayers()).thenReturn((java.util.Collection) List.of(carrier));
        when(carrier.getName()).thenReturn("Alice");

        client = new ProxyChannelClient(plugin,
                (senderId, packet, excluded) -> received.add(new Captured(senderId, packet, excluded)));
        // Sends are only legal on a registered outgoing channel.
        client.register();
    }

    // ------------------------------------------------------------- registration

    @Test
    @DisplayName("register() wires both directions exactly once")
    void registerWiresBothDirections() {
        // setUp already registered once; a second call is a no-op.
        assertThat(client.register()).isFalse();

        verify(messenger).registerOutgoingPluginChannel(plugin, CHANNEL);
        verify(messenger).registerIncomingPluginChannel(plugin, CHANNEL, client);
    }

    @Test
    @DisplayName("an unregistered channel returns false instead of throwing from async chat")
    void unregisteredSendReturnsFalse() {
        client.unregister();
        // Re-registering is the normal path, but the send must not be attempted
        // while unregistered (Bukkit throws from sendPluginMessage then).
        when(config.isPluginMessagingEnabled()).thenReturn(true);

        assertThat(client.send(chat("hi"))).isFalse();
        verify(carrier, never()).sendPluginMessage(any(), any(), any());
    }

    @Test
    @DisplayName("unregister() releases both directions")
    void unregisterReleasesBothDirections() {
        client.register();
        client.unregister();

        verify(messenger).unregisterIncomingPluginChannel(plugin, CHANNEL, client);
        verify(messenger).unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    // ------------------------------------------------------------------ sending

    @Test
    @DisplayName("an upstream packet is encoded with the configured server id and sent")
    void sendEncodesAndSends() throws Exception {
        boolean sent = client.send(chat("hello"));

        assertThat(sent).isTrue();
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(carrier).sendPluginMessage(eq(plugin), eq(CHANNEL), captor.capture());
        ProxyChannelProtocol.Decoded decoded =
                ProxyChannelProtocol.decode(captor.getValue());
        assertThat(decoded.getSenderId()).isEqualTo(SERVER_ID);
        assertThat(decoded.getPacket()).isInstanceOf(ChatMessagePacket.class);
        assertThat(((ChatMessagePacket) decoded.getPacket()).getContent()).isEqualTo("hello");
    }

    @Test
    @DisplayName("with no players online, send returns false instead of pretending success")
    void noPlayersReturnsFalse() {
        when(server.getOnlinePlayers()).thenReturn((java.util.Collection) List.of());

        boolean sent = client.send(chat("nobody home"));

        assertThat(sent).isFalse();
        verify(carrier, never()).sendPluginMessage(any(), any(), any());
    }

    @Test
    @DisplayName("a disabled bridge never sends")
    void disabledBridgeDoesNotSend() {
        when(config.isPluginMessagingEnabled()).thenReturn(false);

        assertThat(client.send(chat("disabled"))).isFalse();
        verify(carrier, never()).sendPluginMessage(any(), any(), any());
    }

    @Test
    @DisplayName("an unencodable packet returns false instead of throwing")
    void oversizedPacketReturnsFalse() {
        ChatMessagePacket huge = chat("x".repeat(ProxyChannelProtocol.MAX_PAYLOAD_BYTES));

        assertThat(client.send(huge)).isFalse();
        verify(carrier, never()).sendPluginMessage(any(), any(), any());
    }

    // ---------------------------------------------------------------- receiving

    @Test
    @DisplayName("a downstream payload is decoded and dispatched with its exclusion list")
    void downstreamPayloadIsDispatched() throws Exception {
        UUID excluded = UUID.randomUUID();
        byte[] payload = ProxyChannelProtocol.encode("proxy-1", chat("fan-out"),
                Set.of(excluded));

        client.onPluginMessageReceived(CHANNEL, listenerPlayer, payload);

        assertThat(received).hasSize(1);
        Captured captured = received.get(0);
        assertThat(captured.senderId).isEqualTo("proxy-1");
        assertThat(captured.packet).isInstanceOf(ChatMessagePacket.class);
        assertThat(((ChatMessagePacket) captured.packet).getContent()).isEqualTo("fan-out");
        assertThat(captured.excluded).containsExactly(excluded);
    }

    @Test
    @DisplayName("a malformed payload is dropped silently (no exception, no dispatch)")
    void malformedPayloadIsDropped() {
        client.onPluginMessageReceived(CHANNEL, listenerPlayer, new byte[]{9, 9, 9});

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("an oversized payload is dropped silently")
    void oversizedPayloadIsDropped() {
        client.onPluginMessageReceived(CHANNEL, listenerPlayer,
                new byte[ProxyChannelProtocol.MAX_PAYLOAD_BYTES + 1]);

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("another channel is ignored")
    void otherChannelIgnored() throws Exception {
        client.onPluginMessageReceived("minecraft:brand", listenerPlayer,
                ProxyChannelProtocol.encode("proxy-1", chat("hi")));

        assertThat(received).isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    private static ChatMessagePacket chat(String content) {
        return new ChatMessagePacket(UUID.randomUUID(), "Alice", SERVER_ID, "global", content);
    }

    private static final class Captured {
        private final String senderId;
        private final Packet packet;
        private final Set<UUID> excluded;

        private Captured(String senderId, Packet packet, Set<UUID> excluded) {
            this.senderId = senderId;
            this.packet = packet;
            this.excluded = excluded;
        }
    }
}
