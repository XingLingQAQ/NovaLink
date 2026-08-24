/**
 * PANEL-011 transport fail-closed policy for the web panel.
 *
 * <p>Production (non-localhost browser origin) must reject arbitrary plaintext
 * {@code http://} / {@code ws://} remote addresses, and an HTTPS page must never
 * downgrade to {@code ws://} / {@code http://} auth. This module centralizes
 * that decision so {@link connectionUrls.js} and the login screen share one
 * rule set instead of each ad-hoc normalizing URLs.
 *
 * <p>Every validator returns a structured result {@code { ok, value?, error? }}
 * so callers can surface the reason without throwing across React state.
 */

/**
 * Whether the page itself is served from a loopback dev origin.
 * On http://localhost or http://127.0.0.1 we still allow plaintext http/ws
 * so local development without TLS keeps working. Anything else (including
 * https origins and real hostnames) is treated as "production".
 */
export function isLocalDevOrigin() {
  if (typeof window === 'undefined' || !window.location) return false;
  const host = window.location.hostname;
  return host === 'localhost' || host === '127.0.0.1' || host === '[::1]';
}

/**
 * @param {string} raw
 * @returns {{ok: true, value: string} | {ok: false, error: string}}
 */
export function validateApiUrl(raw) {
  if (!raw || typeof raw !== 'string') {
    return { ok: false, error: 'API address is empty.' };
  }
  const value = raw.trim();
  if (!value) {
    return { ok: false, error: 'API address is empty.' };
  }

  // Same-origin relative path ("/api") is always safe: it inherits the page
  // scheme/host, so on https it is https. No further scheme check needed.
  if (value.startsWith('/')) {
    return { ok: true, value: value.replace(/\/+$/, '') || '/' };
  }

  let url;
  try {
    url = new URL(value);
  } catch {
    return { ok: false, error: `API address is not a valid URL: ${value}` };
  }

  if (url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]') {
    // localhost dev: allow http and https.
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return { ok: false, error: `API address uses unsupported scheme: ${url.protocol}` };
    }
    return { ok: true, value: stripTrailingSlash(url.toString()) };
  }

  // Production origin: only https is accepted. http:// is rejected.
  if (url.protocol === 'http:') {
    return {
      ok: false,
      error: 'Production API address must use https (http is rejected).',
    };
  }
  if (url.protocol !== 'https:') {
    return { ok: false, error: `API address uses unsupported scheme: ${url.protocol}` };
  }
  return { ok: true, value: stripTrailingSlash(url.toString()) };
}

/**
 * @param {string} raw
 * @returns {{ok: true, value: string} | {ok: false, error: string}}
 */
export function validateWsUrl(raw) {
  if (!raw || typeof raw !== 'string') {
    return { ok: false, error: 'WebSocket address is empty.' };
  }
  const value = raw.trim();
  if (!value) {
    return { ok: false, error: 'WebSocket address is empty.' };
  }

  let url;
  try {
    url = new URL(value);
  } catch {
    return { ok: false, error: `WebSocket address is not a valid URL: ${value}` };
  }

  if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
    return { ok: false, error: `WebSocket address uses unsupported scheme: ${url.protocol}` };
  }
  if (!url.hostname) {
    return { ok: false, error: 'WebSocket address is missing a host.' };
  }

  const isLocalhost =
    url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]';
  const localDev = isLocalDevOrigin();

  // ws:// is only allowed when BOTH the target is loopback AND the page is a
  // local dev origin. A production page must never use ws://.
  if (url.protocol === 'ws:') {
    if (localDev && isLocalhost) {
      return { ok: true, value: ensureWsPath(url) };
    }
    return {
      ok: false,
      error: 'WebSocket address must use wss in production (ws is rejected).',
    };
  }

  // wss:// is always accepted regardless of host.
  return { ok: true, value: ensureWsPath(url) };
}

function stripTrailingSlash(s) {
  return s.replace(/\/+$/, '');
}

function ensureWsPath(url) {
  if (url.pathname === '/' || url.pathname === '') {
    url.pathname = '/ws';
  }
  return stripTrailingSlash(url.toString());
}
