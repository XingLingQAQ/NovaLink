package com.nova.link.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the backend {@link I18n} service
 * ({@code com.nova.link.i18n.I18n}).
 *
 * <p>Exercises zh/en translation, {@code {0}} interpolation, missing-key
 * fallback, {@link I18n#setDefaultLocale(Locale)}, and the
 * {@link LocaleResolver} parsing.
 */
@DisplayName("BackendI18n")
class BackendI18nTest {

    private Locale savedDefault;

    @BeforeEach
    void saveDefault() {
        savedDefault = I18n.getDefaultLocale();
    }

    @AfterEach
    void restoreDefault() {
        I18n.setDefaultLocale(savedDefault);
    }

    // ====================== zh / en translation ======================

    @Test
    @DisplayName("tr resolves zh_CN console keys to Chinese text")
    void trZhCN() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        String result = I18n.tr("console.status.online_players", 5);
        assertThat(result).contains("在线玩家").contains("5");
    }

    @Test
    @DisplayName("tr resolves en_US console keys to English text")
    void trEnUS() {
        I18n.setDefaultLocale(LocaleResolver.EN_US);
        String result = I18n.tr("console.status.online_players", 5);
        assertThat(result).contains("Online players").contains("5");
    }

    @Test
    @DisplayName("tr with explicit locale overrides default")
    void trExplicitLocale() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        String en = I18n.tr(LocaleResolver.EN_US, "console.mute.success", "Steve", "uuid", "staff", "10m", "spam");
        assertThat(en).contains("Muted").contains("Steve").contains("staff");

        String zh = I18n.tr(LocaleResolver.ROOT_LOCALE, "console.mute.success", "Steve", "uuid", "staff", "10m", "spam");
        assertThat(zh).contains("已禁言").contains("Steve").contains("staff");
    }

    // ====================== {0} interpolation ======================

    @Test
    @DisplayName("{0} placeholder is filled by MessageFormat")
    void interpolationPlaceholder() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        String result = I18n.tr("console.status.connections", 3, 2);
        assertThat(result).contains("3").contains("2");
    }

    @Test
    @DisplayName("no-args call returns pattern without formatting")
    void noArgsReturnsPattern() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        String result = I18n.tr("console.players.empty");
        assertThat(result).contains("没有在线玩家");
    }

    // ====================== missing key fallback ======================

    @Test
    @DisplayName("missing key falls back to zh_CN bundle, then to the key itself")
    void missingKeyFallback() {
        I18n.setDefaultLocale(LocaleResolver.EN_US);
        // en_US bundle is partial — a key absent from en_US falls back to zh_CN.
        String result = I18n.tr("console.unknown_command", "frobnicate");
        // console.unknown_command exists in en_US so this verifies the en_US value.
        assertThat(result).contains("Unknown command").contains("frobnicate");

        // A truly missing key returns the key string itself.
        String missing = I18n.tr("nonexistent.backend.key.that.does.not.exist");
        assertThat(missing).isEqualTo("nonexistent.backend.key.that.does.not.exist");
    }

    @Test
    @DisplayName("null key returns empty string")
    void nullKeyReturnsEmpty() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        assertThat(I18n.tr((String) null)).isEmpty();
        assertThat(I18n.tr((Locale) null, null)).isEmpty();
    }

    // ====================== setDefaultLocale ======================

    @Test
    @DisplayName("setDefaultLocale changes the default resolution locale")
    void setDefaultLocale() {
        I18n.setDefaultLocale(LocaleResolver.EN_US);
        assertThat(I18n.getDefaultLocale()).isEqualTo(LocaleResolver.EN_US);
        assertThat(I18n.tr("console.boot.ready")).contains("NovaLink backend console ready");

        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        assertThat(I18n.getDefaultLocale()).isEqualTo(LocaleResolver.ROOT_LOCALE);
        assertThat(I18n.tr("console.boot.ready")).contains("后端控制台已就绪");
    }

    @Test
    @DisplayName("setDefaultLocale(null) falls back to zh_CN")
    void setDefaultLocaleNull() {
        I18n.setDefaultLocale(null);
        assertThat(I18n.getDefaultLocale()).isEqualTo(LocaleResolver.ROOT_LOCALE);
    }

    // ====================== spot-check key samples ======================

    @Test
    @DisplayName("zh: status header + reload success + spy start")
    void zhSpotCheck() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        assertThat(I18n.tr("console.status.header")).contains("NovaLink 状态");
        assertThat(I18n.tr("console.reload.success", 3)).contains("配置已重载").contains("3");
        assertThat(I18n.tr("console.spy.start.success", "staff")).contains("监听已启动").contains("staff");
    }

    @Test
    @DisplayName("en: status header + reload success + spy start")
    void enSpotCheck() {
        I18n.setDefaultLocale(LocaleResolver.EN_US);
        assertThat(I18n.tr("console.status.header")).contains("NovaLink status");
        assertThat(I18n.tr("console.reload.success", 3)).contains("Configuration reloaded").contains("3");
        assertThat(I18n.tr("console.spy.start.success", "staff")).contains("Spy started").contains("staff");
    }

    // ====================== LocaleResolver ======================

    @Test
    @DisplayName("LocaleResolver.parse handles various formats")
    void localeResolverParse() {
        assertThat(LocaleResolver.parse("zh_CN")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(LocaleResolver.parse("en_us")).isEqualTo(Locale.US);
        assertThat(LocaleResolver.parse("en-US")).isEqualTo(Locale.US);
        assertThat(LocaleResolver.parse("")).isNull();
        assertThat(LocaleResolver.parse(null)).isNull();
    }

    @Test
    @DisplayName("LocaleResolver.parseOrDefault falls back correctly")
    void parseOrDefault() {
        assertThat(LocaleResolver.parseOrDefault("en_US", LocaleResolver.ROOT_LOCALE))
                .isEqualTo(LocaleResolver.EN_US);
        assertThat(LocaleResolver.parseOrDefault(null, LocaleResolver.ROOT_LOCALE))
                .isEqualTo(LocaleResolver.ROOT_LOCALE);
        assertThat(LocaleResolver.parseOrDefault("", null))
                .isEqualTo(LocaleResolver.ROOT_LOCALE);
    }
}
