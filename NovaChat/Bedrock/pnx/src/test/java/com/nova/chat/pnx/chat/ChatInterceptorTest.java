package com.nova.chat.pnx.chat;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.level.Level;
import cn.nukkit.scheduler.ServerScheduler;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.config.NovaChatConfig;
import com.nova.chat.pnx.network.NetworkClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PNX {@link ChatInterceptor}.
 *
 * <p>Covers the bedrock-specific chat interception ({@code onPlayerChat}) and the
 * target-side KICK/MUTE notice rendering ({@code PNXChannelResponseAdapter.notifyKickMuteTarget})
 * added in UX §5. Platform API ({@code cn.nukkit.*}) is mocked with Mockito; the
 * private adapter is reached via reflection bound to a real {@link NetworkClient}
 * outer instance so the adapter's {@code plugin} field reads resolve against the
 * mocked plugin.
 */
@DisplayName("PNX ChatInterceptor")
@ExtendWith(MockitoExtension.class)
class ChatInterceptorTest {

    private static final UUID PLAYER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID TARGET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatPNX plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private com.nova.chat.pnx.chat.MessageFormatter messageFormatter;
    @Mock
    private Server server;
    @Mock
    private ServerScheduler scheduler;
    @Mock
    private Player player;
    @Mock
    private Player target;
    @Mock
    private Level level;
    @Mock
    private PlayerChatEvent chatEvent;

    private ChatInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // Shared stubs used across most tests. lenient() avoids UnnecessaryStubbing
        // when a particular test only exercises a subset.
        lenient().when(plugin.getNovaChatConfig()).thenReturn(config);
        lenient().when(plugin.getNetworkClient()).thenReturn(networkClient);
        lenient().when(plugin.getMessageFormatter()).thenReturn(messageFormatter);
        lenient().when(config.getDefaultChannel()).thenReturn("global");
        lenient().when(player.getUniqueId()).thenReturn(PLAYER_ID);
        lenient().when(player.getName()).thenReturn("Steve");
        lenient().when(player.getDisplayName()).thenReturn("Steve");
        lenient().when(player.getLevel()).thenReturn(level);
        lenient().when(level.getName()).thenReturn("world");
        lenient().when(target.getUniqueId()).thenReturn(TARGET_ID);

