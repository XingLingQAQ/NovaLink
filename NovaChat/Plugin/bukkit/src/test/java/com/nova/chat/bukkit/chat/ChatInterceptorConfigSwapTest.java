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
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for CONFIG-FINAL: the interceptor previously cached the initial
 * {@link NovaChatConfig} instance, so a {@code /nc reload} silently kept the
 * old channel prefixes / default channel / format.* values on the hot path.
 * These tests pin the swap-in of a fresh config snapshot via
 * {@link ChatInterceptor#setConfig(NovaChatConfig)} and verify the very next
 * chat event resolves against the new values.
 */
@DisplayName("Bukkit ChatInterceptor config-swap on reload")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorConfigSwapTest {

    private static final UUID PLAYER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private NovaChatConfig initialConfig;
    @Mock
    private NovaChatConfig reloadedConfig;
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
        registry.replaceAll(Set.of("global", "local", "help"));

        // Initial config: REPLACE mode, default "local", "!" -> "global"
        when(initialConfig.isReplaceVanilla()).thenReturn(true);
        when(initialConfig.getDefaultChannel()).thenReturn("local");
        when(initialConfig.getUsername()).thenReturn("bukkit-1");
        when(initialConfig.getChannelPrefixes()).thenReturn(Map.of("!", "global"));

        // Reloaded config: REPLACE mode, default "local", "!" -> "help"
        when(reloadedConfig.isReplaceVanilla()).thenReturn(true);
        when(reloadedConfig.getDefaultChannel()).thenReturn("local");
        when(reloadedConfig.getUsername()).thenReturn("bukkit-1");
        when(reloadedConfig.getChannelPrefixes()).thenReturn(Map.of("!", "help"));

        when(plugin.getNovaChatConfig()).thenReturn(initialConfig);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getKnownChannelRegistry()).thenReturn(registry);
        when(networkClient.isAuthenticated()).thenReturn(true);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Steve");
        when(player.getDisplayName()).thenReturn("Steve");
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");

        when(event.getPlayer()).thenReturn(player);

        interceptor = new ChatInterceptor(plugin);
    }

    @Test
    @DisplayName("setConfig swaps the config snapshot so the next chat routes via the NEW prefixes")
    void setConfigSwapsSnapshot() {
        // Pre-reload: ! routes to "global"
        when(event.getMessage()).thenReturn("!first");
        interceptor.onPlayerChat(event);
        ChatMessagePacket pre = lastPacket();
        assertThat(pre.getChannelId()).isEqualTo("global");

        // Simulate /nc reload: the plugin main class calls setConfig with the
        // freshly built NovaChatConfig instance after loadConfiguration(). In
        // the old code the interceptor held a final reference to initialConfig
        // and kept routing to "global"; the fix makes it pick up "help".
        when(plugin.getNovaChatConfig()).thenReturn(reloadedConfig);
        interceptor.setConfig(reloadedConfig);

        when(event.getMessage()).thenReturn("!second");
        interceptor.onPlayerChat(event);
        ChatMessagePacket post = lastPacket();
        assertThat(post.getChannelId()).isEqualTo("help");
    }

    @Test
    @DisplayName("reload() convenience wraps setConfig(plugin.getNovaChatConfig())")
    void reloadPullsFreshSnapshotFromPlugin() {
        // The plugin now returns the new snapshot
        when(plugin.getNovaChatConfig()).thenReturn(reloadedConfig);

        interceptor.reload();

        when(event.getMessage()).thenReturn("!after-reload");
        interceptor.onPlayerChat(event);
        ChatMessagePacket post = lastPacket();
        assertThat(post.getChannelId()).isEqualTo("help");
    }

    @Test
    @DisplayName("setConfig(null) is a no-op and does not clear the current snapshot")
    void setConfigNullIsSafe() {
        interceptor.setConfig(null);

        when(event.getMessage()).thenReturn("!still-valid");
        interceptor.onPlayerChat(event);
        ChatMessagePacket post = lastPacket();
        // Initial config still in effect
        assertThat(post.getChannelId()).isEqualTo("global");
    }

    private ChatMessagePacket lastPacket() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeast(1)).sendPacket(captor.capture());
        return captor.getAllValues().stream()
                .filter(ChatMessagePacket.class::isInstance)
                .map(ChatMessagePacket.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();
    }
}
