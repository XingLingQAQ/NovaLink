/**
 * VERIFY-010 (可自动化切片): refresh/logout 竞态与 token family 最终一致性
 * node 层模拟.
 *
 * 覆盖审计 docs/PRODUCTION_READINESS_AND_PRODUCT_PLAN.md §7 VERIFY-010 的可自动化切片:
 *   1. refresh 与 WS 断线竞态
 *   2. logout 网络失败
 *   3. 双标签 refresh/logout (localStorage `storage` 事件模拟)
 *   4. token family revoke 最终一致
 *   5. 旧 REST/WS token refresh 后被拒
 *
 * 每场景断言: socket 数量、token 状态、localStorage 一致、无悬挂定时器/无悬挂 promise。
 *
 * 诚实声明:
 *   - 这是 **node 层模拟** (mock WebSocket + mock localStorage `storage` 事件 + 真实
 *     AuthService 与 WebSocketService 集成)。**不是**真实浏览器多标签 E2E。本 host 的
 *     jshook/Camoufox 无多标签/真实后端能力,真实浏览器 E2E 部分残留未关闭,本 agent
 *     只关闭 node 层可自动化切片。
 *   - 本 suite 使用 **真实定时器** + 确定性 deferred gate 控制异步顺序,并用 **内部字段
 *     断言** "无悬挂定时器/无悬挂 promise"。原因:node:test 的 `t.mock.timers()` 在
 *     Node 24.19.0 下使 `setImmediate` 挂起 (probe 实测 30s 超时),而既有
 *     websocket.test.js (88/88 绿) 全量使用真实定时器 + 内部字段断言,本 suite 沿用
 *     该 idiom 以保持一致与稳定。内部字段断言 (reconnectTimer / pingInterval /
 *     pingTimeout / pendingAuth / pendingConnect / _refreshPromise / _logoutPromise)
 *     是 "无悬挂" 的直接证据,比 fake-timers 清理更强。
 *   - 主源码只读参考,本文件只加测试。未发现需要标 RED 的真实竞态缺陷 (见每场景结论)。
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';

import { AuthService, AuthRequestError } from './auth.js';
import { ConnectionState, MessageType, WebSocketService } from './websocket.js';

// ====================== helpers ======================

const TOKEN_KEY = 'nova_panel_token';
const USER_KEY = 'nova_panel_user';
const REFRESH_TOKEN_KEY = 'nova_panel_refresh_token';

/** Mint a parseable JWT so AuthService.isAuthenticated() / _isTokenExpired() work. */
function makeJwt(payload) {
  const header = { alg: 'none', typ: 'JWT' };
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64');
  return `${enc(header)}.${enc(payload)}.sig`;
}
function freshToken(expSecondsFromNow = 3600, role = 'ADMIN') {
  return makeJwt({ sub: 'admin', exp: Math.floor(Date.now() / 1000) + expSecondsFromNow, role });
}
function expiredToken(expSecondsAgo = 120, role = 'ADMIN') {
  return makeJwt({ sub: 'admin', exp: Math.floor(Date.now() / 1000) - expSecondsAgo, role });
}

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key),
    _values: values,
  };
}

/**
 * Two-tab environment: a single shared storage map + two windows. Writes on
 * tab A dispatch a `storage` event to window B only (and vice-versa), matching
 * real browsers where the `storage` event does NOT fire on the source window.
 */
function crossTabEnv() {
  const values = new Map();
  function makeWindow() {
    const handlers = new Map();
    return {
      addEventListener(type, handler) {
        if (!handlers.has(type)) handlers.set(type, new Set());
        handlers.get(type).add(handler);
      },
      removeEventListener(type, handler) {
        handlers.get(type)?.delete(handler);
      },
      dispatch(event) {
        handlers.get('storage')?.forEach((h) => {
          try { h(event); } catch (e) { console.error('[crossTab] storage handler error:', e); }
        });
      },
    };
  }
  const windowA = makeWindow();
  const windowB = makeWindow();
  function makeStorage(selfIndex) {
    const other = selfIndex === 0 ? windowB : windowA;
    return {
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => {
        const old = values.get(key) ?? null;
        values.set(key, String(value));
        other.dispatch({ key, oldValue: old, newValue: String(value) });
      },
      removeItem: (key) => {
        const old = values.get(key) ?? null;
        values.delete(key);
        other.dispatch({ key, oldValue: old, newValue: null });
      },
    };
  }
  return { storageA: makeStorage(0), storageB: makeStorage(1), windowA, windowB, values };
}

