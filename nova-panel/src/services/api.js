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
  if (stored) return stored;
  const envWs = import.meta.env && import.meta.env.VITE_WS_URL;
  if (envWs) return envWs;
  // Derive from the current origin (same host, default ws port 8889).
  if (typeof window !== 'undefined' && window.location) {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${window.location.hostname}:8889`;
  }
  return 'ws://localhost:8889';
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
 * @param {string} path - path relative to API base, e.g. '/channels'
 * @param {object} options - fetch options (method, body, etc.)
 * @returns {Promise<object>} - parsed JSON response
 * @throws {Error} on non-2xx with server message
 */
export async function apiFetch(path, options = {}) {
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
};

export default api;
