package com.nova.chat.client.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Color-code preservation through {@link I18n#tr}.
 *
 * <p>NovaChat embeds Minecraft color codes ({@code &e}, {@code §c}, …) directly
 * inside property values (they are NOT MessageFormat meta-characters), so
 * {@code tr()} must pass them through unchanged. {@link MessageFormat} only
 * treats curly-brace placeholders ({@code {0}}) specially — ampersand and
 * section-sign sequences are ordinary text.
 *
 * <p>This is a regression guard: if a future change ever sanitizes or strips
 * color codes during formatting, these assertions fail.
 */
@DisplayName("I18n color-code preservation")
class I18nColorCodePreservationTest {

    private Locale savedDefault;

    @BeforeEach
    void saveDefault() {
        savedDefault = I18n.getDefaultLocale();
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        I18n.setExternalLangDir((java.io.File) null);
        I18n.invalidate();
    }

    @AfterEach
    void restoreDefault() {
        I18n.setDefaultLocale(savedDefault);
        I18n.setExternalLangDir((java.io.File) null);
        I18n.invalidate();
    }

    /**
     * Ampersand color codes ({@code &e}, {@code &7}) inside a zh_CN value
     * survive tr() unchanged, alongside the interpolated channel name.
     * Uses {@code chat.join.joining = 正在加入频道 &e{0}&7...}.
     */
    @Test
    @DisplayName("ampersand color codes (&e, &7) survive tr() in zh_CN with interpolation")
    void ampersandCodesSurviveZhCN() {
        String result = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.join.joining", "global");
        assertThat(result).contains("正在加入频道");
        assertThat(result).contains("global");
        // Both color codes present and unchanged.
        assertThat(result).contains("&e");
        assertThat(result).contains("&7");
        // The {0} placeholder was filled (no literal {0} left).
        assertThat(result).doesNotContain("{0}");
    }

    /**
     * Ampersand color codes survive in en_US too (same key, English text).
     */
    @Test
    @DisplayName("ampersand color codes survive tr() in en_US")
    void ampersandCodesSurviveEnUS() {
        String result = I18n.tr(LocaleResolver.EN_US, "chat.join.joining", "trade");
        assertThat(result).contains("Joining channel");
        assertThat(result).contains("trade");
        assertThat(result).contains("&e");
        assertThat(result).contains("&7");
    }

    /**
     * Section-sign color codes ({@code §e}, {@code §7}) also survive — the
     * {@code chat.world.auto_switch} value uses {@code §e}/{@code §7}/{@code §a}.
     */
    @Test
    @DisplayName("section-sign color codes (§e, §7, §a) survive tr()")
    void sectionSignCodesSurvive() {
        String result = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.world.auto_switch", "生存", "创造");
        assertThat(result).contains("§e");
        assertThat(result).contains("§7");
        assertThat(result).contains("§a");
        assertThat(result).contains("生存");
        assertThat(result).contains("创造");
    }

    /**
     * A no-arg tr() on a value with color codes returns the pattern verbatim
     * (color codes + a literal {0} placeholder, since no args were supplied).
     */
    @Test
    @DisplayName("no-arg tr() preserves color codes and leaves {0} literal")
    void noArgPreservesColorCodes() {
        String raw = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.join.joined");
        // chat.join.joined = 已加入频道 &e{0}  — no args, so {0} stays literal.
        assertThat(raw).contains("已加入频道");
        assertThat(raw).contains("&e");
        assertThat(raw).contains("{0}");
    }
}
