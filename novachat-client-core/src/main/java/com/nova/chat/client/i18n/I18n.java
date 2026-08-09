package com.nova.chat.client.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
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
 * <p>Bundles are loaded from {@code messages_<lang>_<country>.properties} on
 * the classpath via a UTF-8 {@link ResourceBundle.Control} (see
 * {@link Utf8Control}). Java 9+ already loads {@code PropertyResourceBundle}
 * from an {@code InputStreamReader} as UTF-8, but the explicit control
 * guarantees correct behavior regardless of the default charset and keeps
 * the bundle cache under our control.
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

    /** Base name of the .properties bundles on the classpath. */
    static final String BASE_NAME = "messages";

    private static final Logger LOG = new Logger();

    /** Hard fallback locale (zh_CN) — always loaded so missing keys degrade to Chinese. */
    private static final Locale FALLBACK_LOCALE = LocaleResolver.ROOT_LOCALE;

    /** Cache of (locale -> ResourceBundle); the fallback bundle is always present. */
    private static final ConcurrentMap<Locale, ResourceBundle> BUNDLES = new ConcurrentHashMap<>();

    /** Per-player locale registrations (UUID -> locale), populated by platform plugins. */
    private static final ConcurrentMap<UUID, Locale> PLAYER_LOCALES = new ConcurrentHashMap<>();

    /** Configured default locale; volatile so reads from any thread see the latest set. */
    private static volatile Locale defaultLocale = FALLBACK_LOCALE;

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
        try {
            return ResourceBundle.getBundle(BASE_NAME, locale, new Utf8Control());
        } catch (MissingResourceException e) {
            if (!locale.equals(FALLBACK_LOCALE)) {
                return bundleFor(FALLBACK_LOCALE);
            }
            // No fallback bundle on the classpath at all — return an empty
            // bundle so every key echoes itself rather than throwing.
            return new EmptyBundle();
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
