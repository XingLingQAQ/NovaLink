/**
 * Client Status Component
 * Display and manage connected game servers (clients).
 *
 * Restyled to the shadcn/ui reference idiom: stat Card grid, Card list of
 * connected servers with pill status badges (online/offline), pill action
 * Buttons. The honest-disable info banner uses an amber-tinted Card.
 */

import React, { useState } from 'react';
import {
  Server,
  RefreshCw,
  Power,
  Eye,
  Activity,
  Users,
  Clock,
  Wifi,
  WifiOff,
  Info,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';

function ClientStatus({
  theme,
  mode,
  txtMain: _txtMain,
  txtSec: _txtSec,
  servers = [],
  onReloadConfig,
  onDisconnectServer,
  onViewServerDetails,
}) {
  void _txtMain; void _txtSec;
  const { t } = useTranslation();
  const [viewMode, setViewMode] = useState('grid'); // 'grid' or 'list'

  // Config reload is not exposed to the panel via REST or WS.
  // The App-level handler shows an honest-disable toast.
  const reloadConfigDisabled = true;

  // Calculate statistics.
  const onlineCount = servers.filter((s) => s.status === 'online').length;
  const totalPlayers = servers.reduce((sum, s) => sum + (s.players || 0), 0);
  const avgPing =
    servers.filter((s) => s.status === 'online' && s.ping).length > 0
      ? Math.round(
          servers.filter((s) => s.status === 'online').reduce((sum, s) => sum + (s.ping || 0), 0) /
            servers.filter((s) => s.status === 'online').length
        )
      : 0;

  // Group servers by platform.
  const serversByPlatform = servers.reduce((acc, server) => {
    const platform = server.platform || 'Unknown';
    if (!acc[platform]) acc[platform] = [];
    acc[platform].push(server);
    return acc;
  }, {});

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('common.servers_title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">
            {t('common.servers_subtitle', { online: onlineCount, total: servers.length })}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {/* View Mode Toggle (pill) */}
          <div className="inline-flex h-8 items-center gap-0.5 rounded-full bg-muted p-0.5">
            <button
              onClick={() => setViewMode('grid')}
              className={`flex h-7 w-7 items-center justify-center rounded-full transition-colors ${
                viewMode === 'grid' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor">
                <rect x="1" y="1" width="6" height="6" rx="1" />
                <rect x="9" y="1" width="6" height="6" rx="1" />
                <rect x="1" y="9" width="6" height="6" rx="1" />
                <rect x="9" y="9" width="6" height="6" rx="1" />
              </svg>
            </button>
            <button
              onClick={() => setViewMode('list')}
              className={`flex h-7 w-7 items-center justify-center rounded-full transition-colors ${
                viewMode === 'list' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor">
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
            variant="default"
            onClick={onReloadConfig}
            title={reloadConfigDisabled ? t('common.reload_title_disabled') : t('common.reload_title')}
          >
            <RefreshCw size={14} /> {t('common.reload')}
          </Button>
        </div>
      </div>

      {/* Honest-disable info banner */}
      {reloadConfigDisabled && (
        <Card className="p-3 flex items-start gap-2 border-amber-500/30 bg-amber-500/5">
          <Info size={14} className="text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
          <p className="text-xs text-muted-foreground">{t('common.reload_disable_banner')}</p>
        </Card>
      )}

      {/* Statistics Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard
          icon={Server}
          label={t('common.stat_online_servers')}
          value={`${onlineCount}/${servers.length}`}
          color={servers.length > 0 && onlineCount === servers.length ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400'}
        />
        <StatCard
          icon={Users}
          label={t('common.stat_total_players')}
          value={totalPlayers}
          color="text-sky-600 dark:text-sky-400"
        />
        <StatCard
          icon={Activity}
          label={t('common.stat_avg_ping')}
          value={onlineCount > 0 ? `${avgPing}ms` : '-'}
          color={avgPing < 50 ? 'text-emerald-600 dark:text-emerald-400' : avgPing < 100 ? 'text-amber-600 dark:text-amber-400' : 'text-destructive'}
        />
        <StatCard
          icon={Clock}
          label={t('common.stat_earliest')}
          value={servers.length > 0 && servers[0]?.connectedAt ? formatUptime(servers[0].connectedAt) : '-'}
          color="text-foreground"
        />
      </div>

      {/* Server Display */}
      {servers.length === 0 ? (
        <Card className="p-12 text-center">
          <Server size={40} className="mx-auto mb-3 text-muted-foreground opacity-50" />
          <p className="text-sm text-foreground">{t('common.no_servers')}</p>
          <p className="text-xs text-muted-foreground mt-1">{t('common.no_servers_hint')}</p>
        </Card>
      ) : viewMode === 'grid' ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {servers.map((server) => (
            <ServerCard
              key={server.id}
              server={server}
              theme={theme}
              mode={mode}
              onViewDetails={onViewServerDetails}
              onDisconnect={onDisconnectServer}
            />
          ))}
        </div>
      ) : (
        <Card className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="text-xs text-muted-foreground border-b border-border">
                  <th className="p-3 font-medium">{t('players.col_server')}</th>
                  <th className="p-3 font-medium">{t('players.col_platform')}</th>
                  <th className="p-3 font-medium">{t('common.col_version')}</th>
                  <th className="p-3 font-medium">{t('players.col_player')}</th>
                  <th className="p-3 font-medium">{t('common.col_ping')}</th>
                  <th className="p-3 font-medium">{t('common.col_status')}</th>
                  <th className="p-3 font-medium text-right">{t('players.col_action')}</th>
                </tr>
              </thead>
              <tbody className="text-xs text-foreground">
                {servers.map((server) => (
                  <tr key={server.id} className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors">
                    <td className="p-3">
                      <div className="flex items-center gap-2">
                        <div className={`w-1.5 h-1.5 rounded-full ${server.status === 'online' ? 'bg-emerald-500' : 'bg-destructive'}`} />
                        <span className="font-medium">{server.name}</span>
                      </div>
                    </td>
                    <td className="p-3 text-muted-foreground">{server.platform}</td>
                    <td className="p-3 text-muted-foreground">{server.version}</td>
                    <td className="p-3">{server.players}</td>
                    <td className="p-3 text-muted-foreground">{server.status === 'online' ? `${server.ping}ms` : '-'}</td>
                    <td className="p-3">
                      <Badge variant={server.status === 'online' ? 'success' : 'destructive'}>
                        {server.status === 'online' ? t('common.status_online') : t('common.status_offline')}
                      </Badge>
                    </td>
                    <td className="p-3 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <Button
                          theme={theme}
                          mode={mode}
                          variant="ghost"
                          size="icon"
                          onClick={() => onViewServerDetails && onViewServerDetails(server)}
                        >
                          <Eye size={14} />
                        </Button>
                        {server.status === 'online' && (
                          <Button
                            theme={theme}
                            mode={mode}
                            variant="destructive"
                            size="icon"
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
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <Card className="p-5">
          <h3 className="text-sm font-medium mb-4 text-foreground">{t('dashboard.platform_distribution')}</h3>
          <div className="space-y-2">
            {Object.entries(serversByPlatform).map(([platform, platformServers]) => {
              const online = platformServers.filter((s) => s.status === 'online').length;
              const total = platformServers.length;
              const percent = (online / total) * 100;
              return (
                <div key={platform} className="p-3 rounded-md bg-muted/40">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-sm font-medium text-foreground">{platform}</span>
                    <span className="text-xs text-muted-foreground">{t('common.online_ratio', { online, total })}</span>
                  </div>
                  <div className="h-1.5 rounded-full bg-muted">
                    <div
                      className={`h-full rounded-full transition-all duration-500 ${
                        percent === 100 ? 'bg-emerald-500' : percent > 50 ? 'bg-amber-500' : 'bg-destructive'
                      }`}
                      style={{ width: `${percent}%` }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </Card>

        <Card className="p-5">
          <h3 className="text-sm font-medium mb-4 text-foreground">{t('common.settings_connection')}</h3>
          <div className="space-y-2">
            {servers.slice(0, 5).map((server) => (
              <div key={server.id} className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  {server.status === 'online' ? (
                    <Wifi size={14} className="text-emerald-600 dark:text-emerald-400" />
                  ) : (
                    <WifiOff size={14} className="text-destructive" />
                  )}
                  <span className="text-sm text-foreground">{server.name}</span>
                </div>
                <div className="flex items-center gap-2">
                  {server.status === 'online' && (
                    <span className={`text-xs ${
                      server.ping < 50 ? 'text-emerald-600 dark:text-emerald-400' :
                      server.ping < 100 ? 'text-amber-600 dark:text-amber-400' : 'text-destructive'
                    }`}>
                      {server.ping}ms
                    </span>
                  )}
                  <span className={`w-1.5 h-1.5 rounded-full ${server.status === 'online' ? 'bg-emerald-500' : 'bg-destructive'}`} />
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
function StatCard({ icon: Icon, label, value, color }) {
  return (
    <Card className="p-4">
      <div className="flex items-center gap-3">
        <div className="flex size-8 items-center justify-center rounded-md bg-muted text-muted-foreground">
          <Icon size={16} />
        </div>
        <div>
          <p className={`text-xl font-medium ${color || 'text-foreground'}`}>{value}</p>
          <p className="text-xs text-muted-foreground">{label}</p>
        </div>
      </div>
    </Card>
  );
}

// Server Card Component
function ServerCard({ server, theme, mode, onViewDetails, onDisconnect }) {
  const { t } = useTranslation();
  return (
    <Card className="p-4">
      {/* Header */}
      <div className="flex items-start justify-between mb-3">
        <div className="flex items-center gap-3">
          <div className={`flex size-9 items-center justify-center rounded-md ${
            server.status === 'online' ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400' : 'bg-destructive/10 text-destructive'
          }`}>
            <Server size={18} />
          </div>
          <div>
            <h3 className="text-sm font-medium text-foreground">{server.name}</h3>
            <p className="text-xs text-muted-foreground">{server.platform}</p>
          </div>
        </div>
        <Badge variant={server.status === 'online' ? 'success' : 'destructive'}>
          {server.status === 'online' ? t('common.status_online') : t('common.status_offline')}
        </Badge>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-3 gap-2 text-center">
        <div className="p-2 rounded-md bg-muted/40">
          <p className="text-base font-medium text-foreground">{server.players}</p>
          <p className="text-xs text-muted-foreground">{t('players.col_player')}</p>
        </div>
        <div className="p-2 rounded-md bg-muted/40">
          <p className={`text-base font-medium ${
            server.status === 'online'
              ? (server.ping < 50 ? 'text-emerald-600 dark:text-emerald-400' : server.ping < 100 ? 'text-amber-600 dark:text-amber-400' : 'text-destructive')
              : 'text-muted-foreground'
          }`}>
            {server.status === 'online' ? server.ping : '-'}
          </p>
          <p className="text-xs text-muted-foreground">{t('common.col_ping')}</p>
        </div>
        <div className="p-2 rounded-md bg-muted/40">
          <p className="text-base font-medium text-foreground">{server.version}</p>
          <p className="text-xs text-muted-foreground">{t('common.col_version')}</p>
        </div>
      </div>

      {/* Actions */}
      <div className="flex gap-2 mt-3">
        <Button
          theme={theme}
          mode={mode}
          variant="outline"
          className="flex-1 text-xs"
          onClick={() => onViewDetails && onViewDetails(server)}
        >
          <Eye size={12} /> {t('common.details')}
        </Button>
        {server.status === 'online' && (
          <Button
            theme={theme}
            mode={mode}
            variant="destructive"
            size="icon"
            onClick={() => onDisconnect && onDisconnect(server.id)}
          >
            <Power size={12} />
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
