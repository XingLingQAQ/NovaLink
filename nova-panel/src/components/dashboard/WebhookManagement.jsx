/**
 * Webhook Management Component
 * Manage webhooks - create, list, delete.
 *
 * Follows the shadcn/ui reference idiom used by ChannelManagement: Card list
 * of webhooks with pill event/active badges, pill action Buttons, Modal for
 * create + delete confirm. The backend exposes full CRUD for webhooks, so
 * this component performs real REST calls via the api client.
 */

import React, { useState } from 'react';
import {
  Plus,
  Trash2,
  Bell,
  Search,
  Webhook,
  Loader2,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';
import Modal from '../ui/Modal';
import Select from '../ui/Select';

function WebhookManagement({
  theme,
  mode,
  txtMain: _txtMain,
  txtSec: _txtSec,
  webhooks = [],
  onCreateWebhook,
  onDeleteWebhook,
  loading = false,
}) {
  void _txtMain; void _txtSec;
  const { t } = useTranslation();
  const [searchQuery, setSearchQuery] = useState('');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [newWebhook, setNewWebhook] = useState({
    url: '',
    event: 'message_sent',
    secret: '',
  });

  // Event options.
  const eventOptions = [
    { value: 'message_sent', label: t('webhooks.event_message_sent') },
    { value: 'player_join', label: t('webhooks.event_player_join') },
    { value: 'player_leave', label: t('webhooks.event_player_leave') },
    { value: 'channel_update', label: t('webhooks.event_channel_update') },
  ];

  // Filter webhooks.
  const filteredWebhooks = webhooks.filter((w) => {
    const q = (searchQuery || '').toLowerCase();
    const matchesSearch = ((w && w.url) || '').toLowerCase().includes(q) ||
                          ((w && w.event) || '').toLowerCase().includes(q);
    return matchesSearch;
  });

  // Format created time.
  const formatTime = (ts) => {
    if (!ts) return '-';
    try {
      const num = typeof ts === 'number' ? ts : Number(ts);
      if (Number.isNaN(num)) return String(ts);
      return new Date(num).toLocaleString();
    } catch {
      return String(ts);
    }
  };

  // Get event badge variant.
  const getEventVariant = (event) => {
    switch (event) {
      case 'message_sent': return 'info';
      case 'player_join': return 'success';
      case 'player_leave': return 'warning';
      case 'channel_update': return 'secondary';
      default: return 'secondary';
    }
  };

  // Handle create webhook.
  const handleCreate = async () => {
    if (!newWebhook.url) return;
    setSubmitting(true);
    try {
      await onCreateWebhook({
        url: newWebhook.url,
        event: newWebhook.event,
        secret: newWebhook.secret || undefined,
      });
      setShowCreateModal(false);
      setNewWebhook({ url: '', event: 'message_sent', secret: '' });
    } finally {
      setSubmitting(false);
    }
  };

  // Confirm delete.
  const confirmDelete = async () => {
    if (!deleteTarget) return;
    setSubmitting(true);
    try {
      await onDeleteWebhook(deleteTarget.id);
      setDeleteTarget(null);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('webhooks.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('webhooks.subtitle', { count: webhooks.length })}</p>
        </div>
        <Button
          theme={theme}
          mode={mode}
          variant="default"
          onClick={() => setShowCreateModal(true)}
          title={t('webhooks.create')}
        >
          <Plus size={14} /> {t('webhooks.create')}
        </Button>
      </div>

      {/* Filters */}
      <Card className="p-3">
        <div className="flex flex-wrap items-center gap-3">
          {/* Search */}
          <div className="flex items-center gap-2 rounded-md bg-secondary/55 px-2.5 py-1 flex-1 min-w-[200px]">
            <Search size={14} className="text-muted-foreground" />
            <input
              type="text"
              placeholder={t('webhooks.search_placeholder')}
              className="bg-transparent border-none outline-none text-xs flex-1 placeholder:text-muted-foreground text-foreground"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </div>
      </Card>

      {/* Loading State */}
      {loading && (
        <Card className="p-12 text-center">
          <Loader2 size={28} className="mx-auto mb-3 animate-spin text-muted-foreground" />
          <p className="text-xs text-muted-foreground">{t('common.loading_data')}</p>
        </Card>
      )}

      {/* Webhook List */}
      {!loading && filteredWebhooks.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filteredWebhooks.map((webhook) => (
            <Card key={webhook.id} className="p-4">
              <div className="flex items-start justify-between mb-3">
                <div className="flex items-center gap-3">
                  <div className="flex size-8 items-center justify-center rounded-md bg-muted text-muted-foreground">
                    <Webhook size={16} />
                  </div>
                  <div className="min-w-0">
                    <h3 className="text-sm font-medium text-foreground truncate" title={webhook.url}>
                      {webhook.url}
                    </h3>
                    <p className="text-xs text-muted-foreground">#{webhook.id}</p>
                  </div>
                </div>
                <Badge variant={webhook.active === false ? 'secondary' : 'success'}>
                  {webhook.active === false ? t('webhooks.inactive') : t('webhooks.active')}
                </Badge>
              </div>

              {/* Webhook Details */}
              <div className="space-y-1.5 mb-3">
                <div className="flex items-center gap-2 p-2 rounded-md bg-muted/40 text-xs">
                  <span className="text-muted-foreground">{t('webhooks.event')}:</span>
                  <Badge variant={getEventVariant(webhook.event)}>{webhook.event}</Badge>
                </div>
                <div className="flex items-center gap-2 p-2 rounded-md bg-muted/40 text-xs">
                  <span className="text-muted-foreground">{t('webhooks.created_at')}:</span>
                  <span className="text-foreground">{formatTime(webhook.createdAt)}</span>
                </div>
              </div>

              {/* Actions */}
              <div className="flex gap-2">
                <Button
                  theme={theme}
                  mode={mode}
                  variant="destructive"
                  className="flex-1 text-xs"
                  onClick={() => setDeleteTarget(webhook)}
                  title={t('webhooks.delete')}
                >
                  <Trash2 size={12} /> {t('webhooks.delete')}
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}

      {/* Empty State */}
      {!loading && filteredWebhooks.length === 0 && (
        <Card className="p-12 text-center">
          <Bell size={40} className="mx-auto mb-3 text-muted-foreground" />
          <p className="text-sm text-foreground">{t('webhooks.empty')}</p>
          <p className="text-xs text-muted-foreground mt-1">{t('webhooks.empty_hint')}</p>
        </Card>
      )}

      {/* Create Webhook Modal */}
      <Modal
        isOpen={showCreateModal}
        onClose={() => !submitting && setShowCreateModal(false)}
        title={t('webhooks.create_modal_title')}
        theme={theme}
        mode={mode}
      >
        <WebhookForm
          webhook={newWebhook}
          onChange={setNewWebhook}
          eventOptions={eventOptions}
        />
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button
            variant="ghost"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={() => setShowCreateModal(false)}
            disabled={submitting}
          >
            {t('common.cancel')}
          </Button>
          <Button
            variant="default"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={handleCreate}
            disabled={submitting || !newWebhook.url}
          >
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('common.create')}
          </Button>
        </div>
      </Modal>

      {/* Delete Confirm Modal */}
      <Modal
        isOpen={!!deleteTarget}
        onClose={() => !submitting && setDeleteTarget(null)}
        title={t('webhooks.delete_modal_title')}
        theme={theme}
        mode={mode}
      >
        <p className="text-xs text-muted-foreground">
          {t('webhooks.delete_confirm', { url: (deleteTarget && deleteTarget.url) || '' })}
        </p>
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button
            variant="ghost"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={() => setDeleteTarget(null)}
            disabled={submitting}
          >
            {t('common.cancel')}
          </Button>
          <Button
            variant="destructive"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={confirmDelete}
            disabled={submitting}
          >
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('common.delete')}
          </Button>
        </div>
      </Modal>
    </div>
  );
}

// Webhook Form Component
function WebhookForm({ webhook, onChange, eventOptions }) {
  const { t } = useTranslation();
  const inputClass =
    'flex h-8 w-full rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50';

  return (
    <div className="space-y-4">
      {/* URL */}
      <div className="space-y-2">
        <label className="text-xs font-normal leading-none text-muted-foreground">
          {t('webhooks.url')} <span className="text-destructive">*</span>
        </label>
        <input
          type="text"
          value={webhook.url}
          onChange={(e) => onChange({ ...webhook, url: e.target.value })}
          placeholder="https://example.com/webhook"
          className={inputClass}
        />
      </div>

      {/* Event */}
      <div className="space-y-2">
        <label className="text-xs font-normal leading-none text-muted-foreground">
          {t('webhooks.event')}
        </label>
        <Select
          options={eventOptions}
          value={webhook.event}
          onChange={(v) => onChange({ ...webhook, event: v })}
        />
      </div>

      {/* Secret */}
      <div className="space-y-2">
        <label className="text-xs font-normal leading-none text-muted-foreground">
          {t('webhooks.secret')} <span className="text-muted-foreground/60">({t('webhooks.optional')})</span>
        </label>
        <input
          type="text"
          value={webhook.secret}
          onChange={(e) => onChange({ ...webhook, secret: e.target.value })}
          placeholder={t('webhooks.secret_placeholder')}
          className={inputClass}
        />
      </div>
    </div>
  );
}

export default WebhookManagement;
