package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.chat.ChatInterceptor;
import com.nova.chat.bukkit.chat.MessageFormatter;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Location;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Bukkit inbound ignore filter (/nc ignore): mention
 * notifications and item-display lines from an ignored sender are skipped,
 * while other senders still render. Infrastructure mirrors
 * {@link NetworkClientItemDisplayTest} (core reached via reflection, packet
 * driven through the public dispatch, main-thread hop captured).
 */
@DisplayName("Bukkit NetworkClient ignore filtering")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NetworkClientIgnoreFilterTest {

    private static final UUID IGNORED_SENDER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_SENDER_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");
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
    @Mock
    private Location location;

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
        when(plugin.getIgnoreListService()).thenReturn(ignoreListService);
        when(chatInterceptor.getMessageFormatter()).thenReturn(formatter);
        when(formatter.translateColorCodes(anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).replace('&', '\u00A7'));
        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        when(viewer.spigot()).thenReturn(spigot);
        when(viewer.getLocation()).thenReturn(location);
        doReturn(List.of(viewer)).when(server).getOnlinePlayers();
        when(server.getPlayer(VIEWER_ID)).thenReturn(viewer);

        PlayerChannelState state = new PlayerChannelState(VIEWER_ID, "global", ChatMode.HYBRID);
        when(chatInterceptor.getOrCreateState(viewer)).thenReturn(state);

        NetworkClient client = new NetworkClient(plugin, config, new KnownChannelRegistry());

        Field coreField = AbstractPlatformNetworkClient.class.getDeclaredField("core");
        coreField.setAccessible(true);
        core = (CoreNetworkClient) coreField.get(client);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    private Runnable capturedMainThreadTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runTask(eq(plugin), task.capture());
        return task.getValue();
    }

    @Test
    @DisplayName("item display from an ignored sender is not rendered")
    void itemDisplayFromIgnoredSenderSkipped() {
        core.handlePacket(new ItemDisplayPacket(IGNORED_SENDER_ID, "Steve", "global",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis()));
        capturedMainThreadTask().run();

        verify(spigot, never()).sendMessage(any(BaseComponent.class));
    }

    @Test
    @DisplayName("item display from a non-ignored sender still renders")
    void itemDisplayFromOtherSenderRenders() {
        core.handlePacket(new ItemDisplayPacket(OTHER_SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis()));
        capturedMainThreadTask().run();

        verify(spigot).sendMessage(any(BaseComponent.class));
    }

    @Test
    @DisplayName("mention from an ignored player: no title, no sound")
    void mentionFromIgnoredSenderSkipped() {
        core.handlePacket(new MentionPacket(IGNORED_SENDER_ID, "Steve", VIEWER_ID,
                "global", "hi @Viewer", System.currentTimeMillis()));
        capturedMainThreadTask().run();

        verify(viewer, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(viewer, never()).playSound(any(Location.class), any(org.bukkit.Sound.class),
                org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.anyFloat());
    }

    @Test
    @DisplayName("mention from a non-ignored player still notifies")
    void mentionFromOtherSenderNotifies() {
        core.handlePacket(new MentionPacket(OTHER_SENDER_ID, "Alex", VIEWER_ID,
                "global", "hi @Viewer", System.currentTimeMillis()));
        try {
            capturedMainThreadTask().run();
        } catch (Throwable soundRegistryUnavailable) {
            // org.bukkit.Sound's static init needs a live server registry which
            // does not exist in unit tests; the title send asserted below
            // happens before the sound call, so the assertion is still valid.
        }

        verify(viewer).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }
}
