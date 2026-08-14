package com.nova.chat.velocity.chat;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.nova.chat.velocity.network.NetworkClient;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Locale;
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
 * Unit tests for the Velocity {@link ChatListener}'s ItemDisplayPacket handler.
 *
 * <p>Mirrors {@link ChatListenerTitleTest}'s infrastructure: the handler is
 * captured from the mocked {@link NetworkClient} registration and driven with
 * an {@link ItemDisplayPacket}; the Adventure component send (line + hover)
 * is verified directly since no scheduler hop is needed on the proxy.
 *
 * <p>The proxy has no send side: Velocity cannot read the player's held item,
 * so only the receive side exists here.
 */
@DisplayName("Velocity ChatListener ItemDisplayPacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatListenerItemDisplayTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SENDER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

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
    private Consumer<ItemDisplayPacket> itemDisplayHandler;
    private Locale previousDefaultLocale;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

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

        ArgumentCaptor<Consumer<ItemDisplayPacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ItemDisplayPacket.class), captor.capture());
        itemDisplayHandler = captor.getValue();
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    @Test
    @DisplayName("matching channel: sends a hoverable Adventure line with the item name and count")
    void matchingChannelSendsHoverableLine() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:netherite_sword\",\"count\":2}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());

        String plain = LegacyComponentSerializer.legacySection().serialize(sent.getValue());
        assertThat(plain).contains("Alex").contains("Netherite Sword").contains("x2");

        HoverEvent<?> hover = sent.getValue().hoverEvent();
        assertThat(hover).isNotNull();
        assertThat(hover.action()).isEqualTo(HoverEvent.Action.SHOW_TEXT);
        String hoverPlain = LegacyComponentSerializer.legacySection()
                .serialize((Component) hover.value());
        assertThat(hoverPlain).contains("minecraft:netherite_sword");
    }

    @Test
    @DisplayName("empty hand payload renders the localized empty placeholder")
    void emptyHandRendersPlaceholder() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:air\",\"count\":0}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());
        assertThat(LegacyComponentSerializer.legacySection().serialize(sent.getValue()))
                .contains("\u7A7A\u624B"); // 空手
    }

    @Test
    @DisplayName("non-matching channel: nothing is sent")
    void nonMatchingChannelSkipsPlayer() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "trade",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        verify(player, never()).sendMessage(any(Component.class));
    }
}
