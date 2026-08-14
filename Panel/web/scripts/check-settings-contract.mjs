import assert from 'node:assert/strict';

import {
  adaptSettingsResponse,
  buildSettingsUpdateBody,
  isValidSettingsValue,
} from '../src/lib/settingsContract.js';

const legacy = adaptSettingsResponse({
  filterEnabled: true,
  messageLogEnabled: false,
  crossServerChatEnabled: true,
});

assert.equal(legacy.enableFilter, true);
assert.equal(legacy.logMessages, false);
assert.equal(legacy.crossServerChat, true);
assert.equal(legacy.supported.privateMessagesEnabled, false);
assert.equal(legacy.supported.messageLogRetentionDays, false);
assert.equal(legacy.privateMessagesEnabled, undefined);
assert.equal(legacy.messageLogRetentionDays, undefined);

assert.deepEqual(
  buildSettingsUpdateBody(
    { ...legacy, privateMessagesEnabled: false, messageLogRetentionDays: 0 },
    ['privateMessagesEnabled', 'messageLogRetentionDays'],
  ),
  {},
  'legacy responses must not cause unsupported optional fields to be submitted',
);

assert.deepEqual(
  buildSettingsUpdateBody({ ...legacy, enableFilter: false }, ['enableFilter']),
  { filterEnabled: false },
  'only the explicitly changed legacy setting should be submitted',
);

const modern = adaptSettingsResponse({
  filterEnabled: true,
  messageLogEnabled: true,
  crossServerChatEnabled: false,
  privateMessagesEnabled: false,
  messageLogRetentionDays: 0,
});

assert.equal(modern.supported.privateMessagesEnabled, true);
assert.equal(modern.supported.messageLogRetentionDays, true);
assert.equal(modern.privateMessagesEnabled, false, 'a supported false value must be preserved');
assert.equal(modern.messageLogRetentionDays, 0, 'zero-day permanent retention must be preserved');

assert.deepEqual(
  buildSettingsUpdateBody(modern, ['privateMessagesEnabled']),
  { privateMessagesEnabled: false },
);
assert.deepEqual(
  buildSettingsUpdateBody({ ...modern, messageLogRetentionDays: 365 }, ['messageLogRetentionDays']),
  { messageLogRetentionDays: 365 },
);
assert.deepEqual(
  buildSettingsUpdateBody(
    { ...modern, privateMessagesEnabled: true, messageLogRetentionDays: 30 },
    ['privateMessagesEnabled', 'messageLogRetentionDays'],
  ),
  { privateMessagesEnabled: true, messageLogRetentionDays: 30 },
);

assert.equal(isValidSettingsValue('messageLogRetentionDays', 0), true);
assert.equal(isValidSettingsValue('messageLogRetentionDays', 365), true);
assert.equal(isValidSettingsValue('messageLogRetentionDays', -1), false);
assert.equal(isValidSettingsValue('messageLogRetentionDays', 366), false);
assert.equal(isValidSettingsValue('messageLogRetentionDays', 1.5), false);
assert.deepEqual(
  buildSettingsUpdateBody({ ...modern, messageLogRetentionDays: 366 }, ['messageLogRetentionDays']),
  {},
  'invalid retention values must not be submitted',
);
assert.deepEqual(buildSettingsUpdateBody(modern, ['unknownSetting']), {});

console.log('settings contract checks passed');
