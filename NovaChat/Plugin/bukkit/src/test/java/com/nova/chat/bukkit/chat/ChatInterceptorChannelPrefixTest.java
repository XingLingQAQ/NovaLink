package com.nova.chat.bukkit.chat;

import com.nova.chat.bukkit.NovaChatBukkit;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Bukkit {@link ChatInterceptor}'s outbound channel-prefix
 * routing (REPLACE mode): a configured prefix redirects the message to the
 * mapped channel, escapes and unknown channels fall back to the active channel.
 */
@DisplayName("Bukkit ChatInterceptor channel-prefix routing")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorChannelPrefixTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private Server server;
    @Mock
    private Player player;
    @Mock
    private World world;
    @Mock
    private AsyncPlayerChatEvent event;

    private KnownChannelRegistry registry;
    private ChatInterceptor interceptor;

    @BeforeEach
    void setUp() {
        registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of("global", "local"));

        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getKnownChannelRegistry()).thenReturn(registry);
        when(config.isReplaceVanilla()).thenReturn(true); // REPLACE mode
        when(config.getDefaultChannel()).thenReturn("local");
        when(config.getUsername()).thenReturn("bukkit-1");
        when(config.getChannelPrefixes()).thenReturn(Map.of("!", "global"));
        when(networkClient.isAuthenticated()).thenReturn(true);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Steve");
        when(player.getDisplayName()).thenReturn("Steve");
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");

        when(event.getPlayer()).thenReturn(player);

        interceptor = new ChatInterceptor(plugin);
    }

    private ChatMessagePacket sentChatPacket() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues().stream()
                .filter(ChatMessagePacket.class::isInstance)
                .map(ChatMessagePacket.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("prefixed message is redirected to the mapped channel with the prefix stripped")
    void prefixRedirectsToMappedChannel() {
        when(event.getMessage()).thenReturn("!hello everyone");

        interceptor.onPlayerChat(event);

        verify(event).setCancelled(true);
        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getContent()).isEqualTo("hello everyone");
    }

    @Test
    @DisplayName("escaped prefix sends the literal message to the active channel")
    void escapedPrefixStaysInActiveChannel() {
        when(event.getMessage()).thenReturn("\\!hello");

        interceptor.onPlayerChat(event);

        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("local");
        assertThat(packet.getContent()).isEqualTo("!hello");
    }

    @Test
    @DisplayName("prefix mapped to an unknown channel falls back to the active channel")
    void unknownChannelFallsBack() {
        registry.replaceAll(Set.of("local")); // "global" no longer advertised
        when(event.getMessage()).thenReturn("!hello");

        interceptor.onPlayerChat(event);

        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("local");
        assertThat(packet.getContent()).isEqualTo("!hello");
    }

    @Test
    @DisplayName("message without a prefix goes to the active channel unchanged")
    void plainMessageUnchanged() {
        when(event.getMessage()).thenReturn("hello");

        interceptor.onPlayerChat(event);

        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("local");
        assertThat(packet.getContent()).isEqualTo("hello");
    }
}
