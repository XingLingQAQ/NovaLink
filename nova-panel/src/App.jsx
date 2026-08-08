import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
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
  X,
  Moon,
  Sun,
  LogOut,
  ChevronLeft,
  ChevronRight,
  MoreHorizontal,
  RefreshCw,
  Globe,
  Lock,
  Shield,
  Zap,
  Loader2,
  AlertCircle,
  Server as ServerIcon,
  Users as UsersIcon,
  MessageSquare as MessageIcon,
  Hash as HashIcon,
  ArrowUpRight,
  ArrowDownRight,
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
  buildDashboardStats,
} from './utils/adapters';

import ToastContainer from './components/ui/ToastContainer';
import Card from './components/ui/Card';
import Button from './components/ui/Button';
import Switch from './components/ui/Switch';
import NotificationDropdown from './components/dashboard/NotificationDropdown';

// Dashboard View Components
import DashboardView from './components/dashboard/DashboardView';
import MessageMonitor from './components/dashboard/MessageMonitor';
import ChannelManagement from './components/dashboard/ChannelManagement';
import PlayerManagement from './components/dashboard/PlayerManagement';
import ClientStatus from './components/dashboard/ClientStatus';

import LoginScreen from './components/auth/LoginScreen';

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
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [isMobile, setIsMobile] = useState(false);
  const [theme, setTheme] = useState('glass');
  const [mode, setMode] = useState('dark');
  const [activeTab, setActiveTab] = useState('dashboard');
  const [tabLoading, setTabLoading] = useState(false);

  // Data State — initialized empty, populated from real REST + WS.
  const [servers, setServers] = useState([]);
  const [channels, setChannels] = useState([]);
  const [players, setPlayers] = useState([]);
  const [mutedPlayers] = useState([]);
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

  // Settings State (local UI preferences only — no backend path for these).
  const [settings, setSettings] = useState({
    enableFilter: true,
    logMessages: true,
    crossServerChat: true,
  });

  // WS connection state (for showing a small indicator).
  const [wsState, setWsState] = useState(websocketService.getState());

  // UI State
  const [showNotifications, setShowNotifications] = useState(false);
  const notificationRef = useRef(null);
  const chatContainerRef = useRef(null);
  const wsHandlersRef = useRef({});

  // --- Toasts ---
  const addToast = useCallback((message, type = 'success') => {
    const id = Date.now() + Math.random();
    setToasts((prev) => [...prev, { id, message, type }]);
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), 3000);
  }, []);

  const removeToast = useCallback((id) => setToasts((prev) => prev.filter((t) => t.id !== id)), []);

  // --- Initial data fetch on mount (after auth) ---
  const fetchAllData = useCallback(async () => {
    setFetchError(null);
    try {
      const [statusRes, channelsRes, playersRes] = await Promise.all([
        api.status().catch((e) => { console.warn('[fetch] /api/status failed:', e); return null; }),
        api.getChannels().catch((e) => { console.warn('[fetch] /api/channels failed:', e); return null; }),
        api.getPlayers().catch((e) => { console.warn('[fetch] /api/players failed:', e); return null; }),
      ]);

      if (statusRes) setStatusData(statusRes);

      if (channelsRes && Array.isArray(channelsRes.channels)) {
        setChannels(channelsRes.channels.map(adaptChannel).filter(Boolean));
      }

      if (playersRes && Array.isArray(playersRes.players)) {
        setPlayers(playersRes.players.map(adaptPlayer).filter(Boolean));
      }

      // Servers (clients) are only available via WS server_status; we don't have a REST endpoint.
      // The WS broadcast will populate them shortly after connect.
      setInitialLoading(false);
    } catch (err) {
      console.error('[fetch] initial load failed:', err);
      setFetchError(err.message || '加载失败');
      setInitialLoading(false);
    }
  }, []);

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
        addToast('WebSocket 连接错误', 'error');
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
      addToast('WebSocket 连接失败: ' + (err.message || err), 'error');
    });

    return () => {
      cancelled = true;
      websocketService.off(MessageType.CHAT, handleChat);
      websocketService.off(MessageType.SERVER_STATUS, handleServerStatus);
      websocketService.off(MessageType.CHANNEL_UPDATE, handleChannelUpdate);
      websocketService.off(MessageType.PLAYER_UPDATE, handlePlayerUpdate);
      websocketService.off(MessageType.NOTIFICATION, handleNotification);
      websocketService.off('stateChange', handleStateChange);
      websocketService.disconnect();
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
      addToast('消息已发送', 'success');
    } catch (err) {
      addToast('发送失败: ' + err.message, 'error');
    }
  }, [currentUser, addToast]);

  // Mute / unmute: NO REST or WS admin-action path exists in the backend for the panel.
  // Backend mute is via AdminActionPacket (plugin -> backend), not exposed to the panel.
  // Honest disable: the PlayerManagement component renders the buttons as disabled with a tooltip.
  const handleMutePlayer = useCallback(() => {
    addToast('禁言操作需通过游戏内 /nc mute 执行，面板暂不支持', 'error');
  }, [addToast]);

  const handleUnmutePlayer = useCallback(() => {
    addToast('解除禁言需通过游戏内 /nc unmute 执行，面板暂不支持', 'error');
  }, [addToast]);

  // Reload config: NO REST or WS path exists for config reload from the panel.
  // Honest disable: ClientStatus renders the button disabled with a tooltip.
  const handleReloadConfig = useCallback(() => {
    addToast('配置重载需在服务端执行，面板暂不支持', 'error');
  }, [addToast]);

  // Channel create/edit/delete: NO REST or WS path exists for channel CRUD from the panel.
  // Honest disable: ChannelManagement renders these controls disabled with tooltips.
  const handleCreateChannel = useCallback(() => {
    addToast('频道创建需在服务端配置文件中修改，面板暂不支持', 'error');
  }, [addToast]);

  const handleEditChannel = useCallback(() => {
    addToast('频道编辑需在服务端配置文件中修改，面板暂不支持', 'error');
  }, [addToast]);

  const handleDeleteChannel = useCallback(() => {
    addToast('频道删除需在服务端配置文件中修改，面板暂不支持', 'error');
  }, [addToast]);

  // Notifications.
  const handleMarkAllRead = useCallback(() => {
    setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));
    addToast('已全部标记为已读', 'success');
  }, [addToast]);

  const handleClearNotifications = useCallback(() => {
    setNotifications([]);
    setShowNotifications(false);
    addToast('通知已清空', 'success');
  }, [addToast]);

  // Settings toggles (local UI only).
  const handleSettingToggle = useCallback((key) => {
    setSettings((prev) => ({ ...prev, [key]: !prev[key] }));
    if (navigator.vibrate) navigator.vibrate(5);
  }, []);

  const handleTabChange = useCallback((tab) => {
    setTabLoading(true);
    setActiveTab(tab);
    if (isMobile) setSidebarOpen(false);
    setTimeout(() => setTabLoading(false), 200);
  }, [isMobile]);

  // --- Derived / styling ---
  const getBackground = () => {
    if (theme === 'clean') return mode === 'dark' ? 'bg-slate-900' : 'bg-slate-50';
    return 'bg-cover bg-center bg-fixed';
  };

  const getScrollbarColors = () => {
    if (theme === 'clean') {
      if (mode === 'dark') return { thumb: '#475569', hover: '#64748b' };
      return { thumb: '#cbd5e1', hover: '#94a3b8' };
    }
    if (mode === 'dark') return { thumb: 'rgba(255,255,255,0.2)', hover: 'rgba(255,255,255,0.3)' };
    return { thumb: 'rgba(255,255,255,0.3)', hover: 'rgba(255,255,255,0.5)' };
  };

  const sbColors = getScrollbarColors();
  const txtMain = mode === 'dark' ? 'text-white' : 'text-slate-900';
  const txtSec = mode === 'dark' ? 'text-slate-400' : 'text-slate-500';

  const filteredPlayers = useMemo(() =>
    players.filter((p) =>
      (p.name || '').toLowerCase().includes(searchQuery.toLowerCase()) ||
      (p.server || '').toLowerCase().includes(searchQuery.toLowerCase())
    ), [players, searchQuery]);

  const dashboardStats = useMemo(
    () => buildDashboardStats(statusData, servers, channels, chatMessages),
    [statusData, servers, channels, chatMessages]
  );

  const navItems = [
    { id: 'dashboard', icon: LayoutDashboard, label: '仪表盘' },
    { id: 'console', icon: MessageSquare, label: '实时控制台' },
    { id: 'servers', icon: Server, label: '服务器' },
    { id: 'channels', icon: Hash, label: '频道管理' },
    { id: 'players', icon: Users, label: '玩家管理' },
    { id: 'settings', icon: Settings, label: '系统设置' },
  ];

  const wsIndicator = (() => {
    if (wsState === ConnectionState.AUTHENTICATED) return { color: 'bg-emerald-500', label: 'WS 已连接' };
    if (wsState === ConnectionState.CONNECTED || wsState === ConnectionState.CONNECTING) return { color: 'bg-amber-500', label: 'WS 连接中' };
    if (wsState === ConnectionState.RECONNECTING) return { color: 'bg-amber-500', label: 'WS 重连中' };
    if (wsState === ConnectionState.ERROR) return { color: 'bg-red-500', label: 'WS 错误' };
    return { color: 'bg-slate-500', label: 'WS 未连接' };
  })();

  return (
    <div className={`w-full overflow-hidden font-sans transition-colors duration-700 ${getBackground()} relative`} style={{ minHeight: '100dvh', '--scrollbar-thumb': sbColors.thumb, '--scrollbar-thumb-hover': sbColors.hover }}>
      <ToastContainer toasts={toasts} removeToast={removeToast} />

      {theme === 'glass' && (
        <>
          <div className="fixed inset-0 z-0 transition-opacity duration-1000 bg-gradient-to-br from-slate-950 via-slate-900 to-sky-900" style={{ height: '100dvh', width: '100vw' }} />
          <div className={`fixed inset-0 z-0 transition-all duration-700 ${mode === 'dark' ? 'bg-black/50' : 'bg-white/20'}`} />
          <div className="fixed top-[-10%] right-[-10%] w-[500px] h-[500px] bg-sky-500/20 rounded-full blur-[120px] animate-pulse z-0 pointer-events-none mix-blend-overlay" />
          <div className="fixed bottom-[-10%] left-[-10%] w-[600px] h-[600px] bg-purple-500/20 rounded-full blur-[120px] animate-pulse z-0 pointer-events-none mix-blend-overlay" style={{ animationDelay: '2s' }} />
        </>
      )}

      <div className="relative z-10 flex h-screen w-full" style={{ height: '100dvh' }}>
        {isMobile && sidebarOpen && <div className="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm transition-opacity duration-300" onClick={() => setSidebarOpen(false)} />}

        {/* Sidebar */}
        <aside className={`fixed lg:relative z-50 h-full flex flex-col transition-all duration-500 ease-[cubic-bezier(0.25,0.8,0.25,1)] shadow-2xl lg:shadow-none ${theme === 'clean' ? (mode === 'dark' ? 'bg-slate-800 border-r border-slate-700' : 'bg-white border-r border-slate-200') : (mode === 'dark' ? 'bg-black/40 border-r border-white/10 backdrop-blur-2xl' : 'bg-white/40 border-r border-white/30 backdrop-blur-2xl')} ${isMobile ? (sidebarOpen ? 'translate-x-0 w-72' : '-translate-x-full w-72') : (sidebarOpen ? 'w-64 translate-x-0' : 'w-20 translate-x-0')}`}>
          <div className="flex-1 flex flex-col p-4 overflow-hidden">
            <div className={`flex items-center mb-10 h-10 shrink-0 transition-all duration-500 ${!isMobile && !sidebarOpen ? 'justify-center px-0' : 'gap-3 px-2'}`}>
              <div className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 shadow-lg transition-transform duration-300 ${theme === 'clean' ? 'bg-sky-500 text-white' : 'bg-gradient-to-br from-sky-400 to-blue-500 text-white'}`}>
                <Zap size={20} />
              </div>
              <div className={`overflow-hidden whitespace-nowrap transition-all duration-500 ${!isMobile && !sidebarOpen ? 'w-0 opacity-0' : 'w-40 opacity-100'}`}>
                <h1 className={`font-bold text-xl ${txtMain}`}>Nova<span className="font-light">Panel</span></h1>
              </div>
            </div>
            <nav className="flex-1 space-y-2 overflow-y-auto scrollbar-hide">
              {navItems.map((item) => (
                <button key={item.id} onClick={() => handleTabChange(item.id)} className={`w-full flex items-center gap-3 px-3 py-3 rounded-xl transition-all duration-300 group relative ${activeTab === item.id ? (theme === 'clean' ? 'bg-sky-50 text-sky-600' : (mode === 'dark' ? 'bg-white/10 text-white shadow-lg border border-white/10' : 'bg-white/40 text-slate-900 shadow-lg border border-white/40')) : (mode === 'dark' ? 'text-slate-400 hover:bg-white/5 hover:text-slate-200' : 'text-slate-500 hover:bg-slate-100 hover:text-slate-800')}`} title={!sidebarOpen && !isMobile ? item.label : ''}>
                  <div className="shrink-0"><item.icon size={20} /></div>
                  <span className={`whitespace-nowrap transition-all duration-500 ${!isMobile && !sidebarOpen ? 'opacity-0 w-0 overflow-hidden' : 'opacity-100 w-auto'}`}>{item.label}</span>
                  {activeTab === item.id && <div className={`absolute left-0 top-1/2 -translate-y-1/2 w-1 h-6 rounded-r-full ${theme === 'clean' ? 'bg-sky-500' : 'bg-white'}`} />}
                </button>
              ))}
            </nav>
            <div className={`mt-auto rounded-xl flex items-center transition-all duration-500 overflow-hidden shrink-0 ${!isMobile && !sidebarOpen ? 'p-1.5 justify-center' : 'p-3'} ${theme === 'clean' ? 'bg-slate-100/50' : 'bg-white/10 border border-white/10'}`}>
              <div className={`shrink-0 w-10 h-10 rounded-full flex items-center justify-center bg-gradient-to-br from-sky-400 to-blue-500 text-white font-semibold ${!isMobile && !sidebarOpen ? '' : 'mr-3'}`} title={(currentUser && currentUser.username) || '用户'}>
                {((currentUser && currentUser.username) || 'U')[0].toUpperCase()}
              </div>
              <div className={`overflow-hidden transition-all duration-500 flex-1 min-w-0 ${!isMobile && !sidebarOpen ? 'w-0 opacity-0' : 'w-auto opacity-100'}`}>
                <p className={`text-sm font-semibold whitespace-nowrap ${txtMain}`}>{(currentUser && currentUser.username) || '用户'}</p>
                <p className={`text-xs whitespace-nowrap ${txtSec}`}>{(currentUser && currentUser.role) || ''}</p>
              </div>
              <button onClick={onLogout} className={`${txtSec} hover:text-red-400 transition-all duration-500 shrink-0 ${!isMobile && !sidebarOpen ? 'w-0 opacity-0 overflow-hidden' : 'w-auto opacity-100'}`} title="退出登录">
                <LogOut size={18} />
              </button>
            </div>
          </div>
        </aside>

        {/* Main Content */}
        <main className="flex-1 flex flex-col h-full overflow-hidden relative transition-all duration-500">
          {/* Header */}
          <header className={`h-16 px-4 md:px-6 flex items-center justify-between shrink-0 z-30 ${theme === 'clean' ? (mode === 'dark' ? 'bg-slate-900/80 backdrop-blur-md' : 'bg-white/80 backdrop-blur-md') : 'bg-transparent'}`}>
            <div className="flex items-center gap-4">
              <button onClick={() => setSidebarOpen(!sidebarOpen)} className={`p-2 rounded-lg hover:bg-current/10 transition-transform active:scale-95 ${txtSec}`}>
                {isMobile ? <Menu size={24} /> : (sidebarOpen ? <ChevronLeft size={24} /> : <ChevronRight size={24} />)}
              </button>
              <div className={`hidden md:flex items-center gap-2 px-4 py-2 rounded-full transition-all ${theme === 'clean' ? (mode === 'dark' ? 'bg-slate-800' : 'bg-slate-100') : (mode === 'dark' ? 'bg-black/20 border border-white/10' : 'bg-white/20 border border-white/30')}`}>
                <Search size={18} className={txtSec} />
                <input type="text" placeholder="搜索..." className="bg-transparent border-none outline-none text-sm w-32 lg:w-48 placeholder:text-slate-400" style={{ color: mode === 'dark' ? 'white' : 'black' }} value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} />
              </div>
            </div>
            <div className="flex items-center gap-3 md:gap-4">
              {/* WS status indicator */}
              <div className="hidden sm:flex items-center gap-2 px-2.5 py-1 rounded-full bg-white/5 border border-white/10" title={wsIndicator.label}>
                <span className={`w-2 h-2 rounded-full ${wsIndicator.color} ${wsState === ConnectionState.CONNECTING || wsState === ConnectionState.RECONNECTING ? 'animate-pulse' : ''}`} />
                <span className={`text-xs ${txtSec}`}>{wsIndicator.label}</span>
              </div>
              <div className="relative" ref={notificationRef}>
                <button onClick={() => setShowNotifications(!showNotifications)} className={`p-2 rounded-full relative transition-transform hover:scale-110 ${txtSec} hover:bg-current/10`}>
                  <Bell size={20} />
                  {notifications.some((n) => !n.read) && (
                    <>
                      <span className="absolute top-2 right-2 w-2 h-2 bg-red-500 rounded-full animate-ping" />
                      <span className="absolute top-2 right-2 w-2 h-2 bg-red-500 rounded-full" />
                    </>
                  )}
                </button>
                <NotificationDropdown isOpen={showNotifications} onClose={() => setShowNotifications(false)} theme={theme} mode={mode} notifications={notifications} onMarkAllRead={handleMarkAllRead} onClearAll={handleClearNotifications} />
              </div>
              <div className={`flex items-center p-1 rounded-full gap-1 ${theme === 'clean' ? (mode === 'dark' ? 'bg-slate-800' : 'bg-slate-200') : 'bg-black/20 border border-white/10 backdrop-blur-md'}`}>
                <button onClick={() => setMode('light')} className={`p-1.5 rounded-full transition-all ${mode === 'light' ? 'bg-white shadow-sm text-yellow-500' : 'text-slate-400 hover:text-slate-200'}`}><Sun size={16} /></button>
                <button onClick={() => setMode('dark')} className={`p-1.5 rounded-full transition-all ${mode === 'dark' ? 'bg-slate-700 text-sky-300 shadow-sm' : 'text-slate-400 hover:text-slate-600'}`}><Moon size={16} /></button>
              </div>
            </div>
          </header>

          {/* Content Area */}
          <div className="flex-1 overflow-y-auto p-4 md:p-6 custom-scrollbar">
            <div className="max-w-7xl mx-auto space-y-6">
              {initialLoading ? (
                <div className="h-96 flex flex-col items-center justify-center gap-3">
                  <Loader2 size={40} className={`animate-spin ${theme === 'clean' ? 'text-sky-500' : 'text-white'}`} />
                  <p className={`text-sm ${txtSec}`}>正在加载 NovaLink 数据...</p>
                </div>
              ) : fetchError && channels.length === 0 && players.length === 0 ? (
                <div className="h-96 flex flex-col items-center justify-center gap-4">
                  <AlertCircle size={40} className="text-rose-400" />
                  <p className={`text-sm ${txtMain}`}>加载失败: {fetchError}</p>
                  <Button theme={theme} mode={mode} variant="primary" onClick={fetchAllData}>
                    <RefreshCw size={16} /> 重试
                  </Button>
                </div>
              ) : tabLoading ? (
                <div className="h-96 flex items-center justify-center">
                  <div className={`w-12 h-12 border-4 rounded-full animate-spin ${theme === 'clean' ? 'border-sky-500 border-t-transparent' : 'border-white border-t-transparent'}`} />
                </div>
              ) : (
                <>
                  {/* Dashboard - System Overview */}
                  {activeTab === 'dashboard' && (
                    <DashboardView
                      theme={theme}
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

                  {/* Console - Real-time Message Monitor */}
                  {activeTab === 'console' && (
                    <MessageMonitor
                      theme={theme}
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

                  {/* Servers - Client Status */}
                  {activeTab === 'servers' && (
                    <ClientStatus
                      theme={theme}
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      servers={servers}
                      onReloadConfig={handleReloadConfig}
                    />
                  )}

                  {/* Channels - Channel Management */}
                  {activeTab === 'channels' && (
                    <ChannelManagement
                      theme={theme}
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      channels={channels}
                      onCreateChannel={handleCreateChannel}
                      onEditChannel={handleEditChannel}
                      onDeleteChannel={handleDeleteChannel}
                    />
                  )}

                  {/* Players - Player Management */}
                  {activeTab === 'players' && (
                    <PlayerManagement
                      theme={theme}
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      players={filteredPlayers}
                      mutedPlayers={mutedPlayers}
                      onMutePlayer={handleMutePlayer}
                      onUnmutePlayer={handleUnmutePlayer}
                    />
                  )}

                  {/* Settings */}
                  {activeTab === 'settings' && (
                    <SettingsView
                      theme={theme} mode={mode} txtMain={txtMain} txtSec={txtSec}
                      settings={settings} onToggle={handleSettingToggle}
                      setTheme={setTheme}
                      wsState={wsState}
                      apiUrl={getApiBaseUrl()}
                      wsUrl={getWsUrl()}
                    />
                  )}
                </>
              )}
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}

// ==================== Settings View ====================
function SettingsView({ theme, mode, txtMain, txtSec, settings, onToggle, setTheme, wsState, apiUrl, wsUrl }) {
  const wsLabel = (() => {
    switch (wsState) {
      case ConnectionState.AUTHENTICATED: return '已认证';
      case ConnectionState.CONNECTED: return '已连接';
      case ConnectionState.CONNECTING: return '连接中';
      case ConnectionState.RECONNECTING: return '重连中';
      case ConnectionState.ERROR: return '错误';
      default: return '未连接';
    }
  })();

  return (
    <div className="max-w-2xl mx-auto space-y-6 animate-in fade-in duration-500">
      <div>
        <h2 className={`text-2xl font-bold ${txtMain}`}>系统设置</h2>
        <p className={`text-sm ${txtSec} mt-1`}>配置 NovaPanel 界面参数</p>
      </div>

      <Card theme={theme} mode={mode} className="p-6 space-y-6">
        <div>
          <h3 className={`text-lg font-semibold mb-4 ${txtMain}`}>外观</h3>
          <div className="grid grid-cols-2 gap-4">
            <div onClick={() => setTheme('clean')} className={`cursor-pointer rounded-xl border-2 overflow-hidden transition-all ${theme === 'clean' ? 'border-sky-500 scale-[1.02]' : 'border-transparent opacity-70 hover:opacity-100'}`}>
              <div className="h-24 bg-slate-100 p-3">
                <div className="w-full h-full bg-white shadow-sm rounded-lg flex">
                  <div className="w-1/4 bg-slate-50 border-r h-full"></div>
                  <div className="w-3/4 p-2">
                    <div className="w-1/2 h-2 bg-sky-500 rounded mb-2"></div>
                  </div>
                </div>
              </div>
              <div className={`p-3 ${mode === 'dark' ? 'bg-slate-800' : 'bg-white'}`}>
                <span className={`text-sm font-medium ${txtMain}`}>简洁模式</span>
              </div>
            </div>
            <div onClick={() => setTheme('glass')} className={`cursor-pointer rounded-xl border-2 overflow-hidden transition-all ${theme === 'glass' ? 'border-sky-500 scale-[1.02]' : 'border-transparent opacity-70 hover:opacity-100'}`}>
              <div className="h-24 relative p-3 bg-gradient-to-br from-slate-800 to-sky-800">
                <div className="absolute inset-0 bg-black/30"></div>
                <div className="relative w-full h-full bg-white/20 backdrop-blur-md border border-white/30 rounded-lg flex z-10">
                  <div className="w-1/4 bg-white/10 border-r border-white/10 h-full"></div>
                </div>
              </div>
              <div className={`p-3 ${mode === 'dark' ? 'bg-slate-800' : 'bg-white'}`}>
                <span className={`text-sm font-medium ${txtMain}`}>玻璃模式</span>
              </div>
            </div>
          </div>
        </div>

        <div className="pt-6 border-t border-gray-200/10">
          <h3 className={`text-lg font-semibold mb-4 ${txtMain}`}>连接状态</h3>
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className={txtMain}>API 地址</span>
              <span className={`text-sm font-mono ${txtSec}`}>{apiUrl}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className={txtMain}>WebSocket 地址</span>
              <span className={`text-sm font-mono ${txtSec}`}>{wsUrl}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className={txtMain}>WebSocket 状态</span>
              <span className={`text-sm ${txtSec}`}>{wsLabel}</span>
            </div>
          </div>
        </div>

        <div className="pt-6 border-t border-gray-200/10">
          <h3 className={`text-lg font-semibold mb-4 ${txtMain}`}>聊天功能 (仅本地界面)</h3>
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <span className={txtMain}>敏感词过滤</span>
                <p className={`text-xs ${txtSec}`}>面板界面设置，不影响后端</p>
              </div>
              <Switch checked={settings.enableFilter} onChange={() => onToggle('enableFilter')} theme={theme} mode={mode} />
            </div>
            <div className="flex items-center justify-between">
              <div>
                <span className={txtMain}>消息日志</span>
                <p className={`text-xs ${txtSec}`}>面板界面设置，不影响后端</p>
              </div>
              <Switch checked={settings.logMessages} onChange={() => onToggle('logMessages')} theme={theme} mode={mode} />
            </div>
            <div className="flex items-center justify-between">
              <div>
                <span className={txtMain}>跨服聊天</span>
                <p className={`text-xs ${txtSec}`}>面板界面设置，不影响后端</p>
              </div>
              <Switch checked={settings.crossServerChat} onChange={() => onToggle('crossServerChat')} theme={theme} mode={mode} />
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
}
