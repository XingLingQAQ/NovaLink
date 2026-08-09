/**
 * REST API client for NovaLink backend.
 * Wraps fetch with JWT auth headers and base URL handling.
 *
 * All non-auth endpoints require `Authorization: Bearer <JWT>`.
 */

import authService from './auth';
import i18n from '../i18n';

const DEFAULT_API_URL = '/api';

/**
 * Resolve the API base URL.
 * Priority: localStorage override -> vite env -> default same-origin /api.
 */
export function getApiBaseUrl() {
  const stored = typeof localStorage !== 'undefined' && localStorage.getItem('nova_panel_api_url');
  if (stored) return stored.replace(/\/+$/, '');
  const envUrl = import.meta.env && import.meta.env.VITE_API_URL;
  if (envUrl) return envUrl.replace(/\/+$/, '');
  return DEFAULT_API_URL;
}

/**
 * Resolve the WebSocket URL.
 * Priority: localStorage override -> vite env -> derive from API host -> default.
 */
export function getWsUrl() {
  const stored = typeof localStorage !== 'undefined' && localStorage.getItem('nova_panel_ws_url');
  const envWs = import.meta.env && import.meta.env.VITE_WS_URL;
  // Connect directly to the NovaLink backend WS endpoint (port 8889, path /ws).
  // The backend's WEBSOCKET_PATH is "/ws"; connecting to "/" is rejected (1006).
  // Normalize any stored/env override to ensure it ends with "/ws".
  const normalize = (raw) => {
    if (!raw) return null;
    try {
      const u = new URL(raw);
      if (u.pathname === '/' || u.pathname === '') u.pathname = '/ws';
      return u.toString();
    } catch {
      return raw.endsWith('/ws') ? raw : raw.replace(/\/$/, '') + '/ws';
    }
  };
  if (stored) {
    const n = normalize(stored);
    if (n) return n;
  }
  if (envWs) {
    const n = normalize(envWs);
    if (n) return n;
  }
  if (typeof window !== 'undefined' && window.location) {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${window.location.hostname}:8889/ws`;
  }
  return 'ws://localhost:8889/ws';
}

/**
 * Persist API + WS URL overrides chosen by the user on the login screen.
 */
export function setConnectionUrls(apiUrl, wsUrl) {
  if (apiUrl) {
    const normalized = apiUrl.replace(/\/+$/, '');
    localStorage.setItem('nova_panel_api_url', normalized);
  }
  if (wsUrl) {
    localStorage.setItem('nova_panel_ws_url', wsUrl);
  }
}

/**
 * Clear stored connection URL overrides (used on logout).
 */
export function clearConnectionUrls() {
  localStorage.removeItem('nova_panel_api_url');
  localStorage.removeItem('nova_panel_ws_url');
}

/**
 * Perform a fetch against the REST API with auth headers.
 * On 401, transparently attempts a single token refresh + retry before
 * surfacing the error (avoids kicking the user on a stale-but-refreshable
 * token). The refresh call itself never recurses on its own 401.
 * @param {string} path - path relative to API base, e.g. '/channels'
 * @param {object} options - fetch options (method, body, etc.)
 * @param {boolean} _isRetry - internal guard against refresh loops
 * @returns {Promise<object>} - parsed JSON response
 * @throws {Error} on non-2xx with server message
 */
export async function apiFetch(path, options = {}, _isRetry = false) {
  const base = getApiBaseUrl();
  const url = `${base}${path.startsWith('/') ? path : `/${path}`}`;
  const headers = {
    'Content-Type': 'application/json',
    ...authService.getAuthHeader(),
    ...(options.headers || {}),
  };

  let response;
  try {
    response = await fetch(url, { ...options, headers });
  } catch (err) {
    throw new Error(i18n.t('common.api_error_connect', { error: err.message || err }));
  }

  let data = null;
  const text = await response.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { raw: text };
    }
  }

  // 401: try a single token refresh + retry before giving up. The _isRetry
  // guard ensures a refresh-induced 401 (or a refresh endpoint 401) doesn't
  // recurse infinitely.
  if (response.status === 401 && !_isRetry) {
    try {
      const newToken = await authService.refreshAccessToken(base);
      if (newToken) {
        return apiFetch(path, options, true);
      }
    } catch (refreshErr) {
      // Refresh failed — the token is truly invalid. Log out and surface the
      // original 401 so the UI can react (e.g. show login).
      authService.logout();
      const error = new Error(i18n.t('common.api_error_request', { status: 401 }));
      error.status = 401;
      error.data = data;
      throw error;
    }
  }

  if (!response.ok) {
    const message = (data && (data.message || data.error)) || i18n.t('common.api_error_request', { status: response.status });
    const error = new Error(message);
    error.status = response.status;
    error.data = data;
    throw error;
  }

  return data;
}

/**
 * Convenience methods for the endpoints used by the panel.
 */
export const api = {
  status: () => apiFetch('/status'),
  getChannels: () => apiFetch('/channels'),
  getChannel: (id) => apiFetch(`/channels/${encodeURIComponent(id)}`),
  getChannelMembers: (id) => apiFetch(`/channels/${encodeURIComponent(id)}/members`),
  getPlayers: () => apiFetch('/players'),
  getPlayer: (id) => apiFetch(`/players/${encodeURIComponent(id)}`),
  getWebhooks: () => apiFetch('/webhooks'),
  createWebhook: (body) => apiFetch('/webhooks', { method: 'POST', body: JSON.stringify(body) }),
  deleteWebhook: (id) => apiFetch(`/webhooks/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  sendMessage: (channelId, content, senderName = 'Panel') =>
    apiFetch('/messages', {
      method: 'POST',
      body: JSON.stringify({ channelId, content, senderName }),
    }),

  // --- Channel CRUD (batch 2) ---
  createChannel: (body) => apiFetch('/channels', { method: 'POST', body: JSON.stringify(body) }),
  updateChannel: (id, body) => apiFetch(`/channels/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteChannel: (id) => apiFetch(`/channels/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  invitePlayer: (channelId, body) => apiFetch(`/channels/${encodeURIComponent(channelId)}/invite`, { method: 'POST', body: JSON.stringify(body || {}) }),

  // --- Player mute / unmute / kick (batch 2) ---
  mutePlayer: (uuid, body) => apiFetch(`/players/${encodeURIComponent(uuid)}/mute`, { method: 'POST', body: JSON.stringify(body || {}) }),
  unmutePlayer: (uuid, body) => apiFetch(`/players/${encodeURIComponent(uuid)}/unmute`, { method: 'POST', body: JSON.stringify(body || {}) }),
  getMutes: () => apiFetch('/mutes'),
  kickPlayer: (uuid, body) => apiFetch(`/players/${encodeURIComponent(uuid)}/kick`, { method: 'POST', body: JSON.stringify(body || {}) }),

  // --- Server / config / console (batch 2) ---
  reloadConfig: () => apiFetch('/reload', { method: 'POST' }),
  disconnectClient: (clientId) => apiFetch(`/clients/${encodeURIComponent(clientId)}`, { method: 'DELETE' }),
  runConsoleCommand: (command) => apiFetch('/console', { method: 'POST', body: JSON.stringify({ command }) }),
};

export default api;
