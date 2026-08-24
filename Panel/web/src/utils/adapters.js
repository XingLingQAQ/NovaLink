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
 * Mapping of backend PlatformType enum names (the raw `platform` string the
 * backend sends over WS/REST) to i18n keys for human-readable display names.
 *
 * Backend platform values (confirmed from PlatformType.java + the WS handler):
 *   BUKKIT, VELOCITY, BUNGEECORD, NUKKIT, LEVILAMINA, FABRIC, NEOFORGE, QUILT,
 *   FORGE, POCKETMINE, ENDSTONE, POWERNUKKITX, FOLIA, SPONGE.
 * The backend also emits the literal "Java" (player_update fallback when the
 * player's platform is null) and "Unknown" (server_status fallback when the
 * client connection has no platform).
 *
 * `platform` is kept as the raw enum value on adapted objects because
 * downstream grouping / filtering (PLATFORM_BUCKETS in DashboardView, the
 * platform filter in PlayerManagement, serversByPlatform in ClientStatus)
 * matches against the raw enum name. Display sites call platformLabel() at
 * render time so the label re-localizes when the UI language changes.
 */
const PLATFORM_LABEL_KEYS = {
  BUKKIT: 'platform.bukkit',
  FOLIA: 'platform.folia',
  SPONGE: 'platform.sponge',
  VELOCITY: 'platform.velocity',
  BUNGEECORD: 'platform.bungeecord',
  NUKKIT: 'platform.nukkit',
  POWERNUKKITX: 'platform.powernukkitx',
  POCKETMINE: 'platform.pocketmine',
  ENDSTONE: 'platform.endstone',
  LEVILAMINA: 'platform.levilamina',
  FABRIC: 'platform.fabric',
  NEOFORGE: 'platform.neoforge',
  QUILT: 'platform.quilt',
  FORGE: 'platform.forge',
};

/**
 * Resolve the i18n key for a platform's display name.
 *   - null/undefined/empty/'Java' -> 'platform.java' (the backend's Java
 *     fallback sentinel and our own adapter fallback).
 *   - 'Unknown' -> 'platform.unknown' (the backend's server_status fallback).
 *   - any other non-empty, non-enum string -> 'platform.unknown' (never
 *     masquerade an unknown value as Java).
 *   - a known PlatformType enum name -> its specific platform.* key.
 */
export function platformLabelKey(platform) {
  if (platform == null || platform === '' || platform === 'Java') {
    return 'platform.java';
  }
  if (platform === 'Unknown') {
    return 'platform.unknown';
  }
  return PLATFORM_LABEL_KEYS[platform] || 'platform.unknown';
}

/**
 * Return the localized human-readable name for a platform. Call this at render
 * time (not at adapt time) so the label re-localizes when the UI language
 * changes; mirrors how buildDashboardStats stores i18n keys and lets the
 * consuming component translate them. Unknown values resolve to the localized
 * "unknown" label rather than being mislabeled as Java.
 */
export function platformLabel(platform) {
  return i18n.t(platformLabelKey(platform));
}

/**
 * Bedrock-side platform enum names (from PlatformType.java). Used by the
 * message stat counters and the message-line color so Bedrock-origin messages
 * are tallied/styled correctly once the backend forwards a platform field on
 * chat payloads. Today the backend omits platform on chat messages so every
 * message resolves to Java; this keeps the classification ready and correct.
 */
const BEDROCK_PLATFORMS = ['NUKKIT', 'POWERNUKKITX', 'POCKETMINE', 'ENDSTONE', 'LEVILAMINA'];

/**
 * True when the raw platform value identifies a Bedrock-family client.
 * null/empty/'Java' (the chat-message / player fallback) is NOT Bedrock.
 */
export function isBedrockPlatform(platform) {
  return BEDROCK_PLATFORMS.includes(platform);
}

/**
 * Map the current i18next language (zh_CN / en_US / bare "zh"/"en") to a
 * BCP-47 locale tag (zh-CN / en-US) for toLocale*String formatting.
 */
