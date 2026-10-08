import test from 'node:test';
import assert from 'node:assert/strict';

// §11.6 item 20 / 提案 10 doc-deferred sub-items — API client contract tests
// for the nine SUPER_ADMIN-only /api/config/* endpoints (drafts CRUD +
// approve + publish + backup create/list/restore). These assert the request
// shape (path + method + body) the frontend promises the backend, per the
// locked REST contract implemented in RestApiHandler.java.
//
// IMPORTANT (P0 修复): an earlier revision of these tests asserted a wrong
// contract — paths under /api/settings/*, list responses as bare arrays, and
// draft/backup rows keyed by draftId/backupId. The backend truth is:
//   - paths:    /api/config/drafts | /api/config/drafts/{id}[/approve|/publish]
//               /api/config/backups | /api/config/restore-from-backup
//   - list:     { items:[...] } envelope, NOT a bare array
//   - row id:   { id, ... } (long), NOT draftId/backupId
//   - payload:  draft / backup are nested *objects* (the parsed, masked YAML→
//               JSON), not strings; "validation" is not a draft field at all
// The api.js client unwraps the items envelope for listDrafts/listBackups so
// callers see plain arrays — these tests assert the wire path and that the
// unwrap is performed.
//
// They do NOT hit a live backend — fetch is stubbed so we can inspect the
// outgoing call. Mirrors api.moderation.test.js.
//
// Requires the _test loader shim (`npm test`) because importing api.js pulls
// in src/i18n.js which uses import.meta.glob (Vite-only).

import { api } from './api.js';

// In-memory fetch stub. Captures every call's url + init so each test can
// assert method/path/body. Returns a configurable Response-like object.
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

// ====================== Drafts ======================

test('listDrafts: GET /config/drafts?limit= (default 50) + unwraps {items} envelope', async () => {
  const stub = makeFetchStub({
    ok: true,
    status: 200,
    body: { items: [{ id: 1, status: 'DRAFT', createdAt: 1, createdBy: 'root' }] },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.listDrafts();
    const { url, init } = stub.calls[0];
    assert.match(url, /\/config\/drafts\?limit=50$/);
    assert.equal(init.method, undefined, 'GET has no method');
    assert.ok(Array.isArray(res), 'returns the unwrapped items array');
    assert.equal(res[0].id, 1);
  } finally {
    restore();
  }
});

test('listDrafts: custom limit is honored', async () => {
  const stub = makeFetchStub({ ok: true, status: 200, body: { items: [] } });
  const restore = setupGlobalFetch(stub);
  try {
    await api.listDrafts(10);
    assert.match(stub.calls[0].url, /limit=10$/);
  } finally {
    restore();
  }
});

test('listDrafts: empty/malformed body resolves to [] (no crash)', async () => {
  const stub = makeFetchStub({ ok: true, status: 200, body: {} });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.listDrafts();
    assert.deepEqual(res, []);
  } finally {
    restore();
  }
});

test('createDraft: POST /config/drafts with { yaml }', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { id: 1, status: 'DRAFT', createdBy: 'root', createdAt: 1, draft: { server: { name: 'NovaLink' } } },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.createDraft('server:\n  name: NovaLink\n');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/config\/drafts$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { yaml: 'server:\n  name: NovaLink\n' });
    assert.equal(res.id, 1);
    assert.equal(res.status, 'DRAFT');
  } finally {
    restore();
  }
});

test('getDraft: GET /config/drafts/{id} (id encoded)', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { id: 12, status: 'DRAFT', createdBy: 'root', createdAt: 1, draft: { server: { name: 'x' } } },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.getDraft('12');
    const { url } = stub.calls[0];
    assert.match(url, /\/config\/drafts\/12$/);
    assert.equal(res.id, 12);
    assert.equal(typeof res.draft, 'object', 'draft payload is a parsed object, not a string');
  } finally {
    restore();
  }
});

test('approveDraft: POST /config/drafts/{id}/approve with { note }', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { id: 1, status: 'APPROVED', createdBy: 'creator', approvedBy: 'root', approvedAt: 2, createdAt: 1 },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.approveDraft('1', 'lgtm');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/config\/drafts\/1\/approve$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { note: 'lgtm' });
    assert.equal(res.status, 'APPROVED');
  } finally {
    restore();
  }
});

test('approveDraft: 403 surfaces as error.status so the UI can detect self-approve', async () => {
  const stub = async () => ({
    ok: false,
    status: 403,
    text: async () => JSON.stringify({ message: 'Approver must differ from createdBy' }),
  });
  const restore = setupGlobalFetch(stub);
  try {
    await assert.rejects(
      () => api.approveDraft('1', 'note'),
      (err) => err.status === 403
    );
  } finally {
    restore();
  }
});

test('publishDraft: POST /config/drafts/{id}/publish (no body)', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { success: true, draftId: 1, revision: 42 },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.publishDraft('1');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/config\/drafts\/1\/publish$/);
    assert.equal(init.method, 'POST');
    assert.equal(res.revision, 42);
    assert.equal(res.success, true);
  } finally {
    restore();
  }
});

test('publishDraft: 409 surfaces as error.status so the UI can detect not-approved', async () => {
  const stub = async () => ({
    ok: false,
    status: 409,
    text: async () => JSON.stringify({ message: 'Draft is not in APPROVED state' }),
  });
  const restore = setupGlobalFetch(stub);
  try {
    await assert.rejects(
      () => api.publishDraft('1'),
      (err) => err.status === 409
    );
  } finally {
    restore();
  }
});

