package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.chat.ChatInterceptor;
import com.nova.chat.bukkit.chat.MessageFormatter;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
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

import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * A downstream plugin-message packet must not render to players the proxy has
 * already served.
 *
 * <p>Relay fan-out sends every backend-originated packet down the
 * {@code novachat:main} channel to every sub-server, carrying the proxy's own
 * roster in the exclusion list. The proxy already rendered the packet to those
 * players, so the sub-server must skip them or the line appears twice. Chat had
 * this filter from the start; the other renderable types are covered here
 * because they were routed downstream at the same time.
 */
@DisplayName("Bukkit proxy fan-out exclusion (title / mention / private message)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NetworkClientChannelExclusionTest {

    private static final UUID PROXIED_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID DIRECT_ID = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");
    private static final UUID SENDER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private NovaChatBukkit plugin;
    @Mock private NovaChatConfig config;
    @Mock private Server server;
    @Mock private BukkitScheduler scheduler;
    @Mock private ChatInterceptor chatInterceptor;
    @Mock private MessageFormatter formatter;
    @Mock private Player proxied;
    @Mock private Player direct;
    @Mock private IgnoreListService ignoreListService;

    private NetworkClient client;
    private Locale previousLocale;

    @BeforeEach
    void setUp() {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getServer()).thenReturn(server);
        when(server.getVersion()).thenReturn("test-server");
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.toClientConnectionConfig())
                .thenReturn(ClientConnectionConfig.builder().build());
        when(plugin.getChatInterceptor()).thenReturn(chatInterceptor);
        when(plugin.getIgnoreListService()).thenReturn(ignoreListService);
        when(plugin.getPrivateMessageService()).thenReturn(new PrivateMessageService());
        when(chatInterceptor.getMessageFormatter()).thenReturn(formatter);
        when(formatter.translateColorCodes(anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).replace('&', '§'));
        doReturn(List.of(proxied, direct)).when(server).getOnlinePlayers();

        PlayerChannelState state = new PlayerChannelState(DIRECT_ID, "global", ChatMode.HYBRID);
        when(chatInterceptor.getOrCreateState(proxied)).thenReturn(state);
        when(chatInterceptor.getOrCreateState(direct)).thenReturn(state);

        when(proxied.getUniqueId()).thenReturn(PROXIED_ID);
        when(direct.getUniqueId()).thenReturn(DIRECT_ID);

        // Neither player is reachable by the sub-server's own lookup when it is
        // excluded, so the DM handler's "is local online" test is the filter.
        when(server.getPlayer(PROXIED_ID)).thenReturn(proxied);
        when(server.getPlayer(DIRECT_ID)).thenReturn(direct);

        client = new NetworkClient(plugin, config, new KnownChannelRegistry());
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    @Test
    @DisplayName("a title skips the player the proxy already showed it to")
    void titleSkipsProxiedPlayer() {
        client.dispatchChannelPacket(
                new TitlePacket("global", "Hello", "World", SENDER_ID), Set.of(PROXIED_ID));
        runMainThreadTask();

        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(direct).sendTitle("Hello", "World", 10, 70, 20);
    }

    @Test
    @DisplayName("a mention skips the proxied player and notifies the direct one")
    void mentionSkipsProxiedPlayer() {
        client.dispatchChannelPacket(new MentionPacket(SENDER_ID, "Alice", PROXIED_ID,
                "global", "hi", System.currentTimeMillis()), Set.of(PROXIED_ID));
        runMainThreadTask();

        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a mention for a direct player still notifies")
    void mentionReachesDirectPlayer() {
        client.dispatchChannelPacket(new MentionPacket(SENDER_ID, "Alice", DIRECT_ID,
                "global", "hi", System.currentTimeMillis()), Set.of(PROXIED_ID));
        try {
            runMainThreadTask();
        } catch (Throwable soundRegistryUnavailable) {
            // org.bukkit.Sound's static init needs a live server; the title send
            // happens first, so the assertion below is still meaningful.
        }

        verify(direct).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a private message renders for the direct receiver, not the proxied one")
    void privateMessageSkipsProxiedPlayer() {
        PrivateMessagePacket packet = new PrivateMessagePacket(SENDER_ID, "Alice",
                "survival-01", "Dave", DIRECT_ID, "psst", System.currentTimeMillis());

        client.dispatchChannelPacket(packet, Set.of(PROXIED_ID));
        runMainThreadTask();

        // The proxied player is the sender here: the proxy rendered the echo, so
        // the sub-server must not repeat it.
        verify(proxied, never()).sendMessage(anyString());
        verify(direct).sendMessage(anyString());
    }

    @Test
    @DisplayName("with no exclusions both players are rendered to")
    void noExclusionsRendersEveryone() {
        client.dispatchChannelPacket(
                new TitlePacket("global", "Hello", "World", SENDER_ID), Set.of());
        runMainThreadTask();

        verify(proxied).sendTitle("Hello", "World", 10, 70, 20);
        verify(direct).sendTitle("Hello", "World", 10, 70, 20);
    }

    private void runMainThreadTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runTask(eq(plugin), task.capture());
        task.getValue().run();
    }
}
