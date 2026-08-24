import test from 'node:test';
import assert from 'node:assert/strict';

import {
  ConnectionState,
  MessageType,
  WebSocketService,
} from './websocket.js';

class FakeAuth {
  constructor(token = 'token-1') {
    this.token = token;
    this.listeners = new Set();
  }

  onAuthChange(callback) {
    this.listeners.add(callback);
    return () => this.listeners.delete(callback);
  }

  emit(state) {
    this.token = state.token;
    for (const callback of [...this.listeners]) callback(state);
  }

  getToken() {
    return this.token;
  }

  maybeRefreshToken() {
    return Promise.resolve(null);
  }
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

function harness(options = {}) {
  const auth = new FakeAuth();
  const sockets = [];
  const service = new WebSocketService({
    auth,
    webSocketFactory: (url) => {
      const socket = new FakeSocket(url);
      sockets.push(socket);
      return socket;
    },
    authTimeoutMs: 25,
    reconnectDelay: 1000,
    pingIntervalMs: 60000,
    ...options,
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

test('authentication success and rejection clear timeout, listener, and pending promise', async () => {
  const { service, sockets } = harness();
  const success = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], success);

  assert.equal(service.pendingAuth, null);
  assert.equal(service.pendingConnect, null);
  assert.equal(service.listeners.has(MessageType.AUTH_RESPONSE), false);

  const rejected = service.reconnectWithToken('token-2');
  sockets[1].open();
  sockets[1].message({
    type: MessageType.AUTH_RESPONSE,
    success: false,
    error: 'token rejected',
  });
  await assert.rejects(rejected, /token rejected/);
  assert.equal(service.pendingAuth, null);
  assert.equal(service.pendingConnect, null);
  assert.equal(service.listeners.has(MessageType.AUTH_RESPONSE), false);
  service.destroy();
});

test('authentication timeout clears its listener and pending promise', async () => {
  const { service, sockets } = harness({ authTimeoutMs: 5 });
  const connection = service.connect('ws://example/ws', 'token-1');
  sockets[0].open();

  await assert.rejects(connection, /Authentication timeout/);
  assert.equal(service.pendingAuth, null);
  assert.equal(service.pendingConnect, null);
  assert.equal(service.listeners.has(MessageType.AUTH_RESPONSE), false);
  service.destroy();
});

test('disconnect during authentication rejects once and ignores a late response', async () => {
  const { service, sockets } = harness();
  let authenticatedEvents = 0;
  service.on('stateChange', ({ state }) => {
    if (state === ConnectionState.AUTHENTICATED) authenticatedEvents += 1;
  });

  const connection = service.connect('ws://example/ws', 'token-1');
  sockets[0].open();
  const lateMessage = sockets[0].onmessage;
  service.disconnect();

  await assert.rejects(connection, (error) => error.code === 'WS_DISCONNECTED');
  lateMessage({ data: JSON.stringify({ type: MessageType.AUTH_RESPONSE, success: true }) });
  assert.equal(authenticatedEvents, 0);
  assert.equal(service.getState(), ConnectionState.DISCONNECTED);
  assert.equal(service.pendingAuth, null);
  assert.equal(service.listeners.has(MessageType.AUTH_RESPONSE), false);
  assert.equal(service.messageQueue.length, 0);
  service.destroy();
});

test('token rotation re-authenticates in place on the live socket without a gap', async () => {
  const { auth, service, sockets } = harness();
  const received = [];
  service.on(MessageType.CHAT, (message) => received.push(message.id));
  service.setSubscriptions(['global', 'survival']);

  const firstConnection = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], firstConnection);

  // Token rotation on a live AUTHENTICATED socket: the socket must NOT be
  // retired. The backend handleAuth overwrites the session's auth state on
  // an existing authenticated session, so we re-auth IN PLACE and keep
  // receiving messages throughout the rotation (PANEL-001).
  auth.emit({
    isAuthenticated: true,
    token: 'token-2',
    previousToken: 'token-1',
    reason: 'refresh',
  });
  assert.equal(sockets.length, 1, 'no new socket should be created on in-place re-auth');
  assert.equal(sockets[0].closeCalls.length, 0, 'live socket must NOT be closed on rotation');
  assert.equal(service.getState(), ConnectionState.AUTHENTICATED, 'state stays AUTHENTICATED through the rotation');

  // A second AUTH message with the rotated token must have been sent on the
  // same socket (the in-place re-auth handshake).
  const authMessages = sockets[0].sent.filter((message) => message.type === MessageType.AUTH);
  assert.equal(authMessages.length, 2, 'a second AUTH must be sent for re-auth');
  assert.equal(authMessages[1].token, 'token-2', 'the re-auth AUTH carries the rotated token');

  // Subscriptions persist across the in-place re-auth (same socket, same
  // server-side session). Messages delivered before AUTH_RESPONSE still
  // arrive because the socket was never dropped.
  sockets[0].message({ type: MessageType.CHAT, id: 'during-rotation' });
  assert.deepEqual(received, ['during-rotation'], 'messages during the re-auth window are received (no gap)');

  // Complete the in-place re-auth.
  sockets[0].message({ type: MessageType.AUTH_RESPONSE, success: true });
  assert.equal(service.getState(), ConnectionState.AUTHENTICATED);
  assert.equal(service.pendingAuth, null, 'pendingAuth is cleared after in-place re-auth settles');
  assert.deepEqual(
    service.getSubscribedChannels(),
    ['global', 'survival'],
    'subscriptions persist across in-place re-auth',
  );
  service.destroy();
});

