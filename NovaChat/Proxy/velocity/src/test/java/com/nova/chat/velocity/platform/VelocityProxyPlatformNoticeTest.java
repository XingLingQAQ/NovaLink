package com.nova.chat.velocity.platform;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.proxy.platform.ProxyPlayer;
import com.nova.chat.velocity.NovaChatVelocity;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Velocity's BAN/UNBAN target notice.
 *
 * <p>{@code ProxyPlatform#notifyKickMute} used to end in an unguarded
 * {@code "// MUTE"} branch, so a BAN reaching it rendered "you have been
 * muted". These tests pin the ban/unban copies (the fall-through regression
 * guard) and that the KICK/MUTE arms are unchanged.
 */
@DisplayName("Velocity BAN/UNBAN target notice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VelocityProxyPlatformNoticeTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatVelocity plugin;
    @Mock
    private ProxyServer proxyServer;
    @Mock
    private Player player;

    private VelocityProxyPlatform platform;
    private Locale previousLocale;

    @BeforeEach
    void setUp() {
        previousLocale = com.nova.chat.client.i18n.I18n.getDefaultLocale();
        com.nova.chat.client.i18n.I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getServer()).thenReturn(proxyServer);
        when(player.getUniqueId()).thenReturn(TARGET_ID);
        platform = new VelocityProxyPlatform(plugin);
    }

    @AfterEach
    void tearDown() {
        com.nova.chat.client.i18n.I18n.setDefaultLocale(previousLocale);
    }

    private void pushNotice(ChannelAction action, String channelId, String duration) {
        platform.notifyKickMute(new VelocityProxyPlayer(player), action, channelId, "Admin", duration);
    }

    private String titleText() {
        ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(title.capture());
        return PlainTextComponentSerializer.plainText().serialize(title.getValue().title());
    }

    private String subtitleText() {
        ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(title.capture());
        return PlainTextComponentSerializer.plainText().serialize(title.getValue().subtitle());
    }

    private String actionBarText() {
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(player).sendActionBar(bar.capture());
        return PlainTextComponentSerializer.plainText().serialize(bar.getValue());
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

        verify(player, never()).showTitle(any(Title.class));
        verify(player, never()).sendActionBar(any(Component.class));
    }
}
