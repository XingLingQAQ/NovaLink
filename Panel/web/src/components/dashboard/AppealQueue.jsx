import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Gavel,
  Loader2,
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Search,
  RotateCcw,
  ShieldCheck,
  AlertTriangle,
} from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import CustomSelect from '../ui/CustomSelect';
import Label from '../ui/Label';
import Modal from '../ui/Modal';
import { cn } from '../../lib/cn';
import { api } from '../../services/api';
import { can } from '../../lib/permissions';

const PAGE_SIZE = 20;

const STATUS_OPTIONS = [
  { value: '', label: 'appeals.filter_all_status' },
  { value: 'PENDING', label: 'appeals.status_pending' },
  { value: 'APPROVED', label: 'appeals.status_approved' },
  { value: 'DENIED', label: 'appeals.status_denied' },
  { value: 'ESCALATED', label: 'appeals.status_escalated' },
];

const DECISION_OPTIONS = [
  { value: '', label: 'appeals.decision_placeholder' },
  { value: 'APPROVED', label: 'appeals.decision_approved' },
  { value: 'DENIED', label: 'appeals.decision_denied' },
  { value: 'ESCALATED', label: 'appeals.decision_escalated' },
];

const EMPTY_FILTERS = { status: '' };

/**
 * Appeal Queue (PANEL-007).
 *
 * Lists appeals (GET /api/appeals) with a status filter and 1-based pagination.
 * Selecting a row opens a review Modal where a reviewer (appeals.review)
 * records a decision (APPROVED / DENIED / ESCALATED) plus a note.
 *
 * The backend enforces reviewer != case.assignedModerator and returns 403
 * otherwise. We detect that specific case via err.status === 403 and surface
 * a self-review i18n hint instead of a generic error.
 *
 * Degrades gracefully on fetch failure (endpoint not deployed / 403 / network):
 * renders the empty state plus a single inline error hint, never a crash.
 */
