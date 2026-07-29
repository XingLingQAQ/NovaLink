/**
 * Dashboard View Component
 * Main dashboard with system overview and statistics
 * 
 * Requirements: 24.2, 24.3
 */

import React from 'react';
import {
  Server,
  Users,
  MessageSquare,
  Hash,
  ArrowUpRight,
  ArrowDownRight,
  MoreHorizontal
} from 'lucide-react';
import Card from '../ui/Card';
import Avatar from '../ui/Avatar';

function DashboardView({ theme, mode, txtMain, txtSec, servers, channels, players, chatMessages }) {
  // Calculate statistics
  const onlineServers = servers.filter(s => s.status === 'online').length;
  const totalPlayers = servers.reduce((sum, s) => sum + (s.players || 0), 0);
  const todayMessages = chatMessages?.length || 0;
  const activeChannels = channels?.filter(c => c.type !== 'PRIVATE').length || 0;

  const stats = [
    { 
      title: "在线服务器", 
      value: `${onlineServers}/${servers.length}`, 
      change: servers.some(s => s.status === 'offline') ? "有离线" : "全部在线", 
      trend: servers.some(s => s.status === 'offline') ? "down" : "up", 
      icon: Server 
    },
    { 
      title: "在线玩家", 
      value: totalPlayers.toString(), 
      change: "+23", 
      trend: "up", 
      icon: Users 
    },
    { 
      title: "今日消息", 
      value: todayMessages > 1000 ? `${(todayMessages / 1000).toFixed(1)}k` : todayMessages.toString(), 
      change: "+15.2%", 
      trend: "up", 
      icon: MessageSquare 
    },
    { 
      title: "活跃频道", 
      value: activeChannels.toString(), 
      change: "正常", 
      trend: "normal", 
      icon: Hash 
    },
  ];

  return (
    <div className="space-y-6 animate-in fade-in zoom-in-95 duration-500">
      <div className="flex items-center justify-between">
        <div>
          <h2 className={`text-2xl font-bold ${txtMain}`}>仪表盘</h2>
          <p className={`text-sm ${txtSec} mt-1`}>NovaLink 系统状态概览</p>
        </div>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {stats.map((stat, idx) => (
          <Card key={idx} theme={theme} mode={mode} className="p-5 relative overflow-hidden group hover:-translate-y-1 transition-transform">
            <div className="flex justify-between items-start">
              <div>
                <p className={`text-sm font-medium ${txtSec}`}>{stat.title}</p>
                <h3 className={`text-2xl font-bold mt-1 ${txtMain}`}>{stat.value}</h3>
              </div>
              <div className={`p-2 rounded-lg ${theme === 'clean' ? 'bg-sky-50 text-sky-600' : 'bg-white/20 text-white'}`}>
                <stat.icon size={20} />
              </div>
            </div>
            <div className="mt-3 flex items-center text-sm">
              <span className={`flex items-center ${stat.trend === 'up' ? 'text-emerald-500' : stat.trend === 'down' ? 'text-rose-500' : 'text-sky-500'}`}>
                {stat.trend === 'up' ? <ArrowUpRight size={16} /> : stat.trend === 'down' ? <ArrowDownRight size={16} /> : <MoreHorizontal size={16} />}
                <span className="ml-1 font-semibold">{stat.change}</span>
              </span>
            </div>
          </Card>
        ))}
      </div>

      {/* Server Status and Platform Distribution */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <ServerStatusCard theme={theme} mode={mode} txtMain={txtMain} txtSec={txtSec} servers={servers} />
        <PlatformDistributionCard theme={theme} mode={mode} txtMain={txtMain} txtSec={txtSec} servers={servers} />
      </div>

      {/* Recent Activity */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <RecentMessagesCard theme={theme} mode={mode} txtMain={txtMain} txtSec={txtSec} messages={chatMessages} />
        <OnlinePlayersCard theme={theme} mode={mode} txtMain={txtMain} txtSec={txtSec} players={players} />
      </div>
    </div>
  );
}

// Server Status Card
function ServerStatusCard({ theme, mode, txtMain, txtSec, servers }) {
  return (
    <Card theme={theme} mode={mode} className="p-5">
      <h3 className={`text-lg font-semibold mb-4 ${txtMain}`}>服务器状态</h3>
      <div className="space-y-3 max-h-[300px] overflow-y-auto custom-scrollbar">
        {servers.map((server) => (
          <div key={server.id} className={`flex items-center justify-between p-3 rounded-xl ${theme === 'clean' ? (mode === 'dark' ? 'bg-slate-700/50' : 'bg-slate-50') : 'bg-white/5'}`}>
            <div className="flex items-center gap-3">
              <div className={`w-2 h-2 rounded-full ${server.status === 'online' ? 'bg-emerald-500' : 'bg-red-500'}`} />
              <div>
                <p className={`font-medium ${txtMain}`}>{server.name}</p>
                <p className={`text-xs ${txtSec}`}>{server.platform} · {server.version}</p>
              </div>
            </div>
            <div className="text-right">
              <p className={`font-semibold ${txtMain}`}>{server.players} 玩家</p>
              <p className={`text-xs ${txtSec}`}>{server.status === 'online' ? `${server.ping}ms` : '离线'}</p>
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}

// Platform Distribution Card
function PlatformDistributionCard({ theme, mode, txtMain, txtSec, servers }) {
  const platforms = ['Bukkit/Paper', 'Velocity/Bungee', 'Nukkit', 'LeviLamina'];
  const colors = ['bg-sky-500', 'bg-purple-500', 'bg-amber-500', 'bg-emerald-500'];

  return (
    <Card theme={theme} mode={mode} className="p-5">
      <h3 className={`text-lg font-semibold mb-4 ${txtMain}`}>平台分布</h3>
      <div className="space-y-4">
        {platforms.map((platform, i) => {
          const count = servers.filter(s => 
            platform === 'Bukkit/Paper' ? ['Bukkit', 'Paper'].includes(s.platform) :
            platform === 'Velocity/Bungee' ? ['Velocity', 'BungeeCord'].includes(s.platform) :
            s.platform === platform
          ).length;
          const percent = servers.length > 0 ? (count / servers.length) * 100 : 0;
          return (
            <div key={platform}>
              <div className="flex justify-between mb-1">
                <span className={`text-sm ${txtMain}`}>{platform}</span>
                <span className={`text-sm ${txtSec}`}>{count} 服务器</span>
              </div>
              <div className={`h-2 rounded-full ${theme === 'clean' ? 'bg-slate-200' : 'bg-white/10'}`}>
                <div className={`h-full rounded-full transition-all duration-1000 ${colors[i]}`} style={{ width: `${percent}%` }} />
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}

// Recent Messages Card
function RecentMessagesCard({ theme, mode, txtMain, txtSec, messages = [] }) {
  const recentMessages = messages.slice(-5).reverse();

  return (
    <Card theme={theme} mode={mode} className="p-5">
      <h3 className={`text-lg font-semibold mb-4 ${txtMain}`}>最近消息</h3>
      <div className="space-y-2">
        {recentMessages.length === 0 ? (
          <p className={`text-sm ${txtSec} text-center py-4`}>暂无消息</p>
        ) : (
          recentMessages.map((msg, idx) => (
            <div key={msg.id || idx} className={`p-2 rounded-lg text-sm ${theme === 'clean' ? (mode === 'dark' ? 'bg-slate-700/50' : 'bg-slate-50') : 'bg-white/5'}`}>
              <div className="flex items-center gap-2 mb-1">
                <span className={`text-xs ${txtSec}`}>[{msg.time}]</span>
                <span className="text-sky-400 text-xs">[{msg.server}]</span>
                <span className={`text-xs ${msg.platform === 'Bedrock' ? 'text-amber-400' : 'text-emerald-400'}`}>
                  {msg.player}
                </span>
              </div>
              <p className={`${txtMain} truncate`}>{msg.content}</p>
            </div>
          ))
        )}
      </div>
    </Card>
  );
}

// Online Players Card
function OnlinePlayersCard({ theme, mode, txtMain, txtSec, players = [] }) {
  const displayPlayers = players.slice(0, 5);

  return (
    <Card theme={theme} mode={mode} className="p-5">
      <h3 className={`text-lg font-semibold mb-4 ${txtMain}`}>在线玩家</h3>
      <div className="space-y-2">
        {displayPlayers.length === 0 ? (
          <p className={`text-sm ${txtSec} text-center py-4`}>暂无在线玩家</p>
        ) : (
          displayPlayers.map((player) => (
            <div key={player.uuid} className={`flex items-center justify-between p-2 rounded-lg ${theme === 'clean' ? (mode === 'dark' ? 'bg-slate-700/50' : 'bg-slate-50') : 'bg-white/5'}`}>
              <div className="flex items-center gap-3">
                <Avatar name={player.name} size={24} rounded="rounded" />
                <div>
                  <p className={`text-sm font-medium ${txtMain}`}>{player.name}</p>
                  <p className={`text-xs ${txtSec}`}>{player.server}</p>
                </div>
              </div>
              <span className={`px-2 py-0.5 rounded text-xs ${player.platform === 'Java' ? 'bg-emerald-500/20 text-emerald-400' : 'bg-amber-500/20 text-amber-400'}`}>
                {player.platform}
              </span>
            </div>
          ))
        )}
      </div>
    </Card>
  );
}

export default DashboardView;