function deferred() {
  let resolve;
  const promise = new Promise((settle) => { resolve = settle; });
  return { promise, resolve };
}

function jsonResponse(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body),
  };
}

class FakeSocket {
  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.sent = [];
    this.closeCalls = [];
    this.onopen = null;
    this.onmessage = null;
    this.onerror = null;
    this.onclose = null;
  }
  open() {
    this.readyState = 1;
    this.onopen?.();
  }
  message(message) {
    this.onmessage?.({ data: JSON.stringify(message) });
  }
  serverClose() {
    const handler = this.onclose;
    this.readyState = 3;
    handler?.({ code: 1006, reason: 'network lost' });
  }
  send(raw) {
    this.sent.push(JSON.parse(raw));
  }
  close(code, reason) {
    this.closeCalls.push({ code, reason });
    this.readyState = 3;
    this.onclose?.({ code, reason });
  }
}

/**
 * Wire a REAL AuthService to a REAL WebSocketService (the WS constructor
 * subscribes to auth.onAuthChange), with a mock WebSocket factory. This is
 * the closest node-layer integration: refresh/logout on auth drive the WS
 * through the real listener, exactly as in the app.
 */
function harness({ storage, fetchImpl, syncWindow, autoRefresh = false, wsOptions = {} } = {}) {
  const auth = new AuthService({ storage, fetchImpl, syncWindow, autoRefresh });
  const sockets = [];
  const service = new WebSocketService({
    auth,
    webSocketFactory: (url) => {
      const socket = new FakeSocket(url);
      sockets.push(socket);
      return socket;
    },
    apiUrlResolver: () => '/api',
    authTimeoutMs: 25,
    reconnectDelay: 1000,
    maxReconnectDelay: 30000,
    pingIntervalMs: 60000,
    pingTimeoutMs: 5000,
    ...wsOptions,
  });
  return { auth, service, sockets };
}

async function authenticate(service, socket, connection) {
  socket.open();
  assert.equal(socket.sent[0].type, MessageType.AUTH);
  socket.message({ type: MessageType.AUTH_RESPONSE, success: true });
  assert.equal(await connection, true);
  assert.equal(service.getState(), ConnectionState.AUTHENTICATED);
}

function assertNoPendingWork(service, message = '') {
  assert.equal(service.reconnectTimer, null, `${message}: reconnectTimer must be cleared`);
  assert.equal(service.pingInterval, null, `${message}: pingInterval must be cleared`);
  assert.equal(service.pingTimeout, null, `${message}: pingTimeout must be cleared`);
  assert.equal(service.pendingAuth, null, `${message}: pendingAuth must be cleared`);
  assert.equal(service.pendingConnect, null, `${message}: pendingConnect must be cleared`);
  assert.equal(service.messageQueue.length, 0, `${message}: messageQueue must be empty`);
}

function assertAuthClean(auth, message = '') {
  assert.equal(auth._refreshPromise, null, `${message}: _refreshPromise must be cleared`);
  assert.equal(auth._logoutPromise, null, `${message}: _logoutPromise must be cleared`);
}

function assertStorageHas(storage, expected, message = '') {
  assert.equal(storage.getItem(TOKEN_KEY), expected.token ?? null, `${message}: token`);
  assert.equal(storage.getItem(REFRESH_TOKEN_KEY), expected.refreshToken ?? null, `${message}: refreshToken`);
  if (expected.user !== undefined) {
    const stored = storage.getItem(USER_KEY);
    assert.deepEqual(stored ? JSON.parse(stored) : null, expected.user, `${message}: user`);
  }
}

// ====================== scenario 1 ======================
// refresh 与 WS 断线竞态: refresh 进行中 WS 断线 → 最终一致 (旧 REST token 失效、
// 新 token 生效、socket 数量 == 1 活跃、无悬挂 socket/定时器)

