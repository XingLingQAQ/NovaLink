import test from 'node:test';
import assert from 'node:assert/strict';

// Sidebar — grouped nav redesign (4 collapsible sections: Overview / Operations
// / Moderation & Safety / System). Settings-adjacent surfaces (webhooks / word
// filter / config history / config publish) no longer have top-level leaves;
// they are inner tabs of Settings.
//
// Test harness note (mirrors ConfigHistory.test.js / TopBar.test.js): the
// project runs `node --test` (NOT vitest) with a custom ESM loader
// (_test/loader.mjs) that transpiles .jsx imports and rewrites the one
// import.meta.glob in i18n.js. This file is plain .js using React.createElement
// so it needs no transpile and is discoverable by `node --test`.
//
// SSR limitation (verified): renderToStaticMarkup does NOT run useEffect or
// event handlers, so (1) the localStorage read in loadCollapsedGroups falls
// back to the all-expanded default (globalThis.localStorage is undefined under
// node --test), and (2) clicking a group heading cannot be simulated. We
// assert the structural contract instead: every group heading button carries
// aria-expanded + aria-controls pointing at its leaf <ul>, and the group
// holding the active tab always renders its leaves (isExpanded returns true
// for the active group regardless of persisted collapse state). The leaf
// buttons render with aria-current="page" on the active leaf; the click wiring
// itself (onTabChange(leafId)) is a runtime concern not exercised under SSR,
// covered indirectly by asserting the leaf buttons are present + labelled.

import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

// Importing i18n.js initializes the i18next instance (registers zh_CN/en_US
// resources) so t() returns real translations, not bare keys.
import '../../i18n.js';
import { can } from '../../lib/permissions.js';
import Sidebar from './Sidebar.jsx';

function noop() {}

function renderSidebar(extra = {}) {
  return renderToStaticMarkup(
    React.createElement(Sidebar, {
      activeTab: 'dashboard',
      onTabChange: noop,
      sidebarOpen: true,
      isMobile: false,
      onOverlayClick: noop,
      currentUser: { username: 'admin', role: 'SUPER_ADMIN' },
      onLogout: noop,
      role: 'SUPER_ADMIN',
      ...extra,
    })
  );
}

// ====================== Group headings ======================

test('Sidebar renders all 4 group headings for SUPER_ADMIN (expanded default)', () => {
  // globalThis.localStorage is undefined under node --test, so
  // loadCollapsedGroups returns {} -> every group is expanded by default.
  const html = renderSidebar();
  // i18n common.nav_group_* -> 概览 / 运营 / 审核与安全 / 系统 in zh_CN.
  assert.ok(html.includes('概览'), 'overview group heading present');
  assert.ok(html.includes('运营'), 'operations group heading present');
  assert.ok(html.includes('审核与安全'), 'moderation group heading present');
  assert.ok(html.includes('系统'), 'system group heading present');
});

test('Sidebar group headings are toggle buttons with aria-expanded + aria-controls', () => {
  const html = renderSidebar();
  // Each heading is a <button> with aria-expanded and aria-controls=nav-group-<id>.
  assert.ok(html.includes('aria-controls="nav-group-overview"'), 'overview heading controls its list');
  assert.ok(html.includes('aria-controls="nav-group-operations"'), 'operations heading controls its list');
  assert.ok(html.includes('aria-controls="nav-group-moderation"'), 'moderation heading controls its list');
  assert.ok(html.includes('aria-controls="nav-group-system"'), 'system heading controls its list');
  assert.ok(html.includes('aria-expanded="true"'), 'expanded groups report aria-expanded=true');
});

// ====================== Leaf-list visibility / toggle contract ======================

test('Sidebar: the group holding the active tab always renders its leaves (isExpanded override)', () => {
  // activeTab='console' lives in the operations group. Even if every group
  // were collapsed, isExpanded forces the active group open. Under SSR the
  // default is all-expanded anyway, so we assert the stronger invariant: the
  // active leaf is present + marked aria-current=page.
  const html = renderSidebar({ activeTab: 'console' });
  // i18n common.nav_console_command -> 控制台命令.
  assert.ok(html.includes('控制台命令'), 'active leaf (console) renders');
  assert.ok(html.includes('aria-current="page"'), 'active leaf is marked aria-current=page');
});

