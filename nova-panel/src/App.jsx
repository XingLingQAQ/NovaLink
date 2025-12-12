import React, { useState, useEffect, useRef } from 'react';
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
  ArrowUpRight,
  ArrowDownRight,
  MoreHorizontal,
  Check,
  Plus,
  Trash2,
  Edit,
  RefreshCw,
  Filter,
  Send,
  Volume2,
  VolumeX,
  Globe,
  Lock,
  Shield,
  Zap
} from 'lucide-react';

import { 
  CONNECTED_SERVERS, 
  CHANNELS, 
  ONLINE_PLAYERS, 
  MUTED_PLAYERS,
  DASHBOARD_STATS, 
  CHAT_MESSAGES, 
  SYSTEM_NOTIFICATIONS,
  ANNOUNCEMENTS
} from './data/mockData';
import ToastContainer from './components/ui/ToastContainer';
import Card from './components/ui/Card';
import Button from './components/ui/Button';
import Switch from './components/ui/Switch';
import CustomSelect from './components/ui/CustomSelect';
import Modal from './components/ui/Modal';
import Avatar from './components/ui/Avatar';
import NotificationDropdown from './components/dashboard/NotificationDropdown';

// Dashboard View Components
import DashboardView from './components/dashboard/DashboardView';
import MessageMonitor from './components/dashboard/MessageMonitor';
import ChannelManagement from './components/dashboard/ChannelManagement';
import PlayerManagement from './components/dashboard/PlayerManagement';
import ClientStatus from './components/dashboard/ClientStatus';