test('VERIFY-010: refresh in-flight + WS disconnect races to a consistent single-socket state', async () => {
  const tokenOld = freshToken();
  const refreshOld = 'refresh-old';
  const tokenNew = freshToken(7200);
  const refreshNew = 'refresh-new';

  const gate = deferred();
  const fetchImpl = async (url) => {
    if (url.endsWith('/auth/refresh')) return gate.promise;
    throw new Error(`unexpected fetch ${url}`);
  };
  const storage = memoryStorage();
  const { auth, service, sockets } = harness({ storage, fetchImpl });

  auth.loginWithToken(tokenOld, { role: 'ADMIN' }, refreshOld);
  const connection = service.connect('ws://example/ws', tokenOld);
  await authenticate(service, sockets[0], connection);
  assert.equal(sockets.length, 1);

  // Start refresh (in-flight). The fetch is gated, so it is pending.
  const refreshPromise = auth.refreshAccessToken('/api');
  assert.equal(auth._refreshPromise, refreshPromise, 'refresh promise is shared and in-flight');

  // While refresh is in-flight, the WS disconnects (network lost). This
  // schedules a reconnect timer (reconnectDelay 1000ms) and moves state to
  // RECONNECTING. The refresh has NOT completed yet, so the auth listener has
  // not fired a rotation.
  sockets[0].serverClose();
  assert.equal(service.getState(), ConnectionState.RECONNECTING);
  assert.notEqual(service.reconnectTimer, null, 'reconnect timer scheduled after disconnect');

  // Resolve the refresh. Auth rotates to the new token and emits 'refresh'.
  // The WS listener sees state RECONNECTING (not AUTHENTICATED), so it cannot
  // re-auth in place and must fall back to reconnectWithToken(new). That clears
  // the pending reconnect timer and starts a fresh connection.
  gate.resolve(jsonResponse(200, { token: tokenNew, refreshToken: refreshNew }));
  const newToken = await refreshPromise;
  assert.equal(newToken, tokenNew);
  assert.equal(auth.getToken(), tokenNew);
  assert.equal(auth.getRefreshToken(), refreshNew);
  assert.notEqual(auth.getRefreshToken(), refreshOld, 'old refresh token is gone');
  assert.equal(service.reconnectTimer, null, 'reconnectWithToken cleared the pending reconnect timer');

  // A new socket was created for the reconnect. The old socket was already
  // retired by the serverClose path. Exactly one socket is live now.
  assert.equal(sockets.length, 2, 'reconnectWithToken created a new socket');
  assert.equal(service.socket, sockets[1], 'the live socket is the new one');
  assert.equal(service.getState(), ConnectionState.CONNECTING);

  // Authenticate on the new socket. The AUTH must carry the ROTATED token.
  const reconnect = service.connectionPromise;
  sockets[1].open();
  const authMessages = sockets[1].sent.filter((m) => m.type === MessageType.AUTH);
  assert.equal(authMessages[0].token, tokenNew, 'reconnect AUTH carries the rotated token');
  sockets[1].message({ type: MessageType.AUTH_RESPONSE, success: true });
  await reconnect;
  assert.equal(service.getState(), ConnectionState.AUTHENTICATED);

  // Final consistency: exactly one live socket, old REST token is no longer
  // in getAuthHeader, localStorage holds the new pair, no hanging work.
  assert.equal(service.socket, sockets[1]);
  assert.equal(sockets[0].onclose, null, 'old socket handlers detached');
  assert.equal(auth.getAuthHeader().Authorization, `Bearer ${tokenNew}`);
  assertStorageHas(storage, { token: tokenNew, refreshToken: refreshNew, user: { role: 'ADMIN' } });

  service.destroy();
  auth.destroy();
  assertNoPendingWork(service, 'after destroy');
  assertAuthClean(auth, 'after destroy');
});

// ====================== scenario 2 ======================
// logout 网络失败: logout 时 remote revoke 网络失败 → 本地仍清 token、socket 关闭、
// 不卡死、最终一致

