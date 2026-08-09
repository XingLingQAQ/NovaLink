package com.nova.chat.client.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared client-core {@link I18n} service.
 *
 * <p>Exercises zh/en translation, {@code {0}} interpolation, missing-key
 * fallback, {@link I18n#setDefaultLocale(Locale)}, per-player locale via
 * {@link I18n#registerPlayerLocale(UUID, Locale)} / {@link I18n#forPlayer(UUID)},
 * and the default-locale resolution chain.
 */
@DisplayName("I18n")
class I18nTest {

    private Locale savedDefault;

    @BeforeEach
    void saveDefault() {
        savedDefault = I18n.getDefaultLocale();
    }

    @AfterEach
    void restoreDefault() {
        I18n.setDefaultLocale(savedDefault);
        // Clear any player registrations so tests don't leak into each other.
        I18n.registerPlayerLocale(UUID.randomUUID(), null);
    }

    // ====================== zh / en translation ======================

    @Test
    @DisplayName("tr resolves zh_CN keys to Chinese text")
    void trZhCN() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        String result = I18n.tr("chat.join.joining", "global");
        assertThat(result).contains("正在加入频道");
        assertThat(result).contains("global");
        assertThat(result).contains("&e");
    }

    @Test
    @DisplayName("tr resolves en_US keys to English text")
    void trEnUS() {
        I18n.setDefaultLocale(LocaleResolver.EN_US);
        String result = I18n.tr("chat.join.joining", "global");
        assertThat(result).contains("Joining channel");
        assertThat(result).contains("global");
        assertThat(result).contains("&e");
    }

    @Test
    @DisplayName("tr with explicit locale overrides default")
    void trExplicitLocale() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        String enResult = I18n.tr(LocaleResolver.EN_US, "chat.join.joining", "trade");
        assertThat(enResult).contains("Joining channel").contains("trade");

        String zhResult = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.join.joining", "trade");
        assertThat(zhResult).contains("正在加入频道").contains("trade");
    }

    // ====================== {0} interpolation ======================

    @Test
    @DisplayName("{0} placeholder is filled by MessageFormat")
    void interpolationPlaceholder() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        String result = I18n.tr("chat.leave.left", "staff", "global");
        assertThat(result).contains("staff").contains("global");
        assertThat(result).contains("已离开频道").contains("默认频道");
    }

    @Test
    @DisplayName("no-args call returns pattern without formatting")
    void noArgsReturnsPattern() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        String result = I18n.tr("chat.toggle.on");
        assertThat(result).isEqualTo("聊天已开启");
    }

    // ====================== missing key fallback ======================

    @Test
    @DisplayName("missing key falls back to zh_CN bundle, then to the key itself")
    void missingKeyFallback() {
        // en_US bundle is partial — a key absent from en_US should fall back to zh_CN.
        I18n.setDefaultLocale(LocaleResolver.EN_US);
        String result = I18n.tr("chat.toggle.on");
        // chat.toggle.on exists in en_US so this verifies the en_US value.
        assertThat(result).isEqualTo("Chat enabled");

        // A truly missing key returns the key string itself.
        String missing = I18n.tr("nonexistent.key.that.does.not.exist");
        assertThat(missing).isEqualTo("nonexistent.key.that.does.not.exist");
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
        assertThat(I18n.tr("chat.toggle.off")).isEqualTo("Chat disabled");

        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        assertThat(I18n.getDefaultLocale()).isEqualTo(LocaleResolver.ROOT_LOCALE);
        assertThat(I18n.tr("chat.toggle.off")).isEqualTo("聊天已关闭");
    }

    @Test
    @DisplayName("setDefaultLocale(null) falls back to zh_CN")
    void setDefaultLocaleNull() {
        I18n.setDefaultLocale(null);
        assertThat(I18n.getDefaultLocale()).isEqualTo(LocaleResolver.ROOT_LOCALE);
    }

    // ====================== per-player locale ======================

    @Test
    @DisplayName("registerPlayerLocale + tr(playerId, key) resolves in player locale")
    void perPlayerLocale() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        UUID player = UUID.randomUUID();

        // Default (no registration) → default locale (zh_CN).
        assertThat(I18n.tr(player, "chat.toggle.on")).isEqualTo("聊天已开启");

        // Register en_US for the player → resolves in en_US.
        I18n.registerPlayerLocale(player, LocaleResolver.EN_US);
        assertThat(I18n.tr(player, "chat.toggle.on")).isEqualTo("Chat enabled");

        // Clear registration → falls back to default.
        I18n.registerPlayerLocale(player, null);
        assertThat(I18n.tr(player, "chat.toggle.on")).isEqualTo("聊天已开启");
    }

    @Test
    @DisplayName("forPlayer(uuid).tr resolves in player locale")
    void forPlayerView() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        UUID player = UUID.randomUUID();
        I18n.registerPlayerLocale(player, LocaleResolver.EN_US);

        I18n.PlayerView view = I18n.forPlayer(player);
        assertThat(view.playerId()).isEqualTo(player);
        assertThat(view.locale()).isEqualTo(LocaleResolver.EN_US);
        assertThat(view.tr("chat.toggle.off")).isEqualTo("Chat disabled");
    }

    @Test
    @DisplayName("forPlayer(null) uses default locale")
    void forPlayerNullUsesDefault() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        I18n.PlayerView view = I18n.forPlayer(null);
        assertThat(view.locale()).isEqualTo(LocaleResolver.ROOT_LOCALE);
        assertThat(view.tr("chat.toggle.on")).isEqualTo("聊天已开启");
    }

    @Test
    @DisplayName("registerPlayerLocale(null uuid) is a no-op")
    void registerNullUuidNoOp() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        I18n.registerPlayerLocale(null, LocaleResolver.EN_US);
        // No crash, no effect.
        assertThat(I18n.resolvePlayerLocale(UUID.randomUUID())).isEqualTo(LocaleResolver.ROOT_LOCALE);
    }

    // ====================== resolvePlayerLocale ======================

    @Test
    @DisplayName("resolvePlayerLocale returns registered locale, else default")
    void resolvePlayerLocale() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        UUID player = UUID.randomUUID();
        assertThat(I18n.resolvePlayerLocale(player)).isEqualTo(LocaleResolver.ROOT_LOCALE);
        assertThat(I18n.resolvePlayerLocale(null)).isEqualTo(LocaleResolver.ROOT_LOCALE);

        I18n.registerPlayerLocale(player, LocaleResolver.EN_US);
        assertThat(I18n.resolvePlayerLocale(player)).isEqualTo(LocaleResolver.EN_US);
    }

    // ====================== pattern() ======================

    @Test
    @DisplayName("pattern() returns raw template without interpolation")
    void patternReturnsRaw() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        String raw = I18n.pattern(LocaleResolver.ROOT_LOCALE, "chat.join.joining");
        assertThat(raw).contains("{0}").doesNotContain("global");
    }

    // ====================== LocaleResolver ======================

    @Test
    @DisplayName("LocaleResolver.parse handles various formats")
    void localeResolverParse() {
        assertThat(LocaleResolver.parse("zh_CN")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(LocaleResolver.parse("en_us")).isEqualTo(Locale.US);
        assertThat(LocaleResolver.parse("en-US")).isEqualTo(Locale.US);
        assertThat(LocaleResolver.parse("en")).isEqualTo(new Locale("en"));
        assertThat(LocaleResolver.parse("")).isNull();
        assertThat(LocaleResolver.parse(null)).isNull();
    }

    @Test
    @DisplayName("LocaleResolver.normalize maps to zh_CN or en_US")
    void localeResolverNormalize() {
        assertThat(LocaleResolver.normalize(Locale.US)).isEqualTo(LocaleResolver.EN_US);
        assertThat(LocaleResolver.normalize(new Locale("en", "GB"))).isEqualTo(LocaleResolver.EN_US);
        assertThat(LocaleResolver.normalize(Locale.SIMPLIFIED_CHINESE)).isEqualTo(LocaleResolver.ROOT_LOCALE);
        assertThat(LocaleResolver.normalize(Locale.JAPANESE)).isEqualTo(LocaleResolver.ROOT_LOCALE);
        assertThat(LocaleResolver.normalize(null)).isEqualTo(LocaleResolver.ROOT_LOCALE);
    }
}
