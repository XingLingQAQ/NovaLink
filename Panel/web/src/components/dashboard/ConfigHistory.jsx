/**
 * Config History — browse masked config snapshots, diff two revisions, and
 * (SUPER_ADMIN only) roll the live config back to a chosen revision
 * (§11.6 Project 20 / PANEL proposal 10).
 *
 * Backed by four endpoints (all ADMIN+ except rollback, which is
 * SUPER_ADMIN-only and enforced by the backend):
 *   - GET /api/settings/history?limit=   — snapshot metadata, newest first
 *   - GET /api/settings/snapshots/{rev}   — the masked JSON payload for one rev
 *   - GET /api/settings/diff?from=&to=     — added/removed/changed between revs
 *   - POST /api/settings/rollback          — roll the live config back to a rev
 *
 * Self-contained, mirroring AuditLog.jsx: calls `api.*` directly, manages its
 * own state, and degrades gracefully on 503 (service not enabled) / 404 (revision
 * absent) / 400 / 500 — any failure renders an inline error hint, never a crash
 * or a blank page. State is NOT threaded through useDashboardData.
 *
 * The `formatDiffSections` helper is exported (and unit-tested SSR-side) so the
 * diff-rendering logic is covered without driving the fetch/effect lifecycle.
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
  GitBranch,
  RotateCcw,
  Loader2,
  AlertCircle,
  Eye,
  GitCompare,
  ShieldCheck,
  CheckCircle2,
  FileCheck2,
} from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Modal from '../ui/Modal';
import { api } from '../../services/api';
import { can } from '../../lib/permissions';

const HISTORY_LIMIT = 50;

/**
 * Turn a raw diff payload `{ added, removed, changed }` into an ordered array
 * of `{ label, entries }` sections for rendering. Each `entries` item is a
 * `{ key, value }` pair where `value` is a pre-stringified representation so
 * the renderer can dump it verbatim without re-knowing the shape.
 *
 * Defensive by design: the backend contract describes added/removed as JSON
 * values (paths present on one side only) and changed as a map of path ->
 * {from,to}, but the exact shape may evolve. Any non-empty section whose value
 * we can iterate is rendered; anything unrecognized falls back to a single
 * `JSON.stringify` entry so the operator still sees the raw payload.
 * @param {{added?: any, removed?: any, changed?: any}|null|undefined} diff
 * @returns {Array<{label: 'added'|'removed'|'changed', entries: Array<{key: string, value: string}>}>}
 */
// eslint-disable-next-line react-refresh/only-export-components -- test-harness export; component + helper co-located so ConfigHistory.test.js can import both from one module
export function formatDiffSections(diff) {
  if (!diff || typeof diff !== 'object') return [];
  const sections = [];

  const toEntries = (val) => {
    // Object map of path -> value (the common backend shape). Preserve order.
    if (val && typeof val === 'object' && !Array.isArray(val)) {
      const out = [];
      for (const k of Object.keys(val)) {
        out.push({ key: k, value: safeStringify(val[k]) });
      }
      return out;
    }
    // An array, a primitive, or anything else: one synthetic entry so the
    // operator still sees the raw value rather than a silently empty section.
    return [{ key: '*', value: safeStringify(val) }];
  };

  if (diff.added !== undefined) {
    const entries = toEntries(diff.added);
    if (entries.length) sections.push({ label: 'added', entries });
  }
  if (diff.removed !== undefined) {
    const entries = toEntries(diff.removed);
    if (entries.length) sections.push({ label: 'removed', entries });
  }
  if (diff.changed !== undefined) {
    const entries = toEntries(diff.changed);
    if (entries.length) sections.push({ label: 'changed', entries });
  }
  return sections;
}