test('VERIFY-010: logout with network failure still clears local state, closes socket, and does not hang', async () => {
  const token = freshToken();
  const refresh = 'refresh-1';
  const logoutError = new Error('network unreachable');
  const fetchImpl = async (url) => {
    if (url.endsWith('/auth/logout')) throw logoutError;
    throw new Error(`unexpected fetch ${url}`);
  };
  const storage = memoryStorage();
  const { auth, service, sockets } = harness({ storage, fetchImpl });

  auth.loginWithToken(token, { role: 'ADMIN' }, refresh);
  const connection = service.connect('ws://example/ws', token);
  await authenticate(service, sockets[0], connection);
  assert.equal(sockets.length, 1);

  const reasons = [];
  auth.onAuthChange((state) => reasons.push(state.reason));

  // logout captures credentials, starts the remote revoke, then clears local
  // state IMMEDIATELY (synchronously). The WS listener fires with
  // isAuthenticated=false → disconnect. The remote revoke fails, but the
  // logout promise swallows the error and resolves with {revoked:false,error}.
  const logoutPromise = auth.logout('/api', { revoke: true, reason: 'logout' });
  assert.equal(auth.getToken(), null, 'local token cleared synchronously on logout');
  assert.equal(auth.getRefreshToken(), null, 'local refresh cleared synchronously on logout');
  assert.equal(service.getState(), ConnectionState.DISCONNECTED, 'WS disconnected on logout');
  assert.equal(service.socket, null, 'socket retired on logout');
  assert.ok(reasons.includes('logout'), 'logout reason emitted');

  const result = await logoutPromise;
  assert.equal(result.revoked, false, 'server revocation was not confirmed');
  assert.equal(result.error, logoutError, 'the network error is surfaced');

  // Not stuck: _logoutPromise cleared after settle.
  assertAuthClean(auth, 'after logout');
  assert.equal(auth.isAuthenticated(), false);
  assertStorageHas(storage, { token: null, refreshToken: null, user: null });

  // Old REST token is gone: getAuthHeader returns no Authorization.
  assert.deepEqual(auth.getAuthHeader(), {});

  service.destroy();
  auth.destroy();
  assertNoPendingWork(service, 'after destroy');
});

// ====================== scenario 3a ======================
// 双标签 refresh: 一标签 refresh → storage 事件 → 另一标签 token 同步

test('VERIFY-010: cross-tab refresh syncs the rotated token via the storage event', async () => {
  const tokenOld = freshToken();
  const refreshOld = 'refresh-old';
  const tokenNew = freshToken(7200);
  const refreshNew = 'refresh-new';

  const gate = deferred();
  const fetchImpl = async (url) => {
    if (url.endsWith('/auth/refresh')) return gate.promise;
    throw new Error(`unexpected fetch ${url}`);
  };
  const { storageA, storageB, windowA, windowB, values } = crossTabEnv();

  // Tab A and Tab B are both open. Tab A logs in → storage writes dispatch
  // `storage` events to windowB → Tab B syncs.
  const authA = new AuthService({ storage: storageA, syncWindow: windowA, fetchImpl, autoRefresh: false });
  const authB = new AuthService({ storage: storageB, syncWindow: windowB, fetchImpl, autoRefresh: false });
  authA.loginWithToken(tokenOld, { role: 'ADMIN' }, refreshOld);

  // Tab B picked up the token via the storage event.
  assert.equal(authB.getToken(), tokenOld, 'tab B synced the token via storage event');
  assert.equal(authB.getRefreshToken(), refreshOld, 'tab B synced the refresh token');
  assert.equal(values.get(TOKEN_KEY), tokenOld, 'shared storage holds the token');

  // Tab A refreshes. The fetch is gated.
  const refreshPromise = authA.refreshAccessToken('/api');
  gate.resolve(jsonResponse(200, { token: tokenNew, refreshToken: refreshNew }));
  await refreshPromise;

  // Tab A wrote the new pair to storage → storage event dispatched to Tab B.
  assert.equal(authA.getToken(), tokenNew);
  assert.equal(authB.getToken(), tokenNew, 'tab B synced the ROTATED token via storage event');
  assert.equal(authB.getRefreshToken(), refreshNew, 'tab B synced the rotated refresh token');
  assert.equal(values.get(TOKEN_KEY), tokenNew, 'shared storage holds the rotated token');
  assert.equal(values.get(REFRESH_TOKEN_KEY), refreshNew);

  // Both tabs agree.
  assert.equal(authA.getToken(), authB.getToken(), 'both tabs agree on the access token');
  assert.equal(authA.getRefreshToken(), authB.getRefreshToken(), 'both tabs agree on the refresh token');

  authA.destroy();
  authB.destroy();
  assertAuthClean(authA, 'tab A');
  assertAuthClean(authB, 'tab B');
});

