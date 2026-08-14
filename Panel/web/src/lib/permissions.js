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
