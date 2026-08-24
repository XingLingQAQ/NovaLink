/**
 * Generation-aware WebSocket lifecycle for NovaPanel.
 * Subscription intent survives reconnects; socket-local work never does.
 */

import authService from './auth.js';
import { getApiBaseUrl } from './connectionUrls.js';

export const ConnectionState = {
  DISCONNECTED: 'disconnected',
  CONNECTING: 'connecting',
  CONNECTED: 'connected',
  AUTHENTICATED: 'authenticated',
  RECONNECTING: 'reconnecting',
  ERROR: 'error',
};

export const MessageType = {
  AUTH: 'auth',
  AUTH_RESPONSE: 'auth_response',
  SUBSCRIBE: 'subscribe',
  UNSUBSCRIBE: 'unsubscribe',
  CHAT: 'chat',
  SERVER_STATUS: 'server_status',
  PLAYER_UPDATE: 'player_update',
  CHANNEL_UPDATE: 'channel_update',
  SETTINGS_UPDATE: 'settings_update',
  NOTIFICATION: 'notification',
  ERROR: 'error',
  PING: 'ping',
  PONG: 'pong',
};

const SOCKET_OPEN = 1;
const SNAPSHOT_TYPES = new Set(['get_clients', 'get_players', 'get_channels']);

/**
 * PANEL-008: message types whose payloads replace entity state (not
 * append-only). For these, an out-of-order update with a revision older
 * than the last-applied revision for that type is discarded before any
 * listener sees it. Chat and notification are intentionally excluded —
 * they are append-only events where dropping a stale revision would lose
 * data. The server stamps a revision on every outbound payload regardless.
 */
const REVISION_GUARDED_TYPES = new Set([
  MessageType.SERVER_STATUS,
  MessageType.CHANNEL_UPDATE,
  MessageType.PLAYER_UPDATE,
  // §11.6 提案 10 / item 20: settings is a full state-replacement payload
  // (the live config object), so an out-of-order update with an older
  // revision would clobber a newer local state. Guard it the same way as
  // SERVER_STATUS / CHANNEL_UPDATE — the server stamps a revision on every
  // settings_update emit and the client discards anything older than the
  // last-applied revision. Append-only types (chat, notification) remain
  // intentionally unguarded.
  MessageType.SETTINGS_UPDATE,
]);

export class WebSocketLifecycleError extends Error {
  constructor(message, code) {
    super(message);
    this.name = 'WebSocketLifecycleError';
    this.code = code;
  }
}

export function isWebSocketLifecycleCancellation(error) {
  return error instanceof WebSocketLifecycleError
    && ['WS_CONNECTION_REPLACED', 'WS_DISCONNECTED', 'WS_DESTROYED'].includes(error.code);
}

export class WebSocketService {
  constructor({
    auth = authService,
    webSocketFactory = (url) => new WebSocket(url),
    apiUrlResolver = getApiBaseUrl,
    authTimeoutMs = 10000,
    reconnectDelay = 1000,
    maxReconnectDelay = 30000,
    maxReconnectAttempts = 5,
    pingIntervalMs = 30000,
    pingTimeoutMs = 5000,
  } = {}) {
    this.auth = auth;
    this.webSocketFactory = webSocketFactory;
    this.apiUrlResolver = apiUrlResolver;
    this.authTimeoutMs = authTimeoutMs;
    this.reconnectDelay = reconnectDelay;
    this.maxReconnectDelay = maxReconnectDelay;
    this.maxReconnectAttempts = maxReconnectAttempts;
    this.pingIntervalMs = pingIntervalMs;
    this.pingTimeoutMs = pingTimeoutMs;

    this.socket = null;
    this.state = ConnectionState.DISCONNECTED;
    this.url = null;
    this.token = null;
    this.generation = 0;
    this.reconnectAttempts = 0;
    this.reconnectTimer = null;
    this.pingInterval = null;
    this.pingTimeout = null;
    this.pendingAuth = null;
    this.pendingConnect = null;
    this.connectionPromise = null;
    this.listeners = new Map();
    this.desiredSubscriptions = new Set();
    this.subscribedChannels = new Set();
    this.messageQueue = [];
    this.queueStats = { droppedStale: 0, sentCurrent: 0 };
    this.snapshotRequestedGeneration = null;
    this.destroyed = false;

    // PANEL-008: last-applied revision per guarded entity type. An update
    // whose revision is older than the recorded value is discarded. A
    // missing/zero revision on the payload bypasses the guard (legacy
    // server compatibility) — the update is applied and its revision
    // recorded.
    this.lastAppliedRevisions = Object.create(null);

    this.authUnsubscribe = this.auth?.onAuthChange?.((state) => {
      this._handleAuthChange(state);
    }) || null;
  }