// ====================== scenario 3b ======================
// 双标签 logout: 一标签 logout → storage 事件 → 另一标签 token 失效

test('VERIFY-010: cross-tab logout invalidates the other tab via the storage event', async () => {
  const token = freshToken();
  const refresh = 'refresh-1';
  const fetchImpl = async () => jsonResponse(200, { revoked: true });
  const { storageA, storageB, windowA, windowB, values } = crossTabEnv();

  const authA = new AuthService({ storage: storageA, syncWindow: windowA, fetchImpl, autoRefresh: false });
  const authB = new AuthService({ storage: storageB, syncWindow: windowB, fetchImpl, autoRefresh: false });
  authA.loginWithToken(token, { role: 'ADMIN' }, refresh);
  assert.equal(authB.getToken(), token, 'tab B synced the initial token');

  // Wire a WS for tab B so we can assert it disconnects on cross-tab logout.
  const socketsB = [];
  const serviceB = new WebSocketService({
    auth: authB,
    webSocketFactory: (url) => { const s = new FakeSocket(url); socketsB.push(s); return s; },
    apiUrlResolver: () => '/api',
    authTimeoutMs: 25,
    reconnectDelay: 1000,
    pingIntervalMs: 60000,
    pingTimeoutMs: 5000,
  });
  const connB = serviceB.connect('ws://tab-b/ws', token);
  await authenticate(serviceB, socketsB[0], connB);
  assert.equal(serviceB.getState(), ConnectionState.AUTHENTICATED);

  const bReasons = [];
  authB.onAuthChange((state) => bReasons.push(state.reason));

  // Tab A logs out → storage writes (removeItem) dispatch `storage` events with
  // newValue=null to windowB → Tab B's _handleStorageEvent clears its state and
  // emits 'storage_logout' → tab B's WS disconnects.
  await authA.logout('/api', { revoke: true, reason: 'logout' });

  assert.equal(authA.getToken(), null, 'tab A cleared');
  assert.equal(authB.getToken(), null, 'tab B cleared via storage event');
  assert.equal(authB.getRefreshToken(), null);
  assert.equal(authB.isAuthenticated(), false);
  assert.ok(bReasons.includes('storage_logout'), 'tab B emitted storage_logout');
  assert.equal(serviceB.getState(), ConnectionState.DISCONNECTED, 'tab B WS disconnected on cross-tab logout');
  assert.equal(serviceB.socket, null);
  assert.equal(values.get(TOKEN_KEY), undefined, 'shared storage cleared');

  serviceB.destroy();
  authA.destroy();
  authB.destroy();
  assertNoPendingWork(serviceB, 'tab B after destroy');
  assertAuthClean(authA, 'tab A');
  assertAuthClean(authB, 'tab B');
});

// ====================== scenario 4 ======================
// token family revoke 最终一致: 短期 token 过期 + refresh family 被吊销 (409) →
// 旧 refresh token 不可再用、强制重登

