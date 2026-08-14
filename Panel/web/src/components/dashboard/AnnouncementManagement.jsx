/**
 * Announcement Management — persisted JOIN/CRON announcements plus one-shot
 * INSTANT broadcasts.
 *
 * Backed by /api/announcements (locked contract). The page itself is gated by
 * the `announcements.manage` capability (ADMIN / SUPER_ADMIN) at the sidebar
 * and App routing level, so every visitor here may manage announcements.
 *
 * Degrades gracefully when the backend endpoint is not deployed yet: any list
 * fetch failure (404 / network) renders the empty state plus a single inline
 * error hint — never a blank page or a crash.
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, Trash2, Loader2, AlertCircle, Megaphone } from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';
import Modal from '../ui/Modal';
import Select from '../ui/Select';
import Switch from '../ui/Switch';
import { api } from '../../services/api';

const EMPTY_FORM = { type: 'INSTANT', channelId: '', content: '', cron: '' };

const textareaClass =
  'flex w-full min-h-24 rounded-md border-0 bg-secondary/55 px-3 py-2 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring text-foreground resize-y';

const inputClass =
  'flex h-8 w-full rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring text-foreground';

function AnnouncementManagement({ theme, mode, channels = [], onToast }) {
  const { t } = useTranslation();

  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [busyId, setBusyId] = useState(null); // enable/disable toggle in flight
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);

  const fetchAnnouncements = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.getAnnouncements();
      setItems(res && Array.isArray(res.items) ? res.items : []);
    } catch (err) {
      // Endpoint missing (404) or network failure: empty state + one inline
      // error hint instead of a blank page.
      setItems([]);
      setError(err.message || String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAnnouncements();
  }, [fetchAnnouncements]);

  const openCreateModal = () => {
    setForm({ ...EMPTY_FORM, channelId: (channels[0] && channels[0].id) || '' });
    setShowCreateModal(true);
  };

  // Enable/disable via PUT { enabled } — merge the returned object back in.
  const handleToggle = async (item, next) => {
    setBusyId(item.id);
    try {
      const updated = await api.updateAnnouncement(item.id, { enabled: next });
      setItems((prev) =>
        prev.map((a) => {
          if (a.id !== item.id) return a;
          const merged = updated && typeof updated === 'object' ? { ...a, ...updated } : a;
          return { ...merged, enabled: updated && typeof updated.enabled === 'boolean' ? updated.enabled : next };
        })
      );
    } catch (err) {
      if (onToast) onToast(t('announcements.toast_toggle_failed', { error: err.message }), 'error');
    } finally {
      setBusyId(null);
    }
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    setSubmitting(true);
    try {
      await api.deleteAnnouncement(deleteTarget.id);
      setItems((prev) => prev.filter((a) => a.id !== deleteTarget.id));
      setDeleteTarget(null);
      if (onToast) onToast(t('announcements.toast_delete'), 'success');
    } catch (err) {
      if (onToast) onToast(t('announcements.toast_delete_failed', { error: err.message }), 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const canSubmit =
    !!form.channelId &&
    !!form.content.trim() &&
    (form.type !== 'CRON' || !!form.cron.trim());

  const handleCreate = async () => {
    if (!canSubmit || submitting) return;
    setSubmitting(true);
    try {
      const body = { type: form.type, channelId: form.channelId, content: form.content.trim() };
      if (form.type === 'CRON') body.cron = form.cron.trim();
      await api.createAnnouncement(body);
      if (form.type === 'INSTANT') {
        if (onToast) onToast(t('announcements.toast_sent'), 'success');
      } else {
        if (onToast) onToast(t('announcements.toast_create'), 'success');
        await fetchAnnouncements();
      }
      setShowCreateModal(false);
    } catch (err) {
      if (onToast) onToast(t('announcements.toast_create_failed', { error: err.message }), 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const typeOptions = [
    { value: 'INSTANT', label: t('announcements.type_instant') },
    { value: 'JOIN', label: t('announcements.type_join') },
    { value: 'CRON', label: t('announcements.type_cron') },
  ];

  const channelOptions = channels.map((c) => ({ value: c.id, label: c.name || c.id }));

  const typeLabel = (type) => {
    if (type === 'JOIN') return t('announcements.type_join');
    if (type === 'CRON') return t('announcements.type_cron');
    if (type === 'INSTANT') return t('announcements.type_instant');
    return type || '-';
  };

  const typeVariant = (type) => (type === 'JOIN' ? 'success' : type === 'CRON' ? 'info' : 'secondary');

  const channelLabel = (channelId) => {
    const ch = channels.find((c) => c.id === channelId);
    return ch ? (ch.name || ch.id) : (channelId || '-');
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('announcements.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('announcements.subtitle', { count: items.length })}</p>
        </div>
        <Button theme={theme} mode={mode} variant="default" onClick={openCreateModal} title={t('announcements.create')}>
          <Plus size={14} /> {t('announcements.create')}
        </Button>
      </div>

      {/* Error hint (endpoint missing / network failure) */}
      {error && (
        <Card className="p-3 border-destructive/30 bg-destructive/5">
          <div className="flex items-center gap-2 text-destructive">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('announcements.load_failed', { error })}</p>
          </div>
        </Card>
      )}

      {/* List */}
      <Card className="p-0 overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 size={20} className="animate-spin text-muted-foreground" />
          </div>
        ) : items.length === 0 ? (
          <div className="py-16 text-center text-muted-foreground">
            <Megaphone size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('announcements.empty')}</p>
            <p className="text-[11px] opacity-70 mt-1">{t('announcements.empty_hint')}</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('announcements.col_type')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('announcements.col_channel')}</th>
                  <th className="px-4 py-2.5 font-medium w-full">{t('announcements.col_content')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('announcements.col_cron')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('announcements.col_enabled')}</th>
                  <th className="px-4 py-2.5 font-medium whitespace-nowrap">{t('announcements.col_action')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id} className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors align-top">
                    <td className="px-4 py-2.5 whitespace-nowrap">
                      <Badge variant={typeVariant(item.type)}>{typeLabel(item.type)}</Badge>
                    </td>
                    <td className="px-4 py-2.5 whitespace-nowrap text-sky-600 dark:text-sky-400">{channelLabel(item.channelId)}</td>
                    <td className="px-4 py-2.5 text-foreground break-all">{item.content}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap font-mono text-muted-foreground">{item.type === 'CRON' ? (item.cron || '-') : '-'}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap">
                      {busyId === item.id ? (
                        <Loader2 size={14} className="animate-spin text-muted-foreground" />
                      ) : (
                        <Switch checked={!!item.enabled} onChange={(v) => handleToggle(item, v)} />
                      )}
                    </td>
                    <td className="px-4 py-2.5 whitespace-nowrap">
                      <Button
                        theme={theme}
                        mode={mode}
                        variant="ghost"
                        size="icon"
                        className="text-destructive hover:text-destructive"
                        onClick={() => setDeleteTarget(item)}
                        title={t('common.delete')}
                        aria-label={t('common.delete')}
                      >
                        <Trash2 size={14} />
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Create Modal */}
      <Modal
        isOpen={showCreateModal}
        onClose={() => !submitting && setShowCreateModal(false)}
        title={t('announcements.create_modal_title')}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          {/* Type */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">{t('announcements.field_type')}</label>
            <Select
              options={typeOptions}
              value={form.type}
              onChange={(v) => setForm((prev) => ({ ...prev, type: v }))}
            />
          </div>

          {/* Channel */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">{t('announcements.field_channel')}</label>
            <Select
              options={channelOptions}
              value={form.channelId}
              onChange={(v) => setForm((prev) => ({ ...prev, channelId: v }))}
              placeholder={t('announcements.field_channel')}
            />
          </div>

          {/* Content */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('announcements.field_content')} <span className="text-destructive">*</span>
            </label>
            <textarea
              value={form.content}
              onChange={(e) => setForm((prev) => ({ ...prev, content: e.target.value }))}
              placeholder={t('announcements.field_content_placeholder')}
              className={textareaClass}
            />
          </div>

          {/* Cron (CRON type only) */}
          {form.type === 'CRON' && (
            <div className="space-y-2">
              <label className="text-xs font-normal leading-none text-muted-foreground">
                {t('announcements.field_cron')} <span className="text-destructive">*</span>
              </label>
              <input
                type="text"
                value={form.cron}
                onChange={(e) => setForm((prev) => ({ ...prev, cron: e.target.value }))}
                placeholder={t('announcements.cron_placeholder')}
                className={`${inputClass} font-mono`}
              />
              <p className="text-[11px] text-muted-foreground">{t('announcements.cron_hint')}</p>
            </div>
          )}
        </div>

        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button variant="ghost" className="flex-1" theme={theme} mode={mode} onClick={() => setShowCreateModal(false)} disabled={submitting}>
            {t('common.cancel')}
          </Button>
          <Button variant="default" className="flex-1" theme={theme} mode={mode} onClick={handleCreate} disabled={submitting || !canSubmit}>
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('common.create')}
          </Button>
        </div>
      </Modal>

      {/* Delete Confirm Modal */}
      <Modal
        isOpen={!!deleteTarget}
        onClose={() => !submitting && setDeleteTarget(null)}
        title={t('announcements.delete_modal_title')}
        theme={theme}
        mode={mode}
      >
        <p className="text-xs text-muted-foreground">{t('announcements.delete_confirm')}</p>
        {deleteTarget && (
          <p className="text-xs text-foreground mt-2 p-2 rounded-md bg-muted/40 break-all">{deleteTarget.content}</p>
        )}
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button variant="ghost" className="flex-1" theme={theme} mode={mode} onClick={() => setDeleteTarget(null)} disabled={submitting}>
            {t('common.cancel')}
          </Button>
          <Button variant="destructive" className="flex-1" theme={theme} mode={mode} onClick={confirmDelete} disabled={submitting}>
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('common.delete')}
          </Button>
        </div>
      </Modal>
    </div>
  );
}

export default AnnouncementManagement;