test('Sidebar: leaf buttons render for every SUPER_ADMIN leaf (click target present)', () => {
  // SSR cannot fire onClick; we assert the leaf buttons are present and
  // labelled, which is the structural precondition for onTabChange(leafId).
  const html = renderSidebar({ activeTab: 'dashboard' });
  // One leaf per SUPER_ADMIN-visible entry (dashboard, status, messages,
  // history, console, servers, channels, players, announcements, campaigns,
  // moderation, appeals, audit, settings) = 14 leaves.
  assert.ok(html.includes('仪表盘'), 'dashboard leaf');
  assert.ok(html.includes('系统状态'), 'status leaf');
  assert.ok(html.includes('消息监控'), 'messages leaf');
  assert.ok(html.includes('历史消息'), 'history leaf');
  assert.ok(html.includes('控制台命令'), 'console leaf');
  assert.ok(html.includes('服务器'), 'servers leaf');
  assert.ok(html.includes('频道管理'), 'channels leaf');
  assert.ok(html.includes('玩家管理'), 'players leaf');
  assert.ok(html.includes('公告管理'), 'announcements leaf');
  assert.ok(html.includes('活动推送'), 'campaigns leaf');
  assert.ok(html.includes('审核'), 'moderation leaf');
  assert.ok(html.includes('申诉'), 'appeals leaf');
  assert.ok(html.includes('审计日志'), 'audit leaf');
  assert.ok(html.includes('系统设置'), 'settings leaf');
});

// ====================== RBAC filtering ======================

test('Sidebar RBAC: VIEWER does not see the console leaf (can VIEWER console === false)', () => {
  // The exact predicate the component uses.
  assert.equal(can('VIEWER', 'console'), false, 'VIEWER lacks console');
  assert.equal(can('SUPER_ADMIN', 'console'), true, 'SUPER_ADMIN has console');

  const html = renderSidebar({ role: 'VIEWER', currentUser: { username: 'viewer', role: 'VIEWER' } });
  // console (控制台命令) is gated by `console` -> hidden for VIEWER.
  assert.ok(!html.includes('控制台命令'), 'console leaf hidden for VIEWER');
  // announcements (公告管理) + campaigns (活动推送) gated by announcements.manage -> hidden.
  assert.ok(!html.includes('公告管理'), 'announcements leaf hidden for VIEWER');
  assert.ok(!html.includes('活动推送'), 'campaigns leaf hidden for VIEWER');
  // Ungated leaves are still present.
  assert.ok(html.includes('仪表盘'), 'dashboard still visible for VIEWER');
  assert.ok(html.includes('消息监控'), 'messages still visible for VIEWER');
  assert.ok(html.includes('系统设置'), 'settings still visible for VIEWER');
});

test('Sidebar RBAC: an empty group is dropped entirely (VIEWER loses the whole moderation group)', () => {
  // VIEWER has none of moderation.view / appeals.review / audit.view, so the
  // moderation group has zero visible leaves and must not render at all.
  const html = renderSidebar({ role: 'VIEWER', currentUser: { username: 'viewer', role: 'VIEWER' } });
  assert.ok(!html.includes('审核与安全'), 'moderation group heading hidden when all its leaves are gated');
  assert.ok(!html.includes('nav-group-moderation'), 'moderation group list id absent');
  // The remaining three groups are still present.
  assert.ok(html.includes('概览'), 'overview group still present for VIEWER');
  assert.ok(html.includes('运营'), 'operations group still present for VIEWER');
  assert.ok(html.includes('系统'), 'system group still present for VIEWER');
});

test('Sidebar RBAC: ADMIN sees the moderation group (has moderation.view / appeals.review / audit.view)', () => {
  const html = renderSidebar({ role: 'ADMIN', currentUser: { username: 'admin', role: 'ADMIN' } });
  assert.ok(html.includes('审核与安全'), 'moderation group present for ADMIN');
  assert.ok(html.includes('审核'), 'moderation leaf present for ADMIN');
  assert.ok(html.includes('申诉'), 'appeals leaf present for ADMIN');
  assert.ok(html.includes('审计日志'), 'audit leaf present for ADMIN');
  // ADMIN lacks `console` -> console leaf hidden.
  assert.ok(!html.includes('控制台命令'), 'console leaf hidden for ADMIN');
});

// ====================== Rail mode (desktop, sidebar closed) ======================

test('Sidebar rail mode: group headings hidden when sidebar closed on desktop', () => {
  // railMode = !sidebarOpen && !isMobile. Headings are suppressed; leaves
  // render as icons only. The group <ul> still renders (expanded under railMode).
  const html = renderSidebar({ sidebarOpen: false, isMobile: false });
  assert.ok(!html.includes('aria-controls="nav-group-overview"'), 'overview heading suppressed in rail mode');
  // Leaves still present (icon-only); labels are opacity-0 but in markup.
  assert.ok(html.includes('仪表盘'), 'dashboard leaf still in rail markup');
});

// ====================== i18n parity ======================

test('Sidebar i18n: the 4 group-label keys exist in both locales', async () => {
  const { readFile } = await import('node:fs/promises');
  const zh = JSON.parse(await readFile(new URL('../../lang/zh_CN.json', import.meta.url), 'utf8'));
  const en = JSON.parse(await readFile(new URL('../../lang/en_US.json', import.meta.url), 'utf8'));
  const keys = [
    'nav_group_overview',
    'nav_group_operations',
    'nav_group_moderation',
    'nav_group_system',
  ];
  for (const k of keys) {
    assert.ok(k in zh.common, `zh.common.${k} present`);
    assert.ok(k in en.common, `en.common.${k} present`);
  }
});