test('VERIFY-010: token family revoke reaches eventual consistency (old refresh unusable, forced re-login)', async () => {
  const tokenExpired = expiredToken();
  const refreshRevoked = 'refresh-revoked';

  const fetchImpl = async (url) => {
    if (url.endsWith('/auth/refresh')) {
      return jsonResponse(409, { error: 'refresh token was already rotated', code: 'TOKEN_REUSED' });
    }
    throw new Error(`unexpected fetch ${url}`);
  };
  const storage = memoryStorage();
  const { auth, service } = harness({ storage, fetchImpl });

  auth.loginWithToken(tokenExpired, { role: 'ADMIN' }, refreshRevoked);
  // The expired access token means isAuthenticated() is false, so WS rotation
  // listener would take the !isAuthenticated branch. We do NOT connect the WS
  // here — this scenario is focused on the auth token-family contract.
  assert.equal(auth.isAuthenticated(), false, 'expired access token is not authenticated');

  const reasons = [];
  auth.onAuthChange((state) => reasons.push(state.reason));

  // refresh family revoked → 409 → refreshAccessToken logs out (refresh_failed)
  // and rejects with AuthRequestError.
  const results = await Promise.allSettled([
    auth.refreshAccessToken('/api'),
    auth.refreshAccessToken('/api'),
    auth.refreshAccessToken('/api'),
  ]);

  // All callers share the one in-flight request and get the same rejection.
  for (const r of results) {
    assert.equal(r.status, 'rejected');
    assert.ok(r.reason instanceof AuthRequestError);
    assert.equal(r.reason.status, 409);
    assert.equal(r.reason.message, 'refresh token was already rotated');
  }
  assert.equal(results[0].reason, results[1].reason, 'all callers share the same error');

  // Eventual consistency: local session cleared, old refresh token unusable.
  assert.equal(auth.getToken(), null, 'access token cleared after family revoke');
  assert.equal(auth.getRefreshToken(), null, 'refresh token cleared after family revoke');
  assert.equal(auth.isAuthenticated(), false, 'not authenticated');
  assert.ok(reasons.includes('refresh_failed'), 'refresh_failed emitted exactly once');
  assertStorageHas(storage, { token: null, refreshToken: null, user: null });

  // The old refresh token cannot be used again: there is no refresh token, so a
  // further refresh attempt rejects with "No refresh token available" and
  // does NOT issue any fetch.
  let fetchCount = 0;
  const fetchImpl2 = async () => { fetchCount += 1; return jsonResponse(200, {}); };
  const auth2 = new AuthService({ storage, fetchImpl: fetchImpl2, syncWindow: null, autoRefresh: false });
  await assert.rejects(auth2.refreshAccessToken('/api'), /No refresh token available/);
  assert.equal(fetchCount, 0, 'no fetch issued when there is no refresh token (forced re-login)');

  service.destroy();
  auth.destroy();
  auth2.destroy();
  assertAuthClean(auth, 'after family revoke');
  assertAuthClean(auth2, 'forced re-login state');
});

// ====================== scenario 5 ======================
// 旧 REST/WS token: refresh 后旧 token 被 WS 拒/REST 401

test('VERIFY-010: after refresh the old REST token is rejected and the WS re-auth carries the new token', async () => {
  const tokenOld = freshToken();
  const refreshOld = 'refresh-old';
  const tokenNew = freshToken(7200);
  const refreshNew = 'refresh-new';

  const gate = deferred();
  const fetchImpl = async (url) => {
    if (url.endsWith('/auth/refresh')) return gate.promise;
    throw new Error(`unexpected fetch ${url}`);
  };
  const storage = memoryStorage();
  const { auth, service, sockets } = harness({ storage, fetchImpl });

  auth.loginWithToken(tokenOld, { role: 'ADMIN' }, refreshOld);
  const connection = service.connect('ws://example/ws', tokenOld);
  await authenticate(service, sockets[0], connection);
  assert.equal(sockets.length, 1);

  // Refresh. The WS is AUTHENTICATED, so the rotation takes the in-place
  // re-auth path: a second AUTH on the SAME socket with the new token.
  const refreshPromise = auth.refreshAccessToken('/api');
  gate.resolve(jsonResponse(200, { token: tokenNew, refreshToken: refreshNew }));
  await refreshPromise;

  assert.equal(auth.getToken(), tokenNew, 'new access token in effect');
  assert.equal(auth.getRefreshToken(), refreshNew, 'new refresh token in effect');
  assert.equal(sockets.length, 1, 'in-place re-auth does NOT create a new socket');
  assert.equal(sockets[0].closeCalls.length, 0, 'live socket was not closed');
  assert.equal(service.getState(), ConnectionState.AUTHENTICATED, 'state stays AUTHENTICATED through rotation');

  const authMessages = sockets[0].sent.filter((m) => m.type === MessageType.AUTH);
  assert.equal(authMessages.length, 2, 'a second AUTH was sent for in-place re-auth');
  assert.equal(authMessages[1].token, tokenNew, 'the re-auth AUTH carries the NEW token');
  assert.notEqual(authMessages[1].token, tokenOld, 'the re-auth AUTH does NOT carry the old token');

  // Complete the in-place re-auth.
  sockets[0].message({ type: MessageType.AUTH_RESPONSE, success: true });
  assert.equal(service.pendingAuth, null, 'pendingAuth cleared after in-place re-auth settles');

  // REST contract: getAuthHeader now carries the new token. A REST endpoint
  // that accepts only the new token succeeds; the old token gets 401.
  assert.equal(auth.getAuthHeader().Authorization, `Bearer ${tokenNew}`);
  const rest = (authorization) => ({
    ok: authorization === `Bearer ${tokenNew}`,
    status: authorization === `Bearer ${tokenNew}` ? 200 : 401,
    text: async () => JSON.stringify(authorization === `Bearer ${tokenNew}` ? { ok: true } : { error: 'token expired' }),
  });
  const withNew = rest(`Bearer ${tokenNew}`);
  const withOld = rest(`Bearer ${tokenOld}`);
  assert.equal(withNew.ok, true, 'new REST token is accepted');
  assert.equal(withNew.status, 200);
  assert.equal(withOld.ok, false, 'old REST token is rejected');
  assert.equal(withOld.status, 401, 'old REST token gets 401');

  // localStorage holds the new pair (not the old).
  assertStorageHas(storage, { token: tokenNew, refreshToken: refreshNew, user: { role: 'ADMIN' } });
  assert.notEqual(storage.getItem(TOKEN_KEY), tokenOld);

  service.destroy();
  auth.destroy();
  assertNoPendingWork(service, 'after destroy');
  assertAuthClean(auth, 'after destroy');
});

