/**
 * Status Page — read-only observability surface (proposal 09 / §11.6 project 17).
 *
 * Consumes three endpoints, all polled on a 15s timer:
 *   - GET /api/health   — liveness/readiness JSON (unauth, status/version/uptime)
 *   - GET /api/metrics  — auth-gated Prometheus exposition text
 *   - GET /api/status    — existing authenticated status payload (optional; used
 *     only as a fallback signal — every value shown also has a primary source in
 *     health or metrics, so a /status failure never blanks the page).
 *
 * The metrics endpoint returns Prometheus text (text/plain; version=0.0.4).
 * We hand-parse it into a Map<key, value> via parsePrometheusText (exported for
 * unit testing) — no client library. Every metric is OPTIONAL: the backend
 * metrics agent is shipping incrementally, and the contract grew over time, so
 * any field that is not yet emitted renders as "—" and the page never crashes.
 *
 * The component is props-driven for SSR (renderToStaticMarkup does NOT run
 * useEffect): `initialHealth`/`initialMetrics`/`initialStatus` let the test
 * harness render a populated snapshot without driving the poll loop.
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Activity,
  RefreshCw,
  Loader2,
  AlertCircle,
  Server as ServerIcon,
  Database,
  Webhook as WebhookIcon,
  Layers,
  Cpu,
  FileText,
  CheckCircle2,
  AlertTriangle,
  XCircle,
} from 'lucide-react';

import Card from '../ui/Card';
import Button from '../ui/Button';
import { api, getHealth, getMetrics } from '../../services/api';

const POLL_INTERVAL_MS = 15_000;
const PLACEHOLDER = '—';

/**
 * Parse Prometheus exposition text into a lookup structure.
 *
 * Each non-comment, non-blank line is `name{labels} value` or `name value`.
 * We key by `name` plus the sorted `labels` string so metrics with the same
 * name but different label sets (e.g. nova_link_webhook_deliveries_total{result="..."})
 * are distinguishable. The returned object has:
 *   - byName: Map<metricName, Array<{labels, value}>>
 *   - lookup(name, labelKey): fastest path for a known label value, e.g.
 *     lookup('nova_link_webhook_deliveries_total', 'accepted') matches the
 *     {result="accepted"} series and returns its numeric value (or null).
 *
 * Lines we cannot match (blank, # comment, malformed) are skipped silently —
 * a partial/garbled metrics body must never throw.
 * @param {string} text
 * @returns {{byName: Map<string, Array<{labels: object, value: number, raw: string}>>, lookup: (name: string, labelValue?: string) => (number|null)}}
 */
// eslint-disable-next-line react-refresh/only-export-components -- test-harness export; component + parser co-located so StatusPage.test.js can import both from one module
export function parsePrometheusText(text) {
  const byName = new Map();
  if (typeof text !== 'string' || text.length === 0) {
    return { byName, lookup: () => null };
  }

  // `^(\w+)(\{[^}]*\})?\s+(.+)$` — metric name, optional {labels}, then value.
  const lineRe = /^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(.+)$/;

  for (const rawLine of text.split('\n')) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const m = line.match(lineRe);
    if (!m) continue;
    const name = m[1];
    const labels = {};
    if (m[2]) {
      // {k="v",k2="v2"} → { k: v, k2: v2 }
      const inner = m[2].slice(1, -1);
      for (const pair of inner.split(',')) {
        const eq = pair.indexOf('=');
        if (eq === -1) continue;
        const k = pair.slice(0, eq).trim();
        let v = pair.slice(eq + 1).trim();
        if (v.startsWith('"') && v.endsWith('"')) v = v.slice(1, -1);
        labels[k] = v;
      }
    }
    const valueRaw = m[3].trim();
    const value = Number(valueRaw);
    const entry = { labels, value: Number.isFinite(value) ? value : valueRaw, raw: valueRaw };
    if (!byName.has(name)) byName.set(name, []);
    byName.get(name).push(entry);
  }

  const lookup = (name, labelValue) => {
    const entries = byName.get(name);
    if (!entries || entries.length === 0) return null;
    if (labelValue === undefined) return entries[0].value;
    // Match any series whose labels contain the given value (covers the
    // {result="accepted"} convention used by the webhook delivery counter).
    for (const e of entries) {
      for (const v of Object.values(e.labels)) {
        if (v === labelValue) return e.value;
      }
    }
    return null;
  };

  return { byName, lookup };
}

