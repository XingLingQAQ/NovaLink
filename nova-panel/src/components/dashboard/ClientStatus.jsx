/**
 * Client Status Component
 * Display and manage connected game servers (clients)
 * 
 * Requirements: 24.2, 24.3 - Client status monitoring and management
 */

import React, { useState } from 'react';
import {
  Server,
  RefreshCw,
  Power,
  Settings,
  Activity,
  Users,
  Clock,
  Wifi,
  WifiOff,
  MoreVertical,
  Eye,
  Trash2,
  Info
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';

function ClientStatus({
  theme,
  mode,
  txtMain,
  txtSec,
  servers = [],
  onReloadConfig,
  onDisconnectServer,
  onViewServerDetails
}) {
  const { t } = useTranslation();
  const [viewMode, setViewMode] = useState('grid'); // 'grid' or 'list'

  // Config reload is not exposed to the panel via REST or WS.
  // The App-level handler shows an honest-disable toast.
  const reloadConfigDisabled = true;

  // Calculate statistics
  const onlineCount = servers.filter(s => s.status === 'online').length;
  const totalPlayers = servers.reduce((sum, s) => sum + (s.players || 0), 0);
  const avgPing = servers.filter(s => s.status === 'online' && s.ping).length > 0
    ? Math.round(servers.filter(s => s.status === 'online').reduce((sum, s) => sum + (s.ping || 0), 0) / servers.filter(s => s.status === 'online').length)
    : 0;

  // Group servers by platform
  const serversByPlatform = servers.reduce((acc, server) => {
    const platform = server.platform || 'Unknown';
    if (!acc[platform]) acc[platform] = [];
    acc[platform].push(server);
    return acc;
  }, {});

  return (
    <div className="space-y-4 animate-in fade-in duration-500">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className={`text-2xl font-bold ${txtMain}`}>{t('common.servers_title')}</h2>
          <p className={`text-sm ${txtSec} mt-1`}>
            {t('common.servers_subtitle', { online: onlineCount, total: servers.length })}
          </p>
        </div>
        <div className="flex items-center gap-3">
          {/* View Mode Toggle */}
          <div className={`flex p-1 rounded-lg ${
            theme === 'clean' 
              ? (mode === 'dark' ? 'bg-slate-800' : 'bg-slate-100') 
              : 'bg-white/10'
          }`}>
            <button
              onClick={() => setViewMode('grid')}
              className={`p-2 rounded transition-all ${
                viewMode === 'grid'
                  ? (theme === 'clean' ? 'bg-white shadow text-sky-600' : 'bg-white/20 text-white')
                  : txtSec
              }`}
            >
              <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
                <rect x="1" y="1" width="6" height="6" rx="1" />
                <rect x="9" y="1" width="6" height="6" rx="1" />
                <rect x="1" y="9" width="6" height="6" rx="1" />
                <rect x="9" y="9" width="6" height="6" rx="1" />
              </svg>
            </button>
            <button
              onClick={() => setViewMode('list')}
              className={`p-2 rounded transition-all ${
                viewMode === 'list'
                  ? (theme === 'clean' ? 'bg-white shadow text-sky-600' : 'bg-white/20 text-white')
                  : txtSec
              }`}
            >
              <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
                <rect x="1" y="2" width="14" height="3" rx="1" />
                <rect x="1" y="7" width="14" height="3" rx="1" />
                <rect x="1" y="12" width="14" height="3" rx="1" />
              </svg>
            </button>
          </div>

          {/* Reload Config */}
          <Button
            theme={theme}
            mode={mode}
            variant="primary"
            onClick={onReloadConfig}
            title={reloadConfigDisabled ? t('common.reload_title_disabled') : t('common.reload_title')}
          >
            <RefreshCw size={16} /> {t('common.reload')}
          </Button>
        </div>
      </div>

      {/* Honest-disable info banner */}
      {reloadConfigDisabled && (
        <Card theme={theme} mode={mode} className="p-3 flex items-start gap-2 border border-amber-500/20">
          <Info size={16} className="text-amber-400 shrink-0 mt-0.5" />
          <p className={`text-xs ${txtSec}`}>
            {t('common.reload_disable_banner')}
          </p>
        </Card>
      )}

      {/* Statistics Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard
          theme={theme} mode={mode} txtMain={txtMain} txtSec={txtSec}
          icon={Server} label={t('common.stat_online_servers')} value={`${onlineCount}/${servers.length}`}
          color={servers.length > 0 && onlineCount === servers.length ? 'text-emerald-400' : 'text-amber-400'}
        />
        <StatCard
          theme={theme} mode={mode} txtMain={txtMain} txtSec={txtSec}
          icon={Users} label={t('common.stat_total_players')} value={totalPlayers}
          color="text-sky-400"
        />
        <StatCard
          theme={theme} mode={mode} txtMain={txtMain} txtSec={txtSec}
          icon={Activity} label={t('common.stat_avg_ping')} value={onlineCount > 0 ? `${avgPing}ms` : '-'}
          color={avgPing < 50 ? 'text-emerald-400' : avgPing < 100 ? 'text-amber-400' : 'text-rose-400'}
        />
        <StatCard
          theme={theme} mode={mode} txtMain={txtMain} txtSec={txtSec}
          icon={Clock} label={t('common.stat_earliest')} value={servers.length > 0 && servers[0]?.connectedAt ? formatUptime(servers[0].connectedAt) : '-'}
          color="text-purple-400"
        />
      </div>

      {/* Server Display */}
      {servers.length === 0 ? (
        <Card theme={theme} mode={mode} className="p-12 text-center">
          <Server size={48} className={`mx-auto mb-4 opacity-50 ${txtSec}`} />
          <p className={txtMain}>{t('common.no_servers')}</p>
          <p className={`text-sm ${txtSec} mt-1`}>{t('common.no_servers_hint')}</p>
        </Card>
      ) : viewMode === 'grid' ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {servers.map((server) => (
            <ServerCard 
              key={server.id}
              server={server}
              theme={theme}
              mode={mode}
              txtMain={txtMain}
              txtSec={txtSec}
              onViewDetails={onViewServerDetails}
              onDisconnect={onDisconnectServer}
            />
          ))}
        </div>
      ) : (
        <Card theme={theme} mode={mode} className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className={`text-xs uppercase tracking-wider ${txtSec} border-b ${mode === 'dark' ? 'border-white/10' : 'border-slate-200'}`}>
                  <th className="p-4 font-medium">{t('players.col_server')}</th>
                  <th className="p-4 font-medium">{t('players.col_platform')}</th>
                  <th className="p-4 font-medium">{t('common.col_version')}</th>
                  <th className="p-4 font-medium">{t('players.col_player')}</th>
                  <th className="p-4 font-medium">{t('common.col_ping')}</th>
                  <th className="p-4 font-medium">{t('common.col_status')}</th>
                  <th className="p-4 font-medium text-right">{t('players.col_action')}</th>
                </tr>
              </thead>
              <tbody className={`text-sm ${txtMain}`}>
                {servers.map((server) => (
                  <tr 
                    key={server.id}
                    className={`border-b ${mode === 'dark' ? 'border-white/5' : 'border-slate-100'} hover:bg-white/5 transition-colors`}
                  >
                    <td className="p-4">
                      <div className="flex items-center gap-3">
                        <div className={`w-2 h-2 rounded-full ${server.status === 'online' ? 'bg-emerald-500' : 'bg-red-500'}`} />
                        <span className="font-medium">{server.name}</span>
                      </div>
                    </td>
                    <td className="p-4">{server.platform}</td>
                    <td className="p-4">{server.version}</td>
                    <td className="p-4">{server.players}</td>
                    <td className="p-4">
                      {server.status === 'online' ? `${server.ping}ms` : '-'}
                    </td>
                    <td className="p-4">
                      <span className={`px-2 py-1 rounded-full text-xs font-medium ${
                        server.status === 'online' 
                          ? 'bg-emerald-500/20 text-emerald-500' 
                          : 'bg-red-500/20 text-red-500'
                      }`}>
                        {server.status === 'online' ? t('common.status_online') : t('common.status_offline')}
                      </span>
                    </td>
                    <td className="p-4 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <Button 
                          theme={theme} 
                          mode={mode} 
                          variant="ghost" 
                          className="text-xs"
                          onClick={() => onViewServerDetails && onViewServerDetails(server)}
                        >
                          <Eye size={14} />
                        </Button>
                        {server.status === 'online' && (
                          <Button 
                            theme={theme} 
                            mode={mode} 
                            variant="danger" 
                            className="text-xs"
                            onClick={() => onDisconnectServer && onDisconnectServer(server.id)}
                          >
                            <Power size={14} />
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Platform Summary */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card theme={theme} mode={mode} className="p-5">
          <h3 className={`text-lg font-semibold mb-4 ${txtMain}`}>{t('dashboard.platform_distribution')}</h3>
          <div className="space-y-3">
            {Object.entries(serversByPlatform).map(([platform, platformServers]) => {
              const online = platformServers.filter(s => s.status === 'online').length;
              const total = platformServers.length;
              const percent = (online / total) * 100;
              
              return (
                <div key={platform} className={`p-3 rounded-xl ${
                  theme === 'clean' 
                    ? (mode === 'dark' ? 'bg-slate-700/50' : 'bg-slate-50') 
                    : 'bg-white/5'
                }`}>
                  <div className="flex items-center justify-between mb-2">
                    <span className={`font-medium ${txtMain}`}>{platform}</span>
                    <span className={`text-sm ${txtSec}`}>{t('common.online_ratio', { online, total })}</span>
                  </div>
                  <div className={`h-2 rounded-full ${theme === 'clean' ? 'bg-slate-200' : 'bg-white/10'}`}>
                    <div 
                      className={`h-full rounded-full transition-all duration-500 ${
                        percent === 100 ? 'bg-emerald-500' : percent > 50 ? 'bg-amber-500' : 'bg-rose-500'
                      }`}
                      style={{ width: `${percent}%` }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </Card>

        <Card theme={theme} mode={mode} className="p-5">
          <h3 className={`text-lg font-semibold mb-4 ${txtMain}`}>{t('common.settings_connection')}</h3>
          <div className="space-y-3">
            {servers.slice(0, 5).map((server) => (
              <div key={server.id} className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  {server.status === 'online' ? (
                    <Wifi size={16} className="text-emerald-400" />
                  ) : (
                    <WifiOff size={16} className="text-red-400" />
                  )}
                  <span className={txtMain}>{server.name}</span>
                </div>
                <div className="flex items-center gap-2">
                  {server.status === 'online' && (
                    <span className={`text-sm ${
                      server.ping < 50 ? 'text-emerald-400' : 
                      server.ping < 100 ? 'text-amber-400' : 'text-rose-400'
                    }`}>
                      {server.ping}ms
                    </span>
                  )}
                  <span className={`w-2 h-2 rounded-full ${
                    server.status === 'online' ? 'bg-emerald-500' : 'bg-red-500'
                  }`} />
                </div>
              </div>
            ))}
          </div>
        </Card>
      </div>
    </div>
  );
}

// Statistics Card Component
function StatCard({ theme, mode, txtMain, txtSec, icon: Icon, label, value, color }) {
  return (
    <Card theme={theme} mode={mode} className="p-4">
      <div className="flex items-center gap-3">
        <div className={`p-2 rounded-lg ${theme === 'clean' ? 'bg-sky-50 text-sky-600' : 'bg-white/10 text-white'}`}>
          <Icon size={20} />
        </div>
        <div>
          <p className={`text-2xl font-bold ${color || txtMain}`}>{value}</p>
          <p className={`text-xs ${txtSec}`}>{label}</p>
        </div>
      </div>
    </Card>
  );
}

// Server Card Component
function ServerCard({ server, theme, mode, txtMain, txtSec, onViewDetails, onDisconnect }) {
  const { t } = useTranslation();
  return (
    <Card theme={theme} mode={mode} className="p-5 relative">
      {/* Header */}
      <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className={`w-12 h-12 rounded-xl flex items-center justify-center ${
            server.status === 'online'
              ? (theme === 'clean' ? 'bg-emerald-50 text-emerald-600' : 'bg-emerald-500/20 text-emerald-400')
              : (theme === 'clean' ? 'bg-red-50 text-red-600' : 'bg-red-500/20 text-red-400')
          }`}>
            <Server size={24} />
          </div>
          <div>
            <h3 className={`font-semibold ${txtMain}`}>{server.name}</h3>
            <p className={`text-xs ${txtSec}`}>{server.platform}</p>
          </div>
        </div>

        {/* Status Badge */}
        <span className={`px-2 py-1 rounded-full text-xs font-medium ${
          server.status === 'online'
            ? 'bg-emerald-500/20 text-emerald-500'
            : 'bg-red-500/20 text-red-500'
        }`}>
          {server.status === 'online' ? t('common.status_online') : t('common.status_offline')}
        </span>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-3 gap-2 text-center">
        <div className={`p-2 rounded-lg ${
          theme === 'clean'
            ? (mode === 'dark' ? 'bg-slate-700/50' : 'bg-slate-50')
            : 'bg-white/5'
        }`}>
          <p className={`text-lg font-bold ${txtMain}`}>{server.players}</p>
          <p className={`text-xs ${txtSec}`}>{t('players.col_player')}</p>
        </div>
        <div className={`p-2 rounded-lg ${
          theme === 'clean'
            ? (mode === 'dark' ? 'bg-slate-700/50' : 'bg-slate-50')
            : 'bg-white/5'
        }`}>
          <p className={`text-lg font-bold ${
            server.status === 'online'
              ? (server.ping < 50 ? 'text-emerald-400' : server.ping < 100 ? 'text-amber-400' : 'text-rose-400')
              : txtSec
          }`}>
            {server.status === 'online' ? server.ping : '-'}
          </p>
          <p className={`text-xs ${txtSec}`}>{t('common.col_ping')}</p>
        </div>
        <div className={`p-2 rounded-lg ${
          theme === 'clean'
            ? (mode === 'dark' ? 'bg-slate-700/50' : 'bg-slate-50')
            : 'bg-white/5'
        }`}>
          <p className={`text-lg font-bold ${txtMain}`}>{server.version}</p>
          <p className={`text-xs ${txtSec}`}>{t('common.col_version')}</p>
        </div>
      </div>

      {/* Actions */}
      <div className="flex gap-2 mt-4">
        <Button
          theme={theme}
          mode={mode}
          variant="ghost"
          className="flex-1 text-sm"
          onClick={() => onViewDetails && onViewDetails(server)}
        >
          <Eye size={14} /> {t('common.details')}
        </Button>
        {server.status === 'online' && (
          <Button 
            theme={theme} 
            mode={mode} 
            variant="danger" 
            className="text-sm"
            onClick={() => onDisconnect && onDisconnect(server.id)}
          >
            <Power size={14} />
          </Button>
        )}
      </div>
    </Card>
  );
}

// Format connection uptime from a connectedAt timestamp (ms).
function formatUptime(connectedAt) {
  if (!connectedAt) return '-';
  const diff = Date.now() - connectedAt;
  if (diff < 0) return '-';
  const seconds = Math.floor(diff / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  if (hours > 0) return `${hours}h ${minutes % 60}m`;
  if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
  return `${seconds}s`;
}

export default ClientStatus;
