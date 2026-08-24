/**
 * Audit Log — paginated, filterable view of admin audit events (PANEL-006).
 *
 * Backed by GET /api/audit (1-based pages). Filters: actor (substring) and
 * action (exact code, e.g. channel.create). ADMIN+ only; read access is
 * deliberately separate from notification clear.
 *
 * Mirrors MessageHistory's layout, filter bar, and pagination so the panel's
 * table views stay visually consistent.
 *
 * Degrades gracefully when the backend endpoint is not deployed yet: any fetch
 * failure (404 / 403 / network) renders the empty state plus a single inline
 * error hint — never a blank page or a crash.
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Search,
  RotateCcw,
  Loader2,
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  ShieldCheck,
} from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Label from '../ui/Label';
import CustomSelect from '../ui/CustomSelect';
import { api } from '../../services/api';

const PAGE_SIZE = 20;

const EMPTY_FILTERS = { actor: '', action: '' };

// Stable action code list for the dropdown. Kept in sync with the backend
// audit hooks in RestApiHandler (recordAuditSuccess / recordAudit calls).
const ACTION_OPTIONS = [
  { value: '', label: 'audit.filter_all_actions' },
  { value: 'channel.create', label: 'audit.action_channel_create' },
  { value: 'channel.update', label: 'audit.action_channel_update' },
  { value: 'channel.delete', label: 'audit.action_channel_delete' },
  { value: 'player.mute', label: 'audit.action_player_mute' },
  { value: 'player.ban', label: 'audit.action_player_ban' },
  { value: 'webhook.create', label: 'audit.action_webhook_create' },
  { value: 'webhook.update', label: 'audit.action_webhook_update' },
  { value: 'webhook.delete', label: 'audit.action_webhook_delete' },
  { value: 'config.reload', label: 'audit.action_config_reload' },
  { value: 'settings.update', label: 'audit.action_settings_update' },
];

function AuditLog({ theme, mode }) {
  const { t, i18n } = useTranslation();
  const locale = (i18n.language || 'zh_CN').replace(/_/g, '-');

  // Draft filters vs applied filters so paging never picks up half-edited form
  // values.
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const appliedRef = useRef(EMPTY_FILTERS);
  const [resetKey, setResetKey] = useState(0);

  const [items, setItems] = useState([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchPage = useCallback(async (pageToLoad, applied) => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.getAuditEvents(
        pageToLoad,
        PAGE_SIZE,
        applied.actor.trim(),
        applied.action
      );
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

  // Initial load.
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

  // Resolve the translated label for an action code; falls back to the raw
  // code so unknown actions (added by future backend versions) stay readable.
  const actionLabel = useCallback(
    (code) => {
      if (!code) return '-';
      const match = ACTION_OPTIONS.find((o) => o.value === code);
      return match ? t(match.label) : code;
    },
    [t]
  );

  return (
    <div className="space-y-4">
      {/* Header */}
      <div>
        <h2 className="text-xl font-medium text-foreground">{t('audit.title')}</h2>
        <p className="text-xs text-muted-foreground mt-1">{t('audit.subtitle')}</p>
      </div>

      {/* Filter bar */}
      <Card className="p-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          <div className="space-y-1.5">
            <Label>{t('audit.filter_actor')}</Label>
            <Input
              value={filters.actor}
              onChange={(e) => setFilter('actor')(e.target.value)}
              onKeyDown={handleFilterKeyDown}
              placeholder={t('audit.filter_actor_placeholder')}
            />
          </div>
          <div className="space-y-1.5">
            <Label>{t('audit.filter_action')}</Label>
            <CustomSelect
              key={`action-${resetKey}`}
              theme={theme}
              mode={mode}
              options={ACTION_OPTIONS.map((o) => ({
                value: o.value,
                label: o.value ? t(o.label) : t(o.label),
              }))}
              defaultValue={filters.action}
              onChange={setFilter('action')}
            />
          </div>
        </div>
        <div className="flex items-center justify-end gap-2 mt-3">
          <Button variant="outline" theme={theme} mode={mode} onClick={handleReset} disabled={loading}>
            <RotateCcw size={14} /> {t('audit.reset')}
          </Button>
          <Button theme={theme} mode={mode} onClick={handleSearch} disabled={loading}>
            {loading ? <Loader2 size={14} className="animate-spin" /> : <Search size={14} />}
            {t('audit.search')}
          </Button>
        </div>
      </Card>

      {/* Error hint (endpoint missing / network failure / 403) */}
      {error && (
        <Card className="p-3 border-destructive/30 bg-destructive/5">
          <div className="flex items-center gap-2 text-destructive">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('audit.load_failed', { error })}</p>
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
            <ShieldCheck size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('audit.empty')}</p>
            <p className="text-[11px] opacity-70 mt-1">{t('audit.empty_hint')}</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('audit.col_time')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('audit.col_actor')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('audit.col_action')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('audit.col_resource')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('audit.col_result')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('audit.col_origin')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('audit.col_request_id')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((ev, idx) => (
                  <tr
                    key={ev.id || idx}
                    className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors align-top"
                  >
                    <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{formatTime(ev.createdAt)}</td>
                    <td className="px-4 py-2 whitespace-nowrap font-medium text-foreground">{ev.actor || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap text-sky-600 dark:text-sky-400">{actionLabel(ev.action)}</td>
                    <td className="px-4 py-2 text-foreground break-all">{ev.resource || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap">
                      {ev.result === 'success' ? (
                        <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                          <ShieldCheck size={12} /> {t('audit.result_success')}
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-destructive">
                          <AlertCircle size={12} /> {t('audit.result_failure')}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{ev.origin || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap text-muted-foreground font-mono text-[10px]">{ev.requestId || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination — 1-based */}
        <div className="flex items-center justify-between gap-2 px-4 py-3 border-t border-border flex-wrap">
          <p className="text-[11px] text-muted-foreground">{t('audit.total_count', { count: total })}</p>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={() => fetchPage(page - 1, appliedRef.current)}
              disabled={loading || page <= 1}
            >
              <ChevronLeft size={14} /> {t('audit.prev')}
            </Button>
            <span className="text-[11px] text-muted-foreground whitespace-nowrap">
              {t('audit.page_info', { page, totalPages })}
            </span>
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={() => fetchPage(page + 1, appliedRef.current)}
              disabled={loading || page >= totalPages}
            >
              {t('audit.next')} <ChevronRight size={14} />
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}

export default AuditLog;
