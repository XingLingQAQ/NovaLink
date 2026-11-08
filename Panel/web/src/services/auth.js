/**
 * Authentication service for NovaPanel.
 * Owns the rotating access/refresh pair and emits explicit lifecycle reasons.
 */

import { getApiBaseUrl, getConnectionGeneration } from './connectionUrls.js';

const TOKEN_KEY = 'nova_panel_token';
const USER_KEY = 'nova_panel_user';
const REFRESH_TOKEN_KEY = 'nova_panel_refresh_token';
const AUTH_STORAGE_KEYS = new Set([TOKEN_KEY, USER_KEY, REFRESH_TOKEN_KEY]);

export class AuthRequestError extends Error {
  constructor(message, status = 0, data = null) {
    super(message);
    this.name = 'AuthRequestError';
    this.status = status;
    this.data = data;
  }
}

export class AuthSessionChangedError extends Error {
  constructor() {
    super('Authentication session changed while the request was in flight');
    this.name = 'AuthSessionChangedError';
    this.code = 'AUTH_SESSION_CHANGED';
  }
}

async function readResponseData(response) {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return { raw: text };
  }
}

export class AuthService {
  constructor({ storage, fetchImpl, syncWindow, autoRefresh = true } = {}) {
    this.storage = storage === undefined
      ? (typeof localStorage !== 'undefined' ? localStorage : null)
      : storage;
    this.fetchImpl = fetchImpl || ((...args) => fetch(...args));
    this.syncWindow = syncWindow === undefined
      ? (typeof window !== 'undefined' ? window : null)
      : syncWindow;
    this.autoRefresh = autoRefresh;

    this.token = null;
    this.refreshToken = null;
    this.user = null;
    this.listeners = new Set();
    this._refreshPromise = null;
    this._refreshScope = null;
    this._logoutPromise = null;
    this._logoutScope = null;
    this._sessionGeneration = 0;
    this._loginGeneration = 0;
    this._storageHandler = (event) => this._handleStorageEvent(event);

    this._loadFromStorage();
    this.syncWindow?.addEventListener?.('storage', this._storageHandler);
  }

  _loadFromStorage() {
    if (!this.storage) return;
    try {
      const token = this.storage.getItem(TOKEN_KEY);
      const refreshToken = this.storage.getItem(REFRESH_TOKEN_KEY);
      const userJson = this.storage.getItem(USER_KEY);

      this.token = token || null;
      this.refreshToken = refreshToken || null;
      this.user = userJson ? JSON.parse(userJson) : null;

      if (this.autoRefresh && token && this._isTokenExpired(token)) {
        void this.refreshAccessToken(getApiBaseUrl()).catch(() => {});
      }
    } catch (error) {
      console.error('[Auth] Failed to load from storage:', error);
      this._clearLocalSession('storage_invalid');
    }
  }

  _handleStorageEvent(event) {
    if (event?.key && !AUTH_STORAGE_KEYS.has(event.key)) return;
    if (!this.storage) return;

    try {
      const previousToken = this.token;
      const nextToken = this.storage.getItem(TOKEN_KEY) || null;
      const nextRefreshToken = this.storage.getItem(REFRESH_TOKEN_KEY) || null;
      const userJson = this.storage.getItem(USER_KEY);
      const nextUser = userJson ? JSON.parse(userJson) : null;

      if (previousToken === nextToken
          && this.refreshToken === nextRefreshToken
          && JSON.stringify(this.user) === JSON.stringify(nextUser)) {
        return;
      }

      const currentFamily = this._parseToken(this.refreshToken)?.fid;
      const nextFamily = this._parseToken(nextRefreshToken)?.fid;
      if (!currentFamily || currentFamily !== nextFamily
          || this.user?.username !== nextUser?.username) {
        this._loginGeneration += 1;
      }
      this.token = nextToken;
      this.refreshToken = nextRefreshToken;
      this.user = nextUser;
      this._sessionGeneration += 1;
      this._notifyListeners(nextToken ? 'storage_sync' : 'storage_logout', previousToken);
    } catch (error) {
      console.error('[Auth] Failed to synchronize auth storage:', error);
      this._clearLocalSession('storage_invalid');
    }
  }

