package com.nova.chat.client.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-player locale resolution for {@link I18n}.
 *
 * <p>Proves that two players with different registered locales get different
 * translations for the SAME key (the whole point of per-player i18n), that a
 * {@link I18n.PlayerView} is bound to its player, that a late
 * {@link I18n#registerPlayerLocale(UUID, Locale)} (after a first lookup) takes
 * effect on the next call (re-resolution), and that null/cleared registrations
 * fall back to the default locale.
 *
 * <p>This complements {@link I18nTest} (which covers the single-player happy
 * path) by exercising the multi-player isolation + late-registration cases.
 */
@DisplayName("I18n per-player locale resolution")
class I18nPlayerLocaleTest {

    private Locale savedDefault;

    @BeforeEach
    void saveDefault() {
        savedDefault = I18n.getDefaultLocale();
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
    }

    @AfterEach
    void restoreDefault() {
        I18n.setDefaultLocale(savedDefault);
    }

    /**
     * Two players, same key, different registered locales → different values.
     * This is the core contract: per-player locale is isolated per UUID.
     */
    @Test
    @DisplayName("two players with different locales get different values for the same key")
    void twoPlayersDifferentLocales() {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        I18n.registerPlayerLocale(playerA, LocaleResolver.ROOT_LOCALE); // zh_CN
        I18n.registerPlayerLocale(playerB, LocaleResolver.EN_US);        // en_US

        // Same key, different locale per player.
        assertThat(I18n.tr(playerA, "chat.toggle.on")).isEqualTo("聊天已开启");
        assertThat(I18n.tr(playerB, "chat.toggle.on")).isEqualTo("Chat enabled");

        // And the two results are genuinely different (proves they didn't both
        // collapse to the same locale).
        assertThat(I18n.tr(playerA, "chat.toggle.on"))
                .isNotEqualTo(I18n.tr(playerB, "chat.toggle.on"));
    }

    /**
     * PlayerView.forPlayer(playerA) is bound to playerA's locale, so a key
     * resolves in playerA's locale (zh_CN) even when called right after
     * registering playerB with a different locale.
     */
    @Test
    @DisplayName("PlayerView.forPlayer(playerA).tr returns playerA's locale value")
    void playerViewBoundToPlayer() {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        I18n.registerPlayerLocale(playerA, LocaleResolver.ROOT_LOCALE); // zh_CN
        I18n.registerPlayerLocale(playerB, LocaleResolver.EN_US);        // en_US

        I18n.PlayerView viewA = I18n.forPlayer(playerA);
        assertThat(viewA.playerId()).isEqualTo(playerA);
        assertThat(viewA.locale()).isEqualTo(LocaleResolver.ROOT_LOCALE);
        // Chinese value — proves viewA is bound to playerA, not playerB.
        assertThat(viewA.tr("chat.toggle.on")).isEqualTo("聊天已开启");
    }

    /**
     * A late registerPlayerLocale AFTER the first tr(playerId, ...) call takes
     * effect on the NEXT tr — PlayerView re-resolves on every call, so a player
     * who switches client locale mid-session is served the new locale.
     */
    @Test
    @DisplayName("late registerPlayerLocale (after first tr) takes effect on next tr")
    void lateRegistrationTakesEffect() {
        UUID player = UUID.randomUUID();

        // First lookup with no registration → default locale (zh_CN).
        assertThat(I18n.tr(player, "chat.toggle.on")).isEqualTo("聊天已开启");

        // Player switches their client locale to en_US AFTER the first lookup.
        I18n.registerPlayerLocale(player, LocaleResolver.EN_US);

        // Next lookup re-resolves → now English.
        assertThat(I18n.tr(player, "chat.toggle.on")).isEqualTo("Chat enabled");

        // PlayerView created before the switch also picks up the new locale
        // (it re-resolves on every tr, not cached at construction).
        I18n.PlayerView view = I18n.forPlayer(player);
        assertThat(view.locale()).isEqualTo(LocaleResolver.EN_US);
        assertThat(view.tr("chat.toggle.on")).isEqualTo("Chat enabled");
    }

    /**
     * A null playerId resolves to the default locale (zh_CN here).
     */
    @Test
    @DisplayName("null playerId falls back to default locale")
    void nullPlayerIdUsesDefault() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
        assertThat(I18n.tr((UUID) null, "chat.toggle.on")).isEqualTo("聊天已开启");

        I18n.setDefaultLocale(LocaleResolver.EN_US);
        assertThat(I18n.tr((UUID) null, "chat.toggle.on")).isEqualTo("Chat enabled");
    }

    /**
     * Clearing a player's registration (registerPlayerLocale(player, null))
     * makes subsequent lookups fall back to the default locale.
     */
    @Test
    @DisplayName("registerPlayerLocale(player, null) clears registration → falls back to default")
    void clearRegistrationFallsBackToDefault() {
        UUID player = UUID.randomUUID();
        I18n.registerPlayerLocale(player, LocaleResolver.EN_US);
        assertThat(I18n.tr(player, "chat.toggle.on")).isEqualTo("Chat enabled");

        // Clear → falls back to default (zh_CN, set in @BeforeEach).
        I18n.registerPlayerLocale(player, null);
        assertThat(I18n.resolvePlayerLocale(player)).isEqualTo(LocaleResolver.ROOT_LOCALE);
        assertThat(I18n.tr(player, "chat.toggle.on")).isEqualTo("聊天已开启");
    }
}
