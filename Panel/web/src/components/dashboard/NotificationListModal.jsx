import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Info,
  AlertTriangle,
  CheckCircle,
  UserX,
  Bell,
  Loader2,
  Trash2,
  CheckCheck,
  X,
} from 'lucide-react';
import Modal from '../ui/Modal';
import Button from '../ui/Button';
import { cn } from '../../lib/cn';
import { api } from '../../services/api';
import { adaptNotificationItem } from '../../utils/adapters';

const PAGE_SIZE = 20;

/**
 * Notification List Modal — full paginated notification history.
 *
 * Fetches GET /api/notifications?page=&size=&unreadOnly= and renders the
 * items with level-colored icons, formatted time, and read/unread state.
 * Supports per-item mark-read (click), mark-all-read, and clear-all (with
 * confirm). Calls back to the parent onUnreadCountChange so the bell badge
 * stays in sync. The optional onToast prop surfaces success/error toasts.
 */
const NotificationListModal = ({
  isOpen,
  onClose,
  theme,
  mode,
  unreadOnly = false,
  onUnreadCountChange,
  onToast,
}) => {
  const { t } = useTranslation();
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [unreadCount, setUnreadCount] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [filterUnreadOnly, setFilterUnreadOnly] = useState(unreadOnly);
  const [busyId, setBusyId] = useState(null);
  const [showClearConfirm, setShowClearConfirm] = useState(false);
  const [actionPending, setActionPending] = useState(false);

  // Fetch a page of notifications. When append=true, items are appended (Load
  // more); otherwise the list is replaced (initial load / filter change).
  const fetchPage = useCallback(async (pageToLoad, append) => {
    setLoading(true);
    try {
      const res = await api.getNotifications(pageToLoad, PAGE_SIZE, filterUnreadOnly);
      const list = (res && Array.isArray(res.items)) ? res.items.map(adaptNotificationItem).filter(Boolean) : [];
      const totalCount = (res && typeof res.total === 'number') ? res.total : list.length;
      const unread = (res && typeof res.unreadCount === 'number') ? res.unreadCount : 0;
      setItems((prev) => append ? [...prev, ...list] : list);
      setTotal(totalCount);
      setUnreadCount(unread);
      // Pages are 1-based and the backend total is the real row count.
      setHasMore((pageToLoad * PAGE_SIZE) < totalCount);
      if (onUnreadCountChange) onUnreadCountChange(unread);
    } catch (err) {
      if (onToast) onToast(t('notifications.toast_fetch_failed', { error: err.message }), 'error');
    } finally {
      setLoading(false);
    }
  }, [filterUnreadOnly, onToast, onUnreadCountChange, t]);

  // Initial load whenever the modal opens or the filter toggles.
  useEffect(() => {
    if (!isOpen) return;
    setPage(1);
    fetchPage(1, false);
  }, [isOpen, filterUnreadOnly, fetchPage]);

  // Mark a single notification as read (click on an unread row).
  const handleMarkRead = useCallback(async (notif) => {
    if (!notif || notif.read) return;
    setBusyId(notif.id);
    try {
      await api.markNotificationRead(notif.id);
      setItems((prev) => prev.map((n) => n.id === notif.id ? { ...n, read: true } : n));
      const newUnread = Math.max(0, unreadCount - 1);
      setUnreadCount(newUnread);
      if (onUnreadCountChange) onUnreadCountChange(newUnread);
      if (onToast) onToast(t('notifications.toast_read'), 'success');
    } catch (err) {
      if (onToast) onToast(t('notifications.toast_read_failed', { error: err.message }), 'error');
    } finally {
      setBusyId(null);
    }
  }, [unreadCount, onToast, onUnreadCountChange, t]);

  // Mark all notifications as read.
  const handleMarkAllRead = useCallback(async () => {
    setActionPending(true);
    try {
      await api.markAllNotificationsRead();
      setItems((prev) => prev.map((n) => ({ ...n, read: true })));
      setUnreadCount(0);
      if (onUnreadCountChange) onUnreadCountChange(0);
      if (onToast) onToast(t('notifications.toast_all_read'), 'success');
    } catch (err) {
      if (onToast) onToast(t('notifications.toast_mark_all_failed', { error: err.message }), 'error');
    } finally {
      setActionPending(false);
    }
  }, [onToast, onUnreadCountChange, t]);

  // Clear notifications (after confirm). PANEL-014: the backend only deletes
  // the caller's directed notifications — broadcast events remain. Refresh the
  // page so the list reflects reality rather than optimistically wiping it.
  const handleClearAll = useCallback(async () => {
    setActionPending(true);
    try {
      await api.clearNotifications();
      setShowClearConfirm(false);
      if (onToast) onToast(t('notifications.toast_cleared'), 'success');
      // Re-fetch the current page so broadcast notifications still show.
      await fetchPage(page, false);
    } catch (err) {
      if (onToast) onToast(t('notifications.toast_clear_failed', { error: err.message }), 'error');
    } finally {
      setActionPending(false);
    }
  }, [onToast, onUnreadCountChange, t, fetchPage, page]);

  // Load next page.
  const handleLoadMore = useCallback(() => {
    const next = page + 1;
    setPage(next);
    fetchPage(next, true);
  }, [page, fetchPage]);

  const levelIcon = (type) => {
    if (type === 'warning') return AlertTriangle;
    if (type === 'error') return AlertTriangle;
    if (type === 'success') return CheckCircle;
    if (type === 'mute' || type === 'kick') return UserX;
    return Info;
  };

  const levelColor = (type) => {
    if (type === 'warning') return 'bg-amber-500/10 text-amber-600 dark:text-amber-400';
    if (type === 'error') return 'bg-destructive/10 text-destructive';
    if (type === 'success') return 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400';
    return 'bg-muted text-muted-foreground';
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={() => !actionPending && !loading && onClose()}
      title={t('notifications.list_title')}
      theme={theme}
      mode={mode}
    >
      <div className="space-y-3">
        {/* Filter + actions row */}
        <div className="flex items-center justify-between gap-2 flex-wrap">
          <div className="inline-flex h-7 items-center gap-1 rounded-full bg-muted p-0.5">
            <button
              onClick={() => setFilterUnreadOnly(false)}
              className={cn(
                'inline-flex h-6 items-center justify-center rounded-full px-2.5 text-[11px] font-medium transition-colors',
                !filterUnreadOnly ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
              )}
            >
              {t('notifications.all')}
            </button>
            <button
              onClick={() => setFilterUnreadOnly(true)}
              className={cn(
                'inline-flex h-6 items-center justify-center rounded-full px-2.5 text-[11px] font-medium transition-colors',
                filterUnreadOnly ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
              )}
            >
              {t('notifications.unread_only')}
              {unreadCount > 0 && (
                <span className="ml-1 inline-flex h-3.5 min-w-3.5 items-center justify-center rounded-full bg-primary px-1 text-[9px] font-semibold text-primary-foreground">
                  {unreadCount}
                </span>
              )}
            </button>
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={handleMarkAllRead}
              disabled={actionPending || unreadCount === 0}
              className="text-xs"
            >
              <CheckCheck size={12} />
              {t('notifications.mark_all_read')}
            </Button>
            <Button
              variant="outline"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={() => setShowClearConfirm(true)}
              disabled={actionPending || items.length === 0}
              className="text-xs hover:text-destructive"
            >
              <Trash2 size={12} />
              {t('notifications.clear')}
            </Button>
          </div>
        </div>

        {/* List */}
        <div className="max-h-[420px] overflow-y-auto rounded-md border border-border">
          {loading && items.length === 0 ? (
            <div className="flex items-center justify-center py-10">
              <Loader2 size={20} className="animate-spin text-muted-foreground" />
            </div>
          ) : items.length === 0 ? (
            <div className="p-10 text-center text-muted-foreground">
              <Bell size={32} className="mx-auto mb-2 opacity-50" />
              <p className="text-xs">{t('notifications.empty')}</p>
            </div>
          ) : (
            items.map((notif) => {
              const Icon = levelIcon(notif.type);
              return (
                <div
                  key={notif.id}
                  onClick={() => handleMarkRead(notif)}
                  className={cn(
                    'px-3 py-2.5 flex gap-3 transition-colors cursor-pointer relative border-b border-border last:border-0',
                    'hover:bg-accent/60',
                    !notif.read && 'bg-primary/5'
                  )}
                >
                  {!notif.read && (
                    <div className="absolute left-1 top-1/2 -translate-y-1/2 w-1.5 h-1.5 bg-primary rounded-full" />
                  )}
                  <div className={cn('flex size-7 items-center justify-center shrink-0 rounded-full', levelColor(notif.type))}>
                    <Icon size={13} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className={cn('text-xs truncate text-foreground', !notif.read && 'font-semibold')}>
                      {notif.title}
                    </p>
                    <p className="text-xs truncate text-muted-foreground mt-0.5">{notif.desc}</p>
                    <p className="text-[10px] text-muted-foreground opacity-70 mt-1">{notif.time}</p>
                  </div>
                  {busyId === notif.id && (
                    <Loader2 size={12} className="animate-spin text-muted-foreground shrink-0 self-center" />
                  )}
                </div>
              );
            })
          )}
        </div>

        {/* Load more */}
        {hasMore && items.length > 0 && (
          <div className="flex justify-center pt-1">
            <Button
              variant="ghost"
              size="sm"
              theme={theme}
              mode={mode}
              onClick={handleLoadMore}
              disabled={loading}
              className="text-xs"
            >
              {loading ? <Loader2 size={12} className="animate-spin" /> : null}
              {t('notifications.load_more')}
            </Button>
          </div>
        )}
        {!hasMore && items.length > 0 && (
          <p className="text-center text-[11px] text-muted-foreground">
            {t('notifications.no_more')} · {total}
          </p>
        )}
      </div>

      {/* Footer */}
      <div className="flex gap-2 mt-6 pt-4 border-t border-border">
        <Button
          variant="ghost"
          className="flex-1"
          theme={theme}
          mode={mode}
          onClick={onClose}
          disabled={actionPending}
        >
          {t('common.confirm')}
        </Button>
      </div>

      {/* Clear confirm sub-modal (rendered inside this modal's backdrop) */}
      {showClearConfirm && (
        <div
          className="absolute inset-0 z-10 flex items-center justify-center bg-background/80 rounded-lg"
          onClick={(e) => { e.stopPropagation(); if (!actionPending) setShowClearConfirm(false); }}
        >
          <div
            className="w-[calc(100%-2rem)] max-w-[320px] rounded-lg border border-border bg-background p-4 shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-start gap-2 mb-3">
              <AlertTriangle size={16} className="text-destructive shrink-0 mt-0.5" />
              <p className="text-xs text-muted-foreground">{t('notifications.clear_confirm')}</p>
            </div>
            <div className="flex gap-2">
              <Button
                variant="ghost"
                className="flex-1"
                theme={theme}
                mode={mode}
                onClick={() => setShowClearConfirm(false)}
                disabled={actionPending}
              >
                {t('common.cancel')}
              </Button>
              <Button
                variant="destructive"
                className="flex-1"
                theme={theme}
                mode={mode}
                onClick={handleClearAll}
                disabled={actionPending}
              >
                {actionPending ? <Loader2 size={12} className="animate-spin" /> : null}
                {t('common.confirm')}
              </Button>
            </div>
          </div>
        </div>
      )}
    </Modal>
  );
};

export default NotificationListModal;
