/**
 * Lightweight role -> capability mapping for the panel UI (UX layer only —
 * the backend enforces RBAC on every endpoint).
 *
 * Login response: user.role ∈ SUPER_ADMIN | ADMIN | VIEWER.
 * Unknown / missing roles (including the legacy pre-RBAC CLIENT_ADMIN) are
 * treated as VIEWER — the safe, read-only default.
 */

const ROLE_CAPABILITIES = {
  VIEWER: [],
  ADMIN: [
    'punish', // mute / unmute / kick / ban / unban
    'channels.manage', // create / edit / delete / invite code
    'messages.send',
    'announcements.manage', // announcements page (incl. sidebar entry)
    'filter.manage', // word-filter page (incl. sidebar entry)
    'audit.view', // audit log page (ADMIN+ read)
    // PANEL-007 moderation / appeals. VIEWER is intentionally NOT granted
    // these: a default admin must not browse private-chat content. The only
    // path to private-chat content is via a case's minimal evidence snapshot.
    'moderation.view', // view case / appeal lists + detail
    'moderation.manage', // create report, assign, resolve cases
    'appeals.review', // review appeals (backend blocks self-review)
    // §11.6 Project 20 / PANEL proposal 10: browse masked config snapshots +
    // diff. ADMIN+ (the rollback action is SUPER_ADMIN-only — see below).
    'settings.history',
  ],
  SUPER_ADMIN: [
    'punish',
    'channels.manage',
    'messages.send',
    'announcements.manage',
    'filter.manage',
    'webhooks.manage', // create / edit / delete / test webhooks
    'settings.edit', // write backend settings
    'console', // console command page (incl. sidebar entry)
    'clients.disconnect',
    'config.reload',
    'audit.view',
    'moderation.view',
    'moderation.manage',
    'appeals.review',
    // §11.6 Project 20 / PANEL proposal 10: SUPER_ADMIN also sees config
    // history AND can roll the live config back to a chosen revision.
    'settings.history',
    'config.rollback',
  ],
};

export function normalizeRole(role) {
  if (role === 'SUPER_ADMIN' || role === 'ADMIN') return role;
  return 'VIEWER';
}

/**
 * @param {string|null|undefined} role - raw role from the login response
 * @param {string} capability - e.g. can(role, 'console')
 * @returns {boolean}
 */
export function can(role, capability) {
  return ROLE_CAPABILITIES[normalizeRole(role)].includes(capability);
}
