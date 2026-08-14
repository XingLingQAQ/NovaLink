/**
 * Player Management Component
 * Manage online players and muted players.
 *
 * Restyled to the shadcn/ui reference idiom: Tabs-style switcher, Card table
 * of players with pill platform badges, pill mute/kick Buttons (destructive
 * variant for kick).
 *
 * Batch 2: mute/unmute/kick are now wired to real REST endpoints via the api
 * client. The mute modal collects channelId/durationMs/reason and calls the App
 * handler which performs the REST call + toast + list refresh. The kick action
 * opens a confirm modal (destructive).
 */

import React, { useState } from 'react';
import {
  Search,
  Users,
  UserX,
  MessageSquare,
  Shield,
  ShieldOff,
  Clock,
  Eye,
  Loader2,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';
import Modal from '../ui/Modal';
import CustomSelect from '../ui/CustomSelect';
import Avatar from '../ui/Avatar';
import { api } from '../../services/api';
import { formatRemainingMs } from '../../utils/adapters';
import { can } from '../../lib/permissions';

function PlayerManagement({
  theme,
  mode,
  txtMain: _txtMain,
  txtSec: _txtSec,
  players = [],
  channels = [],
  mutedPlayers = [],
  bannedPlayers = [],
  onMutePlayer,
  onUnmutePlayer,
  onKickPlayer,
  onBanPlayer,
  onUnbanPlayer,
  role,
}) {
  void _txtMain; void _txtSec;
  const { t } = useTranslation();
  const canPunish = can(role, 'punish');
  const [tab, setTab] = useState('online');
  const [searchQuery, setSearchQuery] = useState('');
  const [serverFilter, setServerFilter] = useState('all');
  const [platformFilter, setPlatformFilter] = useState('all');
  const [showMuteModal, setShowMuteModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [playerDetail, setPlayerDetail] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [kickTarget, setKickTarget] = useState(null);
  const [unmuteTarget, setUnmuteTarget] = useState(null);
  const [unbanTarget, setUnbanTarget] = useState(null);

  // Mute form — aligned with backend POST /api/players/{uuid}/mute body.
  // { channelId?, durationMs?, reason? } — durationMs 0 = permanent.
  const emptyMuteTarget = {
    uuid: '',
    name: '',
    reason: '',
    duration: '1h',
    channel: 'all',
  };
  const [muteTarget, setMuteTarget] = useState(emptyMuteTarget);

  // Ban form — aligned with backend POST /api/players/{uuid}/ban body.
  // { channelId?, durationMs, reason } — durationMs 0 = permanent; channelId
  // omitted/empty = global ban.
  const emptyBanTarget = {
    uuid: '',
    name: '',
    reason: '',
    duration: '1h',
    channel: 'all',
  };
  const [banTarget, setBanTarget] = useState(emptyBanTarget);
  const [showBanModal, setShowBanModal] = useState(false);

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

  // Platform filter options derived from the live player data — the backend
  // sends PlatformType enum names (e.g. BUKKIT, VELOCITY, NUKKIT), so a
  // hardcoded list would never match.
  const uniquePlatforms = [...new Set(players.map((p) => p && p.platform).filter(Boolean))];

  // Channel options for the mute modal (all + each channel id).
  const channelOptions = ['all', ...channels.map((c) => c.id).filter(Boolean)];

  // Convert a duration string (from the Select) into a durationMs number for the backend.
  // "permanent" / "0" -> 0 (permanent). "5m" -> 300000, "1h" -> 3600000, etc.
  const durationToMs = (dur) => {
    if (dur === 'permanent' || dur === '0' || !dur) return 0;
    const match = String(dur).match(/^(\d+)\s*(s|m|h|d)$/i);
    if (!match) {
      const n = Number(dur);
      return Number.isNaN(n) ? 0 : n * 1000;
    }
    const num = parseInt(match[1], 10);
    const unit = match[2].toLowerCase();
    const multipliers = { s: 1000, m: 60000, h: 3600000, d: 86400000 };
    return num * (multipliers[unit] || 1000);
  };

  // Handle mute — opens the mute modal with the player pre-filled.
  const handleMute = (player) => {
    setMuteTarget({
      ...emptyMuteTarget,
      uuid: player.uuid,
      name: player.name,
    });
    setShowMuteModal(true);
  };

  // Confirm mute — calls the App handler with the backend-shaped payload.
  const confirmMute = async () => {
    if (!muteTarget.uuid || !onMutePlayer) return;
    const payload = {
      uuid: muteTarget.uuid,
      name: muteTarget.name,
      durationMs: durationToMs(muteTarget.duration),
    };
    if (muteTarget.channel && muteTarget.channel !== 'all') {
      payload.channelId = muteTarget.channel;
    }
    if (muteTarget.reason) payload.reason = muteTarget.reason;
    setSubmitting(true);
    try {
      await onMutePlayer(payload);
      setShowMuteModal(false);
      setMuteTarget(emptyMuteTarget);
    } catch {
      // toast shown by App handler
    } finally {
      setSubmitting(false);
    }
  };

  // Handle unmute — opens a confirm modal.
  const handleUnmute = (mute) => {
    setUnmuteTarget(mute);
  };

  const confirmUnmute = async () => {
    if (!unmuteTarget || !onUnmutePlayer) return;
    const payload = { uuid: unmuteTarget.uuid, name: unmuteTarget.name };
    if (unmuteTarget.channelId) payload.channelId = unmuteTarget.channelId;
    setSubmitting(true);
    try {
      await onUnmutePlayer(payload);
      setUnmuteTarget(null);
    } catch {
      // toast shown by App handler
    } finally {
      setSubmitting(false);
    }
  };

  // Handle kick — opens a confirm modal (destructive).
  const handleKick = (player) => {
    setKickTarget(player);
  };

  const confirmKick = async () => {
    if (!kickTarget || !onKickPlayer) return;
    const payload = { uuid: kickTarget.uuid, name: kickTarget.name };
    if (kickTarget.channel) payload.channelId = kickTarget.channel;
    setSubmitting(true);
    try {
      await onKickPlayer(payload);
      setKickTarget(null);
    } catch {
      // toast shown by App handler
    } finally {
      setSubmitting(false);
    }
  };

  // Handle ban — opens the ban modal with the player pre-filled.
  const handleBan = (player) => {
    setBanTarget({
      ...emptyBanTarget,
      uuid: player.uuid,
      name: player.name,
    });
    setShowBanModal(true);
  };

  // Confirm ban — calls the App handler with the backend-shaped payload.
  const confirmBan = async () => {
    if (!banTarget.uuid || !onBanPlayer) return;
    const payload = {
      uuid: banTarget.uuid,
      name: banTarget.name,
      durationMs: durationToMs(banTarget.duration),
      reason: banTarget.reason || '',
    };
    if (banTarget.channel && banTarget.channel !== 'all') {
      payload.channelId = banTarget.channel;
    }
    setSubmitting(true);
    try {
      await onBanPlayer(payload);
      setShowBanModal(false);
      setBanTarget(emptyBanTarget);
    } catch {
      // toast shown by App handler
    } finally {
      setSubmitting(false);
    }
  };

  // Handle unban — opens a confirm modal.
  const handleUnban = (ban) => {
    setUnbanTarget(ban);
  };

  const confirmUnban = async () => {
    if (!unbanTarget || !onUnbanPlayer) return;
    const payload = { uuid: unbanTarget.uuid, name: unbanTarget.name };
    if (unbanTarget.channelId) payload.channelId = unbanTarget.channelId;
    setSubmitting(true);
    try {
      await onUnbanPlayer(payload);
      setUnbanTarget(null);
    } catch {
      // toast shown by App handler
    } finally {
      setSubmitting(false);
    }
  };

  // Format a ban's expiry for display.
  const formatBanExpiry = (ban) => {
    if (!ban) return '-';
    if (ban.permanent) return t('players.ban_permanent');
    if (!ban.expireTime) return '-';
    try {
      const num = typeof ban.expireTime === 'number' ? ban.expireTime : Number(ban.expireTime);
      if (Number.isNaN(num)) return String(ban.expireTime);
      return new Date(num).toLocaleString();
    } catch {
      return String(ban.expireTime);
    }
  };

  // Format a ban's created-at timestamp for display.
  const formatBanCreated = (ban) => {
    if (!ban || !ban.createdAt) return '-';
    try {
      const num = typeof ban.createdAt === 'number' ? ban.createdAt : Number(ban.createdAt);
      if (Number.isNaN(num)) return String(ban.createdAt);
      return new Date(num).toLocaleString();
    } catch {
      return String(ban.createdAt);
    }
  };

  // Handle view player details — fetches full player info via REST.
  const handleViewDetails = async (player) => {
    setPlayerDetail(null);
    setDetailLoading(true);
    setShowDetailModal(true);
    try {
      const detail = await api.getPlayer(player.uuid).catch((e) => {
        console.warn('[player detail] getPlayer failed:', e);
        return null;
      });
      if (detail) setPlayerDetail(detail);
      else setPlayerDetail(player);
    } catch (err) {
      console.error('[player detail] failed:', err);
      setPlayerDetail(player);
    } finally {
      setDetailLoading(false);
    }
  };

  // Duration options.
  const durationOptions = [
    { value: '5m', label: t('players.duration_5m') },
    { value: '30m', label: t('players.duration_30m') },
    { value: '1h', label: t('players.duration_1h') },
    { value: '6h', label: t('players.duration_6h') },
    { value: '24h', label: t('players.duration_24h') },
    { value: '7d', label: t('players.duration_7d') },
    { value: 'permanent', label: t('players.duration_permanent') },
  ];

  // Format a mute's expiry for display.
  const formatExpiry = (mute) => {
    if (!mute) return '-';
    if (mute.permanent) return t('players.expire_permanent');
    if (!mute.expireTime) return '-';
    try {
      const num = typeof mute.expireTime === 'number' ? mute.expireTime : Number(mute.expireTime);
      if (Number.isNaN(num)) return String(mute.expireTime);
      return new Date(num).toLocaleString();
    } catch {
      return String(mute.expireTime);
    }
  };

  const inputClass =
    'flex h-8 w-full rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs outline-none focus-visible:ring-1 focus-visible:ring-ring placeholder:text-muted-foreground text-foreground';

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('players.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">
            {t('players.subtitle', { online: players.length, muted: mutedPlayers.length, banned: bannedPlayers.length })}
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
          <button
            onClick={() => setTab('banned')}
            className={`inline-flex h-7 items-center justify-center gap-1.5 rounded-full px-3 text-xs font-medium transition-colors ${
              tab === 'banned' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            <ShieldOff size={14} />
            {t('players.tab_banned')}
            {bannedPlayers.length > 0 && (
              <span className="inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold text-destructive-foreground">
                {bannedPlayers.length}
              </span>
            )}
          </button>
        </div>
      </div>

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
                  options={['all', ...uniquePlatforms]}
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
                        <Button
                          theme={theme}
                          mode={mode}
                          variant="outline"
                          size="icon"
                          onClick={() => handleViewDetails(player)}
                          title={t('players.details')}
                          aria-label={t('players.details')}
                        >
                          <Eye size={12} />
                        </Button>
                        {canPunish && !player.muted && (
                          <Button
                            theme={theme}
                            mode={mode}
                            variant="outline"
                            className="text-xs"
                            onClick={() => handleMute(player)}
                            title={t('players.mute_title')}
                          >
                            {t('players.mute')}
                          </Button>
                        )}
                        {canPunish && onBanPlayer && (
                          <Button
                            theme={theme}
                            mode={mode}
                            variant="destructive"
                            className="text-xs"
                            onClick={() => handleBan(player)}
                            title={t('players.ban_title')}
                          >
                            {t('players.ban')}
                          </Button>
                        )}
                        {canPunish && onKickPlayer && (
                          <Button
                            theme={theme}
                            mode={mode}
                            variant="destructive"
                            size="icon"
                            onClick={() => handleKick(player)}
                            title={t('players.kick_title')}
                            aria-label={t('players.kick_title')}
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
                  <th className="p-3 font-medium">{t('players.field_mute_channel')}</th>
                  <th className="p-3 font-medium">{t('players.col_reason')}</th>
                  <th className="p-3 font-medium">{t('players.col_expire')}</th>
                  <th className="p-3 font-medium text-right">{t('players.col_action')}</th>
                </tr>
              </thead>
              <tbody className="text-xs text-foreground">
                {mutedPlayers.map((mute) => (
                  <tr key={(mute.uuid || '') + '|' + (mute.channelId || '')} className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors">
                    <td className="p-3">
                      <div className="flex items-center gap-3">
                        <Avatar name={mute.name || mute.uuid} size={28} rounded="rounded-full" />
                        <div className="min-w-0">
                          <div className="font-medium">{mute.name || mute.uuid}</div>
                          {mute.uuid && mute.uuid !== mute.name && (
                            <div className="text-[10px] text-muted-foreground font-mono truncate">{mute.uuid}</div>
                          )}
                        </div>
                      </div>
                    </td>
                    <td className="p-3">
                      {mute.channelId ? (
                        <span className="rounded-md bg-muted px-1.5 py-0.5 text-xs">#{mute.channelId}</span>
                      ) : (
                        <Badge variant="info">{t('players.field_channel_placeholder')}</Badge>
                      )}
                    </td>
                    <td className="p-3 text-muted-foreground">{mute.reason || '-'}</td>
                    <td className="p-3">
                      <div className="flex items-center gap-1">
                        <Clock size={12} className="text-muted-foreground" />
                        <span className={mute.permanent ? 'text-destructive' : 'text-muted-foreground'}>
                          {formatExpiry(mute)}
                        </span>
                      </div>
                    </td>
                    <td className="p-3 text-right">
                      {canPunish && (
                        <Button
                          theme={theme}
                          mode={mode}
                          variant="outline"
                          className="text-xs text-emerald-600 dark:text-emerald-400"
                          onClick={() => handleUnmute(mute)}
                          title={t('players.unmute_title')}
                        >
                          {t('players.unmute')}
                        </Button>
                      )}
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

      {/* Banned Players Tab */}
      {tab === 'banned' && (
        <Card className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="text-xs text-muted-foreground border-b border-border">
                  <th className="p-3 font-medium">{t('players.col_player')}</th>
                  <th className="p-3 font-medium">{t('players.field_mute_channel')}</th>
                  <th className="p-3 font-medium">{t('players.col_reason')}</th>
                  <th className="p-3 font-medium">{t('players.col_expire_time')}</th>
                  <th className="p-3 font-medium">{t('players.col_remaining')}</th>
                  <th className="p-3 font-medium">{t('players.col_ban_created')}</th>
                  <th className="p-3 font-medium text-right">{t('players.col_action')}</th>
                </tr>
              </thead>
              <tbody className="text-xs text-foreground">
                {bannedPlayers.map((ban) => (
                  <tr key={(ban.uuid || '') + '|' + (ban.channelId || '') + '|' + (ban.createdAt || '')} className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors">
                    <td className="p-3">
                      <div className="flex items-center gap-3">
                        <Avatar name={ban.name || ban.uuid} size={28} rounded="rounded-full" />
                        <div className="min-w-0">
                          <div className="font-medium">{ban.name || ban.uuid}</div>
                          {ban.uuid && ban.uuid !== ban.name && (
                            <div className="text-[10px] text-muted-foreground font-mono truncate">{ban.uuid}</div>
                          )}
                        </div>
                      </div>
                    </td>
                    <td className="p-3">
                      {ban.channelId ? (
                        <span className="rounded-md bg-muted px-1.5 py-0.5 text-xs">#{ban.channelId}</span>
                      ) : (
                        <Badge variant="info">{t('players.ban_global')}</Badge>
                      )}
                    </td>
                    <td className="p-3 text-muted-foreground">{ban.reason || '-'}</td>
                    <td className="p-3">
                      <div className="flex items-center gap-1">
                        <Clock size={12} className="text-muted-foreground" />
                        <span className={ban.permanent ? 'text-destructive' : 'text-muted-foreground'}>
                          {formatBanExpiry(ban)}
                        </span>
                      </div>
                    </td>
                    <td className="p-3">
                      <span className={ban.permanent ? 'text-destructive' : (ban.remainingMs <= 0 ? 'text-muted-foreground' : 'text-foreground')}>
                        {formatRemainingMs(ban.remainingMs, { permanentLabel: t('players.ban_permanent'), expiredLabel: t('players.ban_expired') })}
                      </span>
                    </td>
                    <td className="p-3 text-muted-foreground">{formatBanCreated(ban)}</td>
                    <td className="p-3 text-right">
                      {canPunish && (
                        <Button
                          theme={theme}
                          mode={mode}
                          variant="outline"
                          className="text-xs text-emerald-600 dark:text-emerald-400"
                          onClick={() => handleUnban(ban)}
                          title={t('players.unban_title')}
                        >
                          {t('players.unban')}
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Empty State */}
          {bannedPlayers.length === 0 && (
            <div className="p-12 text-center text-muted-foreground">
              <ShieldOff size={40} className="mx-auto mb-3 opacity-50" />
              <p className="text-sm">{t('players.no_banned')}</p>
            </div>
          )}
        </Card>
      )}

      {/* Mute Modal */}
      <Modal
        isOpen={showMuteModal}
        onClose={() => !submitting && setShowMuteModal(false)}
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
              disabled
              className={`${inputClass} opacity-50 cursor-not-allowed`}
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
              className={inputClass}
            />
          </div>
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('players.field_duration')}
            </label>
            <CustomSelect
              theme={theme}
              mode={mode}
              options={durationOptions}
              defaultValue="1h"
              onChange={(val) => setMuteTarget({ ...muteTarget, duration: val })}
            />
          </div>
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('players.field_channel')}
            </label>
            <CustomSelect
              theme={theme}
              mode={mode}
              options={channelOptions}
              defaultValue="all"
              onChange={(val) => setMuteTarget({ ...muteTarget, channel: val })}
            />
            <p className="text-[11px] text-muted-foreground">{t('players.field_channel_placeholder')}</p>
          </div>
          <div className="flex gap-2 mt-6 pt-4 border-t border-border">
            <Button
              variant="ghost"
              className="flex-1"
              theme={theme}
              mode={mode}
              onClick={() => setShowMuteModal(false)}
              disabled={submitting}
            >
              {t('common.cancel')}
            </Button>
            <Button
              variant="default"
              className="flex-1"
              theme={theme}
              mode={mode}
              onClick={confirmMute}
              disabled={submitting}
            >
              {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
              {t('common.confirm')}
            </Button>
          </div>
        </div>
      </Modal>

      {/* Unmute Confirm Modal */}
      <Modal
        isOpen={!!unmuteTarget}
        onClose={() => !submitting && setUnmuteTarget(null)}
        title={t('players.unmute_title')}
        theme={theme}
        mode={mode}
      >
        <p className="text-xs text-muted-foreground">
          {t('players.toast_unmute_success', { name: (unmuteTarget && (unmuteTarget.name || unmuteTarget.uuid)) || '' })}
        </p>
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button
            variant="ghost"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={() => setUnmuteTarget(null)}
            disabled={submitting}
          >
            {t('common.cancel')}
          </Button>
          <Button
            variant="default"
            className="flex-1 text-emerald-600 dark:text-emerald-400"
            theme={theme}
            mode={mode}
            onClick={confirmUnmute}
            disabled={submitting}
          >
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('players.unmute')}
          </Button>
        </div>
      </Modal>

      {/* Kick Confirm Modal */}
      <Modal
        isOpen={!!kickTarget}
        onClose={() => !submitting && setKickTarget(null)}
        title={t('players.kick_modal_title')}
        theme={theme}
        mode={mode}
      >
        <p className="text-xs text-muted-foreground">
          {t('players.kick_confirm', { name: (kickTarget && kickTarget.name) || '' })}
        </p>
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button
            variant="ghost"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={() => setKickTarget(null)}
            disabled={submitting}
          >
            {t('common.cancel')}
          </Button>
          <Button
            variant="destructive"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={confirmKick}
            disabled={submitting}
          >
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('players.kick')}
          </Button>
        </div>
      </Modal>

      {/* Ban Modal */}
      <Modal
        isOpen={showBanModal}
        onClose={() => !submitting && setShowBanModal(false)}
        title={t('players.ban_modal_title')}
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
              value={banTarget.name}
              disabled
              className={`${inputClass} opacity-50 cursor-not-allowed`}
            />
          </div>
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('players.field_ban_reason')}
            </label>
            <input
              type="text"
              value={banTarget.reason}
              onChange={(e) => setBanTarget({ ...banTarget, reason: e.target.value })}
              placeholder={t('players.field_ban_reason_placeholder')}
              className={inputClass}
            />
          </div>
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('players.field_duration')}
            </label>
            <CustomSelect
              theme={theme}
              mode={mode}
              options={durationOptions}
              defaultValue="1h"
              onChange={(val) => setBanTarget({ ...banTarget, duration: val })}
            />
          </div>
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('players.field_ban_channel')}
            </label>
            <CustomSelect
              theme={theme}
              mode={mode}
              options={channelOptions}
              defaultValue="all"
              onChange={(val) => setBanTarget({ ...banTarget, channel: val })}
            />
            <p className="text-[11px] text-muted-foreground">{t('players.field_channel_placeholder')}</p>
          </div>
          <div className="flex gap-2 mt-6 pt-4 border-t border-border">
            <Button
              variant="ghost"
              className="flex-1"
              theme={theme}
              mode={mode}
              onClick={() => setShowBanModal(false)}
              disabled={submitting}
            >
              {t('common.cancel')}
            </Button>
            <Button
              variant="destructive"
              className="flex-1"
              theme={theme}
              mode={mode}
              onClick={confirmBan}
              disabled={submitting}
            >
              {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
              {t('players.ban')}
            </Button>
          </div>
        </div>
      </Modal>

      {/* Unban Confirm Modal */}
      <Modal
        isOpen={!!unbanTarget}
        onClose={() => !submitting && setUnbanTarget(null)}
        title={t('players.unban_modal_title')}
        theme={theme}
        mode={mode}
      >
        <p className="text-xs text-muted-foreground">
          {t('players.unban_confirm', { name: (unbanTarget && (unbanTarget.name || unbanTarget.uuid)) || '' })}
        </p>
        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button
            variant="ghost"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={() => setUnbanTarget(null)}
            disabled={submitting}
          >
            {t('common.cancel')}
          </Button>
          <Button
            variant="default"
            className="flex-1 text-emerald-600 dark:text-emerald-400"
            theme={theme}
            mode={mode}
            onClick={confirmUnban}
            disabled={submitting}
          >
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('players.unban')}
          </Button>
        </div>
      </Modal>

      {/* Player Details Modal */}
      <Modal
        isOpen={showDetailModal}
        onClose={() => setShowDetailModal(false)}
        title={t('players.details_modal_title')}
        theme={theme}
        mode={mode}
      >
        {detailLoading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 size={20} className="animate-spin text-muted-foreground" />
          </div>
        ) : playerDetail ? (
          <PlayerDetails player={playerDetail} />
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

// Player Details Component
function PlayerDetails({ player }) {
  const { t } = useTranslation();
  const detail = player || {};
  const name = detail.name || detail.uuid || '-';
  const uuid = detail.uuid || '-';
  const clientId = detail.clientId || detail.client_id || detail.server || '-';
  const currentWorld = detail.currentWorld || detail.current_world || '-';
  const activeChannel = detail.activeChannel || detail.active_channel || detail.channel || '-';
  const joinedChannels = Array.isArray(detail.joinedChannels)
    ? detail.joinedChannels
    : (Array.isArray(detail.joined_channels) ? detail.joined_channels : []);

  const rowClass = 'flex items-center justify-between p-2 rounded-md bg-muted/40 text-xs';

  return (
    <div className="space-y-3">
      {/* Player Header */}
      <div className="flex items-center gap-3 pb-3 border-b border-border">
        <Avatar name={name} size={40} rounded="rounded-full" />
        <div className="min-w-0">
          <h4 className="text-sm font-medium text-foreground">{name}</h4>
          <p className="text-xs text-muted-foreground font-mono truncate">{uuid}</p>
        </div>
      </div>

      {/* Detail Rows */}
      <div className="space-y-1.5">
        <div className={rowClass}>
          <span className="text-muted-foreground">{t('players.current_world')}</span>
          <span className="text-foreground">{currentWorld}</span>
        </div>
        <div className={rowClass}>
          <span className="text-muted-foreground">{t('players.active_channel')}</span>
          <span className="text-foreground">{activeChannel}</span>
        </div>
        <div className={rowClass}>
          <span className="text-muted-foreground">{t('players.client_id')}</span>
          <span className="text-foreground">{clientId}</span>
        </div>
      </div>

      {/* Joined Channels */}
      <div className="pt-3 border-t border-border">
        <h4 className="text-xs font-medium text-foreground mb-2">
          {t('players.joined_channels')} ({joinedChannels.length})
        </h4>
        {joinedChannels.length === 0 ? (
          <p className="text-xs text-muted-foreground py-2 text-center">{t('players.no_joined_channels')}</p>
        ) : (
          <div className="flex flex-wrap gap-1.5">
            {joinedChannels.map((ch, i) => (
              <Badge key={i} variant="secondary">{ch}</Badge>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export default PlayerManagement;