  connect(url, token) {
    if (this.destroyed) {
      return Promise.reject(new WebSocketLifecycleError(
        'WebSocket service has been destroyed',
        'WS_DESTROYED',
      ));
    }
    if (!url || !token) return Promise.reject(new Error('WebSocket URL and token are required'));

    const sameConnection = this.socket && this.url === url && this.token === token;
    if (sameConnection && this.connectionPromise) return this.connectionPromise;
    if (sameConnection && this.state === ConnectionState.AUTHENTICATED) {
      return Promise.resolve(true);
    }

    this.url = url;
    this.token = token;
    this.reconnectAttempts = 0;
    return this._startConnection(false);
  }

  reconnectWithToken(token) {
    if (!this.url) {
      return Promise.reject(new WebSocketLifecycleError(
        'No previous connection to restore',
        'WS_DISCONNECTED',
      ));
    }
    this.token = token;
    this.reconnectAttempts = 0;
    return this._startConnection(false);
  }

  _startConnection(isReconnect) {
    this._clearReconnectTimer();
    this._retireSocket(new WebSocketLifecycleError(
      'WebSocket connection was replaced',
      'WS_CONNECTION_REPLACED',
    ));

    const generation = ++this.generation;
    this._dropQueuedMessages();
    this.snapshotRequestedGeneration = null;
    this.subscribedChannels.clear();
    // PANEL-008: revisions from the retired socket must not block updates
    // from the new connection — reset the guard at the start of each
    // connection attempt.
    this.resetRevisions();
    this._setState(isReconnect ? ConnectionState.RECONNECTING : ConnectionState.CONNECTING);

    let socket;
    try {
      socket = this.webSocketFactory(this.url);
    } catch (error) {
      this._setState(ConnectionState.ERROR);
      return Promise.reject(error);
    }
    this.socket = socket;

    this.connectionPromise = new Promise((resolve, reject) => {
      this.pendingConnect = { generation, resolve, reject, settled: false };
    });
    const result = this.connectionPromise;

    socket.onopen = () => {
      if (!this._isCurrent(generation, socket)) return;
      this._setState(ConnectionState.CONNECTED);
      this._authenticate(this.token, generation, socket).then(() => {
        if (!this._isCurrent(generation, socket)) return;
        this.reconnectAttempts = 0;
        this._setState(ConnectionState.AUTHENTICATED);
        this._applyDesiredSubscriptions(generation, socket);
        this._flushMessageQueue(generation, socket);
        this._startPingInterval(generation, socket);
        this._settleConnect(generation, true, true);
      }).catch((error) => {
        if (!this._isCurrent(generation, socket)) return;
        this._settleConnect(generation, false, error);
        this._retireSocket(error);
        this._setState(ConnectionState.ERROR);
      });
    };

    socket.onmessage = (event) => {
      if (!this._isCurrent(generation, socket)) return;
      this._handleMessage(event.data, generation, socket);
    };

    socket.onerror = (error) => {
      if (!this._isCurrent(generation, socket)) return;
      console.error('[WebSocket] Socket error:', error);
    };

    socket.onclose = () => {
      if (!this._isCurrent(generation, socket)) return;
      const error = new WebSocketLifecycleError(
        'WebSocket disconnected',
        'WS_CONNECTION_CLOSED',
      );
      this._cancelAuthentication(generation, error);
      this._settleConnect(generation, false, error);
      this._detachSocket(socket);
      this.socket = null;
      this.connectionPromise = null;
      this.subscribedChannels.clear();
      this._dropQueuedMessages();
      this.snapshotRequestedGeneration = null;
      // PANEL-008: reset revisions on close so the reconnect does not reject
      // the fresh server state (server revision counter resets on restart
      // and we want a clean slate on a new socket).
      this.resetRevisions();
      this._stopPingInterval();
      this.generation += 1;
      this._attemptReconnect(this.generation);
    };

    return result;
  }