function currentLocale() {
  return (i18n.language || 'zh_CN').replace(/_/g, '-');
}

/**
 * Convert a timestamp (ms) to a time string HH:MM:SS in the current UI locale.
 */
function msToTime(ms) {
  const locale = currentLocale();
  if (!ms) return new Date().toLocaleTimeString(locale, { hour12: false });
  try {
    return new Date(Number(ms)).toLocaleTimeString(locale, { hour12: false });
  } catch {
    return new Date().toLocaleTimeString(locale, { hour12: false });
  }
}

/**
 * Adapt a REST /api/channels channel JSON object to the component channel shape.
 * Backend does not expose a "format" field in the REST JSON, so we leave it blank.
 *
 * PANEL-003: source (CONFIG/DATABASE/RUNTIME) and revision (long) are carried
 * through so the UI can render config-managed channels as read-only and detect
 * staleness. Missing source defaults to RUNTIME (dynamic/editable).
 */
export function adaptChannel(channelJson) {
  if (!channelJson) return null;
  const type = scopeToType(channelJson.scope);
  const parsedSlowMode = Number(channelJson.slowModeSeconds);
  const rawSource = channelJson.source;
  const source = rawSource === 'CONFIG' || rawSource === 'DATABASE' || rawSource === 'RUNTIME'
    ? rawSource
    : 'RUNTIME';
  const parsedRevision = Number(channelJson.revision);
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
    subscribable: channelJson.subscribable === true,
    sendable: channelJson.sendable === true,
    slowModeSeconds: Number.isInteger(parsedSlowMode) && parsedSlowMode >= 0
      ? parsedSlowMode
      : 0,
    source,
    revision: Number.isInteger(parsedRevision) ? parsedRevision : 0,
  };
}

/**
 * Adapt a REST /api/players player state JSON object to the component player shape.
 * Backend has no "muted" field; we default muted to false (mute state is managed
 * by the backend MuteManager, which is not exposed via REST). `platform` is the
 * PlatformType enum name (e.g. "BUKKIT", "NUKKIT") or null; we keep null/blank
 * mapped to the "Java" sentinel (the backend's own player_update fallback) so
 * platformLabel() treats missing values as Java rather than "unknown". The raw
 * enum value is preserved for the platform filter; render sites translate it.
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
 * Adapt a WS player_update entry to the component player shape.
 * Backend now enriches each entry with real name / server (clientId) / muted
 * alongside the original uuid + channels. name falls back to uuid when the
 * backend could not resolve it; server is null when unknown (shown as
 * 'unknown'); muted is a boolean. platform is now provided per-player by the
 * backend as the PlatformType enum name (e.g. "BUKKIT", "VELOCITY", "NUKKIT")
 * or null; we fall back to 'Java' when the backend omits it.
 */
export function adaptWsPlayer(wsPlayer) {
  if (!wsPlayer) return null;
  const channels = Array.isArray(wsPlayer.channels) ? wsPlayer.channels : [];
  return {
    uuid: wsPlayer.uuid,
    name: wsPlayer.name || wsPlayer.uuid,
    server: wsPlayer.server || 'unknown',
    channel: wsPlayer.activeChannel || channels[0] || 'global',
    platform: wsPlayer.platform || 'Java',
    muted: typeof wsPlayer.muted === 'boolean' ? wsPlayer.muted : !!wsPlayer.muted,
  };
}

/**
 * Adapt a WS server_status client JSON object to the component server shape.
 * Backend client now provides real platform/ping/players/version (in
 * addition to id, connectionId, remoteAddress, connectedAt, active).
 * platform is the PlatformType enum name (e.g. "BUKKIT", "VELOCITY") or
 * "Unknown" when the backend could not determine it. ping is in ms; players
 * is the online player count on that server. version is the real game
 * version string (e.g. "1.20.4") or empty; we fall back to '-' when the
 * backend omits it.
 */
