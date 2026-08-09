/**
 * Adapter functions that transform NovaLink backend REST/WS response shapes
 * into the prop shapes expected by the dashboard components.
 *
 * Backend REST shapes (from RestApiHandler.java / HttpAuthHandler.java):
 *   GET /api/status -> { status, version, channelCount, playerCount, timestamp }
 *   GET /api/channels -> { channels: [ChannelJson], total }
 *     ChannelJson = { id, displayName, scope, clientId, memberCount, maxCapacity, permission?, allowedWorlds? }
 *   GET /api/players -> { players: [PlayerStateJson], total }
 *     PlayerStateJson = { uuid, name, clientId, currentWorld, activeChannel, joinedChannels: [] }
 *   GET /api/webhooks -> { webhooks: [WebhookJson], total }
 *     WebhookJson = { id, url, event, createdAt, lastTriggered }
 *
 * Backend WS shapes (from WebSocketMessageHandler.java):
 *   chat -> { type:"chat", channelId, senderId, senderName, content, timestamp }
 *   server_status -> { type:"server_status", clients:[ClientJson], totalConnections, timestamp }
 *     ClientJson = { id, connectionId, remoteAddress, connectedAt, active }
 *   channel_update -> { type:"channel_update", channels:[ChannelJson], timestamp }
 *   player_update -> { type:"player_update", players:[{uuid, channels}], totalPlayers, timestamp }
 *   notification -> { type:"notification", title, message, level, timestamp }
 *
 * Component prop shapes:
 *   servers:    { id, name, platform, players, status, ping, version }
 *   channels:   { id, name, type, permission, format, icon, color }
 *   players:    { uuid, name, server, channel, platform, muted }
 *   chatMessages: { id, time, server, player, channel, content, platform }
 *   notifications: { id, title, desc, time, type, read, icon? }
 */

import { Globe, Hash, Lock, Shield, AlertTriangle, CheckCircle, Info, UserX } from 'lucide-react';
import i18n from '../i18n';

/**
 * Map a backend channel scope to the component "type" field.
 * ChannelScope enum: GLOBAL | SERVER | PRIVATE
 */
export function scopeToType(scope) {
  if (!scope) return 'SERVER';
  if (scope === 'GLOBAL') return 'GLOBAL';
  if (scope === 'PRIVATE') return 'PRIVATE';
  return 'SERVER';
}

/**
 * Pick a lucide icon reference for a channel based on its scope/type.
 */
export function channelIconForType(type) {
  switch (type) {
    case 'GLOBAL':
      return Globe;
    case 'PRIVATE':
      return Lock;
    case 'SERVER':
    case 'LOCAL':
      return Hash;
    default:
      return Hash;
  }
}

/**
 * Pick a tailwind color token for a channel based on its type.
 */
export function channelColorForType(type) {
  switch (type) {
    case 'GLOBAL':
      return 'blue';
    case 'PRIVATE':
      return 'yellow';
    case 'SERVER':
    case 'LOCAL':
    default:
      return 'gray';
  }
}

/**
 * Convert ISO timestamp (ms) to a zh-CN time string HH:MM:SS.
 */
function msToTime(ms) {
  if (!ms) return new Date().toLocaleTimeString('zh-CN', { hour12: false });
  try {
    return new Date(Number(ms)).toLocaleTimeString('zh-CN', { hour12: false });
  } catch {
    return new Date().toLocaleTimeString('zh-CN', { hour12: false });
  }
}

/**
 * Adapt a REST /api/channels channel JSON object to the component channel shape.
 * Backend does not expose a "format" field in the REST JSON, so we leave it blank.
 */
export function adaptChannel(channelJson) {
  if (!channelJson) return null;
  const type = scopeToType(channelJson.scope);
  return {
    id: channelJson.id,
    name: channelJson.displayName || channelJson.id,
    type,
    permission: channelJson.permission || '',
    format: channelJson.format || '',
    icon: channelIconForType(type),
    color: channelColorForType(type),
    memberCount: channelJson.memberCount || 0,
    maxCapacity: channelJson.maxCapacity || 0,
    clientId: channelJson.clientId || null,
  };
}

