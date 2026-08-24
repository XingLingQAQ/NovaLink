import test from 'node:test';
import assert from 'node:assert/strict';

// Smoke test: api.js imports i18n.js which uses import.meta.glob. Under plain
// node --test this requires the _test/loader.mjs shim. This test verifies the
// shim works end-to-end so api.moderation.test.js can rely on importing api.
import { api } from './api.js';

test('api.js loads under node --test (i18n import.meta.glob shim works)', () => {
  assert.equal(typeof api.createReport, 'function');
  assert.equal(typeof api.listCases, 'function');
  assert.equal(typeof api.getCase, 'function');
  assert.equal(typeof api.assignCase, 'function');
  assert.equal(typeof api.resolveCase, 'function');
  assert.equal(typeof api.getCaseEvidence, 'function');
  assert.equal(typeof api.createAppeal, 'function');
  assert.equal(typeof api.listAppeals, 'function');
  assert.equal(typeof api.reviewAppeal, 'function');
});
