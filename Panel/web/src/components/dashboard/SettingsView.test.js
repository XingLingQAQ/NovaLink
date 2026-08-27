import test from 'node:test';
import assert from 'node:assert/strict';

// SettingsView — inner-tab redesign. The word filter / webhooks / config history
// / config publish surfaces are now inner tabs of the Settings page (capability
// gated), so they no longer have their own top-level sidebar entries.
//
// Test harness note (mirrors ConfigHistory.test.js / TopBar.test.js): the
// project runs `node --test` (NOT vitest) with a custom ESM loader
// (_test/loader.mjs) that transpiles .jsx imports and rewrites the one
// import.meta.glob in i18n.js. This file is plain .js using React.createElement
// so it needs no transpile and is discoverable by `node --test`.
//
// SSR limitation (verified): renderToStaticMarkup does NOT run useEffect, so
// the child fetches (filter load, webhook list, config-history list) never fire
// during a render. We assert the tab shell contract: (a) the default tab is
// general, (b) the filter tab is hidden for a role lacking filter.manage, (c)
// the filter tab is present AND renders FilterManagement (its title) for a role
// that has it, (d) the ARIA tablist/tab/tabpanel wiring is correct, (e) the
// active sub-tab is clamped to general when the requested tab is gated away.

import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

// Importing i18n.js initializes the i18next instance (registers zh_CN/en_US
// resources) so t() returns real translations, not bare keys.
import '../../i18n.js';
import { can } from '../../lib/permissions.js';
import SettingsView from './SettingsView.jsx';

function noop() {}

function renderSV(role, extra = {}) {
  return renderToStaticMarkup(
    React.createElement(SettingsView, {
      theme: 'clean',
      mode: 'light',
      settings: { supported: {} },
      onToggle: noop,
      onChange: noop,
      settingsLoading: false,
      setMode: noop,
      modeState: 'light',
      wsState: 0,
      apiUrl: 'http://localhost:8888',
      wsUrl: 'ws://localhost:8888',
      role,
      onToast: noop,
      webhooks: [],
      webhooksLoading: false,
      onCreateWebhook: noop,
      onDeleteWebhook: noop,
      onUpdateWebhook: noop,
      onTestWebhook: noop,
      txtMain: '',
      txtSec: '',
      settingsSubTab: 'general',
      onSettingsSubTabChange: noop,
      ...extra,
    })
  );
}

// ====================== Default tab ======================

test('SettingsView default sub-tab is general (appearance section renders)', () => {
  const html = renderSV('SUPER_ADMIN');
  // i18n common.settings_tab_general -> 常规.
  assert.ok(html.includes('常规'), 'general tab button present');
  // The general panel (外观 / 连接状态 / 聊天功能 sections) renders by default.
  assert.ok(html.includes('外观'), 'appearance section renders under general');
  assert.ok(html.includes('连接状态'), 'connection section renders under general');
  assert.ok(html.includes('聊天功能'), 'chat-features section renders under general');
});

// ====================== Tab list (SUPER_ADMIN sees all 5) ======================

test('SettingsView SUPER_ADMIN sees all 5 tabs', () => {
  const html = renderSV('SUPER_ADMIN');
  assert.ok(html.includes('常规'), 'general tab');
  assert.ok(html.includes('敏感词过滤'), 'filter tab');
  assert.ok(html.includes('Webhook'), 'webhooks tab');
  assert.ok(html.includes('配置历史'), 'configHistory tab');
  assert.ok(html.includes('配置发布'), 'configPublish tab');
});

// ====================== RBAC: filter tab gating ======================

test('SettingsView RBAC: filter tab hidden for role without filter.manage (VIEWER)', () => {
  assert.equal(can('VIEWER', 'filter.manage'), false, 'VIEWER lacks filter.manage');
  const html = renderSV('VIEWER');
  assert.ok(html.includes('常规'), 'general tab present for VIEWER');
  // The filter tab button is identified by its id, not its label: the label
  // 敏感词过滤 also appears in settings_filter_desc (rendered under General), so
  // a substring check would false-positive. Assert the tab button id is absent.
  assert.ok(!html.includes('id="settings-tab-filter"'), 'filter tab button hidden for VIEWER');
  // The filter panel must NOT render either.
  assert.ok(!html.includes('id="settings-panel-filter"'), 'filter panel id absent for VIEWER');
});

