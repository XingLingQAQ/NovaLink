/**
 * Dashboard data layer — REST fetchers, mutation handlers and the state they
 * populate. Extracted from App.jsx unchanged in behavior, except that partial
 * failures during the initial fetch now surface a visible error toast instead
 * of only console.warn.
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../services/api';
import {
  adaptChannel,
  adaptPlayer,
  adaptNotification,
  adaptMute,
  adaptBan,
} from '../utils/adapters';
import {
  adaptSettingsResponse,
  buildSettingsUpdateBody,
  createInitialSettings,
  isValidSettingsValue,
} from '../lib/settingsContract';

export function useDashboardData({ addToast, currentUser }) {
  const { t } = useTranslation();
  // Live ref so stable callbacks (fetchAllData/fetchSettings) emit locale-aware
  // text without being recreated on every language change.
  const tRef = useRef(t);
  useEffect(() => { tRef.current = t; }, [t]);

  // Data state — initialized empty, populated from real REST + WS.
  const [statusData, setStatusData] = useState(null);
  const [channels, setChannels] = useState([]);
  const [players, setPlayers] = useState([]);
  const [webhooks, setWebhooks] = useState([]);
  const [webhooksLoading, setWebhooksLoading] = useState(false);
  const [mutedPlayers, setMutedPlayers] = useState([]);
  const [bannedPlayers, setBannedPlayers] = useState([]);
  const [notifications, setNotifications] = useState([]);
  // Unread count sourced from GET /api/notifications for the bell badge.
  const [apiUnreadCount, setApiUnreadCount] = useState(0);

  // Loading / error state for initial fetch.
  const [initialLoading, setInitialLoading] = useState(true);
  const [fetchError, setFetchError] = useState(null);

  // Settings — persisted to the backend via GET/PUT /api/settings. The two
  // newer fields remain unsupported until GET explicitly returns valid values.
  const [settings, setSettings] = useState(createInitialSettings);
  const settingsRef = useRef(settings);
  const [settingsLoading, setSettingsLoading] = useState(false);

  // Fetch backend settings (GET /api/settings) and map to UI keys.
  const fetchSettings = useCallback(async () => {
    setSettingsLoading(true);
    try {
      const res = await api.getSettings();
      if (res) {
        const adapted = adaptSettingsResponse(res);
        settingsRef.current = adapted;
        setSettings(adapted);
      }
    } catch (err) {
      console.warn('[fetchSettings] failed:', err);
      addToast(tRef.current('common.settings_load_failed', { error: err.message }), 'error');
    } finally {
      setSettingsLoading(false);
    }
  }, [addToast]);

  // --- Initial data fetch on mount (after auth) ---
  const fetchAllData = useCallback(async () => {
    setFetchError(null);
    // Per-endpoint failures are collected and surfaced as one visible toast.
    const failedAreas = [];
    try {
      const [statusRes, channelsRes, playersRes, mutesRes, bansRes] = await Promise.all([
        api.status().catch((e) => { console.warn('[fetch] /api/status failed:', e); failedAreas.push('area_status'); return null; }),
        api.getChannels().catch((e) => { console.warn('[fetch] /api/channels failed:', e); failedAreas.push('area_channels'); return null; }),
        api.getPlayers().catch((e) => { console.warn('[fetch] /api/players failed:', e); failedAreas.push('area_players'); return null; }),
        api.getMutes().catch((e) => { console.warn('[fetch] /api/mutes failed:', e); failedAreas.push('area_mutes'); return null; }),
        api.getBans().catch((e) => { console.warn('[fetch] /api/bans failed:', e); failedAreas.push('area_bans'); return null; }),
      ]);

      if (statusRes) setStatusData(statusRes);

      if (channelsRes && Array.isArray(channelsRes.channels)) {
        setChannels(channelsRes.channels.map(adaptChannel).filter(Boolean));
      }

      if (playersRes && Array.isArray(playersRes.players)) {
        setPlayers(playersRes.players.map(adaptPlayer).filter(Boolean));
      }

      if (mutesRes && Array.isArray(mutesRes.mutes)) {
        setMutedPlayers(mutesRes.mutes.map(adaptMute).filter(Boolean));
      }

      if (bansRes && Array.isArray(bansRes)) {
        // GET /api/bans -> [{ playerId, name?, bans: [...] }, ...]; flatten into
        // one row per ban sub-entry via adaptBan (which returns an array).
        const flat = bansRes.flatMap((b) => adaptBan(b)).filter(Boolean);
        setBannedPlayers(flat);
      }

      // Fetch notification unread count for the bell badge (best-effort).
      try {
        const notifRes = await api.getNotifications(1, 1, true);
        if (notifRes && typeof notifRes.unreadCount === 'number') {
          setApiUnreadCount(notifRes.unreadCount);
        }
      } catch (e) {
        console.warn('[fetch] /api/notifications unread failed:', e);
        failedAreas.push('area_notifications');
      }

      // Fetch backend settings (best-effort; defaults stay enabled on error;
      // failure toast shown by fetchSettings itself).
      fetchSettings().catch((e) => console.warn('[fetch] settings failed:', e));

      if (failedAreas.length > 0) {
        const parts = failedAreas.map((k) => tRef.current(`common.${k}`)).join(', ');
        addToast(tRef.current('common.partial_load_failed', { parts }), 'error');
      }

      // Webhooks loaded lazily when the tab is opened; not part of initial fetch.
      setInitialLoading(false);
    } catch (err) {
      console.error('[fetch] initial load failed:', err);
      setFetchError(err.message || tRef.current('common.load_failed'));
      setInitialLoading(false);
    }
  }, [addToast, fetchSettings]);

  // Trigger initial fetch on mount.
  useEffect(() => {
    fetchAllData();
  }, [fetchAllData]);

  // Refresh only the channels list (used after channel CRUD).
  const fetchChannels = useCallback(async () => {
    try {
      const channelsRes = await api.getChannels();
      if (channelsRes && Array.isArray(channelsRes.channels)) {
        setChannels(channelsRes.channels.map(adaptChannel).filter(Boolean));
      }
    } catch (err) {
      console.warn('[fetchChannels] failed:', err);
    }
  }, []);

  // Refresh only the players list (used after kick).
  const fetchPlayers = useCallback(async () => {
    try {
      const playersRes = await api.getPlayers();
      if (playersRes && Array.isArray(playersRes.players)) {
        setPlayers(playersRes.players.map(adaptPlayer).filter(Boolean));
      }
    } catch (err) {
      console.warn('[fetchPlayers] failed:', err);
    }
  }, []);

  // Refresh only the mutes list (used after mute/unmute).
  const fetchMutes = useCallback(async () => {
    try {
      const mutesRes = await api.getMutes();
      if (mutesRes && Array.isArray(mutesRes.mutes)) {
        setMutedPlayers(mutesRes.mutes.map(adaptMute).filter(Boolean));
      }
    } catch (err) {
      console.warn('[fetchMutes] failed:', err);
      addToast(t('players.toast_mutes_failed', { error: err.message }), 'error');
    }
  }, [addToast, t]);

  // Refresh only the bans list (used after ban/unban).
  const fetchBans = useCallback(async () => {
    try {
      const bansRes = await api.getBans();
      if (bansRes && Array.isArray(bansRes)) {
        const flat = bansRes.flatMap((b) => adaptBan(b)).filter(Boolean);
        setBannedPlayers(flat);
      }
    } catch (err) {
      console.warn('[fetchBans] failed:', err);
      addToast(t('players.toast_bans_failed', { error: err.message }), 'error');
    }
  }, [addToast, t]);

  // --- Actions ---

  // Send a message via REST POST /api/messages.
  const handleSendMessage = useCallback(async (channelId, content) => {
    if (!channelId || !content) return;
    const senderName = (currentUser && currentUser.username) || 'Panel';
    try {
      await api.sendMessage(channelId, content, senderName);
      addToast(t('messages.toast_sent'), 'success');
    } catch (err) {
      addToast(t('messages.toast_send_failed', { error: err.message }), 'error');
    }
  }, [currentUser, addToast, t]);

  // Mute a player via REST POST /api/players/{uuid}/mute.
  // body: { channelId?, durationMs?, reason? } — durationMs 0 (or omitted) = permanent.
  const handleMutePlayer = useCallback(async (payload) => {
    if (!payload || !payload.uuid) {
      addToast(t('players.toast_mute_failed', { error: 'uuid' }), 'error');
      return;
    }
    const body = {};
    if (payload.channelId) body.channelId = payload.channelId;
    body.durationMs = payload.durationMs != null ? payload.durationMs : 0;
    if (payload.reason) body.reason = payload.reason;
    try {
      await api.mutePlayer(payload.uuid, body);
      addToast(t('players.toast_mute_success', { name: payload.name || payload.uuid }), 'success');
      await fetchMutes();
      await fetchPlayers();
    } catch (err) {
      addToast(t('players.toast_mute_failed', { error: err.message }), 'error');
    }
  }, [addToast, t, fetchMutes, fetchPlayers]);

  // Unmute a player via REST POST /api/players/{uuid}/unmute.
  // body: { channelId? } — omit for a global unmute.
  const handleUnmutePlayer = useCallback(async (payload) => {
    const uuid = typeof payload === 'string' ? payload : (payload && payload.uuid);
    if (!uuid) {
      addToast(t('players.toast_unmute_failed', { error: 'uuid' }), 'error');
      return;
    }
    const body = {};
    if (typeof payload === 'object' && payload.channelId) body.channelId = payload.channelId;
    try {
      await api.unmutePlayer(uuid, body);
      addToast(t('players.toast_unmute_success', { name: (payload && payload.name) || uuid }), 'success');
      await fetchMutes();
      await fetchPlayers();
    } catch (err) {
      addToast(t('players.toast_unmute_failed', { error: err.message }), 'error');
    }
  }, [addToast, t, fetchMutes, fetchPlayers]);

  // Kick a player via REST POST /api/players/{uuid}/kick (moves to default channel).
  const handleKickPlayer = useCallback(async (payload) => {
    const uuid = typeof payload === 'string' ? payload : (payload && payload.uuid);
    const name = typeof payload === 'object' ? (payload && payload.name) : null;
    if (!uuid) {
      addToast(t('players.toast_kick_failed', { error: 'uuid' }), 'error');
      return;
    }
    const body = {};
    if (typeof payload === 'object' && payload.channelId) body.channelId = payload.channelId;
    try {
      await api.kickPlayer(uuid, body);
      addToast(t('players.toast_kick_success', { name: name || uuid }), 'success');
      await fetchPlayers();
    } catch (err) {
      addToast(t('players.toast_kick_failed', { error: err.message }), 'error');
    }
  }, [addToast, t, fetchPlayers]);

  // Ban a player via REST POST /api/players/{uuid}/ban.
  // body: { channelId?, durationMs, reason } — durationMs 0 = permanent;
  // channelId omitted/empty = global ban.
  const handleBanPlayer = useCallback(async (payload) => {
    if (!payload || !payload.uuid) {
      addToast(t('players.toast_ban_failed', { error: 'uuid' }), 'error');
      return;
    }
    const body = { durationMs: payload.durationMs != null ? payload.durationMs : 0 };
    if (payload.channelId) body.channelId = payload.channelId;
    if (payload.reason) body.reason = payload.reason;
    try {
      await api.banPlayer(payload.uuid, body);
      addToast(t('players.toast_ban_success', { name: payload.name || payload.uuid }), 'success');
      await fetchBans();
    } catch (err) {
      addToast(t('players.toast_ban_failed', { error: err.message }), 'error');
    }
  }, [addToast, t, fetchBans]);

  // Unban a player via REST POST /api/players/{uuid}/unban.
  // body: { channelId? } — omit for a global unban.
  const handleUnbanPlayer = useCallback(async (payload) => {
    const uuid = typeof payload === 'string' ? payload : (payload && payload.uuid);
    if (!uuid) {
      addToast(t('players.toast_unban_failed', { error: 'uuid' }), 'error');
      return;
    }
    const body = {};
    if (typeof payload === 'object' && payload.channelId) body.channelId = payload.channelId;
    try {
      await api.unbanPlayer(uuid, body);
      addToast(t('players.toast_unban_success', { name: (payload && payload.name) || uuid }), 'success');
      await fetchBans();
    } catch (err) {
      addToast(t('players.toast_unban_failed', { error: err.message }), 'error');
    }
  }, [addToast, t, fetchBans]);

  // Reload config via REST POST /api/reload.
  const handleReloadConfig = useCallback(async () => {
    try {
      await api.reloadConfig();
      addToast(t('common.toast_reload_success'), 'success');
    } catch (err) {
      addToast(t('common.toast_reload_failed', { error: err.message }), 'error');
    }
  }, [addToast, t]);

  // Channel create/edit/delete via REST.
  const handleCreateChannel = useCallback(async (body) => {
    try {
      await api.createChannel(body);
      addToast(t('channels.toast_create'), 'success');
      await fetchChannels();
    } catch (err) {
      addToast(t('channels.toast_create_failed', { error: err.message }), 'error');
      throw err;
    }
  }, [addToast, t, fetchChannels]);

  const handleEditChannel = useCallback(async (id, body) => {
    try {
      await api.updateChannel(id, body);
      addToast(t('channels.toast_edit'), 'success');
      await fetchChannels();
    } catch (err) {
      addToast(t('channels.toast_edit_failed', { error: err.message }), 'error');
      throw err;
    }
  }, [addToast, t, fetchChannels]);

  const handleDeleteChannel = useCallback(async (id) => {
    try {
      await api.deleteChannel(id);
      addToast(t('channels.toast_delete'), 'success');
      await fetchChannels();
    } catch (err) {
      addToast(t('channels.toast_delete_failed', { error: err.message }), 'error');
      throw err;
    }
  }, [addToast, t, fetchChannels]);

  // Generate an invite code via REST POST /api/channels/{id}/invite.
  // Returns the invitation code string (or throws).
  const handleInviteChannel = useCallback(async (channelId, body) => {
    try {
      const res = await api.invitePlayer(channelId, body);
      addToast(t('channels.toast_invite'), 'success');
      return res;
    } catch (err) {
      addToast(t('channels.toast_invite_failed', { error: err.message }), 'error');
      throw err;
    }
  }, [addToast, t]);

  // Disconnect a game-server client via REST DELETE /api/clients/{clientId}.
  const handleDisconnectServer = useCallback(async (clientId, name) => {
    try {
      await api.disconnectClient(clientId);
      addToast(t('common.toast_disconnect_success', { name: name || clientId }), 'success');
    } catch (err) {
      addToast(t('common.toast_disconnect_failed', { error: err.message }), 'error');
      throw err;
    }
  }, [addToast, t]);

  // Webhooks: full CRUD is supported via REST (loaded lazily on tab open).
  const fetchWebhooks = useCallback(async () => {
    setWebhooksLoading(true);
    try {
      const res = await api.getWebhooks();
      if (res && Array.isArray(res.webhooks)) {
        setWebhooks(res.webhooks);
      }
    } catch (err) {
      console.error('[webhooks] fetch failed:', err);
      addToast(t('webhooks.toast_fetch_failed', { error: err.message }), 'error');
    } finally {
      setWebhooksLoading(false);
    }
  }, [addToast, t]);

  const handleCreateWebhook = useCallback(async (body) => {
    try {
      await api.createWebhook(body);
      addToast(t('webhooks.toast_create_success'), 'success');
      await fetchWebhooks();
    } catch (err) {
      addToast(t('webhooks.toast_create_failed', { error: err.message }), 'error');
      throw err;
    }
  }, [addToast, t, fetchWebhooks]);

  const handleDeleteWebhook = useCallback(async (id) => {
    try {
      await api.deleteWebhook(id);
      addToast(t('webhooks.toast_delete_success'), 'success');
      await fetchWebhooks();
    } catch (err) {
      addToast(t('webhooks.toast_delete_failed', { error: err.message }), 'error');
      throw err;
    }
  }, [addToast, t, fetchWebhooks]);

  // Update a webhook via PUT /api/webhooks/{id} — used both by the edit modal
  // (full body) and the on-card quick toggle ({ active } only).
  const handleUpdateWebhook = useCallback(async (id, body) => {
    try {
      await api.updateWebhook(id, body);
      addToast(t('webhooks.toast_update_success'), 'success');
      await fetchWebhooks();
    } catch (err) {
      addToast(t('webhooks.toast_update_failed', { error: err.message }), 'error');
      throw err;
    }
  }, [addToast, t, fetchWebhooks]);

  // Fire a test delivery via POST /api/webhooks/{id}/test.
  // Response: { success, statusCode?, error? } — failures surface the
  // statusCode/error detail in the toast. Never throws (the caller only
  // tracks a per-card loading state).
  const handleTestWebhook = useCallback(async (id) => {
    try {
      const res = await api.testWebhook(id);
      if (res && res.success) {
        addToast(t('webhooks.toast_test_success'), 'success');
      } else {
        const detail = [
          res && res.statusCode != null ? `HTTP ${res.statusCode}` : null,
          (res && res.error) || null,
        ].filter(Boolean).join(' · ') || t('common.error');
        addToast(t('webhooks.toast_test_failed', { error: detail }), 'error');
      }
      return res;
    } catch (err) {
      addToast(t('webhooks.toast_test_failed', { error: err.message }), 'error');
      return null;
    }
  }, [addToast, t]);

  // Notifications — persist via the same REST endpoints as the list modal so
  // the unread badge doesn't resurrect after a refresh.
  const handleMarkAllRead = useCallback(async () => {
    try {
      await api.markAllNotificationsRead();
      setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));
      setApiUnreadCount(0);
      addToast(t('notifications.toast_all_read'), 'success');
    } catch (err) {
      addToast(t('notifications.toast_mark_all_failed', { error: err.message }), 'error');
    }
  }, [addToast, t]);

  const handleClearNotifications = useCallback(async () => {
    try {
      await api.clearNotifications();
      setNotifications([]);
      setApiUnreadCount(0);
      addToast(t('notifications.toast_cleared'), 'success');
    } catch (err) {
      addToast(t('notifications.toast_clear_failed', { error: err.message }), 'error');
      throw err;
    }
  }, [addToast, t]);

  // Persist exactly one user-modified, backend-supported setting. Optimistic
  // state is rolled back only when that same value is still current.
  const handleSettingChange = useCallback(async (key, value) => {
    const previous = settingsRef.current;
    if (!previous?.supported?.[key] || !isValidSettingsValue(key, value)) {
      return false;
    }

    const next = { ...previous, [key]: value };
    const body = buildSettingsUpdateBody(next, [key]);
    if (Object.keys(body).length === 0) return false;

    settingsRef.current = next;
    setSettings(next);

    try {
      await api.updateSettings(body);
      addToast(t('common.settings_save_success'), 'success');
      return true;
    } catch (err) {
      addToast(t('common.settings_save_failed', { error: err.message }), 'error');
      setSettings((current) => {
        if (current[key] !== value) return current;
        const rolledBack = { ...current, [key]: previous[key] };
        settingsRef.current = rolledBack;
        return rolledBack;
      });
      return false;
    }
  }, [addToast, t]);

  const handleSettingToggle = useCallback((key) => {
    const current = settingsRef.current;
    if (typeof current?.[key] !== 'boolean') return Promise.resolve(false);
    if (navigator.vibrate) navigator.vibrate(5);
    return handleSettingChange(key, !current[key]);
  }, [handleSettingChange]);

  // Adapt a raw WS notification message into state + toast (used by the WS layer).
  const handleWsNotification = useCallback((message) => {
    const adapted = adaptNotification(message);
    if (adapted) {
      setNotifications((prev) => [adapted, ...prev].slice(0, 100));
      addToast(adapted.title + (adapted.desc ? `: ${adapted.desc}` : ''), adapted.type === 'warning' ? 'error' : 'success');
    }
  }, [addToast]);

  return {
    // state
    statusData,
    channels,
    setChannels,
    players,
    setPlayers,
    webhooks,
    webhooksLoading,
    mutedPlayers,
    bannedPlayers,
    notifications,
    setNotifications,
    apiUnreadCount,
    setApiUnreadCount,
    initialLoading,
    fetchError,
    settings,
    settingsLoading,
    // fetchers
    fetchAllData,
    fetchWebhooks,
    // actions
    handleSendMessage,
    handleMutePlayer,
    handleUnmutePlayer,
    handleKickPlayer,
    handleBanPlayer,
    handleUnbanPlayer,
    handleReloadConfig,
    handleCreateChannel,
    handleEditChannel,
    handleDeleteChannel,
    handleInviteChannel,
    handleDisconnectServer,
    handleCreateWebhook,
    handleDeleteWebhook,
    handleUpdateWebhook,
    handleTestWebhook,
    handleMarkAllRead,
    handleClearNotifications,
    handleSettingChange,
    handleSettingToggle,
    handleWsNotification,
  };
}

export default useDashboardData;
