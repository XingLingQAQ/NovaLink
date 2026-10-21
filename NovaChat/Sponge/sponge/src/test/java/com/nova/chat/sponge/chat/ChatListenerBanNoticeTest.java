package com.nova.chat.sponge.chat;

import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.sponge.NovaChatSponge;
import com.nova.chat.sponge.config.NovaChatConfig;
import com.nova.chat.sponge.network.NetworkClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.lang.reflect.Constructor;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sponge's BAN/UNBAN target-side render arms.
 *
 * <p>Every adapter used to end {@code notifyKickMuteTarget} in an unguarded
 * {@code "// MUTE"} branch. The dispatcher's {@code supportsTargetNotice} gate
 * keeps an unported platform silent; these tests pin both halves for Sponge:
 * the adapter declares the two new arms, and a BAN/UNBAN renders its own copy
 * rather than the mute copy.
 */
@DisplayName("Sponge BAN/UNBAN target notice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatListenerBanNoticeTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

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
    private ServerPlayer target;

    private MockedStatic<Sponge> spongeStatic;
    private ChatListener listener;
    private ChannelResponseDispatcher.ChannelResponseAdapter adapter;
    private Locale previousLocale;

    @BeforeEach
    void setUp() throws Exception {
        previousLocale = com.nova.chat.client.i18n.I18n.getDefaultLocale();
        com.nova.chat.client.i18n.I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(plugin.getContainer()).thenReturn(container);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(false);
        when(target.uniqueId()).thenReturn(TARGET_ID);

        spongeStatic = Mockito.mockStatic(Sponge.class);
        spongeStatic.when(Sponge::server).thenReturn(server);
        when(server.scheduler()).thenReturn(scheduler);
        when(scheduler.executor(container)).thenReturn(executor);

        listener = new ChatListener(plugin);
        adapter = instantiateAdapter(listener);
    }

    @AfterEach
    void tearDown() {
        spongeStatic.close();
        com.nova.chat.client.i18n.I18n.setDefaultLocale(previousLocale);
    }

    private void runExecutorHop(ChannelResponseDispatcher.KickMuteNotice notice) {
        when(server.player(TARGET_ID)).thenReturn(Optional.of(target));
        adapter.notifyKickMuteTarget(notice);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
    }

    private String titlePlainText() {
        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(target).showTitle(shown.capture());
        return PlainTextComponentSerializer.plainText().serialize(shown.getValue().title());
    }

    private String subtitlePlainText() {
        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(target).showTitle(shown.capture());
        return PlainTextComponentSerializer.plainText().serialize(shown.getValue().subtitle());
    }

    private String actionBarPlainText() {
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(target).sendActionBar(bar.capture());
        return PlainTextComponentSerializer.plainText().serialize(bar.getValue());
    }

    @Nested
    @DisplayName("BAN")
    class Ban {

        @Test
        @DisplayName("renders the ban copy with operator, channel and duration")
        void rendersBanCopy() {
            runExecutorHop(new ChannelResponseDispatcher.KickMuteNotice(
                    TARGET_ID, ChannelAction.BAN, "trade", "Admin", "1小时"));

            assertThat(titlePlainText()).contains("封禁").doesNotContain("禁言");
            assertThat(subtitlePlainText()).contains("Admin", "trade", "1小时");
            assertThat(actionBarPlainText()).contains("Admin", "trade", "1小时");
        }
    }

    @Nested
    @DisplayName("UNBAN")
    class Unban {

        @Test
        @DisplayName("renders the unban copy with operator and channel")
        void rendersUnbanCopy() {
            runExecutorHop(new ChannelResponseDispatcher.KickMuteNotice(
                    TARGET_ID, ChannelAction.UNBAN, "trade", "Admin", "一段时间"));

            assertThat(titlePlainText()).contains("解封");
            assertThat(subtitlePlainText()).contains("Admin", "trade");
            assertThat(actionBarPlainText()).contains("Admin", "trade");
        }
    }

    @Nested
    @DisplayName("existing arms")
    class ExistingArms {

        @Test
        @DisplayName("KICK still renders the kick copy")
        void kickUnchanged() {
            runExecutorHop(new ChannelResponseDispatcher.KickMuteNotice(
                    TARGET_ID, ChannelAction.KICK, "trade", "Admin", "0"));

            assertThat(titlePlainText()).contains("踢出");
            assertThat(subtitlePlainText()).contains("Admin", "trade");
        }

        @Test
        @DisplayName("MUTE still renders the mute copy (channel + duration, no operator)")
        void muteUnchanged() {
            runExecutorHop(new ChannelResponseDispatcher.KickMuteNotice(
                    TARGET_ID, ChannelAction.MUTE, "global", "Mod", "5分钟"));

            assertThat(titlePlainText()).contains("禁言");
            assertThat(subtitlePlainText()).contains("global", "5分钟").doesNotContain("Mod");
        }
    }

    @Nested
    @DisplayName("capability gate")
    class CapabilityGate {

        @Test
        @DisplayName("declares exactly KICK, MUTE, BAN and UNBAN")
        void declaresAllFourArms() {
            assertThat(adapter.supportsTargetNotice(ChannelAction.KICK)).isTrue();
            assertThat(adapter.supportsTargetNotice(ChannelAction.MUTE)).isTrue();
            assertThat(adapter.supportsTargetNotice(ChannelAction.BAN)).isTrue();
            assertThat(adapter.supportsTargetNotice(ChannelAction.UNBAN)).isTrue();
            assertThat(adapter.supportsTargetNotice(ChannelAction.JOIN)).isFalse();
        }

        @Test
        @DisplayName("an offline target renders nothing")
        void offlineTargetRendersNothing() {
            when(server.player(TARGET_ID)).thenReturn(Optional.empty());

            adapter.notifyKickMuteTarget(new ChannelResponseDispatcher.KickMuteNotice(
                    TARGET_ID, ChannelAction.BAN, "trade", "Admin", "1小时"));

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(executor).execute(task.capture());
            task.getValue().run();

            verify(target, org.mockito.Mockito.never()).showTitle(any(Title.class));
            verify(target, org.mockito.Mockito.never()).sendActionBar(any(Component.class));
        }
    }

    /**
     * Reflectively instantiates the private {@code SpongeChannelResponseAdapter}
     * inner class against the real {@link ChatListener}, so its field reads
     * ({@code plugin}, {@code config}) resolve against the mocked plugin.
     */
    private static ChannelResponseDispatcher.ChannelResponseAdapter instantiateAdapter(
            ChatListener outer) throws Exception {
        Class<?> adapterClass = Class.forName(
                "com.nova.chat.sponge.chat.ChatListener$SpongeChannelResponseAdapter");
        Constructor<?> ctor = adapterClass.getDeclaredConstructor(ChatListener.class);
        ctor.setAccessible(true);
        return (ChannelResponseDispatcher.ChannelResponseAdapter) ctor.newInstance(outer);
    }
}