export function adaptClient(clientJson) {
  if (!clientJson) return null;
  return {
    id: clientJson.id || clientJson.connectionId,
    name: clientJson.id || clientJson.connectionId || 'unknown',
    platform: clientJson.platform || 'Unknown',
    players: typeof clientJson.players === 'number' ? clientJson.players : 0,
    status: clientJson.active === false ? 'offline' : 'online',
    ping: typeof clientJson.ping === 'number' ? clientJson.ping : 0,
    version: clientJson.version || '-',
    remoteAddress: clientJson.remoteAddress || '',
    connectedAt: clientJson.connectedAt || 0,
  };
}

/**
 * Adapt a WS chat message to the component chatMessage shape.
 * Backend chat payload: { channelId, senderId, senderName, content, timestamp,
 * server }. server is the originating client id, or an empty string when the
 * sender is not a known game-server client (shown as 'unknown'). The backend
 * does not currently attach a `platform` field to chat payloads, so we keep
 * the adapter's 'Java' fallback (the same sentinel the backend uses for
 * players with no platform) — render sites call platformLabel() to map it to
 * a localized name, and the Java/Bedrock stat counters use isBedrockPlatform()
 * so future Bedrock-origin chat traffic counts correctly once the backend
 * starts forwarding a platform field.
 */