  _authenticate(token, generation, socket) {
    const authMessage = { type: MessageType.AUTH, token, timestamp: Date.now() };

    const promise = new Promise((resolve, reject) => {
      const handler = (data) => {
        if (!this._isCurrent(generation, socket)) return;
        if (data.success) this._settleAuthentication(generation, true, data);
        else this._settleAuthentication(
          generation,
          false,
          new Error(data.error || 'Authentication failed'),
        );
      };
      const timeoutId = setTimeout(() => {
        this._settleAuthentication(
          generation,
          false,
          new Error('Authentication timeout'),
        );
      }, this.authTimeoutMs);

      this.pendingAuth = {
        generation,
        socket,
        handler,
        timeoutId,
        resolve,
        reject,
        settled: false,
      };
      this.on(MessageType.AUTH_RESPONSE, handler);
    });

    if (!this._sendNow(authMessage, generation, socket)) {
      this._settleAuthentication(generation, false, new Error('Authentication socket is not open'));
    }
    return promise;
  }

  _settleAuthentication(generation, succeeded, value) {
    const pending = this.pendingAuth;
    if (!pending || pending.generation !== generation || pending.settled) return false;
    pending.settled = true;
    clearTimeout(pending.timeoutId);
    this.off(MessageType.AUTH_RESPONSE, pending.handler);
    this.pendingAuth = null;
    if (succeeded) pending.resolve(value);
    else pending.reject(value);
    return true;
  }

  _cancelAuthentication(generation, error) {
    return this._settleAuthentication(generation, false, error);
  }

  _settleConnect(generation, succeeded, value) {
    const pending = this.pendingConnect;
    if (!pending || pending.generation !== generation || pending.settled) return false;
    pending.settled = true;
    this.pendingConnect = null;
    if (succeeded) pending.resolve(value);
    else pending.reject(value);
    return true;
  }

  _handleAuthChange({ isAuthenticated, token, previousToken }) {
    if (this.destroyed) return;
    if (!isAuthenticated) {
      if (this.socket || this.reconnectTimer || this.url) this.disconnect();
      return;
    }
    if (token && previousToken && token !== previousToken && this.url) {
      this.reconnectWithToken(token).catch((error) => {
        if (!isWebSocketLifecycleCancellation(error)) {
          console.error('[WebSocket] Token rotation reconnect failed:', error);
        }
      });
    }
  }

  setSubscriptions(channels) {
    this.desiredSubscriptions = new Set(
      (Array.isArray(channels) ? channels : []).filter(Boolean),
    );
    if (this.state === ConnectionState.AUTHENTICATED && this.socket) {
      this._applyDesiredSubscriptions(this.generation, this.socket);
    }
  }

  subscribe(channels) {
    const desired = new Set(this.desiredSubscriptions);
    for (const channel of channels || []) if (channel) desired.add(channel);
    this.setSubscriptions([...desired]);
  }

  unsubscribe(channels) {
    const desired = new Set(this.desiredSubscriptions);
    for (const channel of channels || []) desired.delete(channel);
    this.setSubscriptions([...desired]);
  }

  _applyDesiredSubscriptions(generation, socket) {
    if (!this._isAuthenticatedCurrent(generation, socket)) return;
    const unsubscribe = [...this.subscribedChannels]
      .filter((channel) => !this.desiredSubscriptions.has(channel));
    const subscribe = [...this.desiredSubscriptions]
      .filter((channel) => !this.subscribedChannels.has(channel));

    if (unsubscribe.length > 0 && this._sendNow({
      type: MessageType.UNSUBSCRIBE,
      channels: unsubscribe,
      timestamp: Date.now(),
    }, generation, socket)) {
      unsubscribe.forEach((channel) => this.subscribedChannels.delete(channel));
    }
    if (subscribe.length > 0 && this._sendNow({
      type: MessageType.SUBSCRIBE,
      channels: subscribe,
      timestamp: Date.now(),
    }, generation, socket)) {
      subscribe.forEach((channel) => this.subscribedChannels.add(channel));
    }
  }

