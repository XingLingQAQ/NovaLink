/**
 * REST API client for NovaLink backend.
 * Wraps fetch with JWT auth headers and base URL handling.
 *
 * All non-auth endpoints require `Authorization: Bearer <JWT>`.
 */

import authService from './auth.js';
import i18n from '../i18n.js';
import { getApiBaseUrl } from './connectionUrls.js';

export {
  clearConnectionUrls,
  getApiBaseUrl,
  getWsUrl,
  setConnectionUrls,
} from './connectionUrls.js';

/**
 * Perform a fetch against the REST API with auth headers.
 * On 401, transparently attempts a single token refresh + retry before
 * surfacing the error (avoids kicking the user on a stale-but-refreshable
 * token). The refresh call itself never recurses on its own 401.
 * @param {string} path - path relative to API base, e.g. '/channels'
 * @param {object} options - fetch options (method, body, etc.)
 * @param {boolean} _isRetry - internal guard against refresh loops
 * @returns {Promise<object>} - parsed JSON response
 * @throws {Error} on non-2xx with server message
 */
export async function apiFetch(path, options = {}, _isRetry = false) {
  const base = getApiBaseUrl();
  const url = `${base}${path.startsWith('/') ? path : `/${path}`}`;
  const headers = {
    'Content-Type': 'application/json',
    ...authService.getAuthHeader(),
    ...(options.headers || {}),
  };

  let response;
  try {
    response = await fetch(url, { ...options, headers });
  } catch (err) {
    throw new Error(i18n.t('common.api_error_connect', { error: err.message || err }));
  }

  let data = null;
  const text = await response.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { raw: text };
    }
  }

  // 401: try a single token refresh + retry before giving up. The _isRetry
  // guard ensures a refresh-induced 401 (or a refresh endpoint 401) doesn't
  // recurse infinitely.
  if (response.status === 401 && !_isRetry) {
    // AuthService owns the single deterministic logout. Its actual refresh
    // error deliberately propagates to every caller sharing the refresh.
    const newToken = await authService.refreshAccessToken(base);
    if (newToken) {
      return apiFetch(path, options, true);
    }
  }

  if (!response.ok) {
    const message = (data && (data.message || data.error)) || i18n.t('common.api_error_request', { status: response.status });
    const error = new Error(message);
    error.status = response.status;
    error.data = data;
    throw error;
  }

  return data;
}

/**
 * Convenience methods for the endpoints used by the panel.
 */