test('token rotation without a live authenticated socket falls back to reconnectWithToken', async () => {
  const { auth, service, sockets } = harness();
  service.setSubscriptions(['global']);

  // Connect and open the socket, but emit the rotation BEFORE the socket
  // authenticates. The state is CONNECTED (not AUTHENTICATED), so there is
  // no live authenticated socket to re-auth on — the fallback full
  // reconnect path must be used.
  const firstConnection = service.connect('ws://example/ws', 'token-1');
  sockets[0].open();
  assert.equal(service.getState(), ConnectionState.CONNECTED);

  auth.emit({
    isAuthenticated: true,
    token: 'token-2',
    previousToken: 'token-1',
    reason: 'refresh',
  });

  // A new socket must have been created (the full reconnect path), and the
  // old socket retired (closed). This locks in the fallback.
  assert.equal(sockets.length, 2, 'fallback must create a new socket');
  assert.equal(sockets[0].closeCalls.length, 1, 'old socket must be retired on fallback');

  // The first connection (pendingConnect on sockets[0]) rejects because
  // _startConnection retires the old socket before creating the new one.
  await assert.rejects(firstConnection, (error) => error.code === 'WS_CONNECTION_REPLACED');

  // Authenticate on the new socket with the rotated token. The AUTH message
  // is sent in socket.onopen, so open the new socket first.
  sockets[1].open();
  const authMessages = sockets[1].sent.filter((message) => message.type === MessageType.AUTH);
  assert.equal(authMessages[0].token, 'token-2', 'fallback reconnect uses the rotated token');
  sockets[1].message({ type: MessageType.AUTH_RESPONSE, success: true });
  await service.connectionPromise;
  assert.equal(service.getState(), ConnectionState.AUTHENTICATED);
  service.destroy();
});