  requestSnapshot() {
    if (this.snapshotRequestedGeneration === this.generation) return;
    this.snapshotRequestedGeneration = this.generation;
    const messages = ['get_clients', 'get_players', 'get_channels']
      .map((type) => ({ type, timestamp: Date.now() }));
    if (this.state === ConnectionState.AUTHENTICATED && this.socket) {
      messages.forEach((message) => {
        if (this._sendNow(message, this.generation, this.socket)) {
          this.queueStats.sentCurrent += 1;
        }
      });
      return;
    }

    const queuedTypes = new Set(
      this.messageQueue
        .filter((entry) => entry.generation === this.generation)
        .map((entry) => entry.message.type),
    );
    for (const message of messages) {
      if (!queuedTypes.has(message.type)) {
        this.messageQueue.push({ generation: this.generation, message });
      }
    }
  }

  _flushMessageQueue(generation, socket) {
    const queued = this.messageQueue;
    this.messageQueue = [];
    for (const entry of queued) {
      if (entry.generation !== generation || !SNAPSHOT_TYPES.has(entry.message.type)) {
        this.queueStats.droppedStale += 1;
      } else if (this._sendNow(entry.message, generation, socket)) {
        this.queueStats.sentCurrent += 1;
      }
    }
  }

  _dropQueuedMessages() {
    this.queueStats.droppedStale += this.messageQueue.length;
    this.messageQueue = [];
  }

  // Generic messages are deliberately never queued for a future session.
  _send(message) {
    if (this.state !== ConnectionState.AUTHENTICATED || !this.socket) return false;
    return this._sendNow(message, this.generation, this.socket);
  }

  _sendNow(message, generation, socket) {
    if (!this._isCurrent(generation, socket) || socket.readyState !== SOCKET_OPEN) return false;
    socket.send(JSON.stringify(message));
    return true;
  }

  _handleMessage(rawData, generation, socket) {
    try {
      const message = JSON.parse(rawData);
      if (!this._isCurrent(generation, socket)) return;
      if (message.type === MessageType.PONG) {
        this._handlePong();
        return;
      }
      // PANEL-008: discard stale state-replacing updates. If the payload
      // carries a revision and we have already applied a newer revision for
      // this entity type, the update is out-of-order (e.g. a delayed packet
      // from before the latest snapshot) and is dropped before any listener
      // sees it. Append-only types (chat, notification, auth_response,
      // pong) are never filtered here.
      if (REVISION_GUARDED_TYPES.has(message.type) && message.revision != null) {
        const last = this.lastAppliedRevisions[message.type] || 0;
        if (message.revision < last) {
          return;
        }
        this.lastAppliedRevisions[message.type] = message.revision;
      }
      this._notifyListeners(message.type, message);
      this._notifyListeners('all', message);
    } catch (error) {
      console.error('[WebSocket] Failed to parse message:', error);
    }
  }

  _startPingInterval(generation, socket) {
    this._stopPingInterval();
    this.pingInterval = setInterval(() => {
      if (!this._isAuthenticatedCurrent(generation, socket)) return;
      if (!this._sendNow({ type: MessageType.PING, timestamp: Date.now() }, generation, socket)) return;
      if (this.pingTimeout) clearTimeout(this.pingTimeout);
      this.pingTimeout = setTimeout(() => {
        if (this._isCurrent(generation, socket)) socket.close();
      }, this.pingTimeoutMs);
    }, this.pingIntervalMs);
  }

  _stopPingInterval() {
    if (this.pingInterval) clearInterval(this.pingInterval);
    if (this.pingTimeout) clearTimeout(this.pingTimeout);
    this.pingInterval = null;
    this.pingTimeout = null;
  }

  _handlePong() {
    if (this.pingTimeout) clearTimeout(this.pingTimeout);
    this.pingTimeout = null;
  }

  _attemptReconnect(closedGeneration) {
    if (!this.url || !this.token || this.destroyed) {
      this._setState(ConnectionState.DISCONNECTED);
      return;
    }
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      this._setState(ConnectionState.ERROR);
      return;
    }

