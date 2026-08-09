package com.nova.chat.client.i18n;

import java.util.Locale;

/**
 * Resolves a Minecraft client locale string (e.g. {@code "zh_CN"},
 * {@code "en_us"}) to a {@link Locale}, with the NovaChat fallback chain.
 *
 * <p>Minecraft clients report locales in mixed-case with underscores
 * ({@code zh_CN}, {@code en_us}); some platforms normalize differently.
 * This helper centralizes the parsing so every platform plugin maps the
 * raw string the same way.
 *
 * <p>Resolution order (see {@link I18n}):
 * <ol>
 *   <li>Player's registered client locale (if non-null and parseable).</li>
 *   <li>Configured default locale.</li>
 *   <li>{@link #ROOT_LOCALE} ({@code zh_CN}) — the hard fallback.</li>
 * </ol>
 */
public final class LocaleResolver {

    /** Hard fallback locale — Chinese (Simplified, China). Never changes. */
    public static final Locale ROOT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    /** English (US) — the secondary supported locale. */
    public static final Locale EN_US = Locale.US;

    private LocaleResolver() {
        // Utility class — no instances.
    }

    /**
     * Parses a Minecraft client locale string into a {@link Locale}.
     *
     * <p>Accepts {@code "zh_CN"}, {@code "zh_cn"}, {@code "en_us"},
     * {@code "en-US"}, {@code "en"}, etc. Returns {@code null} when the
     * input is blank or cannot be parsed (so the caller falls back to the
     * default locale).
     *
     * @param raw the raw locale string (may be null/blank)
     * @return the parsed locale, or {@code null} on blank/unparseable input
     */
    public static Locale parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().replace('-', '_');
        int underscore = s.indexOf('_');
        if (underscore < 0) {
            // language only — let Locale canonicalize country/country-less.
            String lang = s.toLowerCase(java.util.Locale.ROOT);
            if (lang.isEmpty()) {
                return null;
            }
            return new Locale(lang);
        }
        String language = s.substring(0, underscore).toLowerCase(java.util.Locale.ROOT);
        String country = s.substring(underscore + 1).toUpperCase(java.util.Locale.ROOT);
        if (language.isEmpty()) {
            return null;
        }
        return new Locale(language, country);
    }

    /**
     * Parses a locale string and falls back to {@code fallback} when the
     * input is blank/unparseable.
     *
     * @param raw      the raw locale string
     * @param fallback the locale to use on blank/unparseable input (null → ROOT_LOCALE)
     * @return the parsed locale, or the fallback
     */
    public static Locale parseOrDefault(String raw, Locale fallback) {
        Locale parsed = parse(raw);
        return parsed != null ? parsed : (fallback != null ? fallback : ROOT_LOCALE);
    }

    /**
     * Normalizes a locale to the two NovaChat-supported locales:
     * {@code zh_CN} or {@code en_US}. Any non-English locale collapses to
     * {@code zh_CN} (the default). English variants (en, en_US, en_GB, …)
     * map to {@code en_US}.
     *
     * <p>This is intentionally simple — NovaChat ships only two bundles.
     * The fallback chain inside {@link I18n} still handles any locale by
     * degrading to the closest bundle, so this normalization is a hint, not
     * a hard gate.
     *
     * @param locale the locale (may be null → ROOT_LOCALE)
     * @return {@code zh_CN} or {@code en_US}
     */
    public static Locale normalize(Locale locale) {
        if (locale == null) {
            return ROOT_LOCALE;
        }
        String lang = locale.getLanguage();
        if ("en".equalsIgnoreCase(lang)) {
            return EN_US;
        }
        return ROOT_LOCALE;
    }
}
