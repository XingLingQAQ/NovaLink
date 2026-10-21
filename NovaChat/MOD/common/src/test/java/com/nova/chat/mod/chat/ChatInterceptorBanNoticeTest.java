package com.nova.chat.mod.chat;

import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.Platform;
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

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The mod-common BAN/UNBAN target notice.
 *
 * <p>The mod platform has no title or action-bar channel (title/sound are
 * loader-owned), so the notice degrades to the subtitle line over
 * {@link Platform#sendMessage} — the same degradation as KICK/MUTE. The
 * load-bearing assertion is the copy: the pre-existing method ended in an
 * unguarded "// MUTE" branch, so a BAN that reached it would have told the
 * player they were muted.
 */
@DisplayName("Mod BAN/UNBAN target notice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorBanNoticeTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private Platform platform;
    @Mock
    private NetworkClient networkClient;

    private final List<String> sentToTarget = new ArrayList<>();
    private ChatInterceptor interceptor;
    private ChannelResponseDispatcher.ChannelResponseAdapter adapter;
    private Locale previousLocale;

    @BeforeEach
    void setUp() throws Exception {
        previousLocale = com.nova.chat.client.i18n.I18n.getDefaultLocale();
        com.nova.chat.client.i18n.I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        sentToTarget.clear();

        when(networkClient.getChannelResponseTracker())
                .thenReturn(new ChannelResponseTracker());
        when(platform.isPlayerOnline(TARGET_ID)).thenReturn(true);
        doAnswer(inv -> {
            UUID id = inv.getArgument(0);
            if (TARGET_ID.equals(id)) {
                sentToTarget.add(inv.getArgument(1));
            }
            return null;
        }).when(platform).sendMessage(any(), anyString());

        ModConfig config = new ModConfig();
        config.getChat().setReplaceVanilla(false);
        config.getChat().setDefaultChannel("global");

        interceptor = new ChatInterceptor(platform, networkClient, config,
                new MessageFormatter(Map.of(), "{player}: {message}"));
        adapter = instantiateAdapter(interceptor);
    }

    @AfterEach
    void tearDown() {
        com.nova.chat.client.i18n.I18n.setDefaultLocale(previousLocale);
    }

    @Test
    @DisplayName("a BAN renders the ban copy with operator, channel and duration")
    void banRendersBanCopy() {
        adapter.notifyKickMuteTarget(new ChannelResponseDispatcher.KickMuteNotice(
                TARGET_ID, ChannelAction.BAN, "trade", "Admin", "1小时"));

        assertThat(sentToTarget).hasSize(1);
        String line = sentToTarget.get(0);
        assertThat(line).contains("封禁").doesNotContain("禁言");
        assertThat(line).contains("Admin", "trade", "1小时");
    }

    @Test
    @DisplayName("an UNBAN renders the unban copy with operator and channel")
    void unbanRendersUnbanCopy() {
        adapter.notifyKickMuteTarget(new ChannelResponseDispatcher.KickMuteNotice(
                TARGET_ID, ChannelAction.UNBAN, "trade", "Admin", "一段时间"));

        assertThat(sentToTarget).hasSize(1);
        String line = sentToTarget.get(0);
        assertThat(line).contains("解封");
        assertThat(line).contains("Admin", "trade");
    }

    @Test
    @DisplayName("KICK still renders the kick copy")
    void kickUnchanged() {
        adapter.notifyKickMuteTarget(new ChannelResponseDispatcher.KickMuteNotice(
                TARGET_ID, ChannelAction.KICK, "trade", "Admin", "0"));

        assertThat(sentToTarget).hasSize(1);
        assertThat(sentToTarget.get(0)).contains("踢出").contains("Admin", "trade");
    }

    @Test
    @DisplayName("MUTE still renders the mute copy (channel + duration, never the operator)")
    void muteUnchanged() {
        adapter.notifyKickMuteTarget(new ChannelResponseDispatcher.KickMuteNotice(
                TARGET_ID, ChannelAction.MUTE, "global", "Mod", "5分钟"));

        assertThat(sentToTarget).hasSize(1);
        String line = sentToTarget.get(0);
        // The mod degrades to the subtitle line; zh_CN mute_subtitle is
        // "在频道 {0} 持续 {1}" — it names neither the action nor the operator.
        assertThat(line).contains("在频道", "global", "5分钟");
        assertThat(line).doesNotContain("Mod").doesNotContain("封禁");
    }

    @Test
    @DisplayName("the adapter declares exactly KICK, MUTE, BAN and UNBAN")
    void declaresAllFourArms() {
        assertThat(adapter.supportsTargetNotice(ChannelAction.KICK)).isTrue();
        assertThat(adapter.supportsTargetNotice(ChannelAction.MUTE)).isTrue();
        assertThat(adapter.supportsTargetNotice(ChannelAction.BAN)).isTrue();
        assertThat(adapter.supportsTargetNotice(ChannelAction.UNBAN)).isTrue();
        assertThat(adapter.supportsTargetNotice(ChannelAction.JOIN)).isFalse();
    }

    @Test
    @DisplayName("an offline target is not messaged")
    void offlineTargetIsSilent() {
        when(platform.isPlayerOnline(TARGET_ID)).thenReturn(false);

        adapter.notifyKickMuteTarget(new ChannelResponseDispatcher.KickMuteNotice(
                TARGET_ID, ChannelAction.BAN, "trade", "Admin", "1小时"));

        verify(platform, never()).sendMessage(eq(TARGET_ID), anyString());
    }

    /**
     * Reflectively instantiates the private {@code ModChannelResponseAdapter}
     * against the real {@link ChatInterceptor}, so its field reads
     * ({@code platform}, {@code messageFormatter}) resolve to the test doubles.
     */
    private static ChannelResponseDispatcher.ChannelResponseAdapter instantiateAdapter(
            ChatInterceptor outer) throws Exception {
        Class<?> adapterClass = Class.forName(
                "com.nova.chat.mod.chat.ChatInterceptor$ModChannelResponseAdapter");
        Constructor<?> ctor = adapterClass.getDeclaredConstructor(ChatInterceptor.class);
        ctor.setAccessible(true);
        return (ChannelResponseDispatcher.ChannelResponseAdapter) ctor.newInstance(outer);
    }
}
