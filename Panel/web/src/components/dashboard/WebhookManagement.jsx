/**
 * Webhook Management Component
 * Manage webhooks - create, list, edit, enable/disable, test, delete.
 *
 * Follows the shadcn/ui reference idiom used by ChannelManagement: Card list
 * of webhooks with pill event/active badges, pill action Buttons, Modal for
 * create/edit + delete confirm. The backend exposes full CRUD plus a test
 * endpoint for webhooks, so this component performs real REST calls via the
 * api client (through the useDashboardData handlers).
 *
 * Batch-4 contract additions: webhook objects carry active(boolean) and
 * lastTriggered(epoch ms | null). Both are optional for old backends — the
 * active badge/switch only render when the field is a real boolean, and a
 * null/missing lastTriggered shows "never triggered".
 */

import React, { useState } from 'react';
import {
  Plus,
  Trash2,
  Bell,
  Search,
  Webhook,
  Loader2,
  Pencil,
  Send,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';
import Modal from '../ui/Modal';
import Select from '../ui/Select';
import Switch from '../ui/Switch';
import { can } from '../../lib/permissions';

function WebhookManagement({
  theme,
  mode,
  txtMain: _txtMain,
  txtSec: _txtSec,
  webhooks = [],
  onCreateWebhook,
  onDeleteWebhook,
  onUpdateWebhook,
  onTestWebhook,
  loading = false,
  role,
}) {
  void _txtMain; void _txtSec;
  const { t } = useTranslation();
  const canManage = can(role, 'webhooks.manage');
  const [searchQuery, setSearchQuery] = useState('');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [editTarget, setEditTarget] = useState(null);
  const [editForm, setEditForm] = useState({ url: '', event: 'message_sent', secret: '', active: true });
  const [testingId, setTestingId] = useState(null);
  const [togglingId, setTogglingId] = useState(null);
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
    } catch {
      // Handler already toasted; keep the modal open for corrections.
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
    } catch {
      // Handler already toasted; keep the modal open for confirmation.
    } finally {
      setSubmitting(false);
    }
  };

  // Open the edit modal seeded from the card. secret starts blank — it is
  // only sent when the user types a new one (blank = keep existing).
  const openEdit = (webhook) => {
    setEditForm({
      url: webhook.url || '',
      event: webhook.event || 'message_sent',
      secret: '',
      active: typeof webhook.active === 'boolean' ? webhook.active : true,
    });
    setEditTarget(webhook);
  };

  // Save the edit modal. Locked contract: PUT /api/webhooks/{id}
  // { url?, events?, secret?, active? } — the selected event is sent under
  // the contract's `events` key (the GET shape exposes a single `event`).
  const handleEditSave = async () => {
    if (!editTarget || !editForm.url || !onUpdateWebhook) return;
    setSubmitting(true);
    try {
      const body = { url: editForm.url, events: editForm.event, active: editForm.active };
      if (editForm.secret) body.secret = editForm.secret;
      await onUpdateWebhook(editTarget.id, body);
      setEditTarget(null);
    } catch {
      // Handler already toasted; keep the modal open for corrections.
    } finally {
      setSubmitting(false);
    }
  };

  // On-card quick enable/disable — PUT with { active } only.
  const handleQuickToggle = async (webhook, next) => {
    if (!onUpdateWebhook) return;
    setTogglingId(webhook.id);
    try {
      await onUpdateWebhook(webhook.id, { active: next });
    } catch {
      // Handler already toasted.
    } finally {
      setTogglingId(null);
    }
  };

  // Fire a test delivery — the handler toasts the {success, statusCode, error} result.
  const handleTest = async (webhook) => {
    if (!onTestWebhook) return;
    setTestingId(webhook.id);
    try {
      await onTestWebhook(webhook.id);
    } finally {
      setTestingId(null);
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
        {canManage && (
          <Button
            theme={theme}
            mode={mode}
            variant="default"
            onClick={() => setShowCreateModal(true)}
            title={t('webhooks.create')}
          >
            <Plus size={14} /> {t('webhooks.create')}
          </Button>
        )}
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
              <div className="flex items-start justify-between mb-3 gap-2">
                <div className="flex items-center gap-3 min-w-0">
                  <div className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground">
                    <Webhook size={16} />
                  </div>
                  <div className="min-w-0">
                    <h3 className="text-sm font-medium text-foreground truncate" title={webhook.url}>
                      {webhook.url}
                    </h3>
                    <p className="text-xs text-muted-foreground">#{webhook.id}</p>
                  </div>
                </div>
                {/* Real active badge + quick toggle — only rendered when the
                    backend provides the boolean (old backends omit it). */}
                {typeof webhook.active === 'boolean' && (
                  <div className="flex items-center gap-2 shrink-0">
                    <Badge variant={webhook.active ? 'success' : 'secondary'}>
                      {webhook.active ? t('webhooks.active') : t('webhooks.inactive')}
                    </Badge>
                    {canManage && (
                      togglingId === webhook.id ? (
                        <Loader2 size={14} className="animate-spin text-muted-foreground" />
                      ) : (
                        <Switch checked={webhook.active} onChange={(v) => handleQuickToggle(webhook, v)} />
                      )
                    )}
                  </div>
                )}
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
                <div className="flex items-center gap-2 p-2 rounded-md bg-muted/40 text-xs">
                  <span className="text-muted-foreground">{t('webhooks.last_triggered')}:</span>
                  <span className="text-foreground">
                    {webhook.lastTriggered ? formatTime(webhook.lastTriggered) : t('webhooks.never_triggered')}
                  </span>
                </div>
              </div>

              {/* Actions */}
              {canManage && (
                <div className="flex gap-2">
                  <Button
                    theme={theme}
                    mode={mode}
                    variant="outline"
                    className="flex-1 text-xs"
                    onClick={() => openEdit(webhook)}
                    title={t('common.edit')}
                  >
                    <Pencil size={12} /> {t('common.edit')}
                  </Button>
                  <Button
                    theme={theme}
                    mode={mode}
                    variant="outline"
                    className="flex-1 text-xs"
                    onClick={() => handleTest(webhook)}
                    disabled={testingId === webhook.id}
                    title={t('webhooks.test')}
                  >
                    {testingId === webhook.id ? <Loader2 size={12} className="animate-spin" /> : <Send size={12} />}
                    {t('webhooks.test')}
                  </Button>
                  <Button
                    theme={theme}
                    mode={mode}
                    variant="destructive"
                    size="icon"
                    onClick={() => setDeleteTarget(webhook)}
                    title={t('webhooks.delete')}
                    aria-label={t('webhooks.delete')}
                  >
                    <Trash2 size={12} />
                  </Button>
                </div>
              )}
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

      {/* Edit Webhook Modal */}
      <Modal
        isOpen={!!editTarget}
        onClose={() => !submitting && setEditTarget(null)}
        title={t('webhooks.edit_modal_title')}
        theme={theme}
        mode={mode}
      >
        <WebhookForm
          webhook={editForm}
          onChange={setEditForm}
          eventOptions={eventOptions}
          showActive
        />
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button
            variant="ghost"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={() => setEditTarget(null)}
            disabled={submitting}
          >
            {t('common.cancel')}
          </Button>
          <Button
            variant="default"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={handleEditSave}
            disabled={submitting || !editForm.url}
          >
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('common.save')}
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

// Webhook Form Component (shared by create + edit; edit adds the active switch)
function WebhookForm({ webhook, onChange, eventOptions, showActive = false }) {
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

      {/* Active (edit modal only) */}
      {showActive && (
        <div className="flex items-center justify-between">
          <label className="text-xs font-normal leading-none text-muted-foreground">
            {t('webhooks.field_active')}
          </label>
          <Switch
            checked={!!webhook.active}
            onChange={(v) => onChange({ ...webhook, active: v })}
          />
        </div>
      )}
    </div>
  );
}

export default WebhookManagement;
