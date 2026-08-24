import test from 'node:test';
import assert from 'node:assert/strict';

// Proposal 09 / §11.6 project 17 — StatusPage component + Prometheus parser tests.
//
// Test harness note (mirrors ModerationManagement.test.js): the project runs
// `node --test` (NOT vitest) with a custom ESM loader (_test/loader.mjs) that
// transpiles .jsx imports. This file is plain .js using React.createElement so
// it needs no transpile and is discoverable by `node --test`.
//
// SSR limitation (verified): renderToStaticMarkup does NOT run useEffect, so
// the 15s poll loop never fires during a render. The component is props-driven
// via `initialHealth`/`initialMetrics`/`initialStatus` so a single SSR render
// produces a populated snapshot without driving the effect. We assert:
//   (a) render-without-crash + title + section headings,
//   (b) the three status-badge states (up/degraded/down) render,
//   (c) graceful degradation — missing metrics render "—" instead of crashing,
//   (d) the Prometheus text parser (parsePrometheusText) extracts the right
//       name/labels/value structure from a representative metrics body.

import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

// Importing i18n.js initializes the i18next instance (registers zh_CN/en_US
// resources) so t() returns real translations, not bare keys.
import '../../i18n.js';
import StatusPage, { parsePrometheusText } from './StatusPage.jsx';

// Safety-net fetch stub. Effects don't fire in renderToStaticMarkup so the
// component never calls fetch during a render, but we keep the global patched
// so an unexpected import-time fetch can't hit the network.
function withFetch() {
  const original = globalThis.fetch;
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    text: async () => '',
  });
  return () => { globalThis.fetch = original; };
}

const HEALTH_UP = {
  status: 'up',
  version: '1.0.0',
  uptimeMillis: 7380000, // 2h 3m
  timestamp: 1690000000000,
  checks: {
    connections: { healthy: true, physical: 5, authenticated: 3 },
    channels: { healthy: true, total: 12 },
    announcements: { healthy: true, total: 2 },
    database: { healthy: true, available: true },
  },
};

const HEALTH_DEGRADED = {
  status: 'degraded',
  version: '1.0.0',
  uptimeMillis: 5000,
  timestamp: 1690000000000,
  checks: {
    connections: { healthy: true, physical: 2, authenticated: 1 },
    channels: { healthy: true, total: 4 },
    announcements: { healthy: false, total: 0 },
    database: { healthy: true, available: true },
  },
};

const HEALTH_DOWN = {
  status: 'down',
  version: '1.0.0',
  uptimeMillis: 1000,
  timestamp: 1690000000000,
  checks: {
    connections: { healthy: true, physical: 0, authenticated: 0 },
    channels: { healthy: true, total: 0 },
    announcements: { healthy: true, total: 0 },
    database: { healthy: false, available: true },
  },
};

const METRICS_BODY = [
  '# HELP nova_link_uptime_seconds JVM uptime since backend start.',
  '# TYPE nova_link_uptime_seconds gauge',
  'nova_link_uptime_seconds 123',
  '',
  '# HELP nova_link_connections_active Number of client connections by state.',
  '# TYPE nova_link_connections_active gauge',
  'nova_link_connections_active{state="physical"} 5',
  'nova_link_connections_active{state="authenticated"} 3',
  '',
  '# HELP nova_link_db_alive Database connectivity (1=connected, 0=disconnected).',
  '# TYPE nova_link_db_alive gauge',
  'nova_link_db_alive 1',
  '',
  '# HELP nova_link_webhook_deliveries_total Logical webhook deliveries by terminal result.',
  '# TYPE nova_link_webhook_deliveries_total counter',
  'nova_link_webhook_deliveries_total{result="accepted"} 5',
  'nova_link_webhook_deliveries_total{result="failed"} 1',
  '',
  '# HELP nova_link_packets_dropped_total Dropped packets by id.',
  '# TYPE nova_link_packets_dropped_total counter',
  'nova_link_packets_dropped_total{packet_id="3"} 2',
  'nova_link_packets_dropped_total{packet_id="total"} 2',
  '',
  'nova_link_control_queue_depth 3',
  'nova_link_control_queue_capacity 1024',
  'nova_link_message_queue_depth 12',
  'nova_link_message_queue_capacity 10000',
  'nova_link_ws_sessions_active 5',
  'nova_link_config_revision 7',
].join('\n');

function renderSP(extra = {}) {
  return renderToStaticMarkup(
    React.createElement(StatusPage, {
      theme: 'clean',
      mode: 'light',
      initialHealth: HEALTH_UP,
      initialMetrics: METRICS_BODY,
      ...extra,
    })
  );
}

// ====================== Prometheus parser ======================

test('parsePrometheusText: extracts name + labels + value from a representative body', () => {
  const { byName, lookup } = parsePrometheusText(METRICS_BODY);
  assert.ok(byName.has('nova_link_uptime_seconds'), 'uptime metric present');
  assert.equal(lookup('nova_link_uptime_seconds'), 123, 'bare metric returns its value');
  assert.equal(lookup('nova_link_db_alive'), 1);

  // Labeled counter: lookup by label value resolves the right series.
  assert.equal(lookup('nova_link_webhook_deliveries_total', 'accepted'), 5);
  assert.equal(lookup('nova_link_webhook_deliveries_total', 'failed'), 1);
  assert.equal(lookup('nova_link_webhook_deliveries_total', 'succeeded'), null, 'absent label → null');

  // Unlabeled metrics emitted without # HELP/# TYPE still parse.
  assert.equal(lookup('nova_link_ws_sessions_active'), 5);
  assert.equal(lookup('nova_link_config_revision'), 7);
});

