import test, { afterEach, mock } from 'node:test';
import assert from 'node:assert/strict';
import auth from './auth.js';
import { apiFetch, getMetrics } from './api.js';
import { setConnectionUrls, clearConnectionUrls } from './connectionUrls.js';

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
};
const response = (status, body = {}) => new Response(JSON.stringify(body), { status });
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
afterEach(async () => {
  await auth.logout('/api', { revoke: false });
  clearConnectionUrls();
  mock.restoreAll();
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage);
  else delete globalThis.localStorage;
});

for (const method of ['GET', 'DELETE', 'metrics']) {
  test(`${method}: an old account's 401 cannot refresh or retry as the new account`, async () => {
    const pending = deferred();
    const fetch = mock.method(globalThis, 'fetch', () => pending.promise);
    auth.loginWithToken('access-a', { username: 'alice' }, 'refresh-a');
    const request = method === 'metrics' ? getMetrics() : apiFetch('/channels/old-target', { method });
    const rejected = assert.rejects(request, { code: 'AUTH_SESSION_CHANGED' });
    await auth.logout('/api', { revoke: false });
    auth.loginWithToken('access-b', { username: 'bob' }, 'refresh-b');
    pending.resolve(response(401));
    await rejected;
    assert.equal(fetch.mock.callCount(), 1);
    assert.equal(auth.getToken(), 'access-b');
  });
}

test('logout cancels a late successful response body', async () => {
  const body = deferred();
  mock.method(globalThis, 'fetch', async () => ({ status: 200, ok: true, text: () => body.promise }));
  auth.loginWithToken('access-a', { username: 'alice' }, 'refresh-a');
  const request = apiFetch('/players');
  const rejected = assert.rejects(request, { code: 'AUTH_SESSION_CHANGED' });
  await Promise.resolve();
  await auth.logout('/api', { revoke: false });
  body.resolve(JSON.stringify({ players: ['old-player'] }));
  await rejected;
});

test('switching backend away and back cancels an in-flight request', async () => {
  const values = new Map();
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: (key) => values.delete(key),
  } });
  const pending = deferred();
  const fetch = mock.method(globalThis, 'fetch', () => pending.promise);
  const request = apiFetch('/players');
  const rejected = assert.rejects(request, { code: 'AUTH_SESSION_CHANGED' });
  setConnectionUrls('http://localhost:8890/api', null);
  clearConnectionUrls();
  pending.resolve(response(401));
  await rejected;
  assert.equal(fetch.mock.callCount(), 1);
});

test('a refresh in the same login session retries once with the rotated token', async () => {
  const headers = [];
  mock.method(globalThis, 'fetch', async (_url, options) => {
    headers.push(options.headers.Authorization);
    return headers.length === 1 ? response(401) : response(200, { success: true });
  });
  mock.method(auth, 'fetchImpl', async () => response(200, { token: 'rotated', refreshToken: 'refresh-rotated' }));
  auth.loginWithToken('access-a', { username: 'alice' }, 'refresh-a');
  const session = auth.getSessionId();
  assert.deepEqual(await apiFetch('/channels', { method: 'POST' }), { success: true });
  assert.deepEqual(headers, ['Bearer access-a', 'Bearer rotated']);
  assert.equal(auth.getSessionId(), session);
});

for (const status of [200, 401]) {
  test(`changing account while refreshing (${status}) preserves the new account`, async () => {
    const pending = deferred();
    const entered = deferred();
    const fetch = mock.method(globalThis, 'fetch', async () => response(401));
    mock.method(auth, 'fetchImpl', () => { entered.resolve(); return pending.promise; });
    auth.loginWithToken('access-a', { username: 'alice' }, 'refresh-a');
    const request = apiFetch('/channels', { method: 'DELETE' });
    const rejected = assert.rejects(request, { code: 'AUTH_SESSION_CHANGED' });
    await entered.promise;
    auth.loginWithToken('access-b', { username: 'bob' }, 'refresh-b');
    pending.resolve(response(status, { token: 'rotated-a', refreshToken: 'refresh-rotated-a' }));
    await rejected;
    assert.equal(auth.getToken(), 'access-b');
    assert.equal(fetch.mock.callCount(), 1);
  });
}

test('a rejected old refresh cannot log out the new account', async () => {
  const pending = deferred(), entered = deferred();
  mock.method(globalThis, 'fetch', async () => response(401));
  mock.method(auth, 'fetchImpl', () => { entered.resolve(); return pending.promise; });
  auth.loginWithToken('access-a', { username: 'alice' }, 'refresh-a');
  const request = apiFetch('/players');
  const rejected = assert.rejects(request, { code: 'AUTH_SESSION_CHANGED' });
  await entered.promise;
  auth.loginWithToken('access-b', { username: 'bob' }, 'refresh-b');
  pending.reject(new Error('Network closed'));
  await rejected;
  assert.equal(auth.getToken(), 'access-b');
});

test('an old transport failure is cancelled after the account changes', async () => {
  const pending = deferred();
  mock.method(globalThis, 'fetch', () => pending.promise);
  auth.loginWithToken('access-a', { username: 'alice' }, 'refresh-a');
  const rejected = assert.rejects(apiFetch('/players'), { code: 'AUTH_SESSION_CHANGED' });
  auth.loginWithToken('access-b', { username: 'bob' }, 'refresh-b');
  pending.reject(new Error('Old network failure'));
  await rejected;
  assert.equal(auth.getToken(), 'access-b');
});

test('an old metrics body failure is cancelled after the account changes', async () => {
  const body = deferred(), reading = deferred();
  mock.method(globalThis, 'fetch', async () => ({
    status: 200, ok: true, text() { reading.resolve(); return body.promise; },
  }));
  auth.loginWithToken('access-a', { username: 'alice' }, 'refresh-a');
  const rejected = assert.rejects(getMetrics(), { code: 'AUTH_SESSION_CHANGED' });
  await reading.promise;
  auth.loginWithToken('access-b', { username: 'bob' }, 'refresh-b');
  body.reject(new Error('Old body failure'));
  await rejected;
  assert.equal(auth.getToken(), 'access-b');
});
