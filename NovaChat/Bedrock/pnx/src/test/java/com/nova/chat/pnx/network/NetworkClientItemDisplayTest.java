package com.nova.chat.pnx.network;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.scheduler.ServerScheduler;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.chat.ChatInterceptor;
import com.nova.chat.pnx.chat.MessageFormatter;
import com.nova.chat.pnx.config.NovaChatConfig;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PNX {@link NetworkClient}'s ItemDisplayPacket handler.
 *
 * <p>The handler is registered on the shared {@link CoreNetworkClient} in the
 * facade constructor (a real {@link NetworkClient} is safe to build with
 * mocked platform deps — the core constructor only stores fields); we reach
 * the core via reflection and drive its public {@code handlePacket} dispatch,
 * so the test covers registration + dispatch + main-thread hop + rendering.
 * Bedrock clients have no hover component, so the line is plain colorized
 * text, mirroring the Title handler.
 */
@DisplayName("PNX NetworkClient ItemDisplayPacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NetworkClientItemDisplayTest {

    private static final UUID SENDER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID VIEWER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Mock
    private NovaChatPNX plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private Server server;
    @Mock
    private ServerScheduler scheduler;
    @Mock
    private ChatInterceptor chatInterceptor;
    @Mock
    private MessageFormatter formatter;
    @Mock
    private Player viewer;

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
        when(plugin.getMessageFormatter()).thenReturn(formatter);
        when(formatter.colorize(anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).replace('&', '\u00A7'));
        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        doReturn(Map.of(VIEWER_ID, viewer)).when(server).getOnlinePlayers();

        NetworkClient client = new NetworkClient(plugin, config);

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
        when(chatInterceptor.getState(VIEWER_ID)).thenReturn(state);
    }

    @Test
    @DisplayName("matching channel: hops to the main thread and sends a colorized item line")
    void matchingChannelSendsLineViaSchedulerHop() {
        seedViewerState("global");
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:netherite_sword\",\"count\":2}", System.currentTimeMillis());

        core.handlePacket(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleTask(eq(plugin), task.capture());
        verify(viewer, never()).sendMessage(anyString());

        task.getValue().run();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(viewer).sendMessage(sent.capture());
        // & codes are colorized to § by the (stubbed) formatter.
        assertThat(sent.getValue())
                .contains("Alex")
                .contains("Netherite Sword")
                .contains("x2")
                .contains("\u00A7");
    }

    @Test
    @DisplayName("empty hand payload renders the localized empty placeholder")
    void emptyHandRendersPlaceholder() {
        seedViewerState("global");
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:air\",\"count\":0}", System.currentTimeMillis());

        core.handlePacket(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleTask(eq(plugin), task.capture());
        task.getValue().run();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(viewer).sendMessage(sent.capture());
        assertThat(sent.getValue()).contains("\u7A7A\u624B"); // 空手
    }

    @Test
    @DisplayName("non-matching channel: scheduler hop happens but nothing is rendered")
    void nonMatchingChannelSkipsPlayer() {
        seedViewerState("global");
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "trade",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis());

        core.handlePacket(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleTask(eq(plugin), task.capture());
        task.getValue().run();

        verify(viewer, never()).sendMessage(anyString());
    }
}
