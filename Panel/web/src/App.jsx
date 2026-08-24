import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import {
  RefreshCw,
  Loader2,
  AlertCircle,
  Server as ServerIcon,
  Users as UsersIcon,
  MessageSquare as MessageIcon,
  Hash as HashIcon,
} from 'lucide-react';

import authService from './services/auth';
import websocketService from './services/websocket';
import { getApiBaseUrl, getWsUrl, clearConnectionUrls } from './services/api';
import { buildDashboardStats } from './utils/adapters';
import { can } from './lib/permissions';
import { useDashboardData } from './hooks/useDashboardData';
import { useWsOrchestration } from './hooks/useWsOrchestration';

import ToastContainer from './components/ui/ToastContainer';
import Button from './components/ui/Button';

import Sidebar from './components/dashboard/Sidebar';
import TopBar from './components/dashboard/TopBar';
import NotificationListModal from './components/dashboard/NotificationListModal';
import { ServerDetailsModal, DisconnectConfirmModal } from './components/dashboard/ServerModals';

// Dashboard View Components
import DashboardView from './components/dashboard/DashboardView';
import MessageMonitor from './components/dashboard/MessageMonitor';
import MessageHistory from './components/dashboard/MessageHistory';
import ConsoleCommand from './components/dashboard/ConsoleCommand';
import ChannelManagement from './components/dashboard/ChannelManagement';
import PlayerManagement from './components/dashboard/PlayerManagement';
import AnnouncementManagement from './components/dashboard/AnnouncementManagement';
import FilterManagement from './components/dashboard/FilterManagement';
import ClientStatus from './components/dashboard/ClientStatus';
import WebhookManagement from './components/dashboard/WebhookManagement';
import SettingsView from './components/dashboard/SettingsView';
import AuditLog from './components/dashboard/AuditLog';
import ModerationManagement from './components/dashboard/ModerationManagement';
import AppealQueue from './components/dashboard/AppealQueue';
import ReportCreateModal from './components/dashboard/ReportCreateModal';
import StatusPage from './components/dashboard/StatusPage';
import ConfigHistory from './components/dashboard/ConfigHistory';
import CampaignManagement from './components/dashboard/CampaignManagement';

import LoginScreen from './components/auth/LoginScreen';

