/**
 * Server details modal content (data already in `servers` state; no REST call).
 * Extracted from App.jsx unchanged in behavior.
 */

import React from 'react';
import { useTranslation } from 'react-i18next';

function ServerDetailsContent({ server }) {
  const { t } = useTranslation();
  const s = server || {};
  const rowClass = 'flex items-center justify-between p-2 rounded-md bg-muted/40 text-xs';
  // Snapshot "now" once per render for the uptime computation (avoids calling
  // the impure Date.now() inside the render helper).
  const [now] = React.useState(() => Date.now());

  const formatTime = (ts) => {
    if (!ts) return '-';
    try {
      const num = typeof ts === 'number' ? ts : Number(ts);
      if (Number.isNaN(num)) return String(ts);
      return new Date(num).toLocaleString();
    } catch {
      return String(ts);
    }
  };

  const formatUptime = (connectedAt) => {
    if (!connectedAt) return '-';
    const diff = now - (typeof connectedAt === 'number' ? connectedAt : Number(connectedAt));
    if (Number.isNaN(diff) || diff < 0) return '-';
    const seconds = Math.floor(diff / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    if (hours > 0) return `${hours}h ${minutes % 60}m`;
    if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
    return `${seconds}s`;
  };

  return (
    <div className="space-y-1.5">
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('common.server_details_id')}</span>
        <span className="text-foreground font-mono">{s.id || '-'}</span>
      </div>
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('players.col_server')}</span>
        <span className="text-foreground">{s.name || s.id || '-'}</span>
      </div>
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('players.col_platform')}</span>
        <span className="text-foreground">{s.platform || '-'}</span>
      </div>
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('common.col_version')}</span>
        <span className="text-foreground">{s.version || '-'}</span>
      </div>
      {s.remoteAddress && (
        <div className={rowClass}>
          <span className="text-muted-foreground">{t('common.server_details_remote')}</span>
          <span className="text-foreground font-mono">{s.remoteAddress}</span>
        </div>
      )}
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('common.server_details_connected_at')}</span>
        <span className="text-foreground">{formatTime(s.connectedAt)}</span>
      </div>
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('common.server_details_uptime')}</span>
        <span className="text-foreground">{formatUptime(s.connectedAt)}</span>
      </div>
      <div className={rowClass}>
        <span className="text-muted-foreground">{t('common.server_details_active')}</span>
        <span className={s.status === 'online' ? 'text-emerald-600 dark:text-emerald-400' : 'text-muted-foreground'}>
          {s.status === 'online' ? t('common.active_yes') : t('common.active_no')}
        </span>
      </div>
    </div>
  );
}

export default ServerDetailsContent;
