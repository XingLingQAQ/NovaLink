package com.nova.chat.bungee.chat;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.bungee.network.NetworkClient;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.packets.TitlePacket;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Bungee {@link ChatListener}'s TitlePacket handler.
 *
 * <p>The handler is registered on the (mocked) {@link NetworkClient} in the
 * listener constructor; we capture it via ArgumentCaptor, drive it with a
 * {@link TitlePacket}, and verify the proxy-native title path
 * ({@code ProxyServer#createTitle()} builder + {@code Title#send}).
 */
@DisplayName("Bungee ChatListener TitlePacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatListenerTitleTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatBungee plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChannelResponseTracker tracker;
    @Mock
    private ProxyServer proxy;
    @Mock
    private Title title;
    @Mock
    private ProxiedPlayer player;

    private ChatListener listener;
    private Consumer<TitlePacket> titleHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(plugin.getPluginConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(plugin.getProxy()).thenReturn(proxy);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(false);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        doReturn(List.of(player)).when(proxy).getPlayers();

        // Fluent builder: every setter returns the same Title mock.
        when(proxy.createTitle()).thenReturn(title);
        when(title.title(any(BaseComponent[].class))).thenReturn(title);
        when(title.subTitle(any(BaseComponent[].class))).thenReturn(title);
        when(title.fadeIn(any(int.class))).thenReturn(title);
        when(title.stay(any(int.class))).thenReturn(title);
        when(title.fadeOut(any(int.class))).thenReturn(title);

        listener = new ChatListener(plugin);
        // Seed the player's state; the default active channel is "global".
        listener.getOrCreateState(player);

        ArgumentCaptor<Consumer<TitlePacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(TitlePacket.class), captor.capture());
        titleHandler = captor.getValue();
    }

    @Test
    @DisplayName("matching channel: builds a proxy title with parsed colors and packet timings, then sends it")
    void matchingChannelSendsProxyTitle() {
        TitlePacket packet = new TitlePacket("global", "&6Hello", "&7World", PLAYER_ID, 5, 40, 10);

        titleHandler.accept(packet);

        ArgumentCaptor<BaseComponent[]> titleText = ArgumentCaptor.forClass(BaseComponent[].class);
        ArgumentCaptor<BaseComponent[]> subtitleText = ArgumentCaptor.forClass(BaseComponent[].class);
        verify(title).title(titleText.capture());
        verify(title).subTitle(subtitleText.capture());
        verify(title).fadeIn(5);
        verify(title).stay(40);
        verify(title).fadeOut(10);
        verify(title).send(player);

        // & codes are parsed by the real MessageFormatter into colored components.
        assertThat(TextComponent.toLegacyText(titleText.getValue())).contains("Hello");
        assertThat(TextComponent.toLegacyText(subtitleText.getValue())).contains("World");
        assertThat(TextComponent.toPlainText(titleText.getValue())).isEqualTo("Hello");
    }

    @Test
    @DisplayName("non-matching channel: no title sent to the player")
    void nonMatchingChannelSkipsPlayer() {
        TitlePacket packet = new TitlePacket("trade", "&6Hello", "&7World", PLAYER_ID);

        titleHandler.accept(packet);

        verify(title, never()).send(any());
    }

    @Test
    @DisplayName("null title/subtitle render as empty components with default timings")
    void nullTitleAndSubtitleRenderEmpty() {
        TitlePacket packet = new TitlePacket();
        packet.setChannelId("global");

        titleHandler.accept(packet);

        verify(title).fadeIn(10);
        verify(title).stay(70);
        verify(title).fadeOut(20);
        verify(title).send(player);
    }
}