export default function App() {
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [isMobile, setIsMobile] = useState(false);
  const [theme, setTheme] = useState('glass');
  const [mode, setMode] = useState('dark');
  const [activeTab, setActiveTab] = useState('dashboard');
  const [loading, setLoading] = useState(false);

  // Data State
  const [servers, setServers] = useState(CONNECTED_SERVERS);
  const [channels, setChannels] = useState(CHANNELS);
  const [players, setPlayers] = useState(ONLINE_PLAYERS);
  const [mutedPlayers, setMutedPlayers] = useState(MUTED_PLAYERS);
  const [chatMessages, setChatMessages] = useState(CHAT_MESSAGES);
  const [notifications, setNotifications] = useState(SYSTEM_NOTIFICATIONS);
  const [announcements, setAnnouncements] = useState(ANNOUNCEMENTS);
  const [toasts, setToasts] = useState([]);

  // Search & Filter State
  const [searchQuery, setSearchQuery] = useState('');
  const [chatFilter, setChatFilter] = useState('all');
  const [consoleAutoScroll, setConsoleAutoScroll] = useState(true);

  // Settings State
  const [settings, setSettings] = useState({
    enableFilter: true,
    logMessages: true,
    crossServerChat: true
  });

  // Modal States
  const [showChannelModal, setShowChannelModal] = useState(false);
  const [showMuteModal, setShowMuteModal] = useState(false);
  const [showAnnouncementModal, setShowAnnouncementModal] = useState(false);
  const [editingChannel, setEditingChannel] = useState(null);
  const [muteTarget, setMuteTarget] = useState({ name: '', reason: '', duration: '1h' });

  // UI State
  const [showNotifications, setShowNotifications] = useState(false);
  const notificationRef = useRef(null);
  const chatContainerRef = useRef(null);

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (notificationRef.current && !notificationRef.current.contains(event.target)) {
        setShowNotifications(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // 模拟实时消息
  useEffect(() => {
    const interval = setInterval(() => {
      const newMsg = {
        id: Date.now(),
        time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        server: servers.filter(s => s.status === 'online')[Math.floor(Math.random() * 5)]?.name || 'Lobby-1',
        player: `Player_${Math.floor(Math.random() * 1000)}`,
        channel: 'global',
        content: ['大家好！', '有人在吗？', '一起玩吧', '这个服务器真棒', '新人报到'][Math.floor(Math.random() * 5)],
        platform: Math.random() > 0.3 ? 'Java' : 'Bedrock'
      };
      setChatMessages(prev => [...prev.slice(-99), newMsg]);
    }, 5000);
    return () => clearInterval(interval);
  }, [servers]);

  // 自动滚动聊天
  useEffect(() => {
    if (consoleAutoScroll && chatContainerRef.current) {
      chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
    }
  }, [chatMessages, consoleAutoScroll]);

  const addToast = (message, type = 'success') => {
    const id = Date.now();
    setToasts(prev => [...prev, { id, message, type }]);
    setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 3000);
  };

  const removeToast = (id) => setToasts(prev => prev.filter(t => t.id !== id));

  // --- Actions ---
  const handleMutePlayer = () => {
    if (!muteTarget.name) {
      addToast("请输入玩家名称", "error");
      return;
    }
    const newMute = {
      uuid: `mute-${Date.now()}`,
      name: muteTarget.name,
      reason: muteTarget.reason || '违规行为',
      expireTime: muteTarget.duration === 'permanent' ? '永久' : new Date(Date.now() + parseInt(muteTarget.duration) * 3600000).toLocaleString('zh-CN'),
      operator: 'Admin'
    };
    setMutedPlayers([...mutedPlayers, newMute]);
    setShowMuteModal(false);
    setMuteTarget({ name: '', reason: '', duration: '1h' });
    addToast(`已禁言 ${newMute.name}`, "success");
  };

  const handleUnmutePlayer = (uuid) => {
    setMutedPlayers(mutedPlayers.filter(m => m.uuid !== uuid));
    addToast("已解除禁言", "success");
  };

  const handleReloadConfig = () => {
    addToast("正在重载配置...", "loading");
    setTimeout(() => addToast("配置重载完成", "success"), 1500);
  };

  const handleMarkAllRead = () => {
    setNotifications(notifications.map(n => ({ ...n, read: true })));
    addToast("已全部标记为已读", "success");
  };

  const handleClearNotifications = () => {
    setNotifications([]);
    setShowNotifications(false);
    addToast("通知已清空", "success");
  };

  const handleSettingToggle = (key) => {
    setSettings(prev => ({ ...prev, [key]: !prev[key] }));
    if (navigator.vibrate) navigator.vibrate(5);
  };

  const handleLogout = () => {
    if (window.confirm("确定要退出登录吗？")) {
      addToast("正在退出...", "loading");
      setTimeout(() => window.location.reload(), 1500);
    }
  };

  // --- Lifecycle ---
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

  const handleTabChange = (tab) => {
    setLoading(true);
    setActiveTab(tab);
    if (isMobile) setSidebarOpen(false);
    setTimeout(() => setLoading(false), 300);
  };

  const getBackground = () => {
    if (theme === 'clean') return mode === 'dark' ? 'bg-slate-900' : 'bg-slate-50';
    return `bg-cover bg-center bg-fixed`;
  };

  const getScrollbarColors = () => {
    if (theme === 'clean') {
      if (mode === 'dark') return { thumb: '#475569', hover: '#64748b' };
      return { thumb: '#cbd5e1', hover: '#94a3b8' };
    } else {
      if (mode === 'dark') return { thumb: 'rgba(255,255,255,0.2)', hover: 'rgba(255,255,255,0.3)' };
      return { thumb: 'rgba(255,255,255,0.3)', hover: 'rgba(255,255,255,0.5)' };
    }
  };

  const sbColors = getScrollbarColors();
  const txtMain = mode === 'dark' ? 'text-white' : 'text-slate-900';
  const txtSec = mode === 'dark' ? 'text-slate-400' : 'text-slate-500';

  // Filter
  const filteredPlayers = players.filter(p =>
    p.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.server.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const filteredMessages = chatMessages.filter(m => 
    chatFilter === 'all' || m.channel === chatFilter
  );

  const navItems = [
    { id: 'dashboard', icon: LayoutDashboard, label: '仪表盘' },
    { id: 'console', icon: MessageSquare, label: '实时控制台' },
    { id: 'servers', icon: Server, label: '服务器' },
    { id: 'channels', icon: Hash, label: '频道管理' },
    { id: 'players', icon: Users, label: '玩家管理' },
    { id: 'settings', icon: Settings, label: '系统设置' },
  ];

  return (
    <div className={`w-full overflow-hidden font-sans transition-colors duration-700 ${getBackground()} relative`} style={{ minHeight: '100dvh', '--scrollbar-thumb': sbColors.thumb, '--scrollbar-thumb-hover': sbColors.hover }}>
      <ToastContainer toasts={toasts} removeToast={removeToast} />

      {theme === 'glass' && (
        <>
          <div
            className="fixed inset-0 z-0 transition-opacity duration-1000 bg-gradient-to-br from-slate-950 via-slate-900 to-sky-900"
            style={{ height: '100dvh', width: '100vw' }}
          />
          <div className={`fixed inset-0 z-0 transition-all duration-700 ${mode === 'dark' ? 'bg-black/50' : 'bg-white/20'}`} />
          <div className="fixed top-[-10%] right-[-10%] w-[500px] h-[500px] bg-sky-500/20 rounded-full blur-[120px] animate-pulse z-0 pointer-events-none mix-blend-overlay"></div>
          <div className="fixed bottom-[-10%] left-[-10%] w-[600px] h-[600px] bg-purple-500/20 rounded-full blur-[120px] animate-pulse z-0 pointer-events-none mix-blend-overlay" style={{ animationDelay: '2s' }}></div>
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
              <Avatar
                name="管理员"
                size={!isMobile && !sidebarOpen ? 36 : 40}
                className={`transition-all duration-500 ${!isMobile && !sidebarOpen ? '' : 'mr-3'}`}
              />
              <div className={`overflow-hidden transition-all duration-500 flex-1 min-w-0 ${!isMobile && !sidebarOpen ? 'w-0 opacity-0' : 'w-auto opacity-100'}`}>
                <p className={`text-sm font-semibold whitespace-nowrap ${txtMain}`}>管理员</p>
                <p className={`text-xs whitespace-nowrap ${txtSec}`}>NovaLink v1.0</p>
              </div>
              <button onClick={handleLogout} className={`${txtSec} hover:text-red-400 transition-all duration-500 shrink-0 ${!isMobile && !sidebarOpen ? 'w-0 opacity-0 overflow-hidden' : 'w-auto opacity-100'}`} title="退出">
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
              <div className="relative" ref={notificationRef}>
                <button onClick={() => setShowNotifications(!showNotifications)} className={`p-2 rounded-full relative transition-transform hover:scale-110 ${txtSec} hover:bg-current/10`}>
                  <Bell size={20} />
                  {notifications.some(n => !n.read) && (
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
              {loading ? (
                <div className="h-96 flex items-center justify-center">
                  <div className={`w-12 h-12 border-4 rounded-full animate-spin ${theme === 'clean' ? 'border-sky-500 border-t-transparent' : 'border-white border-t-transparent'}`}></div>
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
                      onCreateChannel={(channel) => {
                        setChannels([...channels, { ...channel, icon: null, color: 'gray' }]);
                        addToast(`频道 ${channel.name} 创建成功`, 'success');
                      }}
                      onEditChannel={(channel) => {
                        setChannels(channels.map(c => c.id === channel.id ? channel : c));
                        addToast(`频道 ${channel.name} 更新成功`, 'success');
                      }}
                      onDeleteChannel={(channelId) => {
                        setChannels(channels.filter(c => c.id !== channelId));
                        addToast('频道已删除', 'success');
                      }}
                    />
                  )}

                  {/* Players - Player Management */}
                  {activeTab === 'players' && (
                    <PlayerManagement 
                      theme={theme} 
                      mode={mode} 
                      txtMain={txtMain} 
                      txtSec={txtSec}
                      players={players} 
                      mutedPlayers={mutedPlayers}
                      servers={servers}
                      onMutePlayer={(muteData) => {
                        const newMute = {
                          uuid: `mute-${Date.now()}`,
                          name: muteData.name,
                          reason: muteData.reason || '违规行为',
                          expireTime: muteData.duration === 'permanent' ? '永久' : new Date(Date.now() + parseInt(muteData.duration) * 3600000).toLocaleString('zh-CN'),
                          operator: 'Admin'
                        };
                        setMutedPlayers([...mutedPlayers, newMute]);
                        addToast(`已禁言 ${newMute.name}`, 'success');
                      }}
                      onUnmutePlayer={handleUnmutePlayer}
                    />
                  )}

                  {/* Settings */}
                  {activeTab === 'settings' && (
                    <SettingsView 
                      theme={theme} mode={mode} txtMain={txtMain} txtSec={txtSec}
                      settings={settings} onToggle={handleSettingToggle}
                      setTheme={setTheme}
                    />
                  )}
                </>
              )}
            </div>
          </div>
        </main>
      </div>

      {/* Mute Modal */}
      <Modal isOpen={showMuteModal} onClose={() => setShowMuteModal(false)} title="禁言玩家" theme={theme} mode={mode}>
        <div className="space-y-4">
          <div>
            <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>玩家名称</label>
            <input type="text" value={muteTarget.name} onChange={(e) => setMuteTarget({ ...muteTarget, name: e.target.value })} placeholder="输入玩家名" className={`w-full px-4 py-2.5 rounded-xl border outline-none focus:ring-2 transition-all ${theme === 'clean' ? (mode === 'dark' ? 'bg-slate-700 border-slate-600 focus:ring-sky-500 text-white' : 'bg-white border-slate-200 focus:ring-sky-500 text-slate-900') : 'bg-white/10 border-white/20 focus:ring-white/50 text-white placeholder:text-white/30'}`} />
          </div>
          <div>
            <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>禁言原因</label>
            <input type="text" value={muteTarget.reason} onChange={(e) => setMuteTarget({ ...muteTarget, reason: e.target.value })} placeholder="违规行为" className={`w-full px-4 py-2.5 rounded-xl border outline-none focus:ring-2 transition-all ${theme === 'clean' ? (mode === 'dark' ? 'bg-slate-700 border-slate-600 focus:ring-sky-500 text-white' : 'bg-white border-slate-200 focus:ring-sky-500 text-slate-900') : 'bg-white/10 border-white/20 focus:ring-white/50 text-white placeholder:text-white/30'}`} />
          </div>
          <div>
            <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>时长</label>
            <CustomSelect theme={theme} mode={mode} options={['1h', '6h', '24h', '7d', 'permanent']} defaultValue="1h" onChange={(val) => setMuteTarget({ ...muteTarget, duration: val })} />
          </div>
          <div className="flex gap-3 mt-6 pt-4 border-t border-gray-200/10">
            <Button variant="ghost" className="flex-1" theme={theme} mode={mode} onClick={() => setShowMuteModal(false)}>取消</Button>
            <Button variant="primary" className="flex-1" theme={theme} mode={mode} onClick={handleMutePlayer}>确认禁言</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}


// ==================== Settings View ====================
function SettingsView({ theme, mode, txtMain, txtSec, settings, onToggle, setTheme }) {
  return (
    <div className="max-w-2xl mx-auto space-y-6 animate-in fade-in duration-500">
      <div>
        <h2 className={`text-2xl font-bold ${txtMain}`}>系统设置</h2>
        <p className={`text-sm ${txtSec} mt-1`}>配置 NovaLink 系统参数</p>
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
          <h3 className={`text-lg font-semibold mb-4 ${txtMain}`}>聊天功能</h3>
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <span className={txtMain}>敏感词过滤</span>
                <p className={`text-xs ${txtSec}`}>自动过滤违规词汇</p>
              </div>
              <Switch checked={settings.enableFilter} onChange={() => onToggle('enableFilter')} theme={theme} mode={mode} />
            </div>
            <div className="flex items-center justify-between">
              <div>
                <span className={txtMain}>消息日志</span>
                <p className={`text-xs ${txtSec}`}>记录所有聊天消息到数据库</p>
              </div>
              <Switch checked={settings.logMessages} onChange={() => onToggle('logMessages')} theme={theme} mode={mode} />
            </div>
            <div className="flex items-center justify-between">
              <div>
                <span className={txtMain}>跨服聊天</span>
                <p className={`text-xs ${txtSec}`}>允许不同服务器间通信</p>
              </div>
              <Switch checked={settings.crossServerChat} onChange={() => onToggle('crossServerChat')} theme={theme} mode={mode} />
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
}
