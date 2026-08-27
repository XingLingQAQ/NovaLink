/**
 * Sidebar — logo, grouped nav items and the user area. The flat 18-entry list
 * is collapsed into four labelled sections (Overview / Operations /
 * Moderation & Safety / System) so the operator can scan the rail by purpose
 * instead of by a long undifferentiated list. Settings-adjacent surfaces
 * (webhooks / word filter / config history / config publish) no longer have
 * their own top-level entries — they live as inner tabs of the Settings page.
 *
 * Collapse state per group is persisted in localStorage; the group holding the
 * active tab is always expanded regardless of the persisted state. Groups with
 * no visible leaves (after RBAC filtering) are dropped entirely. In the
 * collapsed-icon-rail mode (desktop, sidebar closed) group headers are hidden
 * and every leaf renders as an icon, preserving the old rail behaviour.
 */

import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  LayoutDashboard,
  Server,
  Users,
  MessageSquare,
  Hash,
  Settings,
  Terminal,
  LogOut,
  History,
  Megaphone,
  Filter,
  ScrollText,
  ShieldAlert,
  Gavel,
  Activity,
  Send,
  ChevronDown,
} from 'lucide-react';

import { can } from '../../lib/permissions';

const SIDEBAR_GROUPS_KEY = 'nova-panel-sidebar-groups';

function loadCollapsedGroups() {
  try {
    const raw = globalThis.localStorage?.getItem(SIDEBAR_GROUPS_KEY);
    if (raw) return JSON.parse(raw);
  } catch {
    // ignore storage / parse errors — degrade to all-expanded
  }
  return {};
}

function Sidebar({ activeTab, onTabChange, sidebarOpen, isMobile, onOverlayClick, currentUser, onLogout, role }) {
  const { t } = useTranslation();
  const [collapsed, setCollapsed] = useState(loadCollapsedGroups);

  // Entries with a `capability` are hidden for roles lacking it.
  const groups = [
    {
      id: 'overview',
      label: t('common.nav_group_overview'),
      items: [
        { id: 'dashboard', icon: LayoutDashboard, label: t('common.nav_dashboard') },
        // Proposal 09 status page: read-only observability aggregate. No
        // capability gate — every role (including VIEWER) can see it.
        { id: 'status', icon: Activity, label: t('status.nav_status') },
      ],
    },
    {
      id: 'operations',
      label: t('common.nav_group_operations'),
      items: [
        { id: 'messages', icon: MessageSquare, label: t('common.nav_messages') },
        { id: 'history', icon: History, label: t('common.nav_history') },
        { id: 'console', icon: Terminal, label: t('common.nav_console_command'), capability: 'console' },
        { id: 'servers', icon: Server, label: t('common.nav_servers') },
        { id: 'channels', icon: Hash, label: t('common.nav_channels') },
        { id: 'players', icon: Users, label: t('common.nav_players') },
        { id: 'announcements', icon: Megaphone, label: t('common.nav_announcements'), capability: 'announcements.manage' },
        // §11.6 提案 06 (item 19): campaign orchestration. Visible to ADMIN+
        // under the announcements capability.
        { id: 'campaigns', icon: Send, label: t('common.nav_campaigns'), capability: 'announcements.manage' },
      ],
    },
    {
      id: 'moderation',
      label: t('common.nav_group_moderation'),
      items: [
        // PANEL-007: moderation + appeals. Both are ADMIN+ only; VIEWER never
        // sees these entries.
        { id: 'moderation', icon: ShieldAlert, label: t('common.nav_moderation'), capability: 'moderation.view' },
        { id: 'appeals', icon: Gavel, label: t('common.nav_appeals'), capability: 'appeals.review' },
        { id: 'audit', icon: ScrollText, label: t('common.nav_audit'), capability: 'audit.view' },
      ],
    },
    {
      id: 'system',
      label: t('common.nav_group_system'),
      items: [
        { id: 'settings', icon: Settings, label: t('common.nav_settings') },
        // Webhook / word-filter / config-history / config-publish surfaces are
        // now inner tabs of Settings (see SettingsView.jsx). They have no
        // sidebar leaf; App.jsx's handleTabChange still accepts their ids for
        // deep-link compatibility and redirects into the matching Settings tab.
      ],
    },
  ].map((group) => ({
    ...group,
    items: group.items.filter((item) => !item.capability || can(role, item.capability)),
  })).filter((group) => group.items.length > 0);

  const toggleGroup = (groupId) => {
    setCollapsed((prev) => {
      const next = { ...prev, [groupId]: !prev[groupId] };
      try {
        globalThis.localStorage?.setItem(SIDEBAR_GROUPS_KEY, JSON.stringify(next));
      } catch {
        // ignore storage errors
      }
      return next;
    });
  };

  // Collapsed icon-rail mode (desktop, sidebar closed): headers hidden, every
  // leaf shown as an icon. Otherwise a group is expanded when it holds the
  // active tab (always) or when it is not collapsed (persisted state).
  const railMode = !sidebarOpen && !isMobile;
  const isExpanded = (group) => railMode || group.items.some((it) => it.id === activeTab) || !collapsed[group.id];

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
          <nav className="flex-1 overflow-y-auto scrollbar-hide" aria-label={t('common.nav_settings')}>
            {groups.map((group) => {
              const expanded = isExpanded(group);
              return (
                <div key={group.id} className="space-y-0.5">
                  {!railMode && (
                    <button
                      type="button"
                      onClick={() => toggleGroup(group.id)}
                      aria-expanded={expanded}
                      aria-controls={`nav-group-${group.id}`}
                      aria-label={group.label}
                      className="w-full flex items-center gap-2.5 rounded-md px-3 pt-2 pb-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/70 hover:text-foreground transition-colors"
                    >
                      <ChevronDown size={12} className={`shrink-0 transition-transform duration-200 ${expanded ? '' : '-rotate-90'}`} />
                      <span className="whitespace-nowrap">{group.label}</span>
                    </button>
                  )}
                  {expanded && (
                    <ul id={`nav-group-${group.id}`} role="group" aria-label={group.label} className="space-y-0.5">
                      {group.items.map(({ id, icon: Icon, label }) => (
                        <li key={id}>
                          <button
                            onClick={() => onTabChange(id)}
                            className={`w-full flex items-center gap-2.5 rounded-md px-3 py-1.5 transition-colors text-xs font-medium ${activeTab === id ? 'bg-sidebar-accent text-sidebar-accent-foreground' : 'text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground'}`}
                            title={railMode ? label : ''}
                            aria-label={label}
                            aria-current={activeTab === id ? 'page' : undefined}
                          >
                            <div className="shrink-0"><Icon size={16} /></div>
                            <span className={`whitespace-nowrap transition-all duration-300 ${railMode ? 'opacity-0 w-0 overflow-hidden' : 'opacity-100 w-auto'}`}>{label}</span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              );
            })}
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