test('in-place re-auth failure falls back to reconnectWithToken', async () => {
  const { auth, service, sockets } = harness();
  service.setSubscriptions(['global', 'survival']);

  const firstConnection = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], firstConnection);
  assert.equal(sockets.length, 1);

  // Rotate on the live authenticated socket — in-place re-auth is attempted.
  auth.emit({
    isAuthenticated: true,
    token: 'token-2',
    previousToken: 'token-1',
    reason: 'refresh',
  });
  assert.equal(sockets.length, 1, 'in-place re-auth does not create a new socket up front');
  assert.equal(service.getState(), ConnectionState.AUTHENTICATED, 'state stays AUTHENTICATED during the re-auth attempt');

  // Server rejects the rotated token. The in-place re-auth fails, so the
  // service must fall back to a full reconnectWithToken with the rotated
  // token.
  sockets[0].message({
    type: MessageType.AUTH_RESPONSE,
    success: false,
    error: 'token rejected',
  });

  // Give the .catch handler a microtask to run reconnectWithToken.
  await new Promise((resolve) => setTimeout(resolve, 0));

  // Fallback: a new socket is created and the old one is retired.
  assert.equal(sockets.length, 2, 'failure must fall back to reconnectWithToken (new socket created)');
  assert.equal(sockets[0].closeCalls.length, 1, 'old socket must be retired on fallback');
  assert.equal(service.getState(), ConnectionState.CONNECTING, 'service is reconnecting, not stuck non-AUTHENTICATED');

  // The reconnect attempt carries the rotated token. The AUTH message is
  // sent in socket.onopen, so open the new socket first.
  sockets[1].open();
  const authMessages = sockets[1].sent.filter((message) => message.type === MessageType.AUTH);
  assert.equal(authMessages[0].token, 'token-2', 'fallback reconnect uses the rotated token');

  // Authenticate on the new socket to confirm the service recovers.
  sockets[1].message({ type: MessageType.AUTH_RESPONSE, success: true });
  await service.connectionPromise;
  assert.equal(service.getState(), ConnectionState.AUTHENTICATED, 'service recovers AUTHENTICATED after fallback');
  service.destroy();
});

test('queued snapshots are generation-scoped, deduplicated, and cleared on reconnect', async () => {
  const { service, sockets } = harness();
  service.setSubscriptions(['global']);
  const firstConnection = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], firstConnection);

  sockets[0].serverClose();
  assert.equal(service.getState(), ConnectionState.RECONNECTING);
  service.requestSnapshot();
  service.requestSnapshot();
  assert.equal(service.messageQueue.length, 3);
  assert.equal(service._send({ type: 'mutation_must_not_queue' }), false);
  assert.equal(service.messageQueue.length, 3);

  const secondConnection = service.connect('ws://example/ws', 'token-1');
  assert.equal(service.messageQueue.length, 0);
  service.requestSnapshot();
  service.requestSnapshot();
  assert.equal(service.messageQueue.length, 3);
  await authenticate(service, sockets[1], secondConnection);

  const sentTypes = sockets[1].sent.map((message) => message.type);
  assert.equal(sentTypes.filter((type) => type === 'get_clients').length, 1);
  assert.equal(sentTypes.filter((type) => type === 'get_players').length, 1);
  assert.equal(sentTypes.filter((type) => type === 'get_channels').length, 1);
  assert.equal(sentTypes.includes('mutation_must_not_queue'), false);
  assert.deepEqual(service.getSubscribedChannels(), ['global']);
  assert.deepEqual(service.getQueueStats(), {
    droppedStale: 3,
    sentCurrent: 3,
    queued: 0,
  });
  service.destroy();
});

test('automatic reconnect drops disconnected work and restores the latest desired set', async () => {
  const { service, sockets } = harness({ reconnectDelay: 1, maxReconnectDelay: 1 });
  service.setSubscriptions(['global', 'old-channel']);
  const firstConnection = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], firstConnection);

  sockets[0].serverClose();
  service.setSubscriptions(['global', 'new-channel']);
  service.requestSnapshot();
  assert.equal(service.messageQueue.length, 3);

  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(sockets.length, 2);
  assert.equal(service.messageQueue.length, 0);
  const reconnect = service.connectionPromise;
  await authenticate(service, sockets[1], reconnect);

  assert.deepEqual(service.getDesiredSubscriptions(), ['global', 'new-channel']);
  assert.deepEqual(service.getSubscribedChannels(), ['global', 'new-channel']);
  const sentTypes = sockets[1].sent.map((message) => message.type);
  assert.equal(sentTypes.includes('get_clients'), false);
  assert.deepEqual(
    sockets[1].sent.find((message) => message.type === MessageType.SUBSCRIBE).channels,
    ['global', 'new-channel'],
  );
  assert.equal(service.getQueueStats().droppedStale, 3);
  service.destroy();
});

