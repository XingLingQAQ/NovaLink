package com.nova.chat.folia.chat;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.network.AsyncNetworkClient;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
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

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

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
 * Folia's BAN/UNBAN target-side render arms.
 *
 * <p>The dispatcher only delivers a notice an adapter declares through
 * {@code supportsTargetNotice}. That gate exists because every pre-existing
 * adapter ended its {@code notifyKickMuteTarget} with an unguarded
 * {@code "// MUTE"} branch, so a BAN delivered to an unported platform would
 * have rendered "you have been muted" for a ban. These tests pin both halves:
 * Folia declares BAN/UNBAN, and it renders the ban copy rather than the mute
 * copy.
 */
@DisplayName("Folia BAN/UNBAN target notice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncChatInterceptorBanNoticeTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private NovaChatFolia plugin;
    @Mock private NovaChatConfig config;
    @Mock private FoliaSchedulerAdapter scheduler;
    @Mock private AsyncNetworkClient networkClient;
    @Mock private Server server;
    @Mock private PluginManager pluginManager;
    @Mock private Player target;
    @Mock private Player.Spigot spigot;
    @Mock private Logger logger;

    private final ChannelResponseTracker tracker = new ChannelResponseTracker();
    private AsyncChatInterceptor interceptor;
    private Consumer<ChannelActionResponsePacket> responseHandler;
    private Locale previousLocale;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);
        when(config.getDefaultChannel()).thenReturn("global");
        when(target.getUniqueId()).thenReturn(TARGET_ID);
        when(target.isOnline()).thenReturn(true);
        when(target.spigot()).thenReturn(spigot);
        doReturn(target).when(server).getPlayer(TARGET_ID);
        // The notice lands on the target, not the operator: one uncorrelated
        // backend push, exactly what the REST/console paths send.
        doReturn(java.util.List.of(target)).when(server).getOnlinePlayers();

        interceptor = new AsyncChatInterceptor(plugin);

        ArgumentCaptor<Consumer<ChannelActionResponsePacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ChannelActionResponsePacket.class), captor.capture());
        responseHandler = captor.getValue();
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    /** Feeds an uncorrelated success response carrying the target id. */
    private void pushNotice(ChannelAction action, String channelId, String duration) {
        ChannelActionResponsePacket response =
                new ChannelActionResponsePacket(true, action, channelId, "", "ok");
        response.addExtra("targetId", TARGET_ID.toString());
        response.addExtra("operatorName", "Mod");
        if (duration != null) {
            response.addExtra("duration", duration);
        }
        responseHandler.accept(response);
    }

    /** Runs every region-thread task the adapter scheduled. */
    private void runRegionTasks() {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runForPlayer(eq(target), tasks.capture());
        tasks.getAllValues().forEach(Runnable::run);
    }

    @Test
    @DisplayName("a BAN notice renders the ban copy, not the mute copy")
    void banRendersBanCopy() {
        pushNotice(ChannelAction.BAN, "staff", "3600");
        runRegionTasks();

        ArgumentCaptor<String> subtitle = ArgumentCaptor.forClass(String.class);
        verify(target).sendTitle(anyString(), subtitle.capture(), anyInt(), anyInt(), anyInt());
        // The ban subtitle names the action; a mute fall-through would say 禁言.
        org.assertj.core.api.Assertions.assertThat(subtitle.getValue()).contains("封禁");
        // The action bar is sent through spigot().sendMessage(...), never
        // Player#sendActionBar, so a ban reaching the wrong arm is visible.
        verify(target).spigot();
    }

    @Test
    @DisplayName("an UNBAN notice renders the unban copy")
    void unbanRendersUnbanCopy() {
        pushNotice(ChannelAction.UNBAN, "staff", null);
        runRegionTasks();

        ArgumentCaptor<String> subtitle = ArgumentCaptor.forClass(String.class);
        verify(target).sendTitle(anyString(), subtitle.capture(), anyInt(), anyInt(), anyInt());
        org.assertj.core.api.Assertions.assertThat(subtitle.getValue()).contains("解封");
    }

    @Test
    @DisplayName("a global ban names a scope instead of leaving the channel blank")
    void globalBanNamesScope() {
        pushNotice(ChannelAction.BAN, "", "3600");
        runRegionTasks();

        ArgumentCaptor<String> subtitle = ArgumentCaptor.forClass(String.class);
        verify(target).sendTitle(anyString(), subtitle.capture(), anyInt(), anyInt(), anyInt());
        org.assertj.core.api.Assertions.assertThat(subtitle.getValue()).contains("全部频道");
    }

    @Test
    @DisplayName("KICK still renders the kick copy")
    void kickStillRendersKickCopy() {
        pushNotice(ChannelAction.KICK, "staff", null);
        runRegionTasks();

        ArgumentCaptor<String> subtitle = ArgumentCaptor.forClass(String.class);
        verify(target).sendTitle(anyString(), subtitle.capture(), anyInt(), anyInt(), anyInt());
        org.assertj.core.api.Assertions.assertThat(subtitle.getValue()).contains("踢出");
    }

    @Test
    @DisplayName("a failed BAN response renders no notice at all")
    void failedBanRendersNothing() {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, ChannelAction.BAN, "staff", "NC-403", "Permission denied");
        response.addExtra("targetId", TARGET_ID.toString());
        responseHandler.accept(response);

        verify(scheduler, never()).runForPlayer(any(), any(Runnable.class));
    }
}
