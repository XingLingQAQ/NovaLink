import test from 'node:test';
import assert from 'node:assert/strict';

// PANEL-007 contract tests for the moderation / appeals API client methods.
// These assert the request shape (path + method + body) the frontend promises
// the backend, per the locked REST contract. They do NOT hit a live backend —
// fetch is stubbed so we can inspect the outgoing call.
//
// Requires the _test loader shim (npm test) because importing api.js pulls in
// src/i18n.js which uses import.meta.glob (Vite-only).

import { api, apiFetch } from './api.js';

// In-memory fetch stub. Captures the last call's url + init so each test can
// assert method/path/body. Returns a minimal 200 JSON response by default.
function makeFetchStub(response = { ok: true, status: 200, body: {} }) {
  const calls = [];
  const stub = async (url, init) => {
    calls.push({ url: String(url), init });
    return {
      ok: response.ok,
      status: response.status,
      text: async () => JSON.stringify(response.body ?? {}),
    };
  };
  stub.calls = calls;
  return stub;
}

function setupGlobalFetch(stub) {
  const original = globalThis.fetch;
  globalThis.fetch = stub;
  return () => { globalThis.fetch = original; };
}

test('createReport: POST /reports with full body', async () => {
  const stub = makeFetchStub({ ok: true, status: 201, body: { caseId: 'c1', status: 'OPEN' } });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.createReport({
      reportedPlayerId: 'p1',
      reasonCode: 'SPAM',
      reasonText: 'spamming',
      originChannelId: 'ch1',
      evidenceSnapshot: 'snap',
    });
    assert.equal(stub.calls.length, 1);
    const { url, init } = stub.calls[0];
    assert.match(url, /\/reports$/);
    assert.equal(init.method, 'POST');
    const body = JSON.parse(init.body);
    assert.deepEqual(body, {
      reportedPlayerId: 'p1',
      reasonCode: 'SPAM',
      reasonText: 'spamming',
      originChannelId: 'ch1',
      evidenceSnapshot: 'snap',
    });
    assert.deepEqual(res, { caseId: 'c1', status: 'OPEN' });
  } finally {
    restore();
  }
});

test('listCases: GET /moderation/cases with page+size, optional filters omitted when empty', async () => {
  const stub = makeFetchStub({ ok: true, status: 200, body: { items: [], total: 0, page: 1 } });
  const restore = setupGlobalFetch(stub);
  try {
    await api.listCases({ page: 1, size: 20 });
    const { url } = stub.calls[0];
    assert.match(url, /\/moderation\/cases\?page=1&size=20$/);
    assert.equal(stub.calls[0].init.method, undefined); // GET
  } finally {
    restore();
  }
});

test('listCases: status + assigned filters appended when provided', async () => {
  const stub = makeFetchStub();
  const restore = setupGlobalFetch(stub);
  try {
    await api.listCases({ page: 2, size: 10, status: 'OPEN', assigned: 'mod1' });
    const { url } = stub.calls[0];
    assert.match(url, /page=2/);
    assert.match(url, /size=10/);
    assert.match(url, /status=OPEN/);
    assert.match(url, /assigned=mod1/);
  } finally {
    restore();
  }
});

test('getCase: GET /moderation/cases/{id} (id encoded)', async () => {
  const stub = makeFetchStub();
  const restore = setupGlobalFetch(stub);
  try {
    await api.getCase('c/1');
    const { url } = stub.calls[0];
    assert.match(url, /\/moderation\/cases\/c%2F1$/);
  } finally {
    restore();
  }
});

test('assignCase: POST /moderation/cases/{id}/assign { moderator }', async () => {
  const stub = makeFetchStub();
  const restore = setupGlobalFetch(stub);
  try {
    await api.assignCase('c1', 'modAlice');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/moderation\/cases\/c1\/assign$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { moderator: 'modAlice' });
  } finally {
    restore();
  }
});