test('SettingsView RBAC: filter tab present + renders FilterManagement for ADMIN (has filter.manage)', () => {
  assert.equal(can('ADMIN', 'filter.manage'), true, 'ADMIN has filter.manage');
  // Request the filter sub-tab explicitly.
  const html = renderSV('ADMIN', { settingsSubTab: 'filter' });
  assert.ok(html.includes('id="settings-tab-filter"'), 'filter tab button present for ADMIN');
  // FilterManagement renders its enabled toggle label (filter.enabled_label
  // -> 启用过滤); 敏感词过滤 alone is ambiguous (see the VIEWER test above).
  assert.ok(html.includes('启用过滤'), 'FilterManagement enabled-label rendered under filter tab');
  // The filter panel has the tabpanel id.
  assert.ok(html.includes('id="settings-panel-filter"'), 'filter panel rendered with tabpanel id');
  // The general panel must NOT render when filter is active.
  assert.ok(!html.includes('id="settings-panel-general"'), 'general panel not rendered when filter active');
});

// ====================== RBAC: configHistory / configPublish gating ======================

test('SettingsView RBAC: configHistory tab hidden for VIEWER, present for ADMIN', () => {
  assert.equal(can('VIEWER', 'settings.history'), false, 'VIEWER lacks settings.history');
  assert.equal(can('ADMIN', 'settings.history'), true, 'ADMIN has settings.history');
  assert.ok(!renderSV('VIEWER').includes('配置历史'), 'configHistory tab hidden for VIEWER');
  assert.ok(renderSV('ADMIN').includes('配置历史'), 'configHistory tab present for ADMIN');
});

test('SettingsView RBAC: configPublish tab is SUPER_ADMIN-only (hidden for ADMIN+VIEWER)', () => {
  assert.equal(can('ADMIN', 'config.publish'), false, 'ADMIN lacks config.publish');
  assert.equal(can('SUPER_ADMIN', 'config.publish'), true, 'SUPER_ADMIN has config.publish');
  assert.ok(!renderSV('ADMIN').includes('配置发布'), 'configPublish tab hidden for ADMIN');
  assert.ok(!renderSV('VIEWER').includes('配置发布'), 'configPublish tab hidden for VIEWER');
  assert.ok(renderSV('SUPER_ADMIN').includes('配置发布'), 'configPublish tab present for SUPER_ADMIN');
});

// ====================== Defense-in-depth: clamp gated sub-tab ======================

test('SettingsView clamps a gated-away sub-tab to general (defense-in-depth)', () => {
  // VIEWER cannot use filter, but a stale deep-link requests settingsSubTab=filter.
  // SettingsView must fall back to general, NOT render the filter panel.
  const html = renderSV('VIEWER', { settingsSubTab: 'filter' });
  assert.ok(html.includes('外观'), 'fell back to general panel (appearance renders)');
  assert.ok(!html.includes('id="settings-panel-filter"'), 'filter panel did NOT render despite the stale request');
  // The general tab is aria-selected=true.
  assert.ok(html.includes('aria-selected="true"'), 'some tab is selected');
});

// ====================== ARIA tablist / tab / tabpanel wiring ======================

test('SettingsView ARIA: tablist + tab + tabpanel ids are wired', () => {
  const html = renderSV('SUPER_ADMIN');
  assert.ok(html.includes('role="tablist"'), 'tablist role present');
  assert.ok(html.includes('role="tab"'), 'tab role present');
  assert.ok(html.includes('role="tabpanel"'), 'tabpanel role present');
  // Each tab controls its panel: aria-controls=settings-panel-<id> + id=settings-tab-<id>.
  assert.ok(html.includes('aria-controls="settings-panel-general"'), 'general tab controls its panel');
  assert.ok(html.includes('id="settings-tab-general"'), 'general tab has its id');
  // The general panel is labelled by its tab.
  assert.ok(html.includes('aria-labelledby="settings-tab-general"'), 'general panel labelled by its tab');
});

// ====================== i18n parity ======================

test('SettingsView i18n: the 5 tab-label keys exist in both locales', async () => {
  const { readFile } = await import('node:fs/promises');
  const zh = JSON.parse(await readFile(new URL('../../lang/zh_CN.json', import.meta.url), 'utf8'));
  const en = JSON.parse(await readFile(new URL('../../lang/en_US.json', import.meta.url), 'utf8'));
  const keys = [
    'settings_tab_general',
    'settings_tab_filter',
    'settings_tab_webhooks',
    'settings_tab_config_history',
    'settings_tab_config_publish',
  ];
  for (const k of keys) {
    assert.ok(k in zh.common, `zh.common.${k} present`);
    assert.ok(k in en.common, `en.common.${k} present`);
  }
});