const THEME_STORAGE_KEY = 'nova-panel-theme';

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
  const { t } = useTranslation();
  const role = currentUser && currentUser.role;
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
  const [liveChannelSelection, setLiveChannelSelection] = useState('all');

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

  // WS-fed state that lives here (not REST-fetched): servers + chat stream.
  const [servers, setServers] = useState([]);
  const [chatMessages, setChatMessages] = useState([]);
  const [toasts, setToasts] = useState([]);
  const [consoleAutoScroll, setConsoleAutoScroll] = useState(true);

  // UI state
  const [showNotificationList, setShowNotificationList] = useState(false);
  const chatContainerRef = useRef(null);
  // Server details modal (opened from ClientStatus "view details").
  const [serverDetailTarget, setServerDetailTarget] = useState(null);
  // Disconnect confirm modal (opened from ClientStatus "disconnect").
  const [disconnectTarget, setDisconnectTarget] = useState(null);
  // PANEL-007: report-create modal (opened from the moderation page).
  const [showReportModal, setShowReportModal] = useState(false);

  // --- Toasts ---
  const addToast = useCallback((message, type = 'success') => {
    const id = Date.now() + Math.random();
    setToasts((prev) => [...prev, { id, message, type }]);
    setTimeout(() => setToasts((prev) => prev.filter((tt) => tt.id !== id)), 3000);
  }, []);

  const removeToast = useCallback((id) => setToasts((prev) => prev.filter((tt) => tt.id !== id)), []);

  // --- Data layer (REST fetchers + mutation handlers + state) ---
  const data = useDashboardData({ addToast, currentUser });

  // --- WebSocket orchestration (connect / auth / subscribe / snapshot) ---
  const { wsState, handleManualReconnect } = useWsOrchestration({
    channels: data.channels,
    activeTab,
    selectedChannelId: liveChannelSelection,
    setServers,
    setChannels: data.setChannels,
    setPlayers: data.setPlayers,
    setChatMessages,
    onNotification: data.handleWsNotification,
    onSettingsUpdate: data.handleWsSettingsUpdate,
    addToast,
  });

  // Stable references pulled out of `data` for use inside useCallback deps.
  const { handleDisconnectServer, setApiUnreadCount, fetchWebhooks } = data;

  // Auto-scroll chat container (console tab).
  useEffect(() => {
    if (consoleAutoScroll && chatContainerRef.current) {
      chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
    }
  }, [chatMessages, consoleAutoScroll]);

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

  // --- UI handlers ---

  // Keep the bell badge's unread count in sync with the API-sourced count
  // reported by the NotificationListModal after each fetch/action.
  const handleUnreadCountChange = useCallback((count) => {
    setApiUnreadCount(count);
  }, [setApiUnreadCount]);

  const handleTabChange = useCallback((tab) => {
    setActiveTab(tab);
    if (tab !== 'messages') setLiveChannelSelection('all');
    if (isMobile) setSidebarOpen(false);
    // Lazy-load webhooks when the tab is first opened.
    if (tab === 'webhooks') {
      fetchWebhooks();
    }
  }, [isMobile, fetchWebhooks]);

  // --- Derived / styling ---
  // Token-based text/background classes so the whole panel re-themes via the
  // .dark class + the oklch CSS variables in index.css.
  const txtMain = 'text-foreground';
  const txtSec = 'text-muted-foreground';

  const dashboardStats = useMemo(
    () => buildDashboardStats(data.statusData, servers, data.channels, chatMessages),
    [data.statusData, servers, data.channels, chatMessages]
  );

  return (
    <div className="w-full overflow-hidden font-sans bg-background text-foreground relative" style={{ minHeight: '100dvh' }}>
      <ToastContainer toasts={toasts} removeToast={removeToast} />

      <div className="relative flex h-screen w-full" style={{ height: '100dvh' }}>
        <Sidebar
          activeTab={activeTab}
          onTabChange={handleTabChange}
          sidebarOpen={sidebarOpen}
          isMobile={isMobile}
          onOverlayClick={() => setSidebarOpen(false)}
          currentUser={currentUser}
          onLogout={onLogout}
          role={role}
        />

        {/* Main Content */}
        <main className="flex-1 flex flex-col h-full overflow-hidden relative transition-all duration-300">
          <TopBar
            sidebarOpen={sidebarOpen}
            onToggleSidebar={() => setSidebarOpen(!sidebarOpen)}
            isMobile={isMobile}
            mode={mode}
            setMode={setMode}
            wsState={wsState}
            onManualReconnect={handleManualReconnect}
            notifications={data.notifications}
            apiUnreadCount={data.apiUnreadCount}
            onMarkAllRead={data.handleMarkAllRead}
            onClearAll={data.handleClearNotifications}
            onOpenList={() => setShowNotificationList(true)}
          />

          {/* Content Area */}
          <div className="flex-1 overflow-y-auto p-4 md:p-6">
            <div className="max-w-7xl mx-auto space-y-6">
              {data.initialLoading ? (
                <div className="h-96 flex flex-col items-center justify-center gap-3">
                  <Loader2 size={28} className="animate-spin text-muted-foreground" />
                  <p className="text-xs text-muted-foreground">{t('common.loading_data')}</p>
                </div>
              ) : data.fetchError && data.channels.length === 0 && data.players.length === 0 ? (
                <div className="h-96 flex flex-col items-center justify-center gap-4">
                  <AlertCircle size={28} className="text-destructive" />
                  <p className="text-sm text-foreground">{t('common.load_failed_msg', { error: data.fetchError })}</p>
                  <Button variant="outline" onClick={data.fetchAllData}>
                    <RefreshCw size={14} /> {t('common.retry')}
                  </Button>
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
                      channels={data.channels}
                      players={data.players}
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
                      channels={data.channels}
                      onChannelSelectionChange={setLiveChannelSelection}
                      onClearMessages={() => setChatMessages([])}
                      onSendMessage={data.handleSendMessage}
                      chatContainerRef={chatContainerRef}
                      consoleAutoScroll={consoleAutoScroll}
                      setConsoleAutoScroll={setConsoleAutoScroll}
                      role={role}
                    />
                  )}

                  {/* History - Persisted Message History (all roles) */}
                  {activeTab === 'history' && (
                    <MessageHistory
                      theme="clean"
                      mode={mode}
                      channels={data.channels}
                      servers={servers}
                    />
                  )}

                  {/* Console - Backend Command Executor (SUPER_ADMIN only) */}
                  {activeTab === 'console' && can(role, 'console') && (
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
                      onReloadConfig={data.handleReloadConfig}
                      onDisconnectServer={(server) => setDisconnectTarget(server)}
                      onViewServerDetails={(server) => setServerDetailTarget(server)}
                      role={role}
                    />
                  )}

                  {/* Channels - Channel Management */}
                  {activeTab === 'channels' && (
                    <ChannelManagement
                      theme="clean"
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      channels={data.channels}
                      onCreateChannel={data.handleCreateChannel}
                      onEditChannel={data.handleEditChannel}
                      onDeleteChannel={data.handleDeleteChannel}
                      onInviteChannel={data.handleInviteChannel}
                      role={role}
                    />
                  )}

                  {/* Players - Player Management */}
                  {activeTab === 'players' && (
                    <PlayerManagement
                      theme="clean"
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      players={data.players}
                      channels={data.channels}
                      mutedPlayers={data.mutedPlayers}
                      bannedPlayers={data.bannedPlayers}
                      onMutePlayer={data.handleMutePlayer}
                      onUnmutePlayer={data.handleUnmutePlayer}
                      onKickPlayer={data.handleKickPlayer}
                      onBanPlayer={data.handleBanPlayer}
                      onUnbanPlayer={data.handleUnbanPlayer}
                      role={role}
                    />
                  )}

                  {/* Announcements - Announcement Management (ADMIN / SUPER_ADMIN) */}
                  {activeTab === 'announcements' && can(role, 'announcements.manage') && (
                    <AnnouncementManagement
                      theme="clean"
                      mode={mode}
                      channels={data.channels}
                      onToast={addToast}
                    />
                  )}

                  {/* Filter - Word Filter Management (ADMIN / SUPER_ADMIN) */}
                  {activeTab === 'filter' && can(role, 'filter.manage') && (
                    <FilterManagement
                      theme="clean"
                      mode={mode}
                      onToast={addToast}
                    />
                  )}

                  {/* Webhooks - Webhook Management */}
                  {activeTab === 'webhooks' && (
                    <WebhookManagement
                      theme="clean"
                      mode={mode}
                      txtMain={txtMain}
                      txtSec={txtSec}
                      webhooks={data.webhooks}
                      loading={data.webhooksLoading}
                      onCreateWebhook={data.handleCreateWebhook}
                      onDeleteWebhook={data.handleDeleteWebhook}
                      onUpdateWebhook={data.handleUpdateWebhook}
                      onTestWebhook={data.handleTestWebhook}
                      role={role}
                    />
                  )}

                  {/* Settings */}
                  {activeTab === 'settings' && (
                    <SettingsView
                      theme="clean" mode={mode}
                      settings={data.settings} onToggle={data.handleSettingToggle}
                      onChange={data.handleSettingChange}
                      settingsLoading={data.settingsLoading}
                      setMode={setMode}
                      modeState={mode}
                      wsState={wsState}
                      apiUrl={getApiBaseUrl()}
                      wsUrl={getWsUrl()}
                      role={role}
                    />
                  )}

                  {/* Audit Log (ADMIN / SUPER_ADMIN) */}
                  {activeTab === 'audit' && can(role, 'audit.view') && (
                    <AuditLog theme="clean" mode={mode} />
                  )}

                  {/* Config history (§11.6 Project 20 / PANEL proposal 10) —
                      masked snapshot browse + diff + rollback. ADMIN+ see it;
                      rollback is SUPER_ADMIN-only (gated inside the view). */}
                  {activeTab === 'configHistory' && can(role, 'settings.history') && (
                    <ConfigHistory theme="clean" mode={mode} role={role} />
                  )}

                  {/* Campaigns (§11.6 提案 06 / item 19) — orchestrated,
                      scheduled, revocable announcements. ADMIN+ entry under the
                      announcements capability; create/schedule/activate are
                      ADMIN mutations, revoke is SUPER_ADMIN-only (gated inside
                      the view via the `campaign.revoke` capability). */}
                  {activeTab === 'campaigns' && can(role, 'announcements.manage') && (
                    <CampaignManagement
                      theme="clean"
                      mode={mode}
                      channels={data.channels}
                      onToast={addToast}
                      role={role}
                    />
                  )}

                  {/* Moderation cases (PANEL-007, ADMIN / SUPER_ADMIN).
                      VIEWER never reaches this: sidebar entry + this route are
                      both capability-gated. The page holds the report-create
                      modal trigger. */}
                  {activeTab === 'moderation' && can(role, 'moderation.view') && (
                    <>
                      <div className="flex justify-end">
                        {can(role, 'moderation.manage') && (
                          <Button
                            variant="outline"
                            theme="clean"
                            mode={mode}
                            onClick={() => setShowReportModal(true)}
                          >
                            {t('moderation.new_report')}
                          </Button>
                        )}
                      </div>
                      <ModerationManagement
                        theme="clean"
                        mode={mode}
                        onToast={addToast}
                        role={role}
                      />
                    </>
                  )}

                  {/* Appeals queue (PANEL-007, ADMIN / SUPER_ADMIN). */}
                  {activeTab === 'appeals' && can(role, 'appeals.review') && (
                    <AppealQueue
                      theme="clean"
                      mode={mode}
                      onToast={addToast}
                      role={role}
                    />
                  )}

                  {/* Status page (proposal 09 / §11.6 project 17). Read-only
                      observability aggregate. No capability gate — every role
                      (including VIEWER) can see it, mirroring the unauth
                      /api/health + VIEWER-readable /api/metrics backend
                      contract. */}
                  {activeTab === 'status' && (
                    <StatusPage theme="clean" mode={mode} />
                  )}
                </>
              )}
            </div>
          </div>

          {/* Server Details Modal */}
          <ServerDetailsModal
            server={serverDetailTarget}
            mode={mode}
            onClose={() => setServerDetailTarget(null)}
          />

          {/* Disconnect Server Confirm Modal */}
          <DisconnectConfirmModal
            target={disconnectTarget}
            mode={mode}
            onClose={() => setDisconnectTarget(null)}
            onDisconnect={handleDisconnectServer}
          />

          {/* Notification List Modal (full paginated history) */}
          <NotificationListModal
            isOpen={showNotificationList}
            onClose={() => setShowNotificationList(false)}
            theme="clean"
            mode={mode}
            onUnreadCountChange={handleUnreadCountChange}
            onToast={addToast}
          />

          {/* PANEL-007: report-create modal (opened from the moderation page). */}
          <ReportCreateModal
            isOpen={showReportModal}
            onClose={() => setShowReportModal(false)}
            theme="clean"
            mode={mode}
            onToast={addToast}
          />
        </main>
      </div>
    </div>
  );
}
