package com.nova.chat.velocity.chat;

import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.nova.chat.velocity.network.NetworkClient;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
 * Unit tests for the Velocity {@link ChatListener}'s TitlePacket handler.
 *
 * <p>The handler is registered on the (mocked) {@link NetworkClient} in the
 * listener constructor; we capture it via ArgumentCaptor, drive it with a
 * {@link TitlePacket}, and verify the Adventure {@code showTitle} rendering
 * (legacy-&amp; color parsing plus tick-to-duration timing conversion).
 */
@DisplayName("Velocity ChatListener TitlePacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatListenerTitleTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatVelocity plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChannelResponseTracker tracker;
    @Mock
    private ProxyServer proxyServer;
    @Mock
    private Player player;

    private ChatListener listener;
    private Consumer<TitlePacket> titleHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(plugin.getServer()).thenReturn(proxyServer);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(false);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        doReturn(List.of(player)).when(proxyServer).getAllPlayers();

        listener = new ChatListener(plugin);
        // Seed the player's state; the default active channel is "global".
        listener.getOrCreateState(player);

        ArgumentCaptor<Consumer<TitlePacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(TitlePacket.class), captor.capture());
        titleHandler = captor.getValue();
    }

    @Test
    @DisplayName("matching channel: shows an Adventure title with parsed colors and packet timings")
    void matchingChannelShowsTitle() {
        TitlePacket packet = new TitlePacket("global", "&6Hello", "&7World", PLAYER_ID, 5, 40, 10);

        titleHandler.accept(packet);

        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(shown.capture());

        Title title = shown.getValue();
        // Components must match the listener's own legacy-& parser output.
        assertThat(title.title())
                .isEqualTo(listener.getMessageFormatter().parseColors("&6Hello"));
        assertThat(title.subtitle())
                .isEqualTo(listener.getMessageFormatter().parseColors("&7World"));
        // Tick timings convert at 50ms/tick.
        assertThat(title.times()).isNotNull();
        assertThat(title.times().fadeIn()).isEqualTo(Duration.ofMillis(250));
        assertThat(title.times().stay()).isEqualTo(Duration.ofMillis(2000));
        assertThat(title.times().fadeOut()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("non-matching channel: no title shown")
    void nonMatchingChannelSkipsPlayer() {
        TitlePacket packet = new TitlePacket("trade", "&6Hello", "&7World", PLAYER_ID);

        titleHandler.accept(packet);

        verify(player, never()).showTitle(any());
    }

    @Test
    @DisplayName("null title/subtitle render as empty components instead of throwing")
    void nullTitleAndSubtitleRenderEmpty() {
        TitlePacket packet = new TitlePacket();
        packet.setChannelId("global");

        titleHandler.accept(packet);

        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(shown.capture());
        assertThat(shown.getValue().title())
                .isEqualTo(listener.getMessageFormatter().parseColors(""));
    }
}
