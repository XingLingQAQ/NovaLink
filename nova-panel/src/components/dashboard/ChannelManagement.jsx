/**
 * Channel Management Component
 * Manage chat channels - create, edit, delete channels
 * 
 * Requirements: 24.3 - Channel management functionality
 */

import React, { useState } from 'react';
import {
  Plus,
  Edit,
  Trash2,
  Globe,
  Hash,
  Lock,
  Shield,
  Users,
  Settings,
  Search,
  Info
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

function ChannelManagement({
  theme,
  mode,
  txtMain,
  txtSec,
  channels = [],
  onCreateChannel,
  onEditChannel,
  onDeleteChannel
}) {
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
    format: '&7[{channel}] {player}: {message}'
  });

  // Channel CRUD is not exposed to the panel via REST or WS.
  // The create/edit/delete handlers in App.jsx show a toast explaining this.
  // We still render the buttons so the user can discover the limitation,
  // but clicking them triggers the honest-disable toast instead of a fake local mutation.
  const channelCrudDisabled = true;

  // Filter channels
  const filteredChannels = channels.filter(c => {
    const matchesSearch = c.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
                          c.id.toLowerCase().includes(searchQuery.toLowerCase());
    const matchesType = filterType === 'all' || c.type === filterType;
    return matchesSearch && matchesType;
  });

  // Get channel icon
  const getChannelIcon = (type) => {
    switch(type) {
      case 'GLOBAL': return Globe;
      case 'LOCAL': 
      case 'SERVER': return Hash;
      case 'PRIVATE': return Lock;
      default: return Hash;
    }
  };

  // Get channel type color
  const getTypeColor = (type) => {
    switch(type) {
      case 'GLOBAL': return 'bg-blue-500/20 text-blue-400';
      case 'LOCAL':
      case 'SERVER': return 'bg-slate-500/20 text-slate-400';
      case 'PRIVATE': return 'bg-amber-500/20 text-amber-400';
      default: return 'bg-slate-500/20 text-slate-400';
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
        format: '&7[{channel}] {player}: {message}'
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

  // Handle save edit
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

  // Channel type options
  const channelTypes = [
    { value: 'GLOBAL', label: t('channels.type_global'), icon: Globe },
    { value: 'SERVER', label: t('channels.type_server'), icon: Hash },
    { value: 'PRIVATE', label: t('channels.type_private'), icon: Lock }
  ];

  return (
    <div className="space-y-4 animate-in fade-in duration-500">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className={`text-2xl font-bold ${txtMain}`}>{t('channels.title')}</h2>
          <p className={`text-sm ${txtSec} mt-1`}>{t('channels.subtitle', { count: channels.length })}</p>
        </div>
        <Button
          theme={theme}
          mode={mode}
          variant="primary"
          onClick={() => (channelCrudDisabled ? handleCreate() : setShowCreateModal(true))}
          title={channelCrudDisabled ? t('channels.create_title') : t('channels.create')}
        >
          <Plus size={16} /> {t('channels.create')}
        </Button>
      </div>

      {/* Honest-disable info banner */}
      {channelCrudDisabled && (
        <Card theme={theme} mode={mode} className="p-3 flex items-start gap-2 border border-amber-500/20">
          <Info size={16} className="text-amber-400 shrink-0 mt-0.5" />
          <p className={`text-xs ${txtSec}`}>
            {t('channels.disable_banner')}
          </p>
        </Card>
      )}

      {/* Filters */}
      <Card theme={theme} mode={mode} className="p-4">
        <div className="flex flex-wrap items-center gap-4">
          {/* Search */}
          <div className={`flex items-center gap-2 px-3 py-2 rounded-lg flex-1 min-w-[200px] ${
            theme === 'clean' 
              ? (mode === 'dark' ? 'bg-slate-700' : 'bg-slate-100') 
              : 'bg-white/10'
          }`}>
            <Search size={16} className={txtSec} />
            <input 
              type="text" 
              placeholder={t('channels.search_placeholder')}
              className="bg-transparent border-none outline-none text-sm flex-1" 
              style={{ color: mode === 'dark' ? 'white' : 'black' }}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>

          {/* Type Filter */}
          <div className="flex gap-2">
            {['all', 'GLOBAL', 'SERVER', 'PRIVATE'].map(type => (
              <button
                key={type}
                onClick={() => setFilterType(type)}
                className={`px-3 py-1.5 rounded-lg text-sm transition-all ${
                  filterType === type
                    ? (theme === 'clean' ? 'bg-sky-500 text-white' : 'bg-white/20 text-white')
                    : (mode === 'dark' ? 'text-slate-400 hover:bg-white/10' : 'text-slate-500 hover:bg-slate-100')
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
            <Card key={channel.id} theme={theme} mode={mode} className="p-5">
              <div className="flex items-start justify-between mb-4">
                <div className="flex items-center gap-3">
                  <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${
                    theme === 'clean' ? 'bg-sky-50 text-sky-600' : 'bg-sky-500/20 text-sky-400'
                  }`}>
                    <Icon size={20} />
                  </div>
                  <div>
                    <h3 className={`font-semibold ${txtMain}`}>{channel.name}</h3>
                    <p className={`text-xs ${txtSec}`}>#{channel.id}</p>
                  </div>
                </div>
                <span className={`px-2 py-1 rounded-full text-xs font-medium ${getTypeColor(channel.type)}`}>
                  {channel.type}
                </span>
              </div>

              {/* Channel Details */}
              <div className="space-y-2 mb-4">
                <div className={`p-2 rounded-lg text-xs ${
                  theme === 'clean'
                    ? (mode === 'dark' ? 'bg-slate-700/50' : 'bg-slate-50')
                    : 'bg-white/5'
                }`}>
                  <span className={txtSec}>{t('channels.permission')}: </span>
                  <span className={txtMain}>{channel.permission || t('channels.permission_none')}</span>
                </div>
                {channel.format && (
                  <div className={`p-2 rounded-lg text-xs font-mono overflow-hidden ${
                    theme === 'clean'
                      ? (mode === 'dark' ? 'bg-slate-700/50' : 'bg-slate-50')
                      : 'bg-white/5'
                  }`}>
                    <span className={txtSec}>{t('channels.format')}: </span>
                    <span className={`${txtMain} truncate block`}>{channel.format}</span>
                  </div>
                )}
                <div className={`flex items-center gap-3 p-2 rounded-lg text-xs ${
                  theme === 'clean'
                    ? (mode === 'dark' ? 'bg-slate-700/50' : 'bg-slate-50')
                    : 'bg-white/5'
                }`}>
                  <span className={txtSec}>{t('channels.members')}: </span>
                  <span className={txtMain}>{channel.memberCount || 0}/{channel.maxCapacity || 0}</span>
                  {channel.clientId && (
                    <>
                      <span className={txtSec}>· {t('channels.server')}: </span>
                      <span className={txtMain}>{channel.clientId}</span>
                    </>
                  )}
                </div>
              </div>

              {/* Actions */}
              <div className="flex gap-2">
                <Button
                  theme={theme}
                  mode={mode}
                  variant="ghost"
                  className="flex-1 text-sm"
                  onClick={() => handleEdit(channel)}
                  title={channelCrudDisabled ? t('channels.edit_title') : t('channels.edit')}
                >
                  <Edit size={14} /> {t('channels.edit')}
                </Button>
                <Button
                  theme={theme}
                  mode={mode}
                  variant="danger"
                  className="text-sm"
                  onClick={() => handleDelete(channel.id)}
                  title={channelCrudDisabled ? t('channels.delete_title') : t('common.delete')}
                >
                  <Trash2 size={14} />
                </Button>
              </div>
            </Card>
          );
        })}
      </div>

      {/* Empty State */}
      {filteredChannels.length === 0 && (
        <Card theme={theme} mode={mode} className="p-12 text-center">
          <Hash size={48} className={`mx-auto mb-4 ${txtSec}`} />
          <p className={txtMain}>{t('channels.not_found')}</p>
          <p className={`text-sm ${txtSec} mt-1`}>{t('channels.not_found_hint')}</p>
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
          theme={theme}
          mode={mode}
          txtSec={txtSec}
          channel={newChannel}
          onChange={setNewChannel}
          channelTypes={channelTypes}
        />
        <div className="flex gap-3 mt-6 pt-4 border-t border-gray-200/10">
          <Button variant="ghost" className="flex-1" theme={theme} mode={mode} onClick={() => setShowCreateModal(false)}>
            {t('common.cancel')}
          </Button>
          <Button variant="primary" className="flex-1" theme={theme} mode={mode} onClick={handleCreate}>
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
              theme={theme}
              mode={mode}
              txtSec={txtSec}
              channel={editingChannel}
              onChange={setEditingChannel}
              channelTypes={channelTypes}
              isEdit
            />
            <div className="flex gap-3 mt-6 pt-4 border-t border-gray-200/10">
              <Button variant="ghost" className="flex-1" theme={theme} mode={mode} onClick={() => setShowEditModal(false)}>
                {t('common.cancel')}
              </Button>
              <Button variant="primary" className="flex-1" theme={theme} mode={mode} onClick={handleSaveEdit}>
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
function ChannelForm({ theme, mode, txtSec, channel, onChange, channelTypes, isEdit = false }) {
  const { t } = useTranslation();
  const inputClass = `w-full px-4 py-2.5 rounded-xl border outline-none focus:ring-2 transition-all ${
    theme === 'clean'
      ? (mode === 'dark' ? 'bg-slate-700 border-slate-600 focus:ring-sky-500 text-white' : 'bg-white border-slate-200 focus:ring-sky-500 text-slate-900')
      : 'bg-white/10 border-white/20 focus:ring-white/50 text-white placeholder:text-white/30'
  }`;

  return (
    <div className="space-y-4">
      {/* Channel ID */}
      <div>
        <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>
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
      <div>
        <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>
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
      <div>
        <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>
          {t('channels.field_channel_type')}
        </label>
        <div className="grid grid-cols-3 gap-2">
          {channelTypes.map(type => (
            <button
              key={type.value}
              onClick={() => onChange({ ...channel, type: type.value })}
              className={`p-3 rounded-xl border text-center transition-all ${
                channel.type === type.value
                  ? (theme === 'clean' ? 'border-sky-500 bg-sky-50 text-sky-600' : 'border-white/50 bg-white/20 text-white')
                  : (theme === 'clean' 
                      ? (mode === 'dark' ? 'border-slate-600 text-slate-400 hover:border-slate-500' : 'border-slate-200 text-slate-500 hover:border-slate-300')
                      : 'border-white/20 text-white/50 hover:border-white/40')
              }`}
            >
              <type.icon size={20} className="mx-auto mb-1" />
              <span className="text-xs">{type.label}</span>
            </button>
          ))}
        </div>
      </div>

      {/* Permission */}
      <div>
        <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>
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
      <div>
        <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>
          {t('channels.field_message_format')}
        </label>
        <input
          type="text"
          value={channel.format}
          onChange={(e) => onChange({ ...channel, format: e.target.value })}
          placeholder="&7[{channel}] {player}: {message}"
          className={`${inputClass} font-mono text-sm`}
        />
        <p className={`text-xs ${txtSec} mt-1`}>
          {t('channels.field_format_vars')}
        </p>
      </div>
    </div>
  );
}

export default ChannelManagement;
