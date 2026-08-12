package com.nova.link.i18n;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
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
 * <p>Bundles: {@code lang/zh_CN.properties} (default / hard fallback) and
 * {@code lang/en_US.properties} on the classpath (base name
 * {@code lang.messages}), loaded as UTF-8 via {@link Utf8Control}. Color codes
 * are not used in console output; the {@code {0}} placeholders are filled by
 * {@link MessageFormat}.
 *
 * <h2>Adding a new language</h2>
 * <ul>
 *   <li><b>Classpath (built-in):</b> add {@code lang/<locale>.properties} to
 *       {@code StarLink/core/src/main/resources/lang/}.</li>
 *   <li><b>External (drop-in, no rebuild):</b> create
 *       {@code <externalLangDir>/lang/<locale>.properties} where
 *       {@code externalLangDir} defaults to the {@code novalink.yml} working
 *       directory (set via {@link #setExternalLangDir(File)} at startup).
 *       External entries override classpath entries per-key; a locale present
 *       only externally is loaded entirely from the external file.</li>
 * </ul>
 *
 * <p>Thread-safe: bundle cache is concurrent; the default locale is volatile.
 */
public final class I18n {

    /**
     * Base name of the .properties bundles on the classpath. The dotted form
     * {@code "lang.messages"} resolves to classpath path
     * {@code lang/messages_<locale>.properties} (the {@code .} is treated as a
     * path separator), so all translation files live under one {@code lang/}
     * package.
     */
    static final String BASE_NAME = "lang.messages";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(I18n.class);

    /** Hard fallback locale (zh_CN). */
    private static final Locale FALLBACK_LOCALE = LocaleResolver.ROOT_LOCALE;

    private static final ConcurrentMap<Locale, ResourceBundle> BUNDLES = new ConcurrentHashMap<>();

    private static volatile Locale defaultLocale = FALLBACK_LOCALE;

    /**
     * External lang override directory (may be null). When set, files under
     * {@code <dir>/lang/<locale>.properties} are merged on top of the classpath
     * bundles (external entries win per-key) and brand-new locales can be
     * loaded from external files alone. Volatile so the bundle loader sees the
     * latest directory across threads.
     *
     * <p>Default at runtime: the backend's working directory (the
     * {@code novalink.yml} folder), set from {@code NovaLinkMain} at startup.
     * Layout: {@code <workdir>/lang/<locale>.properties}.
     */
    private static volatile File externalLangDir;

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
     * Registers an external lang override directory. When set, translation
     * files under {@code <dir>/lang/<locale>.properties} are merged on top of
     * the classpath bundles at load time — external entries win per-key for an
     * existing locale, and a locale present only externally is loaded entirely
     * from the external file (so a brand-new language works via one drop-in
     * file). Pass {@code null} to clear.
     *
     * <p>Default backend dir: {@code <workdir>/lang/} where {@code workdir} is
     * the {@code novalink.yml} folder.
     *
     * @param dir the external lang directory, or null to clear
     */
    public static void setExternalLangDir(File dir) {
        externalLangDir = dir;
    }

    /**
     * Convenience overload accepting a {@link Path}.
     *
     * @param dir the external lang directory, or null to clear
     */
    public static void setExternalLangDir(Path dir) {
        externalLangDir = (dir != null) ? dir.toFile() : null;
    }

    /**
     * @return the registered external lang override dir, or null if none set
     */
    public static File getExternalLangDir() {
        return externalLangDir;
    }

    /**
     * Clears the bundle cache so subsequent {@link #tr} calls reload from the
     * current classpath + external dir. Intended for hot-reload flows.
     */
    public static void invalidate() {
        BUNDLES.clear();
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
        // 1. Classpath bundle (lang/messages_<locale>.properties via Utf8Control).
        ResourceBundle classpathBundle = null;
        try {
            classpathBundle = ResourceBundle.getBundle(BASE_NAME, locale, new Utf8Control());
        } catch (MissingResourceException e) {
            // No classpath bundle for this locale — external-only may still work.
        }

        // 2. External override file: <externalLangDir>/lang/<locale>.properties.
        ResourceBundle externalBundle = loadExternalBundle(locale);

        if (externalBundle != null) {
            // External wins per-key: merge external on top of classpath (if any).
            // When there is no classpath bundle, the external file alone forms the
            // bundle — a brand-new language works via a single drop-in file.
            return new MergedBundle(
                    classpathBundle != null ? classpathBundle : new EmptyBundle(),
                    externalBundle);
        }

        if (classpathBundle != null) {
            return classpathBundle;
        }

        // No bundle at all for this locale — fall back to zh_CN, then EmptyBundle.
        if (!locale.equals(FALLBACK_LOCALE)) {
            return bundleFor(FALLBACK_LOCALE);
        }
        return new EmptyBundle();
    }

    /**
     * Loads an external override bundle for the locale from
     * {@code <externalLangDir>/lang/<locale>.properties} (UTF-8). Returns null
     * when the external dir is unset, does not exist, or has no file for the
     * locale.
     */
    private static ResourceBundle loadExternalBundle(Locale locale) {
        File dir = externalLangDir;
        if (dir == null) {
            return null;
        }
        String fileName = "lang/" + locale.toString() + ".properties";
        File file = new File(dir, fileName);
        if (!file.isFile()) {
            return null;
        }
        try (InputStream in = java.nio.file.Files.newInputStream(file.toPath());
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new PropertyResourceBundle(reader);
        } catch (IOException e) {
            LOG.warn("Failed to load external lang file {}: {}", file, e.getMessage());
            return null;
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

    /**
     * Bundle that layers an external override bundle on top of a base bundle
     * (the classpath bundle). External entries win per-key; keys absent from
     * the external file delegate to the base. This is what makes user
     * customizations override built-in strings without forking the bundle.
     */
    private static final class MergedBundle extends ResourceBundle {
        private final ResourceBundle base;
        private final ResourceBundle overrides;

        MergedBundle(ResourceBundle base, ResourceBundle overrides) {
            this.base = base;
            this.overrides = overrides;
        }

        @Override
        protected Object handleGetObject(String key) {
            // External overrides win per-key. ResourceBundle.handleGetObject is
            // protected, so we cannot call it on another bundle instance; use the
            // public getString-with-fallback path instead (it throws on a miss,
            // which we treat as "key not in that bundle").
            if (overrides != null && overrides.containsKey(key)) {
                try {
                    return overrides.getString(key);
                } catch (MissingResourceException ignored) {
                    // fall through to base.
                }
            }
            if (base.containsKey(key)) {
                try {
                    return base.getString(key);
                } catch (MissingResourceException ignored) {
                    // fall through to null (resolvePattern will handle the miss)
                }
            }
            return null;
        }

        @Override
        public Enumeration<String> getKeys() {
            // Union of both bundles' keys; base alone is insufficient because
            // external-only keys must also be enumerable.
            java.util.Set<String> keys = new java.util.HashSet<>();
            if (overrides != null) {
                keys.addAll(Collections.list(overrides.getKeys()));
            }
            keys.addAll(Collections.list(base.getKeys()));
            return Collections.enumeration(keys);
        }
    }
}
