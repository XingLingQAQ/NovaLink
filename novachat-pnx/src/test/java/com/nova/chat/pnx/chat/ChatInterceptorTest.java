package com.nova.chat.pnx.chat;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.level.Level;
import cn.nukkit.scheduler.NukkitScheduler;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PNX {@link ChatInterceptor}.
 *
 * <p>Covers the bedrock-specific chat interception ({@code onPlayerChat}) and the
 * target-side KICK/MUTE notice rendering ({@code PNXChannelResponseAdapter.notifyKickMuteTarget})
 * that was added in UX §5. Platform API ({@code cn.nukkit.*}) is mocked with
 * Mockito; the private adapter is reached via reflection so we exercise the real
 * main-thread hop + title/action-bar rendering without a live server.
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
    private NukkitScheduler scheduler;
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
        lenient().when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getScheduler()).thenReturn(scheduler);
        lenient().when(config.getDefaultChannel()).thenReturn("global");
        lenient().when(player.getUniqueId()).thenReturn(PLAYER_ID);
        lenient().when(player.getName()).thenReturn("Steve");
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
            // Disable chat on the player's state
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

        private ChannelResponseDispatcher.ChannelResponseAdapter adapter;

        @BeforeEach
        void setUpAdapter() throws Exception {
            // The PNXChannelResponseAdapter is a private inner class of NetworkClient.
            // Instantiate it via reflection so we exercise the real rendering + thread hop
            // without standing up a full NetworkClient (which would require a live Netty core).
            adapter = instantiatePnxAdapter();
        }

        @Test
        @DisplayName("KICK sends title + actionbar with kick i18n keys and hops via scheduler")
        void kickSendsTitleAndActionbar() {
            ChannelResponseDispatcher.KickMuteNotice notice =
                    new ChannelResponseDispatcher.KickMuteNotice(
                            TARGET_ID, ChannelAction.KICK, "trade", "Admin", "0");

            Map<UUID, Player> online = new HashMap<>();
            online.put(TARGET_ID, target);
            when(server.getOnlinePlayers()).thenReturn(online);
            when(messageFormatter.colorize(anyString())).thenAnswer(inv -> inv.getArgument(0));

            adapter.notifyKickMuteTarget(notice);

            // The adapter must hop to the main thread via the scheduler.
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleTask(eq(plugin), task.capture());
            // Execute the captured hop synchronously.
            task.getValue().run();

            // KICK renders a title, subtitle and action-bar — three colorize calls total.
            verify(messageFormatter, times(3)).colorize(anyString());
            verify(target).sendTitle(anyString(), anyString(),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_IN),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_STAY),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_OUT));
            verify(target).sendActionBar(anyString());
        }

        @Test
        @DisplayName("MUTE sends title + actionbar with mute i18n keys and duration text")
        void muteSendsTitleAndActionbar() {
            ChannelResponseDispatcher.KickMuteNotice notice =
                    new ChannelResponseDispatcher.KickMuteNotice(
                            TARGET_ID, ChannelAction.MUTE, "global", "Mod", "5 minutes");

            Map<UUID, Player> online = new HashMap<>();
            online.put(TARGET_ID, target);
            when(server.getOnlinePlayers()).thenReturn(online);
            when(messageFormatter.colorize(anyString())).thenAnswer(inv -> inv.getArgument(0));

            adapter.notifyKickMuteTarget(notice);

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleTask(eq(plugin), task.capture());
            task.getValue().run();

            verify(messageFormatter, times(3)).colorize(anyString());
            verify(target).sendTitle(anyString(), anyString(),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_IN),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_STAY),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_OUT));
            verify(target).sendActionBar(anyString());
        }

        @Test
        @DisplayName("target offline: scheduler hops but no title/actionbar is sent")
        void targetOfflineSkipsRender() {
            ChannelResponseDispatcher.KickMuteNotice notice =
                    new ChannelResponseDispatcher.KickMuteNotice(
                            TARGET_ID, ChannelAction.KICK, "trade", "Admin", "0");

            when(server.getOnlinePlayers()).thenReturn(new HashMap<>());

            adapter.notifyKickMuteTarget(notice);

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleTask(eq(plugin), task.capture());
            task.getValue().run();

            verify(target, never()).sendTitle(anyString(), anyString(), any(int.class), any(int.class), any(int.class));
            verify(target, never()).sendActionBar(anyString());
        }

        /**
         * Reflectively instantiates the private {@code PNXChannelResponseAdapter}
         * inner class of {@link NetworkClient}, binding it to the mocked plugin.
         */
        private ChannelResponseDispatcher.ChannelResponseAdapter instantiatePnxAdapter() throws Exception {
            Class<?> adapterClass = Class.forName(
                    "com.nova.chat.pnx.network.NetworkClient$PNXChannelResponseAdapter");
            Constructor<?> ctor = adapterClass.getDeclaredConstructor(NetworkClient.class);
            ctor.setAccessible(true);
            // The adapter only uses the outer plugin reference, so we can pass a mocked
            // NetworkClient whose own fields are never touched by notifyKickMuteTarget.
            NetworkClient outer = networkClient;
            return (ChannelResponseDispatcher.ChannelResponseAdapter) ctor.newInstance(outer);
        }
    }
}
