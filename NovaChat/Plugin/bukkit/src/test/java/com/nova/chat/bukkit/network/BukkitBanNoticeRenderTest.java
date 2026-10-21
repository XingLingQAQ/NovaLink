package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
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
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bukkit's BAN/UNBAN target-side render arms.
 *
 * <p>Drives a real {@link NetworkClient} (so the production dispatcher and
 * adapter run) with an uncorrelated successful response carrying a
 * {@code targetId}, which is exactly what the console/REST push and the
 * cross-server forwarding produce. Then it runs the scheduler tasks the adapter
 * queued and inspects what the target was sent.
 *
 * <p>The load-bearing assertion is that a BAN renders the ban copy: every
 * adapter's method used to end in an unguarded "// MUTE" branch, so an
 * unported platform would have told a banned player they were muted.
 */
@DisplayName("Bukkit BAN/UNBAN target notice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BukkitBanNoticeRenderTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private NovaChatBukkit plugin;
    @Mock private NovaChatConfig config;
    @Mock private Server server;
    @Mock private BukkitScheduler scheduler;
    @Mock private Player target;
    @Mock private Player.Spigot spigot;
    @Mock private MessageHelper messageHelper;
    @Mock private Logger logger;

    private CoreNetworkClient core;
    private Locale previousLocale;

    @BeforeEach
    void setUp() throws Exception {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getServer()).thenReturn(server);
        when(server.getVersion()).thenReturn("test-server");
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.toClientConnectionConfig())
                .thenReturn(ClientConnectionConfig.builder().build());
        when(plugin.getMessageHelper()).thenReturn(messageHelper);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getErrorHandler()).thenReturn(
                mock(com.nova.chat.bukkit.error.ErrorMessageHandler.class));

        when(target.getUniqueId()).thenReturn(TARGET_ID);
        when(target.spigot()).thenReturn(spigot);
        doReturn(target).when(server).getPlayer(TARGET_ID);

        NetworkClient client = new NetworkClient(plugin, config, new KnownChannelRegistry());
        Field coreField = AbstractPlatformNetworkClient.class.getDeclaredField("core");
        coreField.setAccessible(true);
        core = (CoreNetworkClient) coreField.get(client);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    /** Feeds an uncorrelated success carrying the target id (console/REST shape). */
    private void pushNotice(ChannelAction action, String channelId, String duration) {
        ChannelActionResponsePacket response =
                new ChannelActionResponsePacket(true, action, channelId, "", "ok");
        response.addExtra("targetId", TARGET_ID.toString());
        response.addExtra("operatorName", "Mod");
        if (duration != null) {
            response.addExtra("duration", duration);
        }
        core.handlePacket(response);
    }

    /** Runs every main-thread task the adapter queued. */
    private void runScheduledTasks() {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runTask(eq(plugin), tasks.capture());
        tasks.getAllValues().forEach(Runnable::run);
    }

    @Test
    @DisplayName("a BAN notice renders the ban title/subtitle, not the mute copy")
    void banRendersBanCopy() {
        pushNotice(ChannelAction.BAN, "staff", "3600");
        runScheduledTasks();

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subtitle = ArgumentCaptor.forClass(String.class);
        verify(target).sendTitle(title.capture(), subtitle.capture(),
                anyInt(), anyInt(), anyInt());
        assertThat(subtitle.getValue()).contains("封禁");
        assertThat(title.getValue()).isNotBlank();
        verify(spigot).sendMessage(any(net.md_5.bungee.api.ChatMessageType.class),
                any(net.md_5.bungee.api.chat.BaseComponent.class));
    }

    @Test
    @DisplayName("an UNBAN notice renders the unban copy")
    void unbanRendersUnbanCopy() {
        pushNotice(ChannelAction.UNBAN, "staff", null);
        runScheduledTasks();

        ArgumentCaptor<String> subtitle = ArgumentCaptor.forClass(String.class);
        verify(target).sendTitle(anyString(), subtitle.capture(), anyInt(), anyInt(), anyInt());
        assertThat(subtitle.getValue()).contains("解封");
    }

    @Test
    @DisplayName("a global ban names a scope instead of leaving the channel blank")
    void globalBanNamesScope() {
        pushNotice(ChannelAction.BAN, "", "3600");
        runScheduledTasks();

        ArgumentCaptor<String> subtitle = ArgumentCaptor.forClass(String.class);
        verify(target).sendTitle(anyString(), subtitle.capture(), anyInt(), anyInt(), anyInt());
        assertThat(subtitle.getValue()).contains("全部频道");
    }

    @Test
    @DisplayName("MUTE still renders the mute subtitle (channel + duration)")
    void existingArmsStillRender() {
        pushNotice(ChannelAction.MUTE, "staff", "600");
        runScheduledTasks();

        ArgumentCaptor<String> subtitle = ArgumentCaptor.forClass(String.class);
        verify(target).sendTitle(anyString(), subtitle.capture(), anyInt(), anyInt(), anyInt());
        // zh_CN mute_subtitle is "在频道 {0} 持续 {1}" — it does NOT name the
        // action, so assert on the channel + formatted duration instead.
        assertThat(subtitle.getValue()).contains("staff").contains("10");
    }

    @Test
    @DisplayName("a target that is not on this server renders nothing")
    void offlineTargetRendersNothing() {
        doReturn(null).when(server).getPlayer(TARGET_ID);

        pushNotice(ChannelAction.BAN, "staff", "3600");
        runScheduledTasks();

        verify(target, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("an adapter without a BAN arm declares it and is never asked to render one")
    void capabilityGateMatchesDeclaredArms() {
        // The gate is what keeps unported platforms silent; assert the production
        // adapter declares exactly the four arms it implements.
        BukkitChannelResponseAdapter adapter =
                new BukkitChannelResponseAdapter(new NetworkClient(
                        plugin, config, new KnownChannelRegistry()));
        assertThat(adapter.supportsTargetNotice(ChannelAction.KICK)).isTrue();
        assertThat(adapter.supportsTargetNotice(ChannelAction.MUTE)).isTrue();
        assertThat(adapter.supportsTargetNotice(ChannelAction.BAN)).isTrue();
        assertThat(adapter.supportsTargetNotice(ChannelAction.UNBAN)).isTrue();
        assertThat(adapter.supportsTargetNotice(ChannelAction.JOIN)).isFalse();
    }
}
