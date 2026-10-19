package com.nova.chat.bukkit.channel;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Carrier selection for the sub-server half of {@code novachat:main}.
 *
 * <p>Plugin messaging has no server-addressed write: the payload rides one
 * player's connection. A player connected straight to this server takes the
 * payload into their own client and the proxy never sees it — the silent loss
 * this selection guards against. Bukkit exposes no "is proxied" flag, so the
 * order under test is: a player already proven to carry downstream payloads,
 * then an address match against the configured proxy address, then the first
 * online player with a one-shot warning.
 */
@DisplayName("Bukkit ProxyChannelClient carrier selection")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProxyChannelClientCarrierTest {

    private static final String SERVER_ID = "survival-01";

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private Server server;
    @Mock
    private Messenger messenger;
    @Mock
    private Player directPlayer;
    @Mock
    private Player proxiedPlayer;

    private ProxyChannelClient client;

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(server.getMessenger()).thenReturn(messenger);
        when(config.isPluginMessagingEnabled()).thenReturn(true);
        when(config.getPluginMessagingServerId()).thenReturn(SERVER_ID);
        when(config.getPluginMessagingProxyAddresses()).thenReturn(List.of());

        when(directPlayer.getName()).thenReturn("Direct");
        when(directPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(proxiedPlayer.getName()).thenReturn("Proxied");
        when(proxiedPlayer.getUniqueId()).thenReturn(UUID.randomUUID());

        client = new ProxyChannelClient(plugin, (senderId, packet, excluded) -> { });
        client.register();
    }

    @Test
    @DisplayName("a player that carried a downstream payload wins over the first online player")
    void verifiedCarrierWins() throws Exception {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(directPlayer, proxiedPlayer));

        // A downstream payload can only arrive over a proxy connection, so this
        // is proof, not a guess.
        client.onPluginMessageReceived(ProxyChannelProtocol.CHANNEL_ID, proxiedPlayer,
                ProxyChannelProtocol.encode("proxy-1", chat("from proxy")));

        assertThat(client.send(chat("hello"))).isTrue();
        verify(proxiedPlayer).sendPluginMessage(eq(plugin),
                eq(ProxyChannelProtocol.CHANNEL_ID), any(byte[].class));
        verify(directPlayer, never()).sendPluginMessage(any(), any(), any());
    }

    @Test
    @DisplayName("a loopback connection is treated as the proxy when no address is configured")
    void loopbackIsTreatedAsProxy() throws Exception {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(directPlayer, proxiedPlayer));
        stubAddress(directPlayer, "203.0.113.9");
        stubAddress(proxiedPlayer, "127.0.0.1");

        assertThat(client.send(chat("hello"))).isTrue();
        verify(proxiedPlayer).sendPluginMessage(eq(plugin),
                eq(ProxyChannelProtocol.CHANNEL_ID), any(byte[].class));
        verify(directPlayer, never()).sendPluginMessage(any(), any(), any());
    }

    @Test
    @DisplayName("a configured proxy address is matched instead of the loopback default")
    void configuredProxyAddressIsMatched() throws Exception {
        when(config.getPluginMessagingProxyAddresses()).thenReturn(List.of("10.0.0.5"));
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(directPlayer, proxiedPlayer));
        stubAddress(directPlayer, "127.0.0.1"); // co-located but not the proxy
        stubAddress(proxiedPlayer, "10.0.0.5");

        assertThat(client.send(chat("hello"))).isTrue();
        verify(proxiedPlayer).sendPluginMessage(eq(plugin),
                eq(ProxyChannelProtocol.CHANNEL_ID), any(byte[].class));
        verify(directPlayer, never()).sendPluginMessage(any(), any(), any());
    }

    @Test
    @DisplayName("with no evidence at all the first player is used, so the bridge still starts")
    void unverifiedFallbackStillSends() throws Exception {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(directPlayer));
        stubAddress(directPlayer, "203.0.113.9");

        assertThat(client.send(chat("hello"))).isTrue();
        verify(directPlayer).sendPluginMessage(eq(plugin),
                eq(ProxyChannelProtocol.CHANNEL_ID), any(byte[].class));
    }

    @Test
    @DisplayName("a failed carrier write is reported, not swallowed")
    void carrierWriteFailureReturnsFalse() throws Exception {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(directPlayer));
        stubAddress(directPlayer, "203.0.113.9");
        doThrow(new IllegalStateException("channel not registered"))
                .when(directPlayer).sendPluginMessage(any(), any(), any());

        assertThat(client.send(chat("hello"))).isFalse();
    }

    @Test
    @DisplayName("selectCarrier never returns null while a player is online")
    void selectCarrierAlwaysPicksSomeone() throws Exception {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(directPlayer));
        stubAddress(directPlayer, "203.0.113.9");

        assertThat(client.selectCarrier(List.of(directPlayer))).isEqualTo(directPlayer);
    }

    private static void stubAddress(Player player, String address) throws Exception {
        when(player.getAddress()).thenReturn(
                new InetSocketAddress(InetAddress.getByName(address), 25565));
    }

    private static ChatMessagePacket chat(String content) {
        return new ChatMessagePacket(UUID.randomUUID(), "Alice", SERVER_ID, "global", content);
    }
}
