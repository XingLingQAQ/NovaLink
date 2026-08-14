package com.nova.chat.sponge.chat;

import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.sponge.NovaChatSponge;
import com.nova.chat.sponge.config.NovaChatConfig;
import com.nova.chat.sponge.network.NetworkClient;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.scheduler.Scheduler;
import org.spongepowered.api.scheduler.TaskExecutorService;
import org.spongepowered.plugin.PluginContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Sponge {@link ChatListener}'s TitlePacket handler.
 *
 * <p>The handler is registered on the (mocked) {@link NetworkClient} in the
 * listener constructor; we capture it via ArgumentCaptor, drive it with a
 * {@link TitlePacket} inside a {@code mockStatic(Sponge.class)} scope, capture
 * the plugin-executor hop, and verify the Adventure {@code showTitle} rendering
 * (legacy-&amp; color deserialization plus tick-to-duration timing conversion).
 */
@DisplayName("Sponge ChatListener TitlePacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatListenerTitleTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatSponge plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChannelResponseTracker tracker;
    @Mock
    private PluginContainer container;
    @Mock
    private Server server;
    @Mock
    private Scheduler scheduler;
    @Mock
    private TaskExecutorService executor;
    @Mock
    private ServerPlayer player;

    private MockedStatic<Sponge> spongeStatic;
    private ChatListener listener;
    private Consumer<TitlePacket> titleHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(plugin.getContainer()).thenReturn(container);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(false);
        when(player.uniqueId()).thenReturn(PLAYER_ID);

        spongeStatic = Mockito.mockStatic(Sponge.class);
        spongeStatic.when(Sponge::server).thenReturn(server);
        when(server.scheduler()).thenReturn(scheduler);
        when(scheduler.executor(container)).thenReturn(executor);
        doReturn(List.of(player)).when(server).onlinePlayers();

        listener = new ChatListener(plugin);
        // Seed the player's state; the default active channel is "global".
        listener.getOrCreateState(player);

        ArgumentCaptor<Consumer<TitlePacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(TitlePacket.class), captor.capture());
        titleHandler = captor.getValue();
    }

    @AfterEach
    void tearDown() {
        spongeStatic.close();
    }

    @Test
    @DisplayName("matching channel: hops to the plugin executor and shows an Adventure title")
    void matchingChannelShowsTitleViaExecutorHop() {
        TitlePacket packet = new TitlePacket("global", "&6Hello", "&7World", PLAYER_ID, 5, 40, 10);

        titleHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        verify(player, never()).showTitle(any());

        task.getValue().run();

        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(shown.capture());

        Title title = shown.getValue();
        assertThat(title.title())
                .isEqualTo(LegacyComponentSerializer.legacyAmpersand().deserialize("&6Hello"));
        assertThat(title.subtitle())
                .isEqualTo(LegacyComponentSerializer.legacyAmpersand().deserialize("&7World"));
        // Tick timings convert at 50ms/tick.
        assertThat(title.times()).isNotNull();
        assertThat(title.times().fadeIn()).isEqualTo(Duration.ofMillis(250));
        assertThat(title.times().stay()).isEqualTo(Duration.ofMillis(2000));
        assertThat(title.times().fadeOut()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("non-matching channel: executor hop happens but no title is shown")
    void nonMatchingChannelSkipsPlayer() {
        TitlePacket packet = new TitlePacket("trade", "&6Hello", "&7World", PLAYER_ID);

        titleHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(player, never()).showTitle(any());
    }

    @Test
    @DisplayName("null title/subtitle render as empty components instead of throwing")
    void nullTitleAndSubtitleRenderEmpty() {
        TitlePacket packet = new TitlePacket();
        packet.setChannelId("global");

        titleHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(shown.capture());
        assertThat(shown.getValue().title())
                .isEqualTo(LegacyComponentSerializer.legacyAmpersand().deserialize(""));
    }
}
