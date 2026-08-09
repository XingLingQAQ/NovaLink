/**
 * i18next initialization for NovaPanel.
 *
 * Detects the browser language (localStorage 'nova-panel-lang' -> navigator),
 * falls back to zh_CN, and loads the bundled zh_CN + en_US resources directly
 * (no network/HTTP backend — the JSON is imported below).
 *
 * Import this module once in src/main.jsx BEFORE <App /> renders so the
 * initialized i18n instance is available to every useTranslation() hook.
 */

import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

import zh_CN from './locales/zh_CN.json';
import en_US from './locales/en_US.json';

export const LANG_STORAGE_KEY = 'nova-panel-lang';
export const DEFAULT_LANG = 'zh_CN';
export const SUPPORTED_LANGS = ['zh_CN', 'en_US'];

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
    resources: {
      zh_CN: { translation: zh_CN },
      en_US: { translation: en_US },
      zh: { translation: zh_CN },
      en: { translation: en_US },
    },
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