/**
 * Adapt a REST /api/players player state JSON object to the component player shape.
 * Backend has no "platform" or "muted" field; we default platform to 'Java' and muted to false
 * (mute state is managed by the backend MuteManager, which is not exposed via REST).
 */
export function adaptPlayer(playerJson) {
  if (!playerJson) return null;
  return {
    uuid: playerJson.uuid,
    name: playerJson.name || playerJson.uuid,
    server: playerJson.clientId || 'unknown',
    channel: playerJson.activeChannel || (playerJson.joinedChannels && playerJson.joinedChannels[0]) || 'global',
    platform: playerJson.platform || 'Java',
    muted: !!playerJson.muted,
  };
}

/**
 * Adapt a WS player_update entry ({uuid, channels}) to the component player shape.
 * The WS player_update payload is minimal (uuid + joined channels), so several
 * component fields fall back to placeholders.
 */
export function adaptWsPlayer(wsPlayer) {
  if (!wsPlayer) return null;
  const channels = Array.isArray(wsPlayer.channels) ? wsPlayer.channels : [];
  return {
    uuid: wsPlayer.uuid,
    name: wsPlayer.name || wsPlayer.uuid,
    server: wsPlayer.clientId || 'unknown',
    channel: wsPlayer.activeChannel || channels[0] || 'global',
    platform: wsPlayer.platform || 'Java',
    muted: !!wsPlayer.muted,
  };
}

/**
 * Adapt a WS server_status client JSON object to the component server shape.
 * Backend client has { id, connectionId, remoteAddress, connectedAt, active } —
 * no platform/players/ping/version. We derive name from id, status from active,
 * and leave the rest as placeholders.
 */
export function adaptClient(clientJson) {
  if (!clientJson) return null;
  return {
    id: clientJson.id || clientJson.connectionId,
    name: clientJson.id || clientJson.connectionId || 'unknown',
    platform: clientJson.platform || 'Unknown',
    players: clientJson.players || 0,
    status: clientJson.active === false ? 'offline' : 'online',
    ping: clientJson.ping || 0,
    version: clientJson.version || '-',
    remoteAddress: clientJson.remoteAddress || '',
    connectedAt: clientJson.connectedAt || 0,
  };
}

/**
 * Adapt a WS chat message to the component chatMessage shape.
 * Backend chat payload: { channelId, senderId, senderName, content, timestamp }
 * No server/platform in the payload — we use channelId as the server proxy and default platform.
 */
export function adaptChatMessage(msgJson) {
  if (!msgJson) return null;
  return {
    id: msgJson.id || `${msgJson.senderId}-${msgJson.timestamp}`,
    time: msToTime(msgJson.timestamp),
    server: msgJson.server || msgJson.clientId || msgJson.channelId,
    player: msgJson.senderName || msgJson.sender || 'unknown',
    channel: msgJson.channelId || msgJson.channel || 'global',
    content: msgJson.content || '',
    platform: msgJson.platform || 'Java',
  };
}

/**
 * Adapt a REST /api/mutes mute JSON object to the component muted-player shape.
 * Backend mute JSON (from GET /api/mutes):
 *   { playerId, playerName, channelId, reason, expireTime, remainingMs, permanent }
 * - channelId is the literal "(global)" sentinel when the mute is global.
 * - expireTime is 0 for permanent; remainingMs is -1 for permanent, 0 when expired.
 * - permanent is a boolean (true when expireTime <= 0).
 * Component prop shape (mutedPlayers entry):
 *   { uuid, name, reason, expireTime, channelId, permanent, remainingMs, operator }
 * The backend does not expose an "operator" field on the REST mute list, so we
 * leave it blank (the column is rendered only when present).
 */
