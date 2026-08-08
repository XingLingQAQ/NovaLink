/**
 * Player Management Component
 * Manage online players and muted players
 * 
 * Requirements: 24.3 - Player management functionality
 */

import React, { useState } from 'react';
import {
  Search,
  Users,
  UserX,
  MessageSquare,
  Shield,
  Clock,
  Filter,
  Info
} from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Modal from '../ui/Modal';
import CustomSelect from '../ui/CustomSelect';
import Avatar from '../ui/Avatar';

function PlayerManagement({ 
  theme, 
  mode, 
  txtMain, 
  txtSec, 
  players = [],
  mutedPlayers = [],
  onMutePlayer,
  onUnmutePlayer,
  onKickPlayer
}) {
  const [tab, setTab] = useState('online');
  const [searchQuery, setSearchQuery] = useState('');
  const [serverFilter, setServerFilter] = useState('all');
  const [platformFilter, setPlatformFilter] = useState('all');
  const [showMuteModal, setShowMuteModal] = useState(false);
  const [muteTarget, setMuteTarget] = useState({
    name: '',
    reason: '',
    duration: '1h',
    channel: 'all'
  });

  // Mute/unmute is not exposed to the panel via REST or WS.
  // The App-level handlers show an honest-disable toast.
  const muteActionDisabled = true;

  // Filter players
  const filteredPlayers = players.filter(p => {
    const matchesSearch = p.name.toLowerCase().includes(searchQuery.toLowerCase());
    const matchesServer = serverFilter === 'all' || p.server === serverFilter;
    const matchesPlatform = platformFilter === 'all' || p.platform === platformFilter;
    return matchesSearch && matchesServer && matchesPlatform;
  });

  // Get unique servers
  const uniqueServers = [...new Set(players.map(p => p.server))];

  // Handle mute — delegates to App (honest-disable toast, no fake mutation).
  const handleMute = (playerName) => {
    if (muteActionDisabled) {
      onMutePlayer && onMutePlayer({ name: playerName });
      return;
    }
    setMuteTarget({ ...muteTarget, name: playerName });
    setShowMuteModal(true);
  };

  // Confirm mute
  const confirmMute = () => {
    if (muteTarget.name && onMutePlayer) {
      onMutePlayer(muteTarget);
      setShowMuteModal(false);
      setMuteTarget({ name: '', reason: '', duration: '1h', channel: 'all' });
    }
  };

  // Duration options
  const durationOptions = [
    { value: '1h', label: '1 小时' },
    { value: '6h', label: '6 小时' },
    { value: '24h', label: '24 小时' },
    { value: '7d', label: '7 天' },
    { value: 'permanent', label: '永久' }
  ];

  return (
    <div className="space-y-4 animate-in fade-in duration-500">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className={`text-2xl font-bold ${txtMain}`}>玩家管理</h2>
          <p className={`text-sm ${txtSec} mt-1`}>
            管理在线玩家和禁言 · {players.length} 在线 · {mutedPlayers.length} 禁言
          </p>
        </div>

        {/* Tab Switcher */}
        <div className={`flex p-1 rounded-xl ${
          theme === 'clean'
            ? (mode === 'dark' ? 'bg-slate-800' : 'bg-slate-100')
            : 'bg-white/10'
        }`}>
          <button
            onClick={() => setTab('online')}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-all flex items-center gap-2 ${
              tab === 'online'
                ? (theme === 'clean' ? 'bg-white shadow text-sky-600' : 'bg-white/20 text-white')
                : txtSec
            }`}
          >
            <Users size={16} />
            在线玩家
          </button>
          <button 
            onClick={() => setTab('muted')} 
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-all flex items-center gap-2 ${
              tab === 'muted' 
                ? (theme === 'clean' ? 'bg-white shadow text-sky-600' : 'bg-white/20 text-white') 
                : txtSec
            }`}
          >
            <UserX size={16} />
            禁言列表
            {mutedPlayers.length > 0 && (
              <span className="bg-rose-500 text-white text-xs px-1.5 py-0.5 rounded-full">
                {mutedPlayers.length}
              </span>
            )}
          </button>
        </div>
      </div>

      {/* Honest-disable info banner */}
      {muteActionDisabled && (
        <Card theme={theme} mode={mode} className="p-3 flex items-start gap-2 border border-amber-500/20">
          <Info size={16} className="text-amber-400 shrink-0 mt-0.5" />
          <p className={`text-xs ${txtSec}`}>
            禁言与解除禁言操作需通过游戏内 /nc mute 或 /nc unmute 命令执行，面板暂不支持远程禁言。点击禁言按钮可查看说明。
          </p>
        </Card>
      )}

      {/* Online Players Tab */}
      {tab === 'online' && (
        <Card theme={theme} mode={mode} className="overflow-hidden">
          {/* Filters */}
          <div className={`p-4 border-b ${mode === 'dark' ? 'border-white/10' : 'border-slate-200'}`}>
            <div className="flex flex-wrap items-center gap-3">
              {/* Search */}
              <div className={`flex items-center gap-2 px-3 py-2 rounded-lg flex-1 min-w-[200px] ${
                theme === 'clean' 
                  ? (mode === 'dark' ? 'bg-slate-700' : 'bg-slate-100') 
                  : 'bg-white/10'
              }`}>
                <Search size={16} className={txtSec} />
                <input 
                  type="text" 
                  placeholder="搜索玩家..." 
                  className="bg-transparent border-none outline-none text-sm flex-1" 
                  style={{ color: mode === 'dark' ? 'white' : 'black' }}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                />
              </div>

              {/* Server Filter */}
              <CustomSelect 
                theme={theme} 
                mode={mode} 
                options={['all', ...uniqueServers]} 
                defaultValue={serverFilter}
                onChange={setServerFilter}
              />

              {/* Platform Filter */}
              <CustomSelect 
                theme={theme} 
                mode={mode} 
                options={['all', 'Java', 'Bedrock']} 
                defaultValue={platformFilter}
                onChange={setPlatformFilter}
              />
            </div>
          </div>

          {/* Player Table */}
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className={`text-xs uppercase tracking-wider ${txtSec} border-b ${mode === 'dark' ? 'border-white/10' : 'border-slate-200'}`}>
                  <th className="p-4 font-medium">玩家</th>
                  <th className="p-4 font-medium">服务器</th>
                  <th className="p-4 font-medium">频道</th>
                  <th className="p-4 font-medium">平台</th>
                  <th className="p-4 font-medium text-right">操作</th>
                </tr>
              </thead>
              <tbody className={`text-sm ${txtMain}`}>
                {filteredPlayers.map((player) => (
                  <tr 
                    key={player.uuid} 
                    className={`border-b ${mode === 'dark' ? 'border-white/5' : 'border-slate-100'} hover:bg-white/5 transition-colors`}
                  >
                    <td className="p-4">
                      <div className="flex items-center gap-3">
                        <Avatar name={player.name} size={32} rounded="rounded" />
                        <div>
                          <div className="font-medium">{player.name}</div>
                          {player.muted && (
                            <span className="text-xs text-red-400 flex items-center gap-1">
                              <UserX size={12} /> 已禁言
                            </span>
                          )}
                        </div>
                      </div>
                    </td>
                    <td className="p-4">{player.server}</td>
                    <td className="p-4">
                      <span className={`px-2 py-1 rounded text-xs ${
                        theme === 'clean' 
                          ? (mode === 'dark' ? 'bg-slate-700' : 'bg-slate-100') 
                          : 'bg-white/10'
                      }`}>
                        #{player.channel}
                      </span>
                    </td>
                    <td className="p-4">
                      <span className={`px-2 py-1 rounded text-xs ${
                        player.platform === 'Java' 
                          ? 'bg-emerald-500/20 text-emerald-400' 
                          : 'bg-amber-500/20 text-amber-400'
                      }`}>
                        {player.platform}
                      </span>
                    </td>
                    <td className="p-4 text-right">
                      <div className="flex items-center justify-end gap-2">
                        {!player.muted && (
                          <Button
                            theme={theme}
                            mode={mode}
                            variant="danger"
                            className="text-xs"
                            onClick={() => handleMute(player.name)}
                            title={muteActionDisabled ? '需通过游戏内 /nc mute 操作，面板暂不支持' : '禁言'}
                          >
                            禁言
                          </Button>
                        )}
                        {onKickPlayer && (
                          <Button
                            theme={theme}
                            mode={mode}
                            variant="ghost"
                            className="text-xs text-amber-400"
                            onClick={() => onKickPlayer(player.uuid)}
                          >
                            踢出
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
            <div className={`p-12 text-center ${txtSec}`}>
              <Users size={48} className="mx-auto mb-4 opacity-50" />
              <p>没有找到玩家</p>
            </div>
          )}
        </Card>
      )}

      {/* Muted Players Tab */}
      {tab === 'muted' && (
        <Card theme={theme} mode={mode} className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className={`text-xs uppercase tracking-wider ${txtSec} border-b ${mode === 'dark' ? 'border-white/10' : 'border-slate-200'}`}>
                  <th className="p-4 font-medium">玩家</th>
                  <th className="p-4 font-medium">原因</th>
                  <th className="p-4 font-medium">到期时间</th>
                  <th className="p-4 font-medium">操作者</th>
                  <th className="p-4 font-medium text-right">操作</th>
                </tr>
              </thead>
              <tbody className={`text-sm ${txtMain}`}>
                {mutedPlayers.map((mute) => (
                  <tr 
                    key={mute.uuid} 
                    className={`border-b ${mode === 'dark' ? 'border-white/5' : 'border-slate-100'} hover:bg-white/5 transition-colors`}
                  >
                    <td className="p-4">
                      <div className="flex items-center gap-3">
                        <Avatar name={mute.name} size={32} rounded="rounded" />
                        <span className="font-medium">{mute.name}</span>
                      </div>
                    </td>
                    <td className="p-4">{mute.reason}</td>
                    <td className="p-4">
                      <div className="flex items-center gap-1">
                        <Clock size={14} className={txtSec} />
                        <span className={mute.expireTime === '永久' ? 'text-rose-400' : ''}>
                          {mute.expireTime}
                        </span>
                      </div>
                    </td>
                    <td className="p-4">
                      <div className="flex items-center gap-1">
                        <Shield size={14} className={txtSec} />
                        {mute.operator}
                      </div>
                    </td>
                    <td className="p-4 text-right">
                      <Button
                        theme={theme}
                        mode={mode}
                        variant="ghost"
                        className="text-xs text-emerald-400"
                        onClick={() => onUnmutePlayer && onUnmutePlayer(mute.uuid)}
                        title={muteActionDisabled ? '需通过游戏内 /nc unmute 操作，面板暂不支持' : '解除禁言'}
                      >
                        解除禁言
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Empty State */}
          {mutedPlayers.length === 0 && (
            <div className={`p-12 text-center ${txtSec}`}>
              <MessageSquare size={48} className="mx-auto mb-4 opacity-50" />
              <p>暂无禁言记录</p>
            </div>
          )}
        </Card>
      )}

      {/* Mute Modal */}
      <Modal 
        isOpen={showMuteModal} 
        onClose={() => setShowMuteModal(false)} 
        title="禁言玩家" 
        theme={theme} 
        mode={mode}
      >
        <div className="space-y-4">
          <div>
            <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>
              玩家名称
            </label>
            <input 
              type="text" 
              value={muteTarget.name} 
              onChange={(e) => setMuteTarget({ ...muteTarget, name: e.target.value })}
              placeholder="输入玩家名"
              className={`w-full px-4 py-2.5 rounded-xl border outline-none focus:ring-2 transition-all ${
                theme === 'clean' 
                  ? (mode === 'dark' ? 'bg-slate-700 border-slate-600 focus:ring-sky-500 text-white' : 'bg-white border-slate-200 focus:ring-sky-500 text-slate-900') 
                  : 'bg-white/10 border-white/20 focus:ring-white/50 text-white placeholder:text-white/30'
              }`}
            />
          </div>
          <div>
            <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>
              禁言原因
            </label>
            <input 
              type="text" 
              value={muteTarget.reason} 
              onChange={(e) => setMuteTarget({ ...muteTarget, reason: e.target.value })}
              placeholder="违规行为"
              className={`w-full px-4 py-2.5 rounded-xl border outline-none focus:ring-2 transition-all ${
                theme === 'clean' 
                  ? (mode === 'dark' ? 'bg-slate-700 border-slate-600 focus:ring-sky-500 text-white' : 'bg-white border-slate-200 focus:ring-sky-500 text-slate-900') 
                  : 'bg-white/10 border-white/20 focus:ring-white/50 text-white placeholder:text-white/30'
              }`}
            />
          </div>
          <div>
            <label className={`block text-xs font-semibold uppercase tracking-wider mb-1.5 ${txtSec}`}>
              时长
            </label>
            <CustomSelect 
              theme={theme} 
              mode={mode} 
              options={durationOptions.map(d => d.value)} 
              defaultValue="1h"
              onChange={(val) => setMuteTarget({ ...muteTarget, duration: val })}
            />
          </div>
          <div className="flex gap-3 mt-6 pt-4 border-t border-gray-200/10">
            <Button variant="ghost" className="flex-1" theme={theme} mode={mode} onClick={() => setShowMuteModal(false)}>
              取消
            </Button>
            <Button variant="primary" className="flex-1" theme={theme} mode={mode} onClick={confirmMute}>
              确认禁言
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}

export default PlayerManagement;