export function adaptChatMessage(msgJson) {
  if (!msgJson) return null;
  return {
    id: msgJson.id || `${msgJson.senderId}-${msgJson.timestamp}`,
    time: msToTime(msgJson.timestamp),
    server: msgJson.server || 'unknown',
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
 * Adapt a REST /api/bans ban JSON object to the component banned-player shape.
 * Backend GET /api/bans returns a list of entries, each shaped:
 *   { playerId, name?, bans: [ { channelId, expireTime, reason, operatorId, createdAt } ] }
 * - channelId is null for a global ban (across all channels).
 * - expireTime is 0 for a permanent ban.
 * - createdAt is the ban's creation epoch millis.
 * We flatten the bans array into one component row per ban, carrying the
 * playerId/name down so the unban call can target the right player. The
 * caller may pass a single ban entry (with a .bans array) or a pre-flattened
 * row (with a single .channelId/.expireTime/...); both resolve to the same
 * component shape.
 * Component prop shape (bannedPlayers entry):
 *   { uuid, name, reason, channelId, channelLabel, expireTime, permanent,
 *     remainingMs, operatorId, createdAt }
 */
export function adaptBan(banJson) {
  if (!banJson) return null;
  const playerId = banJson.playerId || '';
  const name = banJson.name || banJson.playerName || playerId;
  const banEntries = Array.isArray(banJson.bans) && banJson.bans.length > 0
    ? banJson.bans
    : [banJson];
  // Flatten each ban sub-entry into its own component row.
  return banEntries.map((b) => {
    const expireTime = b.expireTime || 0;
    const permanent = !expireTime || expireTime <= 0;
    const channelId = b.channelId || '';
    const isGlobal = !channelId || channelId === '(global)';
    const now = Date.now();
    let remainingMs;
    if (permanent) {
      remainingMs = -1;
    } else {
      const num = typeof expireTime === 'number' ? expireTime : Number(expireTime);
      remainingMs = Number.isNaN(num) ? 0 : num - now;
      if (remainingMs < 0) remainingMs = 0;
    }
    return {
      uuid: playerId,
      name,
      reason: b.reason || '',
      channelId: isGlobal ? '' : channelId,
      channelLabel: isGlobal ? '' : channelId,
      expireTime: permanent ? null : (typeof expireTime === 'number' ? expireTime : Number(expireTime)),
      permanent,
      remainingMs,
      operatorId: b.operatorId || '',
      createdAt: b.createdAt || 0,
    };
  }).filter(Boolean);
}

/**
 * Adapt a REST /api/notifications page item to the component notification shape.
 * Backend notification item: { id, title, message, level, createdAt, read }
 * level is "info" | "warning" | "error" — map to component type + icon. read
 * is a boolean preserved for unread highlighting. createdAt is epoch millis.
 */
export function adaptNotificationItem(itemJson) {
  if (!itemJson) return null;
  const level = itemJson.level || itemJson.type || 'info';
  let type = 'info';
  let icon = Info;
  if (level === 'warning' || level === 'warn') {
    type = 'warning';
    icon = AlertTriangle;
  } else if (level === 'error' || level === 'danger') {
    type = 'error';
    icon = AlertTriangle;
  } else if (level === 'success') {
    type = 'success';
    icon = CheckCircle;
  } else if (level === 'mute' || level === 'kick') {
    type = 'info';
    icon = UserX;
  }
  let time = i18n.t('notifications.default_time');
  if (itemJson.createdAt) {
    try {
      const num = typeof itemJson.createdAt === 'number' ? itemJson.createdAt : Number(itemJson.createdAt);
      if (!Number.isNaN(num)) time = new Date(num).toLocaleString();
    } catch {
      time = i18n.t('notifications.default_time');
    }
  }
  return {
    id: itemJson.id,
    title: itemJson.title || i18n.t('notifications.default_title'),
    desc: itemJson.message || itemJson.desc || itemJson.description || '',
    time,
    type,
    icon,
    read: !!itemJson.read,
    createdAt: itemJson.createdAt || 0,
  };
}

/**
 * Build the dashboard stats array from REST /api/status + live data.
 * Only uses real backend-provided fields; no fabricated numbers.
 * Returns i18n keys + interpolation params (no translated strings) so the
 * consuming component (DashboardView) renders them via useTranslation and
 * they re-localize on language change.
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
      titleKey: 'dashboard.kpi.online_servers',
      value: totalServers > 0 ? `${onlineServers}/${totalServers}` : '0',
      changeKey: totalServers === 0
        ? 'dashboard.kpi.no_connection'
        : (onlineServers === totalServers ? 'dashboard.kpi.all_online' : 'dashboard.kpi.offline_count'),
      changeParams: totalServers > 0 && onlineServers !== totalServers
        ? { count: totalServers - onlineServers }
        : undefined,
      trend: totalServers === 0 ? 'normal' : (onlineServers === totalServers ? 'up' : 'down'),
      icon: 'Server',
    },
    {
      titleKey: 'dashboard.kpi.online_players',
      value: String(playerCount),
      changeKey: playerCount > 0 ? 'dashboard.kpi.realtime' : 'dashboard.kpi.no_players',
      trend: playerCount > 0 ? 'up' : 'normal',
      icon: 'Users',
    },
    {
      titleKey: 'dashboard.kpi.channel_total',
      value: String(channelCount),
      changeKey: 'dashboard.kpi.registered',
      trend: 'normal',
      icon: 'Hash',
    },
    {
      titleKey: 'dashboard.kpi.session_messages',
      value: messageCount > 1000 ? `${(messageCount / 1000).toFixed(1)}k` : String(messageCount),
      changeKey: messageCount > 0 ? 'dashboard.kpi.this_session' : 'dashboard.kpi.none',
      trend: messageCount > 0 ? 'up' : 'normal',
      icon: 'MessageSquare',
    },
  ];
}

/**
 * Format a remaining-millis duration into a compact human string.
 * -1 / negative -> permanent label; 0 -> expired label.
 * Used by the mute/ban lists for the "remaining time" column.
 * @param {number} remainingMs
 * @param {object} opts - { permanentLabel, expiredLabel } i18n strings
 * @returns {string}
 */
export function formatRemainingMs(remainingMs, opts = {}) {
  const permanentLabel = opts.permanentLabel || i18n.t('players.expire_permanent');
  const expiredLabel = opts.expiredLabel || i18n.t('players.expire_permanent');
  if (remainingMs == null) return permanentLabel;
  if (remainingMs === -1) return permanentLabel;
  if (remainingMs <= 0) return expiredLabel;
  const ms = Number(remainingMs);
  if (Number.isNaN(ms)) return expiredLabel;
  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);
  if (days > 0) return `${days}d ${hours % 24}h`;
  if (hours > 0) return `${hours}h ${minutes % 60}m`;
  if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
  return `${seconds}s`;
}
