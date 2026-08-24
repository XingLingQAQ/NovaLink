const DEFAULT_API_URL = '/api';

import { validateApiUrl, validateWsUrl } from './connectionPolicy.js';

export function getApiBaseUrl() {
  const stored = typeof localStorage !== 'undefined'
    && localStorage.getItem('nova_panel_api_url');
  if (stored) return stored.replace(/\/+$/, '');
  const envUrl = import.meta.env?.VITE_API_URL;
  if (envUrl) return envUrl.replace(/\/+$/, '');
  return DEFAULT_API_URL;
}

export function getWsUrl() {
  const stored = typeof localStorage !== 'undefined'
    && localStorage.getItem('nova_panel_ws_url');
  const envWs = import.meta.env?.VITE_WS_URL;
  const normalize = (raw) => {
    if (!raw) return null;
    try {
      const url = new URL(raw);
      if (url.pathname === '/' || url.pathname === '') url.pathname = '/ws';
      return url.toString();
    } catch {
      return raw.endsWith('/ws') ? raw : `${raw.replace(/\/$/, '')}/ws`;
    }
  };

  if (stored) return normalize(stored);
  if (envWs) return normalize(envWs);
  if (typeof window !== 'undefined' && window.location) {
    // PANEL-011: never downgrade an HTTPS page to ws://. A production panel
    // (https origin) without an explicit WS url must use wss://; only a
    // localhost dev page falls back to plain ws://.
    const httpsPage = window.location.protocol === 'https:';
    const protocol = httpsPage ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.hostname}:8889/ws`;
  }
  return 'ws://localhost:8889/ws';
}

/**
 * Persists the API + WS URLs for this session.
 *
 * PANEL-011: the policy validators in {@link connectionPolicy.js} are the gate.
 * If either url fails validation, that key is NOT written (and the caller is
 * expected to surface the returned error). A previously-stored unsafe value is
 * cleared so a stale localStorage entry from an older session cannot stick.
 *
 * @returns {{api: {ok:boolean,value?:string,error?:string},
 *            ws:  {ok:boolean,value?:string,error?:string}}}
 */
export function setConnectionUrls(apiUrl, wsUrl) {
  const api = apiUrl == null ? { ok: true } : validateApiUrl(apiUrl);
  const ws = wsUrl == null ? { ok: true } : validateWsUrl(wsUrl);
  if (typeof localStorage === 'undefined') return { api, ws };

  if (api.ok && apiUrl != null) {
    localStorage.setItem('nova_panel_api_url', api.value);
  } else if (apiUrl != null) {
    // Don't leave a rejected value in localStorage.
    localStorage.removeItem('nova_panel_api_url');
  }
  if (ws.ok && wsUrl != null) {
    localStorage.setItem('nova_panel_ws_url', ws.value);
  } else if (wsUrl != null) {
    localStorage.removeItem('nova_panel_ws_url');
  }
  return { api, ws };
}

export function clearConnectionUrls() {
  if (typeof localStorage === 'undefined') return;
  localStorage.removeItem('nova_panel_api_url');
  localStorage.removeItem('nova_panel_ws_url');
}
