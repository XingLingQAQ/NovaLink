package com.nova.chat.sponge.chat;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.sponge.NovaChatSponge;
import com.nova.chat.sponge.config.NovaChatConfig;
import com.nova.chat.sponge.network.NetworkClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
 * Unit tests for the Sponge {@link ChatListener}'s ItemDisplayPacket handler.
 *
 * <p>Mirrors {@link ChatListenerTitleTest}'s infrastructure: the handler is
 * captured from the mocked {@link NetworkClient} registration, driven inside a
 * {@code mockStatic(Sponge.class)} scope, and the plugin-executor hop is
 * asserted before the Adventure component send (line + hover) is verified.
 *
 * <p>Send side is intentionally out of scope for Sponge in this pass; only
 * the receive side exists here.
 */
@DisplayName("Sponge ChatListener ItemDisplayPacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatListenerItemDisplayTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SENDER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

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
    private Consumer<ItemDisplayPacket> itemDisplayHandler;
    private Locale previousDefaultLocale;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

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

        ArgumentCaptor<Consumer<ItemDisplayPacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ItemDisplayPacket.class), captor.capture());
        itemDisplayHandler = captor.getValue();
    }

    @AfterEach
    void tearDown() {
        spongeStatic.close();
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    @Test
    @DisplayName("matching channel: hops to the plugin executor and sends a hoverable item line")
    void matchingChannelSendsHoverableLineViaExecutorHop() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:netherite_sword\",\"count\":2}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        verify(player, never()).sendMessage(any(Component.class));

        task.getValue().run();

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());

        String plain = PlainTextComponentSerializer.plainText().serialize(sent.getValue());
        assertThat(plain).contains("Alex").contains("Netherite Sword").contains("x2");

        HoverEvent<?> hover = sent.getValue().hoverEvent();
        assertThat(hover).isNotNull();
        assertThat(hover.action()).isEqualTo(HoverEvent.Action.SHOW_TEXT);
        String hoverPlain = PlainTextComponentSerializer.plainText()
                .serialize((Component) hover.value());
        assertThat(hoverPlain).contains("minecraft:netherite_sword");
    }

    @Test
    @DisplayName("empty hand payload renders the localized empty placeholder")
    void emptyHandRendersPlaceholder() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:air\",\"count\":0}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());
        assertThat(PlainTextComponentSerializer.plainText().serialize(sent.getValue()))
                .contains("\u7A7A\u624B"); // 空手
    }

    @Test
    @DisplayName("non-matching channel: executor hop happens but nothing is sent")
    void nonMatchingChannelSkipsPlayer() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "trade",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(player, never()).sendMessage(any(Component.class));
    }
}
