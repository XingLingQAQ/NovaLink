/**
 * Dashboard View Component
 * Main dashboard with system overview and statistics.
 *
 * Restyled to the shadcn/ui reference idiom: Card-based stat grid
 * (text-2xl font-medium values, text-xs text-muted-foreground labels),
 * token-driven colors, pill badges, rounded-lg cards.
 */

import React from 'react';
import {
  Server,
  Users,
  MessageSquare,
  Hash,
  ArrowUpRight,
  ArrowDownRight,
  MoreHorizontal,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Badge from '../ui/Badge';
import Avatar from '../ui/Avatar';

function DashboardView({ theme: _theme, mode: _mode, txtMain: _txtMain, txtSec: _txtSec, servers, channels, players, chatMessages, dashboardStats, statIconMap }) {
  void _theme; void _mode; void _txtMain; void _txtSec;
  const { t } = useTranslation();
  // Use the pre-built stats from real backend data if provided; otherwise fall back to computed.
  const stats = dashboardStats && dashboardStats.length > 0
    ? dashboardStats.map((s) => {
        let change = s.change;
        if (s.changeKey) {
          change = t(s.changeKey, s.changeOfflineCount != null ? { count: s.changeOfflineCount } : undefined);
        } else if (s.changeOfflineCount != null) {
          change = t('dashboard.change_offline_count', { count: s.changeOfflineCount });
        }
        return {
          ...s,
          title: t(s.titleKey || s.title, { defaultValue: s.title }),
          change,
          icon: (statIconMap && statIconMap[s.icon]) || Server,
        };
      })
    : (() => {
        const onlineServers = servers.filter((s) => s.status === 'online').length;
        const totalPlayers = players.length;
        const todayMessages = chatMessages?.length || 0;
        const activeChannels = channels?.length || 0;
        return [
          { title: t('dashboard.online_servers'), value: `${onlineServers}/${servers.length}`, change: servers.some((s) => s.status === 'offline') ? t('dashboard.change_has_offline') : t('dashboard.change_all_online'), trend: servers.some((s) => s.status === 'offline') ? 'down' : 'up', icon: Server },
          { title: t('dashboard.online_players'), value: totalPlayers.toString(), change: t('dashboard.change_realtime'), trend: totalPlayers > 0 ? 'up' : 'normal', icon: Users },
          { title: t('dashboard.session_messages'), value: todayMessages > 1000 ? `${(todayMessages / 1000).toFixed(1)}k` : todayMessages.toString(), change: todayMessages > 0 ? t('dashboard.change_this_session') : t('dashboard.change_none'), trend: todayMessages > 0 ? 'up' : 'normal', icon: MessageSquare },
          { title: t('dashboard.total_channels'), value: activeChannels.toString(), change: t('dashboard.change_registered'), trend: 'normal', icon: Hash },
        ];
      })();

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('dashboard.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('dashboard.subtitle')}</p>
        </div>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {stats.map((stat, idx) => (
          <Card key={idx} className="p-5">
            <div className="flex justify-between items-start">
              <div>
                <p className="text-xs text-muted-foreground">{stat.title}</p>
                <h3 className="text-2xl font-medium mt-1 text-foreground">{stat.value}</h3>
              </div>
              <div className="flex size-8 items-center justify-center rounded-md bg-muted text-muted-foreground">
                <stat.icon size={16} />
              </div>
            </div>
            <div className="mt-3 flex items-center text-xs">
              <span className={`flex items-center ${stat.trend === 'up' ? 'text-emerald-600 dark:text-emerald-400' : stat.trend === 'down' ? 'text-destructive' : 'text-muted-foreground'}`}>
                {stat.trend === 'up' ? <ArrowUpRight size={14} /> : stat.trend === 'down' ? <ArrowDownRight size={14} /> : <MoreHorizontal size={14} />}
                <span className="ml-1 font-medium">{stat.change}</span>
              </span>
            </div>
          </Card>
        ))}
      </div>

      {/* Server Status and Platform Distribution */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <ServerStatusCard servers={servers} />
        <PlatformDistributionCard servers={servers} />
      </div>

      {/* Recent Activity */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <RecentMessagesCard messages={chatMessages} />
        <OnlinePlayersCard players={players} />
      </div>
    </div>
  );
}

// Server Status Card
function ServerStatusCard({ servers }) {
  const { t } = useTranslation();
  return (
    <Card className="p-5">
      <h3 className="text-sm font-medium mb-4 text-foreground">{t('dashboard.server_status')}</h3>
      {(!servers || servers.length === 0) ? (
        <p className="text-xs text-muted-foreground text-center py-8">{t('dashboard.no_servers_ws')}</p>
      ) : (
        <div className="space-y-2 max-h-[300px] overflow-y-auto">
          {servers.map((server) => (
            <div key={server.id} className="flex items-center justify-between p-2.5 rounded-md bg-muted/40">
              <div className="flex items-center gap-3">
                <div className={`w-1.5 h-1.5 rounded-full ${server.status === 'online' ? 'bg-emerald-500' : 'bg-destructive'}`} />
                <div>
                  <p className="text-sm font-medium text-foreground">{server.name}</p>
                  <p className="text-xs text-muted-foreground">{server.platform} · {server.version}</p>
                </div>
              </div>
              <div className="text-right">
                <p className="text-sm font-medium text-foreground">{server.players} {t('dashboard.players')}</p>
                <p className="text-xs text-muted-foreground">{server.status === 'online' ? `${server.ping}ms` : t('dashboard.offline')}</p>
              </div>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}

// Platform Distribution Card
// Backend sends platform as the PlatformType enum name (BUKKIT, VELOCITY,
// BUNGEECORD, NUKKIT, LEVILAMINA, FABRIC, NEOFORGE, QUILT, FORGE, POCKETMINE,
// ENDSTONE, POWERNUKKITX, FOLIA, SPONGE) or "Unknown". We group them into
// friendly display buckets and collapse the rest (including a real "Unknown")
// into an "Other" row so the card reflects the real platform distribution
// instead of always showing all-zero bars.
const PLATFORM_BUCKETS = [
  { labelKey: 'dashboard.platform_bukkit', match: ['BUKKIT', 'FOLIA', 'SPONGE'], color: 'bg-primary' },
  { labelKey: 'dashboard.platform_proxy', match: ['VELOCITY', 'BUNGEECORD'], color: 'bg-sky-500' },
  { labelKey: 'dashboard.platform_nukkit', match: ['NUKKIT', 'POWERNUKKITX', 'POCKETMINE', 'ENDSTONE'], color: 'bg-amber-500' },
  { labelKey: 'dashboard.platform_levilamina', match: ['LEVILAMINA'], color: 'bg-emerald-500' },
  { labelKey: 'dashboard.platform_mod', match: ['FABRIC', 'NEOFORGE', 'QUILT', 'FORGE'], color: 'bg-purple-500' },
];

function PlatformDistributionCard({ servers }) {
  const { t } = useTranslation();

  // Count servers per bucket. Anything not matching a known bucket (including
  // a real backend "Unknown") rolls up into the "Other" row.
  const counts = PLATFORM_BUCKETS.map((b) => ({
    ...b,
    count: (servers || []).filter((s) => b.match.includes(s.platform)).length,
  }));
  const otherCount = (servers || []).filter(
    (s) => !PLATFORM_BUCKETS.some((b) => b.match.includes(s.platform))
  ).length;
  const rows = [
    ...counts,
    { labelKey: 'dashboard.platform_other', count: otherCount, color: 'bg-muted-foreground/40' },
  ];
  const total = servers && servers.length > 0 ? servers.length : 0;

  return (
    <Card className="p-5">
      <h3 className="text-sm font-medium mb-4 text-foreground">{t('dashboard.platform_distribution')}</h3>
      <div className="space-y-3">
        {rows.map((row, i) => {
          const percent = total > 0 ? (row.count / total) * 100 : 0;
          return (
            <div key={row.labelKey}>
              <div className="flex justify-between mb-1">
                <span className="text-xs text-foreground">{t(row.labelKey)}</span>
                <span className="text-xs text-muted-foreground">{t('dashboard.servers_count', { count: row.count })}</span>
              </div>
              <div className="h-1.5 rounded-full bg-muted">
                <div className={`h-full rounded-full transition-all duration-700 ${row.color}`} style={{ width: `${percent}%` }} />
              </div>
            </div>
          );
        })}
        {(!servers || servers.length === 0) && (
          <p className="text-xs text-muted-foreground text-center py-4">{t('dashboard.waiting_server_data')}</p>
        )}
      </div>
    </Card>
  );
}

// Recent Messages Card
function RecentMessagesCard({ messages = [] }) {
  const { t } = useTranslation();
  const recentMessages = (messages || []).slice(-5).reverse();

  return (
    <Card className="p-5">
      <h3 className="text-sm font-medium mb-4 text-foreground">{t('dashboard.recent_messages')}</h3>
      <div className="space-y-2">
        {recentMessages.length === 0 ? (
          <p className="text-xs text-muted-foreground text-center py-4">{t('dashboard.no_messages_ws')}</p>
        ) : (
          recentMessages.map((msg, idx) => (
            <div key={msg.id || idx} className="p-2.5 rounded-md bg-muted/40">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-xs text-muted-foreground">[{msg.time}]</span>
                <span className="text-xs text-muted-foreground">[{msg.server}]</span>
                <span className="text-xs text-foreground font-medium">{msg.player}</span>
              </div>
              <p className="text-xs text-foreground truncate">{msg.content}</p>
            </div>
          ))
        )}
      </div>
    </Card>
  );
}

// Online Players Card
function OnlinePlayersCard({ players = [] }) {
  const { t } = useTranslation();
  const displayPlayers = (players || []).slice(0, 5);

  return (
    <Card className="p-5">
      <h3 className="text-sm font-medium mb-4 text-foreground">{t('dashboard.online_players')}</h3>
      <div className="space-y-2">
        {displayPlayers.length === 0 ? (
          <p className="text-xs text-muted-foreground text-center py-4">{t('dashboard.no_online_players')}</p>
        ) : (
          displayPlayers.map((player) => (
            <div key={player.uuid} className="flex items-center justify-between p-2.5 rounded-md bg-muted/40">
              <div className="flex items-center gap-3">
                <Avatar name={player.name} size={28} rounded="rounded-full" />
                <div>
                  <p className="text-sm font-medium text-foreground">{player.name}</p>
                  <p className="text-xs text-muted-foreground">{player.server}</p>
                </div>
              </div>
              <Badge variant={player.platform === 'Java' ? 'success' : 'warning'}>
                {player.platform}
              </Badge>
            </div>
          ))
        )}
      </div>
    </Card>
  );
}

export default DashboardView;