test('resolveCase: POST /moderation/cases/{id}/resolve { action, reason, ... }', async () => {
  const stub = makeFetchStub({ ok: true, status: 200, body: { caseId: 'c1', action: 'mute' } });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.resolveCase('c1', {
      action: 'mute',
      reason: 'toxic',
      targetChannelId: 'ch1',
      durationMs: 60000,
    });
    const { url, init } = stub.calls[0];
    assert.match(url, /\/moderation\/cases\/c1\/resolve$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), {
      action: 'mute',
      reason: 'toxic',
      targetChannelId: 'ch1',
      durationMs: 60000,
    });
    assert.deepEqual(res, { caseId: 'c1', action: 'mute' });
  } finally {
    restore();
  }
});

test('resolveCase: dismiss omits targetChannelId/durationMs', async () => {
  const stub = makeFetchStub();
  const restore = setupGlobalFetch(stub);
  try {
    await api.resolveCase('c1', { action: 'dismiss', reason: 'no violation' });
    const body = JSON.parse(stub.calls[0].init.body);
    assert.deepEqual(body, { action: 'dismiss', reason: 'no violation' });
    assert.equal(body.targetChannelId, undefined);
    assert.equal(body.durationMs, undefined);
  } finally {
    restore();
  }
});

test('getCaseEvidence: GET /moderation/cases/{id}/evidence', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { items: [{ evidenceType: 'MESSAGE', contentHash: 'h', contentSnapshot: 's', itemJson: '{}', capturedAt: 1, capturedBy: 'u' }] },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.getCaseEvidence('c1');
    const { url } = stub.calls[0];
    assert.match(url, /\/moderation\/cases\/c1\/evidence$/);
    assert.ok(Array.isArray(res.items));
    assert.equal(res.items[0].evidenceType, 'MESSAGE');
  } finally {
    restore();
  }
});

test('createAppeal: POST /appeals { caseId, appellantId, reason }', async () => {
  const stub = makeFetchStub({ ok: true, status: 201, body: { appealId: 'a1', status: 'PENDING' } });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.createAppeal({ caseId: 'c1', appellantId: 'p1', reason: 'unfair' });
    const { url, init } = stub.calls[0];
    assert.match(url, /\/appeals$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { caseId: 'c1', appellantId: 'p1', reason: 'unfair' });
    assert.deepEqual(res, { appealId: 'a1', status: 'PENDING' });
  } finally {
    restore();
  }
});

test('listAppeals: GET /appeals with page+size, status optional', async () => {
  const stub = makeFetchStub();
  const restore = setupGlobalFetch(stub);
  try {
    await api.listAppeals({ page: 1, size: 20 });
    const { url } = stub.calls[0];
    assert.match(url, /\/appeals\?page=1&size=20$/);
  } finally {
    restore();
  }
  // with status
  const stub2 = makeFetchStub();
  const restore2 = setupGlobalFetch(stub2);
  try {
    await api.listAppeals({ page: 2, size: 5, status: 'PENDING' });
    const { url } = stub2.calls[0];
    assert.match(url, /page=2/);
    assert.match(url, /size=5/);
    assert.match(url, /status=PENDING/);
  } finally {
    restore2();
  }
});

test('reviewAppeal: POST /appeals/{id}/review { decision, note }', async () => {
  const stub = makeFetchStub({ ok: true, status: 200, body: {} });
  const restore = setupGlobalFetch(stub);
  try {
    await api.reviewAppeal('a1', { decision: 'APPROVED', note: 'cleared' });
    const { url, init } = stub.calls[0];
    assert.match(url, /\/appeals\/a1\/review$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { decision: 'APPROVED', note: 'cleared' });
  } finally {
    restore();
  }
});

test('apiFetch surfaces error.status on non-2xx so the UI can detect 403 self-review', async () => {
  const stub = async () => ({
    ok: false,
    status: 403,
    text: async () => JSON.stringify({ message: 'cannot review your own case' }),
  });
  const restore = setupGlobalFetch(stub);
  try {
    await assert.rejects(
      () => apiFetch('/appeals/a1/review', { method: 'POST', body: '{}' }),
      (err) => err.status === 403 && /own case/.test(err.message)
    );
  } finally {
    restore();
  }
});
