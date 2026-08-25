import test from 'node:test';
import assert from 'node:assert/strict';

// §11.6 item 20 / 提案 10 doc-deferred sub-items — API client contract tests
// for the nine SUPER_ADMIN-only /api/settings/* endpoints (drafts CRUD +
// approve + publish + backup create/list/restore). These assert the request
// shape (path + method + body) the frontend promises the backend, per the
// locked REST contract. They do NOT hit a live backend — fetch is stubbed so
// we can inspect the outgoing call. Mirrors api.moderation.test.js.
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

test('listDrafts: GET /settings/drafts?limit= (default 50)', async () => {
  const stub = makeFetchStub({ ok: true, status: 200, body: [{ draftId: 'd1', status: 'DRAFT' }] });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.listDrafts();
    const { url, init } = stub.calls[0];
    assert.match(url, /\/settings\/drafts\?limit=50$/);
    assert.equal(init.method, undefined, 'GET has no method');
    assert.ok(Array.isArray(res), 'returns the parsed array');
    assert.equal(res[0].draftId, 'd1');
  } finally {
    restore();
  }
});

test('listDrafts: custom limit is honored', async () => {
  const stub = makeFetchStub();
  const restore = setupGlobalFetch(stub);
  try {
    await api.listDrafts(10);
    assert.match(stub.calls[0].url, /limit=10$/);
  } finally {
    restore();
  }
});

test('createDraft: POST /settings/drafts with { yaml }', async () => {
  const stub = makeFetchStub({
    ok: true, status: 201,
    body: { draftId: 'd1', status: 'DRAFT', validation: { valid: true, errors: [], warnings: [] } },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.createDraft('server:\n  name: NovaLink\n');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/settings\/drafts$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { yaml: 'server:\n  name: NovaLink\n' });
    assert.equal(res.draftId, 'd1');
    assert.equal(res.status, 'DRAFT');
  } finally {
    restore();
  }
});

test('getDraft: GET /settings/drafts/{id} (id encoded)', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { draftId: 'd/1', status: 'DRAFT', draft_yaml: 'server:\n  name: x\n' },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.getDraft('d/1');
    const { url } = stub.calls[0];
    assert.match(url, /\/settings\/drafts\/d%2F1$/);
    assert.equal(res.draftId, 'd/1');
  } finally {
    restore();
  }
});

test('approveDraft: POST /settings/drafts/{id}/approve with { note }', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { draftId: 'd1', status: 'APPROVED', approvedBy: 'root', approvedAt: 1 },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.approveDraft('d1', 'lgtm');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/settings\/drafts\/d1\/approve$/);
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
    text: async () => JSON.stringify({ message: 'approver must differ from creator' }),
  });
  const restore = setupGlobalFetch(stub);
  try {
    await assert.rejects(
      () => api.approveDraft('d1', 'note'),
      (err) => err.status === 403
    );
  } finally {
    restore();
  }
});

test('publishDraft: POST /settings/drafts/{id}/publish (no body)', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { revision: 42, backupId: 'b1', publishedAt: 1 },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.publishDraft('d1');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/settings\/drafts\/d1\/publish$/);
    assert.equal(init.method, 'POST');
    assert.equal(res.revision, 42);
    assert.equal(res.backupId, 'b1');
  } finally {
    restore();
  }
});

test('publishDraft: 409 surfaces as error.status so the UI can detect not-approved', async () => {
  const stub = async () => ({
    ok: false,
    status: 409,
    text: async () => JSON.stringify({ message: 'draft is not APPROVED' }),
  });
  const restore = setupGlobalFetch(stub);
  try {
    await assert.rejects(
      () => api.publishDraft('d1'),
      (err) => err.status === 409
    );
  } finally {
    restore();
  }
});

