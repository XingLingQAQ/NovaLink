package com.nova.chat.folia.chat;

import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.network.AsyncNetworkClient;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Folia {@link AsyncChatInterceptor}'s TitlePacket handler.
 *
 * <p>The handler is registered on the (mocked) {@link AsyncNetworkClient} in the
 * interceptor constructor; we capture it via ArgumentCaptor, drive it with a
 * {@link TitlePacket}, and verify the region-thread hop
 * ({@link FoliaSchedulerAdapter#runForPlayer}) plus the color-translated
 * {@code Player#sendTitle} rendering with the packet's fade timings.
 */
@DisplayName("Folia AsyncChatInterceptor TitlePacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncChatInterceptorTitleTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private FoliaSchedulerAdapter scheduler;
    @Mock
    private AsyncNetworkClient networkClient;
    @Mock
    private ChannelResponseTracker tracker;
    @Mock
    private Server server;
    @Mock
    private PluginManager pluginManager;
    @Mock
    private Player player;

    private AsyncChatInterceptor interceptor;
    private Consumer<TitlePacket> titleHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(false);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);
        doReturn(List.of(player)).when(server).getOnlinePlayers();

        interceptor = new AsyncChatInterceptor(plugin);
        // Seed the player's state; the default active channel is "global".
        interceptor.getOrCreateState(player);

        ArgumentCaptor<Consumer<TitlePacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(TitlePacket.class), captor.capture());
        titleHandler = captor.getValue();
    }

    @Test
    @DisplayName("matching channel: hops to the player's region thread and sends a color-translated title")
    void matchingChannelSendsTitleOnRegionThread() {
        TitlePacket packet = new TitlePacket("global", "&6Hello", "&7World", PLAYER_ID, 5, 40, 10);

        titleHandler.accept(packet);

        // The player API call must be dispatched via runForPlayer (region thread).
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayer(eq(player), task.capture());
        verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());

        task.getValue().run();

        // & codes are translated to § by the AsyncMessageFormatter; timings come
        // straight from the packet.
        verify(player).sendTitle(eq("\u00A76Hello"), eq("\u00A77World"), eq(5), eq(40), eq(10));
    }

    @Test
    @DisplayName("non-matching channel: no region hop, no title")
    void nonMatchingChannelSkipsPlayer() {
        TitlePacket packet = new TitlePacket("trade", "&6Hello", "&7World", PLAYER_ID);

        titleHandler.accept(packet);

        verify(scheduler, never()).runForPlayer(any(Player.class), any(Runnable.class));
        verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("null title/subtitle render as empty strings instead of throwing")
    void nullTitleAndSubtitleRenderEmpty() {
        TitlePacket packet = new TitlePacket();
        packet.setChannelId("global");

        titleHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayer(eq(player), task.capture());
        task.getValue().run();

        verify(player).sendTitle(eq(""), eq(""), eq(10), eq(70), eq(20));
    }
}
