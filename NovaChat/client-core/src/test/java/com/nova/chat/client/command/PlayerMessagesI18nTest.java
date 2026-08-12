package com.nova.chat.client.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.ChatModeDescriptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locale-aware tests for {@link PlayerMessages}.
 *
 * <p>Asserts that every PlayerMessages method returns the correct text under
 * both {@code zh_CN} (default) and {@code en_US}, covering both the
 * default-locale overloads and the per-player-UUID overloads. Also verifies
 * the validation contracts (null/blank channel, null mode).
 */
@DisplayName("PlayerMessages i18n")
class PlayerMessagesI18nTest {

    private Locale savedDefault;

    @BeforeEach
    void saveDefault() {
        savedDefault = I18n.getDefaultLocale();
    }

    @AfterEach
    void restoreDefault() {
        I18n.setDefaultLocale(savedDefault);
    }

    // ====================== zh_CN (default) ======================

    @Test
    @DisplayName("zh: joining/joined/leaving/left render Chinese text")
    void zhJoinLeave() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        assertThat(PlayerMessages.joining("global")).isEqualTo("正在加入频道 &eglobal&7...");
        assertThat(PlayerMessages.joined("trade")).isEqualTo("已加入频道 &etrade");
        assertThat(PlayerMessages.leaving("staff")).isEqualTo("正在离开频道 &estaff&7...");
        assertThat(PlayerMessages.left("staff", "global"))
                .isEqualTo("已离开频道 &estaff&7，已切换到默认频道: &eglobal");
    }

    @Test
    @DisplayName("zh: currentChannelBar renders Chinese with mode name")
    void zhCurrentChannelBar() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        assertThat(PlayerMessages.currentChannelBar("global", ChatMode.HYBRID))
                .isEqualTo("&7当前频道：&bglobal &7（" + ChatModeDescriptions.HYBRID_MODE_NAME + "）");
        assertThat(PlayerMessages.currentChannelBar("trade", ChatMode.REPLACE))
                .isEqualTo("&7当前频道：&btrade &7（" + ChatModeDescriptions.REPLACE_MODE_NAME + "）");
    }

    @Test
    @DisplayName("zh: chatOn/chatOff are Chinese plain text")
    void zhChatToggle() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        assertThat(PlayerMessages.chatOn()).isEqualTo("聊天已开启");
        assertThat(PlayerMessages.chatOff()).isEqualTo("聊天已关闭");
    }

    // ====================== en_US ======================

    @Test
    @DisplayName("en: joining/joined/leaving/left render English text")
    void enJoinLeave() {
        I18n.setDefaultLocale(LocaleResolver.EN_US);
        assertThat(PlayerMessages.joining("global")).isEqualTo("Joining channel &eglobal&7...");
        assertThat(PlayerMessages.joined("trade")).isEqualTo("Joined channel &etrade");
        assertThat(PlayerMessages.leaving("staff")).isEqualTo("Leaving channel &estaff&7...");
        assertThat(PlayerMessages.left("staff", "global"))
                .isEqualTo("Left channel &estaff&7, switched to default channel: &eglobal");
    }

    @Test
    @DisplayName("en: currentChannelBar renders English with mode name")
    void enCurrentChannelBar() {
        I18n.setDefaultLocale(LocaleResolver.EN_US);
        assertThat(PlayerMessages.currentChannelBar("global", ChatMode.HYBRID))
                .isEqualTo("&7Current channel: &bglobal &7(" + ChatModeDescriptions.modeName(ChatMode.HYBRID) + ")");
        assertThat(PlayerMessages.currentChannelBar("trade", ChatMode.REPLACE))
                .isEqualTo("&7Current channel: &btrade &7(" + ChatModeDescriptions.modeName(ChatMode.REPLACE) + ")");
    }

    @Test
    @DisplayName("en: chatOn/chatOff are English plain text")
    void enChatToggle() {
        I18n.setDefaultLocale(LocaleResolver.EN_US);
        assertThat(PlayerMessages.chatOn()).isEqualTo("Chat enabled");
        assertThat(PlayerMessages.chatOff()).isEqualTo("Chat disabled");
    }

    // ====================== per-player UUID overloads ======================

    @Test
    @DisplayName("per-player: joining(uuid, ch) resolves in player locale")
    void perPlayerJoining() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        UUID player = UUID.randomUUID();

        // No registration → default (zh_CN).
        assertThat(PlayerMessages.joining(player, "global")).isEqualTo("正在加入频道 &eglobal&7...");

        // Register en_US.
        I18n.registerPlayerLocale(player, LocaleResolver.EN_US);
        assertThat(PlayerMessages.joining(player, "global")).isEqualTo("Joining channel &eglobal&7...");
        assertThat(PlayerMessages.joined(player, "trade")).isEqualTo("Joined channel &etrade");
        assertThat(PlayerMessages.leaving(player, "staff")).isEqualTo("Leaving channel &estaff&7...");
        assertThat(PlayerMessages.left(player, "staff", "global"))
                .isEqualTo("Left channel &estaff&7, switched to default channel: &eglobal");

        // Clear → back to default.
        I18n.registerPlayerLocale(player, null);
        assertThat(PlayerMessages.joining(player, "global")).isEqualTo("正在加入频道 &eglobal&7...");
    }

    @Test
    @DisplayName("per-player: currentChannelBar(uuid, ch, mode) resolves in player locale")
    void perPlayerCurrentChannelBar() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        UUID player = UUID.randomUUID();
        I18n.registerPlayerLocale(player, LocaleResolver.EN_US);
        String bar = PlayerMessages.currentChannelBar(player, "global", ChatMode.HYBRID);
        assertThat(bar).contains("Current channel").contains("global");
    }

    @Test
    @DisplayName("per-player: chatOn/chatOff(uuid) resolve in player locale")
    void perPlayerChatToggle() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        UUID player = UUID.randomUUID();
        I18n.registerPlayerLocale(player, LocaleResolver.EN_US);
        assertThat(PlayerMessages.chatOn(player)).isEqualTo("Chat enabled");
        assertThat(PlayerMessages.chatOff(player)).isEqualTo("Chat disabled");
    }

    // ====================== validation (locale-independent) ======================

    @Test
    @DisplayName("null/blank channel throws IllegalArgumentException")
    void channelValidation() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        assertThatThrownBy(() -> PlayerMessages.joining(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> PlayerMessages.joined("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> PlayerMessages.leaving(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> PlayerMessages.left("a", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultChannel");
        assertThatThrownBy(() -> PlayerMessages.currentChannelBar(null, ChatMode.HYBRID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    @DisplayName("null mode throws via modeName")
    void nullModeThrows() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        assertThatThrownBy(() -> PlayerMessages.currentChannelBar("global", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }

    @Test
    @DisplayName("UUID overload also validates channel")
    void uuidOverloadValidation() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        UUID player = UUID.randomUUID();
        assertThatThrownBy(() -> PlayerMessages.joining(player, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> PlayerMessages.left(player, "a", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultChannel");
    }
}
