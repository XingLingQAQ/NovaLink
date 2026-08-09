/**
 * Player Management Component
 * Manage online players and muted players.
 *
 * Restyled to the shadcn/ui reference idiom: Tabs-style switcher, Card table
 * of players with pill platform badges, pill mute/kick Buttons (destructive
 * variant for kick). The honest-disable info banner uses an amber-tinted Card.
 */

import React, { useState } from 'react';
import {
  Search,
  Users,
  UserX,
  MessageSquare,
  Shield,
  Clock,
  Info,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';
import Modal from '../ui/Modal';
import CustomSelect from '../ui/CustomSelect';
import Avatar from '../ui/Avatar';

function PlayerManagement({
  theme,
  mode,
  txtMain: _txtMain,
  txtSec: _txtSec,
  players = [],
  mutedPlayers = [],
  onMutePlayer,
  onUnmutePlayer,
  onKickPlayer,
}) {
  void _txtMain; void _txtSec;
  const { t } = useTranslation();
  const [tab, setTab] = useState('online');
  const [searchQuery, setSearchQuery] = useState('');
  const [serverFilter, setServerFilter] = useState('all');
  const [platformFilter, setPlatformFilter] = useState('all');
  const [showMuteModal, setShowMuteModal] = useState(false);
  const [muteTarget, setMuteTarget] = useState({
    name: '',
    reason: '',
    duration: '1h',
    channel: 'all',
  });

  // Mute/unmute is not exposed to the panel via REST or WS.
  // The App-level handlers show an honest-disable toast.
  const muteActionDisabled = true;

  // Filter players.
  const filteredPlayers = players.filter((p) => {
    const q = (searchQuery || '').toLowerCase();
    const matchesSearch = ((p && p.name) || '').toLowerCase().includes(q);
    const matchesServer = serverFilter === 'all' || (p && p.server) === serverFilter;
    const matchesPlatform = platformFilter === 'all' || (p && p.platform) === platformFilter;
    return matchesSearch && matchesServer && matchesPlatform;
  });

  // Get unique servers.
  const uniqueServers = [...new Set(players.map((p) => p.server))];

  // Handle mute — delegates to App (honest-disable toast, no fake mutation).
  const handleMute = (playerName) => {
    if (muteActionDisabled) {
      onMutePlayer && onMutePlayer({ name: playerName });
      return;
    }
    setMuteTarget({ ...muteTarget, name: playerName });
    setShowMuteModal(true);
  };

  // Confirm mute.
  const confirmMute = () => {
    if (muteTarget.name && onMutePlayer) {
      onMutePlayer(muteTarget);
      setShowMuteModal(false);
      setMuteTarget({ name: '', reason: '', duration: '1h', channel: 'all' });
    }
  };

  // Duration options.
  const durationOptions = [
    { value: '1h', label: t('players.duration_1h') },
    { value: '6h', label: t('players.duration_6h') },
    { value: '24h', label: t('players.duration_24h') },
    { value: '7d', label: t('players.duration_7d') },
    { value: 'permanent', label: t('players.duration_permanent') },
  ];

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('players.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">
            {t('players.subtitle', { online: players.length, muted: mutedPlayers.length })}
          </p>
        </div>

        {/* Tab Switcher (pill) */}
        <div className="inline-flex h-8 items-center gap-1 rounded-full bg-muted p-0.5">
          <button
            onClick={() => setTab('online')}
            className={`inline-flex h-7 items-center justify-center gap-1.5 rounded-full px-3 text-xs font-medium transition-colors ${
              tab === 'online' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            <Users size={14} />
            {t('players.tab_online')}
          </button>
          <button
            onClick={() => setTab('muted')}
            className={`inline-flex h-7 items-center justify-center gap-1.5 rounded-full px-3 text-xs font-medium transition-colors ${
              tab === 'muted' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            <UserX size={14} />
            {t('players.tab_muted')}
            {mutedPlayers.length > 0 && (
              <span className="inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold text-destructive-foreground">
                {mutedPlayers.length}
              </span>
            )}
          </button>
        </div>
      </div>

      {/* Honest-disable info banner */}
      {muteActionDisabled && (
        <Card className="p-3 flex items-start gap-2 border-amber-500/30 bg-amber-500/5">
          <Info size={14} className="text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
          <p className="text-xs text-muted-foreground">{t('players.disable_banner')}</p>
        </Card>
      )}

      {/* Online Players Tab */}
      {tab === 'online' && (
        <Card className="overflow-hidden">
          {/* Filters */}
          <div className="p-3 border-b border-border">
            <div className="flex flex-wrap items-center gap-2">
              {/* Search */}
              <div className="flex items-center gap-2 rounded-md bg-secondary/55 px-2.5 py-1 flex-1 min-w-[200px]">
                <Search size={14} className="text-muted-foreground" />
                <input
                  type="text"
                  placeholder={t('players.search_placeholder')}
                  className="bg-transparent border-none outline-none text-xs flex-1 placeholder:text-muted-foreground text-foreground"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                />
              </div>

              {/* Server Filter */}
              <div className="w-28">
                <CustomSelect
                  theme={theme}
                  mode={mode}
                  options={['all', ...uniqueServers]}
                  defaultValue={serverFilter}
                  onChange={setServerFilter}
                />
              </div>

              {/* Platform Filter */}
              <div className="w-28">
                <CustomSelect
                  theme={theme}
                  mode={mode}
                  options={['all', 'Java', 'Bedrock']}
                  defaultValue={platformFilter}
                  onChange={setPlatformFilter}
                />
              </div>
            </div>
          </div>

          {/* Player Table */}
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="text-xs text-muted-foreground border-b border-border">
                  <th className="p-3 font-medium">{t('players.col_player')}</th>
                  <th className="p-3 font-medium">{t('players.col_server')}</th>
                  <th className="p-3 font-medium">{t('players.col_channel')}</th>
                  <th className="p-3 font-medium">{t('players.col_platform')}</th>
                  <th className="p-3 font-medium text-right">{t('players.col_action')}</th>
                </tr>
              </thead>
              <tbody className="text-xs text-foreground">
                {filteredPlayers.map((player) => (
                  <tr key={player.uuid} className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors">
                    <td className="p-3">
                      <div className="flex items-center gap-3">
                        <Avatar name={player.name} size={28} rounded="rounded-full" />
                        <div>
                          <div className="font-medium">{player.name}</div>
                          {player.muted && (
                            <span className="text-destructive flex items-center gap-1">
                              <UserX size={12} /> {t('players.muted_badge')}
                            </span>
                          )}
                        </div>
                      </div>
                    </td>
                    <td className="p-3 text-muted-foreground">{player.server}</td>
                    <td className="p-3">
                      <span className="rounded-md bg-muted px-1.5 py-0.5 text-xs">#{player.channel}</span>
                    </td>
                    <td className="p-3">
                      <Badge variant={player.platform === 'Java' ? 'success' : 'warning'}>
                        {player.platform}
                      </Badge>
                    </td>
                    <td className="p-3 text-right">
                      <div className="flex items-center justify-end gap-2">
                        {!player.muted && (
                          <Button
                            theme={theme}
                            mode={mode}
                            variant="outline"
                            className="text-xs"
                            onClick={() => handleMute(player.name)}
                            title={muteActionDisabled ? t('players.mute_title_disabled') : t('players.mute_title')}
                          >
                            {t('players.mute')}
                          </Button>
                        )}
                        {onKickPlayer && (
                          <Button
                            theme={theme}
                            mode={mode}
                            variant="destructive"
                            size="icon"
                            onClick={() => onKickPlayer(player.uuid)}
                          >
                            <UserX size={12} />
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Empty State */}
          {filteredPlayers.length === 0 && (
            <div className="p-12 text-center text-muted-foreground">
              <Users size={40} className="mx-auto mb-3 opacity-50" />
              <p className="text-sm">{t('players.not_found')}</p>
            </div>
          )}
        </Card>
      )}

      {/* Muted Players Tab */}
      {tab === 'muted' && (
        <Card className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="text-xs text-muted-foreground border-b border-border">
                  <th className="p-3 font-medium">{t('players.col_player')}</th>
                  <th className="p-3 font-medium">{t('players.col_reason')}</th>
                  <th className="p-3 font-medium">{t('players.col_expire')}</th>
                  <th className="p-3 font-medium">{t('players.col_operator')}</th>
                  <th className="p-3 font-medium text-right">{t('players.col_action')}</th>
                </tr>
              </thead>
              <tbody className="text-xs text-foreground">
                {mutedPlayers.map((mute) => (
                  <tr key={mute.uuid} className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors">
                    <td className="p-3">
                      <div className="flex items-center gap-3">
                        <Avatar name={mute.name} size={28} rounded="rounded-full" />
                        <span className="font-medium">{mute.name}</span>
                      </div>
                    </td>
                    <td className="p-3 text-muted-foreground">{mute.reason}</td>
                    <td className="p-3">
                      <div className="flex items-center gap-1">
                        <Clock size={12} className="text-muted-foreground" />
                        <span className={mute.expireTime === t('players.duration_permanent') ? 'text-destructive' : 'text-muted-foreground'}>
                          {mute.expireTime}
                        </span>
                      </div>
                    </td>
                    <td className="p-3">
                      <div className="flex items-center gap-1 text-muted-foreground">
                        <Shield size={12} />
                        {mute.operator}
                      </div>
                    </td>
                    <td className="p-3 text-right">
                      <Button
                        theme={theme}
                        mode={mode}
                        variant="outline"
                        className="text-xs text-emerald-600 dark:text-emerald-400"
                        onClick={() => onUnmutePlayer && onUnmutePlayer(mute.uuid)}
                        title={muteActionDisabled ? t('players.unmute_title_disabled') : t('players.unmute_title')}
                      >
                        {t('players.unmute')}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Empty State */}
          {mutedPlayers.length === 0 && (
            <div className="p-12 text-center text-muted-foreground">
              <MessageSquare size={40} className="mx-auto mb-3 opacity-50" />
              <p className="text-sm">{t('players.no_muted')}</p>
            </div>
          )}
        </Card>
      )}

      {/* Mute Modal */}
      <Modal
        isOpen={showMuteModal}
        onClose={() => setShowMuteModal(false)}
        title={t('players.mute_modal_title')}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('players.field_player_name')}
            </label>
            <input
              type="text"
              value={muteTarget.name}
              onChange={(e) => setMuteTarget({ ...muteTarget, name: e.target.value })}
              placeholder={t('players.field_player_name_placeholder')}
              className="flex h-8 w-full rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs outline-none focus-visible:ring-1 focus-visible:ring-ring placeholder:text-muted-foreground text-foreground"
            />
          </div>
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('players.field_reason')}
            </label>
            <input
              type="text"
              value={muteTarget.reason}
              onChange={(e) => setMuteTarget({ ...muteTarget, reason: e.target.value })}
              placeholder={t('players.field_reason_placeholder')}
              className="flex h-8 w-full rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs outline-none focus-visible:ring-1 focus-visible:ring-ring placeholder:text-muted-foreground text-foreground"
            />
          </div>
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('players.field_duration')}
            </label>
            <CustomSelect
              theme={theme}
              mode={mode}
              options={durationOptions.map((d) => d.value)}
              defaultValue="1h"
              onChange={(val) => setMuteTarget({ ...muteTarget, duration: val })}
            />
          </div>
          <div className="flex gap-2 mt-6 pt-4 border-t border-border">
            <Button variant="ghost" className="flex-1" theme={theme} mode={mode} onClick={() => setShowMuteModal(false)}>
              {t('common.cancel')}
            </Button>
            <Button variant="default" className="flex-1" theme={theme} mode={mode} onClick={confirmMute}>
              {t('common.confirm')}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}

export default PlayerManagement;