test('discardDraft: DELETE /config/drafts/{id}', async () => {
  // The backend returns 200 {success:true,draftId}; the legacy test used a 204
  // stub but only asserted path + method. Either way, apiFetch never throws on
  // ok:true. Here we keep the stub minimal.
  const stub = makeFetchStub({ ok: true, status: 200, body: { success: true, draftId: 1 } });
  const restore = setupGlobalFetch(stub);
  try {
    await api.discardDraft('1');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/config\/drafts\/1$/);
    assert.equal(init.method, 'DELETE');
  } finally {
    restore();
  }
});

test('createDraft: 400 with validation report resolves to a thrown error carrying the report', async () => {
  const stub = async () => ({
    ok: false,
    status: 400,
    text: async () => JSON.stringify({
      message: 'invalid yaml',
      errors: [{ path: 'server.name', message: 'required' }],
      warnings: [],
    }),
  });
  const restore = setupGlobalFetch(stub);
  try {
    await assert.rejects(
      () => api.createDraft('bad: : :'),
      (err) => err.status === 400 && Array.isArray(err.data?.errors) && err.data.errors[0].path === 'server.name'
    );
  } finally {
    restore();
  }
});

// ====================== Backups ======================

test('createBackup: POST /config/backups with { label }', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { id: 7, label: 'pre-deploy', settingsRevision: 7, createdAt: 1, createdBy: 'root' },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.createBackup('pre-deploy');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/config\/backups$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { label: 'pre-deploy' });
    assert.equal(res.id, 7);
    assert.equal(res.settingsRevision, 7);
  } finally {
    restore();
  }
});

test('listBackups: GET /config/backups?limit= (default 50) + unwraps {items} envelope', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { items: [{ id: 7, label: 'pre-deploy', settingsRevision: 7, createdAt: 1, createdBy: 'root' }] },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.listBackups();
    const { url, init } = stub.calls[0];
    assert.match(url, /\/config\/backups\?limit=50$/);
    assert.equal(init.method, undefined, 'GET has no method');
    assert.ok(Array.isArray(res));
    assert.equal(res[0].id, 7);
  } finally {
    restore();
  }
});

test('restoreFromBackup: POST /config/restore-from-backup with { backupId }', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { success: true, backupId: 7, revision: 9 },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.restoreFromBackup(7);
    const { url, init } = stub.calls[0];
    assert.match(url, /\/config\/restore-from-backup$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { backupId: 7 });
    assert.equal(res.revision, 9);
    assert.equal(res.success, true);
  } finally {
    restore();
  }
});

// ====================== Defense-in-depth ======================

test('all nine methods exist on the api object (wiring smoke)', () => {
  const methods = [
    'listDrafts', 'createDraft', 'getDraft', 'approveDraft', 'publishDraft', 'discardDraft',
    'createBackup', 'listBackups', 'restoreFromBackup',
  ];
  for (const m of methods) {
    assert.equal(typeof api[m], 'function', `api.${m} is a function`);
  }
});

test('all draft/backup paths are prefixed with /config/ (no accidental /api/settings/ leak)', async () => {
  // apiFetch prepends the API base (default '/api') to every path we pass; the
  // stub captures the *full* URL. We assert each captured URL ends with the
  // /config/... path the method constructed (the /api prefix is apiFetch's
  // concern, tested separately).
  const stub = makeFetchStub({ ok: true, status: 200, body: { items: [] } });
  const restore = setupGlobalFetch(stub);
  try {
    await api.listDrafts();
    await api.createDraft('x');
    await api.getDraft('1');
    await api.approveDraft('1', 'n');
    await api.publishDraft('1');
    await api.discardDraft('1');
    await api.createBackup('lbl');
    await api.listBackups();
    await api.restoreFromBackup(7);
    const expected = [
      '/config/drafts?limit=50',
      '/config/drafts',
      '/config/drafts/1',
      '/config/drafts/1/approve',
      '/config/drafts/1/publish',
      '/config/drafts/1',
      '/config/backups',
      '/config/backups?limit=50',
      '/config/restore-from-backup',
    ];
    assert.equal(stub.calls.length, expected.length, 'one call per method');
    for (let i = 0; i < expected.length; i++) {
      assert.ok(
        stub.calls[i].url.endsWith(expected[i]),
        `call ${i} url ${stub.calls[i].url} should end with ${expected[i]}`
      );
    }
    // Defense: none of the calls may hit the legacy /settings/draft|backup paths.
    for (const c of stub.calls) {
      assert.ok(
        !/settings\/(drafts|backups?|restore-from-backup)/.test(c.url),
        `url ${c.url} must not hit the legacy /settings/* path`
      );
    }
  } finally {
    restore();
  }
});

test('apiFetch sends the same auth+content-type headers for drafts as for other endpoints', async () => {
  let captured = null;
  const stub = async (url, init) => {
    captured = { url, init };
    return { ok: true, status: 200, text: async () => JSON.stringify({}) };
  };
  const restore = setupGlobalFetch(stub);
  try {
    await api.createDraft('x');
    assert.ok(captured.init, 'init captured');
    assert.ok(captured.init.headers, 'headers object present');
    const ct = captured.init.headers['Content-Type'] || captured.init.headers['content-type'];
    assert.equal(ct, 'application/json', 'Content-Type is JSON');
  } finally {
    restore();
  }
});
