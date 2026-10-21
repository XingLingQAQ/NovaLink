package com.nova.chat.bungee.platform;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.proxy.platform.ProxyPlayer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BungeeCord's BAN/UNBAN target notice.
 *
 * <p>{@code ProxyPlatform#notifyKickMute} used to end in an unguarded
 * {@code "// MUTE"} branch, so a BAN reaching it rendered "you have been
 * muted". These tests pin the ban/unban copies (the fall-through regression
 * guard) and that the KICK/MUTE arms are unchanged.
 */
@DisplayName("Bungee BAN/UNBAN target notice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BungeeProxyPlatformNoticeTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatBungee plugin;
    @Mock
    private ProxyServer proxyServer;
    @Mock
    private ProxiedPlayer player;
    @Mock
    private Title title;

    private BungeeProxyPlatform platform;
    private Locale previousLocale;

    @BeforeEach
    void setUp() {
        previousLocale = com.nova.chat.client.i18n.I18n.getDefaultLocale();
        com.nova.chat.client.i18n.I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getProxy()).thenReturn(proxyServer);
        when(player.getUniqueId()).thenReturn(TARGET_ID);
        when(proxyServer.createTitle()).thenReturn(title);
        when(title.title(any(BaseComponent[].class))).thenReturn(title);
        when(title.subTitle(any(BaseComponent[].class))).thenReturn(title);
        when(title.fadeIn(anyInt())).thenReturn(title);
        when(title.stay(anyInt())).thenReturn(title);
        when(title.fadeOut(anyInt())).thenReturn(title);
        platform = new BungeeProxyPlatform(plugin);
    }

    @AfterEach
    void tearDown() {
        com.nova.chat.client.i18n.I18n.setDefaultLocale(previousLocale);
    }

    private void pushNotice(ChannelAction action, String channelId, String duration) {
        platform.notifyKickMute(new BungeeProxyPlayer(player), action, channelId, "Admin", duration);
    }

    private String titleText() {
        ArgumentCaptor<BaseComponent[]> captured = ArgumentCaptor.forClass(BaseComponent[].class);
        verify(title).title(captured.capture());
        return new TextComponent(captured.getValue()).toPlainText();
    }

    private String subtitleText() {
        ArgumentCaptor<BaseComponent[]> captured = ArgumentCaptor.forClass(BaseComponent[].class);
        verify(title).subTitle(captured.capture());
        return new TextComponent(captured.getValue()).toPlainText();
    }

    private String actionBarText() {
        ArgumentCaptor<BaseComponent[]> captured = ArgumentCaptor.forClass(BaseComponent[].class);
        verify(player).sendMessage(eq(ChatMessageType.ACTION_BAR), captured.capture());
        return new TextComponent(captured.getValue()).toPlainText();
    }

    @Nested
    @DisplayName("BAN")
    class Ban {

        @Test
        @DisplayName("renders the ban copy with operator, channel and duration")
        void rendersBanCopy() {
            pushNotice(ChannelAction.BAN, "trade", "1小时");

            assertThat(titleText()).contains("封禁").doesNotContain("禁言");
            assertThat(subtitleText()).contains("Admin", "trade", "1小时");
            assertThat(actionBarText()).contains("Admin", "trade", "1小时");
        }
    }

    @Nested
    @DisplayName("UNBAN")
    class Unban {

        @Test
        @DisplayName("renders the unban copy with operator and channel")
        void rendersUnbanCopy() {
            pushNotice(ChannelAction.UNBAN, "trade", "一段时间");

            assertThat(titleText()).contains("解封");
            assertThat(subtitleText()).contains("Admin", "trade");
            assertThat(actionBarText()).contains("Admin", "trade");
        }
    }

    @Nested
    @DisplayName("existing arms")
    class ExistingArms {

        @Test
        @DisplayName("KICK still renders the kick copy")
        void kickUnchanged() {
            pushNotice(ChannelAction.KICK, "trade", "0");

            assertThat(titleText()).contains("踢出");
            assertThat(subtitleText()).contains("Admin", "trade");
            assertThat(actionBarText()).contains("Admin", "trade");
        }

        @Test
        @DisplayName("MUTE still renders the mute copy with channel and duration")
        void muteUnchanged() {
            pushNotice(ChannelAction.MUTE, "global", "5分钟");

            assertThat(titleText()).contains("禁言");
            assertThat(subtitleText()).contains("global", "5分钟");
            assertThat(actionBarText()).contains("5分钟", "global");
        }
    }

    @Test
    @DisplayName("a player the platform does not own is never rendered to")
    void unknownPlayerIsIgnored() {
        // A ProxyPlayer of another implementation: handle() unwraps to null.
        ProxyPlayer foreign = mock(ProxyPlayer.class);

        platform.notifyKickMute(foreign, ChannelAction.BAN, "trade", "Admin", "1小时");

        verify(title, never()).send(any(ProxiedPlayer.class));
        verify(player, never()).sendMessage(any(ChatMessageType.class), any(BaseComponent[].class));
    }
}
