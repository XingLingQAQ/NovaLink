export const MESSAGE_LOG_RETENTION_MAX_DAYS = 365;

const SETTINGS_FIELDS = Object.freeze({
  enableFilter: {
    backendKey: 'filterEnabled',
    type: 'boolean',
    supportedByDefault: true,
    defaultValue: true,
  },
  logMessages: {
    backendKey: 'messageLogEnabled',
    type: 'boolean',
    supportedByDefault: true,
    defaultValue: true,
  },
  crossServerChat: {
    backendKey: 'crossServerChatEnabled',
    type: 'boolean',
    supportedByDefault: true,
    defaultValue: true,
  },
  privateMessagesEnabled: {
    backendKey: 'privateMessagesEnabled',
    type: 'boolean',
    supportedByDefault: false,
    defaultValue: undefined,
  },
  messageLogRetentionDays: {
    backendKey: 'messageLogRetentionDays',
    type: 'retentionDays',
    supportedByDefault: false,
    defaultValue: undefined,
  },
});

const hasOwn = (value, key) => Object.prototype.hasOwnProperty.call(value, key);

export function createInitialSettings() {
  const settings = { supported: {} };
  for (const [key, definition] of Object.entries(SETTINGS_FIELDS)) {
    settings[key] = definition.defaultValue;
    settings.supported[key] = definition.supportedByDefault;
  }
  return settings;
}

export function isValidSettingsValue(key, value) {
  const definition = SETTINGS_FIELDS[key];
  if (!definition) return false;
  if (definition.type === 'boolean') return typeof value === 'boolean';
  if (definition.type === 'retentionDays') {
    return Number.isInteger(value) && value >= 0 && value <= MESSAGE_LOG_RETENTION_MAX_DAYS;
  }
  return false;
}

/**
 * Maps GET /api/settings into the panel shape while feature-detecting fields
 * that older backends do not expose. PANEL-010: captures the server-reported
 * `revision` so the next update can send it back as baseRevision for
 * optimistic-concurrency protection.
 */
export function adaptSettingsResponse(response) {
  const source = response && typeof response === 'object' ? response : {};
  const settings = createInitialSettings();

  // Preserve the established defaults for the three original switches.
  settings.enableFilter = source.filterEnabled !== false;
  settings.logMessages = source.messageLogEnabled !== false;
  settings.crossServerChat = source.crossServerChatEnabled !== false;

  for (const key of ['privateMessagesEnabled', 'messageLogRetentionDays']) {
    if (hasOwn(source, key) && isValidSettingsValue(key, source[key])) {
      settings[key] = source[key];
      settings.supported[key] = true;
    }
  }

  // PANEL-010: stash the server revision so the next update can send it back
  // as baseRevision. Undefined on older backends (no concurrency protection).
  settings.revision = typeof source.revision === 'number' ? source.revision : undefined;

  return settings;
}

/**
 * Builds a partial PUT body. A field is emitted only when the caller marks it
 * as changed, the fetched backend response declared support, and its value is
 * valid for that field.
 */
export function buildSettingsUpdateBody(settings, changedKeys) {
  if (!settings || typeof settings !== 'object') return {};
  const requestedKeys = Array.isArray(changedKeys)
    ? changedKeys
    : (typeof changedKeys === 'string' ? [changedKeys] : []);
  const body = {};

  for (const key of new Set(requestedKeys)) {
    const definition = SETTINGS_FIELDS[key];
    if (!definition || settings.supported?.[key] !== true) continue;
    if (!isValidSettingsValue(key, settings[key])) continue;
    body[definition.backendKey] = settings[key];
  }

  return body;
}
