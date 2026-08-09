/**
 * Channel Management Component
 * Manage chat channels - create, edit, delete channels.
 *
 * Restyled to the shadcn/ui reference idiom: Card list of channels with pill
 * type/permission badges, pill action Buttons, Modal for create/edit.
 * The honest-disable info banner uses an amber-tinted Card.
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
  Info,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';
import Modal from '../ui/Modal';

function ChannelManagement({
  theme,
  mode,
  txtMain: _txtMain,
  txtSec: _txtSec,
  channels = [],
  onCreateChannel,
  onEditChannel,
  onDeleteChannel,
}) {
  void _txtMain; void _txtSec;
  const { t } = useTranslation();
  const [searchQuery, setSearchQuery] = useState('');
  const [filterType, setFilterType] = useState('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [editingChannel, setEditingChannel] = useState(null);
  const [newChannel, setNewChannel] = useState({
    id: '',
    name: '',
    type: 'SERVER',
    permission: '',
    format: '&7[{channel}] {player}: {message}',
  });

  // Channel CRUD is not exposed to the panel via REST or WS.
  // The create/edit/delete handlers in App.jsx show a toast explaining this.
  // We still render the buttons so the user can discover the limitation,
  // but clicking them triggers the honest-disable toast instead of a fake local mutation.
  const channelCrudDisabled = true;

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

  // Handle create channel — delegates to App (honest-disable toast, no fake mutation).
  const handleCreate = () => {
    if (channelCrudDisabled) {
      onCreateChannel && onCreateChannel(null);
      return;
    }
    if (onCreateChannel && newChannel.id && newChannel.name) {
      onCreateChannel(newChannel);
      setShowCreateModal(false);
      setNewChannel({
        id: '',
        name: '',
        type: 'SERVER',
        permission: '',
        format: '&7[{channel}] {player}: {message}',
      });
    }
  };

  // Handle edit channel — delegates to App (honest-disable toast).
  const handleEdit = (channel) => {
    if (channelCrudDisabled) {
      onEditChannel && onEditChannel(channel);
      return;
    }
    setEditingChannel({ ...channel });
    setShowEditModal(true);
  };

  // Handle save edit.
  const handleSaveEdit = () => {
    if (onEditChannel && editingChannel) {
      onEditChannel(editingChannel);
      setShowEditModal(false);
      setEditingChannel(null);
    }
  };

  // Handle delete channel — delegates to App (honest-disable toast).
  const handleDelete = (channelId) => {
    if (channelCrudDisabled) {
      onDeleteChannel && onDeleteChannel(channelId);
      return;
    }
    if (window.confirm(t('channels.delete_confirm'))) {
      onDeleteChannel && onDeleteChannel(channelId);
    }
  };

  // Channel type options.
  const channelTypes = [
    { value: 'GLOBAL', label: t('channels.type_global'), icon: Globe },
    { value: 'SERVER', label: t('channels.type_server'), icon: Hash },
    { value: 'PRIVATE', label: t('channels.type_private'), icon: Lock },
  ];

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('channels.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('channels.subtitle', { count: channels.length })}</p>
        </div>
        <Button
          theme={theme}
          mode={mode}
          variant="default"
          onClick={() => (channelCrudDisabled ? handleCreate() : setShowCreateModal(true))}
          title={channelCrudDisabled ? t('channels.create_title') : t('channels.create')}
        >
          <Plus size={14} /> {t('channels.create')}
        </Button>
      </div>

      {/* Honest-disable info banner */}
      {channelCrudDisabled && (
        <Card className="p-3 flex items-start gap-2 border-amber-500/30 bg-amber-500/5">
          <Info size={14} className="text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
          <p className="text-xs text-muted-foreground">{t('channels.disable_banner')}</p>
        </Card>
      )}

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

          {/* Type Filter */}
          <div className="flex gap-1">
            {['all', 'GLOBAL', 'SERVER', 'PRIVATE'].map((type) => (
              <button
                key={type}
                onClick={() => setFilterType(type)}
                className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                  filterType === type
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:bg-accent hover:text-foreground'
                }`}
              >
                {type === 'all' ? t('channels.filter_all') : type}
              </button>
            ))}
          </div>
        </div>
      </Card>

      {/* Channel Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {filteredChannels.map((channel) => {
          const Icon = getChannelIcon(channel.type);
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
                <Badge variant={getTypeVariant(channel.type)}>{channel.type}</Badge>
              </div>

              {/* Channel Details */}
              <div className="space-y-1.5 mb-3">
                <div className="p-2 rounded-md bg-muted/40 text-xs">
                  <span className="text-muted-foreground">{t('channels.permission')}: </span>
                  <span className="text-foreground">{channel.permission || t('channels.permission_none')}</span>
                </div>
                {channel.format && (
                  <div className="p-2 rounded-md bg-muted/40 text-xs font-mono overflow-hidden">
                    <span className="text-muted-foreground">{t('channels.format')}: </span>
                    <span className="text-foreground truncate block">{channel.format}</span>
                  </div>
                )}
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
              </div>

              {/* Actions */}
              <div className="flex gap-2">
                <Button
                  theme={theme}
                  mode={mode}
                  variant="outline"
                  className="flex-1 text-xs"
                  onClick={() => handleEdit(channel)}
                  title={channelCrudDisabled ? t('channels.edit_title') : t('channels.edit')}
                >
                  <Edit size={12} /> {t('channels.edit')}
                </Button>
                <Button
                  theme={theme}
                  mode={mode}
                  variant="destructive"
                  size="icon"
                  onClick={() => handleDelete(channel.id)}
                  title={channelCrudDisabled ? t('channels.delete_title') : t('common.delete')}
                >
                  <Trash2 size={12} />
                </Button>
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
        onClose={() => setShowCreateModal(false)}
        title={t('channels.create_modal_title')}
        theme={theme}
        mode={mode}
      >
        <ChannelForm
          channel={newChannel}
          onChange={setNewChannel}
          channelTypes={channelTypes}
        />
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button variant="ghost" className="flex-1" theme={theme} mode={mode} onClick={() => setShowCreateModal(false)}>
            {t('common.cancel')}
          </Button>
          <Button variant="default" className="flex-1" theme={theme} mode={mode} onClick={handleCreate}>
            {t('common.create')}
          </Button>
        </div>
      </Modal>

      {/* Edit Channel Modal */}
      <Modal
        isOpen={showEditModal}
        onClose={() => setShowEditModal(false)}
        title={t('channels.edit_modal_title')}
        theme={theme}
        mode={mode}
      >
        {editingChannel && (
          <>
            <ChannelForm
              channel={editingChannel}
              onChange={setEditingChannel}
              channelTypes={channelTypes}
              isEdit
            />
            <div className="flex gap-2 mt-6 pt-4 border-t border-border">
              <Button variant="ghost" className="flex-1" theme={theme} mode={mode} onClick={() => setShowEditModal(false)}>
                {t('common.cancel')}
              </Button>
              <Button variant="default" className="flex-1" theme={theme} mode={mode} onClick={handleSaveEdit}>
                {t('common.save')}
              </Button>
            </div>
          </>
        )}
      </Modal>
    </div>
  );
}

// Channel Form Component
function ChannelForm({ channel, onChange, channelTypes, isEdit = false }) {
  const { t } = useTranslation();
  const inputClass =
    'flex h-8 w-full rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50';

  return (
    <div className="space-y-4">
      {/* Channel ID */}
      <div className="space-y-2">
        <label className="text-xs font-normal leading-none text-muted-foreground">
          {t('channels.field_channel_id')}
        </label>
        <input
          type="text"
          value={channel.id}
          onChange={(e) => onChange({ ...channel, id: e.target.value.toLowerCase().replace(/\s/g, '_') })}
          placeholder="channel_id"
          disabled={isEdit}
          className={`${inputClass} ${isEdit ? 'opacity-50 cursor-not-allowed' : ''}`}
        />
      </div>

      {/* Channel Name */}
      <div className="space-y-2">
        <label className="text-xs font-normal leading-none text-muted-foreground">
          {t('channels.field_display_name')}
        </label>
        <input
          type="text"
          value={channel.name}
          onChange={(e) => onChange({ ...channel, name: e.target.value })}
          placeholder={t('channels.field_display_name_placeholder')}
          className={inputClass}
        />
      </div>

      {/* Channel Type */}
      <div className="space-y-2">
        <label className="text-xs font-normal leading-none text-muted-foreground">
          {t('channels.field_channel_type')}
        </label>
        <div className="grid grid-cols-3 gap-2">
          {channelTypes.map((type) => (
            <button
              key={type.value}
              onClick={() => onChange({ ...channel, type: type.value })}
              className={`flex flex-col items-center gap-1 rounded-md border p-3 text-center transition-colors ${
                channel.type === type.value
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border bg-background text-muted-foreground hover:bg-accent hover:text-foreground'
              }`}
            >
              <type.icon size={16} />
              <span className="text-xs">{type.label}</span>
            </button>
          ))}
        </div>
      </div>

      {/* Permission */}
      <div className="space-y-2">
        <label className="text-xs font-normal leading-none text-muted-foreground">
          {t('channels.field_permission')}
        </label>
        <input
          type="text"
          value={channel.permission}
          onChange={(e) => onChange({ ...channel, permission: e.target.value })}
          placeholder="novachat.channel.example"
          className={inputClass}
        />
      </div>

      {/* Format */}
      <div className="space-y-2">
        <label className="text-xs font-normal leading-none text-muted-foreground">
          {t('channels.field_message_format')}
        </label>
        <input
          type="text"
          value={channel.format}
          onChange={(e) => onChange({ ...channel, format: e.target.value })}
          placeholder="&7[{channel}] {player}: {message}"
          className={`${inputClass} font-mono`}
        />
        <p className="text-xs text-muted-foreground">{t('channels.field_format_vars')}</p>
      </div>
    </div>
  );
}

export default ChannelManagement;