export function adaptMute(muteJson) {
  if (!muteJson) return null;
  const permanent = !!muteJson.permanent;
  const channelId = muteJson.channelId || '';
  // Treat the backend "(global)" sentinel as a global mute (no specific channel).
  const isGlobal = !channelId || channelId === '(global)';
  return {
    uuid: muteJson.playerId || '',
    name: muteJson.playerName || muteJson.playerId || '',
    reason: muteJson.reason || '',
    expireTime: permanent ? null : (muteJson.expireTime || 0),
    channelId: isGlobal ? '' : channelId,
    channelLabel: isGlobal ? '' : channelId,
    permanent,
    remainingMs: muteJson.remainingMs != null ? muteJson.remainingMs : (permanent ? -1 : 0),
    operator: muteJson.operator || '',
  };
}

/**
 * Adapt a WS notification to the component notification shape.
 * Backend notification payload: { title, message, level, timestamp }
 * level is "info" | "warning" | "error" — map to component type + icon.
 */
export function adaptNotification(notifJson) {
  if (!notifJson) return null;
  const level = notifJson.level || notifJson.type || 'info';
  let type = 'info';
  let icon = Info;
  if (level === 'warning' || level === 'warn') {
    type = 'warning';
    icon = AlertTriangle;
  } else if (level === 'error' || level === 'danger') {
    type = 'warning';
    icon = AlertTriangle;
  } else if (level === 'success') {
    type = 'success';
    icon = CheckCircle;
  } else if (level === 'mute' || level === 'kick') {
    type = 'info';
    icon = UserX;
  }
  return {
    id: notifJson.id || `notif-${notifJson.timestamp || Date.now()}`,
    title: notifJson.title || i18n.t('notifications.default_title'),
    desc: notifJson.desc || notifJson.message || notifJson.description || '',
    time: notifJson.time || i18n.t('notifications.default_time'),
    type,
    icon,
    read: false,
  };
}

/**
 * Build the dashboard stats array from REST /api/status + live data.
 * Only uses real backend-provided fields; no fabricated numbers.
 */
export function buildDashboardStats(statusJson, servers, channels, chatMessages) {
  const onlineServers = servers.filter((s) => s.status === 'online').length;
  const totalServers = servers.length;
  const playerCount = statusJson && typeof statusJson.playerCount === 'number'
    ? statusJson.playerCount
    : 0;
  const channelCount = statusJson && typeof statusJson.channelCount === 'number'
    ? statusJson.channelCount
    : channels.length;
  const messageCount = chatMessages ? chatMessages.length : 0;

  return [
    {
      title: '在线服务器',
      titleKey: 'dashboard.online_servers',
      value: totalServers > 0 ? `${onlineServers}/${totalServers}` : '0',
      change: totalServers === 0 ? '无连接' : (onlineServers === totalServers ? '全部在线' : `${totalServers - onlineServers} 离线`),
      changeKey: totalServers === 0 ? 'dashboard.change_none' : (onlineServers === totalServers ? 'dashboard.change_all_online' : undefined),
      changeOfflineCount: totalServers === 0 ? undefined : (onlineServers === totalServers ? undefined : totalServers - onlineServers),
      trend: totalServers === 0 ? 'normal' : (onlineServers === totalServers ? 'up' : 'down'),
      icon: 'Server',
    },
    {
      title: '在线玩家',
      titleKey: 'dashboard.online_players',
      value: String(playerCount),
      change: playerCount > 0 ? '实时' : '无玩家',
      changeKey: playerCount > 0 ? 'dashboard.change_realtime' : 'dashboard.change_none',
      trend: playerCount > 0 ? 'up' : 'normal',
      icon: 'Users',
    },
    {
      title: '频道总数',
      titleKey: 'dashboard.total_channels',
      value: String(channelCount),
      change: '已注册',
      changeKey: 'dashboard.change_registered',
      trend: 'normal',
      icon: 'Hash',
    },
    {
      title: '会话消息',
      titleKey: 'dashboard.session_messages',
      value: messageCount > 1000 ? `${(messageCount / 1000).toFixed(1)}k` : String(messageCount),
      change: messageCount > 0 ? '本会话' : '暂无',
      changeKey: messageCount > 0 ? 'dashboard.change_this_session' : 'dashboard.change_none',
      trend: messageCount > 0 ? 'up' : 'normal',
      icon: 'MessageSquare',
    },
  ];
}