/**
 * Format an uptime in milliseconds as a compact human-readable duration, e.g.
 * "2h 3m", "5m 10s", "45s". Falls back to PLACEHOLDER on garbage input.
 */
function formatUptime(uptimeMillis) {
  const ms = Number(uptimeMillis);
  if (!Number.isFinite(ms) || ms < 0) return PLACEHOLDER;
  const totalSec = Math.floor(ms / 1000);
  const days = Math.floor(totalSec / 86400);
  const hours = Math.floor((totalSec % 86400) / 3600);
  const minutes = Math.floor((totalSec % 3600) / 60);
  const seconds = totalSec % 60;
  const parts = [];
  if (days) parts.push(`${days}d`);
  if (hours) parts.push(`${hours}h`);
  if (minutes) parts.push(`${minutes}m`);
  if (!days && !hours) parts.push(`${seconds}s`);
  return parts.length ? parts.join(' ') : '0s';
}

function formatTs(ts, locale) {
  const n = Number(ts);
  if (!Number.isFinite(n) || n <= 0) return PLACEHOLDER;
  try {
    return new Date(n).toLocaleString(locale, { hour12: false });
  } catch {
    return PLACEHOLDER;
  }
}

function MetricRow({ label, value, warn }) {
  return (
    <div className="flex items-center justify-between gap-3 py-1.5 border-b border-border/60 last:border-0">
      <span className="text-xs text-muted-foreground truncate">{label}</span>
      <span
        className={`text-xs font-mono whitespace-nowrap ${warn ? 'text-amber-600 dark:text-amber-400 font-semibold' : 'text-foreground'}`}
      >
        {value}
      </span>
    </div>
  );
}

function SectionCard({ icon: Icon, title, children, accent }) {
  return (
    <Card className="p-4">
      <div className="flex items-center gap-2 mb-3">
        <div className={`shrink-0 size-7 rounded-md flex items-center justify-center ${accent || 'bg-primary/10 text-primary'}`}>
          {Icon && <Icon size={15} />}
        </div>
        <h3 className="text-sm font-medium text-foreground">{title}</h3>
      </div>
      <div className="space-y-0.5">{children}</div>
    </Card>
  );
}