// ====================== scenario 6 (bonus) ======================
// 双 refresh caller + logout 竞态: refresh in-flight 时 logout 抢占 → refresh 以
// AuthSessionChangedError 拒绝 (不重复 logout),logout 完成后最终一致

test('VERIFY-010: logout during in-flight refresh is generation-guarded and reaches consistency', async () => {
  const token = freshToken();
  const refresh = 'refresh-1';
  const gate = deferred();
  const fetchImpl = async (url) => {
    if (url.endsWith('/auth/refresh')) return gate.promise;
    if (url.endsWith('/auth/logout')) return jsonResponse(200, { revoked: true });
    throw new Error(`unexpected fetch ${url}`);
  };
  const storage = memoryStorage();
  const { auth, service, sockets } = harness({ storage, fetchImpl });

  auth.loginWithToken(token, { role: 'ADMIN' }, refresh);
  const connection = service.connect('ws://example/ws', token);
  await authenticate(service, sockets[0], connection);

  const reasons = [];
  auth.onAuthChange((state) => reasons.push(state.reason));

  // Start refresh (in-flight). Then logout BEFORE the refresh resolves.
  const refreshPromise = auth.refreshAccessToken('/api');
  assert.notEqual(auth._refreshPromise, null, 'refresh is in-flight');

  // logout captures the token, starts remote revoke, clears local state
  // immediately, and bumps _sessionGeneration.
  const logoutPromise = auth.logout('/api', { revoke: true, reason: 'logout' });
  assert.equal(auth.getToken(), null, 'logout cleared local token immediately');
  assert.equal(auth._refreshPromise, refreshPromise, 'refresh promise still pending during logout');
  assert.equal(service.getState(), ConnectionState.DISCONNECTED, 'WS disconnected on logout');

  // Now the refresh resolves. Its generation guard sees the session changed
  // (generation bumped by logout) → throws AuthSessionChangedError, which is
  // NOT treated as a refresh failure (no second logout). The catch skips the
  // logout call for AuthSessionChangedError.
  gate.resolve(jsonResponse(200, { token: freshToken(7200), refreshToken: 'refresh-2' }));
  await assert.rejects(refreshPromise, /Authentication session changed while the request was in flight/);

  // logout completes. Final state: logged out, no token, no double-logout.
  const logoutResult = await logoutPromise;
  assert.equal(logoutResult.revoked, true);
  assert.equal(auth.getToken(), null);
  assert.equal(auth.getRefreshToken(), null);
  assert.equal(auth.isAuthenticated(), false);
  assertStorageHas(storage, { token: null, refreshToken: null, user: null });

  // Exactly one logout (no refresh_failed from the guarded refresh).
  const logoutCount = reasons.filter((r) => r === 'logout').length;
  const refreshFailedCount = reasons.filter((r) => r === 'refresh_failed').length;
  assert.equal(logoutCount, 1, 'exactly one logout reason');
  assert.equal(refreshFailedCount, 0, 'generation-guarded refresh did NOT emit refresh_failed');

  service.destroy();
  auth.destroy();
  assertNoPendingWork(service, 'after destroy');
  assertAuthClean(auth, 'after destroy');
});