test('destroy removes auth listener and clears every connection-local resource', async () => {
  const { auth, service, sockets } = harness();
  const connection = service.connect('ws://example/ws', 'token-1');
  sockets[0].open();
  service.requestSnapshot();
  service.destroy();

  await assert.rejects(connection, (error) => error.code === 'WS_DISCONNECTED');
  assert.equal(auth.listeners.size, 0);
  assert.equal(service.pendingAuth, null);
  assert.equal(service.pendingConnect, null);
  assert.equal(service.reconnectTimer, null);
  assert.equal(service.pingInterval, null);
  assert.equal(service.pingTimeout, null);
  assert.equal(service.messageQueue.length, 0);
  assert.equal(service.listeners.size, 0);
});

test('PANEL-008: out-of-order state updates with an older revision are discarded', async () => {
  const { service, sockets } = harness();
  const connection = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], connection);

  const playerUpdates = [];
  service.on(MessageType.PLAYER_UPDATE, (message) => playerUpdates.push(message));

  // Server sends rev=5, then rev=3 (out of order). The client must apply
  // rev=5 and discard rev=3 because 3 < 5.
  sockets[0].message({ type: MessageType.PLAYER_UPDATE, revision: 5, players: [{ uuid: 'a', name: 'A5' }] });
  sockets[0].message({ type: MessageType.PLAYER_UPDATE, revision: 3, players: [{ uuid: 'b', name: 'B3' }] });

  assert.equal(playerUpdates.length, 1, 'stale rev=3 update must be discarded');
  assert.equal(playerUpdates[0].revision, 5);
  assert.equal(service.getLastAppliedRevision(MessageType.PLAYER_UPDATE), 5);

  // A newer revision is applied normally.
  sockets[0].message({ type: MessageType.PLAYER_UPDATE, revision: 7, players: [{ uuid: 'c', name: 'C7' }] });
  assert.equal(playerUpdates.length, 2);
  assert.equal(playerUpdates[1].revision, 7);
  assert.equal(service.getLastAppliedRevision(MessageType.PLAYER_UPDATE), 7);
  service.destroy();
});

test('PANEL-008: append-only types (chat, notification) are not filtered by the revision guard', async () => {
  const { service, sockets } = harness();
  const connection = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], connection);

  const chats = [];
  const notifications = [];
  service.on(MessageType.CHAT, (message) => chats.push(message));
  service.on(MessageType.NOTIFICATION, (message) => notifications.push(message));

  // Even if revisions arrive out of order for append-only types, every event
  // must be delivered — dropping a chat message would lose data.
  sockets[0].message({ type: MessageType.CHAT, revision: 9, channelId: 'global', content: 'first' });
  sockets[0].message({ type: MessageType.CHAT, revision: 2, channelId: 'global', content: 'second' });
  sockets[0].message({ type: MessageType.NOTIFICATION, revision: 8, title: 't1', message: 'm1', level: 'info' });
  sockets[0].message({ type: MessageType.NOTIFICATION, revision: 1, title: 't2', message: 'm2', level: 'info' });

  assert.equal(chats.length, 2, 'both chat events must be delivered');
  assert.equal(notifications.length, 2, 'both notifications must be delivered');
  service.destroy();
});

