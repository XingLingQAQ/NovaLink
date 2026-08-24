/**
 * Campaign Management — orchestrated, scheduled, revocable announcements
 * (§11.6 提案 06 — slice A: in-memory backend).
 *
 * Backed by six endpoints (locked contract with RestApiHandler.java):
 *   - GET  /api/campaigns[?channelId=]    — { items:[campaignJson], total }  (VIEWER)
 *   - POST /api/campaigns                  — create in PREVIEW status         (ADMIN)
 *   - GET  /api/campaigns/{id}             — single campaign, 404 not found   (VIEWER)
 *   - POST /api/campaigns/{id}/schedule    — PREVIEW→SCHEDULED (or →ACTIVE
 *                                            when startAt=0); bumps revision (ADMIN)
 *   - POST /api/campaigns/{id}/activate    — SCHEDULED→ACTIVE, deliver once  (ADMIN)
 *   - POST /api/campaigns/{id}/revoke      — any non-terminal→REVOKED         (SUPER_ADMIN)
 *
 * campaignJson shape (campaignToJson @4798):
 *   { id, channelId, platforms:[...], content, status, scheduleRevision,
 *     deliveryPolicy, startAt, endAt, rateLimitPerChannelPerHour,
 *     createdAt, revokedAt, revokedBy(string|null) }
 * NOTE: creatorId / creatorClientId are NOT in the REST output.
 *
 * Create body fields: { channelId, content, platforms:[...], deliveryPolicy?,
 *   startAt?, endAt?, rateLimitPerHour? } — the backend reads `rateLimitPerHour`
 * from the request and maps it to the internal `rateLimitPerChannelPerHour`.
 *
 * Self-contained, mirroring ConfigHistory.jsx + AnnouncementManagement.jsx:
 * calls `api.*` directly, manages its own state, and degrades gracefully on
 * 503 (campaign manager not wired) / 404 (not found) / network failure — any
 * failure renders an inline error hint or the service-unavailable banner,
 * never a blank page or a crash. State is NOT threaded through useDashboardData.
 *
 * RBAC: the page itself is gated by `announcements.manage` (ADMIN+) at the
 * sidebar + App route level. Create/schedule/activate are therefore always
 * available to visitors. Revoke is SUPER_ADMIN-only, gated inside this view
 * via a direct `role === 'SUPER_ADMIN'` check. There is intentionally NO
 * `campaign.*` capability in permissions.js — campaign reuses
 * `announcements.manage` for page visibility (recorded RBAC architectural
 * debt), so revoke falls back to a raw role compare rather than
 * `can(role, 'campaign.revoke')`. The backend still enforces SUPER_ADMIN on
 * /api/campaigns/{id}/revoke regardless of the frontend gate.
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Send,
  Plus,
  Loader2,
  AlertCircle,
  RotateCcw,
  CalendarClock,
  Play,
  Ban,
} from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';
import Modal from '../ui/Modal';
import Select from '../ui/Select';
import { api } from '../../services/api';
import { platformLabel } from '../../utils/adapters';

const EMPTY_FORM = {
  channelId: '',
  content: '',
  platforms: [],
  deliveryPolicy: 'INSTANT',
  startAt: '', // datetime-local string; '' = immediate (0)
  endAt: '', // datetime-local string; '' = no expiry (0)
  rateLimitPerHour: '0',
};

// Platform enum names a campaign may target (PlatformType.java). Kept as a
// local list so the create form has a stable selectable set independent of
// the live channel/client state; labels are localized via platformLabel().
const CAMPAIGN_PLATFORMS = [
  'BUKKIT',
  'FOLIA',
  'SPONGE',
  'VELOCITY',
  'BUNGEECORD',
  'NUKKIT',
  'POWERNUKKITX',
  'POCKETMINE',
  'ENDSTONE',
  'LEVILAMINA',
  'FABRIC',
  'NEOFORGE',
  'QUILT',
  'FORGE',
];

const DELIVERY_POLICIES = ['INSTANT', 'TITLE_FALLBACK', 'ACTIONBAR_FALLBACK'];

const textareaClass =
  'flex w-full min-h-24 rounded-md border-0 bg-secondary/55 px-3 py-2 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring text-foreground resize-y';

const inputClass =
  'flex h-8 w-full rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring text-foreground';

// datetime-local value ("2026-08-13T12:00") -> epoch millis, or 0 when
// empty/invalid. 0 is the backend sentinel for "immediate" (startAt) /
// "no expiry" (endAt).
function toMillis(value) {
  if (!value) return 0;
  const ms = new Date(value).getTime();
  return Number.isNaN(ms) ? 0 : ms;
}

function formatTime(ts, locale) {
  if (!ts) return '-';
  try {
    return new Date(Number(ts)).toLocaleString(locale, { hour12: false });
  } catch {
    return '-';
  }
}

// Status -> Badge variant. Mirrors the semantic mapping used across the panel:
// draft=secondary, pending=info, live=success, stale=warning, dead=destructive.
function statusVariant(status) {
  switch (status) {
    case 'PREVIEW':
      return 'secondary';
    case 'SCHEDULED':
      return 'info';
    case 'ACTIVE':
      return 'success';
    case 'EXPIRED':
      return 'warning';
    case 'REVOKED':
      return 'destructive';
    default:
      return 'secondary';
  }
}

function isTerminal(status) {
  return status === 'EXPIRED' || status === 'REVOKED';
}

function CampaignManagement({ theme, mode, channels = [], onToast, role }) {
  const { t, i18n } = useTranslation();
  const locale = (i18n.language || 'zh_CN').replace(/_/g, '-');
  const canRevoke = role === 'SUPER_ADMIN';

  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [serviceUnavailable, setServiceUnavailable] = useState(false);
  const [filterChannelId, setFilterChannelId] = useState('');

  // Mutation in-flight row (schedule / activate). Revoke has its own pending
  // state on the confirm modal.
  const [busyId, setBusyId] = useState(null);

  // Create modal.
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);

  // Revoke confirm modal (SUPER_ADMIN only).
  const [revokeTarget, setRevokeTarget] = useState(null);
  const [revokePending, setRevokePending] = useState(false);
  const [revokeError, setRevokeError] = useState(null);

  const fetchCampaigns = useCallback(async (channelId) => {
    setLoading(true);
    setError(null);
    setServiceUnavailable(false);
    try {
      const res = await api.getCampaigns(channelId);
      setItems(res && Array.isArray(res.items) ? res.items : []);
    } catch (err) {
      setItems([]);
      if (err && err.status === 503) {
        setServiceUnavailable(true);
      } else {
        setError(err && err.message ? err.message : String(err));
      }
    } finally {
      setLoading(false);
    }
  }, []);

  // Initial load + re-fetch when the channel filter changes.
  useEffect(() => {
    fetchCampaigns(filterChannelId);
  }, [fetchCampaigns, filterChannelId]);

  const refresh = useCallback(() => fetchCampaigns(filterChannelId), [fetchCampaigns, filterChannelId]);

  const openCreateModal = useCallback(() => {
    setForm({
      ...EMPTY_FORM,
      channelId: (channels[0] && channels[0].id) || '',
    });
    setShowCreateModal(true);
  }, [channels]);

  const canSubmit =
    !!form.channelId &&
    !!form.content.trim() &&
    Array.isArray(form.platforms) &&
    form.platforms.length > 0;

  const handleCreate = useCallback(async () => {
    if (!canSubmit || submitting) return;
    setSubmitting(true);
    try {
      const body = {
        channelId: form.channelId,
        content: form.content.trim(),
        platforms: form.platforms.slice(),
        deliveryPolicy: form.deliveryPolicy,
        startAt: toMillis(form.startAt),
        endAt: toMillis(form.endAt),
        rateLimitPerHour: Number(form.rateLimitPerHour) || 0,
      };
      await api.createCampaign(body);
      if (onToast) onToast(t('campaigns.toast_create'), 'success');
      setShowCreateModal(false);
      await refresh();
    } catch (err) {
      if (onToast) {
        onToast(
          t('campaigns.toast_create_failed', { error: (err && err.message) || String(err) }),
          'error'
        );
      }
    } finally {
      setSubmitting(false);
    }
  }, [canSubmit, submitting, form, onToast, t, refresh]);

  const handleSchedule = useCallback(
    async (item) => {
      if (busyId) return;
      setBusyId(item.id);
      try {
        await api.scheduleCampaign(item.id);
        if (onToast) onToast(t('campaigns.toast_schedule'), 'success');
        await refresh();
      } catch (err) {
        if (onToast) {
          onToast(
            t('campaigns.toast_schedule_failed', { error: (err && err.message) || String(err) }),
            'error'
          );
        }
      } finally {
        setBusyId(null);
      }
    },
    [busyId, onToast, t, refresh]
  );

  const handleActivate = useCallback(
    async (item) => {
      if (busyId) return;
      setBusyId(item.id);
      try {
        await api.activateCampaign(item.id);
        if (onToast) onToast(t('campaigns.toast_activate'), 'success');
        await refresh();
      } catch (err) {
        if (onToast) {
          onToast(
            t('campaigns.toast_activate_failed', { error: (err && err.message) || String(err) }),
            'error'
          );
        }
      } finally {
        setBusyId(null);
      }
    },
    [busyId, onToast, t, refresh]
  );

  const openRevoke = useCallback((item) => {
    setRevokeTarget(item);
    setRevokeError(null);
    setRevokePending(false);
  }, []);

  const closeRevoke = useCallback(() => {
    setRevokeTarget(null);
    setRevokeError(null);
    setRevokePending(false);
  }, []);

  const handleRevoke = useCallback(async () => {
    if (!revokeTarget || revokePending) return;
    setRevokePending(true);
    setRevokeError(null);
    try {
      await api.revokeCampaign(revokeTarget.id);
      setRevokeTarget(null);
      if (onToast) onToast(t('campaigns.toast_revoke'), 'success');
      await refresh();
    } catch (err) {
      setRevokeError((err && err.message) || String(err));
    } finally {
      setRevokePending(false);
    }
  }, [revokeTarget, revokePending, onToast, t, refresh]);

  const channelOptions = channels.map((c) => ({ value: c.id, label: c.name || c.id }));

  const filterOptions = [
    { value: '', label: t('campaigns.filter_all_channels') },
    ...channelOptions,
  ];

  const policyOptions = DELIVERY_POLICIES.map((p) => ({
    value: p,
    label: t('campaigns.policy_' + p.toLowerCase()),
  }));

  const statusLabel = (status) => {
    const key = (status || '').toLowerCase();
    if (key === 'preview') return t('campaigns.status_preview');
    if (key === 'scheduled') return t('campaigns.status_scheduled');
    if (key === 'active') return t('campaigns.status_active');
    if (key === 'expired') return t('campaigns.status_expired');
    if (key === 'revoked') return t('campaigns.status_revoked');
    return status || '-';
  };

  const channelLabel = (channelId) => {
    const ch = channels.find((c) => c.id === channelId);
    return ch ? ch.name || ch.id : channelId || '-';
  };

  const platformList = (platforms) => {
    if (!Array.isArray(platforms) || platforms.length === 0) return '-';
    return platforms.map((p) => platformLabel(p)).join(', ');
  };

  const formatWindow = (startAt, endAt) => {
    const s = startAt ? formatTime(startAt, locale) : t('campaigns.immediate');
    const e = endAt ? formatTime(endAt, locale) : t('campaigns.no_expiry');
    return `${s} → ${e}`;
  };

  const togglePlatform = (p, checked) => {
    setForm((prev) => ({
      ...prev,
      platforms: checked
        ? [...prev.platforms, p]
        : prev.platforms.filter((x) => x !== p),
    }));
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('campaigns.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">
            {t('campaigns.subtitle', { count: items.length })}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            theme={theme}
            mode={mode}
            onClick={refresh}
            disabled={loading}
            aria-label={t('campaigns.refresh')}
          >
            {loading ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />}
            {t('campaigns.refresh')}
          </Button>
          <Button
            variant="default"
            theme={theme}
            mode={mode}
            onClick={openCreateModal}
            title={t('campaigns.create')}
          >
            <Plus size={14} /> {t('campaigns.create')}
          </Button>
        </div>
      </div>

      {/* Channel filter */}
      <div className="flex items-center gap-2 max-w-xs">
        <label className="text-xs text-muted-foreground whitespace-nowrap" htmlFor="campaign-channel-filter">
          {t('campaigns.field_channel')}
        </label>
        <div id="campaign-channel-filter" className="flex-1">
          <Select
            options={filterOptions}
            value={filterChannelId}
            onChange={(v) => setFilterChannelId(v || '')}
            aria-label={t('campaigns.field_channel')}
          />
        </div>
      </div>

      {/* Error hint (network failure / non-503). */}
      {error && (
        <Card className="p-3 border-destructive/30 bg-destructive/5">
          <div className="flex items-center gap-2 text-destructive">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('campaigns.load_failed', { error })}</p>
          </div>
        </Card>
      )}

      {/* Service-unavailable hint (503 — CampaignManager not wired). */}
      {serviceUnavailable && (
        <Card className="p-3 border-amber-500/30 bg-amber-500/5">
          <div className="flex items-center gap-2 text-amber-700 dark:text-amber-300">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('campaigns.service_unavailable')}</p>
          </div>
        </Card>
      )}

      {/* List */}
      <Card className="p-0 overflow-hidden">
        {loading && items.length === 0 ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 size={20} className="animate-spin text-muted-foreground" />
            <span className="text-xs text-muted-foreground ml-2">{t('campaigns.loading')}</span>
          </div>
        ) : serviceUnavailable || (items.length === 0 && !error) ? (
          <div className="py-16 text-center text-muted-foreground">
            <Send size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs">{t('campaigns.empty')}</p>
            <p className="text-[11px] opacity-70 mt-1">{t('campaigns.empty_hint')}</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('campaigns.col_id')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('campaigns.col_channel')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium w-full">{t('campaigns.col_content')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('campaigns.col_platforms')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('campaigns.col_status')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap">{t('campaigns.col_window')}</th>
                  <th scope="col" className="px-4 py-2.5 font-medium whitespace-nowrap text-right">{t('campaigns.col_actions')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item, idx) => {
                  const status = item.status;
                  const terminal = isTerminal(status);
                  const isBusy = busyId === item.id;
                  return (
                    <tr
                      key={item.id != null ? item.id : idx}
                      className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors align-top"
                    >
                      <td className="px-4 py-2.5 whitespace-nowrap font-mono text-foreground text-[11px]">{item.id || '-'}</td>
                      <td className="px-4 py-2.5 whitespace-nowrap text-sky-600 dark:text-sky-400">{channelLabel(item.channelId)}</td>
                      <td className="px-4 py-2.5 text-foreground break-all">{item.content || '-'}</td>
                      <td className="px-4 py-2.5 text-[11px] text-muted-foreground break-words max-w-[12rem]">{platformList(item.platforms)}</td>
                      <td className="px-4 py-2.5 whitespace-nowrap">
                        <Badge variant={statusVariant(status)}>{statusLabel(status)}</Badge>
                      </td>
                      <td className="px-4 py-2.5 whitespace-nowrap text-[11px] text-muted-foreground">
                        <div>{formatWindow(item.startAt, item.endAt)}</div>
                        <div className="opacity-70">{t('campaigns.rate_label', { count: item.rateLimitPerChannelPerHour ?? 0 })}</div>
                      </td>
                      <td className="px-4 py-2.5 whitespace-nowrap text-right">
                        <div className="inline-flex items-center gap-1.5">
                          {isBusy && <Loader2 size={13} className="animate-spin text-muted-foreground" />}
                          {status === 'PREVIEW' && !isBusy && (
                            <Button
                              variant="ghost"
                              size="sm"
                              theme={theme}
                              mode={mode}
                              onClick={() => handleSchedule(item)}
                              title={
                                item.startAt === 0
                                  ? t('campaigns.action_schedule_immediate')
                                  : t('campaigns.action_schedule')
                              }
                            >
                              <CalendarClock size={13} /> {t('campaigns.action_schedule')}
                            </Button>
                          )}
                          {status === 'SCHEDULED' && !isBusy && (
                            <Button
                              variant="ghost"
                              size="sm"
                              theme={theme}
                              mode={mode}
                              onClick={() => handleActivate(item)}
                              title={t('campaigns.action_activate')}
                            >
                              <Play size={13} /> {t('campaigns.action_activate')}
                            </Button>
                          )}
                          {canRevoke && !terminal && (
                            <Button
                              variant="ghost"
                              size="sm"
                              theme={theme}
                              mode={mode}
                              className="text-destructive hover:text-destructive"
                              onClick={() => openRevoke(item)}
                              title={t('campaigns.action_revoke')}
                              aria-label={t('campaigns.action_revoke')}
                            >
                              <Ban size={13} />
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Create Modal */}
      <Modal
        isOpen={showCreateModal}
        onClose={() => !submitting && setShowCreateModal(false)}
        title={t('campaigns.create_modal_title')}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          {/* Channel */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('campaigns.field_channel')} <span className="text-destructive">*</span>
            </label>
            {channels.length === 0 ? (
              <p className="text-[11px] text-muted-foreground">{t('campaigns.field_no_channels')}</p>
            ) : (
              <Select
                options={channelOptions}
                value={form.channelId}
                onChange={(v) => setForm((prev) => ({ ...prev, channelId: v }))}
                placeholder={t('campaigns.field_channel')}
                aria-label={t('campaigns.field_channel')}
              />
            )}
          </div>

          {/* Content */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">
              {t('campaigns.field_content')} <span className="text-destructive">*</span>
            </label>
            <textarea
              value={form.content}
              onChange={(e) => setForm((prev) => ({ ...prev, content: e.target.value }))}
              placeholder={t('campaigns.field_content_placeholder')}
              className={textareaClass}
            />
          </div>

          {/* Platforms */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <label className="text-xs font-normal leading-none text-muted-foreground">
                {t('campaigns.field_platforms')} <span className="text-destructive">*</span>
              </label>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  className="text-[11px] text-muted-foreground hover:text-foreground underline-offset-2 hover:underline"
                  onClick={() => setForm((prev) => ({ ...prev, platforms: CAMPAIGN_PLATFORMS.slice() }))}
                >
                  {t('campaigns.select_all')}
                </button>
                <button
                  type="button"
                  className="text-[11px] text-muted-foreground hover:text-foreground underline-offset-2 hover:underline"
                  onClick={() => setForm((prev) => ({ ...prev, platforms: [] }))}
                >
                  {t('campaigns.clear_all')}
                </button>
              </div>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-1.5 rounded-md border border-border bg-secondary/30 p-2.5">
              {CAMPAIGN_PLATFORMS.map((p) => {
                const checked = form.platforms.includes(p);
                return (
                  <label
                    key={p}
                    className="inline-flex items-center gap-1.5 text-[11px] cursor-pointer text-foreground"
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={(e) => togglePlatform(p, e.target.checked)}
                      className="accent-primary"
                      aria-label={platformLabel(p)}
                    />
                    <span>{platformLabel(p)}</span>
                  </label>
                );
              })}
            </div>
          </div>

          {/* Delivery policy */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">{t('campaigns.field_delivery_policy')}</label>
            <Select
              options={policyOptions}
              value={form.deliveryPolicy}
              onChange={(v) => setForm((prev) => ({ ...prev, deliveryPolicy: v }))}
              aria-label={t('campaigns.field_delivery_policy')}
            />
          </div>

          {/* Start / End */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="space-y-2">
              <label className="text-xs font-normal leading-none text-muted-foreground">{t('campaigns.field_start_at')}</label>
              <input
                type="datetime-local"
                value={form.startAt}
                onChange={(e) => setForm((prev) => ({ ...prev, startAt: e.target.value }))}
                className={inputClass}
                aria-label={t('campaigns.field_start_at')}
              />
              <p className="text-[11px] text-muted-foreground">{t('campaigns.field_start_at_hint')}</p>
            </div>
            <div className="space-y-2">
              <label className="text-xs font-normal leading-none text-muted-foreground">{t('campaigns.field_end_at')}</label>
              <input
                type="datetime-local"
                value={form.endAt}
                onChange={(e) => setForm((prev) => ({ ...prev, endAt: e.target.value }))}
                className={inputClass}
                aria-label={t('campaigns.field_end_at')}
              />
              <p className="text-[11px] text-muted-foreground">{t('campaigns.field_end_at_hint')}</p>
            </div>
          </div>

          {/* Rate limit */}
          <div className="space-y-2">
            <label className="text-xs font-normal leading-none text-muted-foreground">{t('campaigns.field_rate_limit')}</label>
            <input
              type="number"
              min="0"
              value={form.rateLimitPerHour}
              onChange={(e) => setForm((prev) => ({ ...prev, rateLimitPerHour: e.target.value }))}
              className={inputClass}
              aria-label={t('campaigns.field_rate_limit')}
            />
            <p className="text-[11px] text-muted-foreground">{t('campaigns.field_rate_limit_hint')}</p>
          </div>
        </div>

        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <Button
            variant="ghost"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={() => !submitting && setShowCreateModal(false)}
            disabled={submitting}
          >
            {t('common.cancel')}
          </Button>
          <Button
            variant="default"
            className="flex-1"
            theme={theme}
            mode={mode}
            onClick={handleCreate}
            disabled={submitting || !canSubmit}
          >
            {submitting ? <Loader2 size={14} className="animate-spin" /> : null}
            {t('common.create')}
          </Button>
        </div>
      </Modal>

      {/* Revoke confirm modal (SUPER_ADMIN only). */}
      <Modal
        isOpen={!!revokeTarget}
        onClose={() => !revokePending && closeRevoke()}
        title={t('campaigns.revoke_modal_title')}
        theme={theme}
        mode={mode}
      >
        <div className="space-y-4">
          <p className="text-xs text-foreground">{t('campaigns.revoke_confirm')}</p>
          {revokeTarget && (
            <div className="rounded-md border border-border bg-muted/30 p-2.5 space-y-1">
              <p className="text-[11px] font-mono text-foreground break-all">{revokeTarget.id}</p>
              <p className="text-xs text-foreground break-all">{revokeTarget.content}</p>
            </div>
          )}

          {revokeError && (
            <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
              <AlertCircle size={14} className="shrink-0 mt-0.5" />
              <p className="text-xs">{t('campaigns.toast_revoke_failed', { error: revokeError })}</p>
            </div>
          )}

          <div className="flex justify-end gap-2 pt-1">
            <Button
              variant="ghost"
              theme={theme}
              mode={mode}
              onClick={closeRevoke}
              disabled={revokePending}
            >
              {t('campaigns.revoke_cancel')}
            </Button>
            <Button
              variant="destructive"
              theme={theme}
              mode={mode}
              onClick={handleRevoke}
              disabled={revokePending}
            >
              {revokePending ? <Loader2 size={14} className="animate-spin" /> : <Ban size={14} />}
              {t('campaigns.revoke_confirm_button')}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}

export default CampaignManagement;
