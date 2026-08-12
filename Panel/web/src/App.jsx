import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import {
  LayoutDashboard,
  Server,
  Users,
  MessageSquare,
  Hash,
  Settings,
  Bell,
  Search,
  Menu,
  Moon,
  Sun,
  LogOut,
  ChevronLeft,
  ChevronRight,
  RefreshCw,
  Loader2,
  AlertCircle,
  Terminal,
  Server as ServerIcon,
  Users as UsersIcon,
  MessageSquare as MessageIcon,
  Hash as HashIcon,
} from 'lucide-react';

import authService from './services/auth';
import websocketService, { ConnectionState, MessageType } from './services/websocket';
import { api, getApiBaseUrl, getWsUrl, clearConnectionUrls } from './services/api';
import {
  adaptChannel,
  adaptPlayer,
  adaptWsPlayer,
  adaptClient,
  adaptChatMessage,
  adaptNotification,
  adaptMute,
  adaptBan,
  buildDashboardStats,
} from './utils/adapters';

import ToastContainer from './components/ui/ToastContainer';
import Card from './components/ui/Card';
import Button from './components/ui/Button';
import Switch from './components/ui/Switch';
import Modal from './components/ui/Modal';
import NotificationDropdown from './components/dashboard/NotificationDropdown';
import NotificationListModal from './components/dashboard/NotificationListModal';

// Dashboard View Components
import DashboardView from './components/dashboard/DashboardView';
import MessageMonitor from './components/dashboard/MessageMonitor';
import ConsoleCommand from './components/dashboard/ConsoleCommand';
import ChannelManagement from './components/dashboard/ChannelManagement';
import PlayerManagement from './components/dashboard/PlayerManagement';
import ClientStatus from './components/dashboard/ClientStatus';
import WebhookManagement from './components/dashboard/WebhookManagement';

import LoginScreen from './components/auth/LoginScreen';

import { SUPPORTED_LANGS } from './i18n';

const THEME_STORAGE_KEY = 'nova-panel-theme';

// Settings UI key -> backend key map (module-level so handleSettingToggle's
// useCallback deps don't need to reference it).
const SETTINGS_KEY_MAP = {
  enableFilter: 'filterEnabled',
  logMessages: 'messageLogEnabled',
  crossServerChat: 'crossServerChatEnabled',
};

// Lucide icon lookup for dashboard stat cards (built dynamically from string names).
const STAT_ICON_MAP = {
  Server: ServerIcon,
  Users: UsersIcon,
  MessageSquare: MessageIcon,
  Hash: HashIcon,
};

export default function App() {
  // --- Auth gate ---
  const [authenticated, setAuthenticated] = useState(authService.isAuthenticated());
  const [currentUser, setCurrentUser] = useState(authService.getUser());
  const [authVersion, setAuthVersion] = useState(0); // force re-eval after login/logout

  // Re-check auth state whenever authVersion changes.
  useEffect(() => {
    const unsub = authService.onAuthChange((state) => {
      setAuthenticated(state.isAuthenticated);
      setCurrentUser(state.user);
    });
    return unsub;
  }, [authVersion]);

  const handleLoginSuccess = useCallback(() => {
    setAuthenticated(true);
    setCurrentUser(authService.getUser());
    setAuthVersion((v) => v + 1);
  }, []);

  const handleLogout = useCallback(() => {
    websocketService.disconnect();
    authService.logout();
    clearConnectionUrls();
    setAuthenticated(false);
    setCurrentUser(null);
    setAuthVersion((v) => v + 1);
  }, []);

  if (!authenticated) {
    return <LoginScreen onLoginSuccess={handleLoginSuccess} />;
  }

  return <Dashboard key={authVersion} currentUser={currentUser} onLogout={handleLogout} />;
}

