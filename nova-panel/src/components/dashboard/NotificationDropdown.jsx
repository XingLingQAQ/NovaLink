import React from 'react';
import { useTranslation } from 'react-i18next';
import { Info, AlertTriangle, CheckCircle, UserX } from 'lucide-react';
import { cn } from '../../lib/cn';

/**
 * Notification dropdown — restyled to the shadcn/ui token idiom.
 * Pill-ish rounded-lg popover with token-driven bg/border, muted rows.
 * Legacy `theme`/`mode` props are accepted but ignored (tokens auto-switch).
 */
const NotificationDropdown = ({ isOpen, theme, mode, notifications, onMarkAllRead, onClearAll }) => {
  void theme; void mode;
  const { t } = useTranslation();
  const containerStyles = cn(
    'absolute top-full right-0 mt-2 w-80 sm:w-96 rounded-lg shadow-lg z-50 overflow-hidden origin-top-right transition-all duration-200 border bg-popover text-popover-foreground',
    isOpen ? 'opacity-100 scale-100 translate-y-0' : 'opacity-0 scale-95 -translate-y-1 pointer-events-none'
  );

  const divider = 'border-border';
  const hoverBg = 'hover:bg-accent/60';

  const unreadCount = notifications.filter((n) => !n.read).length;

  return (
    <div className={containerStyles}>
      <div className={cn('px-4 py-3 border-b flex justify-between items-center', divider)}>
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-medium text-foreground">{t('notifications.title')}</h3>
          {unreadCount > 0 && (
            <span className="inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[10px] font-semibold text-primary-foreground">
              {unreadCount}
            </span>
          )}
        </div>
        <div className="flex gap-2">
          <button
            onClick={onMarkAllRead}
            className="text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            {t('notifications.mark_all_read')}
          </button>
          <button
            onClick={onClearAll}
            className="text-xs text-muted-foreground hover:text-destructive transition-colors"
          >
            {t('notifications.clear')}
          </button>
        </div>
      </div>

      <div className="max-h-[320px] overflow-y-auto">
        {notifications.length === 0 ? (
          <div className="p-8 text-center text-muted-foreground text-xs">{t('notifications.empty')}</div>
        ) : (
          notifications.map((notif) => {
            const Icon = notif.icon
              ? (typeof notif.icon === 'function' ? notif.icon : null)
              : (notif.type === 'warning' ? AlertTriangle : notif.type === 'success' ? CheckCircle : notif.type === 'mute' ? UserX : Info);
            return (
              <div
                key={notif.id}
                className={cn(
                  'px-4 py-3 flex gap-3 transition-colors cursor-pointer relative',
                  hoverBg,
                  !notif.read && 'bg-primary/5'
                )}
              >
                {!notif.read && (
                  <div className="absolute left-1.5 top-1/2 -translate-y-1/2 w-1.5 h-1.5 bg-primary rounded-full" />
                )}
                <div
                  className={cn(
                    'flex size-8 items-center justify-center shrink-0 rounded-full',
                    notif.type === 'warning' ? 'bg-amber-500/10 text-amber-600 dark:text-amber-400' :
                    notif.type === 'success' ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400' :
                    'bg-muted text-muted-foreground'
                  )}
                >
                  {Icon && <Icon size={14} />}
                </div>
                <div className="flex-1 min-w-0">
                  <p className={cn('text-xs font-medium truncate text-foreground', !notif.read && 'font-semibold')}>{notif.title}</p>
                  <p className="text-xs truncate text-muted-foreground mt-0.5">{notif.desc}</p>
                  <p className="text-[11px] text-muted-foreground opacity-70 mt-1">{notif.time}</p>
                </div>
              </div>
            );
          })
        )}
      </div>
      <div className={cn('p-3 border-t text-center', divider)}>
        <button className="text-xs font-medium text-muted-foreground hover:text-foreground transition-colors">
          {t('notifications.view_all')}
        </button>
      </div>
    </div>
  );
};

export default NotificationDropdown;