export const api = {
  status: () => apiFetch('/status'),
  getChannels: () => apiFetch('/channels'),
  getChannel: (id) => apiFetch(`/channels/${encodeURIComponent(id)}`),
  getChannelMembers: (id) => apiFetch(`/channels/${encodeURIComponent(id)}/members`),
  getPlayers: () => apiFetch('/players'),
  getPlayer: (id) => apiFetch(`/players/${encodeURIComponent(id)}`),
  getWebhooks: () => apiFetch('/webhooks'),
  createWebhook: (body) => apiFetch('/webhooks', { method: 'POST', body: JSON.stringify(body) }),
  deleteWebhook: (id) => apiFetch(`/webhooks/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  sendMessage: (channelId, content, senderName = 'Panel') =>
    apiFetch('/messages', {
      method: 'POST',
      body: JSON.stringify({ channelId, content, senderName }),
    }),

  // --- Message history (batch 4) ---
  // GET /api/messages?page=&size=&channel=&server=&player=&q=&from=&to=
  //   -> { items: [{id, channelId, senderId, senderName, clientId, content,
  //        timestamp}], page, pageSize, total }
  // page is 1-based; channel/server/player/q may be empty; from/to are epoch
  // millis or empty. All keys are always sent (empty string = no filter),
  // matching the locked backend contract.
  getMessages: ({ page = 1, size = 50, channel = '', server = '', player = '', q = '', from = '', to = '' } = {}) => {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
      channel: channel || '',
      server: server || '',
      player: player || '',
      q: q || '',
      from: from === '' || from == null ? '' : String(from),
      to: to === '' || to == null ? '' : String(to),
    });
    return apiFetch(`/messages?${params.toString()}`);
  },

  // --- Channel CRUD (batch 2) ---
  // createChannel body shape (PANEL-003): { id?, displayName, scope, clientId?,
  //   maxCapacity, permission?, slowModeSeconds? }. SERVER/PRIVATE scope MUST
  //   include a clientId referencing a real connected client; GLOBAL omits it.
  //   The caller (ChannelManagement.handleCreate) is responsible for assembling
  //   the body including clientId — api.js only serializes what it receives.
  createChannel: (body) => apiFetch('/channels', { method: 'POST', body: JSON.stringify(body) }),
  // updateChannel body shape (PANEL-003): { displayName?, maxCapacity?, permission?,
  //   permissionPresent?, slowModeSeconds? }. To CLEAR the permission, send
  //   { permissionPresent: true, permission: null } — a bare null permission is
  //   treated as "leave untouched" by the legacy overload. The caller
  //   (ChannelManagement.handleSaveEdit) sets permissionPresent explicitly.
  updateChannel: (id, body) => apiFetch(`/channels/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteChannel: (id) => apiFetch(`/channels/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  invitePlayer: (channelId, body) => apiFetch(`/channels/${encodeURIComponent(channelId)}/invite`, { method: 'POST', body: JSON.stringify(body || {}) }),

  // --- Player mute / unmute / kick (batch 2) ---
  mutePlayer: (uuid, body) => apiFetch(`/players/${encodeURIComponent(uuid)}/mute`, { method: 'POST', body: JSON.stringify(body || {}) }),
  unmutePlayer: (uuid, body) => apiFetch(`/players/${encodeURIComponent(uuid)}/unmute`, { method: 'POST', body: JSON.stringify(body || {}) }),
  getMutes: () => apiFetch('/mutes'),
  kickPlayer: (uuid, body) => apiFetch(`/players/${encodeURIComponent(uuid)}/kick`, { method: 'POST', body: JSON.stringify(body || {}) }),

  // --- Player ban / unban (batch 3) ---
  // body: { channelId?, durationMs, reason } — durationMs 0 = permanent;
  // channelId omitted/empty = global ban.
  banPlayer: (uuid, body) => apiFetch(`/players/${encodeURIComponent(uuid)}/ban`, { method: 'POST', body: JSON.stringify(body || {}) }),
  unbanPlayer: (uuid, body) => apiFetch(`/players/${encodeURIComponent(uuid)}/unban`, { method: 'POST', body: JSON.stringify(body || {}) }),
  getBans: () => apiFetch('/bans'),

  // --- Notifications (batch 3) ---
  // GET /api/notifications?page=&size=&unreadOnly= -> { items, total, unreadCount }
  // page is 1-based; total is the real row count.
  // PANEL-014: reads/marks/clears are per-user (userId derived from JWT by the
  // backend); clearNotifications only deletes directed notifications for the
  // caller. Broadcast events require the separate SUPER_ADMIN route below.
  getNotifications: (page = 1, size = 20, unreadOnly = false) =>
    apiFetch(`/notifications?page=${page}&size=${size}&unreadOnly=${unreadOnly}`),
  markNotificationRead: (id) => apiFetch(`/notifications/${encodeURIComponent(id)}/read`, { method: 'POST' }),
  markAllNotificationsRead: () => apiFetch('/notifications/read-all', { method: 'POST' }),
  clearNotifications: () => apiFetch('/notifications', { method: 'DELETE' }),
  // PANEL-014: SUPER_ADMIN-only global broadcast cleanup. Deletes every
  // notification row (both broadcast and directed). Audited as
  // notification.clear_broadcast.
  clearBroadcastNotifications: () => apiFetch('/notifications/broadcast', { method: 'DELETE' }),

  // --- Settings (batch 3) ---
  // Newer backends also expose privateMessagesEnabled and
  // messageLogRetentionDays; callers feature-detect those optional fields.
  // PANEL-010: the response includes a numeric `revision`. Pass it back as
  // baseRevision on the next update so the server can detect a stale write and
  // return 409 with the current server state.
  getSettings: () => apiFetch('/settings'),
  updateSettings: (body, baseRevision) => apiFetch('/settings', {
    method: 'PUT',
    headers: baseRevision != null ? { 'If-Match': `W/"${baseRevision}"` } : {},
    body: JSON.stringify(baseRevision != null ? { ...body, baseRevision } : body),
  }),

  // --- Config history (§11.6 Project 20 / PANEL proposal 10) ---
  // GET /api/settings/history?limit= -> { items: [{id, revision, createdAt,
  //   createdBy?, active}] } — masked config snapshots newest first, NO
  //   payload. ADMIN+ only. 503 when the service is unavailable.
  getConfigHistory: (limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    return apiFetch(`/settings/history?${params.toString()}`);
  },
  // GET /api/settings/snapshots/{revision} -> { id, revision, createdAt,
  //   createdBy?, active, snapshot } — snapshot is the masked JSON object.
  //   404 when the revision is absent; 503 when the service is down.
  getConfigSnapshot: (revision) =>
    apiFetch(`/settings/snapshots/${encodeURIComponent(revision)}`),
  // GET /api/settings/diff?from=&to= -> { fromRevision, toRevision, added,
  //   removed, changed } — masked diff. 400 missing params; 404 missing
  //   revision; 500 NC-510.
  getConfigDiff: (from, to) => {
    const params = new URLSearchParams({ from: String(from), to: String(to) });
    return apiFetch(`/settings/diff?${params.toString()}`);
  },
  // POST /api/settings/rollback { targetRevision } -> { success, rolledBackTo,
  //   revision } — SUPER_ADMIN only. 400 missing/already-active; 404 not
  //   found; 500 NC-510 (fail-closed, live config unchanged).
  rollbackConfig: (targetRevision) => apiFetch('/settings/rollback', {
    method: 'POST',
    body: JSON.stringify({ targetRevision }),
  }),
  // POST /api/settings/validate { yaml } -> { valid, errors:[{path,message}],
  //   warnings, revision, checkedAt } — ADMIN+ only. Validates a YAML config
  //   text WITHOUT applying it. 503 when the validator service is unavailable.
  validateConfig: (yamlText) => apiFetch('/settings/validate', {
    method: 'POST',
    body: JSON.stringify({ yaml: yamlText }),
  }),

  // --- Config drafts / backups / publish (§11.6 item 20 / 提案 10 doc-deferred) ---
  // All SUPER_ADMIN-only (backend-enforced); all responses masked. The draft
  // workflow is: create (DRAFT) -> approve (APPROVED, approver != createdBy or
  // 403) -> publish (PUBLISHED, requires APPROVED or 409) -> live config +
  // backup snapshot. Discard is DRAFT-only. Restore-from-backup rolls the
  // live config back to a backup revision.
  // GET /api/settings/drafts?limit= -> [{draftId,status,createdAt,createdBy,
  //   approvedAt,publishedAt}] — draft list, newest first.
  listDrafts: (limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    return apiFetch(`/settings/drafts?${params.toString()}`);
  },
  // POST /api/settings/drafts { yaml } -> { draftId, status:"DRAFT",
  //   validation:{valid,errors[],warnings[]}, createdAt, createdBy }. 400 +
  //   validation report when the YAML is invalid.
  createDraft: (yamlText) => apiFetch('/settings/drafts', {
    method: 'POST',
    body: JSON.stringify({ yaml: yamlText }),
  }),
  // GET /api/settings/drafts/{id} -> full draft { draftId, status, draft_yaml,
  //   validation, createdAt, createdBy, approvedBy, approvedAt, publishedAt,
  //   publishedRevision, note }.
  getDraft: (draftId) =>
    apiFetch(`/settings/drafts/${encodeURIComponent(draftId)}`),
  // POST /api/settings/drafts/{id}/approve { note } -> { draftId,
  //   status:"APPROVED", approvedBy, approvedAt }. 403 when approver == createdBy.
  approveDraft: (draftId, note) => apiFetch(`/settings/drafts/${encodeURIComponent(draftId)}/approve`, {
    method: 'POST',
    body: JSON.stringify({ note }),
  }),
  // POST /api/settings/drafts/{id}/publish -> { revision, backupId,
  //   publishedAt }. 409 when the draft is not APPROVED.
  publishDraft: (draftId) => apiFetch(`/settings/drafts/${encodeURIComponent(draftId)}/publish`, {
    method: 'POST',
  }),
  // DELETE /api/settings/drafts/{id} -> 204 (DRAFT-only; backend 409 otherwise).
  discardDraft: (draftId) => apiFetch(`/settings/drafts/${encodeURIComponent(draftId)}`, {
    method: 'DELETE',
  }),
  // POST /api/settings/backup { label } -> { backupId, label, revision,
  //   createdAt, createdBy }.
  createBackup: (label) => apiFetch('/settings/backup', {
    method: 'POST',
    body: JSON.stringify({ label }),
  }),
  // GET /api/settings/backups?limit= -> [{ backupId, label, revision,
  //   createdAt, createdBy }] — backup list, newest first.
  listBackups: (limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    return apiFetch(`/settings/backups?${params.toString()}`);
  },
  // POST /api/settings/restore-from-backup { backupId } -> { revision,
  //   restoredFromBackupId }.
  restoreFromBackup: (backupId) => apiFetch('/settings/restore-from-backup', {
    method: 'POST',
    body: JSON.stringify({ backupId }),
  }),

  // --- Audit log (PANEL-006) ---
  // GET /api/audit?page=&size=&actor=&action= -> { items, total, page, pageSize }
  // page is 1-based; actor/action are optional substring/exact filters.
  // ADMIN+ only; read access is separate from notification clear.
  getAuditEvents: (page = 1, size = 20, actor = '', action = '') => {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    });
    if (actor) params.set('actor', actor);
    if (action) params.set('action', action);
    return apiFetch(`/audit?${params.toString()}`);
  },

  // --- Announcements (batch 4) ---
  // GET /api/announcements -> { items: [{id, type: "JOIN"|"CRON", channelId,
  //   content, cron, enabled, createdAt}], total } — only persisted JOIN/CRON
  //   announcements; cron is set for CRON type only.
  getAnnouncements: () => apiFetch('/announcements'),
  // body: { type: "INSTANT"|"JOIN"|"CRON", channelId, content, cron? }
  // INSTANT sends immediately and returns { sent: true }; JOIN/CRON return
  // the created announcement object.
  createAnnouncement: (body) => apiFetch('/announcements', { method: 'POST', body: JSON.stringify(body) }),
  // body: { enabled: boolean } -> updated announcement object (enable/disable)
  updateAnnouncement: (id, body) => apiFetch(`/announcements/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteAnnouncement: (id) => apiFetch(`/announcements/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  // --- Campaigns (§11.6 提案 06 / item 19 — slice A: in-memory) ---
  // GET /api/campaigns[?channelId=] -> { items:[campaignJson], total } (VIEWER).
  //   campaignJson = { id, channelId, platforms:[...], content, status,
  //     scheduleRevision, deliveryPolicy, startAt, endAt,
  //     rateLimitPerChannelPerHour, createdAt, revokedAt, revokedBy(string|null) }
  //   All campaign routes return 503 "Campaigns not enabled" when the backend
  //   CampaignManager is null.
  getCampaigns: (channelId) => {
    const params = new URLSearchParams();
    if (channelId) params.set('channelId', channelId);
    const q = params.toString();
    return apiFetch(q ? `/campaigns?${q}` : '/campaigns');
  },
  getCampaign: (id) => apiFetch(`/campaigns/${encodeURIComponent(id)}`),
  // body: { channelId, content, platforms:[...], deliveryPolicy?, startAt?,
  //   endAt?, rateLimitPerHour? } -> 201 campaignJson (ADMIN). The backend reads
  //   `rateLimitPerHour` from the request and maps it to the internal
  //   `rateLimitPerChannelPerHour`.
  createCampaign: (body) => apiFetch('/campaigns', { method: 'POST', body: JSON.stringify(body) }),
  // POST /api/campaigns/{id}/schedule -> campaignJson (PREVIEW→SCHEDULED, or
  //   PREVIEW→ACTIVE when startAt=0; bumps scheduleRevision). ADMIN.
  scheduleCampaign: (id) => apiFetch(`/campaigns/${encodeURIComponent(id)}/schedule`, { method: 'POST' }),
  // POST /api/campaigns/{id}/activate -> campaignJson (SCHEDULED→ACTIVE,
  //   delivers once subject to the per-channel/per-hour rate limit). ADMIN.
  activateCampaign: (id) => apiFetch(`/campaigns/${encodeURIComponent(id)}/activate`, { method: 'POST' }),
  // POST /api/campaigns/{id}/revoke -> campaignJson (any non-terminal→REVOKED;
  //   cancels armed task, stamps revokedAt/revokedBy). SUPER_ADMIN only.
  revokeCampaign: (id) => apiFetch(`/campaigns/${encodeURIComponent(id)}/revoke`, { method: 'POST' }),

  // --- Word filter (batch 4) ---
  // GET /api/filter -> { enabled: boolean, words: [string], patterns: [string] }
  getFilter: () => apiFetch('/filter'),
  // PUT /api/filter { enabled?, words?, patterns? } -> full updated state;
  // provided arrays fully replace the stored lists.
  updateFilter: (body) => apiFetch('/filter', { method: 'PUT', body: JSON.stringify(body) }),

  // --- Webhook update / test (batch 4) ---
  // PUT /api/webhooks/{id} { url?, events?, secret?, active? } -> updated object
  updateWebhook: (id, body) => apiFetch(`/webhooks/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) }),
  // POST /api/webhooks/{id}/test -> { success: boolean, statusCode?, error? }
  testWebhook: (id) => apiFetch(`/webhooks/${encodeURIComponent(id)}/test`, { method: 'POST' }),

  // --- Moderation cases / appeals (PANEL-007) ---
  // Locked contract with the backend moderation agent. Cases are the unit of
  // abuse handling; evidence is the ONLY endpoint that returns private-chat
  // content, and only as a minimal snapshot scoped to a case. Appeals have a
  // separate reviewer (backend enforces reviewer != assignedModerator via 403).
  // POST /api/reports -> 201 { caseId, status:"OPEN" }
  //   body: { reportedPlayerId, reasonCode, reasonText, originChannelId?,
  //           evidenceSnapshot? }
  createReport: (payload) => apiFetch('/reports', { method: 'POST', body: JSON.stringify(payload) }),
  // GET /api/moderation/cases?page=&size=&status=&assigned=
  //   -> { items:[{caseId,status,reportedPlayerId,reporterId,reasonCode,
  //        reasonText,originChannelId,createdAt,assignedModerator,
  //        resolutionAction,resolvedAt}], total, page }
  // page is 1-based; status/assigned are optional exact filters.
  listCases: ({ page = 1, size = 20, status = '', assigned = '' } = {}) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) params.set('status', status);
    if (assigned) params.set('assigned', assigned);
    return apiFetch(`/moderation/cases?${params.toString()}`);
  },
  // GET /api/moderation/cases/{id} -> single case detail (same fields as list
  //   item, plus an evidence summary array).
  getCase: (caseId) => apiFetch(`/moderation/cases/${encodeURIComponent(caseId)}`),
  // POST /api/moderation/cases/{id}/assign { moderator } -> 200
  assignCase: (caseId, moderator) =>
    apiFetch(`/moderation/cases/${encodeURIComponent(caseId)}/assign`, {
      method: 'POST',
      body: JSON.stringify({ moderator }),
    }),
  // POST /api/moderation/cases/{id}/resolve { action, reason, targetChannelId?,
  //   durationMs? } -> 200 { caseId, action }. action ∈
  //   {warn,mute,ban,kick,dismiss}. targetChannelId/durationMs only sent for
  //   mute/ban/kick.
  resolveCase: (caseId, body) =>
    apiFetch(`/moderation/cases/${encodeURIComponent(caseId)}/resolve`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  // GET /api/moderation/cases/{id}/evidence
  //   -> { items:[{evidenceType,contentHash,contentSnapshot,itemJson,
  //        capturedAt,capturedBy}] }
  // The ONLY endpoint that returns private-chat content, minimal snapshot.
  getCaseEvidence: (caseId) =>
    apiFetch(`/moderation/cases/${encodeURIComponent(caseId)}/evidence`),
  // POST /api/appeals { caseId, appellantId, reason } -> 201 { appealId, status:"PENDING" }
  createAppeal: (payload) => apiFetch('/appeals', { method: 'POST', body: JSON.stringify(payload) }),
  // GET /api/appeals?page=&size=&status=
  //   -> { items:[{appealId,caseId,status,appellantId,originalAction,reviewedBy,
  //        reviewedAt,reviewDecision,createdAt}], total, page }
  // page is 1-based; status is an optional exact filter.
  listAppeals: ({ page = 1, size = 20, status = '' } = {}) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) params.set('status', status);
    return apiFetch(`/appeals?${params.toString()}`);
  },
  // POST /api/appeals/{id}/review { decision, note } -> 200. decision ∈
  //   {APPROVED,DENIED,ESCALATED}. Backend enforces reviewer !=
  //   case.assignedModerator and returns 403 otherwise (surfaced as a
  //   self-review i18n hint by the UI).
  reviewAppeal: (appealId, body) =>
    apiFetch(`/appeals/${encodeURIComponent(appealId)}/review`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  // --- Server / config / console (batch 2) ---
  reloadConfig: () => apiFetch('/reload', { method: 'POST' }),
  disconnectClient: (clientId) => apiFetch(`/clients/${encodeURIComponent(clientId)}`, { method: 'DELETE' }),
  runConsoleCommand: (command) => apiFetch('/console', { method: 'POST', body: JSON.stringify({ command }) }),
};

