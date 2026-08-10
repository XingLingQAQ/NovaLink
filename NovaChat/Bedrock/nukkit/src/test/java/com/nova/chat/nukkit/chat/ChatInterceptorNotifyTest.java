package com.nova.chat.nukkit.chat;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.scheduler.ServerScheduler;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.config.NovaChatConfig;
import com.nova.chat.nukkit.command.MessageHelper;
import com.nova.chat.nukkit.network.NetworkClient;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Nukkit {@link ChatInterceptor}'s KICK/MUTE target notice.
 *
 * <p>{@code NukkitChannelResponseAdapter} is a private inner class of
 * {@link ChatInterceptor}, so it binds to the real instance fields
 * ({@code plugin}, {@code messageFormatter}, {@code config}) populated by the
 * {@link ChatInterceptor} constructor. We instantiate the adapter via reflection
 * and drive {@code notifyKickMuteTarget}, capturing the scheduler main-thread hop
 * to verify the title + action-bar rendering against the mocked Player.
 */
@DisplayName("Nukkit ChatInterceptor notifyKickMuteTarget")
@ExtendWith(MockitoExtension.class)
class ChatInterceptorNotifyTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatNukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private MessageHelper messageHelper;
    @Mock
    private Server server;
    @Mock
    private ServerScheduler scheduler;
    @Mock
    private Player target;
    @Mock
    private ChannelResponseTracker tracker;

    private ChatInterceptor interceptor;
    private ChannelResponseDispatcher.ChannelResponseAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(plugin.getNovaChatConfig()).thenReturn(config);
        lenient().when(plugin.getNetworkClient()).thenReturn(networkClient);
        lenient().when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        lenient().when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getScheduler()).thenReturn(scheduler);
        lenient().when(config.getDefaultChannel()).thenReturn("global");
        lenient().when(config.isReplaceVanilla()).thenReturn(false);
        lenient().when(config.getPrefix()).thenReturn("§8[§bNovaChat§8]§r ");
        lenient().when(config.getErrorFormat()).thenReturn("§c{message}");
        lenient().when(target.getUniqueId()).thenReturn(TARGET_ID);

        // Real ChatInterceptor — its constructor populates the plugin/config/messageFormatter
        // instance fields the inner adapter reads, and registers handlers on the network client.
        interceptor = new ChatInterceptor(plugin);
        adapter = instantiateNukkitAdapter(interceptor);
    }

    @Nested
    @DisplayName("KICK")
    class Kick {

        @Test
        @DisplayName("sends title + actionbar with kick i18n keys via scheduler hop")
        void sendsKickTitleAndActionbar() {
            ChannelResponseDispatcher.KickMuteNotice notice =
                    new ChannelResponseDispatcher.KickMuteNotice(
                            TARGET_ID, ChannelAction.KICK, "trade", "Admin", "0");

            when(server.getPlayer(TARGET_ID)).thenReturn(Optional.of(target));

            adapter.notifyKickMuteTarget(notice);

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleTask(eq(plugin), task.capture());
            task.getValue().run();

            // Capture the title, subtitle and action-bar rendered to the target.
            ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> subtitle = ArgumentCaptor.forClass(String.class);
            verify(target).sendTitle(title.capture(), subtitle.capture(),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_IN),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_STAY),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_OUT));
            ArgumentCaptor<String> actionbar = ArgumentCaptor.forClass(String.class);
            verify(target).sendActionBar(actionbar.capture());

            // The resolved text must carry the operator + channel and be color-translated
            // (& -> §) by the real MessageFormatter bound to the interceptor.
            assertThat(title.getValue()).contains("踢出");
            assertThat(subtitle.getValue()).contains("Admin", "trade");
            assertThat(subtitle.getValue()).contains("§");
            assertThat(actionbar.getValue()).contains("Admin", "trade");
        }
    }

    @Nested
    @DisplayName("MUTE")
    class Mute {

        @Test
        @DisplayName("sends title + actionbar with mute i18n keys and duration text")
        void sendsMuteTitleAndActionbar() {
            ChannelResponseDispatcher.KickMuteNotice notice =
                    new ChannelResponseDispatcher.KickMuteNotice(
                            TARGET_ID, ChannelAction.MUTE, "global", "Mod", "5 minutes");

            when(server.getPlayer(TARGET_ID)).thenReturn(Optional.of(target));

            adapter.notifyKickMuteTarget(notice);

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleTask(eq(plugin), task.capture());
            task.getValue().run();

            ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> subtitle = ArgumentCaptor.forClass(String.class);
            verify(target).sendTitle(title.capture(), subtitle.capture(),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_IN),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_STAY),
                    eq(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_OUT));
            ArgumentCaptor<String> actionbar = ArgumentCaptor.forClass(String.class);
            verify(target).sendActionBar(actionbar.capture());

            assertThat(title.getValue()).contains("禁言");
            assertThat(subtitle.getValue()).contains("global", "5 minutes");
            assertThat(actionbar.getValue()).contains("5 minutes", "global");
        }
    }

    @Nested
    @DisplayName("offline target")
    class OfflineTarget {

        @Test
        @DisplayName("hops to scheduler but renders nothing when target is offline")
        void offlineTargetSkipsRender() {
            ChannelResponseDispatcher.KickMuteNotice notice =
                    new ChannelResponseDispatcher.KickMuteNotice(
                            TARGET_ID, ChannelAction.KICK, "trade", "Admin", "0");

            when(server.getPlayer(TARGET_ID)).thenReturn(Optional.empty());

            adapter.notifyKickMuteTarget(notice);

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleTask(eq(plugin), task.capture());
            task.getValue().run();

            verify(target, never()).sendTitle(anyString(), anyString(),
                    any(int.class), any(int.class), any(int.class));
            verify(target, never()).sendActionBar(anyString());
        }
    }

    /**
     * Reflectively instantiates the private {@code NukkitChannelResponseAdapter}
     * inner class, bound to the real {@link ChatInterceptor} outer instance so the
     * adapter's field reads ({@code plugin}, {@code messageFormatter}, {@code config})
     * resolve against the real interceptor state.
     */
    private static ChannelResponseDispatcher.ChannelResponseAdapter instantiateNukkitAdapter(
            ChatInterceptor outer) throws Exception {
        Class<?> adapterClass = Class.forName(
                "com.nova.chat.nukkit.chat.ChatInterceptor$NukkitChannelResponseAdapter");
        Constructor<?> ctor = adapterClass.getDeclaredConstructor(ChatInterceptor.class);
        ctor.setAccessible(true);
        return (ChannelResponseDispatcher.ChannelResponseAdapter) ctor.newInstance(outer);
    }
}
