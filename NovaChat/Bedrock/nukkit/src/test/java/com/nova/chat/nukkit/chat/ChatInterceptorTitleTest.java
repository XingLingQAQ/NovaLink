package com.nova.chat.nukkit.chat;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.scheduler.ServerScheduler;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.config.NovaChatConfig;
import com.nova.chat.nukkit.network.NetworkClient;
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
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Nukkit {@link ChatInterceptor}'s TitlePacket handler.
 *
 * <p>The handler is registered on the (mocked) {@link NetworkClient} in the
 * interceptor constructor; we capture it via ArgumentCaptor, drive it with a
 * {@link TitlePacket}, capture the Nukkit main-thread scheduler hop, and verify
 * the color-translated {@code Player#sendTitle} rendering with the packet's
 * fade timings.
 */
@DisplayName("Nukkit ChatInterceptor TitlePacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorTitleTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatNukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChannelResponseTracker tracker;
    @Mock
    private Server server;
    @Mock
    private ServerScheduler scheduler;
    @Mock
    private Player player;

    private ChatInterceptor interceptor;
    private Consumer<TitlePacket> titleHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(false);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        doReturn(Map.of(PLAYER_ID, player)).when(server).getOnlinePlayers();

        interceptor = new ChatInterceptor(plugin);
        // Seed the player's state; the default active channel is "global".
        interceptor.getOrCreateState(player);

        ArgumentCaptor<Consumer<TitlePacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(TitlePacket.class), captor.capture());
        titleHandler = captor.getValue();
    }

    @Test
    @DisplayName("matching channel: hops to the main thread and sends a color-translated title")
    void matchingChannelSendsTitleViaSchedulerHop() {
        TitlePacket packet = new TitlePacket("global", "&6Hello", "&7World", PLAYER_ID, 5, 40, 10);

        titleHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleTask(eq(plugin), task.capture());
        verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());

        task.getValue().run();

        // & codes are translated to § by the real MessageFormatter; timings come
        // straight from the packet.
        verify(player).sendTitle(eq("\u00A76Hello"), eq("\u00A77World"), eq(5), eq(40), eq(10));
    }

    @Test
    @DisplayName("non-matching channel: scheduler hop happens but nothing is rendered")
    void nonMatchingChannelSkipsPlayer() {
        TitlePacket packet = new TitlePacket("trade", "&6Hello", "&7World", PLAYER_ID);

        titleHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleTask(eq(plugin), task.capture());
        task.getValue().run();

        verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("null title/subtitle render as empty strings with default timings")
    void nullTitleAndSubtitleRenderEmpty() {
        TitlePacket packet = new TitlePacket();
        packet.setChannelId("global");

        titleHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleTask(eq(plugin), task.capture());
        task.getValue().run();

        verify(player).sendTitle(eq(""), eq(""), eq(10), eq(70), eq(20));
    }
}
