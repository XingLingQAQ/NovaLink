import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ShieldAlert,
  Loader2,
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Search,
  RotateCcw,
  UserCog,
  Gavel,
  FileText,
  Lock,
} from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Label from '../ui/Label';
import CustomSelect from '../ui/CustomSelect';
import Modal from '../ui/Modal';
import { cn } from '../../lib/cn';
import { api } from '../../services/api';
import { can } from '../../lib/permissions';

const PAGE_SIZE = 20;

const STATUS_OPTIONS = [
  { value: '', label: 'moderation.filter_all_status' },
  { value: 'OPEN', label: 'moderation.status_open' },
  { value: 'IN_PROGRESS', label: 'moderation.status_in_progress' },
  { value: 'RESOLVED', label: 'moderation.status_resolved' },
];

const RESOLVE_ACTIONS = [
  { value: '', label: 'moderation.resolve_action_placeholder' },
  { value: 'warn', label: 'moderation.action_warn' },
  { value: 'mute', label: 'moderation.action_mute' },
  { value: 'ban', label: 'moderation.action_ban' },
  { value: 'kick', label: 'moderation.action_kick' },
  { value: 'dismiss', label: 'moderation.action_dismiss' },
];

const EMPTY_FILTERS = { status: '', assigned: '' };

/**
 * Moderation Management (PANEL-007).
 *
 * Lists moderation cases (GET /api/moderation/cases) with status + assigned
 * filters and 1-based pagination. Selecting a row opens a detail Modal that
 * shows the case fields plus a read-only minimal evidence window
 * (GET /api/moderation/cases/{id}/evidence) — the ONLY path to private-chat
 * content, scoped to a legitimate case.
 *
 * Capabilities:
 *   - moderation.view  (ADMIN / SUPER_ADMIN) → see the page + open detail
 *   - moderation.manage (ADMIN / SUPER_ADMIN) → assign + resolve
 *
 * VIEWER never reaches this page (the sidebar entry + App.jsx route are both
 * capability-gated), and the detail Modal's assign/resolve controls are
 * additionally gated on moderation.manage.
 *
 * Degrades gracefully on fetch failure (endpoint not deployed / 403 / network):
 * renders the empty state plus a single inline error hint, never a crash.
 */
