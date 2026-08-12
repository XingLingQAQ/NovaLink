package com.nova.chat.client.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder interpolation behaviour for {@link I18n#tr}.
 *
 * <p>Asserts that {@code {0}} / {@code {1}} placeholders fill in order, that
 * multiple placeholders in one pattern all resolve, and that a missing
 * argument does NOT crash — {@link MessageFormat} leaves an unfilled
 * placeholder handled gracefully (the pattern's literal text for that slot).
 *
 * <p>Also asserts that an argument containing MessageFormat-special characters
 * (apostrophes, which {@link MessageFormat} treats as quote-escapes) is passed
 * through without corrupting the surrounding format — the standard NovaChat
 * pattern uses plain {@code {0}} (no choice/format spec), so an apostrophe in
 * an arg only affects that arg's own slot, not the whole pattern.
 */
@DisplayName("I18n placeholder interpolation")
class I18nPlaceholderInterpolationTest {

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
     * {0} and {1} fill in order: {@code chat.leave.left} =
     * {@code 已离开频道 &e{0}&7，已切换到默认频道: &e{1}}.
     */
    @Test
    @DisplayName("{0} and {1} fill in order")
    void placeholdersFillInOrder() {
        String result = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.leave.left", "staff", "global");
        assertThat(result).contains("已离开频道");
        assertThat(result).contains("staff");
        assertThat(result).contains("global");
        assertThat(result).contains("默认频道");
        // Both placeholders consumed.
        assertThat(result).doesNotContain("{0}");
        assertThat(result).doesNotContain("{1}");
    }

    /**
     * A single placeholder fills correctly.
     */
    @Test
    @DisplayName("single {0} placeholder fills with the arg")
    void singlePlaceholderFills() {
        String result = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.join.joined", "raid");
        assertThat(result).contains("已加入频道");
        assertThat(result).contains("raid");
        assertThat(result).doesNotContain("{0}");
    }

    /**
     * Fewer args than placeholders does NOT crash — the unfilled {1} is left
     * as literal text by {@link MessageFormat} (it renders the placeholder
     * slot as the pattern's text for a missing arg).
     */
    @Test
    @DisplayName("fewer args than placeholders does not crash")
    void fewerArgsThanPlaceholdersDoesNotCrash() {
        // Pattern has {0} and {1}; supply only one arg.
        String result = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.leave.left", "staff");
        // No exception thrown, and the supplied arg is present.
        assertThat(result).contains("staff");
        // No exception → the call returned a non-null string.
        assertThat(result).isNotNull();
    }

    /**
     * Zero args on a multi-placeholder pattern returns the raw pattern with
     * literal {0}/{1} intact (the no-args fast path skips MessageFormat).
     */
    @Test
    @DisplayName("zero args on a placeholder pattern returns raw pattern with literal placeholders")
    void zeroArgsReturnsRawPattern() {
        String raw = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.leave.left");
        assertThat(raw).contains("{0}");
        assertThat(raw).contains("{1}");
        assertThat(raw).contains("已离开频道");
    }

    /**
     * An argument containing an apostrophe (a MessageFormat special char) is
     * handled without corrupting the pattern. {@link MessageFormat} treats
     * apostrophes as quote escapes only inside the PATTERN, not inside arg
     * values — so an apostrophe in the channel name passes through.
     */
    @Test
    @DisplayName("apostrophe in argument does not corrupt the pattern")
    void apostropheInArgDoesNotCorrupt() {
        String result = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.join.joined", "player's_room");
        // The apostrophe-bearing arg is present verbatim.
        assertThat(result).contains("player's_room");
        assertThat(result).contains("已加入频道");
    }
}
