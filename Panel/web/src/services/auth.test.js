import test from 'node:test';
import assert from 'node:assert/strict';

import { AuthRequestError, AuthService } from './auth.js';

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key),
  };
}

function jsonResponse(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body),
  };
}

function deferred() {
  let resolve;
  const promise = new Promise((settle) => { resolve = settle; });
  return { promise, resolve };
}

test('concurrent refresh callers share one rotating-token request', async () => {
  const gate = deferred();
  let fetchCount = 0;
  const service = new AuthService({
    storage: memoryStorage(),
    syncWindow: null,
    autoRefresh: false,
    fetchImpl: async () => {
      fetchCount += 1;
      return gate.promise;
    },
  });
  service.loginWithToken('access-old', { role: 'ADMIN' }, 'refresh-old');

  const first = service.refreshAccessToken('/api');
  const second = service.refreshAccessToken('/api');
  const third = service.refreshAccessToken('/api');

  assert.equal(first, second);
  assert.equal(second, third);
  assert.equal(fetchCount, 1);

  gate.resolve(jsonResponse(200, {
    token: 'access-new',
    refreshToken: 'refresh-new',
  }));
  assert.deepEqual(await Promise.all([first, second, third]), [
    'access-new',
    'access-new',
    'access-new',
  ]);
  assert.equal(service.getToken(), 'access-new');
  assert.equal(service.getRefreshToken(), 'refresh-new');
  service.destroy();
});

test('refresh failure preserves the server error and logs out exactly once', async () => {
  let fetchCount = 0;
  const reasons = [];
  const service = new AuthService({
    storage: memoryStorage(),
    syncWindow: null,
    autoRefresh: false,
    fetchImpl: async () => {
      fetchCount += 1;
      return jsonResponse(409, { error: 'refresh token was already rotated', code: 'TOKEN_REUSED' });
    },
  });
  service.loginWithToken('access-old', { role: 'ADMIN' }, 'refresh-old');
  service.onAuthChange(({ reason }) => reasons.push(reason));

  const results = await Promise.allSettled([
    service.refreshAccessToken('/api'),
    service.refreshAccessToken('/api'),
    service.refreshAccessToken('/api'),
  ]);

  assert.equal(fetchCount, 1);
  assert.deepEqual(reasons, ['refresh_failed']);
  assert.equal(service.getToken(), null);
  assert.equal(service.getRefreshToken(), null);
  for (const result of results) {
    assert.equal(result.status, 'rejected');
    assert.ok(result.reason instanceof AuthRequestError);
    assert.equal(result.reason.message, 'refresh token was already rotated');
    assert.equal(result.reason.status, 409);
    assert.deepEqual(result.reason.data, {
      error: 'refresh token was already rotated',
      code: 'TOKEN_REUSED',
    });
    assert.equal(result.reason, results[0].reason);
  }
  service.destroy();
});