test('parsePrometheusText: comment + blank lines are skipped, malformed lines do not throw', () => {
  // `barename` has no ` <value>` suffix so it does not match the line regex and
  // is skipped. A line like `garbage line with no value` DOES match (name +
  // space + value) and is kept with a non-numeric value — that is expected
  // Prometheus-format behavior; only structural junk is dropped.
  const { byName, lookup } = parsePrometheusText(
    '# HELP foo bar\n# TYPE foo counter\n\n  \nfoo 7\nbarename\ngarbage line with no value\n'
  );
  assert.equal(lookup('foo'), 7);
  assert.ok(byName.has('garbage'), 'name-with-text-value line is kept (non-numeric value stored as string)');
  assert.equal(byName.get('garbage')[0].value, 'line with no value');
  assert.ok(!byName.has('barename'), 'line with no value suffix is dropped');
  assert.equal(byName.size, 2, 'comment/blank/valueless lines are not registered');
});

test('parsePrometheusText: empty / non-string input yields an empty map and a null lookup', () => {
  assert.equal(parsePrometheusText('').byName.size, 0);
  assert.equal(parsePrometheusText('').lookup('anything'), null);
  assert.equal(parsePrometheusText(null).lookup('anything'), null);
  assert.equal(parsePrometheusText(undefined).byName.size, 0);
});

test('parsePrometheusText: packets_dropped_total preserves per-label breakdown', () => {
  const { byName, lookup } = parsePrometheusText(METRICS_BODY);
  const entries = byName.get('nova_link_packets_dropped_total');
  assert.equal(entries.length, 2, 'two series (packet_id=3 + packet_id=total)');
  assert.equal(lookup('nova_link_packets_dropped_total', 'total'), 2);
  assert.equal(lookup('nova_link_packets_dropped_total', '3'), 2);
});

// ====================== Rendering ======================

test('StatusPage renders without crashing and shows the title + section headings', () => {
  const restore = withFetch();
  try {
    const html = renderSP();
    assert.ok(html.length > 0, 'rendered output must be non-empty');
    // i18n status.title -> "系统状态" in zh_CN (default locale).
    assert.ok(html.includes('系统状态'), 'title heading present');
    // All five required section headings render.
    assert.ok(html.includes('总览'), 'overview section present');
    assert.ok(html.includes('连接'), 'connections section present');
    assert.ok(html.includes('队列'), 'queues section present');
    assert.ok(html.includes('Webhook'), 'webhook section present');
    assert.ok(html.includes('数据库'), 'database section present');
    // Config revision section.
    assert.ok(html.includes('配置 Revision'));
  } finally {
    restore();
  }
});

test('StatusPage renders the "up" badge when health.status === "up"', () => {
  const restore = withFetch();
  try {
    const html = renderSP({ initialHealth: HEALTH_UP });
    // i18n status.up -> "正常".
    assert.ok(html.includes('正常'), 'up badge renders');
    assert.ok(!html.includes('宕机'), 'down badge absent');
  } finally {
    restore();
  }
});

test('StatusPage renders the "degraded" badge when health.status === "degraded"', () => {
  const restore = withFetch();
  try {
    const html = renderSP({ initialHealth: HEALTH_DEGRADED });
    // i18n status.degraded -> "降级".
    assert.ok(html.includes('降级'), 'degraded badge renders');
  } finally {
    restore();
  }
});

test('StatusPage renders the "down" badge when health.status === "down"', () => {
  const restore = withFetch();
  try {
    const html = renderSP({ initialHealth: HEALTH_DOWN });
    // i18n status.down -> "宕机".
    assert.ok(html.includes('宕机'), 'down badge renders');
  } finally {
    restore();
  }
});

test('StatusPage renders metric values pulled from the parsed Prometheus body', () => {
  const restore = withFetch();
  try {
    const html = renderSP();
    // uptime 7380000ms → "2h 3m".
    assert.ok(html.includes('2h 3m'), 'uptime is formatted human-readable');
    // physical connections from health.checks.connections.
    assert.ok(html.includes('>5<'), 'physical connection count rendered');
    // config revision from metrics.
    assert.ok(html.includes('7'), 'config revision value rendered');
  } finally {
    restore();
  }
});

test('StatusPage degrades gracefully when metrics are absent: missing fields render "—" and the page does not crash', () => {
  const restore = withFetch();
  try {
    // Empty metrics body → every metric-driven field falls back to "—".
    const html = renderSP({ initialMetrics: '' });
    assert.ok(html.length > 0, 'rendered without crashing');
    assert.ok(html.includes('—'), 'placeholder shown for missing metrics');
    // Title + sections still present.
    assert.ok(html.includes('系统状态'));
    assert.ok(html.includes('队列'));
  } finally {
    restore();
  }
});

test('StatusPage degrades gracefully when health is absent too (renders placeholder, no crash)', () => {
  const restore = withFetch();
  try {
    const html = renderSP({ initialHealth: null, initialMetrics: '' });
    assert.ok(html.length > 0, 'rendered without crashing with no health data');
    // status badge falls back to "未知" (unknown).
    assert.ok(html.includes('未知'), 'unknown status badge rendered when health is null');
  } finally {
    restore();
  }
});