test('discardDraft: DELETE /settings/drafts/{id}', async () => {
  // 204 No Content: the stub returns body:null -> text: '{}' -> apiFetch parses
  // to {}. The real backend returns an empty body; apiFetch resolves that to
  // null. Here we assert the method + path (the response shape is apiFetch's
  // concern, tested separately).
  const stub = makeFetchStub({ ok: true, status: 204, body: {} });
  const restore = setupGlobalFetch(stub);
  try {
    await api.discardDraft('d1');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/settings\/drafts\/d1$/);
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

test('createBackup: POST /settings/backup with { label }', async () => {
  const stub = makeFetchStub({
    ok: true, status: 201,
    body: { backupId: 'b1', label: 'pre-deploy', revision: 7, createdAt: 1, createdBy: 'root' },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.createBackup('pre-deploy');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/settings\/backup$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { label: 'pre-deploy' });
    assert.equal(res.backupId, 'b1');
    assert.equal(res.revision, 7);
  } finally {
    restore();
  }
});

test('createBackup: undefined label is omitted by JSON.stringify (backend treats as unlabeled)', async () => {
  const stub = makeFetchStub();
  const restore = setupGlobalFetch(stub);
  try {
    await api.createBackup(undefined);
    // JSON.stringify({ label: undefined }) === '{}' (the key is dropped).
    assert.deepEqual(JSON.parse(stub.calls[0].init.body), {}, 'undefined label -> empty object');
  } finally {
    restore();
  }
});

test('listBackups: GET /settings/backups?limit= (default 50)', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: [{ backupId: 'b1', label: 'pre-deploy', revision: 7, createdAt: 1, createdBy: 'root' }],
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.listBackups();
    const { url, init } = stub.calls[0];
    assert.match(url, /\/settings\/backups\?limit=50$/);
    assert.equal(init.method, undefined, 'GET has no method');
    assert.ok(Array.isArray(res));
    assert.equal(res[0].backupId, 'b1');
  } finally {
    restore();
  }
});

test('restoreFromBackup: POST /settings/restore-from-backup with { backupId }', async () => {
  const stub = makeFetchStub({
    ok: true, status: 200,
    body: { revision: 7, restoredFromBackupId: 'b1' },
  });
  const restore = setupGlobalFetch(stub);
  try {
    const res = await api.restoreFromBackup('b1');
    const { url, init } = stub.calls[0];
    assert.match(url, /\/settings\/restore-from-backup$/);
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { backupId: 'b1' });
    assert.equal(res.revision, 7);
    assert.equal(res.restoredFromBackupId, 'b1');
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

test('all draft/backup paths are prefixed with /settings/ (no accidental /api/ leak)', async () => {
  // apiFetch prepends the API base (default '/api') to every path we pass; the
  // stub captures the *full* URL. We assert each captured URL ends with the
  // /settings/... path the method constructed (the /api prefix is apiFetch's
  // concern, tested separately). This catches a typo that sent a draft/backup
  // method to a non-/settings/ path.
  const stub = makeFetchStub();
  const restore = setupGlobalFetch(stub);
  try {
    await api.listDrafts();
    await api.createDraft('x');
    await api.getDraft('d1');
    await api.approveDraft('d1', 'n');
    await api.publishDraft('d1');
    await api.discardDraft('d1');
    await api.createBackup('lbl');
    await api.listBackups();
    await api.restoreFromBackup('b1');
    const expected = [
      '/settings/drafts?limit=50',
      '/settings/drafts',
      '/settings/drafts/d1',
      '/settings/drafts/d1/approve',
      '/settings/drafts/d1/publish',
      '/settings/drafts/d1',
      '/settings/backup',
      '/settings/backups?limit=50',
      '/settings/restore-from-backup',
    ];
    assert.equal(stub.calls.length, expected.length, 'one call per method');
    for (let i = 0; i < expected.length; i++) {
      assert.ok(
        stub.calls[i].url.endsWith(expected[i]),
        `call ${i} url ${stub.calls[i].url} should end with ${expected[i]}`
      );
    }
  } finally {
    restore();
  }
});

test('apiFetch sends the same auth+content-type headers for drafts as for other endpoints', async () => {
  // We don't assert the Bearer token value (that's auth.js's job); we assert
  // the request carries a Content-Type: application/json header (set by
  // apiFetch) so the backend's SUPER_ADMIN gate can authenticate the caller.
  let captured = null;
  const stub = async (url, init) => {
    captured = { url, init };
    return { ok: true, status: 200, text: async () => JSON.stringify({}) };
  };
  const restore = setupGlobalFetch(stub);
  try {
    await api.createDraft('x');
    assert.ok(captured.init, 'init captured');
    // apiFetch builds the headers object itself (it does NOT use init.headers)
    // and always sets Content-Type: application/json on every call.
    assert.ok(captured.init.headers, 'headers object present');
    // The header name may be canonicalized; assert case-insensitively.
    const ct = captured.init.headers['Content-Type'] || captured.init.headers['content-type'];
    assert.equal(ct, 'application/json', 'Content-Type is JSON');
  } finally {
    restore();
  }
});
