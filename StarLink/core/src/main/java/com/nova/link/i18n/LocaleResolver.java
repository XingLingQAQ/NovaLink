package com.nova.link.i18n;

import java.util.Locale;

/**
 * Resolves the backend console locale from the {@code console.locale}
 * config string. Backend copy of the client-core LocaleResolver (kept
 * independent so novalink-core has no client-core dependency).
 *
 * <p>The backend supports two locales: {@code zh_CN} (default / hard fallback)
 * and {@code en_US}.
 */
public final class LocaleResolver {

    /** Hard fallback locale — Chinese (Simplified, China). */
    public static final Locale ROOT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    /** English (US) — the secondary supported locale. */
    public static final Locale EN_US = Locale.US;

    private LocaleResolver() {
        // Utility class — no instances.
    }

    /**
     * Parses a locale string ({@code "zh_CN"}, {@code "en_us"},
     * {@code "en-US"}, …) into a {@link Locale}.
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
}