  _saveToStorage() {
    if (!this.storage) return;
    try {
      if (this.token) this.storage.setItem(TOKEN_KEY, this.token);
      else this.storage.removeItem(TOKEN_KEY);

      if (this.refreshToken) this.storage.setItem(REFRESH_TOKEN_KEY, this.refreshToken);
      else this.storage.removeItem(REFRESH_TOKEN_KEY);

      if (this.user) this.storage.setItem(USER_KEY, JSON.stringify(this.user));
      else this.storage.removeItem(USER_KEY);
    } catch (error) {
      console.error('[Auth] Failed to save to storage:', error);
    }
  }

  async login(username, password, apiUrl = '/api') {
    const response = await this.fetchImpl(`${apiUrl}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    const data = await readResponseData(response);

    if (!response.ok) {
      throw new AuthRequestError(
        data?.message || data?.error || 'Login failed',
        response.status,
        data,
      );
    }

    const previousToken = this.token;
    this.token = data.token;
    this.refreshToken = data.refreshToken;
    this.user = data.user;
    this._sessionGeneration += 1;
    this._loginGeneration += 1;
    this._saveToStorage();
    this._notifyListeners('login', previousToken);
    return { success: true, user: this.user };
  }

  loginWithToken(token, user = null, refreshToken = null) {
    const previousToken = this.token;
    this.token = token;
    this.refreshToken = refreshToken;
    this.user = user || this._parseToken(token);
    this._sessionGeneration += 1;
    this._loginGeneration += 1;
    this._saveToStorage();
    this._notifyListeners('login', previousToken);
  }

  /**
   * Starts remote revocation with captured credentials, then clears local state
   * immediately. Repeated logout calls in one login share a best-effort request.
   */
  logout(apiUrl = '/api', { revoke = true, reason = 'logout' } = {}) {
    if (this._logoutPromise && (!this.token
        || (this._logoutScope?.session === this._loginGeneration && this._logoutScope.apiUrl === apiUrl))) {
      this._clearLocalSession(reason);
      return this._logoutPromise;
    }

    const accessToken = this.token;
    const refreshToken = this.refreshToken;
    const shouldRevoke = revoke && !!accessToken;

    const remoteLogout = shouldRevoke
      ? (async () => {
          const response = await this.fetchImpl(`${apiUrl}/auth/logout`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${accessToken}`,
            },
            body: JSON.stringify({ refreshToken }),
          });
          const data = await readResponseData(response);
          if (!response.ok) {
            throw new AuthRequestError(
              data?.message || data?.error || `Logout failed (${response.status})`,
              response.status,
              data,
            );
          }
          return { revoked: true, error: null };
        })()
      : Promise.resolve({ revoked: false, error: null });

    const pending = remoteLogout.catch((error) => {
      console.warn('[Auth] Server session revocation was not confirmed:', error);
      return { revoked: false, error };
    }).finally(() => {
      if (this._logoutPromise === pending) {
        this._logoutPromise = null;
        this._logoutScope = null;
      }
    });
    this._logoutPromise = pending;
    this._logoutScope = { session: this._loginGeneration, apiUrl };
    this._clearLocalSession(reason);
    return pending;
  }

  _clearLocalSession(reason) {
    if (!this.token && !this.refreshToken && !this.user) return false;
    const previousToken = this.token;
    this.token = null;
    this.refreshToken = null;
    this.user = null;
    this._sessionGeneration += 1;
    this._loginGeneration += 1;
    this._saveToStorage();
    this._notifyListeners(reason, previousToken);
    return true;
  }

  /**
   * Rotates one session's token pair. Concurrent callers in that session
   * share the request because the backend invalidates each used refresh token.
   */
  refreshAccessToken(apiUrl = '/api') {
    const requestGeneration = this._sessionGeneration;
    const connectionGeneration = getConnectionGeneration();
    if (this._refreshPromise && this._refreshScope?.session === requestGeneration
        && this._refreshScope.connection === connectionGeneration && this._refreshScope.apiUrl === apiUrl) {
      return this._refreshPromise;
    }
    if (!this.refreshToken) {
      const error = new AuthRequestError('No refresh token available');
      void this.logout(apiUrl, { revoke: false, reason: 'refresh_failed' });
      return Promise.reject(error);
    }

    const refreshToken = this.refreshToken;
    const request = (async () => {
      try {
        const response = await this.fetchImpl(`${apiUrl}/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken }),
        });
        const data = await readResponseData(response);

        if (this._sessionGeneration !== requestGeneration || this.refreshToken !== refreshToken
            || getConnectionGeneration() !== connectionGeneration) {
          throw new AuthSessionChangedError();
        }

        if (!response.ok) {
          throw new AuthRequestError(
            data?.message || data?.error || `Token refresh failed (${response.status})`,
            response.status,
            data,
          );
        }
        if (!data?.token || !data?.refreshToken) {
          throw new AuthRequestError('Token refresh returned an incomplete token pair', response.status, data);
        }
        const previousToken = this.token;
        this.token = data.token;
        this.refreshToken = data.refreshToken;
        this._sessionGeneration += 1;
        this._saveToStorage();
        this._notifyListeners('refresh', previousToken);
        return this.token;
      } catch (error) {
        if (this._sessionGeneration !== requestGeneration || this.refreshToken !== refreshToken
            || getConnectionGeneration() !== connectionGeneration) throw new AuthSessionChangedError();
        if (!(error instanceof AuthSessionChangedError)) {
          void this.logout(apiUrl, { revoke: false, reason: 'refresh_failed' });
        }
        throw error;
      }
    })();
    const pending = request.finally(() => {
      if (this._refreshPromise === pending) {
        this._refreshPromise = null;
        this._refreshScope = null;
      }
    });
    this._refreshPromise = pending;
    this._refreshScope = { session: requestGeneration, connection: connectionGeneration, apiUrl };
    return pending;
  }

  getToken() {
    return this.token;
  }

  getSessionId() {
    return this._loginGeneration;
  }

  getRefreshToken() {
    return this.refreshToken;
  }

  getUser() {
    return this.user;
  }

  isAuthenticated() {
    return !!this.token && !this._isTokenExpired(this.token);
  }

  _isTokenExpired(token) {
    const payload = this._parseToken(token);
    if (!payload?.exp) return true;
    return Date.now() >= (payload.exp * 1000) - 60000;
  }

  _isTokenExpiringSoon(withinMs = 5 * 60 * 1000) {
    const payload = this.token ? this._parseToken(this.token) : null;
    if (!payload?.exp) return false;
    return Date.now() >= (payload.exp * 1000) - withinMs;
  }

  async maybeRefreshToken(apiUrl = '/api') {
    if (!this.token || !this.refreshToken || !this._isTokenExpiringSoon()) return null;
    return this.refreshAccessToken(apiUrl);
  }

  _parseToken(token) {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) return null;
      const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(atob(payload));
    } catch {
      return null;
    }
  }

  getTokenExpiration() {
    const payload = this.token ? this._parseToken(this.token) : null;
    return payload?.exp ? new Date(payload.exp * 1000) : null;
  }

  onAuthChange(callback) {
    this.listeners.add(callback);
    return () => this.listeners.delete(callback);
  }

  _notifyListeners(reason, previousToken = null) {
    const state = {
      isAuthenticated: this.isAuthenticated(),
      user: this.user,
      token: this.token,
      previousToken,
      reason,
    };
    this.listeners.forEach((callback) => {
      try {
        callback(state);
      } catch (error) {
        console.error('[Auth] Listener error:', error);
      }
    });
  }

  getAuthHeader() {
    return this.token ? { Authorization: `Bearer ${this.token}` } : {};
  }

  destroy() {
    this.syncWindow?.removeEventListener?.('storage', this._storageHandler);
    this.listeners.clear();
  }
}

export const authService = new AuthService();
export default authService;