    this._setState(ConnectionState.RECONNECTING);
    const delay = Math.min(
      this.reconnectDelay * (2 ** this.reconnectAttempts),
      this.maxReconnectDelay,
    );
    this.reconnectTimer = setTimeout(async () => {
      this.reconnectTimer = null;
      if (this.generation !== closedGeneration || !this.url || this.destroyed) return;
      this.reconnectAttempts += 1;
      try {
        await this.auth?.maybeRefreshToken?.(this.apiUrlResolver());
        if (this.generation !== closedGeneration || !this.url || this.destroyed) return;
        this.token = this.auth?.getToken?.() || this.token;
        this._startConnection(true).catch(() => {});
      } catch {
        if (this.url && !this.destroyed) this._setState(ConnectionState.ERROR);
      }
    }, delay);
  }

  async manualReconnect() {
    if (!this.url) throw new Error('No previous connection to restore');
    this.reconnectAttempts = 0;
    await this.auth?.maybeRefreshToken?.(this.apiUrlResolver());
    const token = this.auth?.getToken?.() || this.token;
    return this.reconnectWithToken(token);
  }

  disconnect() {
    const error = new WebSocketLifecycleError('WebSocket disconnected', 'WS_DISCONNECTED');
    this._clearReconnectTimer();
    this._retireSocket(error);
    this._dropQueuedMessages();
    this.snapshotRequestedGeneration = null;
    this.desiredSubscriptions.clear();
    this.subscribedChannels.clear();
    this.resetRevisions();
    this.url = null;
    this.token = null;
    this.generation += 1;
    this._setState(ConnectionState.DISCONNECTED);
  }

  _retireSocket(error) {
    this._stopPingInterval();
    if (this.pendingAuth) this._cancelAuthentication(this.pendingAuth.generation, error);
    if (this.pendingConnect) this._settleConnect(this.pendingConnect.generation, false, error);
    const socket = this.socket;
    if (socket) {
      this._detachSocket(socket);
      this.socket = null;
      try {
        if (socket.readyState < 2) socket.close(1000, 'Connection retired');
      } catch {
        // The generation guard already makes a failed close harmless.
      }
    }
    this.connectionPromise = null;
    this.subscribedChannels.clear();
  }

  _detachSocket(socket) {
    socket.onopen = null;
    socket.onmessage = null;
    socket.onerror = null;
    socket.onclose = null;
  }

  _clearReconnectTimer() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
  }

  _isCurrent(generation, socket) {
    return !this.destroyed && this.generation === generation && this.socket === socket;
  }

  _isAuthenticatedCurrent(generation, socket) {
    return this.state === ConnectionState.AUTHENTICATED && this._isCurrent(generation, socket);
  }

  on(type, callback) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(callback);
    return () => this.off(type, callback);
  }

  off(type, callback) {
    const callbacks = this.listeners.get(type);
    if (!callbacks) return;
    callbacks.delete(callback);
    if (callbacks.size === 0) this.listeners.delete(type);
  }

  _notifyListeners(type, data) {
    for (const callback of [...(this.listeners.get(type) || [])]) {
      try {
        callback(data);
      } catch (error) {
        console.error('[WebSocket] Listener error:', error);
      }
    }
  }

  _setState(state) {
    if (this.state === state) return;
    this.state = state;
    this._notifyListeners('stateChange', { state, generation: this.generation });
  }

  getState() {
    return this.state;
  }

  isAuthenticated() {
    return this.state === ConnectionState.AUTHENTICATED;
  }

  getSubscribedChannels() {
    return [...this.subscribedChannels];
  }

  getDesiredSubscriptions() {
    return [...this.desiredSubscriptions];
  }

  getQueueStats() {
    return { ...this.queueStats, queued: this.messageQueue.length };
  }

  /**
   * PANEL-008: returns the last-applied revision for a guarded entity type,
   * or 0 when none has been applied yet. Exposed for diagnostics/tests.
   */
  getLastAppliedRevision(type) {
    return this.lastAppliedRevisions[type] || 0;
  }

  /**
   * PANEL-008: resets the recorded revisions (used on hard reconnect /
   * destroy so a fresh session does not carry stale ordering state).
   */
  resetRevisions() {
    this.lastAppliedRevisions = Object.create(null);
  }

  destroy() {
    if (this.destroyed) return;
    this.disconnect();
    this.authUnsubscribe?.();
    this.authUnsubscribe = null;
    this.listeners.clear();
    this.destroyed = true;
  }
}

export const websocketService = new WebSocketService();
export default websocketService;