function StatusBadge({ status, t }) {
  let cls = 'bg-muted text-muted-foreground';
  let Icon = Activity;
  let label = t('status.unknown');
  if (status === 'up') {
    cls = 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400';
    Icon = CheckCircle2;
    label = t('status.up');
  } else if (status === 'degraded') {
    cls = 'bg-amber-500/15 text-amber-600 dark:text-amber-400';
    Icon = AlertTriangle;
    label = t('status.degraded');
  } else if (status === 'down') {
    cls = 'bg-destructive/15 text-destructive';
    Icon = XCircle;
    label = t('status.down');
  }
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${cls}`}>
      <Icon size={13} /> {label}
    </span>
  );
}

function StatusPage({ theme, mode, initialHealth, initialMetrics, initialStatus }) {
  void theme; void mode; // token-driven styling; props kept for layout idiom parity
  const { t, i18n } = useTranslation();
  const locale = (i18n.language || 'zh_CN').replace(/_/g, '-');

  const [health, setHealth] = useState(initialHealth || null);
  const [metricsText, setMetricsText] = useState(initialMetrics || '');
  const [statusData, setStatusData] = useState(initialStatus || null); // eslint-disable-line no-unused-vars -- polled /api/status stored for future per-field breakdown; all currently-rendered values source from health+metrics, so /status is a defensive fallback signal
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [lastUpdated, setLastUpdated] = useState(null);
  const mountedRef = useRef(true);

  const poll = useCallback(async () => {
    setLoading(true);
    setError(null);
    // Fetch all three in parallel; a single endpoint failure must NOT abort the
    // others — each is rendered defensively with PLACEHOLDER fallbacks.
    const results = await Promise.allSettled([
      getHealth(),
      getMetrics(),
      api.status().catch(() => null),
    ]);
    if (!mountedRef.current) return;
    if (results[0].status === 'fulfilled') setHealth(results[0].value);
    if (results[1].status === 'fulfilled') setMetricsText(results[1].value);
    if (results[2].status === 'fulfilled' && results[2].value) setStatusData(results[2].value);
    const failures = results.filter((r) => r.status === 'rejected').map((r) => r.reason);
    if (failures.length === 3) {
      // Every endpoint failed — surface the first error.
      setError(failures[0]?.message || String(failures[0]));
    } else if (failures.length > 0) {
      // Partial failure: keep the data we got, note partial in lastUpdated.
      setError(null);
    }
    setLastUpdated(Date.now());
    setLoading(false);
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- kick the first poll immediately so the page isn't blank for 15s; subsequent updates arrive via setInterval
    poll();
    const id = setInterval(poll, POLL_INTERVAL_MS);
    return () => {
      mountedRef.current = false;
      clearInterval(id);
    };
  }, [poll]);

  const metrics = parsePrometheusText(metricsText);
  const checks = (health && health.checks) || {};
  const conn = checks.connections || {};
  const db = checks.database || {};

  // Queue depth / capacity: warn when depth exceeds 80% of capacity. Each
  // metric pair is optional (backend may not emit it yet) → PLACEHOLDER.
  const queueRatio = (depth, cap) => {
    const d = Number(depth);
    const c = Number(cap);
    if (!Number.isFinite(d) || !Number.isFinite(c) || c <= 0) return null;
    return d / c;
  };

  const controlDepth = metrics.lookup('nova_link_control_queue_depth');
  const controlCap = metrics.lookup('nova_link_control_queue_capacity');
  const controlWarn = queueRatio(controlDepth, controlCap) >= 0.8;

  const msgDepth = metrics.lookup('nova_link_message_queue_depth');
  const msgCap = metrics.lookup('nova_link_message_queue_capacity');
  const msgWarn = queueRatio(msgDepth, msgCap) >= 0.8;

  const whDepth = metrics.lookup('nova_link_webhook_delivery_queue_depth');
  const whCap = metrics.lookup('nova_link_webhook_delivery_queue_capacity');
  const whWarn = queueRatio(whDepth, whCap) >= 0.8;

  // Dropped packets: a labeled counter nova_link_packets_dropped_total{packet_id="..."}.
  // The "total" label is the aggregate; others are per-packet-id breakdowns.
  const droppedEntries = metrics.byName.get('nova_link_packets_dropped_total') || [];
  const droppedTotal = metrics.lookup('nova_link_packets_dropped_total', 'total');
  const droppedByPacket = droppedEntries.filter((e) => e.labels.packet_id && e.labels.packet_id !== 'total');

  const wsSessions = metrics.lookup('nova_link_ws_sessions_active');
  const configRevision = metrics.lookup('nova_link_config_revision');

  const webhookResults = ['accepted', 'rejected', 'succeeded', 'failed', 'completed'].map(
    (r) => ({ result: r, value: metrics.lookup('nova_link_webhook_deliveries_total', r) })
  );
  const pendingRetries = metrics.lookup('nova_link_webhook_pending_retries');
  const retriesRejected = metrics.lookup('nova_link_webhook_retries_rejected_total');
  const attemptsRejected = metrics.lookup('nova_link_webhook_attempts_rejected_total');

  const fmt = (v) => (v === null || v === undefined || (typeof v === 'number' && !Number.isFinite(v)) ? PLACEHOLDER : String(v));

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('status.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('status.subtitle')}</p>
        </div>
        <div className="flex items-center gap-2">
          {lastUpdated && (
            <span className="text-[11px] text-muted-foreground">
              {t('status.last_updated', { time: formatTs(lastUpdated, locale) })}
            </span>
          )}
          <Button variant="outline" theme={theme} mode={mode} onClick={poll} disabled={loading}>
            {loading ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
            {t('status.refresh')}
          </Button>
        </div>
      </div>

      {/* Error banner (total failure only) */}
      {error && (
        <Card className="p-3 border-destructive/30 bg-destructive/5">
          <div className="flex items-center gap-2 text-destructive">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('status.load_failed', { error })}</p>
          </div>
        </Card>
      )}

      {/* Overview */}
      <SectionCard icon={Activity} title={t('status.overview')}>
        <div className="flex items-center justify-between gap-3 py-1.5 border-b border-border/60">
          <span className="text-xs text-muted-foreground">{t('status.status_label')}</span>
          <StatusBadge status={health && health.status} t={t} />
        </div>
        <MetricRow label={t('status.version')} value={fmt(health && health.version)} />
        <MetricRow label={t('status.uptime')} value={health && health.uptimeMillis != null ? formatUptime(health.uptimeMillis) : PLACEHOLDER} />
        <MetricRow label={t('status.timestamp')} value={health && health.timestamp ? formatTs(health.timestamp, locale) : PLACEHOLDER} />
      </SectionCard>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Connections */}
        <SectionCard icon={ServerIcon} title={t('status.connections')}>
          <MetricRow label={t('status.conn_physical')} value={fmt(conn.physical)} />
          <MetricRow label={t('status.conn_authenticated')} value={fmt(conn.authenticated)} />
        </SectionCard>

        {/* Queues */}
        <SectionCard icon={Layers} title={t('status.queues')}>
          <MetricRow label={t('status.queue_control')} value={fmt(controlDepth)} warn={controlWarn} />
          <MetricRow label={t('status.queue_control_capacity')} value={fmt(controlCap)} />
          <MetricRow label={t('status.queue_message')} value={fmt(msgDepth)} warn={msgWarn} />
          <MetricRow label={t('status.queue_message_capacity')} value={fmt(msgCap)} />
          <MetricRow
            label={t('status.dropped_packets')}
            value={fmt(droppedTotal)}
            warn={droppedTotal != null && droppedTotal > 0}
          />
          {droppedByPacket.length > 0 && (
            <div className="pt-2 mt-1 border-t border-border/60">
              <p className="text-[11px] text-muted-foreground mb-1">{t('status.dropped_by_packet')}</p>
              {droppedByPacket.map((e) => (
                <MetricRow key={e.labels.packet_id} label={`packet ${e.labels.packet_id}`} value={fmt(e.value)} />
              ))}
            </div>
          )}
        </SectionCard>

        {/* WebSocket sessions */}
        <SectionCard icon={Cpu} title={t('status.ws_sessions')}>
          <MetricRow label={t('status.ws_active')} value={fmt(wsSessions)} />
        </SectionCard>

        {/* Message delivery / webhooks */}
        <SectionCard icon={WebhookIcon} title={t('status.webhook')}>
          {webhookResults.map(({ result, value }) => (
            <MetricRow key={result} label={t(`status.webhook_${result}`)} value={fmt(value)} />
          ))}
          <MetricRow label={t('status.webhook_queue_depth')} value={fmt(whDepth)} warn={whWarn} />
          <MetricRow label={t('status.webhook_queue_capacity')} value={fmt(whCap)} />
          <MetricRow label={t('status.webhook_pending_retries')} value={fmt(pendingRetries)} />
          <MetricRow label={t('status.webhook_retries_rejected')} value={fmt(retriesRejected)} />
          <MetricRow label={t('status.webhook_attempts_rejected')} value={fmt(attemptsRejected)} />
        </SectionCard>

        {/* Database */}
        <SectionCard
          icon={Database}
          title={t('status.database')}
          accent={db.healthy === false ? 'bg-destructive/15 text-destructive' : undefined}
        >
          <div className="flex items-center justify-between gap-3 py-1.5 border-b border-border/60">
            <span className="text-xs text-muted-foreground">{t('status.db_healthy')}</span>
            <span className={`text-xs font-mono ${db.healthy === false ? 'text-destructive' : 'text-foreground'}`}>
              {db.healthy === true ? t('status.yes') : db.healthy === false ? t('status.no') : PLACEHOLDER}
            </span>
          </div>
          <MetricRow label={t('status.db_available')} value={db.available === true ? t('status.yes') : db.available === false ? t('status.no') : PLACEHOLDER} />
          <MetricRow label={t('status.db_alive_metric')} value={fmt(metrics.lookup('nova_link_db_alive'))} />
        </SectionCard>

        {/* Config revision */}
        <SectionCard icon={FileText} title={t('status.config_revision')}>
          <MetricRow label={t('status.config_revision_value')} value={fmt(configRevision)} />
        </SectionCard>
      </div>

      {/* Queue capacity warning footer */}
      {(controlWarn || msgWarn || whWarn) && (
        <Card className="p-3 border-amber-500/40 bg-amber-500/5">
          <div className="flex items-center gap-2 text-amber-600 dark:text-amber-400">
            <AlertTriangle size={14} className="shrink-0" />
            <p className="text-xs">{t('status.queue_capacity_warning')}</p>
          </div>
        </Card>
      )}
    </div>
  );
}

export default StatusPage;
