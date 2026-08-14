/**
 * Message History — paginated, filterable view of persisted chat messages.
 *
 * Backed by GET /api/messages (1-based pages, locked contract). Filters:
 * channel (reuses the loaded channels), server (reuses the WS-fed servers),
 * player name, keyword, and a from/to time range (datetime-local, sent as
 * epoch millis). Visible to all roles (read-only page).
 *
 * Degrades gracefully when the backend endpoint is not deployed yet:
 * any fetch failure (404 / network) renders the empty state plus a single
 * inline error hint — never a blank page or a crash.
 */

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Search,
  RotateCcw,
  Loader2,
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  History as HistoryIcon,
} from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Label from '../ui/Label';
import CustomSelect from '../ui/CustomSelect';
import { api } from '../../services/api';

const PAGE_SIZE = 50;

const EMPTY_FILTERS = { channel: '', server: '', player: '', q: '', from: '', to: '' };

// datetime-local value ("2026-08-13T12:00") -> epoch millis, or '' when unset/invalid.
function toMillis(value) {
  if (!value) return '';
  const ms = new Date(value).getTime();
  return Number.isNaN(ms) ? '' : ms;
}

function MessageHistory({ theme, mode, channels = [], servers = [] }) {
  const { t, i18n } = useTranslation();
  const locale = (i18n.language || 'zh_CN').replace(/_/g, '-');

  // Draft filters (bound to the form) vs applied filters (used by fetches),
  // so paging never silently picks up half-edited form values.
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const appliedRef = useRef(EMPTY_FILTERS);
  // Bumped on reset to remount the CustomSelects (they don't sync an empty
  // controlled value back into their internal state).
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
      const res = await api.getMessages({
        page: pageToLoad,
        size: PAGE_SIZE,
        channel: applied.channel,
        server: applied.server,
        player: applied.player.trim(),
        q: applied.q.trim(),
        from: toMillis(applied.from),
        to: toMillis(applied.to),
      });
      const list = res && Array.isArray(res.items) ? res.items : [];
      setItems(list);
      setTotal(res && typeof res.total === 'number' ? res.total : list.length);
      setPage(pageToLoad);
    } catch (err) {
      // Endpoint missing (404) or network failure: empty state + one inline
      // error hint instead of a blank page.
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

  const setFilter = (key) => (value) => setFilters((prev) => ({ ...prev, [key]: value }));

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const channelOptions = useMemo(
    () => [
      { value: '', label: t('history.filter_all') },
      ...channels.map((c) => ({ value: c.id, label: c.name || c.id })),
    ],
    [channels, t]
  );

  const serverOptions = useMemo(
    () => [
      { value: '', label: t('history.filter_all') },
      ...servers.map((s) => ({ value: s.id, label: s.name || s.id })),
    ],
    [servers, t]
  );

  const channelLabel = useCallback((channelId) => {
    const ch = channels.find((c) => c.id === channelId);
    return ch ? (ch.name || ch.id) : (channelId || '-');
  }, [channels]);

  const formatTime = useCallback((ts) => {
    if (!ts) return '-';
    try {
      return new Date(Number(ts)).toLocaleString(locale, { hour12: false });
    } catch {
      return '-';
    }
  }, [locale]);

  return (
    <div className="space-y-4">
      {/* Header */}
      <div>
        <h2 className="text-xl font-medium text-foreground">{t('history.title')}</h2>
        <p className="text-xs text-muted-foreground mt-1">{t('history.subtitle')}</p>
      </div>

      {/* Filter bar */}
      <Card className="p-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          <div className="space-y-1.5">
            <Label>{t('history.filter_channel')}</Label>
            <CustomSelect
              key={`channel-${resetKey}`}
              theme={theme}
              mode={mode}
              options={channelOptions}
              defaultValue={filters.channel}
              onChange={setFilter('channel')}
            />
          </div>
          <div className="space-y-1.5">
            <Label>{t('history.filter_server')}</Label>
            <CustomSelect
              key={`server-${resetKey}`}
              theme={theme}
              mode={mode}
              options={serverOptions}
              defaultValue={filters.server}
              onChange={setFilter('server')}
            />
          </div>
          <div className="space-y-1.5">
            <Label>{t('history.filter_player')}</Label>
            <Input
              value={filters.player}
              onChange={(e) => setFilter('player')(e.target.value)}
              onKeyDown={handleFilterKeyDown}
              placeholder={t('history.filter_player_placeholder')}
            />
          </div>
          <div className="space-y-1.5">
            <Label>{t('history.filter_keyword')}</Label>
            <Input
              value={filters.q}
              onChange={(e) => setFilter('q')(e.target.value)}
              onKeyDown={handleFilterKeyDown}
              placeholder={t('history.filter_keyword_placeholder')}
            />
          </div>
          <div className="space-y-1.5">
            <Label>{t('history.filter_from')}</Label>
            <Input
              type="datetime-local"
              value={filters.from}
              onChange={(e) => setFilter('from')(e.target.value)}
              className="[color-scheme:light] dark:[color-scheme:dark]"
            />
          </div>
          <div className="space-y-1.5">
            <Label>{t('history.filter_to')}</Label>
            <Input
              type="datetime-local"
              value={filters.to}
              onChange={(e) => setFilter('to')(e.target.value)}
              className="[color-scheme:light] dark:[color-scheme:dark]"
            />
          </div>
        </div>
        <div className="flex items-center justify-end gap-2 mt-3">
          <Button variant="outline" theme={theme} mode={mode} onClick={handleReset} disabled={loading}>
            <RotateCcw size={14} /> {t('history.reset')}
          </Button>
          <Button theme={theme} mode={mode} onClick={handleSearch} disabled={loading}>
            {loading ? <Loader2 size={14} className="animate-spin" /> : <Search size={14} />}
            {t('history.search')}
          </Button>
        </div>
      </Card>

      {/* Error hint (endpoint missing / network failure) */}
      {error && (
        <Card className="p-3 border-destructive/30 bg-destructive/5">
          <div className="flex items-center gap-2 text-destructive">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('history.load_failed', { error })}</p>
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
            <HistoryIcon size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('history.empty')}</p>
            <p className="text-[11px] opacity-70 mt-1">{t('history.empty_hint')}</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('history.col_time')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('history.col_channel')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('history.col_server')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('history.col_player')}</th>
                  <th className="px-4 py-2.5 font-medium w-full">{t('history.col_content')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((msg, idx) => (
                  <tr
                    key={msg.id || idx}
                    className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors align-top"
                  >
                    <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{formatTime(msg.timestamp)}</td>
                    <td className="px-4 py-2 whitespace-nowrap text-sky-600 dark:text-sky-400">{channelLabel(msg.channelId)}</td>
                    <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{msg.clientId || '-'}</td>
                    <td className="px-4 py-2 whitespace-nowrap font-medium text-foreground">{msg.senderName || msg.senderId || '-'}</td>
                    <td className="px-4 py-2 text-foreground break-all">{msg.content}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination — 1-based */}
        <div className="flex items-center justify-between gap-2 px-4 py-3 border-t border-border flex-wrap">
          <p className="text-[11px] text-muted-foreground">{t('history.total_count', { count: total })}</p>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={() => fetchPage(page - 1, appliedRef.current)}
              disabled={loading || page <= 1}
            >
              <ChevronLeft size={14} /> {t('history.prev')}
            </Button>
            <span className="text-[11px] text-muted-foreground whitespace-nowrap">
              {t('history.page_info', { page, totalPages })}
            </span>
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={() => fetchPage(page + 1, appliedRef.current)}
              disabled={loading || page >= totalPages}
            >
              {t('history.next')} <ChevronRight size={14} />
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}

export default MessageHistory;
