/**
 * Channel Management Component
 * Manage chat channels - create, edit, delete channels + generate invite codes.
 *
 * Restyled to the shadcn/ui reference idiom: Card list of channels with pill
 * type/permission badges, pill action Buttons, Modal for create/edit/delete.
 *
 * Batch 2: channel CRUD is now wired to real REST endpoints via the api client.
 * The create/edit/delete/invite handlers delegate to App.jsx which performs the
 * REST call + toast + list refresh.
 */

import React, { useState } from 'react';
import {
  Plus,
  Edit,
  Trash2,
  Globe,
  Hash,
  Lock,
  Search,
  Eye,
  Loader2,
  Gift,
  Copy,
  Check,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';
import Modal from '../ui/Modal';
import { api } from '../../services/api';
import { can } from '../../lib/permissions';

function parseSlowModeSeconds(value) {
  if (value === '' || value == null) return null;
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : null;
}

function formatSlowMode(t, value) {
  const seconds = parseSlowModeSeconds(value) ?? 0;
  return seconds === 0
    ? t('channels.slow_mode_disabled')
    : t('channels.slow_mode_seconds', { count: seconds });
}

function ChannelManagement({
  theme,
  mode,
  txtMain: _txtMain,
  txtSec: _txtSec,
  channels = [],
  onCreateChannel,
  onEditChannel,
  onDeleteChannel,
  onInviteChannel,
  role,
}) {
  void _txtMain; void _txtSec;
  const { t } = useTranslation();
  const canManage = can(role, 'channels.manage');
  const [searchQuery, setSearchQuery] = useState('');
  const [filterType, setFilterType] = useState('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [editingChannel, setEditingChannel] = useState(null);
  const [detailChannel, setDetailChannel] = useState(null);
  const [detailMembers, setDetailMembers] = useState([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  // Invite modal state.
  const [inviteTarget, setInviteTarget] = useState(null);
  const [inviteTtl, setInviteTtl] = useState('');
  const [inviteResult, setInviteResult] = useState(null);
  const [inviteCopied, setInviteCopied] = useState(false);

  // New channel form — aligned with backend POST /api/channels body.
  // { id?, displayName, scope, clientId?, maxCapacity, permission?, slowModeSeconds }
  // PANEL-003: clientId is REQUIRED for SERVER/PRIVATE scope (must reference a
  // real connected client); GLOBAL omits it.
  const emptyChannel = {
    id: '',
    name: '',
    scope: 'GLOBAL',
    clientId: '',
    maxCapacity: 100,
    permission: '',
    slowModeSeconds: 0,
  };
  const [newChannel, setNewChannel] = useState(emptyChannel);
  const newSlowModeSeconds = parseSlowModeSeconds(newChannel.slowModeSeconds);
  const newMaxCapacity = Number(newChannel.maxCapacity);
  const newMaxCapacityValid = Number.isInteger(newMaxCapacity) && newMaxCapacity > 0;
  const newClientIdRequired = newChannel.scope === 'SERVER' || newChannel.scope === 'PRIVATE';
  const newClientIdValid = !newClientIdRequired || !!(newChannel.clientId && newChannel.clientId.trim());
  const editingSlowModeSeconds = parseSlowModeSeconds(editingChannel?.slowModeSeconds);
  const editingMaxCapacity = Number(editingChannel?.maxCapacity);
  const editingMaxCapacityValid = Number.isInteger(editingMaxCapacity) && editingMaxCapacity > 0;

  // Filter channels.
  const filteredChannels = channels.filter((c) => {
    const q = (searchQuery || '').toLowerCase();
    const matchesSearch = ((c && c.name) || '').toLowerCase().includes(q) ||
                          ((c && c.id) || '').toLowerCase().includes(q);
    const matchesType = filterType === 'all' || (c && c.type) === filterType;
    return matchesSearch && matchesType;
  });

  // Get channel icon.
  const getChannelIcon = (type) => {
    switch (type) {
      case 'GLOBAL': return Globe;
      case 'LOCAL':
      case 'SERVER': return Hash;
      case 'PRIVATE': return Lock;
      default: return Hash;
    }
  };

  // Get channel type badge variant.
  const getTypeVariant = (type) => {
    switch (type) {
      case 'GLOBAL': return 'info';
      case 'LOCAL':
      case 'SERVER': return 'secondary';
      case 'PRIVATE': return 'warning';
      default: return 'secondary';
    }
  };

  // Handle create channel — calls App handler with backend-shaped body.
  const handleCreate = async () => {
    // PANEL-003: validate before submit so the user gets immediate feedback
    // instead of a 400 round-trip. maxCapacity must be a positive integer;
    // SERVER/PRIVATE scope requires a non-empty clientId (the backend will
    // additionally verify the client is currently connected).
    if (!newChannel.name || newSlowModeSeconds == null || !newMaxCapacityValid || !newClientIdValid) return;
    const body = {
      displayName: newChannel.name,
      scope: newChannel.scope,
      maxCapacity: newMaxCapacity,
      slowModeSeconds: newSlowModeSeconds,
    };
    if (newChannel.id && newChannel.id.trim()) body.id = newChannel.id.trim();
    // GLOBAL scope must not carry a clientId (backend rejects it); SERVER /
    // PRIVATE must carry the user-entered clientId.
    if (newClientIdRequired) body.clientId = newChannel.clientId.trim();
    if (newChannel.permission && newChannel.permission.trim()) body.permission = newChannel.permission.trim();
    setSubmitting(true);
    try {
      await onCreateChannel(body);
      setShowCreateModal(false);
      setNewChannel(emptyChannel);
    } catch {
      // toast shown by App handler
    } finally {
      setSubmitting(false);
    }
  };

  // Handle edit channel — opens the edit modal with the channel's current values.
  const handleEdit = (channel) => {
    setEditingChannel({
      id: channel.id,
      name: channel.name,
      scope: channel.type,
      maxCapacity: channel.maxCapacity || 100,
      permission: channel.permission || '',
      slowModeSeconds: channel.slowModeSeconds ?? 0,
      source: channel.source || 'RUNTIME',
    });
    setShowEditModal(true);
  };

  // Handle save edit — calls App handler with the updatable fields only.
  const handleSaveEdit = async () => {
    if (!editingChannel || !editingChannel.id || editingSlowModeSeconds == null) return;
    const body = {};
    if (editingChannel.name) body.displayName = editingChannel.name;
    body.maxCapacity = Number(editingChannel.maxCapacity) || 100;
    body.slowModeSeconds = editingSlowModeSeconds;
    // PANEL-003: explicitly signal that the permission field is being set so
    // the backend applies it (including clearing it to null). Without
    // permissionPresent the backend's legacy overload treats null as
    // "leave untouched" and the old value survives a clear.
    body.permissionPresent = true;
    body.permission = (editingChannel.permission && editingChannel.permission.trim()) || null;
    setSubmitting(true);
    try {
      await onEditChannel(editingChannel.id, body);
      setShowEditModal(false);
      setEditingChannel(null);
    } catch {
      // toast shown by App handler
    } finally {
      setSubmitting(false);
    }
  };

  // Handle delete channel — opens a confirm modal (destructive action).
  const handleDelete = (channel) => {
    setDeleteTarget(channel);
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    setSubmitting(true);
    try {
      await onDeleteChannel(deleteTarget.id);
      setDeleteTarget(null);
    } catch {
      // toast shown by App handler
    } finally {
      setSubmitting(false);
    }
  };

  // Handle generate invite — opens the invite modal.
  const handleInvite = (channel) => {
    setInviteTarget(channel);
    setInviteTtl('');
    setInviteResult(null);
    setInviteCopied(false);
  };

  const confirmInvite = async () => {
    if (!inviteTarget) return;
    const body = {};
    const ttl = Number(inviteTtl);
    if (inviteTtl && !Number.isNaN(ttl) && ttl > 0) body.ttlMillis = ttl;
    setSubmitting(true);
    try {
      const res = await onInviteChannel(inviteTarget.id, body);
      setInviteResult(res);
      setInviteCopied(false);
    } catch {
      // toast shown by App handler
    } finally {
      setSubmitting(false);
    }
  };

  const copyInviteCode = () => {
    if (inviteResult && inviteResult.code) {
      try {
        navigator.clipboard.writeText(inviteResult.code);
        setInviteCopied(true);
        setTimeout(() => setInviteCopied(false), 2000);
      } catch {
        // clipboard may be unavailable; ignore
      }
    }
  };

  // Handle view channel details — fetches full channel + members via REST.
  const handleViewDetails = async (channel) => {
    setDetailChannel(null);
    setDetailMembers([]);
    setDetailLoading(true);
    setShowDetailModal(true);
    try {
      const [detail, membersRes] = await Promise.all([
        api.getChannel(channel.id).catch((e) => { console.warn('[channel detail] getChannel failed:', e); return null; }),
        api.getChannelMembers(channel.id).catch((e) => { console.warn('[channel detail] getChannelMembers failed:', e); return null; }),
      ]);
      if (detail) setDetailChannel(detail);
      else setDetailChannel(channel);
      if (membersRes && Array.isArray(membersRes.members)) {
        setDetailMembers(membersRes.members);
      }
    } catch (err) {
      console.error('[channel detail] failed:', err);
      setDetailChannel(channel);
    } finally {
      setDetailLoading(false);
    }
  };

  // Channel scope options (aligned with backend ChannelScope enum).
  const channelScopes = [
    { value: 'GLOBAL', label: t('channels.scope_global'), icon: Globe },
    { value: 'SERVER', label: t('channels.scope_server'), icon: Hash },
    { value: 'PRIVATE', label: t('channels.scope_private'), icon: Lock },
  ];

  const inputClass =
    'flex h-8 w-full rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50';

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('channels.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('channels.subtitle', { count: channels.length })}</p>
        </div>
        {canManage && (
          <Button
            theme={theme}
            mode={mode}
            variant="default"
            onClick={() => setShowCreateModal(true)}
            title={t('channels.create')}
          >
            <Plus size={14} /> {t('channels.create')}
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
              placeholder={t('channels.search_placeholder')}
              className="bg-transparent border-none outline-none text-xs flex-1 placeholder:text-muted-foreground text-foreground"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>

          {/* Type Filter — PANEL-009: real <button> toggles with aria-pressed
              and a labeled group so assistive tech announces the filter state.
              Group is allowed to wrap on small screens (flex-wrap). */}
          <div
            role="group"
            aria-label={t('channels.title')}
            className="flex flex-wrap gap-1"
          >
            {['all', 'GLOBAL', 'SERVER', 'PRIVATE'].map((type) => {
              const pressed = filterType === type;
              // Visible text is the accessible name; "all" uses the existing
              // translated label, typed filters use the scope string.
              const label = type === 'all' ? t('channels.filter_all') : type;
              return (
                <button
                  key={type}
                  type="button"
                  aria-pressed={pressed}
                  onClick={() => setFilterType(type)}
                  className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring ${
                    pressed
                      ? 'bg-primary text-primary-foreground'
                      : 'text-muted-foreground hover:bg-accent hover:text-foreground'
                  }`}
                >
                  {label}
                </button>
              );
            })}
          </div>
        </div>
      </Card>

      {/* Channel Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {filteredChannels.map((channel) => {
          const Icon = getChannelIcon(channel.type);
          // PANEL-003: CONFIG channels are managed by config reload and are
          // read-only in the panel — hide edit/delete/invite and surface a
          // read-only badge + hint. DATABASE/RUNTIME (or missing source)
          // remain fully editable dynamic channels.
          const isConfigManaged = channel.source === 'CONFIG';
          return (
            <Card key={channel.id} className="p-4">
              <div className="flex items-start justify-between mb-3">
                <div className="flex items-center gap-3">
                  <div className="flex size-8 items-center justify-center rounded-md bg-muted text-muted-foreground">
                    <Icon size={16} />
                  </div>
                  <div>
                    <h3 className="text-sm font-medium text-foreground">{channel.name}</h3>
                    <p className="text-xs text-muted-foreground">#{channel.id}</p>
                  </div>
                </div>
                <div className="flex flex-col items-end gap-1">
                  <Badge variant={getTypeVariant(channel.type)}>{channel.type}</Badge>
                  <Badge variant={isConfigManaged ? 'secondary' : 'outline'} title={isConfigManaged ? t('channels.source_config_hint') : undefined}>
                    {isConfigManaged ? t('channels.source_config') : t('channels.source_dynamic')}
                  </Badge>
                </div>
              </div>

              {/* Channel Details */}
              <div className="space-y-1.5 mb-3">
                <div className="p-2 rounded-md bg-muted/40 text-xs">
                  <span className="text-muted-foreground">{t('channels.permission')}: </span>
                  <span className="text-foreground">{channel.permission || t('channels.permission_none')}</span>
                </div>
                <div className="flex items-center gap-2 p-2 rounded-md bg-muted/40 text-xs">
                  <span className="text-muted-foreground">{t('channels.members')}: </span>
                  <span className="text-foreground">{channel.memberCount || 0}/{channel.maxCapacity || 0}</span>
                  {channel.clientId && (
                    <>
                      <span className="text-muted-foreground">· {t('channels.server')}: </span>
                      <span className="text-foreground">{channel.clientId}</span>
                    </>
                  )}
                </div>
                <div className="p-2 rounded-md bg-muted/40 text-xs">
                  <span className="text-muted-foreground">{t('channels.field_slow_mode')}: </span>
                  <span className="text-foreground">{formatSlowMode(t, channel.slowModeSeconds)}</span>
                </div>
                {isConfigManaged && (
                  <p className="text-[11px] text-muted-foreground italic">{t('channels.source_config_hint')}</p>
                )}
              </div>

              {/* Actions */}
              <div className="flex gap-2 flex-wrap">
                <Button
                  theme={theme}
                  mode={mode}
                  variant="outline"
                  className="flex-1 text-xs"
                  onClick={() => handleViewDetails(channel)}
                  title={t('channels.details')}
                >
                  <Eye size={12} /> {t('channels.details')}
                </Button>
                {canManage && !isConfigManaged && (
                  <>
                    <Button
                      theme={theme}
                      mode={mode}
                      variant="outline"
                      className="text-xs"
                      onClick={() => handleEdit(channel)}
                      title={t('channels.edit')}
                    >
                      <Edit size={12} /> {t('channels.edit')}
                    </Button>
                    <Button
                      theme={theme}
                      mode={mode}
                      variant="ghost"
                      size="icon"
                      onClick={() => handleInvite(channel)}
                      title={t('channels.invite')}
                      aria-label={t('channels.invite')}
                    >
                      <Gift size={12} />
                    </Button>
                    <Button
                      theme={theme}
                      mode={mode}
                      variant="destructive"
                      size="icon"
                      onClick={() => handleDelete(channel)}
                      title={t('common.delete')}
                      aria-label={t('common.delete')}
                    >
                      <Trash2 size={12} />
                    </Button>
                  </>
                )}
                {canManage && isConfigManaged && (
                  <span className="text-[11px] text-muted-foreground self-center">{t('channels.config_readonly')}</span>
                )}
              </div>
            </Card>
          );
        })}
      </div>

      {/* Empty State */}
      {filteredChannels.length === 0 && (
        <Card className="p-12 text-center">
          <Hash size={40} className="mx-auto mb-3 text-muted-foreground" />
          <p className="text-sm text-foreground">{t('channels.not_found')}</p>
          <p className="text-xs text-muted-foreground mt-1">{t('channels.not_found_hint')}</p>
        </Card>
      )}

      {/* Create Channel Modal */}
      <Modal
        isOpen={showCreateModal}
        onClose={() => !submitting && setShowCreateModal(false)}
        title={t('channels.create_modal_title')}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          {/* Channel ID (optional) */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('channels.field_id_optional')}
            </label>
            <input
              type="text"
              value={newChannel.id}
              onChange={(e) => setNewChannel({ ...newChannel, id: e.target.value.toLowerCase().replace(/\s/g, '_') })}
              placeholder="channel_id"
              className={inputClass}
            />
            <p className="text-[11px] text-muted-foreground">{t('channels.field_id_hint')}</p>
          </div>

          {/* Display Name */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('channels.field_display_name')} <span className="text-destructive">*</span>
            </label>
            <input
              type="text"
              value={newChannel.name}
              onChange={(e) => setNewChannel({ ...newChannel, name: e.target.value })}
              placeholder={t('channels.field_display_name_placeholder')}
              className={inputClass}
            />
          </div>

          {/* Scope */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('channels.field_scope')}
            </label>
            <div className="grid grid-cols-3 gap-2">
              {channelScopes.map((scope) => (
                <button
                  key={scope.value}
                  onClick={() => setNewChannel({ ...newChannel, scope: scope.value })}
                  className={`flex flex-col items-center gap-1 rounded-md border p-3 text-center transition-colors ${
                    newChannel.scope === scope.value
                      ? 'border-primary bg-primary/10 text-primary'
                      : 'border-border bg-background text-muted-foreground hover:bg-accent hover:text-foreground'
                  }`}
                >
                  <scope.icon size={16} />
                  <span className="text-xs">{scope.label}</span>
                </button>
              ))}
            </div>
          </div>

          {/* Client ID — PANEL-003: required for SERVER/PRIVATE scope (must
              reference a real connected client), hidden for GLOBAL. */}
          {newClientIdRequired && (
            <div className="space-y-2">
              <label className="text-xs font-normal leading-none text-muted-foreground">
                {t('channels.field_client_id')} <span className="text-destructive">*</span>
              </label>
              <input
                type="text"
                value={newChannel.clientId}
                onChange={(e) => setNewChannel({ ...newChannel, clientId: e.target.value })}
                placeholder={t('channels.field_client_id_placeholder')}
                className={inputClass}
              />
              <p className="text-[11px] text-muted-foreground">{t('channels.field_client_id_hint')}</p>
              {!newClientIdValid && (
                <p className="text-[11px] text-destructive" role="alert">
                  {t('channels.validation_client_id_required')}
                </p>
              )}
            </div>
          )}

          {/* Max Capacity */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('channels.field_max_capacity')}
            </label>
            <input
              type="number"
              min="1"
              step="1"
              value={newChannel.maxCapacity}
              onChange={(e) => setNewChannel({ ...newChannel, maxCapacity: e.target.value })}
              placeholder={t('channels.field_max_capacity_placeholder')}
              className={inputClass}
            />
            {!newMaxCapacityValid && (
              <p className="text-[11px] text-destructive" role="alert">
                {t('channels.validation_max_capacity_invalid')}
              </p>
            )}
          </div>

          {/* Slow Mode */}
          <div className="space-y-2">
            <label
              htmlFor="create-channel-slow-mode"
              className="text-xs font-normal leading-none text-muted-foreground"
            >
              {t('channels.field_slow_mode')}
            </label>
            <input
              id="create-channel-slow-mode"
              type="number"
              min="0"
              step="1"
              value={newChannel.slowModeSeconds}
              onChange={(e) => setNewChannel({ ...newChannel, slowModeSeconds: e.target.value })}
              aria-describedby={`create-channel-slow-mode-hint${newSlowModeSeconds == null ? ' create-channel-slow-mode-error' : ''}`}
              aria-invalid={newSlowModeSeconds == null ? true : undefined}
              className={inputClass}
            />
            <p id="create-channel-slow-mode-hint" className="text-[11px] text-muted-foreground">
              {t('channels.field_slow_mode_hint')}
            </p>
            {newSlowModeSeconds == null && (
              <p id="create-channel-slow-mode-error" className="text-[11px] text-destructive" role="alert">
                {t('channels.field_slow_mode_invalid')}
              </p>
            )}
          </div>

          {/* Permission */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('channels.field_permission')}
            </label>
            <input
              type="text"
              value={newChannel.permission}
              onChange={(e) => setNewChannel({ ...newChannel, permission: e.target.value })}
              placeholder="novachat.channel.example"
              className={inputClass}
            />
          </div>
        </div>
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
            disabled={submitting || !newChannel.name || newSlowModeSeconds == null || !newMaxCapacityValid || !newClientIdValid}
          >
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('common.create')}
          </Button>
        </div>
      </Modal>

      {/* Edit Channel Modal */}
      <Modal
        isOpen={showEditModal}
        onClose={() => !submitting && setShowEditModal(false)}
        title={t('channels.edit_modal_title')}
        theme={theme}
        mode={mode}
      >
        {editingChannel && (
          <div className="space-y-4">
            {/* Channel ID (read-only) */}
            <div className="space-y-2">
              <label className="text-xs font-normal leading-none text-muted-foreground">
                {t('channels.field_channel_id')}
              </label>
              <input
                type="text"
                value={editingChannel.id}
                disabled
                className={`${inputClass} opacity-50 cursor-not-allowed`}
              />
            </div>

            {/* Display Name */}
            <div className="space-y-2">
              <label className="text-xs font-normal leading-none text-muted-foreground">
                {t('channels.field_display_name')}
              </label>
              <input
                type="text"
                value={editingChannel.name}
                onChange={(e) => setEditingChannel({ ...editingChannel, name: e.target.value })}
                placeholder={t('channels.field_display_name_placeholder')}
                className={inputClass}
              />
            </div>

            {/* Max Capacity */}
            <div className="space-y-2">
              <label className="text-xs font-normal leading-none text-muted-foreground">
                {t('channels.field_max_capacity')}
              </label>
              <input
                type="number"
                min="1"
                step="1"
                value={editingChannel.maxCapacity}
                onChange={(e) => setEditingChannel({ ...editingChannel, maxCapacity: e.target.value })}
                placeholder={t('channels.field_max_capacity_placeholder')}
                className={inputClass}
              />
              {!editingMaxCapacityValid && (
                <p className="text-[11px] text-destructive" role="alert">
                  {t('channels.validation_max_capacity_invalid')}
                </p>
              )}
            </div>

            {/* Slow Mode */}
            <div className="space-y-2">
              <label
                htmlFor="edit-channel-slow-mode"
                className="text-xs font-normal leading-none text-muted-foreground"
              >
                {t('channels.field_slow_mode')}
              </label>
              <input
                id="edit-channel-slow-mode"
                type="number"
                min="0"
                step="1"
                value={editingChannel.slowModeSeconds}
                onChange={(e) => setEditingChannel({ ...editingChannel, slowModeSeconds: e.target.value })}
                aria-describedby={`edit-channel-slow-mode-hint${editingSlowModeSeconds == null ? ' edit-channel-slow-mode-error' : ''}`}
                aria-invalid={editingSlowModeSeconds == null ? true : undefined}
                className={inputClass}
              />
              <p id="edit-channel-slow-mode-hint" className="text-[11px] text-muted-foreground">
                {t('channels.field_slow_mode_hint')}
              </p>
              {editingSlowModeSeconds == null && (
                <p id="edit-channel-slow-mode-error" className="text-[11px] text-destructive" role="alert">
                  {t('channels.field_slow_mode_invalid')}
                </p>
              )}
            </div>

            {/* Permission */}
            <div className="space-y-2">
              <label className="text-xs font-normal leading-none text-muted-foreground">
                {t('channels.field_permission')}
              </label>
              <input
                type="text"
                value={editingChannel.permission}
                onChange={(e) => setEditingChannel({ ...editingChannel, permission: e.target.value })}
                placeholder="novachat.channel.example"
                className={inputClass}
              />
            </div>
          </div>
        )}
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button
            variant="ghost"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={() => setShowEditModal(false)}
            disabled={submitting}
          >
            {t('common.cancel')}
          </Button>
          <Button
            variant="default"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={handleSaveEdit}
            disabled={submitting || editingSlowModeSeconds == null || !editingMaxCapacityValid}
          >
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('common.save')}
          </Button>
        </div>
      </Modal>

      {/* Delete Channel Confirm Modal */}
      <Modal
        isOpen={!!deleteTarget}
        onClose={() => !submitting && setDeleteTarget(null)}
        title={t('channels.delete_modal_title')}
        theme={theme}
        mode={mode}
      >
        <p className="text-xs text-muted-foreground">
          {t('channels.delete_confirm_name', { name: (deleteTarget && deleteTarget.name) || '' })}
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

      {/* Invite Channel Modal */}
      <Modal
        isOpen={!!inviteTarget}
        onClose={() => !submitting && setInviteTarget(null)}
        title={t('channels.invite_modal_title')}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('channels.invite_ttl')}
            </label>
            <input
              type="number"
              min="0"
              value={inviteTtl}
              onChange={(e) => setInviteTtl(e.target.value)}
              placeholder={t('channels.invite_ttl_placeholder')}
              className={inputClass}
            />
          </div>

          {inviteResult && inviteResult.code && (
            <div className="space-y-2">
              <label className="text-xs font-normal leading-none text-muted-foreground">
                {t('channels.invite_code')}
              </label>
              <div className="flex items-center gap-2">
                <div className="flex-1 rounded-md bg-secondary/55 px-3 py-1.5 text-xs font-mono text-foreground break-all">
                  {inviteResult.code}
                </div>
                <Button
                  theme={theme}
                  mode={mode}
                  variant="outline"
                  size="icon"
                  onClick={copyInviteCode}
                  title={t('channels.invite_copy')}
                >
                  {inviteCopied ? <Check size={12} className="text-emerald-600 dark:text-emerald-400" /> : <Copy size={12} />}
                </Button>
              </div>
              <p className="text-[11px] text-muted-foreground">{t('channels.invite_code_hint')}</p>
            </div>
          )}
        </div>
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button
            variant="ghost"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={() => setInviteTarget(null)}
            disabled={submitting}
          >
            {inviteResult ? t('common.confirm') : t('common.cancel')}
          </Button>
          {!inviteResult && (
            <Button
              variant="default"
              className="flex-1"
              theme={theme}
              mode={mode}
              onClick={confirmInvite}
              disabled={submitting}
            >
              {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
              {t('common.confirm')}
            </Button>
          )}
        </div>
      </Modal>

      {/* Channel Details Modal */}
      <Modal
        isOpen={showDetailModal}
        onClose={() => setShowDetailModal(false)}
        title={t('channels.details_modal_title')}
        theme={theme}
        mode={mode}
      >
        {detailLoading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 size={20} className="animate-spin text-muted-foreground" />
          </div>
        ) : detailChannel ? (
          <ChannelDetails channel={detailChannel} members={detailMembers} />
        ) : null}
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button variant="ghost" className="flex-1" theme={theme} mode={mode} onClick={() => setShowDetailModal(false)}>
            {t('common.confirm')}
          </Button>
        </div>
      </Modal>
    </div>
  );
}

// Channel Details Component
function ChannelDetails({ channel, members }) {
  const { t } = useTranslation();
  const detail = channel || {};
  const displayId = detail.id || detail.channelId || '-';
  const displayName = detail.displayName || detail.name || displayId;
  const scope = detail.scope || detail.type || '-';
  const memberCount = detail.memberCount != null ? detail.memberCount : (detail.member_count != null ? detail.member_count : (members.length || 0));
  const maxCapacity = detail.maxCapacity || detail.max_capacity || 0;
  const permission = detail.permission || '';
  const clientId = detail.clientId || detail.client_id || '';
  const slowModeSeconds = detail.slowModeSeconds ?? detail.slow_mode_seconds ?? 0;
  const memberList = Array.isArray(members) ? members : [];

  const rowClass = 'flex items-center justify-between p-2 rounded-md bg-muted/40 text-xs';

  return (
    <div className="space-y-3">
      {/* Basic Info */}
      <div className="space-y-1.5">
        <div className={rowClass}>
          <span className="text-muted-foreground">{t('channels.field_channel_id')}</span>
          <span className="text-foreground font-mono">{displayId}</span>
        </div>
        <div className={rowClass}>
          <span className="text-muted-foreground">{t('channels.field_display_name')}</span>
          <span className="text-foreground">{displayName}</span>
        </div>
        <div className={rowClass}>
          <span className="text-muted-foreground">{t('channels.field_channel_type')}</span>
          <span className="text-foreground">{scope}</span>
        </div>
        <div className={rowClass}>
          <span className="text-muted-foreground">{t('channels.member_count')}</span>
          <span className="text-foreground">{memberCount}/{maxCapacity}</span>
        </div>
        <div className={rowClass}>
          <span className="text-muted-foreground">{t('channels.field_slow_mode')}</span>
          <span className="text-foreground">{formatSlowMode(t, slowModeSeconds)}</span>
        </div>
        {permission && (
          <div className={rowClass}>
            <span className="text-muted-foreground">{t('channels.permission')}</span>
            <span className="text-foreground font-mono">{permission}</span>
          </div>
        )}
        {clientId && (
          <div className={rowClass}>
            <span className="text-muted-foreground">{t('channels.server')}</span>
            <span className="text-foreground">{clientId}</span>
          </div>
        )}
      </div>

      {/* Members List */}
      <div className="pt-3 border-t border-border">
        <h4 className="text-xs font-medium text-foreground mb-2">
          {t('channels.members')} ({memberList.length})
        </h4>
        {memberList.length === 0 ? (
          <p className="text-xs text-muted-foreground py-4 text-center">{t('channels.no_members')}</p>
        ) : (
          <div className="max-h-48 overflow-y-auto space-y-1">
            {memberList.map((m, i) => {
              const name = m.name || m.username || '';
              const uuid = m.uuid || m.id || '';
              const isOffline = !name;
              return (
                <div key={uuid || i} className="flex items-center gap-2 p-1.5 rounded-md bg-muted/30 text-xs">
                  <div className="flex-1 min-w-0">
                    {isOffline ? (
                      <span className="text-muted-foreground font-mono" title={t('channels.offline')}>
                        {uuid} <span className="text-muted-foreground/60">({t('channels.offline')})</span>
                      </span>
                    ) : (
                      <span className="text-foreground">{name}</span>
                    )}
                  </div>
                  {!isOffline && uuid && uuid !== name && (
                    <span className="text-muted-foreground font-mono text-[10px] truncate">{uuid}</span>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

export default ChannelManagement;