function AppealQueue({ theme, mode, onToast, role }) {
  const { t } = useTranslation();

  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const appliedRef = useRef(EMPTY_FILTERS);
  const [resetKey, setResetKey] = useState(0);

  const [items, setItems] = useState([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Review modal state.
  const [reviewTarget, setReviewTarget] = useState(null);
  const [reviewDraft, setReviewDraft] = useState({ decision: '', note: '' });
  const [reviewPending, setReviewPending] = useState(false);
  const [selfReviewHint, setSelfReviewHint] = useState(false);

  const canReview = can(role, 'appeals.review');

  const fetchPage = useCallback(async (pageToLoad, applied) => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.listAppeals({
        page: pageToLoad,
        size: PAGE_SIZE,
        status: applied.status,
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

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const openReview = useCallback((appeal) => {
    setReviewTarget(appeal);
    setReviewDraft({ decision: '', note: '' });
    setSelfReviewHint(false);
  }, []);

  const closeReview = useCallback(() => {
    setReviewTarget(null);
    setSelfReviewHint(false);
  }, []);

  const handleReview = useCallback(async () => {
    if (!reviewTarget || !reviewDraft.decision) return;
    setReviewPending(true);
    setSelfReviewHint(false);
    try {
      await api.reviewAppeal(reviewTarget.appealId, {
        decision: reviewDraft.decision,
        note: reviewDraft.note.trim(),
      });
      if (onToast) onToast(t('appeals.toast_reviewed', { decision: reviewDraft.decision }), 'success');
      closeReview();
      fetchPage(page, appliedRef.current);
    } catch (err) {
      // 403 from the review endpoint means the backend blocked self-review
      // (reviewer == case.assignedModerator). Surface the dedicated hint.
      if (err.status === 403) {
        setSelfReviewHint(true);
      } else if (onToast) {
        onToast(t('appeals.toast_review_failed', { error: err.message }), 'error');
      }
    } finally {
      setReviewPending(false);
    }
  }, [reviewTarget, reviewDraft, onToast, t, closeReview, fetchPage, page]);

  const statusBadge = (status) => {
    if (status === 'PENDING') return 'bg-amber-500/10 text-amber-600 dark:text-amber-400';
    if (status === 'APPROVED') return 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400';
    if (status === 'DENIED') return 'bg-destructive/10 text-destructive';
    if (status === 'ESCALATED') return 'bg-sky-500/10 text-sky-600 dark:text-sky-400';
    return 'bg-muted text-muted-foreground';
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div>
        <h2 className="text-xl font-medium text-foreground flex items-center gap-2">
          <Gavel size={18} /> {t('appeals.title')}
        </h2>
        <p className="text-xs text-muted-foreground mt-1">{t('appeals.subtitle')}</p>
      </div>

      {/* Filter bar */}
      <Card className="p-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          <div className="space-y-1.5">
            <Label>{t('appeals.filter_status')}</Label>
            <CustomSelect
              key={`status-${resetKey}`}
              theme={theme}
              mode={mode}
              options={STATUS_OPTIONS.map((o) => ({ value: o.value, label: t(o.label) }))}
              defaultValue={filters.status}
              onChange={(v) => setFilters((prev) => ({ ...prev, status: v }))}
              aria-label={t('appeals.filter_status')}
            />
          </div>
        </div>
        <div className="flex items-center justify-end gap-2 mt-3">
          <Button variant="outline" theme={theme} mode={mode} onClick={handleReset} disabled={loading}>
            <RotateCcw size={14} /> {t('appeals.reset')}
          </Button>
          <Button theme={theme} mode={mode} onClick={handleSearch} disabled={loading}>
            {loading ? <Loader2 size={14} className="animate-spin" /> : <Search size={14} />}
            {t('appeals.search')}
          </Button>
        </div>
      </Card>

      {/* Error hint */}
      {error && (
        <Card className="p-3 border-destructive/30 bg-destructive/5">
          <div className="flex items-center gap-2 text-destructive">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('appeals.load_failed', { error })}</p>
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
            <Gavel size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('appeals.empty')}</p>
            <p className="text-[11px] opacity-70 mt-1">{t('appeals.empty_hint')}</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('appeals.col_appeal_id')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('appeals.col_case_id')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('appeals.col_status')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('appeals.col_appellant')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('appeals.col_original_action')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('appeals.col_reviewer')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('appeals.col_created')}</th>
                  {canReview && <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('appeals.col_action')}</th>}
                </tr>
              </thead>
              <tbody>
                {items.map((a, idx) => (
                  <tr
                    key={a.appealId || idx}
                    className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors align-top"
                  >
                    <td className="px-4 py-2 whitespace-nowrap font-mono text-[10px] text-foreground">{a.appealId || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap font-mono text-[10px] text-muted-foreground">{a.caseId || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap">
                      <span className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium', statusBadge(a.status))}>
                        {a.status || '-'}
                      </span>
                    </td>
                    <td className="px-4 py-2 whitespace-nowrap text-foreground">{a.appellantId || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{a.originalAction || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{a.reviewedBy || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{a.createdAt || '-'}</td>
                    {canReview && (
                      <td className="px-4 py-2 whitespace-nowrap">
                        {a.status === 'PENDING' ? (
                          <Button
                            variant="outline"
                            size="sm"
                            theme={theme}
                            mode={mode}
                            onClick={() => openReview(a)}
                          >
                            <Gavel size={12} /> {t('appeals.review_button')}
                          </Button>
                        ) : (
                          <span className="text-[10px] text-muted-foreground">{t('appeals.reviewed')}</span>
                        )}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination — 1-based */}
        <div className="flex items-center justify-between gap-2 px-4 py-3 border-t border-border flex-wrap">
          <p className="text-[11px] text-muted-foreground">{t('appeals.total_count', { count: total })}</p>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={() => fetchPage(page - 1, appliedRef.current)}
              disabled={loading || page <= 1}
            >
              <ChevronLeft size={14} /> {t('appeals.prev')}
            </Button>
            <span className="text-[11px] text-muted-foreground whitespace-nowrap">
              {t('appeals.page_info', { page, totalPages })}
            </span>
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={() => fetchPage(page + 1, appliedRef.current)}
              disabled={loading || page >= totalPages}
            >
              {t('appeals.next')} <ChevronRight size={14} />
            </Button>
          </div>
        </div>
      </Card>

      {/* Review modal */}
      <Modal
        isOpen={!!reviewTarget}
        onClose={closeReview}
        title={reviewTarget ? t('appeals.review_title', { id: reviewTarget.appealId }) : t('appeals.review_title_default')}
        theme={theme}
        mode={mode}
      >
        {reviewTarget ? (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 text-xs">
              <div>
                <p className="text-[10px] uppercase text-muted-foreground mb-0.5">{t('appeals.col_case_id')}</p>
                <p className="text-foreground font-mono text-[10px]">{reviewTarget.caseId || '-'}</p>
              </div>
              <div>
                <p className="text-[10px] uppercase text-muted-foreground mb-0.5">{t('appeals.col_appellant')}</p>
                <p className="text-foreground">{reviewTarget.appellantId || '-'}</p>
              </div>
              <div>
                <p className="text-[10px] uppercase text-muted-foreground mb-0.5">{t('appeals.col_original_action')}</p>
                <p className="text-muted-foreground">{reviewTarget.originalAction || '-'}</p>
              </div>
              <div>
                <p className="text-[10px] uppercase text-muted-foreground mb-0.5">{t('appeals.col_status')}</p>
                <span className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium', statusBadge(reviewTarget.status))}>
                  {reviewTarget.status || '-'}
                </span>
              </div>
            </div>
            {reviewTarget.reason && (
              <div className="rounded-md border border-border bg-muted/30 p-3">
                <p className="text-[10px] uppercase text-muted-foreground mb-1">{t('appeals.reason_label')}</p>
                <p className="text-xs text-foreground whitespace-pre-wrap break-words">{reviewTarget.reason}</p>
              </div>
            )}

            {/* Self-review hint — rendered when the backend returned 403. */}
            {selfReviewHint && (
              <div className="flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-700 dark:text-amber-300">
                <AlertTriangle size={14} className="shrink-0 mt-0.5" />
                <p>{t('appeals.self_review_hint')}</p>
              </div>
            )}

            <div className="space-y-1.5">
              <Label>{t('appeals.decision_label')}</Label>
              <CustomSelect
                theme={theme}
                mode={mode}
                options={DECISION_OPTIONS.map((o) => ({ value: o.value, label: t(o.label) }))}
                defaultValue={reviewDraft.decision}
                onChange={(v) => setReviewDraft((prev) => ({ ...prev, decision: v }))}
                aria-label={t('appeals.decision_label')}
              />
            </div>
            <div className="space-y-1.5">
              <Label>{t('appeals.review_note')}</Label>
              <textarea
                className="flex w-full rounded-md border-0 bg-secondary/55 px-3 py-2 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                rows={3}
                value={reviewDraft.note}
                onChange={(e) => setReviewDraft((prev) => ({ ...prev, note: e.target.value }))}
                placeholder={t('appeals.review_note_placeholder')}
                maxLength={1024}
              />
              <p className="text-[10px] text-muted-foreground">{reviewDraft.note.length}/1024</p>
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="ghost" theme={theme} mode={mode} onClick={closeReview} disabled={reviewPending}>
                {t('common.cancel')}
              </Button>
              <Button
                theme={theme}
                mode={mode}
                onClick={handleReview}
                disabled={reviewPending || !reviewDraft.decision}
              >
                {reviewPending ? <Loader2 size={12} className="animate-spin" /> : <ShieldCheck size={12} />}
                {t('appeals.submit_review')}
              </Button>
            </div>
          </div>
        ) : null}
      </Modal>
    </div>
  );
}

export default AppealQueue;
