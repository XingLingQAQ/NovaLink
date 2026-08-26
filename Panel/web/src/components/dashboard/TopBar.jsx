/**
 * Top bar — sidebar toggle, WS status indicator, notification bell/dropdown,
 * language switcher and theme toggle. Extracted from App.jsx unchanged in
 * behavior (the dropdown open state and its click-outside handling moved here).
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Menu, Moon, Sun, Bell, ChevronLeft, ChevronRight } from 'lucide-react';

import { ConnectionState } from '../../services/websocket';
import { SUPPORTED_LANGS, languageLabel } from '../../i18n';
import NotificationDropdown from './NotificationDropdown';

function TopBar({
  sidebarOpen,
  onToggleSidebar,
  isMobile,
  mode,
  setMode,
  wsState,
  onManualReconnect,
  notifications,
  apiUnreadCount,
  onMarkAllRead,
  onClearAll,
  onOpenList,
}) {
  const { t, i18n } = useTranslation();
  const [showNotifications, setShowNotifications] = useState(false);
  const notificationRef = useRef(null);

  // Click-outside for notification dropdown.
  useEffect(() => {
    const handleClickOutside = (event) => {
      if (notificationRef.current && !notificationRef.current.contains(event.target)) {
        setShowNotifications(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Clear all: close the dropdown only when the clear succeeded (failure toast
  // is shown upstream and the dropdown stays open).
  const handleClearAll = useCallback(async () => {
    try {
      await onClearAll();
      setShowNotifications(false);
    } catch {
      // toast already shown by the caller-provided handler
    }
  }, [onClearAll]);

  // Open the full notification list modal (from the dropdown "view all").
  const handleOpenList = useCallback(() => {
    setShowNotifications(false);
    onOpenList();
  }, [onOpenList]);

  const wsIndicator = (() => {
    if (wsState === ConnectionState.AUTHENTICATED) return { color: 'bg-emerald-500', label: t('common.ws_connected') };
    if (wsState === ConnectionState.CONNECTED || wsState === ConnectionState.CONNECTING) return { color: 'bg-amber-500', label: t('common.ws_connecting') };
    if (wsState === ConnectionState.RECONNECTING) return { color: 'bg-amber-500', label: t('common.ws_reconnecting') };
    if (wsState === ConnectionState.ERROR) return { color: 'bg-red-500', label: t('common.ws_error') };
    return { color: 'bg-slate-500', label: t('common.ws_disconnected') };
  })();

  return (
    <header className="h-14 px-4 md:px-6 flex items-center justify-between shrink-0 z-30 border-b border-border bg-background/95 backdrop-blur-sm">
      <div className="flex items-center gap-3">
        <button onClick={onToggleSidebar} className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground" title={t('common.toggle_sidebar')} aria-label={t('common.toggle_sidebar')}>
          {isMobile ? <Menu size={18} /> : (sidebarOpen ? <ChevronLeft size={18} /> : <ChevronRight size={18} />)}
        </button>
      </div>
      <div className="flex items-center gap-2 md:gap-3">
        {/* WS status indicator */}
        <div className="hidden sm:flex items-center gap-1.5 rounded-full bg-muted/60 border border-border px-2 py-0.5" title={wsIndicator.label}>
          <span className={`w-1.5 h-1.5 rounded-full ${wsIndicator.color} ${wsState === ConnectionState.CONNECTING || wsState === ConnectionState.RECONNECTING ? 'animate-pulse' : ''}`} />
          <span className="text-[11px] text-muted-foreground">{wsIndicator.label}</span>
          {wsState === ConnectionState.ERROR && (
            <button
              onClick={onManualReconnect}
              className="text-[11px] font-medium text-primary hover:underline"
            >
              {t('common.ws_reconnect')}
            </button>
          )}
        </div>
        <div className="relative" ref={notificationRef}>
          <button onClick={() => setShowNotifications(!showNotifications)} className="relative rounded-full p-1.5 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground" title={t('notifications.title')} aria-label={t('notifications.title')}>
            <Bell size={18} />
            {apiUnreadCount > 0 ? (
              <span className="absolute top-0.5 right-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold text-destructive-foreground">
                {apiUnreadCount > 99 ? '99+' : apiUnreadCount}
              </span>
            ) : notifications.some((n) => !n.read) ? (
              <span className="absolute top-1 right-1 w-1.5 h-1.5 bg-destructive rounded-full" />
            ) : null}
          </button>
          <NotificationDropdown
            isOpen={showNotifications}
            onClose={() => setShowNotifications(false)}
            theme="clean"
            mode={mode}
            notifications={notifications}
            onMarkAllRead={onMarkAllRead}
            onClearAll={handleClearAll}
            onOpenList={handleOpenList}
          />
        </div>
        {/* Language switcher — driven by SUPPORTED_LANGS (auto-detected from src/lang/*.json) */}
        <div className="flex items-center p-0.5 rounded-full gap-0.5 border border-border bg-muted/60" title={t('language.switch_title')}>
          {SUPPORTED_LANGS.map((lang) => {
            const label = languageLabel(lang);
            const isActive = i18n.language === lang || i18n.language?.split('_')[0] === lang.split('_')[0];
            return (
              <button
                key={lang}
                onClick={() => i18n.changeLanguage(lang)}
                className={`px-2 py-0.5 rounded-full text-[11px] font-medium transition-colors ${isActive ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'}`}
              >
                {label}
              </button>
            );
          })}
        </div>
        <div className="flex items-center p-0.5 rounded-full gap-0.5 border border-border bg-muted/60">
          <button onClick={() => setMode('light')} className={`p-1 rounded-full transition-colors ${mode === 'light' ? 'bg-background shadow-sm text-amber-500' : 'text-muted-foreground hover:text-foreground'}`} title={t('common.theme_light')} aria-label={t('common.theme_light')}><Sun size={14} /></button>
          <button onClick={() => setMode('dark')} className={`p-1 rounded-full transition-colors ${mode === 'dark' ? 'bg-background shadow-sm text-primary' : 'text-muted-foreground hover:text-foreground'}`} title={t('common.theme_dark')} aria-label={t('common.theme_dark')}><Moon size={14} /></button>
        </div>
      </div>
    </header>
  );
}

export default TopBar;
