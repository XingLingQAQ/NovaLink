/**
 * i18next initialization for NovaPanel.
 *
 * Languages are AUTO-DETECTED at build time: every `src/lang/<locale>.json`
 * is globbed via Vite's `import.meta.glob` and registered as a resource.
 * Dropping a new `src/lang/<locale>.json` file is the ONLY step needed to
 * add a language — no registry edit, no import to add. The file is picked up
 * on the next build / dev reload.
 *
 * Detection order: localStorage 'nova-panel-lang' -> navigator -> htmlTag.
 * Fallback: zh_CN. Bare-code aliases (zh -> zh_CN, en -> en_US, …) are derived
 * dynamically from the detected files so a navigator.language like "en" still
 * resolves to the en_US translations.
 *
 * Import this module once in src/main.jsx BEFORE <App /> renders so the
 * initialized i18n instance is available to every useTranslation() hook.
 */

import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

export const LANG_STORAGE_KEY = 'nova-panel-lang';
export const DEFAULT_LANG = 'zh_CN';

/**
 * Eagerly import every lang/<locale>.json at build time.
 * `import: 'default'` yields the parsed JSON object directly (the default
 * export of a JSON module), so each module value IS the translation object.
 * Keys are the filename without extension, e.g. './lang/zh_CN.json' -> 'zh_CN'.
 */
const localeModules = import.meta.glob('./lang/*.json', {
  eager: true,
  import: 'default',
});

/**
 * Map a locale code to the locale it should alias for translation lookup.
 * "en" -> "en_US", "zh" -> "zh_CN", while "en_US" stays "en_US" (no alias).
 * Returns null when the locale is already a full resource (no bare alias needed).
 */
function aliasTarget(locale) {
  const base = locale.split('_')[0];
  return base === locale ? null : base;
}

/**
 * Build the i18next `resources` object dynamically from the globbed JSONs.
 * For each detected <locale>.json we register:
 *   - <locale>: { translation: <json> }      (e.g. zh_CN, en_US)
 *   - <base>: { translation: <json> }         (e.g. zh, en) when <base> is not
 *                                             itself a registered full locale,
 *                                             so a bare navigator.language code
 *                                             still resolves to translations.
 */
const resources = {};
for (const [path, translation] of Object.entries(localeModules)) {
  const locale = path.split('/').pop().replace(/\.json$/, '');
  resources[locale] = { translation };
  const base = aliasTarget(locale);
  if (base && !resources[base]) {
    resources[base] = { translation };
  }
}

/**
 * The list of full locales (zh_CN, en_US, …) minus the bare-code aliases (zh, en).
 * Drives the language switcher UI: new langs appear automatically.
 */
export const SUPPORTED_LANGS = Object.keys(resources).filter(
  (locale) => !aliasTarget(locale)
);

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    // NOTE: zh/en aliases point at the same JSON as zh_CN/en_US so a bare code
    // emitted by the browser language detector (e.g. navigator.language === 'en')
    // still resolves to the full translations. We intentionally do NOT set
    // `supportedLngs` together with `nonExplicitSupportedLngs`: in i18next v23+,
    // that combination causes t() to fall through and return the raw key (every
    // string rendered as e.g. "dashboard.online_servers"). fallbackLng covers
    // any unrecognized language instead.
    resources,
    fallbackLng: DEFAULT_LANG,
    nonExplicitSupportedLngs: true,
    interpolation: {
      escapeValue: false, // React already escapes by default
    },
    detection: {
      order: ['localStorage', 'navigator', 'htmlTag'],
      lookupLocalStorage: LANG_STORAGE_KEY,
      caches: ['localStorage'],
    },
  });

export default i18n;
