package com.nova.chat.pnx.network;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.scheduler.ServerScheduler;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PNX {@link NetworkClient}'s ignore filtering on the
 * inbound {@link ItemDisplayPacket} and {@link MentionPacket} paths
 * ({@code /nc ignore}). Infrastructure mirrors
 * {@link NetworkClientItemDisplayTest}: a real {@link NetworkClient} facade is
 * built with mocked platform deps, the shared {@link CoreNetworkClient} is
 * reached via reflection and its public {@code handlePacket} dispatch is
 * driven directly; main-thread scheduler hops run synchronously.
 */
@DisplayName("PNX NetworkClient ignore filter")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NetworkClientIgnoreFilterTest {

    private static final UUID VIEWER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID IGNORED_SENDER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID OTHER_SENDER_ID = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");

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

    private IgnoreListService ignoreListService;
    private CoreNetworkClient core;
    private Locale previousDefaultLocale;

    @BeforeEach
    void setUp() throws Exception {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        ignoreListService = new IgnoreListService();
        ignoreListService.ignore(VIEWER_ID, "Viewer", "Steve");

        when(plugin.getServer()).thenReturn(server);
        when(server.getVersion()).thenReturn("test-server");
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.toClientConnectionConfig())
                .thenReturn(ClientConnectionConfig.builder().build());
        when(plugin.getChatInterceptor()).thenReturn(chatInterceptor);
        when(plugin.getMessageFormatter()).thenReturn(formatter);
        when(plugin.getIgnoreListService()).thenReturn(ignoreListService);
        when(formatter.colorize(anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).replace('&', '\u00A7'));
        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        doReturn(Map.of(VIEWER_ID, viewer)).when(server).getOnlinePlayers();

        PlayerChannelState state = new PlayerChannelState(VIEWER_ID, "global", ChatMode.HYBRID);
        when(chatInterceptor.getState(VIEWER_ID)).thenReturn(state);
        when(chatInterceptor.shouldNotifyMention(eq(VIEWER_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        NetworkClient client = new NetworkClient(plugin, config);

        Field coreField = AbstractPlatformNetworkClient.class.getDeclaredField("core");
        coreField.setAccessible(true);
        core = (CoreNetworkClient) coreField.get(client);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    /** Runs the task submitted to the mocked main-thread scheduler. */
    private void runScheduledTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleTask(eq(plugin), task.capture());
        task.getValue().run();
    }

    @Test
    @DisplayName("item display from an ignored sender is not rendered")
    void itemDisplayFromIgnoredSenderSkipped() {
        core.handlePacket(new ItemDisplayPacket(IGNORED_SENDER_ID, "Steve", "global",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis()));
        runScheduledTask();

        verify(viewer, never()).sendMessage(anyString());
    }

    @Test
    @DisplayName("item display from a non-ignored sender still renders")
    void itemDisplayFromOtherSenderRenders() {
        core.handlePacket(new ItemDisplayPacket(OTHER_SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis()));
        runScheduledTask();

        verify(viewer).sendMessage(anyString());
    }

    @Test
    @DisplayName("mention from an ignored player: no title, no action bar")
    void mentionFromIgnoredSenderSkipped() {
        core.handlePacket(new MentionPacket(IGNORED_SENDER_ID, "Steve", VIEWER_ID,
                "global", "hi @Viewer", System.currentTimeMillis()));
        runScheduledTask();

        verify(viewer, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(viewer, never()).sendActionBar(anyString());
    }

    @Test
    @DisplayName("mention from a non-ignored player still notifies")
    void mentionFromOtherSenderNotifies() {
        core.handlePacket(new MentionPacket(OTHER_SENDER_ID, "Alex", VIEWER_ID,
                "global", "hi @Viewer", System.currentTimeMillis()));
        runScheduledTask();

        verify(viewer).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(viewer).sendActionBar(anyString());
    }
}
