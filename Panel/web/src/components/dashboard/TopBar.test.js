import test from 'node:test';
import assert from 'node:assert/strict';

// TopBar — dashboard top bar (sidebar toggle, WS status, notification bell,
// language switcher, theme toggle).
//
// Test harness note (mirrors ConfigHistory.test.js / StatusPage.test.js):
// the project runs `node --test` (NOT vitest) with a custom ESM loader
// (_test/loader.mjs) that transpiles .jsx imports and rewrites the one
// import.meta.glob in i18n.js. This file is plain .js using React.createElement
// so it needs no transpile and is discoverable by `node --test`.
//
// Regression scope (P1, found by live browser E2E): i18next v26 removed the
// store() API, and TopBar's language-switcher label derivation called
// i18n.store().data on mount. The thrown TypeError unmounted the whole React
// tree after login -> blank white page. No prior test rendered TopBar, so the
// 140-test suite stayed green while production was broken. These tests render
// TopBar directly so any future removed-API usage on the mount path fails here.
//
// SSR limitation (verified): renderToStaticMarkup does NOT run useEffect, so
// the notification-dropdown click-outside listener never registers during a
// render and no document/global is needed. The dropdown child renders closed
// (opacity-0) inline, which is exactly what we want to assert against.

import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

// Importing i18n.js initializes the i18next instance (registers zh_CN/en_US
// resources) so t() returns real translations, not bare keys.
import '../../i18n.js';
import { SUPPORTED_LANGS, languageLabel } from '../../i18n.js';
import { ConnectionState } from '../../services/websocket.js';
import TopBar from './TopBar.jsx';

function noop() {}

function renderTopBar(extra = {}) {
  return renderToStaticMarkup(
    React.createElement(TopBar, {
      sidebarOpen: true,
      onToggleSidebar: noop,
      isMobile: false,
      mode: 'light',
      setMode: noop,
      wsState: ConnectionState.DISCONNECTED,
      onManualReconnect: noop,
      notifications: [],
      apiUnreadCount: 0,
      onMarkAllRead: noop,
      onClearAll: noop,
      onOpenList: noop,
      ...extra,
    })
  );
}

// ====================== languageLabel (pure helper) ======================

test('languageLabel: full locales map to their language.self translation', () => {
  // From src/lang/*.json ("language.self"): zh_CN -> 中文, en_US -> EN.
  assert.equal(languageLabel('zh_CN'), '中文');
  assert.equal(languageLabel('en_US'), 'EN');
});

test('languageLabel: bare-code aliases resolve through their base resource', () => {
  // i18n.js registers zh/en aliases pointing at the zh_CN/en_US JSONs, so a
  // bare navigator-language code still gets the human label, not the code.
  assert.equal(languageLabel('zh'), '中文');
  assert.equal(languageLabel('en'), 'EN');
});

test('languageLabel: unknown or malformed locale falls back to the code itself', () => {
  assert.equal(languageLabel('xx_YY'), 'xx_YY');
  assert.equal(languageLabel('de'), 'de');
  // Must not throw on missing input (the optional-chain guard in the helper).
  assert.doesNotThrow(() => languageLabel(undefined));
});

// ====================== Rendering ======================

test('TopBar renders without crashing (regression: i18next v26 removed store())', () => {
  // The old label derivation called i18n.store?.()?.data on mount; v26 dropped
  // the API so this exact render path used to throw and blank the whole app.
  const html = renderTopBar();
  assert.ok(html.length > 0, 'rendered output must be non-empty');
});

test('TopBar renders a switcher button labelled by languageLabel() for EVERY supported language', () => {
  const html = renderTopBar();
  for (const lang of SUPPORTED_LANGS) {
    const label = languageLabel(lang);
    assert.ok(
      html.includes(`>${label}</button>`),
      `switcher button for ${lang} must show "${label}" (from language.self), got: ${html.slice(0, 200)}…`
    );
  }
  // The switcher chrome itself is present (i18n language.switch_title).
  assert.ok(html.includes('切换语言'), 'language.switch_title tooltip present');
});

test('TopBar switcher shows human-readable labels, never raw locale codes as button text', () => {
  const html = renderTopBar();
  // If the label derivation regresses to falling back everywhere, the buttons
  // would read "zh_CN"/"en_US" — the exact silent-degradation mode we guard.
  assert.ok(!html.includes('>zh_CN</button>'), 'no raw zh_CN button label');
  assert.ok(!html.includes('>en_US</button>'), 'no raw en_US button label');
  assert.ok(html.includes('>中文</button>'), 'Chinese self-label rendered');
  assert.ok(html.includes('>EN</button>'), 'English self-label rendered');
});

test('TopBar renders the rest of its chrome alongside the fixed switcher', () => {
  const html = renderTopBar();
  // WS status pill (wsState DISCONNECTED -> common.ws_disconnected) ...
  assert.ok(html.includes('WS 未连接'), 'WS disconnected indicator present');
  // ... notification bell dropdown (closed state) ...
  assert.ok(html.includes('系统通知'), 'notifications.title heading present');
  // ... and both theme-toggle buttons (common.theme_light / theme_dark titles).
  assert.ok(html.includes('浅色模式'), 'light-mode toggle present');
  assert.ok(html.includes('深色模式'), 'dark-mode toggle present');
});