// ==================== Dashboard ====================
function Dashboard({ currentUser, onLogout }) {
  const { t, i18n } = useTranslation();
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [isMobile, setIsMobile] = useState(false);
  // DEFAULT THEME = LIGHT. The app loads with no .dark class on <html>.
  // Persist + restore the user's choice (light/dark) in localStorage.
  const [mode, setMode] = useState(() => {
    try {
      return localStorage.getItem(THEME_STORAGE_KEY) || 'light';
    } catch {
      return 'light';
    }
  });
  const [activeTab, setActiveTab] = useState('dashboard');
  const [tabLoading, setTabLoading] = useState(false);

  // Sync the `.dark` class on <html> with the mode state so the oklch CSS
  // variables (card/border/primary/muted-foreground/...) switch automatically.
  useEffect(() => {
    const root = document.documentElement;
    if (mode === 'dark') root.classList.add('dark');
    else root.classList.remove('dark');
    try {
      localStorage.setItem(THEME_STORAGE_KEY, mode);
    } catch {
      // ignore storage errors
    }
  }, [mode]);

  // Data State — initialized empty, populated from real REST + WS.
  const [servers, setServers] = useState([]);
  const [channels, setChannels] = useState([]);
  const [players, setPlayers] = useState([]);
  const [webhooks, setWebhooks] = useState([]);
  const [webhooksLoading, setWebhooksLoading] = useState(false);
  const [mutedPlayers, setMutedPlayers] = useState([]);
  const [bannedPlayers, setBannedPlayers] = useState([]);
  const [chatMessages, setChatMessages] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [toasts, setToasts] = useState([]);
  const [statusData, setStatusData] = useState(null);

  // Loading / error state for initial fetch.
  const [initialLoading, setInitialLoading] = useState(true);
  const [fetchError, setFetchError] = useState(null);

  // Search & Filter State
  const [searchQuery, setSearchQuery] = useState('');
  const [consoleAutoScroll, setConsoleAutoScroll] = useState(true);

  // Settings State — persisted to the backend via GET/PUT /api/settings.
  // Backend keys: { filterEnabled, messageLogEnabled, crossServerChatEnabled }.
  // UI keys: enableFilter / logMessages / crossServerChat (kept for the
  // existing Switch wiring). Defaults to enabled until the first GET resolves.
  const [settings, setSettings] = useState({
    enableFilter: true,
    logMessages: true,
    crossServerChat: true,
  });
  const [settingsLoading, setSettingsLoading] = useState(false);

  // WS connection state (for showing a small indicator).
  const [wsState, setWsState] = useState(websocketService.getState());

  // UI State
  const [showNotifications, setShowNotifications] = useState(false);
  const [showNotificationList, setShowNotificationList] = useState(false);
  // Unread count sourced from GET /api/notifications for the bell badge.
  const [apiUnreadCount, setApiUnreadCount] = useState(0);
  const notificationRef = useRef(null);
  const chatContainerRef = useRef(null);
  const wsHandlersRef = useRef({});
  // Server details modal (opened from ClientStatus "view details").
  const [serverDetailTarget, setServerDetailTarget] = useState(null);
  // Disconnect confirm modal (opened from ClientStatus "disconnect").
  const [disconnectTarget, setDisconnectTarget] = useState(null);
  // Defers WS disconnect on effect cleanup so React StrictMode's dev-only
  // mount→unmount→remount cycle doesn't tear down a connection that's about to
  // be reused. A real unmount (logout / leave page) still disconnects, just
  // delayed by a tick; a remount cancels the timer and keeps the socket alive.
  const wsDisconnectTimerRef = useRef(null);
  // Keep a live ref to the translation function so long-lived WS handlers
  // (registered once on mount) emit locale-aware toast text without re-running
  // the WS effect on every language change.
  const tRef = useRef(t);
  useEffect(() => { tRef.current = t; }, [t]);

  // --- Toasts ---
  const addToast = useCallback((message, type = 'success') => {
    const id = Date.now() + Math.random();
    setToasts((prev) => [...prev, { id, message, type }]);
    setTimeout(() => setToasts((prev) => prev.filter((tt) => tt.id !== id)), 3000);
  }, []);

  const removeToast = useCallback((id) => setToasts((prev) => prev.filter((tt) => tt.id !== id)), []);

  // --- Initial data fetch on mount (after auth) ---
  const fetchAllData = useCallback(async () => {
    setFetchError(null);
    try {
      const [statusRes, channelsRes, playersRes, mutesRes, bansRes] = await Promise.all([
        api.status().catch((e) => { console.warn('[fetch] /api/status failed:', e); return null; }),
        api.getChannels().catch((e) => { console.warn('[fetch] /api/channels failed:', e); return null; }),
        api.getPlayers().catch((e) => { console.warn('[fetch] /api/players failed:', e); return null; }),
        api.getMutes().catch((e) => { console.warn('[fetch] /api/mutes failed:', e); return null; }),
        api.getBans().catch((e) => { console.warn('[fetch] /api/bans failed:', e); return null; }),
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
        const notifRes = await api.getNotifications(0, 1, true);
        if (notifRes && typeof notifRes.unreadCount === 'number') {
          setApiUnreadCount(notifRes.unreadCount);
        }
      } catch (e) {
        console.warn('[fetch] /api/notifications unread failed:', e);
      }

      // Fetch backend settings (best-effort; defaults stay enabled on error).
      fetchSettings().catch((e) => console.warn('[fetch] settings failed:', e));

      // Webhooks loaded lazily when the tab is opened; not part of initial fetch.
      setInitialLoading(false);
    } catch (err) {
      console.error('[fetch] initial load failed:', err);
      setFetchError(err.message || tRef.current('common.load_failed'));
      setInitialLoading(false);
    }
  }, []);

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

  // Fetch backend settings (GET /api/settings) and map to UI keys.
  const fetchSettings = useCallback(async () => {
    setSettingsLoading(true);
    try {
      const res = await api.getSettings();
      if (res) {
        setSettings({
          enableFilter: res.filterEnabled !== false,
          logMessages: res.messageLogEnabled !== false,
          crossServerChat: res.crossServerChatEnabled !== false,
        });
      }
    } catch (err) {
      console.warn('[fetchSettings] failed:', err);
      addToast(t('common.settings_load_failed', { error: err.message }), 'error');
    } finally {
      setSettingsLoading(false);
    }
  }, [addToast, t]);

  // Trigger initial fetch on mount. The fetch itself is async and calls setState
  // in callbacks (not synchronously in the effect body), which satisfies the
  // react-hooks/set-state-in-effect rule.
  useEffect(() => {
    let cancelled = false;
    const doFetch = async () => {
      await fetchAllData();
      if (cancelled) return;
    };
    doFetch();
    return () => { cancelled = true; };
  }, [fetchAllData]);

  // --- WebSocket connection + real-time handlers ---
  useEffect(() => {
    // Cancel any pending disconnect from a prior cleanup (StrictMode remount).
    if (wsDisconnectTimerRef.current) {
      clearTimeout(wsDisconnectTimerRef.current);
      wsDisconnectTimerRef.current = null;
    }
    let cancelled = false;
    const token = authService.getToken();
    const wsUrl = getWsUrl();

    if (!token) {
      console.warn('[WS] no auth token, skipping connect');
      return;
    }

    // State-change listener.
    const handleStateChange = ({ state }) => {
      if (cancelled) return;
      setWsState(state);
      if (state === ConnectionState.ERROR) {
        addToast(tRef.current('common.ws_toast_error'), 'error');
      }
    };
    websocketService.on('stateChange', handleStateChange);

    // Message handlers.
    const handleChat = (message) => {
      const adapted = adaptChatMessage(message);
      if (adapted) {
        setChatMessages((prev) => [...prev.slice(-199), adapted]);
      }
    };

    const handleServerStatus = (message) => {
      if (Array.isArray(message.clients)) {
        const adapted = message.clients.map(adaptClient).filter(Boolean);
        setServers(adapted);
      } else if (message.server) {
        const adapted = adaptClient(message.server);
        if (adapted) {
          setServers((prev) => {
            const idx = prev.findIndex((s) => s.id === adapted.id);
            if (idx >= 0) {
              const next = [...prev];
              next[idx] = adapted;
              return next;
            }
            return [...prev, adapted];
          });
        }
      }
    };

    const handleChannelUpdate = (message) => {
      if (Array.isArray(message.channels)) {
        setChannels(message.channels.map(adaptChannel).filter(Boolean));
      }
    };

    const handlePlayerUpdate = (message) => {
      if (Array.isArray(message.players)) {
        setPlayers(message.players.map(adaptWsPlayer).filter(Boolean));
      } else if (message.player) {
        const adapted = adaptWsPlayer(message.player);
        if (adapted) {
          setPlayers((prev) => {
            if (message.action === 'leave') {
              return prev.filter((p) => p.uuid !== adapted.uuid);
            }
            const idx = prev.findIndex((p) => p.uuid === adapted.uuid);
            if (idx >= 0) {
              const next = [...prev];
              next[idx] = adapted;
              return next;
            }
            return [...prev, adapted];
          });
        }
      }
    };

    const handleNotification = (message) => {
      const adapted = adaptNotification(message);
      if (adapted) {
        setNotifications((prev) => [adapted, ...prev]);
        addToast(adapted.title + (adapted.desc ? `: ${adapted.desc}` : ''), adapted.type === 'warning' ? 'error' : 'success');
      }
    };

    websocketService.on(MessageType.CHAT, handleChat);
    websocketService.on(MessageType.SERVER_STATUS, handleServerStatus);
    websocketService.on(MessageType.CHANNEL_UPDATE, handleChannelUpdate);
    websocketService.on(MessageType.PLAYER_UPDATE, handlePlayerUpdate);
    websocketService.on(MessageType.NOTIFICATION, handleNotification);

    wsHandlersRef.current = { handleChat, handleServerStatus, handleChannelUpdate, handlePlayerUpdate, handleNotification, handleStateChange };

    // Connect (non-blocking — failures are surfaced via toasts/state).
    websocketService.connect(wsUrl, token).catch((err) => {
      console.error('[WS] connect failed:', err);
      addToast(tRef.current('common.ws_toast_failed', { error: (err.message || err) }), 'error');
    });

    return () => {
      cancelled = true;
      websocketService.off(MessageType.CHAT, handleChat);
      websocketService.off(MessageType.SERVER_STATUS, handleServerStatus);
      websocketService.off(MessageType.CHANNEL_UPDATE, handleChannelUpdate);
      websocketService.off(MessageType.PLAYER_UPDATE, handlePlayerUpdate);
      websocketService.off(MessageType.NOTIFICATION, handleNotification);
      websocketService.off('stateChange', handleStateChange);
      // Defer disconnect: StrictMode remounts the effect immediately after
      // cleanup in dev. If a remount follows, its setup clears this timer and
      // reuses the live socket. If no remount comes (real unmount), the socket
      // is closed after 150ms.
      wsDisconnectTimerRef.current = setTimeout(() => {
        websocketService.disconnect();
        wsDisconnectTimerRef.current = null;
      }, 150);
    };
  }, [addToast]);

  // After WS authenticates, subscribe to all known channel IDs so we receive chat.
  useEffect(() => {
    if (wsState !== ConnectionState.AUTHENTICATED) return;
    if (channels.length === 0) return;
    const channelIds = channels.map((c) => c.id).filter(Boolean);
    if (channelIds.length > 0) {
      websocketService.subscribe(channelIds);
    }
  }, [wsState, channels]);

  // Auto-scroll chat container (console tab).
  useEffect(() => {
    if (consoleAutoScroll && chatContainerRef.current) {
      chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
    }
  }, [chatMessages, consoleAutoScroll]);

  // Click-outside for notification dropdown.
  useEffect(() => {
    const handleClickOutside = (event) => {
      if (notificationRef.current && !notificationRef.current.contains(event.target)) {
        setShowNotifications(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Responsive.
  useEffect(() => {
    const handleResize = () => {
      const mobile = window.innerWidth < 1024;
      setIsMobile(mobile);
      if (mobile) setSidebarOpen(false);
      else setSidebarOpen(true);
    };
    handleResize();
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

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

  // Open the server details modal (data already in `servers` state; no REST call).
  const handleViewServerDetails = useCallback((server) => {
    setServerDetailTarget(server);
  }, []);

  // Confirm + execute disconnect (called by the disconnect confirm modal).
  const [disconnecting, setDisconnecting] = useState(false);
  const confirmDisconnect = useCallback(async () => {
    if (!disconnectTarget) return;
    setDisconnecting(true);
    try {
      await handleDisconnectServer(disconnectTarget.id, disconnectTarget.name);
      setDisconnectTarget(null);
    } catch {
      // toast already shown by handleDisconnectServer
    } finally {
      setDisconnecting(false);
    }
  }, [disconnectTarget, handleDisconnectServer]);

  // Webhooks: full CRUD is supported via REST.
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

  // Notifications.
  const handleMarkAllRead = useCallback(() => {
    setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));
    setApiUnreadCount(0);
    addToast(t('notifications.toast_all_read'), 'success');
  }, [addToast, t]);

  const handleClearNotifications = useCallback(() => {
    setNotifications([]);
    setShowNotifications(false);
    setApiUnreadCount(0);
    addToast(t('notifications.toast_cleared'), 'success');
  }, [addToast, t]);

  // Open the full notification list modal (from the dropdown "view all").
  const handleOpenNotificationList = useCallback(() => {
    setShowNotifications(false);
    setShowNotificationList(true);
  }, []);

  // Keep the bell badge's unread count in sync with the API-sourced count
  // reported by the NotificationListModal after each fetch/action.
  const handleUnreadCountChange = useCallback((count) => {
    setApiUnreadCount(count);
  }, []);

  // Settings toggles — optimistic update + PUT /api/settings persist.
  const handleSettingToggle = useCallback((key) => {
    if (!SETTINGS_KEY_MAP[key]) return;
    // Optimistic flip.
    setSettings((prev) => {
      const next = { ...prev, [key]: !prev[key] };
      // Build the full backend body from the new state.
      const body = {
        filterEnabled: next.enableFilter,
        messageLogEnabled: next.logMessages,
        crossServerChatEnabled: next.crossServerChat,
      };
      // Persist; roll back on failure.
      api.updateSettings(body).then(() => {
        addToast(t('common.settings_save_success'), 'success');
      }).catch((err) => {
        addToast(t('common.settings_save_failed', { error: err.message }), 'error');
        setSettings((p) => ({ ...p, [key]: prev[key] }));
      });
      return next;
    });
    if (navigator.vibrate) navigator.vibrate(5);
  }, [addToast, t]);

  const handleTabChange = useCallback((tab) => {
    setTabLoading(true);
    setActiveTab(tab);
    if (isMobile) setSidebarOpen(false);
    // Lazy-load webhooks when the tab is first opened.
    if (tab === 'webhooks') {
      fetchWebhooks();
    }
    setTimeout(() => setTabLoading(false), 200);
  }, [isMobile, fetchWebhooks]);

  // --- Derived / styling ---
  // Token-based text/background classes so the whole panel re-themes via the
  // .dark class + the oklch CSS variables in index.css.
  const txtMain = 'text-foreground';
  const txtSec = 'text-muted-foreground';

  const filteredPlayers = useMemo(() => {
    const q = (searchQuery || '').toLowerCase();
    return players.filter((p) =>
      ((p && p.name) || '').toLowerCase().includes(q) ||
      ((p && p.server) || '').toLowerCase().includes(q)
    );
  }, [players, searchQuery]);

  const dashboardStats = useMemo(
    () => buildDashboardStats(statusData, servers, channels, chatMessages),
    [statusData, servers, channels, chatMessages]
  );

  const navItems = [
    { id: 'dashboard', icon: LayoutDashboard, label: t('common.nav_dashboard') },
    { id: 'messages', icon: MessageSquare, label: t('common.nav_messages') },
    { id: 'console', icon: Terminal, label: t('common.nav_console_command') },
    { id: 'servers', icon: Server, label: t('common.nav_servers') },
    { id: 'channels', icon: Hash, label: t('common.nav_channels') },
    { id: 'players', icon: Users, label: t('common.nav_players') },
    { id: 'webhooks', icon: Bell, label: t('common.nav_webhooks') },
    { id: 'settings', icon: Settings, label: t('common.nav_settings') },
  ];

  const wsIndicator = (() => {
    if (wsState === ConnectionState.AUTHENTICATED) return { color: 'bg-emerald-500', label: t('common.ws_connected') };
    if (wsState === ConnectionState.CONNECTED || wsState === ConnectionState.CONNECTING) return { color: 'bg-amber-500', label: t('common.ws_connecting') };
    if (wsState === ConnectionState.RECONNECTING) return { color: 'bg-amber-500', label: t('common.ws_reconnecting') };
    if (wsState === ConnectionState.ERROR) return { color: 'bg-red-500', label: t('common.ws_error') };
    return { color: 'bg-slate-500', label: t('common.ws_disconnected') };
  })();

  return (
    <div className="w-full overflow-hidden font-sans bg-background text-foreground relative" style={{ minHeight: '100dvh' }}>
      <ToastContainer toasts={toasts} removeToast={removeToast} />

      <div className="relative flex h-screen w-full" style={{ height: '100dvh' }}>
        {isMobile && sidebarOpen && <div className="fixed inset-0 z-40 bg-black/50 transition-opacity duration-300" onClick={() => setSidebarOpen(false)} />}

        {/* Sidebar — token-driven (bg-sidebar). Light mode = near-white, dark mode = near-black. */}
        <aside className={`fixed lg:relative z-50 h-full flex flex-col transition-all duration-300 bg-sidebar text-sidebar-foreground border-r border-sidebar-border ${isMobile ? (sidebarOpen ? 'translate-x-0 w-60' : '-translate-x-full w-60') : (sidebarOpen ? 'w-60 translate-x-0' : 'w-16 translate-x-0')}`}>
          <div className="flex-1 flex flex-col p-3 overflow-hidden">
            <div className={`flex items-center mb-6 h-10 shrink-0 transition-all duration-300 ${!isMobile && !sidebarOpen ? 'justify-center px-0' : 'gap-2 px-2'}`}>
              <img src="/novalink-logo.svg" alt="NovaLink" className="size-8 shrink-0 object-contain" />
              <div className={`overflow-hidden whitespace-nowrap transition-all duration-300 ${!isMobile && !sidebarOpen ? 'w-0 opacity-0' : 'w-auto opacity-100'}`}>
                <h1 className="text-sm font-semibold text-foreground">NovaPanel</h1>
              </div>
            </div>
            <nav className="flex-1 space-y-0.5 overflow-y-auto scrollbar-hide">
              {navItems.map((item) => (
                <button
                  key={item.id}
                  onClick={() => handleTabChange(item.id)}
                  className={`w-full flex items-center gap-2.5 rounded-md px-3 py-1.5 transition-colors text-xs font-medium ${activeTab === item.id ? 'bg-sidebar-accent text-sidebar-accent-foreground' : 'text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground'}`}
                  title={!sidebarOpen && !isMobile ? item.label : ''}
                >
                  <div className="shrink-0"><item.icon size={16} /></div>
                  <span className={`whitespace-nowrap transition-all duration-300 ${!isMobile && !sidebarOpen ? 'opacity-0 w-0 overflow-hidden' : 'opacity-100 w-auto'}`}>{item.label}</span>
                </button>
              ))}
            </nav>
            <div className={`mt-3 rounded-md flex items-center transition-all duration-300 overflow-hidden shrink-0 border border-sidebar-border ${!isMobile && !sidebarOpen ? 'p-1.5 justify-center' : 'p-2'}`}>
              <div className={`shrink-0 flex size-7 items-center justify-center rounded-full bg-primary text-primary-foreground text-xs font-semibold ${!isMobile && !sidebarOpen ? '' : 'mr-2'}`} title={(currentUser && currentUser.username) || t('common.user')}>
                {((currentUser && currentUser.username) || 'U')[0].toUpperCase()}
              </div>
              <div className={`overflow-hidden transition-all duration-300 flex-1 min-w-0 ${!isMobile && !sidebarOpen ? 'w-0 opacity-0' : 'w-auto opacity-100'}`}>
                <p className="text-xs font-medium whitespace-nowrap text-foreground truncate">{(currentUser && currentUser.username) || t('common.user')}</p>
                <p className="text-[11px] whitespace-nowrap text-muted-foreground truncate">{(currentUser && currentUser.role) || ''}</p>
              </div>
              <button onClick={onLogout} className="text-muted-foreground hover:text-destructive transition-colors shrink-0 rounded-md p-1 hover:bg-accent" title={t('common.logout_title')}>
                <LogOut size={16} />
              </button>
            </div>
          </div>
        </aside>

        {/* Main Content */}
        <main className="flex-1 flex flex-col h-full overflow-hidden relative transition-all duration-300">
          {/* Header */}
          <header className="h-14 px-4 md:px-6 flex items-center justify-between shrink-0 z-30 border-b border-border bg-background/95 backdrop-blur-sm">
            <div className="flex items-center gap-3">
              <button onClick={() => setSidebarOpen(!sidebarOpen)} className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground" title="Toggle sidebar">
                {isMobile ? <Menu size={18} /> : (sidebarOpen ? <ChevronLeft size={18} /> : <ChevronRight size={18} />)}
              </button>
              <div className="hidden md:flex items-center gap-2 rounded-md border border-border bg-muted/40 px-2.5 py-1 transition-colors">
                <Search size={14} className="text-muted-foreground" />
                <input type="text" placeholder={t('common.search')} className="bg-transparent border-none outline-none text-xs w-32 lg:w-48 placeholder:text-muted-foreground text-foreground" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} />
              </div>
            </div>
            <div className="flex items-center gap-2 md:gap-3">
              {/* WS status indicator */}
              <div className="hidden sm:flex items-center gap-1.5 rounded-full bg-muted/60 border border-border px-2 py-0.5" title={wsIndicator.label}>
                <span className={`w-1.5 h-1.5 rounded-full ${wsIndicator.color} ${wsState === ConnectionState.CONNECTING || wsState === ConnectionState.RECONNECTING ? 'animate-pulse' : ''}`} />
                <span className="text-[11px] text-muted-foreground">{wsIndicator.label}</span>
              </div>
              <div className="relative" ref={notificationRef}>
                <button onClick={() => setShowNotifications(!showNotifications)} className="relative rounded-full p-1.5 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground" title={t('notifications.title')}>
                  <Bell size={18} />
                  {apiUnreadCount > 0 ? (
                    <span className="absolute top-0.5 right-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold text-destructive-foreground">
                      {apiUnreadCount > 99 ? '99+' : apiUnreadCount}
                    </span>
                  ) : notifications.some((n) => !n.read) ? (
                    <span className="absolute top-1 right-1 w-1.5 h-1.5 bg-destructive rounded-full" />
                  ) : null}
                </button>
                <NotificationDropdown
                  isOpen={showNotifications}
                  onClose={() => setShowNotifications(false)}
                  theme="clean"
                  mode={mode}
                  notifications={notifications}
                  onMarkAllRead={handleMarkAllRead}
                  onClearAll={handleClearNotifications}
                  onOpenList={handleOpenNotificationList}
                />
              </div>
              {/* Language switcher — driven by SUPPORTED_LANGS (auto-detected from src/lang/*.json) */}
              <div className="flex items-center p-0.5 rounded-full gap-0.5 border border-border bg-muted/60" title={t('language.switch_title')}>
                {SUPPORTED_LANGS.map((lang) => {
                  const store = i18n.store?.()?.data ?? {};
                  const label = store[lang]?.translation?.language?.self ?? lang;
                  const isActive = i18n.language === lang || i18n.language?.split('_')[0] === lang.split('_')[0];
                  return (
                    <button
                      key={lang}
                      onClick={() => i18n.changeLanguage(lang)}
                      className={`px-2 py-0.5 rounded-full text-[11px] font-medium transition-colors ${isActive ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'}`}
                    >
                      {label}
                    </button>
                  );
                })}
              </div>
              <div className="flex items-center p-0.5 rounded-full gap-0.5 border border-border bg-muted/60">
                <button onClick={() => setMode('light')} className={`p-1 rounded-full transition-colors ${mode === 'light' ? 'bg-background shadow-sm text-amber-500' : 'text-muted-foreground hover:text-foreground'}`} title="Light"><Sun size={14} /></button>
                <button onClick={() => setMode('dark')} className={`p-1 rounded-full transition-colors ${mode === 'dark' ? 'bg-background shadow-sm text-primary' : 'text-muted-foreground hover:text-foreground'}`} title="Dark"><Moon size={14} /></button>
              </div>
            </div>
          </header>

          {/* Content Area */}
          <div className="flex-1 overflow-y-auto p-4 md:p-6">
            <div className="max-w-7xl mx-auto space-y-6">
              {initialLoading ? (
                <div className="h-96 flex flex-col items-center justify-center gap-3">
                  <Loader2 size={28} className="animate-spin text-muted-foreground" />
                  <p className="text-xs text-muted-foreground">{t('common.loading_data')}</p>
                </div>
              ) : fetchError && channels.length === 0 && players.length === 0 ? (
                <div className="h-96 flex flex-col items-center justify-center gap-4">
                  <AlertCircle size={28} className="text-destructive" />
                  <p className="text-sm text-foreground">{t('common.load_failed_msg', { error: fetchError })}</p>
                  <Button variant="outline" onClick={fetchAllData}>
                    <RefreshCw size={14} /> {t('common.retry')}
                  </Button>
                </div>
              ) : tabLoading ? (
                <div className="h-96 flex items-center justify-center">
                  <div className="size-8 border-4 rounded-full animate-spin border-primary border-t-transparent" />
                </div>
              ) : (
                <>
                  {/* Dashboard - System Overview */}
                  {activeTab === 'dashboard' && (
                    <DashboardView
                      theme="clean"
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      servers={servers}
                      channels={channels}
                      players={players}
                      chatMessages={chatMessages}
                      dashboardStats={dashboardStats}
                      statIconMap={STAT_ICON_MAP}
                    />
                  )}

                  {/* Messages - Real-time Message Monitor */}
                  {activeTab === 'messages' && (
                    <MessageMonitor
                      theme="clean"
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      messages={chatMessages}
                      channels={channels}
                      onClearMessages={() => setChatMessages([])}
                      onSendMessage={handleSendMessage}
                      chatContainerRef={chatContainerRef}
                      consoleAutoScroll={consoleAutoScroll}
                      setConsoleAutoScroll={setConsoleAutoScroll}
                    />
                  )}

                  {/* Console - Backend Command Executor */}
                  {activeTab === 'console' && (
                    <ConsoleCommand
                      theme="clean"
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                    />
                  )}

                  {/* Servers - Client Status */}
                  {activeTab === 'servers' && (
                    <ClientStatus
                      theme="clean"
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      servers={servers}
                      onReloadConfig={handleReloadConfig}
                      onDisconnectServer={(server) => setDisconnectTarget(server)}
                      onViewServerDetails={handleViewServerDetails}
                    />
                  )}

                  {/* Channels - Channel Management */}
                  {activeTab === 'channels' && (
                    <ChannelManagement
                      theme="clean"
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      channels={channels}
                      onCreateChannel={handleCreateChannel}
                      onEditChannel={handleEditChannel}
                      onDeleteChannel={handleDeleteChannel}
                      onInviteChannel={handleInviteChannel}
                    />
                  )}

                  {/* Players - Player Management */}
                  {activeTab === 'players' && (
                    <PlayerManagement
                      theme="clean"
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      players={filteredPlayers}
                      channels={channels}
                      mutedPlayers={mutedPlayers}
                      bannedPlayers={bannedPlayers}
                      onMutePlayer={handleMutePlayer}
                      onUnmutePlayer={handleUnmutePlayer}
                      onKickPlayer={handleKickPlayer}
                      onBanPlayer={handleBanPlayer}
                      onUnbanPlayer={handleUnbanPlayer}
                    />
                  )}

                  {/* Webhooks - Webhook Management */}
                  {activeTab === 'webhooks' && (
                    <WebhookManagement
                      theme="clean"
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      webhooks={webhooks}
                      loading={webhooksLoading}
                      onCreateWebhook={handleCreateWebhook}
                      onDeleteWebhook={handleDeleteWebhook}
                    />
                  )}

                  {/* Settings */}
                  {activeTab === 'settings' && (
                    <SettingsView
                      theme="clean" mode={mode} txtMain={txtMain} txtSec={txtSec}
                      settings={settings} onToggle={handleSettingToggle}
                      settingsLoading={settingsLoading}
                      setMode={setMode}
                      modeState={mode}
                      wsState={wsState}
                      apiUrl={getApiBaseUrl()}
                      wsUrl={getWsUrl()}
                    />
                  )}
                </>
              )}
            </div>
          </div>

          {/* Server Details Modal */}
          <Modal
            isOpen={!!serverDetailTarget}
            onClose={() => setServerDetailTarget(null)}
            title={t('common.server_details_modal_title')}
            theme="clean"
            mode={mode}
          >
            {serverDetailTarget && (
              <ServerDetailsContent server={serverDetailTarget} />
            )}
            <div className="flex gap-2 mt-6 pt-4 border-t border-border">
              <Button variant="ghost" className="flex-1" theme="clean" mode={mode} onClick={() => setServerDetailTarget(null)}>
                {t('common.confirm')}
              </Button>
            </div>
          </Modal>

          {/* Disconnect Server Confirm Modal */}
          <Modal
            isOpen={!!disconnectTarget}
            onClose={() => !disconnecting && setDisconnectTarget(null)}
            title={t('common.disconnect_modal_title')}
            theme="clean"
            mode={mode}
          >
            {disconnectTarget && (
              <p className="text-xs text-muted-foreground">
                {t('common.disconnect_confirm', { name: disconnectTarget.name || disconnectTarget.id })}
              </p>
            )}
            <div className="flex gap-2 mt-6 pt-4 border-t border-border">
              <Button
                variant="ghost"
                className="flex-1"
                theme="clean"
                mode={mode}
                onClick={() => setDisconnectTarget(null)}
                disabled={disconnecting}
              >
                {t('common.cancel')}
              </Button>
              <Button
                variant="destructive"
                className="flex-1"
                theme="clean"
                mode={mode}
                onClick={confirmDisconnect}
                disabled={disconnecting}
              >
                {disconnecting ? <Loader2 size={14} className="animate-spin" /> : null}
                {t('common.disconnect')}
              </Button>
            </div>
          </Modal>

          {/* Notification List Modal (full paginated history) */}
          <NotificationListModal
            isOpen={showNotificationList}
            onClose={() => setShowNotificationList(false)}
            theme="clean"
            mode={mode}
            onUnreadCountChange={handleUnreadCountChange}
            onToast={addToast}
          />
        </main>
      </div>
    </div>
  );
}

// ==================== Server Details Content ====================
function ServerDetailsContent({ server }) {
  const { t } = useTranslation();
  const s = server || {};
  const rowClass = 'flex items-center justify-between p-2 rounded-md bg-muted/40 text-xs';

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

  const formatUptime = (connectedAt) => {
    if (!connectedAt) return '-';
    const diff = Date.now() - (typeof connectedAt === 'number' ? connectedAt : Number(connectedAt));
    if (Number.isNaN(diff) || diff < 0) return '-';
    const seconds = Math.floor(diff / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    if (hours > 0) return `${hours}h ${minutes % 60}m`;
    if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
    return `${seconds}s`;
  };

  return (
    <div className="space-y-1.5">
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('common.server_details_id')}</span>
        <span className="text-foreground font-mono">{s.id || '-'}</span>
      </div>
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('players.col_server')}</span>
        <span className="text-foreground">{s.name || s.id || '-'}</span>
      </div>
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('players.col_platform')}</span>
        <span className="text-foreground">{s.platform || '-'}</span>
      </div>
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('common.col_version')}</span>
        <span className="text-foreground">{s.version || '-'}</span>
      </div>
      {s.remoteAddress && (
        <div className={rowClass}>
          <span className="text-muted-foreground">{t('common.server_details_remote')}</span>
          <span className="text-foreground font-mono">{s.remoteAddress}</span>
        </div>
      )}
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('common.server_details_connected_at')}</span>
        <span className="text-foreground">{formatTime(s.connectedAt)}</span>
      </div>
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('common.server_details_uptime')}</span>
        <span className="text-foreground">{formatUptime(s.connectedAt)}</span>
      </div>
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('common.server_details_active')}</span>
        <span className={s.status === 'online' ? 'text-emerald-600 dark:text-emerald-400' : 'text-muted-foreground'}>
          {s.status === 'online' ? t('common.active_yes') : t('common.active_no')}
        </span>
      </div>
    </div>
  );
}

