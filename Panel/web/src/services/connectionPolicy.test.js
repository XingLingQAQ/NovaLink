import test from 'node:test';
import assert from 'node:assert/strict';

import {
  validateApiUrl,
  validateWsUrl,
  isLocalDevOrigin,
} from './connectionPolicy.js';

// Helper: swap window.location for the duration of a test so the policy's
// "am I on a production page?" check can be steered. Restores the original.
function withLocation(location, fn) {
  const original = globalThis.window;
  globalThis.window = { location };
  try {
    return fn();
  } finally {
    if (original === undefined) {
      delete globalThis.window;
    } else {
      globalThis.window = original;
    }
  }
}

// ============================ isLocalDevOrigin ============================

test('isLocalDevOrigin: true on localhost/127.0.0.1', () => {
  withLocation({ hostname: 'localhost' }, () => {
    assert.equal(isLocalDevOrigin(), true);
  });
  withLocation({ hostname: '127.0.0.1' }, () => {
    assert.equal(isLocalDevOrigin(), true);
  });
});

test('isLocalDevOrigin: false on a production hostname', () => {
  withLocation({ hostname: 'panel.example.com' }, () => {
    assert.equal(isLocalDevOrigin(), false);
  });
});

// ============================ validateApiUrl ============================

test('validateApiUrl: same-origin relative path is always accepted', () => {
  const r = validateApiUrl('/api');
  assert.equal(r.ok, true);
  assert.equal(r.value, '/api');
});

test('validateApiUrl: https URL to production host is accepted', () => {
  const r = validateApiUrl('https://panel.example.com/api');
  assert.equal(r.ok, true);
  assert.equal(r.value, 'https://panel.example.com/api');
});

test('validateApiUrl: trailing slash is stripped', () => {
  const r = validateApiUrl('https://panel.example.com/api/');
  assert.equal(r.ok, true);
  assert.equal(r.value, 'https://panel.example.com/api');
});

test('validateApiUrl: production rejects plaintext http', () => {
  const r = validateApiUrl('http://panel.example.com/api');
  assert.equal(r.ok, false);
  assert.match(r.error, /https/i);
});

test('validateApiUrl: localhost http is allowed (dev)', () => {
  const r = validateApiUrl('http://localhost:8080/api');
  assert.equal(r.ok, true);
  assert.equal(r.value, 'http://localhost:8080/api');
});

test('validateApiUrl: 127.0.0.1 http is allowed (dev)', () => {
  const r = validateApiUrl('http://127.0.0.1:8080/api');
  assert.equal(r.ok, true);
  assert.equal(r.value, 'http://127.0.0.1:8080/api');
});

test('validateApiUrl: file scheme is rejected', () => {
  const r = validateApiUrl('file:///etc/passwd');
  assert.equal(r.ok, false);
});

test('validateApiUrl: empty input is rejected', () => {
  assert.equal(validateApiUrl('').ok, false);
  assert.equal(validateApiUrl('   ').ok, false);
  assert.equal(validateApiUrl(null).ok, false);
});

// ============================ validateWsUrl ============================

test('validateWsUrl: wss to production host is accepted and path normalized', () => {
  const r = validateWsUrl('wss://panel.example.com');
  assert.equal(r.ok, true);
  assert.equal(r.value, 'wss://panel.example.com/ws');
});

test('validateWsUrl: wss keeps an explicit /ws path', () => {
  const r = validateWsUrl('wss://panel.example.com/ws');
  assert.equal(r.ok, true);
  assert.equal(r.value, 'wss://panel.example.com/ws');
});

test('validateWsUrl: ws to production host is rejected', () => {
  const r = validateWsUrl('ws://panel.example.com/ws');
  assert.equal(r.ok, false);
  assert.match(r.error, /wss/i);
});

test('validateWsUrl: on a localhost page, ws to localhost is allowed', () => {
  withLocation({ hostname: 'localhost' }, () => {
    const r = validateWsUrl('ws://localhost:8889/ws');
    assert.equal(r.ok, true);
    assert.equal(r.value, 'ws://localhost:8889/ws');
  });
});

test('validateWsUrl: on a production page, ws to localhost is rejected', () => {
  withLocation({ hostname: 'panel.example.com' }, () => {
    const r = validateWsUrl('ws://localhost:8889/ws');
    assert.equal(r.ok, false);
  });
});

test('validateWsUrl: non-ws/wss scheme is rejected', () => {
  const r = validateWsUrl('http://panel.example.com/ws');
  assert.equal(r.ok, false);
});

test('validateWsUrl: empty input is rejected', () => {
  assert.equal(validateWsUrl('').ok, false);
  assert.equal(validateWsUrl(null).ok, false);
});
