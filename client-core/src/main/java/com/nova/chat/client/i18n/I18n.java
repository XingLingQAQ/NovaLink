package com.nova.chat.client.i18n;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Shared internationalization service for the NovaChat client layer
 * ({@code novachat-client-core}). Used by every platform plugin to render
 * player-facing text in the player's own locale.
 *
 * <p>Locale resolution order (per player):
 * <ol>
 *   <li>The locale registered for the player via
 *       {@link #registerPlayerLocale(UUID, Locale)} (captured from the
 *       Minecraft client locale packet / Bedrock login chain by each
 *       platform plugin).</li>
 *   <li>The configured default locale (set once at startup via
 *       {@link #setDefaultLocale(Locale)}).</li>
 *   <li>{@link LocaleResolver#ROOT_LOCALE} ({@code zh_CN}) — the hard
 *       fallback baked into the bundle chain.</li>
 * </ol>
 *
 * <p>For console / sender-agnostic calls (no player id), the configured
 * default locale is used directly.
 *
 * <p>Bundles are loaded from {@code lang/<lang>_<country>.properties} on the
 * classpath via a UTF-8 {@link ResourceBundle.Control} (see
 * {@link Utf8Control}). The dotted base name {@code "lang.messages"} resolves to
 * the classpath path {@code lang/messages_<locale>.properties} (Java treats
 * {@code .} as {@code /} in bundle names), so all translation files live under
 * one {@code lang/} package — adding a new language is just dropping one file.
 * Java 9+ already loads {@code PropertyResourceBundle} from an
 * {@code InputStreamReader} as UTF-8, but the explicit control guarantees
 * correct behavior regardless of the default charset and keeps the bundle
 * cache under our control.
 *
 * <h2>Adding a new language</h2>
 * <ul>
 *   <li><b>Classpath (built-in):</b> add
 *       {@code lang/<locale>.properties} (e.g. {@code lang/fr_FR.properties})
 *       to {@code client-core/src/main/resources/lang/}. It ships inside the
 *       jar.</li>
 *   <li><b>External (drop-in, no rebuild):</b> create
 *       {@code <externalLangDir>/lang/<locale>.properties}
 *       (e.g. {@code plugins/NovaChat/lang/fr_FR.properties}) where
 *       {@code externalLangDir} is the directory registered at startup via
 *       {@link #setExternalLangDir(File)}. The file is read at load time; no
 *       restart beyond the first lookup is needed.</li>
 * </ul>
 * <p><b>Override semantics:</b> when an external file exists for a locale, its
 * entries are merged on top of the classpath bundle for that locale — external
 * values win per-key, so a user can override individual strings without
 * forking the whole bundle. When a locale exists ONLY externally (no classpath
 * bundle), it is loaded entirely from the external file — a brand-new language
 * works via a single external file.
 *
 * <p>Color codes ({@code &e}, {@code §c}, …) stay <em>inside</em> the
 * property values; i18n swaps only natural-language text.
 * {@link MessageFormat} {@code {0}} placeholders are used for dynamic
 * values, so callers pass {@code I18n.tr("chat.join.joining", channel)}.
 *
 * <p>Thread-safe: bundle cache and player-locale map are concurrent. The
 * default locale is volatile.
 *
 * <p>Architecture B: plugin-only. The backend ({@code novalink-core}) ships
 * its own independent copy that never depends on this class.
 */
public final class I18n {

    /**
     * Base name of the .properties bundles on the classpath. The dotted form
     * {@code "lang.messages"} makes {@link ResourceBundle#getBundle} resolve to
     * classpath path {@code lang/messages_<locale>.properties} (the {@code .}
     * is treated as a path separator).
     */
    static final String BASE_NAME = "lang.messages";

    private static final Logger LOG = new Logger();

    /** Hard fallback locale (zh_CN) — always loaded so missing keys degrade to Chinese. */
    private static final Locale FALLBACK_LOCALE = LocaleResolver.ROOT_LOCALE;

    /** Cache of (locale -> ResourceBundle); the fallback bundle is always present. */
    private static final ConcurrentMap<Locale, ResourceBundle> BUNDLES = new ConcurrentHashMap<>();

    /** Per-player locale registrations (UUID -> locale), populated by platform plugins. */
    private static final ConcurrentMap<UUID, Locale> PLAYER_LOCALES = new ConcurrentHashMap<>();

    /** Configured default locale; volatile so reads from any thread see the latest set. */
    private static volatile Locale defaultLocale = FALLBACK_LOCALE;

    /**
     * External lang override directory (may be null). When set, files under
     * {@code <dir>/lang/<locale>.properties} are merged on top of the classpath
     * bundles (external entries win per-key) and brand-new locales can be
     * loaded from external files alone. Volatile so the bundle loader sees the
     * latest directory across threads.
     *
     * <p>Layout: the external dir is the plugin's data folder (e.g.
     * {@code plugins/NovaChat/}); translation files go in a {@code lang/}
     * subdirectory inside it, mirroring the classpath {@code lang/} package:
     * {@code <externalLangDir>/lang/<locale>.properties}.
     */
    private static volatile File externalLangDir;

    private I18n() {
        // Utility class — no instances.
    }

    // ============================ default locale ============================

    /**
     * @return the configured default locale (never null; falls back to zh_CN)
     */
    public static Locale getDefaultLocale() {
        return defaultLocale;
    }

    /**
     * Sets the default locale used when no player-specific locale is
     * registered. Called once at plugin startup from the configured
     * {@code chat.locale}.
     *
     * @param locale the new default locale; null falls back to zh_CN
     */
    public static void setDefaultLocale(Locale locale) {
        defaultLocale = (locale != null) ? locale : FALLBACK_LOCALE;
    }

    // ============================ external lang dir ============================

    /**
     * Registers an external lang override directory. When set, translation
     * files under {@code <dir>/lang/<locale>.properties} are merged on top of
     * the classpath bundles at load time — external entries win per-key for an
     * existing locale, and a locale present only externally is loaded entirely
     * from the external file (so a brand-new language works via one drop-in
     * file).
     *
     * <p>Pass {@code null} to clear the override (reverts to classpath-only).
     * The directory does NOT need to exist; a non-existent or empty dir is
     * treated as "no external overrides". Changing the dir does NOT clear the
     * bundle cache — call {@link #invalidate()} if previously-loaded bundles
     * should be discarded (e.g. a reload).
     *
     * @param dir the external lang directory (plugin data folder), or null to clear
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

    // ============================ player locales ============================

    /**
     * Registers a player's client locale so subsequent
     * {@link #forPlayer(UUID)} / {@link #tr(UUID, String, Object...)} calls
     * resolve in that locale. Called by each platform plugin when it receives
     * the player-locale event (Bukkit {@code PlayerLocaleChangeEvent},
     * Velocity {@code PlayerLocaleChangeEvent}, Bedrock login chain, …).
     *
     * @param playerId the player's UUID (not null)
     * @param locale   the player's locale; null clears the registration
     */
    public static void registerPlayerLocale(UUID playerId, Locale locale) {
        if (playerId == null) {
            return;
        }
        if (locale == null) {
            PLAYER_LOCALES.remove(playerId);
        } else {
            PLAYER_LOCALES.put(playerId, locale);
        }
    }

    /**
     * Resolves the effective locale for a player (registered → default → zh_CN).
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @return the resolved locale, never null
     */
    public static Locale resolvePlayerLocale(UUID playerId) {
        if (playerId != null) {
            Locale registered = PLAYER_LOCALES.get(playerId);
            if (registered != null) {
                return registered;
            }
        }
        return defaultLocale;
    }

    /**
     * Returns a thread-safe view bound to a specific player's locale. The
     * view re-resolves the player's locale on every call, so a late
     * {@link #registerPlayerLocale(UUID, Locale)} takes effect immediately.
     *
     * @param playerId the player's UUID (may be null → default-locale view)
     * @return a per-player translation view
     */
    public static PlayerView forPlayer(UUID playerId) {
        return new PlayerView(playerId);
    }

    // ============================ translate ============================

    /**
     * Translates a key in the default locale with {@code {0}} interpolation.
     *
     * @param key  the bundle key (e.g. {@code "chat.join.joining"})
     * @param args the {@code MessageFormat} arguments
     * @return the localized string; on a missing key, the key itself (with a warning logged)
     */
    public static String tr(String key, Object... args) {
        return tr(defaultLocale, key, args);
    }

    /**
     * Translates a key in a specific player's locale (registered → default → zh_CN).
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @param key      the bundle key
     * @param args     the {@code MessageFormat} arguments
     * @return the localized string; on a missing key, the key itself
     */
    public static String tr(UUID playerId, String key, Object... args) {
        return tr(resolvePlayerLocale(playerId), key, args);
    }

    /**
     * Translates a key in an explicit locale with {@code {0}} interpolation.
     *
     * <p>Fallback chain: requested-locale bundle → fallback (zh_CN) bundle →
     * the key itself (a warning is logged once per missing key).
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
        // MessageFormat treats {0} placeholders; color codes (&, §) are not
        // MessageFormat meta-characters so they pass through untouched.
        return new MessageFormat(pattern, loc).format(args);
    }

    /**
     * Resolves a raw pattern string (no interpolation) for a locale — exposed
     * so callers that do their own formatting (e.g. multi-arg composition)
     * can fetch the template.
     *
     * @param locale the target locale (null → default)
     * @param key    the bundle key
     * @return the raw pattern string; the key itself on a miss
     */
    public static String pattern(Locale locale, String key) {
        if (key == null) {
            return "";
        }
        Locale loc = (locale != null) ? locale : defaultLocale;
        return resolvePattern(loc, key);
    }

    // ============================ internals ============================

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
            LOG.warn("Missing i18n key: " + key + " (locale=" + locale + ")");
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
            LOG.warn("Failed to load external lang file " + file + ": " + e.getMessage());
            return null;
        }
    }

    // ============================ helpers ============================

    /**
     * Minimal logger abstraction so this class has no SLF4J dependency (the
     * client-core module is deliberately logging-agnostic; platforms plug in
     * their own logger). Warnings go to {@code System.err} with a guard so
     * repeated missing-key spam is limited.
     */
    private static final class Logger {
        private final java.util.Set<String> warned = ConcurrentHashMap.newKeySet();

        void warn(String message) {
            if (warned.add(message)) {
                System.err.println("[NovaChat i18n] " + message);
            }
        }
    }

    /** Empty bundle that echoes every requested key as a MissingResourceException. */
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
                    // containsKey said yes but getString threw (parent chain miss);
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

    // ============================ player view ============================

    /**
     * Locale-bound translation view for a single player. Re-resolves the
     * player's registered locale on every call so a late
     * {@link #registerPlayerLocale(UUID, Locale)} takes effect immediately.
     */
    public static final class PlayerView {
        private final UUID playerId;

        PlayerView(UUID playerId) {
            this.playerId = playerId;
        }

        /** @return the player id this view is bound to (may be null) */
        public UUID playerId() {
            return playerId;
        }

        /** @return the effective locale for this player right now */
        public Locale locale() {
            return resolvePlayerLocale(playerId);
        }

        /**
         * Translates a key in this player's locale with interpolation.
         *
         * @param key  the bundle key
         * @param args the {@code MessageFormat} arguments
         * @return the localized string
         */
        public String tr(String key, Object... args) {
            return I18n.tr(resolvePlayerLocale(playerId), key, args);
        }

        /**
         * Resolves a raw pattern (no interpolation) for this player's locale.
         *
         * @param key the bundle key
         * @return the raw pattern string
         */
        public String pattern(String key) {
            return I18n.pattern(resolvePlayerLocale(playerId), key);
        }
    }
}
