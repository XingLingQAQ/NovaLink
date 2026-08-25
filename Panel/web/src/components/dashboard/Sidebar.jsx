/**
 * Sidebar — logo, nav items and the user area. Extracted from App.jsx
 * unchanged in behavior; the console entry is hidden for roles without the
 * `console` capability.
 */

import React from 'react';
import { useTranslation } from 'react-i18next';
import {
  LayoutDashboard,
  Server,
  Users,
  MessageSquare,
  Hash,
  Settings,
  Bell,
  Terminal,
  LogOut,
  History,
  Megaphone,
  Filter,
  ScrollText,
  ShieldAlert,
  Gavel,
  Activity,
  GitBranch,
  Send,
  FileEdit,
} from 'lucide-react';

import { can } from '../../lib/permissions';

function Sidebar({ activeTab, onTabChange, sidebarOpen, isMobile, onOverlayClick, currentUser, onLogout, role }) {
  const { t } = useTranslation();

  // Entries with a `capability` are hidden for roles lacking it.
  const navItems = [
    { id: 'dashboard', icon: LayoutDashboard, label: t('common.nav_dashboard') },
    { id: 'messages', icon: MessageSquare, label: t('common.nav_messages') },
    { id: 'history', icon: History, label: t('common.nav_history') },
    { id: 'console', icon: Terminal, label: t('common.nav_console_command'), capability: 'console' },
    { id: 'servers', icon: Server, label: t('common.nav_servers') },
    { id: 'channels', icon: Hash, label: t('common.nav_channels') },
    { id: 'players', icon: Users, label: t('common.nav_players') },
    { id: 'announcements', icon: Megaphone, label: t('common.nav_announcements'), capability: 'announcements.manage' },
    // §11.6 提案 06 (item 19): campaign orchestration. Visible to ADMIN+ under
    // the announcements capability (create/schedule/activate are ADMIN-level
    // mutations; the revoke action is SUPER_ADMIN-only, gated inside the view).
    { id: 'campaigns', icon: Send, label: t('common.nav_campaigns'), capability: 'announcements.manage' },
    { id: 'filter', icon: Filter, label: t('common.nav_filter'), capability: 'filter.manage' },
    { id: 'audit', icon: ScrollText, label: t('common.nav_audit'), capability: 'audit.view' },
    // PANEL-007: moderation + appeals. Both are ADMIN+ only; VIEWER never
    // sees these entries (a default admin must not browse private-chat content).
    { id: 'moderation', icon: ShieldAlert, label: t('common.nav_moderation'), capability: 'moderation.view' },
    { id: 'appeals', icon: Gavel, label: t('common.nav_appeals'), capability: 'appeals.review' },
    { id: 'webhooks', icon: Bell, label: t('common.nav_webhooks') },
    { id: 'settings', icon: Settings, label: t('common.nav_settings') },
    // §11.6 Project 20 / PANEL proposal 10: masked config snapshot browse +
    // diff + rollback. ADMIN+ see it; the rollback action is SUPER_ADMIN-only
    // (gated inside the view).
    { id: 'configHistory', icon: GitBranch, label: t('common.nav_config_history'), capability: 'settings.history' },
    // §11.6 item 20 / 提案 10 doc-deferred sub-items: draft / approve / publish
    // / backup / restore workflow. SUPER_ADMIN-only — nine endpoints under
    // /api/settings/* all require SUPER_ADMIN. Gated by the `config.publish`
    // capability so the sidebar entry, the App route, and the component guard
    // share one source of truth. ADMIN sees configHistory (read + rollback) but
    // never this entry.
    { id: 'configPublish', icon: FileEdit, label: t('common.nav_config_publish'), capability: 'config.publish' },
    // Proposal 09 status page: read-only observability aggregate. No capability
    // gate — every role (including VIEWER) can see it, mirroring the unauth
    // /api/health + VIEWER-readable /api/metrics backend contract.
    { id: 'status', icon: Activity, label: t('status.nav_status') },
  ].filter((item) => !item.capability || can(role, item.capability));

  return (
    <>
      {isMobile && sidebarOpen && <div className="fixed inset-0 z-40 bg-black/50 transition-opacity duration-300" onClick={onOverlayClick} />}

      {/* Sidebar — token-driven (bg-sidebar). Light mode = near-white, dark mode = near-black. */}
      <aside className={`fixed lg:relative z-50 h-full flex flex-col transition-all duration-300 bg-sidebar text-sidebar-foreground border-r border-sidebar-border ${isMobile ? (sidebarOpen ? 'translate-x-0 w-60' : '-translate-x-full w-60') : (sidebarOpen ? 'w-60 translate-x-0' : 'w-16 translate-x-0')}`}>
        <div className="flex-1 flex flex-col p-3 overflow-hidden">
          <div className={`flex items-center mb-6 h-10 shrink-0 transition-all duration-300 ${!isMobile && !sidebarOpen ? 'justify-center px-0' : 'gap-2 px-2'}`}>
            <img src="/novalink-logo.svg" alt="NovaLink" className="size-8 shrink-0 object-contain" />
            <div className={`overflow-hidden whitespace-nowrap transition-all duration-300 ${!isMobile && !sidebarOpen ? 'w-0 opacity-0' : 'w-auto opacity-100'}`}>
              <h1 className="text-sm font-semibold text-foreground">NovaPanel</h1>
            </div>
          </div>
          <nav className="flex-1 space-y-0.5 overflow-y-auto scrollbar-hide">
            {navItems.map((item) => (
              <button
                key={item.id}
                onClick={() => onTabChange(item.id)}
                className={`w-full flex items-center gap-2.5 rounded-md px-3 py-1.5 transition-colors text-xs font-medium ${activeTab === item.id ? 'bg-sidebar-accent text-sidebar-accent-foreground' : 'text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground'}`}
                title={!sidebarOpen && !isMobile ? item.label : ''}
                aria-label={item.label}
              >
                <div className="shrink-0"><item.icon size={16} /></div>
                <span className={`whitespace-nowrap transition-all duration-300 ${!isMobile && !sidebarOpen ? 'opacity-0 w-0 overflow-hidden' : 'opacity-100 w-auto'}`}>{item.label}</span>
              </button>
            ))}
          </nav>
          <div className={`mt-3 rounded-md flex items-center transition-all duration-300 overflow-hidden shrink-0 border border-sidebar-border ${!isMobile && !sidebarOpen ? 'p-1.5 justify-center' : 'p-2'}`}>
            <div className={`shrink-0 flex size-7 items-center justify-center rounded-full bg-primary text-primary-foreground text-xs font-semibold ${!isMobile && !sidebarOpen ? '' : 'mr-2'}`} title={(currentUser && currentUser.username) || t('common.user')}>
              {((currentUser && currentUser.username) || 'U')[0].toUpperCase()}
            </div>
            <div className={`overflow-hidden transition-all duration-300 flex-1 min-w-0 ${!isMobile && !sidebarOpen ? 'w-0 opacity-0' : 'w-auto opacity-100'}`}>
              <p className="text-xs font-medium whitespace-nowrap text-foreground truncate">{(currentUser && currentUser.username) || t('common.user')}</p>
              <p className="text-[11px] whitespace-nowrap text-muted-foreground truncate">{(currentUser && currentUser.role) || ''}</p>
            </div>
            <button onClick={onLogout} className="text-muted-foreground hover:text-destructive transition-colors shrink-0 rounded-md p-1 hover:bg-accent" title={t('common.logout_title')} aria-label={t('common.logout_title')}>
              <LogOut size={16} />
            </button>
          </div>
        </div>
      </aside>
    </>
  );
}

export default Sidebar;