export default api;

/**
 * GET /api/health — unauthenticated liveness/readiness JSON (§11.7). Routed
 * through apiFetch so the request shares baseURL + auth headers + 401-refresh
 * handling with the rest of the panel; the endpoint itself ignores the token.
 * @returns {Promise<object>} { status, version, uptimeMillis, timestamp, checks }
 */
export async function getHealth() {
  return apiFetch('/health');
}

/**
 * GET /api/metrics — auth-gated Prometheus exposition text (§11.7). MUST
 * return the raw text/plain body (NOT .json()): the frontend parses it by
 * hand in StatusPage.parsePrometheusText. Uses the same baseURL + token idiom
 * as apiFetch but bypasses apiFetch because apiFetch force-parses the body as
 * JSON. VIEWER tokens can read this endpoint.
 * @returns {Promise<string>} Prometheus exposition-format text
 * @throws {Error} on non-2xx (carries .status)
 */
export async function getMetrics() {
  const base = getApiBaseUrl();
  const url = `${base}/metrics`;
  const headers = { ...authService.getAuthHeader() };
  let response;
  try {
    response = await fetch(url, { headers });
  } catch (err) {
    throw new Error(i18n.t('common.api_error_connect', { error: err.message || err }));
  }
  if (response.status === 401) {
    // Mirror apiFetch's single-refresh retry so a stale-but-refreshable token
    // doesn't kick the user out of the status page.
    const newToken = await authService.refreshAccessToken(base);
    if (newToken) {
      headers.Authorization = `Bearer ${newToken}`;
      response = await fetch(url, { headers });
    }
  }
  const text = await response.text();
  if (!response.ok) {
    const error = new Error(
      i18n.t('common.api_error_request', { status: response.status })
    );
    error.status = response.status;
    error.data = text;
    throw error;
  }
  return text;
}