test('PANEL-008: revisions reset on reconnect so a fresh server state is not rejected', async () => {
  const { service, sockets } = harness({ reconnectDelay: 1, maxReconnectDelay: 1 });
  const firstConnection = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], firstConnection);

  sockets[0].message({ type: MessageType.PLAYER_UPDATE, revision: 100, players: [] });
  assert.equal(service.getLastAppliedRevision(MessageType.PLAYER_UPDATE), 100);

  // Server restarts — its revision counter is back at 1. The client must
  // reset its guard on reconnect so the fresh state is applied.
  sockets[0].serverClose();
  service.requestSnapshot();
  await new Promise((resolve) => setTimeout(resolve, 5));
  const reconnect = service.connectionPromise;
  await authenticate(service, sockets[1], reconnect);

  assert.equal(service.getLastAppliedRevision(MessageType.PLAYER_UPDATE), 0,
    'revisions must reset on reconnect');

  sockets[1].message({ type: MessageType.PLAYER_UPDATE, revision: 1, players: [{ uuid: 'fresh', name: 'Fresh' }] });
  assert.equal(service.getLastAppliedRevision(MessageType.PLAYER_UPDATE), 1,
    'fresh server revision after restart must be applied');
  service.destroy();
});

// ====================== SETTINGS_UPDATE (§11.6 提案 10 / item 20 缺口 B) ======================
//
// settings_update is a state-replacing payload (the live config object), so it
// is in REVISION_GUARDED_TYPES alongside SERVER_STATUS / CHANNEL_UPDATE /
// PLAYER_UPDATE. The existing _handleMessage logic already discards stale
// revisions for every type in that set, so adding the type is sufficient to
// enjoy the guard. These tests verify: (a) the type is registered in the
// guard set, (b) a stale-revision settings_update is discarded, (c) a newer
// revision is emitted to listeners.

test('SETTINGS_UPDATE is in REVISION_GUARDED_TYPES (state-replacement guard applies)', async () => {
  const { service, sockets } = harness();
  const connection = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], connection);

  // rev=5 applies and records the last-applied revision.
  sockets[0].message({ type: MessageType.SETTINGS_UPDATE, revision: 5, settings: { filterEnabled: true } });
  assert.equal(service.getLastAppliedRevision(MessageType.SETTINGS_UPDATE), 5);
  service.destroy();
});

test('SETTINGS_UPDATE: a stale revision is discarded before any listener sees it', async () => {
  const { service, sockets } = harness();
  const connection = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], connection);

  const seen = [];
  service.on(MessageType.SETTINGS_UPDATE, (message) => seen.push(message));

  // rev=9 first, then rev=4 (out of order). rev=4 must be dropped.
  sockets[0].message({ type: MessageType.SETTINGS_UPDATE, revision: 9, settings: { filterEnabled: false } });
  sockets[0].message({ type: MessageType.SETTINGS_UPDATE, revision: 4, settings: { filterEnabled: true } });

  assert.equal(seen.length, 1, 'stale rev=4 settings_update must be discarded');
  assert.equal(seen[0].revision, 9, 'only the newer rev=9 reaches listeners');
  assert.equal(service.getLastAppliedRevision(MessageType.SETTINGS_UPDATE), 9);
  service.destroy();
});

test('SETTINGS_UPDATE: a newer revision is delivered to listeners and bumps the guard', async () => {
  const { service, sockets } = harness();
  const connection = service.connect('ws://example/ws', 'token-1');
  await authenticate(service, sockets[0], connection);

  const seen = [];
  service.on(MessageType.SETTINGS_UPDATE, (message) => seen.push(message.revision));

  sockets[0].message({ type: MessageType.SETTINGS_UPDATE, revision: 3, settings: { filterEnabled: true } });
  sockets[0].message({ type: MessageType.SETTINGS_UPDATE, revision: 7, settings: { filterEnabled: false } });

  assert.deepEqual(seen, [3, 7], 'both newer-revision updates delivered in order');
  assert.equal(service.getLastAppliedRevision(MessageType.SETTINGS_UPDATE), 7);
  service.destroy();
});
