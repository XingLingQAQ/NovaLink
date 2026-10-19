package com.nova.chat.bukkit.chat;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.channel.ProxyChannelClient;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.bukkit.network.NetworkClient;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transport-selection rule introduced with the plugin-messaging channel:
 * the backend link stays the preferred path (no duplicate delivery), and the
 * proxy channel is used exactly when the backend is unavailable — the lite-mode
 * case that used to cancel the player's line and answer NC-503.
 */
@DisplayName("Bukkit ChatInterceptor proxy-channel fallback")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ChatInterceptorProxyChannelTest {

    private static final UUID PLAYER_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ProxyChannelClient channelClient;
    @Mock
    private Server server;
    @Mock
    private Player player;
    @Mock
    private World world;
    @Mock
    private AsyncPlayerChatEvent event;

    private ChatInterceptor interceptor;

    @BeforeEach
    void setUp() {
        KnownChannelRegistry registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of("global", "local"));

        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(plugin.getProxyChannelClient()).thenReturn(channelClient);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getKnownChannelRegistry()).thenReturn(registry);
        when(config.isReplaceVanilla()).thenReturn(true);
        when(config.getDefaultChannel()).thenReturn("local");
        when(config.getUsername()).thenReturn("bukkit-1");
        when(config.getPrefix()).thenReturn("");
        when(config.getErrorFormat()).thenReturn("{message}");
        when(config.getChannelPrefixes()).thenReturn(Map.of());
        when(channelClient.isEnabled()).thenReturn(true);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Steve");
        when(player.getDisplayName()).thenReturn("Steve");
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn("hello proxy");

        interceptor = new ChatInterceptor(plugin);
    }

    @Test
    @DisplayName("backend unavailable: the cancelled line is forwarded over novachat:main")
    void backendDownFallsBackToProxyChannel() {
        when(networkClient.isAuthenticated()).thenReturn(false);
        when(channelClient.send(any(Packet.class))).thenReturn(true);

        interceptor.onPlayerChat(event);

        verify(event).setCancelled(true);
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(channelClient).send(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ChatMessagePacket.class);
        ChatMessagePacket packet = (ChatMessagePacket) captor.getValue();
        assertThat(packet.getContent()).isEqualTo("hello proxy");
        assertThat(packet.getChannelId()).isEqualTo("local");
        assertThat(packet.getSenderId()).isEqualTo(PLAYER_ID);
        assertThat(packet.getPlaceholders()).containsEntry("world", "world");
        verify(networkClient, never()).sendPacket(any());
        verify(player, never()).sendMessage(any(String.class));
    }

    @Test
    @DisplayName("backend available: the backend path is used and the channel is not")
    void backendUpKeepsBackendPath() {
        when(networkClient.isAuthenticated()).thenReturn(true);

        interceptor.onPlayerChat(event);

        verify(networkClient).sendPacket(any(ChatMessagePacket.class));
        verify(channelClient, never()).send(any());
    }

    @Test
    @DisplayName("backend unavailable and no players online: the player is told (NC-503 path)")
    void noTransportRepliesWithError() {
        when(networkClient.isAuthenticated()).thenReturn(false);
        // ProxyChannelClient reports the drop when nobody is online to carry it.
        when(channelClient.send(any(Packet.class))).thenReturn(false);

        interceptor.onPlayerChat(event);

        verify(channelClient).send(any(Packet.class));
        verify(player).sendMessage(any(String.class));
        verify(networkClient, never()).sendPacket(any());
    }

    @Test
    @DisplayName("a disabled channel does not intercept the backend-unavailable path")
    void disabledChannelIsNotUsed() {
        when(networkClient.isAuthenticated()).thenReturn(false);
        when(channelClient.isEnabled()).thenReturn(false);

        interceptor.onPlayerChat(event);

        verify(channelClient, never()).send(any());
        verify(player).sendMessage(any(String.class));
    }
}
