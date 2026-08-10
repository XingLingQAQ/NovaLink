package com.nova.link.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Independent internationalization service for the NovaLink backend
 * ({@code novalink-core}). Renders console / backend-internal user-facing
 * text in a single configured locale (read from {@code console.locale} in
 * {@code novalink.yml}).
 *
 * <p>This package is deliberately a standalone copy of the client-core i18n
 * pattern — {@code novalink-core} must NOT depend on
 * {@code novachat-client-core}. The backend uses a single process-wide locale
 * (no per-player resolution), so the API is simpler: {@link #tr(String, Object...)}
 * uses the configured locale; {@link #tr(Locale, String, Object...)} overrides
 * it for tests / explicit calls.
 *
 * <p>Bundles: {@code messages_zh_CN.properties} (default / hard fallback) and
 * {@code messages_en_US.properties}, loaded as UTF-8 via
 * {@link Utf8Control}. Color codes are not used in console output; the
 * {@code {0}} placeholders are filled by {@link MessageFormat}.
 *
 * <p>Thread-safe: bundle cache is concurrent; the default locale is volatile.
 */
public final class I18n {

    /** Base name of the .properties bundles on the classpath. */
    static final String BASE_NAME = "messages";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(I18n.class);

    /** Hard fallback locale (zh_CN). */
    private static final Locale FALLBACK_LOCALE = LocaleResolver.ROOT_LOCALE;

    private static final ConcurrentMap<Locale, ResourceBundle> BUNDLES = new ConcurrentHashMap<>();

    private static volatile Locale defaultLocale = FALLBACK_LOCALE;

    private I18n() {
        // Utility class — no instances.
    }

    /**
     * @return the configured backend locale (never null; falls back to zh_CN)
     */
    public static Locale getDefaultLocale() {
        return defaultLocale;
    }

    /**
     * Sets the backend locale from the configured {@code console.locale}.
     * Called once at startup from {@code NovaLinkMain}.
     *
     * @param locale the new backend locale; null falls back to zh_CN
     */
    public static void setDefaultLocale(Locale locale) {
        defaultLocale = (locale != null) ? locale : FALLBACK_LOCALE;
    }

    /**
     * Translates a key in the configured backend locale with interpolation.
     *
     * @param key  the bundle key (e.g. {@code "console.status.header"})
     * @param args the {@code MessageFormat} arguments
     * @return the localized string; on a missing key, the key itself (with a warning logged)
     */
    public static String tr(String key, Object... args) {
        return tr(defaultLocale, key, args);
    }

    /**
     * Translates a key in an explicit locale with interpolation (used by tests
     * that assert output under a specific locale).
     *
     * @param locale the target locale (null → default)
     * @param key    the bundle key
     * @param args   the {@code MessageFormat} arguments
     * @return the localized string; on a missing key, the key itself
     */
    public static String tr(Locale locale, String key, Object... args) {
        if (key == null) {
            return "";
        }
        Locale loc = (locale != null) ? locale : defaultLocale;
        String pattern = resolvePattern(loc, key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        return new MessageFormat(pattern, loc).format(args);
    }

    private static String resolvePattern(Locale locale, String key) {
        ResourceBundle bundle = bundleFor(locale);
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            if (!locale.equals(FALLBACK_LOCALE)) {
                ResourceBundle fallback = bundleFor(FALLBACK_LOCALE);
                try {
                    return fallback.getString(key);
                } catch (MissingResourceException ignored) {
                    // fall through to key echo
                }
            }
            LOG.warn("Missing backend i18n key: {} (locale={})", key, locale);
            return key;
        }
    }

    private static ResourceBundle bundleFor(Locale locale) {
        Locale loc = (locale != null) ? locale : defaultLocale;
        return BUNDLES.computeIfAbsent(loc, I18n::loadBundle);
    }

    private static ResourceBundle loadBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle(BASE_NAME, locale, new Utf8Control());
        } catch (MissingResourceException e) {
            if (!locale.equals(FALLBACK_LOCALE)) {
                return bundleFor(FALLBACK_LOCALE);
            }
            return new EmptyBundle();
        }
    }

    private static final class EmptyBundle extends ResourceBundle {
        @Override
        protected Object handleGetObject(String key) {
            return null;
        }
        @Override
        public java.util.Enumeration<String> getKeys() {
            return java.util.Collections.emptyEnumeration();
        }
    }
}
