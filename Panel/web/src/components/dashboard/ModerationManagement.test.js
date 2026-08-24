import test from 'node:test';
import assert from 'node:assert/strict';

// PANEL-007 component-level tests for ModerationManagement.
//
// Test harness note (read before changing these): the project runs `node
// --test` (NOT vitest) with a custom ESM loader (_test/loader.mjs). The loader
// transpiles .jsx imports and rewrites the one import.meta.glob in i18n.js, so
// importing a .jsx component works under plain Node. This test file itself is
// plain .js and uses React.createElement (no JSX) so it needs no transpile and
// is discoverable by `node --test`'s default *.test.js glob.
//
// SSR limitation (verified): renderToStaticMarkup does NOT run useEffect, so
// fetchPage never fires and `items` stays []. The assign/resolve controls live
// inside a Modal that only mounts after a row onClick — not drivable in a
// single SSR render. So instead of driving the effect/modal lifecycle we
// assert: (a) render-without-crash + title + graceful empty state, (b) the
// permission layer the component reads (can(role, 'moderation.manage')), and
// (c) the exact contract + i18n strings the handlers emit. The contract tests
// in api.moderation.test.js cover the wire shape; here we cover the component
// surface + the toast/hint strings the handlers pass through.

import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import i18next from 'i18next';

// Importing i18n.js initializes the i18next instance (registers zh_CN/en_US
// resources) so t() returns real translations, not bare keys.
import '../../i18n.js';
import { can } from '../../lib/permissions.js';
import { api } from '../../services/api.js';
import ModerationManagement from './ModerationManagement.jsx';

// Safety-net fetch stub. Effects don't fire in renderToStaticMarkup so the
// component never calls fetch during a render, but we keep the global patched
// so an unexpected import-time fetch can't hit the network.
function withFetch(response = { ok: true, status: 200, body: { items: [], total: 0, page: 1 } }) {
  const original = globalThis.fetch;
  globalThis.fetch = async () => ({
    ok: response.ok,
    status: response.status,
    text: async () => JSON.stringify(response.body ?? {}),
  });
  return () => { globalThis.fetch = original; };
}

function renderMM(role, extra = {}) {
  return renderToStaticMarkup(
    React.createElement(ModerationManagement, {
      theme: 'clean',
      mode: 'light',
      onToast: () => {},
      role,
      ...extra,
    })
  );
}

test('ModerationManagement renders without crashing for ADMIN and shows the title', () => {
  const restore = withFetch();
  try {
    const html = renderMM('ADMIN');
    assert.ok(html.length > 0, 'rendered output must be non-empty');
    // i18n key moderation.title -> "审核案件" in zh_CN (default locale).
    assert.ok(html.includes('审核案件'), 'title heading should be present');
  } finally {
    restore();
  }
});

test('ModerationManagement renders without crashing for SUPER_ADMIN', () => {
  const restore = withFetch();
  try {
    const html = renderMM('SUPER_ADMIN');
    assert.ok(html.length > 0);
    assert.ok(html.includes('审核案件'));
  } finally {
    restore();
  }
});

test('ModerationManagement renders without crashing for VIEWER (degenerate — App.jsx gates the route, but a direct render must not crash)', () => {
  const restore = withFetch();
  try {
    const html = renderMM('VIEWER');
    assert.ok(html.length > 0);
  } finally {
    restore();
  }
});

test('ModerationManagement renders the graceful empty state when there are no cases (fetch did not populate items)', () => {
  const restore = withFetch({ ok: true, status: 200, body: { items: [], total: 0, page: 1 } });
  try {
    const html = renderMM('ADMIN');
    // empty hint i18n key moderation.empty -> "暂无审核案件".
    assert.ok(html.includes('暂无审核案件'), 'empty-state hint should render when items is []');
  } finally {
    restore();
  }
});

test('permission gate: VIEWER is NOT granted moderation.view (page never shown) or moderation.manage (assign/resolve/evidence hidden)', () => {
  // The page-level route (App.jsx) gates on moderation.view; the in-modal
  // assign/resolve/evidence controls gate on moderation.manage. VIEWER has
  // neither, so it can neither reach the page nor, if handed a row, manage a
  // case. This is the privacy guard: a default admin must not browse
  // private-chat content; only a legitimate case's minimal evidence snapshot
  // is reachable, and only by moderation.manage holders.
  assert.equal(can('VIEWER', 'moderation.view'), false);
  assert.equal(can('VIEWER', 'moderation.manage'), false);
  assert.equal(can('ADMIN', 'moderation.view'), true);
  assert.equal(can('ADMIN', 'moderation.manage'), true);
  assert.equal(can('SUPER_ADMIN', 'moderation.view'), true);
  assert.equal(can('SUPER_ADMIN', 'moderation.manage'), true);
});

test('resolve flow toast: i18n moderation.toast_resolved renders the action the handler passes to onToast', async () => {
  // handleResolve calls: onToast(t('moderation.toast_resolved', { action }), 'success')
  // where action is the resolve action (warn/mute/ban/kick/dismiss). Verify the
  // interpolated string for each action so the toast reads correctly.
  assert.equal(i18next.t('moderation.toast_resolved', { action: 'mute' }), '案件已处理（mute）');
  assert.equal(i18next.t('moderation.toast_resolved', { action: 'ban' }), '案件已处理（ban）');
  assert.equal(i18next.t('moderation.toast_resolved', { action: 'dismiss' }), '案件已处理（dismiss）');

  // And the resolve API returns { caseId, action } which handleResolve reads
  // to update the detail row status before firing the toast.
  const restore = withFetch({ ok: true, status: 200, body: { caseId: 'c1', action: 'mute' } });
  try {
    const res = await api.resolveCase('c1', { action: 'mute', reason: 'toxic' });
    assert.deepEqual(res, { caseId: 'c1', action: 'mute' });
  } finally {
    restore();
  }
});

test('403 self-review path: api.reviewAppeal surfaces err.status === 403, and the appeal self-review hint i18n string is present', async () => {
  // AppealQueue.handleReview checks err.status === 403 from api.reviewAppeal
  // and, when it sees it, sets selfReviewHint(true) which renders
  // t('appeals.self_review_hint'). We assert both halves of that flow here:
  //   1. apiFetch throws an error carrying .status === 403 on a 403 response
  //      (so the component's branch can detect self-review), and
  //   2. the hint string the component would render resolves to the expected
  //      Chinese text (non-empty, mentions reviewing one's own case).
  const restore = withFetch({
    ok: false,
    status: 403,
    body: { message: 'cannot review your own case' },
  });
  try {
    await assert.rejects(
      () => api.reviewAppeal('a1', { decision: 'APPROVED', note: '' }),
      (err) => err.status === 403
    );
  } finally {
    restore();
  }
  const hint = i18next.t('appeals.self_review_hint');
  assert.ok(typeof hint === 'string' && hint.length > 0, 'self-review hint must resolve to non-empty text');
  assert.ok(/不能审核/.test(hint), 'self-review hint should tell the reviewer they cannot review their own case');
});

test('assign flow toast: i18n moderation.toast_assigned resolves to the success string handleAssign passes to onToast', () => {
  // handleAssign on success: onToast(t('moderation.toast_assigned'), 'success')
  assert.equal(i18next.t('moderation.toast_assigned'), '案件已分配');
  assert.ok(i18next.t('moderation.toast_assign_failed', { error: 'boom' }).length > 0);
});
