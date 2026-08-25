/**
 * Config Publish Panel (§11.6 item 20 / 提案 10 doc-deferred sub-items).
 *
 * SUPER_ADMIN-only surface for the draft / approve / publish / backup / restore
 * workflow. Backed by nine endpoints (all `/api/settings/*`, all SUPER_ADMIN,
 * all responses masked):
 *   - POST   /api/settings/drafts                  — create a DRAFT (validates YAML)
 *   - GET    /api/settings/drafts?limit=            — draft list, newest first
 *   - GET    /api/settings/drafts/{id}              — full draft detail
 *   - POST   /api/settings/drafts/{id}/approve      — DRAFT→APPROVED (approver!=createdBy)
 *   - POST   /api/settings/drafts/{id}/publish      — APPROVED→PUBLISHED + backup
 *   - DELETE /api/settings/drafts/{id}              — discard a DRAFT
 *   - POST   /api/settings/backup                  — create a labeled backup
 *   - GET    /api/settings/backups?limit=           — backup list, newest first
 *   - POST   /api/settings/restore-from-backup      — roll live config to a backup
 *
 * Self-contained, mirroring ConfigHistory.jsx + CampaignManagement.jsx: calls
 * `api.*` directly, manages its own state, and degrades gracefully on 503
 * (service not enabled) / 403 / 409 / network failure — any failure renders
 * an inline error hint, never a crash or a blank page. State is NOT threaded
 * through useDashboardData.
 *
 * RBAC: the page route is gated by `role === 'SUPER_ADMIN'` in App.jsx (these
 * endpoints are SUPER_ADMIN-only on the backend). We re-check the same guard
 * here so a direct render with a lesser role degrades to a forbidden hint
 * rather than exposing mutation controls.
 *
 * Secret fields: the backend masks every response; the frontend renders the
 * masked value verbatim (`***`) and never attempts to recover the plaintext.
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
  FileEdit,
  ShieldCheck,
  RotateCcw,
  Loader2,
  AlertCircle,
  Eye,
  CheckCircle2,
  Archive,
  History,
  Upload,
} from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';
import Modal from '../ui/Modal';
import { api } from '../../services/api';

const LIST_LIMIT = 50;

const textareaClass =
  'flex w-full min-h-[160px] rounded-md border-0 bg-secondary/55 px-3 py-2 text-xs font-mono transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring text-foreground resize-y';
const inputClass =
  'flex h-8 w-full rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring text-foreground';

function formatTime(ts, locale) {
  if (!ts) return '-';
  try {
    return new Date(Number(ts)).toLocaleString(locale, { hour12: false });
  } catch {
    return '-';
  }
}

// Status -> Badge variant. Mirrors the semantic mapping used across the panel:
// draft=secondary, approved=info, published=success, discarded=destructive.
function draftStatusVariant(status) {
  switch (status) {
    case 'DRAFT':
      return 'secondary';
    case 'APPROVED':
      return 'info';
    case 'PUBLISHED':
      return 'success';
    case 'DISCARDED':
      return 'destructive';
    default:
      return 'secondary';
  }
}

function draftStatusLabel(status, t) {
  switch (status) {
    case 'DRAFT':
      return t('configPublish.status_draft');
    case 'APPROVED':
      return t('configPublish.status_approved');
    case 'PUBLISHED':
      return t('configPublish.status_published');
    case 'DISCARDED':
      return t('configPublish.status_discarded');
    default:
      return status || '-';
  }
}

function ConfigPublishPanel({ theme, mode, onToast, role }) {
  const { t, i18n } = useTranslation();
  const locale = (i18n.language || 'zh_CN').replace(/_/g, '-');
  const canManage = role === 'SUPER_ADMIN';

  // --- Draft list state ---
  const [drafts, setDrafts] = useState([]);
  const [draftsLoading, setDraftsLoading] = useState(false);
  const [draftsError, setDraftsError] = useState(null);
  const [draftsUnavailable, setDraftsUnavailable] = useState(false);

  // --- Backup list state ---
  const [backups, setBackups] = useState([]);
  const [backupsLoading, setBackupsLoading] = useState(false);
  const [backupsError, setBackupsError] = useState(null);
  const [backupsUnavailable, setBackupsUnavailable] = useState(false);

  // --- Draft editor state ---
  const [draftYaml, setDraftYaml] = useState('');
  const [validatePending, setValidatePending] = useState(false);
  const [validateResult, setValidateResult] = useState(null); // { valid, errors, warnings }
  const [validateError, setValidateError] = useState(null);
  const [saveDraftPending, setSaveDraftPending] = useState(false);

  // --- Per-row pending (approve / publish / discard). Read by the row spinner;
  //     the setter is intentionally unused for now (rows are sequential) but
  //     kept so a future per-row disable-on-busy refactor has the hook ready. ---
  // eslint-disable-next-line no-unused-vars
  const [busyDraftId, setBusyDraftId] = useState(null);

  // --- Approve modal ---
  const [approveTarget, setApproveTarget] = useState(null); // { draftId }
  const [approveNote, setApproveNote] = useState('');
  const [approvePending, setApprovePending] = useState(false);
  const [approveError, setApproveError] = useState(null);

  // --- Publish modal ---
  const [publishTarget, setPublishTarget] = useState(null); // { draftId }
  const [publishPending, setPublishPending] = useState(false);
  const [publishError, setPublishError] = useState(null);

  // --- Discard modal ---
  const [discardTarget, setDiscardTarget] = useState(null); // { draftId }
  const [discardPending, setDiscardPending] = useState(false);
  const [discardError, setDiscardError] = useState(null);

  // --- Draft detail modal ---
  const [detailTarget, setDetailTarget] = useState(null); // { draftId }
  const [detailData, setDetailData] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState(null);

  // --- Create backup ---
  const [backupLabel, setBackupLabel] = useState('');
  const [backupPending, setBackupPending] = useState(false);
  const [backupError, setBackupError] = useState(null);

  // --- Restore modal ---
  const [restoreTarget, setRestoreTarget] = useState(null); // { backupId, label, revision }
  const [restorePending, setRestorePending] = useState(false);
  const [restoreError, setRestoreError] = useState(null);

  const fetchDrafts = useCallback(async () => {
    setDraftsLoading(true);
    setDraftsError(null);
    setDraftsUnavailable(false);
    try {
      const res = await api.listDrafts(LIST_LIMIT);
      setDrafts(Array.isArray(res) ? res : []);
    } catch (err) {
      setDrafts([]);
      if (err && err.status === 503) {
        setDraftsUnavailable(true);
      } else {
        setDraftsError(err && err.message ? err.message : String(err));
      }
    } finally {
      setDraftsLoading(false);
    }
  }, []);

  const fetchBackups = useCallback(async () => {
    setBackupsLoading(true);
    setBackupsError(null);
    setBackupsUnavailable(false);
    try {
      const res = await api.listBackups(LIST_LIMIT);
      setBackups(Array.isArray(res) ? res : []);
    } catch (err) {
      setBackups([]);
      if (err && err.status === 503) {
        setBackupsUnavailable(true);
      } else {
        setBackupsError(err && err.message ? err.message : String(err));
      }
    } finally {
      setBackupsLoading(false);
    }
  }, []);

  const refresh = useCallback(() => {
    fetchDrafts();
    fetchBackups();
  }, [fetchDrafts, fetchBackups]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // --- Validate (pre-check before saving a draft) ---
  const handleValidate = useCallback(async () => {
    const trimmed = draftYaml.trim();
    if (!trimmed) {
      setValidateError(t('configPublish.validate_no_yaml'));
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
  }, [draftYaml, t]);

  // --- Save as draft (POST /drafts — backend validates + returns a draft) ---
  const handleSaveDraft = useCallback(async () => {
    const trimmed = draftYaml.trim();
    if (!trimmed || saveDraftPending) return;
    setSaveDraftPending(true);
    try {
      const res = await api.createDraft(trimmed);
      if (onToast) {
        onToast(t('configPublish.toast_draft_created', { draftId: res && res.draftId }), 'success');
      }
      setDraftYaml('');
      setValidateResult(null);
      setValidateError(null);
      await fetchDrafts();
    } catch (err) {
      // 400 + validation report: surface the errors inline.
      if (err && err.status === 400 && err.data) {
        setValidateResult({
          valid: false,
          errors: (err.data.errors || err.data.validation?.errors || []),
          warnings: (err.data.warnings || err.data.validation?.warnings || []),
        });
      } else if (onToast) {
        onToast(
          t('configPublish.approve_failed', { error: (err && err.message) || String(err) }),
          'error'
        );
      }
    } finally {
      setSaveDraftPending(false);
    }
  }, [draftYaml, saveDraftPending, onToast, t, fetchDrafts]);

  // --- Open draft detail ---
  const openDetail = useCallback((draftId) => {
    setDetailTarget({ draftId });
    setDetailData(null);
    setDetailError(null);
    setDetailLoading(true);
    api
      .getDraft(draftId)
      .then((res) => setDetailData(res || null))
      .catch((err) => setDetailError(err && err.message ? err.message : String(err)))
      .finally(() => setDetailLoading(false));
  }, []);

  const closeDetail = useCallback(() => {
    setDetailTarget(null);
    setDetailData(null);
    setDetailError(null);
    setDetailLoading(false);
  }, []);

  // --- Approve flow ---
  const openApprove = useCallback((draftId) => {
    setApproveTarget({ draftId });
    setApproveNote('');
    setApproveError(null);
    setApprovePending(false);
  }, []);

  const closeApprove = useCallback(() => {
    setApproveTarget(null);
    setApproveNote('');
    setApproveError(null);
    setApprovePending(false);
  }, []);

  const handleApprove = useCallback(async () => {
    if (!approveTarget || approvePending) return;
    setApprovePending(true);
    setApproveError(null);
    try {
      await api.approveDraft(approveTarget.draftId, approveNote);
      if (onToast) onToast(t('configPublish.approve_success'), 'success');
      closeApprove();
      await fetchDrafts();
    } catch (err) {
      if (err && err.status === 403) {
        setApproveError(t('configPublish.approve_self_error'));
      } else {
        setApproveError(t('configPublish.approve_failed', { error: (err && err.message) || String(err) }));
      }
    } finally {
      setApprovePending(false);
    }
  }, [approveTarget, approvePending, approveNote, onToast, t, closeApprove, fetchDrafts]);

  // --- Publish flow (destructive: replaces live config) ---
  const openPublish = useCallback((draftId) => {
    setPublishTarget({ draftId });
    setPublishError(null);
    setPublishPending(false);
  }, []);

  const closePublish = useCallback(() => {
    setPublishTarget(null);
    setPublishError(null);
    setPublishPending(false);
  }, []);

  const handlePublish = useCallback(async () => {
    if (!publishTarget || publishPending) return;
    setPublishPending(true);
    setPublishError(null);
    try {
      const res = await api.publishDraft(publishTarget.draftId);
      if (onToast) onToast(t('configPublish.publish_success', { revision: res && res.revision }), 'success');
      closePublish();
      await refresh();
    } catch (err) {
      if (err && err.status === 409) {
        setPublishError(t('configPublish.publish_not_approved'));
      } else {
        setPublishError(t('configPublish.publish_failed', { error: (err && err.message) || String(err) }));
      }
    } finally {
      setPublishPending(false);
    }
  }, [publishTarget, publishPending, onToast, t, closePublish, refresh]);

  // --- Discard flow (DRAFT only) ---
  const openDiscard = useCallback((draftId) => {
    setDiscardTarget({ draftId });
    setDiscardError(null);
    setDiscardPending(false);
  }, []);

  const closeDiscard = useCallback(() => {
    setDiscardTarget(null);
    setDiscardError(null);
    setDiscardPending(false);
  }, []);

  const handleDiscard = useCallback(async () => {
    if (!discardTarget || discardPending) return;
    setDiscardPending(true);
    setDiscardError(null);
    try {
      await api.discardDraft(discardTarget.draftId);
      if (onToast) onToast(t('configPublish.discard_success'), 'success');
      closeDiscard();
      await fetchDrafts();
    } catch (err) {
      setDiscardError(t('configPublish.discard_failed', { error: (err && err.message) || String(err) }));
    } finally {
      setDiscardPending(false);
    }
  }, [discardTarget, discardPending, onToast, t, closeDiscard, fetchDrafts]);

  // --- Create backup ---
  const handleCreateBackup = useCallback(async () => {
    if (backupPending) return;
    setBackupPending(true);
    setBackupError(null);
    try {
      const res = await api.createBackup(backupLabel.trim() || undefined);
      if (onToast) onToast(t('configPublish.create_backup_success', { revision: res && res.revision }), 'success');
      setBackupLabel('');
      await fetchBackups();
    } catch (err) {
      setBackupError(t('configPublish.create_backup_failed', { error: (err && err.message) || String(err) }));
    } finally {
      setBackupPending(false);
    }
  }, [backupPending, backupLabel, onToast, t, fetchBackups]);

  // --- Restore flow (destructive: replaces live config) ---
  const openRestore = useCallback((row) => {
    setRestoreTarget(row);
    setRestoreError(null);
    setRestorePending(false);
  }, []);

  const closeRestore = useCallback(() => {
    setRestoreTarget(null);
    setRestoreError(null);
    setRestorePending(false);
  }, []);

  const handleRestore = useCallback(async () => {
    if (!restoreTarget || restorePending) return;
    setRestorePending(true);
    setRestoreError(null);
    try {
      const res = await api.restoreFromBackup(restoreTarget.backupId);
      if (onToast) onToast(t('configPublish.restore_success', { revision: res && res.revision }), 'success');
      closeRestore();
      await refresh();
    } catch (err) {
      setRestoreError(t('configPublish.restore_failed', { error: (err && err.message) || String(err) }));
    } finally {
      setRestorePending(false);
    }
  }, [restoreTarget, restorePending, onToast, t, closeRestore, refresh]);

  // --- Forbidden guard: a non-SUPER_ADMIN render degrades to a hint. ---
  if (!canManage) {
    return (
      <div className="space-y-4">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('configPublish.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('configPublish.subtitle')}</p>
        </div>
        <Card className="p-4 border-amber-500/30 bg-amber-500/5">
          <div className="flex items-center gap-2 text-amber-700 dark:text-amber-300">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('configPublish.forbidden')}</p>
          </div>
        </Card>
      </div>
    );
  }

  const validationErrors = validateResult && Array.isArray(validateResult.errors) ? validateResult.errors : [];
  const validationWarnings = validateResult && Array.isArray(validateResult.warnings) ? validateResult.warnings : [];

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('configPublish.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('configPublish.subtitle')}</p>
        </div>
        <Button
          variant="outline"
          theme={theme}
          mode={mode}
          onClick={refresh}
          disabled={draftsLoading || backupsLoading}
          aria-label={t('configPublish.refresh')}
        >
          {draftsLoading || backupsLoading ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />}
          {t('configPublish.refresh')}
        </Button>
      </div>

      {/* ===================== Draft editor ===================== */}
      <Card className="p-4 space-y-3">
        <div className="flex items-center gap-2">
          <FileEdit size={16} className="text-muted-foreground shrink-0" />
          <div>
            <h3 className="text-sm font-medium text-foreground">{t('configPublish.drafts_section')}</h3>
            <p className="text-xs text-muted-foreground mt-0.5">{t('configPublish.drafts_hint')}</p>
          </div>
        </div>
        <div className="space-y-2">
          <label className="text-xs font-normal leading-none text-muted-foreground" htmlFor="config-publish-yaml">
            {t('configPublish.draft_yaml_label')}
          </label>
          <textarea
            id="config-publish-yaml"
            value={draftYaml}
            onChange={(e) => setDraftYaml(e.target.value)}
            placeholder={t('configPublish.draft_yaml_placeholder')}
            aria-label={t('configPublish.draft_yaml_label')}
            className={textareaClass}
            spellCheck={false}
          />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            theme={theme}
            mode={mode}
            onClick={handleValidate}
            disabled={validatePending || saveDraftPending}
            aria-label={t('configPublish.validate_button')}
          >
            {validatePending ? <Loader2 size={14} className="animate-spin" /> : <ShieldCheck size={14} />}
            {t('configPublish.validate_button')}
          </Button>
          <Button
            variant="default"
            theme={theme}
            mode={mode}
            onClick={handleSaveDraft}
            disabled={saveDraftPending || validatePending || !draftYaml.trim()}
            aria-label={t('configPublish.save_draft_button')}
          >
            {saveDraftPending ? <Loader2 size={14} className="animate-spin" /> : <FileEdit size={14} />}
            {saveDraftPending ? t('configPublish.save_draft_pending') : t('configPublish.save_draft_button')}
          </Button>
        </div>

        {/* Validate error / result */}
        {validateError && (
          <div className="flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-500/5 p-3 text-amber-700 dark:text-amber-300">
            <AlertCircle size={14} className="shrink-0 mt-0.5" />
            <p className="text-xs">{t('configPublish.validate_error', { error: validateError })}</p>
          </div>
        )}
        {validateResult && validateResult.valid && (
          <div className="flex items-start gap-2 rounded-md border border-emerald-500/30 bg-emerald-500/5 p-3 text-emerald-700 dark:text-emerald-300">
            <CheckCircle2 size={14} className="shrink-0 mt-0.5" />
            <p className="text-xs">{t('configPublish.validate_success')}</p>
          </div>
        )}
        {validateResult && !validateResult.valid && (
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-destructive">
              <AlertCircle size={14} className="shrink-0" />
              <p className="text-xs font-medium">{t('configPublish.validate_failed')}</p>
            </div>
            {validationErrors.length > 0 && (
              <div className="rounded-md border border-destructive/30 bg-destructive/5 divide-y divide-border">
                <p className="px-3 py-1 text-[10px] uppercase tracking-wide text-muted-foreground">
                  {t('configPublish.validate_errors')}
                </p>
                {validationErrors.map((entry, i) => {
                  const message = (entry && entry.message) || String(entry);
                  const path = entry && entry.path != null ? entry.path : null;
                  return (
                    <div key={i} className="px-3 py-1.5">
                      {path != null && <p className="text-[11px] font-mono text-foreground break-all">{path}</p>}
                      <p className="text-[11px] text-muted-foreground break-all">{message}</p>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}
        {validationWarnings.length > 0 && (
          <div className="rounded-md border border-amber-500/30 bg-amber-500/5 divide-y divide-border">
            <p className="px-3 py-1 text-[10px] uppercase tracking-wide text-muted-foreground">
              {t('configPublish.validate_warnings')}
            </p>
            {validationWarnings.map((entry, i) => {
              const message = (entry && entry.message) || String(entry);
              const path = entry && entry.path != null ? entry.path : null;
              return (
                <div key={i} className="px-3 py-1.5">
                  {path != null && <p className="text-[11px] font-mono text-foreground break-all">{path}</p>}
                  <p className="text-[11px] text-muted-foreground break-all">{message}</p>
                </div>
              );
            })}
          </div>
        )}
      </Card>

      {/* ===================== Draft list ===================== */}
      <Card className="p-0 overflow-hidden">
        <div className="px-4 py-3 border-b border-border flex items-center gap-2">
          <History size={16} className="text-muted-foreground shrink-0" />
          <h3 className="text-sm font-medium text-foreground">{t('configPublish.draft_list_title')}</h3>
        </div>
        {draftsLoading && drafts.length === 0 ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 size={20} className="animate-spin text-muted-foreground" />
            <span className="text-xs text-muted-foreground ml-2">{t('configPublish.loading')}</span>
          </div>
        ) : draftsUnavailable ? (
          <div className="py-16 text-center text-muted-foreground">
            <AlertCircle size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('configPublish.load_failed', { error: t('configPublish.forbidden') })}</p>
          </div>
        ) : drafts.length === 0 ? (
          <div className="py-16 text-center text-muted-foreground">
            <FileEdit size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('configPublish.draft_empty')}</p>
          </div>
        ) : draftsError ? (
          <div className="py-8 text-center text-muted-foreground">
            <AlertCircle size={28} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('configPublish.load_failed', { error: draftsError })}</p>
            <Button variant="ghost" size="sm" theme={theme} mode={mode} onClick={fetchDrafts} className="mt-3">
              <RotateCcw size={13} /> {t('configPublish.refresh')}
            </Button>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_draft_id')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_status')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_created_at')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_created_by')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_approved_at')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_published_at')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap text-right">{t('configPublish.col_actions')}</th>
                </tr>
              </thead>
              <tbody>
                {drafts.map((row, idx) => {
                  const id = row.draftId != null ? row.draftId : idx;
                  const status = row.status;
                  const isBusy = busyDraftId === id;
                  return (
                    <tr
                      key={id}
                      className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors align-top"
                    >
                      <td className="px-4 py-2.5 whitespace-nowrap font-mono text-foreground text-[11px]">{row.draftId || '-'}</td>
                      <td className="px-4 py-2.5 whitespace-nowrap">
                        <Badge variant={draftStatusVariant(status)}>{draftStatusLabel(status, t)}</Badge>
                      </td>
                      <td className="px-4 py-2.5 whitespace-nowrap text-muted-foreground">{formatTime(row.createdAt, locale)}</td>
                      <td className="px-4 py-2.5 whitespace-nowrap text-foreground">{row.createdBy || '-'}</td>
                      <td className="px-4 py-2.5 whitespace-nowrap text-muted-foreground">{formatTime(row.approvedAt, locale)}</td>
                      <td className="px-4 py-2.5 whitespace-nowrap text-muted-foreground">
                        {row.publishedAt ? (
                          <span>
                            {formatTime(row.publishedAt, locale)}
                            {row.publishedRevision != null && (
                              <span className="opacity-70 ml-1">#{row.publishedRevision}</span>
                            )}
                          </span>
                        ) : '-'}
                      </td>
                      <td className="px-4 py-2.5 whitespace-nowrap text-right">
                        <div className="inline-flex items-center gap-1.5">
                          {isBusy && <Loader2 size={13} className="animate-spin text-muted-foreground" />}
                          <Button
                            variant="ghost"
                            size="sm"
                            theme={theme}
                            mode={mode}
                            onClick={() => openDetail(row.draftId)}
                            title={t('configPublish.action_view')}
                            aria-label={t('configPublish.action_view')}
                          >
                            <Eye size={13} />
                          </Button>
                          {status === 'DRAFT' && (
                            <>
                              <Button
                                variant="ghost"
                                size="sm"
                                theme={theme}
                                mode={mode}
                                onClick={() => openApprove(row.draftId)}
                                title={t('configPublish.action_approve')}
                              >
                                <ShieldCheck size={13} /> {t('configPublish.action_approve')}
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                theme={theme}
                                mode={mode}
                                className="text-destructive hover:text-destructive"
                                onClick={() => openDiscard(row.draftId)}
                                title={t('configPublish.action_discard')}
                                aria-label={t('configPublish.action_discard')}
                              >
                                <RotateCcw size={13} />
                              </Button>
                            </>
                          )}
                          {status === 'APPROVED' && (
                            <Button
                              variant="ghost"
                              size="sm"
                              theme={theme}
                              mode={mode}
                              onClick={() => openPublish(row.draftId)}
                              title={t('configPublish.action_publish')}
                            >
                              <Upload size={13} /> {t('configPublish.action_publish')}
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

      {/* ===================== Backups ===================== */}
      <Card className="p-0 overflow-hidden">
        <div className="px-4 py-3 border-b border-border flex items-center gap-2">
          <Archive size={16} className="text-muted-foreground shrink-0" />
          <h3 className="text-sm font-medium text-foreground">{t('configPublish.backups_section')}</h3>
        </div>
        <div className="px-4 py-3 border-b border-border space-y-2">
          <p className="text-xs text-muted-foreground">{t('configPublish.backups_hint')}</p>
          <div className="flex flex-wrap items-center gap-2">
            <label className="text-xs font-normal leading-none text-muted-foreground" htmlFor="config-backup-label">
              {t('configPublish.create_backup_label')}
            </label>
            <input
              id="config-backup-label"
              type="text"
              value={backupLabel}
              onChange={(e) => setBackupLabel(e.target.value)}
              placeholder={t('configPublish.create_backup_placeholder')}
              className={`${inputClass} max-w-xs`}
              aria-label={t('configPublish.create_backup_label')}
            />
            <Button
              variant="default"
              theme={theme}
              mode={mode}
              onClick={handleCreateBackup}
              disabled={backupPending}
              aria-label={t('configPublish.create_backup_button')}
            >
              {backupPending ? <Loader2 size={14} className="animate-spin" /> : <Archive size={14} />}
              {backupPending ? t('configPublish.create_backup_pending') : t('configPublish.create_backup_button')}
            </Button>
          </div>
          {backupError && (
            <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
              <AlertCircle size={14} className="shrink-0 mt-0.5" />
              <p className="text-xs">{backupError}</p>
            </div>
          )}
        </div>
        {backupsLoading && backups.length === 0 ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 size={20} className="animate-spin text-muted-foreground" />
            <span className="text-xs text-muted-foreground ml-2">{t('configPublish.loading')}</span>
          </div>
        ) : backupsUnavailable ? (
          <div className="py-16 text-center text-muted-foreground">
            <AlertCircle size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('configPublish.load_failed', { error: t('configPublish.forbidden') })}</p>
          </div>
        ) : backups.length === 0 ? (
          <div className="py-16 text-center text-muted-foreground">
            <Archive size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('configPublish.backup_empty')}</p>
          </div>
        ) : backupsError ? (
          <div className="py-8 text-center text-muted-foreground">
            <AlertCircle size={28} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('configPublish.load_failed', { error: backupsError })}</p>
            <Button variant="ghost" size="sm" theme={theme} mode={mode} onClick={fetchBackups} className="mt-3">
              <RotateCcw size={13} /> {t('configPublish.refresh')}
            </Button>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_backup_id')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_label')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_revision')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_created_at')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('configPublish.col_created_by')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap text-right">{t('configPublish.col_actions')}</th>
                </tr>
              </thead>
              <tbody>
                {backups.map((row, idx) => (
                  <tr
                    key={row.backupId != null ? row.backupId : idx}
                    className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors align-top"
                  >
                    <td className="px-4 py-2.5 whitespace-nowrap font-mono text-foreground text-[11px]">{row.backupId || '-'}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap text-foreground">{row.label || '-'}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap font-mono text-foreground">#{row.revision != null ? row.revision : '-'}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap text-muted-foreground">{formatTime(row.createdAt, locale)}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap text-foreground">{row.createdBy || '-'}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        theme={theme}
                        mode={mode}
                        className="text-destructive hover:text-destructive"
                        onClick={() => openRestore(row)}
                        title={t('configPublish.action_restore')}
                        aria-label={t('configPublish.action_restore')}
                      >
                        <RotateCcw size={13} /> {t('configPublish.action_restore')}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* ===================== Modals ===================== */}

      {/* Draft detail modal — masked YAML + validation + metadata. */}
      <Modal
        isOpen={!!detailTarget}
        onClose={closeDetail}
        title={detailTarget ? t('configPublish.draft_detail_modal_title', { draftId: detailTarget.draftId }) : ''}
        theme={theme}
        mode={mode}
      >
        {detailLoading ? (
          <div className="flex items-center justify-center py-10">
            <Loader2 size={18} className="animate-spin text-muted-foreground" />
            <span className="text-xs text-muted-foreground ml-2">{t('configPublish.loading')}</span>
          </div>
        ) : detailError ? (
          <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
            <AlertCircle size={14} className="shrink-0 mt-0.5" />
            <p className="text-xs">{t('configPublish.load_failed', { error: detailError })}</p>
          </div>
        ) : (
          <div className="space-y-3">
            <p className="text-xs text-muted-foreground">{t('configPublish.draft_yaml_masked_hint')}</p>
            {detailData && (
              <div className="space-y-1 text-xs">
                <p><span className="text-muted-foreground">{t('configPublish.col_status')}:</span> <Badge variant={draftStatusVariant(detailData.status)}>{draftStatusLabel(detailData.status, t)}</Badge></p>
                <p><span className="text-muted-foreground">{t('configPublish.col_created_by')}:</span> <span className="text-foreground">{detailData.createdBy || '-'}</span></p>
                {detailData.approvedBy && <p><span className="text-muted-foreground">{t('configPublish.action_approve')}:</span> <span className="text-foreground">{detailData.approvedBy}</span></p>}
                {detailData.publishedRevision != null && <p><span className="text-muted-foreground">{t('configPublish.col_revision')}:</span> <span className="text-foreground font-mono">#{detailData.publishedRevision}</span></p>}
              </div>
            )}
            <pre className="max-h-[50vh] overflow-auto rounded-md border border-border bg-muted/30 p-3 text-[11px] font-mono leading-relaxed text-foreground whitespace-pre-wrap break-all">
              {detailData && detailData.draft_yaml ? detailData.draft_yaml : ''}
            </pre>
            {detailData && detailData.validation && !detailData.validation.valid && Array.isArray(detailData.validation.errors) && detailData.validation.errors.length > 0 && (
              <div className="rounded-md border border-destructive/30 bg-destructive/5 divide-y divide-border">
                <p className="px-3 py-1 text-[10px] uppercase tracking-wide text-muted-foreground">{t('configPublish.validate_errors')}</p>
                {detailData.validation.errors.map((entry, i) => {
                  const message = (entry && entry.message) || String(entry);
                  const path = entry && entry.path != null ? entry.path : null;
                  return (
                    <div key={i} className="px-3 py-1.5">
                      {path != null && <p className="text-[11px] font-mono text-foreground break-all">{path}</p>}
                      <p className="text-[11px] text-muted-foreground break-all">{message}</p>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </Modal>

      {/* Approve modal */}
      <Modal
        isOpen={!!approveTarget}
        onClose={!approvePending ? closeApprove : undefined}
        title={approveTarget ? t('configPublish.approve_modal_title', { draftId: approveTarget.draftId }) : ''}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground" htmlFor="approve-note">
              {t('configPublish.approve_note_label')}
            </label>
            <textarea
              id="approve-note"
              value={approveNote}
              onChange={(e) => setApproveNote(e.target.value)}
              placeholder={t('configPublish.approve_note_placeholder')}
              className={textareaClass}
            />
          </div>
          {approveError && (
            <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
              <AlertCircle size={14} className="shrink-0 mt-0.5" />
              <p className="text-xs">{approveError}</p>
            </div>
          )}
          <div className="flex justify-end gap-2 pt-1">
            <Button variant="ghost" theme={theme} mode={mode} onClick={closeApprove} disabled={approvePending}>
              {t('configPublish.approve_cancel')}
            </Button>
            <Button variant="default" theme={theme} mode={mode} onClick={handleApprove} disabled={approvePending}>
              {approvePending ? <Loader2 size={14} className="animate-spin" /> : <ShieldCheck size={14} />}
              {approvePending ? t('configPublish.approve_pending') : t('configPublish.approve_confirm')}
            </Button>
          </div>
        </div>
      </Modal>

      {/* Publish modal (destructive confirm) */}
      <Modal
        isOpen={!!publishTarget}
        onClose={!publishPending ? closePublish : undefined}
        title={publishTarget ? t('configPublish.publish_modal_title', { draftId: publishTarget.draftId }) : ''}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          <p className="text-xs text-foreground">{t('configPublish.publish_confirm_body')}</p>
          {publishError && (
            <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
              <AlertCircle size={14} className="shrink-0 mt-0.5" />
              <p className="text-xs">{publishError}</p>
            </div>
          )}
          <div className="flex justify-end gap-2 pt-1">
            <Button variant="ghost" theme={theme} mode={mode} onClick={closePublish} disabled={publishPending}>
              {t('configPublish.publish_cancel')}
            </Button>
            <Button variant="destructive" theme={theme} mode={mode} onClick={handlePublish} disabled={publishPending}>
              {publishPending ? <Loader2 size={14} className="animate-spin" /> : <Upload size={14} />}
              {publishPending ? t('configPublish.publish_pending') : t('configPublish.publish_confirm')}
            </Button>
          </div>
        </div>
      </Modal>

      {/* Discard modal (destructive confirm) */}
      <Modal
        isOpen={!!discardTarget}
        onClose={!discardPending ? closeDiscard : undefined}
        title={discardTarget ? t('configPublish.discard_modal_title', { draftId: discardTarget.draftId }) : ''}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          <p className="text-xs text-foreground">{t('configPublish.discard_confirm_body')}</p>
          {discardError && (
            <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
              <AlertCircle size={14} className="shrink-0 mt-0.5" />
              <p className="text-xs">{discardError}</p>
            </div>
          )}
          <div className="flex justify-end gap-2 pt-1">
            <Button variant="ghost" theme={theme} mode={mode} onClick={closeDiscard} disabled={discardPending}>
              {t('configPublish.discard_cancel')}
            </Button>
            <Button variant="destructive" theme={theme} mode={mode} onClick={handleDiscard} disabled={discardPending}>
              {discardPending ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />}
              {discardPending ? t('configPublish.discard_pending') : t('configPublish.discard_confirm')}
            </Button>
          </div>
        </div>
      </Modal>

      {/* Restore modal (destructive confirm) */}
      <Modal
        isOpen={!!restoreTarget}
        onClose={!restorePending ? closeRestore : undefined}
        title={t('configPublish.restore_modal_title')}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          <p className="text-xs text-foreground">
            {restoreTarget
              ? t('configPublish.restore_confirm_body', {
                  label: restoreTarget.label || '-',
                  revision: restoreTarget.revision != null ? restoreTarget.revision : '-',
                })
              : ''}
          </p>
          {restoreError && (
            <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
              <AlertCircle size={14} className="shrink-0 mt-0.5" />
              <p className="text-xs">{restoreError}</p>
            </div>
          )}
          <div className="flex justify-end gap-2 pt-1">
            <Button variant="ghost" theme={theme} mode={mode} onClick={closeRestore} disabled={restorePending}>
              {t('configPublish.restore_cancel')}
            </Button>
            <Button variant="destructive" theme={theme} mode={mode} onClick={handleRestore} disabled={restorePending}>
              {restorePending ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />}
              {restorePending ? t('configPublish.restore_pending') : t('configPublish.restore_confirm')}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}

export default ConfigPublishPanel;
