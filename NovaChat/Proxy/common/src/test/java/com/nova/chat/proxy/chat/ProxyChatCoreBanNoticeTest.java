package com.nova.chat.proxy.chat;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The proxy's capability gate and notice delivery.
 *
 * <p>{@link ProxyChatCore} renders the target-side notice through the shared
 * dispatcher, which only delivers an action the adapter declares in
 * {@code supportsTargetNotice}. Every proxy platform used to end
 * {@code notifyKickMute} in an unguarded {@code "// MUTE"} branch, so widening
 * the gate without the BAN/UNBAN arms would have told a banned player they were
 * muted. These tests drive a real {@link ProxyChatCore} with an uncorrelated
 * successful response (the console/REST shape) and assert what reached the
 * platform.
 */
@DisplayName("Proxy BAN/UNBAN notice delivery")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProxyChatCoreBanNoticeTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private ProxyConfigView config;
    @Mock
    private AbstractPlatformNetworkClient client;

    private RecordingPlatform platform;
    private ProxyChatCore core;
    private Locale previousLocale;

    @BeforeEach
    void setUp() {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(config.isReplaceVanilla()).thenReturn(false);
        when(config.getDefaultChannel()).thenReturn("global");
        when(client.getChannelResponseTracker()).thenReturn(new ChannelResponseTracker());

        platform = new RecordingPlatform();
        // The notice resolves its recipient through the platform roster; a
        // player missing there means "not on this proxy" and renders nothing.
        platform.addPlayer(TARGET_ID);
        core = new ProxyChatCore(platform, config, new IgnoreListService(), null,
                new KnownChannelRegistry(), () -> null);
        core.rebindIncomingHandlers(client);
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
        core.handleChannelActionResponse(response);
    }

    @Test
    @DisplayName("a BAN response reaches the platform as a BAN, not a MUTE")
    void banIsDeliveredAsBan() {
        pushNotice(ChannelAction.BAN, "staff", "3600");

        assertThat(platform.notices).hasSize(1);
        RecordingPlatform.Notice notice = platform.notices.get(0);
        assertThat(notice.action).isEqualTo(ChannelAction.BAN);
        assertThat(notice.channelId).isEqualTo("staff");
        assertThat(notice.operator).isEqualTo("Mod");
        assertThat(notice.durationText).isNotBlank();
    }

    @Test
    @DisplayName("an UNBAN response reaches the platform as an UNBAN")
    void unbanIsDeliveredAsUnban() {
        pushNotice(ChannelAction.UNBAN, "staff", null);

        assertThat(platform.notices).hasSize(1);
        assertThat(platform.notices.get(0).action).isEqualTo(ChannelAction.UNBAN);
    }

    @Test
    @DisplayName("a global ban names a scope instead of leaving the channel blank")
    void globalBanNamesScope() {
        pushNotice(ChannelAction.BAN, "", "3600");

        assertThat(platform.notices).hasSize(1);
        assertThat(platform.notices.get(0).channelId).isEqualTo("全部频道");
    }

    @Test
    @DisplayName("KICK and MUTE still reach the platform unchanged")
    void existingActionsStillDelivered() {
        pushNotice(ChannelAction.KICK, "staff", "0");
        pushNotice(ChannelAction.MUTE, "staff", "600");

        assertThat(platform.notices).extracting(n -> n.action)
                .containsExactly(ChannelAction.KICK, ChannelAction.MUTE);
        assertThat(platform.notices.get(1).durationText).isNotBlank();
    }

    @Test
    @DisplayName("an unrelated action is still not delivered")
    void joinIsNotDeliveredAsNotice() {
        pushNotice(ChannelAction.JOIN, "global", null);

        assertThat(platform.notices).isEmpty();
    }

    /** Platform double: records each notifyKickMute call instead of rendering. */
    private static final class RecordingPlatform implements ProxyPlatform {

        static final class Notice {
            final ChannelAction action;
            final String channelId;
            final String operator;
            final String durationText;

            Notice(ChannelAction action, String channelId, String operator, String durationText) {
                this.action = action;
                this.channelId = channelId;
                this.operator = operator;
                this.durationText = durationText;
            }
        }

        final List<Notice> notices = new ArrayList<>();
        private final Map<UUID, ProxyPlayer> players = new LinkedHashMap<>();

        void addPlayer(UUID playerId) {
            players.put(playerId, new RecordingPlayer(playerId));
        }

        @Override
        public Collection<? extends ProxyPlayer> getOnlinePlayers() {
            return List.copyOf(players.values());
        }

        @Override
        public ProxyPlayer getPlayer(UUID playerId) {
            return players.get(playerId);
        }

        @Override
        public void sendMessage(ProxyPlayer player, String legacyText) {
        }

        @Override
        public void sendHoverableMessage(ProxyPlayer player, String legacyText, String hoverText) {
        }

        @Override
        public void showTitle(ProxyPlayer player, String title, String subtitle,
                              int fadeIn, int stay, int fadeOut) {
        }

        @Override
        public void playSound(ProxyPlayer player, String soundId, float volume, float pitch) {
        }

        @Override
        public void notifyMention(ProxyPlayer player, String highlight, String subtitle) {
        }

        @Override
        public void sendActionBar(ProxyPlayer player, String legacyText) {
        }

        @Override
        public void showChannelStatusBar(ProxyPlayer player, String channelId, ChatMode mode) {
        }

        @Override
        public void notifyKickMute(ProxyPlayer player, ChannelAction action, String channelId,
                                   String operator, String durationText) {
            notices.add(new Notice(action, channelId, operator, durationText));
        }

        @Override
        public boolean consoleHasPermission(String permission) {
            return true;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            task.run();
        }

        @Override
        public void logInfo(String message) {
        }

        @Override
        public void logWarn(String message) {
        }

        @Override
        public void logDebug(String message) {
        }

        @Override
        public void logError(String message) {
        }

        @Override
        public void logError(String message, Throwable cause) {
        }

        @Override
        public PlatformType getPlatformType() {
            return PlatformType.VELOCITY;
        }

        @Override
        public void reloadConfiguration() {
        }
    }

    /** Minimal player handle the notice path can resolve from the roster. */
    private static final class RecordingPlayer implements ProxyPlayer {

        private final UUID id;

        RecordingPlayer(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getUniqueId() {
            return id;
        }

        @Override
        public String getName() {
            return "Target";
        }

        @Override
        public String getDisplayName() {
            return "Target";
        }

        @Override
        public String getCurrentServer() {
            return "survival-01";
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }
    }
}