function ModerationManagement({ theme, mode, onToast, role }) {
  const { t } = useTranslation();

  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const appliedRef = useRef(EMPTY_FILTERS);
  const [resetKey, setResetKey] = useState(0);

  const [items, setItems] = useState([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Detail modal state.
  const [detailCase, setDetailCase] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [evidence, setEvidence] = useState([]);
  const [evidenceLoading, setEvidenceLoading] = useState(false);
  const [evidenceError, setEvidenceError] = useState(null);

  // Assign / resolve in-modal action state.
  const [assignName, setAssignName] = useState('');
  const [assignPending, setAssignPending] = useState(false);
  const [resolveDraft, setResolveDraft] = useState({ action: '', reason: '', targetChannelId: '', durationMs: '' });
  const [resolvePending, setResolvePending] = useState(false);

  const canManage = can(role, 'moderation.manage');

  const fetchPage = useCallback(async (pageToLoad, applied) => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.listCases({
        page: pageToLoad,
        size: PAGE_SIZE,
        status: applied.status,
        assigned: applied.assigned.trim(),
      });
      const list = res && Array.isArray(res.items) ? res.items : [];
      setItems(list);
      setTotal(res && typeof res.total === 'number' ? res.total : list.length);
      setPage(pageToLoad);
    } catch (err) {
      setItems([]);
      setTotal(0);
      setPage(pageToLoad);
      setError(err.message || String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchPage(1, appliedRef.current);
  }, [fetchPage]);

  const handleSearch = useCallback(() => {
    appliedRef.current = filters;
    fetchPage(1, filters);
  }, [filters, fetchPage]);

  const handleReset = useCallback(() => {
    setFilters(EMPTY_FILTERS);
    appliedRef.current = EMPTY_FILTERS;
    setResetKey((k) => k + 1);
    fetchPage(1, EMPTY_FILTERS);
  }, [fetchPage]);

  const handleFilterKeyDown = (e) => {
    if (e.key === 'Enter') handleSearch();
  };

  const setFilter = (key) => (value) =>
    setFilters((prev) => ({ ...prev, [key]: value }));

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  // Open the detail modal for a case: fetch full detail + evidence.
  const openDetail = useCallback(async (caseItem) => {
    setDetailCase(caseItem);
    setDetailLoading(true);
    setEvidence([]);
    setEvidenceError(null);
    setAssignName(caseItem.assignedModerator || '');
    setResolveDraft({ action: '', reason: '', targetChannelId: caseItem.originChannelId || '', durationMs: '' });
    try {
      const full = await api.getCase(caseItem.caseId);
      setDetailCase(full || caseItem);
      if (canManage) {
        setEvidenceLoading(true);
        try {
          const ev = await api.getCaseEvidence(caseItem.caseId);
          setEvidence(ev && Array.isArray(ev.items) ? ev.items : []);
        } catch (err) {
          setEvidenceError(err.message || String(err));
        } finally {
          setEvidenceLoading(false);
        }
      }
    } catch (err) {
      // Detail fetch failed — keep the list-row summary so the modal still
      // shows what we have, and surface the error inline.
      setEvidenceError(err.message || String(err));
    } finally {
      setDetailLoading(false);
    }
  }, [canManage]);

  const closeDetail = useCallback(() => {
    setDetailCase(null);
    setEvidence([]);
    setEvidenceError(null);
  }, []);

  const handleAssign = useCallback(async () => {
    if (!detailCase || !assignName.trim()) return;
    setAssignPending(true);
    try {
      await api.assignCase(detailCase.caseId, assignName.trim());
      setDetailCase((prev) => prev ? { ...prev, assignedModerator: assignName.trim() } : prev);
      if (onToast) onToast(t('moderation.toast_assigned'), 'success');
    } catch (err) {
      if (onToast) onToast(t('moderation.toast_assign_failed', { error: err.message }), 'error');
    } finally {
      setAssignPending(false);
    }
  }, [detailCase, assignName, onToast, t]);

  const handleResolve = useCallback(async () => {
    if (!detailCase) return;
    const action = resolveDraft.action;
    if (!action) return;
    const reason = resolveDraft.reason.trim();
    if (!reason) return;
    const body = { action, reason };
    // targetChannelId / durationMs only apply to mute/ban/kick.
    if (action === 'mute' || action === 'ban' || action === 'kick') {
      if (resolveDraft.targetChannelId.trim()) body.targetChannelId = resolveDraft.targetChannelId.trim();
      if (resolveDraft.durationMs !== '' && Number.isFinite(Number(resolveDraft.durationMs))) {
        body.durationMs = Number(resolveDraft.durationMs);
      }
    }
    setResolvePending(true);
    try {
      const res = await api.resolveCase(detailCase.caseId, body);
      setDetailCase((prev) => prev ? {
        ...prev,
        status: 'RESOLVED',
        resolutionAction: (res && res.action) || action,
        resolvedAt: Date.now(),
      } : prev);
      if (onToast) onToast(t('moderation.toast_resolved', { action }), 'success');
      // Refresh the list so the row reflects the new status.
      fetchPage(page, appliedRef.current);
    } catch (err) {
      if (onToast) onToast(t('moderation.toast_resolve_failed', { error: err.message }), 'error');
    } finally {
      setResolvePending(false);
    }
  }, [detailCase, resolveDraft, onToast, t, fetchPage, page]);

  const statusBadge = (status) => {
    if (status === 'OPEN') return 'bg-amber-500/10 text-amber-600 dark:text-amber-400';
    if (status === 'IN_PROGRESS') return 'bg-sky-500/10 text-sky-600 dark:text-sky-400';
    if (status === 'RESOLVED') return 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400';
    return 'bg-muted text-muted-foreground';
  };

  return (
    <div className="space-y-4">
      {/* Header + create-report entry */}
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div>
          <h2 className="text-xl font-medium text-foreground flex items-center gap-2">
            <ShieldAlert size={18} /> {t('moderation.title')}
          </h2>
          <p className="text-xs text-muted-foreground mt-1">{t('moderation.subtitle')}</p>
        </div>
      </div>

      {/* Filter bar */}
      <Card className="p-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          <div className="space-y-1.5">
            <Label>{t('moderation.filter_status')}</Label>
            <CustomSelect
              key={`status-${resetKey}`}
              theme={theme}
              mode={mode}
              options={STATUS_OPTIONS.map((o) => ({ value: o.value, label: t(o.label) }))}
              defaultValue={filters.status}
              onChange={setFilter('status')}
              aria-label={t('moderation.filter_status')}
            />
          </div>
          <div className="space-y-1.5">
            <Label>{t('moderation.filter_assigned')}</Label>
            <Input
              value={filters.assigned}
              onChange={(e) => setFilter('assigned')(e.target.value)}
              onKeyDown={handleFilterKeyDown}
              placeholder={t('moderation.filter_assigned_placeholder')}
            />
          </div>
        </div>
        <div className="flex items-center justify-end gap-2 mt-3">
          <Button variant="outline" theme={theme} mode={mode} onClick={handleReset} disabled={loading}>
            <RotateCcw size={14} /> {t('moderation.reset')}
          </Button>
          <Button theme={theme} mode={mode} onClick={handleSearch} disabled={loading}>
            {loading ? <Loader2 size={14} className="animate-spin" /> : <Search size={14} />}
            {t('moderation.search')}
          </Button>
        </div>
      </Card>

      {/* Error hint */}
      {error && (
        <Card className="p-3 border-destructive/30 bg-destructive/5">
          <div className="flex items-center gap-2 text-destructive">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('moderation.load_failed', { error })}</p>
          </div>
        </Card>
      )}

      {/* Result table */}
      <Card className="p-0 overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 size={20} className="animate-spin text-muted-foreground" />
          </div>
        ) : items.length === 0 ? (
          <div className="py-16 text-center text-muted-foreground">
            <ShieldAlert size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('moderation.empty')}</p>
            <p className="text-[11px] opacity-70 mt-1">{t('moderation.empty_hint')}</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('moderation.col_case_id')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('moderation.col_status')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('moderation.col_reported')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('moderation.col_reason_code')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('moderation.col_assigned')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('moderation.col_created')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((c, idx) => (
                  <tr
                    key={c.caseId || idx}
                    className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors align-top cursor-pointer"
                    onClick={() => openDetail(c)}
                  >
                    <td className="px-4 py-2 whitespace-nowrap font-mono text-[10px] text-foreground">{c.caseId || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap">
                      <span className={cn('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium', statusBadge(c.status))}>
                        {c.status || '-'}
                      </span>
                    </td>
                    <td className="px-4 py-2 whitespace-nowrap text-foreground">{c.reportedPlayerId || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap text-sky-600 dark:text-sky-400">{c.reasonCode || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{c.assignedModerator || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{c.createdAt || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination — 1-based */}
        <div className="flex items-center justify-between gap-2 px-4 py-3 border-t border-border flex-wrap">
          <p className="text-[11px] text-muted-foreground">{t('moderation.total_count', { count: total })}</p>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={() => fetchPage(page - 1, appliedRef.current)}
              disabled={loading || page <= 1}
            >
              <ChevronLeft size={14} /> {t('moderation.prev')}
            </Button>
            <span className="text-[11px] text-muted-foreground whitespace-nowrap">
              {t('moderation.page_info', { page, totalPages })}
            </span>
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={() => fetchPage(page + 1, appliedRef.current)}
              disabled={loading || page >= totalPages}
            >
              {t('moderation.next')} <ChevronRight size={14} />
            </Button>
          </div>
        </div>
      </Card>

      {/* Case detail modal */}
      <Modal
        isOpen={!!detailCase}
        onClose={closeDetail}
        title={detailCase ? t('moderation.detail_title', { id: detailCase.caseId }) : t('moderation.detail_title_default')}
        theme={theme}
        mode={mode}
      >
        {detailLoading ? (
          <div className="flex items-center justify-center py-10">
            <Loader2 size={20} className="animate-spin text-muted-foreground" />
          </div>
        ) : detailCase ? (
          <div className="space-y-4">
            {/* Case fields */}
            <div className="grid grid-cols-2 gap-3 text-xs">
              <Field label={t('moderation.col_status')} value={detailCase.status} badge={statusBadge(detailCase.status)} />
              <Field label={t('moderation.col_reported')} value={detailCase.reportedPlayerId} />
              <Field label={t('moderation.col_reporter')} value={detailCase.reporterId} />
              <Field label={t('moderation.col_reason_code')} value={detailCase.reasonCode} />
              <Field label={t('moderation.col_assigned')} value={detailCase.assignedModerator} />
              <Field label={t('moderation.col_created')} value={detailCase.createdAt} />
              <Field label={t('moderation.col_origin_channel')} value={detailCase.originChannelId} />
              <Field label={t('moderation.col_resolution')} value={detailCase.resolutionAction} />
            </div>
            {detailCase.reasonText && (
              <div className="rounded-md border border-border bg-muted/30 p-3">
                <p className="text-[10px] uppercase text-muted-foreground mb-1">{t('moderation.reason_text_label')}</p>
                <p className="text-xs text-foreground whitespace-pre-wrap break-words">{detailCase.reasonText}</p>
              </div>
            )}

            {/* Evidence window — minimal, read-only. Only fetchable by
                moderation.manage. This is the ONLY path to private-chat
                content in the panel, scoped to this case. */}
            {canManage && (
              <div className="rounded-md border border-border p-3">
                <div className="flex items-center gap-2 mb-2">
                  <FileText size={14} className="text-muted-foreground" />
                  <p className="text-[10px] uppercase text-muted-foreground">{t('moderation.evidence_label')}</p>
                </div>
                {evidenceLoading ? (
                  <div className="flex items-center justify-center py-4">
                    <Loader2 size={16} className="animate-spin text-muted-foreground" />
                  </div>
                ) : evidenceError ? (
                  <p className="text-xs text-destructive">{t('moderation.evidence_load_failed', { error: evidenceError })}</p>
                ) : evidence.length === 0 ? (
                  <p className="text-xs text-muted-foreground">{t('moderation.evidence_empty')}</p>
                ) : (
                  <ul className="space-y-2 max-h-48 overflow-y-auto">
                    {evidence.map((ev, i) => (
                      <li key={i} className="rounded-sm bg-muted/40 p-2 text-[11px]">
                        <div className="flex items-center justify-between gap-2 mb-1">
                          <span className="font-medium text-foreground">{ev.evidenceType}</span>
                          <span className="text-muted-foreground font-mono text-[9px]">{ev.contentHash}</span>
                        </div>
                        {ev.contentSnapshot && (
                          <p className="text-foreground whitespace-pre-wrap break-words">{ev.contentSnapshot}</p>
                        )}
                        <p className="text-muted-foreground mt-1">
                          {t('moderation.evidence_captured', { by: ev.capturedBy || '-', at: ev.capturedAt || '-' })}
                        </p>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
            {!canManage && (
              <div className="flex items-center gap-2 rounded-md border border-border bg-muted/30 p-2 text-[11px] text-muted-foreground">
                <Lock size={12} /> {t('moderation.evidence_view_only_manage')}
              </div>
            )}

            {/* Assign (moderation.manage) */}
            {canManage && detailCase.status !== 'RESOLVED' && (
              <div className="space-y-1.5">
                <Label>{t('moderation.assign_label')}</Label>
                <div className="flex gap-2">
                  <Input
                    value={assignName}
                    onChange={(e) => setAssignName(e.target.value)}
                    placeholder={t('moderation.assign_placeholder')}
                  />
                  <Button
                    variant="outline"
                    theme={theme}
                    mode={mode}
                    onClick={handleAssign}
                    disabled={assignPending || !assignName.trim()}
                  >
                    {assignPending ? <Loader2 size={12} className="animate-spin" /> : <UserCog size={12} />}
                    {t('moderation.assign_button')}
                  </Button>
                </div>
              </div>
            )}

            {/* Resolve (moderation.manage) */}
            {canManage && detailCase.status !== 'RESOLVED' && (
              <div className="space-y-2 rounded-md border border-border p-3">
                <div className="flex items-center gap-2 text-xs font-medium text-foreground">
                  <Gavel size={14} /> {t('moderation.resolve_title')}
                </div>
                <div className="space-y-1.5">
                  <Label>{t('moderation.resolve_action')}</Label>
                  <CustomSelect
                    theme={theme}
                    mode={mode}
                    options={RESOLVE_ACTIONS.map((o) => ({ value: o.value, label: t(o.label) }))}
                    defaultValue={resolveDraft.action}
                    onChange={(v) => setResolveDraft((prev) => ({ ...prev, action: v }))}
                    aria-label={t('moderation.resolve_action')}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label>{t('moderation.resolve_reason')}</Label>
                  <textarea
                    className="flex w-full rounded-md border-0 bg-secondary/55 px-3 py-2 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                    rows={3}
                    value={resolveDraft.reason}
                    onChange={(e) => setResolveDraft((prev) => ({ ...prev, reason: e.target.value }))}
                    placeholder={t('moderation.resolve_reason_placeholder')}
                    maxLength={1024}
                  />
                  <p className="text-[10px] text-muted-foreground">{resolveDraft.reason.length}/1024</p>
                </div>
                {(resolveDraft.action === 'mute' || resolveDraft.action === 'ban' || resolveDraft.action === 'kick') && (
                  <div className="grid grid-cols-2 gap-2">
                    <div className="space-y-1.5">
                      <Label>{t('moderation.resolve_target_channel')}</Label>
                      <Input
                        value={resolveDraft.targetChannelId}
                        onChange={(e) => setResolveDraft((prev) => ({ ...prev, targetChannelId: e.target.value }))}
                        placeholder={t('moderation.resolve_target_channel_placeholder')}
                      />
                    </div>
                    <div className="space-y-1.5">
                      <Label>{t('moderation.resolve_duration')}</Label>
                      <Input
                        type="number"
                        value={resolveDraft.durationMs}
                        onChange={(e) => setResolveDraft((prev) => ({ ...prev, durationMs: e.target.value }))}
                        placeholder="0"
                      />
                    </div>
                  </div>
                )}
                <Button
                  theme={theme}
                  mode={mode}
                  onClick={handleResolve}
                  disabled={resolvePending || !resolveDraft.action || !resolveDraft.reason.trim()}
                >
                  {resolvePending ? <Loader2 size={12} className="animate-spin" /> : <Gavel size={12} />}
                  {t('moderation.resolve_button')}
                </Button>
              </div>
            )}
          </div>
        ) : null}
      </Modal>
    </div>
  );
}

// Tiny label + value cell used in the detail grid.
function Field({ label, value, badge }) {
  return (
    <div>
      <p className="text-[10px] uppercase text-muted-foreground mb-0.5">{label}</p>
      {badge ? (
        <span className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium', badge)}>
          {value || '-'}
        </span>
      ) : (
        <p className="text-foreground break-all">{value || '-'}</p>
      )}
    </div>
  );
}

export default ModerationManagement;
