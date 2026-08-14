package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.chat.ChatInterceptor;
import com.nova.chat.bukkit.chat.MessageFormatter;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Bukkit {@link NetworkClient}'s ItemDisplayPacket handler.
 *
 * <p>The handler is registered on the shared {@link CoreNetworkClient} in the
 * facade constructor; we reach the core via reflection (it is intentionally
 * private) and drive its public {@code handlePacket} dispatch, so the test
 * covers registration + dispatch + main-thread hop + hoverable rendering.
 */
@DisplayName("Bukkit NetworkClient ItemDisplayPacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NetworkClientItemDisplayTest {

    private static final UUID SENDER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID VIEWER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private Server server;
    @Mock
    private BukkitScheduler scheduler;
    @Mock
    private ChatInterceptor chatInterceptor;
    @Mock
    private MessageFormatter formatter;
    @Mock
    private Player viewer;
    @Mock
    private Player.Spigot spigot;

    private CoreNetworkClient core;
    private Locale previousDefaultLocale;

    @BeforeEach
    void setUp() throws Exception {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getServer()).thenReturn(server);
        when(server.getVersion()).thenReturn("test-server");
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.toClientConnectionConfig())
                .thenReturn(ClientConnectionConfig.builder().build());
        when(plugin.getChatInterceptor()).thenReturn(chatInterceptor);
        when(chatInterceptor.getMessageFormatter()).thenReturn(formatter);
        when(formatter.translateColorCodes(anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).replace('&', '\u00A7'));
        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        when(viewer.spigot()).thenReturn(spigot);
        doReturn(List.of(viewer)).when(server).getOnlinePlayers();

        NetworkClient client = new NetworkClient(plugin, config, new KnownChannelRegistry());

        Field coreField = AbstractPlatformNetworkClient.class.getDeclaredField("core");
        coreField.setAccessible(true);
        core = (CoreNetworkClient) coreField.get(client);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    private void seedViewerState(String activeChannel) {
        PlayerChannelState state = new PlayerChannelState(VIEWER_ID, activeChannel, ChatMode.HYBRID);
        when(chatInterceptor.getOrCreateState(viewer)).thenReturn(state);
    }

    @Test
    @DisplayName("matching channel: hops to the main thread and sends a hoverable item line")
    void matchingChannelRendersHoverableLine() {
        seedViewerState("global");
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Steve", "global",
                "{\"id\":\"minecraft:netherite_sword\",\"count\":2}", System.currentTimeMillis());

        core.handlePacket(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTask(eq(plugin), task.capture());
        verify(spigot, never()).sendMessage(any(BaseComponent.class));

        task.getValue().run();

        ArgumentCaptor<BaseComponent> component = ArgumentCaptor.forClass(BaseComponent.class);
        verify(spigot).sendMessage(component.capture());

        String plain = component.getValue().toPlainText();
        assertThat(plain).contains("Steve").contains("Netherite Sword").contains("x2");
        assertThat(component.getValue().getHoverEvent()).isNotNull();
        assertThat(component.getValue().getHoverEvent().getAction())
                .isEqualTo(HoverEvent.Action.SHOW_TEXT);
    }

    @Test
    @DisplayName("empty hand payload renders the localized empty placeholder")
    void emptyHandRendersPlaceholder() {
        seedViewerState("global");
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Steve", "global",
                "{\"id\":\"minecraft:air\",\"count\":0}", System.currentTimeMillis());

        core.handlePacket(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTask(eq(plugin), task.capture());
        task.getValue().run();

        ArgumentCaptor<BaseComponent> component = ArgumentCaptor.forClass(BaseComponent.class);
        verify(spigot).sendMessage(component.capture());
        assertThat(component.getValue().toPlainText()).contains("\u7A7A\u624B"); // 空手
    }

    @Test
    @DisplayName("non-matching channel: scheduler hop happens but nothing is rendered")
    void nonMatchingChannelSkipsPlayer() {
        seedViewerState("global");
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Steve", "trade",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis());

        core.handlePacket(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTask(eq(plugin), task.capture());
        task.getValue().run();

        verify(spigot, never()).sendMessage(any(BaseComponent.class));
    }
}