        interceptor = new ChatInterceptor(plugin);
    }

    @Nested
    @DisplayName("onPlayerChat")
    class OnPlayerChat {

        @Test
        @DisplayName("skips when event is already cancelled")
        void skipsCancelledEvent() {
            when(chatEvent.isCancelled()).thenReturn(true);

            interceptor.onPlayerChat(chatEvent);

            verify(chatEvent, never()).setCancelled(true);
            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("cancels event and sends error when chat is disabled")
        void cancelsWhenChatDisabled() {
            when(chatEvent.isCancelled()).thenReturn(false);
            when(chatEvent.getPlayer()).thenReturn(player);
            when(chatEvent.getMessage()).thenReturn("hi");
            when(messageFormatter.formatError(anyString())).thenReturn("err");
            interceptor.getOrCreateState(player).setChatEnabled(false);

            interceptor.onPlayerChat(chatEvent);

            verify(chatEvent).setCancelled(true);
            verify(player).sendMessage(eq("err"));
            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("replace_vanilla cancels the vanilla event and forwards to backend")
        void replaceVanillaCancelsAndForwards() {
            when(chatEvent.isCancelled()).thenReturn(false);
            when(chatEvent.getPlayer()).thenReturn(player);
            when(chatEvent.getMessage()).thenReturn("hello");
            when(config.isReplaceVanilla()).thenReturn(true);
            when(networkClient.isConnected()).thenReturn(true);
            when(networkClient.isAuthenticated()).thenReturn(true);
            when(config.getBackendUsername()).thenReturn("pnx-server");

            interceptor.onPlayerChat(chatEvent);

            verify(chatEvent).setCancelled(true);
            ArgumentCaptor<ChatMessagePacket> captor = ArgumentCaptor.forClass(ChatMessagePacket.class);
            verify(networkClient).sendPacket(captor.capture());
            ChatMessagePacket sent = captor.getValue();
            assertThat(sent.getChannelId()).isEqualTo("global");
            assertThat(sent.getContent()).isEqualTo("hello");
            assertThat(sent.getSenderName()).isEqualTo("Steve");
        }

        @Test
        @DisplayName("replace_vanilla false leaves vanilla chat untouched but still forwards")
        void hybridModeDoesNotCancel() {
            when(chatEvent.isCancelled()).thenReturn(false);
            when(chatEvent.getPlayer()).thenReturn(player);
            when(chatEvent.getMessage()).thenReturn("hello");
            when(config.isReplaceVanilla()).thenReturn(false);
            when(networkClient.isConnected()).thenReturn(true);
            when(networkClient.isAuthenticated()).thenReturn(true);
            when(config.getBackendUsername()).thenReturn("pnx-server");

            interceptor.onPlayerChat(chatEvent);

            verify(chatEvent, never()).setCancelled(true);
            verify(networkClient).sendPacket(any(ChatMessagePacket.class));
        }

        @Test
        @DisplayName("not connected shows error and does not send packet")
        void notConnectedShowsError() {
            when(chatEvent.isCancelled()).thenReturn(false);
            when(chatEvent.getPlayer()).thenReturn(player);
            when(chatEvent.getMessage()).thenReturn("hello");
            when(config.isReplaceVanilla()).thenReturn(true);
            when(networkClient.isConnected()).thenReturn(false);
            when(messageFormatter.formatError(anyString())).thenReturn("no-net");

            interceptor.onPlayerChat(chatEvent);

            verify(chatEvent).setCancelled(true);
            verify(player).sendMessage(eq("no-net"));
            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("connected but not authenticated shows connecting error")
        void notAuthenticatedShowsConnecting() {
            when(chatEvent.isCancelled()).thenReturn(false);
            when(chatEvent.getPlayer()).thenReturn(player);
            when(chatEvent.getMessage()).thenReturn("hello");
            when(config.isReplaceVanilla()).thenReturn(true);
            when(networkClient.isConnected()).thenReturn(true);
            when(networkClient.isAuthenticated()).thenReturn(false);
            when(messageFormatter.formatError(anyString())).thenReturn("connecting");

            interceptor.onPlayerChat(chatEvent);

            verify(chatEvent).setCancelled(true);
            verify(player).sendMessage(eq("connecting"));
            verify(networkClient, never()).sendPacket(any());
        }
    }

    @Nested
    @DisplayName("PNXChannelResponseAdapter.notifyKickMuteTarget")
    class NotifyKickMuteTarget {

        // A separate plugin mock used to build a REAL NetworkClient (the private
        // PNXChannelResponseAdapter is an inner class of NetworkClient and reads
        // the outer instance's `plugin` field, so the outer instance must be a
        // real NetworkClient — a Mockito mock would leave that field null).
        private NovaChatPNX adapterPlugin;
        private NovaChatConfig adapterConfig;
        private Server adapterServer;
        private ServerScheduler adapterScheduler;
        private com.nova.chat.pnx.chat.MessageFormatter adapterFormatter;
        private ChatInterceptor adapterChatInterceptor;
        private Player adapterTarget;
        private NetworkClient realClient;
        private ChannelResponseDispatcher.ChannelResponseAdapter adapter;

        @BeforeEach
        void setUpAdapter() throws Exception {
            adapterPlugin = mock(NovaChatPNX.class);
            adapterConfig = mock(NovaChatConfig.class);
            adapterServer = mock(Server.class);
            adapterScheduler = mock(ServerScheduler.class);
            adapterFormatter = mock(com.nova.chat.pnx.chat.MessageFormatter.class);
            adapterChatInterceptor = mock(ChatInterceptor.class);
            adapterTarget = mock(Player.class);

            ClientConnectionConfig connConfig = ClientConnectionConfig.builder()
                    .host("127.0.0.1").port(8888).username("u").password("p")
                    .build();

            // CoreNetworkClient's constructor only stores fields (no I/O / threads),
            // so a real NetworkClient is safe to build with mocked platform deps.
            lenient().when(adapterPlugin.getNovaChatConfig()).thenReturn(adapterConfig);
            lenient().when(adapterPlugin.getServer()).thenReturn(adapterServer);
            lenient().when(adapterServer.getScheduler()).thenReturn(adapterScheduler);
            lenient().when(adapterServer.getVersion()).thenReturn("1.0");
            lenient().when(adapterConfig.toClientConnectionConfig()).thenReturn(connConfig);
            lenient().when(adapterPlugin.getMessageFormatter()).thenReturn(adapterFormatter);
            lenient().when(adapterPlugin.getChatInterceptor()).thenReturn(adapterChatInterceptor);
            lenient().when(adapterTarget.getUniqueId()).thenReturn(TARGET_ID);
            lenient().when(adapterFormatter.colorize(anyString())).thenAnswer(inv -> inv.getArgument(0));

            realClient = new NetworkClient(adapterPlugin, adapterConfig);
            adapter = instantiatePnxAdapter(realClient);
        }

        @Test
        @DisplayName("KICK sends title + actionbar with kick i18n keys and hops via scheduler")
        void kickSendsTitleAndActionbar() {
            ChannelResponseDispatcher.KickMuteNotice notice =
                    new ChannelResponseDispatcher.KickMuteNotice(
                            TARGET_ID, ChannelAction.KICK, "trade", "Admin", "0");

            Map<UUID, Player> online = new HashMap<>();
            online.put(TARGET_ID, adapterTarget);
            when(adapterServer.getOnlinePlayers()).thenReturn(online);

            adapter.notifyKickMuteTarget(notice);

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(adapterScheduler).scheduleTask(eq(adapterPlugin), task.capture());
            task.getValue().run();

            // KICK renders title, subtitle and action-bar — three colorize calls.
            verify(adapterFormatter, times(3)).colorize(anyString());
            verify(adapterTarget).sendTitle(anyString(), anyString(),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_IN),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_STAY),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_OUT));
            verify(adapterTarget).sendActionBar(anyString());
        }

        @Test
        @DisplayName("MUTE sends title + actionbar with mute i18n keys and duration text")
        void muteSendsTitleAndActionbar() {
            ChannelResponseDispatcher.KickMuteNotice notice =
                    new ChannelResponseDispatcher.KickMuteNotice(
                            TARGET_ID, ChannelAction.MUTE, "global", "Mod", "5 minutes");

            Map<UUID, Player> online = new HashMap<>();
            online.put(TARGET_ID, adapterTarget);
            when(adapterServer.getOnlinePlayers()).thenReturn(online);

            adapter.notifyKickMuteTarget(notice);

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(adapterScheduler).scheduleTask(eq(adapterPlugin), task.capture());
            task.getValue().run();

            verify(adapterFormatter, times(3)).colorize(anyString());
            verify(adapterTarget).sendTitle(anyString(), anyString(),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_IN),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_STAY),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_OUT));
            verify(adapterTarget).sendActionBar(anyString());
        }

        @Test
        @DisplayName("target offline: scheduler hops but no title/actionbar is sent")
        void targetOfflineSkipsRender() {
            ChannelResponseDispatcher.KickMuteNotice notice =
                    new ChannelResponseDispatcher.KickMuteNotice(
                            TARGET_ID, ChannelAction.KICK, "trade", "Admin", "0");

            when(adapterServer.getOnlinePlayers()).thenReturn(new HashMap<>());

            adapter.notifyKickMuteTarget(notice);

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(adapterScheduler).scheduleTask(eq(adapterPlugin), task.capture());
            task.getValue().run();

            verify(adapterTarget, never()).sendTitle(anyString(), anyString(),
                    any(int.class), any(int.class), any(int.class));
            verify(adapterTarget, never()).sendActionBar(anyString());
        }

        /**
         * Reflectively instantiates the private {@code PNXChannelResponseAdapter}
         * inner class, bound to the real {@link NetworkClient} outer instance so
         * the adapter's {@code plugin} field reads resolve correctly.
         */
        private ChannelResponseDispatcher.ChannelResponseAdapter instantiatePnxAdapter(
                NetworkClient outer) throws Exception {
            Class<?> adapterClass = Class.forName(
                    "com.nova.chat.pnx.network.NetworkClient$PNXChannelResponseAdapter");
            Constructor<?> ctor = adapterClass.getDeclaredConstructor(NetworkClient.class);
            ctor.setAccessible(true);
            return (ChannelResponseDispatcher.ChannelResponseAdapter) ctor.newInstance(outer);
        }
    }
}