// ==================== Settings View ====================
function SettingsView({ theme, mode, txtMain: _txtMain, txtSec: _txtSec, settings, onToggle, settingsLoading, setMode, modeState, wsState, apiUrl, wsUrl }) {
  void _txtMain; void _txtSec;
  const { t } = useTranslation();
  const wsLabel = (() => {
    switch (wsState) {
      case ConnectionState.AUTHENTICATED: return t('common.ws_state_authenticated');
      case ConnectionState.CONNECTED: return t('common.ws_state_connected');
      case ConnectionState.CONNECTING: return t('common.ws_state_connecting');
      case ConnectionState.RECONNECTING: return t('common.ws_state_reconnecting');
      case ConnectionState.ERROR: return t('common.ws_state_error');
      default: return t('common.ws_state_disconnected');
    }
  })();

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div>
        <h2 className="text-xl font-medium text-foreground">{t('common.settings_title')}</h2>
        <p className="text-xs text-muted-foreground mt-1">{t('common.settings_subtitle')}</p>
      </div>

      <Card className="p-6 space-y-6">
        <div>
          <h3 className="text-sm font-medium mb-3 text-foreground">{t('common.settings_appearance')}</h3>
          <div className="flex items-center justify-between">
            <div>
              <span className="text-sm text-foreground">{t('common.settings_theme') || 'Theme'}</span>
              <p className="text-xs text-muted-foreground mt-0.5">{t('common.settings_local_only')}</p>
            </div>
            <div className="flex items-center p-0.5 rounded-full gap-0.5 border border-border bg-muted/60">
              <button onClick={() => setMode('light')} className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${modeState === 'light' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground hover:text-foreground'}`}>
                <Sun size={12} className="inline mr-1" />Light
              </button>
              <button onClick={() => setMode('dark')} className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${modeState === 'dark' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground hover:text-foreground'}`}>
                <Moon size={12} className="inline mr-1" />Dark
              </button>
            </div>
          </div>
        </div>

        <div className="pt-6 border-t border-border">
          <h3 className="text-sm font-medium mb-3 text-foreground">{t('common.settings_connection')}</h3>
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-sm text-foreground">{t('common.settings_api_address')}</span>
              <span className="text-xs font-mono text-muted-foreground">{apiUrl}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-foreground">{t('common.settings_ws_address')}</span>
              <span className="text-xs font-mono text-muted-foreground">{wsUrl}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-foreground">{t('common.settings_ws_state')}</span>
              <span className="text-xs text-muted-foreground">{wsLabel}</span>
            </div>
          </div>
        </div>

        <div className="pt-6 border-t border-border">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-medium text-foreground">{t('common.settings_chat_features')}</h3>
            {settingsLoading && (
              <span className="flex items-center gap-1 text-[11px] text-muted-foreground">
                <Loader2 size={11} className="animate-spin" />
                {t('common.settings_loading')}
              </span>
            )}
          </div>
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <div>
                <span className="text-sm text-foreground">{t('common.settings_filter')}</span>
                <p className="text-xs text-muted-foreground mt-0.5">{t('common.settings_filter_desc')}</p>
              </div>
              <Switch checked={settings.enableFilter} onChange={() => onToggle('enableFilter')} theme={theme} mode={mode} disabled={settingsLoading} />
            </div>
            <div className="flex items-center justify-between">
              <div>
                <span className="text-sm text-foreground">{t('common.settings_log')}</span>
                <p className="text-xs text-muted-foreground mt-0.5">{t('common.settings_log_desc')}</p>
              </div>
              <Switch checked={settings.logMessages} onChange={() => onToggle('logMessages')} theme={theme} mode={mode} disabled={settingsLoading} />
            </div>
            <div className="flex items-center justify-between">
              <div>
                <span className="text-sm text-foreground">{t('common.settings_cross_server')}</span>
                <p className="text-xs text-muted-foreground mt-0.5">{t('common.settings_cross_server_desc')}</p>
              </div>
              <Switch checked={settings.crossServerChat} onChange={() => onToggle('crossServerChat')} theme={theme} mode={mode} disabled={settingsLoading} />
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
}