function safeStringify(value) {
  if (value === null) return 'null';
  if (value === undefined) return 'undefined';
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

function ConfigHistory({ theme, mode, role }) {
  const { t, i18n } = useTranslation();
  const locale = (i18n.language || 'zh_CN').replace(/_/g, '-');
  const canRollback = can(role, 'config.rollback');
  const canValidate = can(role, 'settings.history');

  // History list state.
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [serviceUnavailable, setServiceUnavailable] = useState(false);

  // Snapshot detail modal.
  const [snapshotTarget, setSnapshotTarget] = useState(null); // { revision }
  const [snapshotData, setSnapshotData] = useState(null);
  const [snapshotLoading, setSnapshotLoading] = useState(false);
  const [snapshotError, setSnapshotError] = useState(null);

  // Diff modal: operator picks two revisions via radio buttons in the table.
  const [diffFrom, setDiffFrom] = useState(null);
  const [diffTo, setDiffTo] = useState(null);
  const [diffOpen, setDiffOpen] = useState(false);
  const [diffData, setDiffData] = useState(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffError, setDiffError] = useState(null);

  // Rollback confirm modal.
  const [rollbackTarget, setRollbackTarget] = useState(null); // { revision }
  const [rollbackPending, setRollbackPending] = useState(false);
  const [rollbackError, setRollbackError] = useState(null);
  const [rollbackSuccess, setRollbackSuccess] = useState(null); // { rolledBackTo, revision }

  // --- Validate section (§11.6 提案 10 / item 20 缺口 A) ---
  // Paste-YAML validate card. Reuses the same Card/Button primitives as the
  // history list. State is local and independent of the history fetch
  // lifecycle: a validate run never mutates live config (POST /validate is
  // side-effect-free on the backend). RBAC: the whole ConfigHistory page is
  // already gated by `can(role,'settings.history')` in App.jsx, and we re-check
  // the same capability below so a future VIEWER-with-route-access still sees
  // no validate button.
  const [validateYaml, setValidateYaml] = useState('');
  const [validatePending, setValidatePending] = useState(false);
  const [validateResult, setValidateResult] = useState(null); // { valid, errors, warnings, revision, checkedAt }
  const [validateError, setValidateError] = useState(null);

  const handleValidate = useCallback(async () => {
    const trimmed = validateYaml.trim();
    if (!trimmed) {
      setValidateError(t('configHistory.validate_no_yaml'));
      setValidateResult(null);
      return;
    }
    setValidatePending(true);
    setValidateError(null);
    setValidateResult(null);
    try {
      const res = await api.validateConfig(trimmed);
      setValidateResult(res || null);
    } catch (err) {
      setValidateError(err && err.message ? err.message : String(err));
    } finally {
      setValidatePending(false);
    }
  }, [validateYaml, t]);

  const fetchHistory = useCallback(async () => {
    setLoading(true);
    setError(null);
    setServiceUnavailable(false);
    try {
      const res = await api.getConfigHistory(HISTORY_LIMIT);
      const list = res && Array.isArray(res.items) ? res.items : [];
      setItems(list);
    } catch (err) {
      setItems([]);
      if (err && err.status === 503) {
        setServiceUnavailable(true);
      } else {
        setError(err && err.message ? err.message : String(err));
      }
    } finally {
      setLoading(false);
    }
  }, []);

  // Initial load.
  useEffect(() => {
    fetchHistory();
  }, [fetchHistory]);

  // Fetch the masked snapshot payload when the snapshot modal opens.
  const openSnapshot = useCallback((revision) => {
    setSnapshotTarget({ revision });
    setSnapshotData(null);
    setSnapshotError(null);
    setSnapshotLoading(true);
    api
      .getConfigSnapshot(revision)
      .then((res) => {
        setSnapshotData(res && res.snapshot ? res.snapshot : res);
      })
      .catch((err) => {
        setSnapshotError(err && err.message ? err.message : String(err));
      })
      .finally(() => setSnapshotLoading(false));
  }, []);

  const closeSnapshot = useCallback(() => {
    setSnapshotTarget(null);
    setSnapshotData(null);
    setSnapshotError(null);
    setSnapshotLoading(false);
  }, []);

  // Diff: requires two distinct revisions selected from the table.
  const openDiff = useCallback(async () => {
    if (diffFrom == null || diffTo == null || diffFrom === diffTo) return;
    setDiffOpen(true);
    setDiffData(null);
    setDiffError(null);
    setDiffLoading(true);
    try {
      const res = await api.getConfigDiff(diffFrom, diffTo);
      setDiffData(res || null);
    } catch (err) {
      setDiffError(err && err.message ? err.message : String(err));
    } finally {
      setDiffLoading(false);
    }
  }, [diffFrom, diffTo]);

  const closeDiff = useCallback(() => {
    setDiffOpen(false);
    setDiffData(null);
    setDiffError(null);
    setDiffLoading(false);
  }, []);

  // Rollback: confirm modal -> POST -> refresh history on success.
  const openRollback = useCallback((revision) => {
    setRollbackTarget({ revision });
    setRollbackError(null);
    setRollbackSuccess(null);
  }, []);

  const closeRollback = useCallback(() => {
    setRollbackTarget(null);
    setRollbackError(null);
    setRollbackSuccess(null);
    setRollbackPending(false);
  }, []);

  const handleRollback = useCallback(async () => {
    if (!rollbackTarget) return;
    setRollbackPending(true);
    setRollbackError(null);
    setRollbackSuccess(null);
    try {
      const res = await api.rollbackConfig(rollbackTarget.revision);
      setRollbackSuccess({
        rolledBackTo: res && res.rolledBackTo,
        revision: res && res.revision,
      });
      // Refresh the list so the new snapshot + active badge show up.
      fetchHistory();
    } catch (err) {
      const status = err && err.status;
      if (status === 400) {
        setRollbackError(t('configHistory.rollback_already_active'));
      } else if (status === 404) {
        setRollbackError(t('configHistory.rollback_not_found'));
      } else {
        setRollbackError(err && err.message ? err.message : String(err));
      }
    } finally {
      setRollbackPending(false);
    }
  }, [rollbackTarget, fetchHistory, t]);

  const formatTime = useCallback(
    (ts) => {
      if (!ts) return '-';
      try {
        return new Date(Number(ts)).toLocaleString(locale, { hour12: false });
      } catch {
        return '-';
      }
    },
    [locale]
  );

  const diffSections = diffData ? formatDiffSections(diffData) : [];
  const diffIsEmpty =
    diffData &&
    diffSections.length === 0 &&
    diffData.added === undefined &&
    diffData.removed === undefined &&
    diffData.changed === undefined;

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('configHistory.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('configHistory.subtitle')}</p>
        </div>
        <Button
          variant="outline"
          theme={theme}
          mode={mode}
          onClick={fetchHistory}
          disabled={loading}
          aria-label={t('configHistory.refresh')}
        >
          {loading ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />}
          {t('configHistory.refresh')}
        </Button>
      </div>

      {/* Error hint (network failure / non-503). */}
      {error && (
        <Card className="p-3 border-destructive/30 bg-destructive/5">
          <div className="flex items-center gap-2 text-destructive">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('configHistory.load_failed', { error })}</p>
          </div>
        </Card>
      )}

      {/* Service-unavailable hint (503 — the backend has no ConfigHistoryService). */}
      {serviceUnavailable && (
        <Card className="p-3 border-amber-500/30 bg-amber-500/5">
          <div className="flex items-center gap-2 text-amber-700 dark:text-amber-300">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('configHistory.service_unavailable')}</p>
          </div>
        </Card>
      )}

      {/* Diff selection hint + action. */}
      {diffFrom != null && diffTo != null && diffFrom !== diffTo && (
        <Card className="p-3">
          <div className="flex items-center justify-between gap-2 flex-wrap">
            <p className="text-xs text-muted-foreground">
              {t('configHistory.diff_from')}: <span className="font-medium text-foreground">#{diffFrom}</span>
              {' → '}
              {t('configHistory.diff_to')}: <span className="font-medium text-foreground">#{diffTo}</span>
            </p>
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={openDiff}
              aria-label={t('configHistory.view_diff')}
            >
              <GitCompare size={14} /> {t('configHistory.view_diff')}
            </Button>
          </div>
        </Card>
      )}

      {/* History table. */}
      <Card className="p-0 overflow-hidden">
        {loading && items.length === 0 ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 size={20} className="animate-spin text-muted-foreground" />
            <span className="text-xs text-muted-foreground ml-2">{t('configHistory.loading')}</span>
          </div>
        ) : items.length === 0 ? (
          <div className="py-16 text-center text-muted-foreground">
            <GitBranch size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('configHistory.empty')}</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configHistory.col_revision')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configHistory.col_created_at')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configHistory.col_created_by')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configHistory.col_status')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap text-right">{t('configHistory.view_snapshot')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((row, idx) => {
                  const rev = row.revision;
                  const isActive = !!row.active;
                  return (
                    <tr
                      key={row.id != null ? row.id : idx}
                      className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors align-top"
                    >
                      <td className="px-4 py-2 whitespace-nowrap font-mono text-foreground">
                        <span className="inline-flex items-center gap-2">
                          {/* Two radio columns folded into one: pick From or To. */}
                          <label className="inline-flex items-center gap-1 cursor-pointer" title={t('configHistory.diff_from')}>
                            <input
                              type="radio"
                              name="diffFrom"
                              value={String(rev)}
                              checked={diffFrom === rev}
                              onChange={() => setDiffFrom(rev)}
                              className="accent-primary"
                              aria-label={t('configHistory.diff_from') + ' #' + rev}
                            />
                          </label>
                          <label className="inline-flex items-center gap-1 cursor-pointer" title={t('configHistory.diff_to')}>
                            <input
                              type="radio"
                              name="diffTo"
                              value={String(rev)}
                              checked={diffTo === rev}
                              onChange={() => setDiffTo(rev)}
                              className="accent-primary"
                              aria-label={t('configHistory.diff_to') + ' #' + rev}
                            />
                          </label>
                          #{rev}
                        </span>
                      </td>
                      <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{formatTime(row.createdAt)}</td>
                      <td className="px-4 py-2 whitespace-nowrap text-foreground">{row.createdBy || '-'}</td>
                      <td className="px-4 py-2 whitespace-nowrap">
                        {isActive ? (
                          <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                            <ShieldCheck size={12} /> {t('configHistory.active_badge')}
                          </span>
                        ) : (
                          <span className="text-muted-foreground">-</span>
                        )}
                      </td>
                      <td className="px-4 py-2 whitespace-nowrap text-right">
                        <div className="inline-flex items-center gap-1.5">
                          <Button
                            variant="ghost"
                            size="sm"
                            theme={theme}
                            mode={mode}
                            onClick={() => openSnapshot(rev)}
                            aria-label={t('configHistory.view_snapshot') + ' #' + rev}
                          >
                            <Eye size={13} /> {t('configHistory.view_snapshot')}
                          </Button>
                          {canRollback && !isActive && (
                            <Button
                              variant="ghost"
                              size="sm"
                              theme={theme}
                              mode={mode}
                              onClick={() => openRollback(rev)}
                              aria-label={t('configHistory.rollback') + ' #' + rev}
                            >
                              <RotateCcw size={13} /> {t('configHistory.rollback')}
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Snapshot detail modal. */}
      <Modal
        isOpen={!!snapshotTarget}
        onClose={closeSnapshot}
        title={snapshotTarget ? t('configHistory.snapshot_modal_title', { revision: snapshotTarget.revision }) : ''}
        theme={theme}
        mode={mode}
      >
        {snapshotLoading ? (
          <div className="flex items-center justify-center py-10">
            <Loader2 size={18} className="animate-spin text-muted-foreground" />
            <span className="text-xs text-muted-foreground ml-2">{t('configHistory.loading')}</span>
          </div>
        ) : snapshotError ? (
          <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
            <AlertCircle size={14} className="shrink-0 mt-0.5" />
            <p className="text-xs">{t('configHistory.snapshot_load_failed', { error: snapshotError })}</p>
          </div>
        ) : (
          <pre className="max-h-[60vh] overflow-auto rounded-md border border-border bg-muted/30 p-3 text-[11px] leading-relaxed text-foreground whitespace-pre-wrap break-all">
            {snapshotData ? JSON.stringify(snapshotData, null, 2) : ''}
          </pre>
        )}
      </Modal>

      {/* Diff modal. */}
      <Modal
        isOpen={diffOpen}
        onClose={closeDiff}
        title={t('configHistory.diff_modal_title')}
        theme={theme}
        mode={mode}
      >
        {diffLoading ? (
          <div className="flex items-center justify-center py-10">
            <Loader2 size={18} className="animate-spin text-muted-foreground" />
            <span className="text-xs text-muted-foreground ml-2">{t('configHistory.loading')}</span>
          </div>
        ) : diffError ? (
          <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
            <AlertCircle size={14} className="shrink-0 mt-0.5" />
            <p className="text-xs">{t('configHistory.diff_load_failed', { error: diffError })}</p>
          </div>
        ) : diffIsEmpty ? (
          <p className="text-xs text-muted-foreground py-6 text-center">{t('configHistory.diff_empty')}</p>
        ) : diffSections.length === 0 ? (
          <p className="text-xs text-muted-foreground py-6 text-center">{t('configHistory.diff_empty')}</p>
        ) : (
          <div className="space-y-3">
            {diffSections.map((section) => (
              <div key={section.label}>
                <p className="text-[10px] uppercase tracking-wide text-muted-foreground mb-1">
                  {t('configHistory.diff_' + section.label)}
                </p>
                <div className="rounded-md border border-border bg-muted/30 divide-y divide-border">
                  {section.entries.map((entry, i) => (
                    <div key={entry.key + '-' + i} className="px-3 py-1.5">
                      <p className="text-[11px] font-mono text-foreground break-all">{entry.key}</p>
                      <pre className="mt-0.5 text-[10px] text-muted-foreground whitespace-pre-wrap break-all">{entry.value}</pre>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </Modal>

      {/* Rollback confirm modal (SUPER_ADMIN only). */}
      <Modal
        isOpen={!!rollbackTarget}
        onClose={closeRollback}
        title={t('configHistory.rollback_confirm_title')}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          <p className="text-xs text-foreground">
            {rollbackTarget ? t('configHistory.rollback_confirm_body', { revision: rollbackTarget.revision }) : ''}
          </p>

          {rollbackError && (
            <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
              <AlertCircle size={14} className="shrink-0 mt-0.5" />
              <p className="text-xs">{t('configHistory.rollback_failed', { error: rollbackError })}</p>
            </div>
          )}

          {rollbackSuccess && (
            <div className="flex items-start gap-2 rounded-md border border-emerald-500/30 bg-emerald-500/5 p-3 text-emerald-700 dark:text-emerald-300">
              <ShieldCheck size={14} className="shrink-0 mt-0.5" />
              <p className="text-xs">
                {t('configHistory.rollback_success', {
                  rolledBackTo: rollbackSuccess.rolledBackTo,
                  revision: rollbackSuccess.revision,
                })}
              </p>
            </div>
          )}

          <div className="flex justify-end gap-2 pt-1">
            <Button
              variant="ghost"
              theme={theme}
              mode={mode}
              onClick={closeRollback}
              disabled={rollbackPending}
            >
              {t('configHistory.rollback_cancel')}
            </Button>
            {!rollbackSuccess && (
              <Button
                variant="destructive"
                theme={theme}
                mode={mode}
                onClick={handleRollback}
                disabled={rollbackPending}
              >
                {rollbackPending ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />}
                {t('configHistory.rollback_confirm')}
              </Button>
            )}
          </div>
        </div>
      </Modal>

      {/* Validate config card (§11.6 提案 10 / item 20 缺口 A).
          RBAC: the whole page is already gated by can(role,'settings.history')
          in App.jsx (ADMIN+). We re-check the same capability here so a
          VIEWER that somehow reaches the route still sees no validate button. */}
      {canValidate && (
        <Card className="p-4 space-y-3">
          <div className="flex items-center gap-2">
            <FileCheck2 size={16} className="text-muted-foreground shrink-0" />
            <div>
              <h3 className="text-sm font-medium text-foreground">{t('configHistory.validate_title')}</h3>
              <p className="text-xs text-muted-foreground mt-0.5">{t('configHistory.validate_hint')}</p>
            </div>
          </div>
          <textarea
            value={validateYaml}
            onChange={(e) => setValidateYaml(e.target.value)}
            placeholder={'server:\n  name: NovaLink\n  maxPlayers: 20'}
            aria-label={t('configHistory.validate_title')}
            className="w-full min-h-[120px] rounded-md border border-border bg-muted/30 p-3 text-[11px] font-mono text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-1 focus:ring-ring resize-y"
            spellCheck={false}
          />
          <div className="flex items-center gap-2">
            <Button
              variant="default"
              theme={theme}
              mode={mode}
              onClick={handleValidate}
              disabled={validatePending}
              aria-label={t('configHistory.validate_button')}
            >
              {validatePending ? <Loader2 size={14} className="animate-spin" /> : <ShieldCheck size={14} />}
              {t('configHistory.validate_button')}
            </Button>
            {validatePending && (
              <span className="text-xs text-muted-foreground">{t('configHistory.validate_pending')}</span>
            )}
          </div>

          {/* Network / 503 error: degraded inline message. */}
          {validateError && (
            <div className="flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-500/5 p-3 text-amber-700 dark:text-amber-300">
              <AlertCircle size={14} className="shrink-0 mt-0.5" />
              <p className="text-xs">{t('configHistory.validate_error', { error: validateError })}</p>
            </div>
          )}

          {/* Valid result: green success hint with revision if present. */}
          {validateResult && validateResult.valid && (
            <div className="flex items-start gap-2 rounded-md border border-emerald-500/30 bg-emerald-500/5 p-3 text-emerald-700 dark:text-emerald-300">
              <CheckCircle2 size={14} className="shrink-0 mt-0.5" />
              <p className="text-xs">
                {validateResult.revision != null
                  ? t('configHistory.validate_success_with_revision', { revision: validateResult.revision })
                  : t('configHistory.validate_success')}
              </p>
            </div>
          )}

          {/* Invalid result: red error list. path is optional — when null only
              the message is shown. */}
          {validateResult && !validateResult.valid && (
            <div className="space-y-2">
              <div className="flex items-center gap-2 text-destructive">
                <AlertCircle size={14} className="shrink-0" />
                <p className="text-xs font-medium">{t('configHistory.validate_failed')}</p>
              </div>
              {Array.isArray(validateResult.errors) && validateResult.errors.length > 0 && (
                <div className="rounded-md border border-destructive/30 bg-destructive/5 divide-y divide-border">
                  <p className="px-3 py-1 text-[10px] uppercase tracking-wide text-muted-foreground">
                    {t('configHistory.validate_errors')}
                  </p>
                  {validateResult.errors.map((entry, i) => {
                    const message = (entry && entry.message) || String(entry);
                    const path = entry && entry.path != null ? entry.path : null;
                    return (
                      <div key={i} className="px-3 py-1.5">
                        {path != null && (
                          <p className="text-[11px] font-mono text-foreground break-all">{path}</p>
                        )}
                        <p className="text-[11px] text-muted-foreground break-all">{message}</p>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </Card>
      )}
    </div>
  );
}

export default ConfigHistory;
