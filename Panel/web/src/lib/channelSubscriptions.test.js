import test from 'node:test';
import assert from 'node:assert/strict';

import {
  desiredChannelSubscriptions,
  subscriptionDelta,
} from './channelSubscriptions.js';

const channels = [
  { id: 'global', type: 'GLOBAL', subscribable: true },
  { id: 'survival', type: 'SERVER', subscribable: true },
  { id: 'private-staff', type: 'PRIVATE', subscribable: true },
  { id: 'forged-private', type: 'PRIVATE', subscribable: false },
];

test('dashboard subscribes only to authorized global channels', () => {
  assert.deepEqual(desiredChannelSubscriptions(channels, 'dashboard'), ['global']);
});

test('message monitor defaults to authorized non-private channels', () => {
  assert.deepEqual(
    desiredChannelSubscriptions(channels, 'messages', 'all'),
    ['global', 'survival'],
  );
});

test('private channels require explicit selection and backend authorization', () => {
  assert.deepEqual(
    desiredChannelSubscriptions(channels, 'messages', 'private-staff'),
    ['private-staff'],
  );
  assert.deepEqual(
    desiredChannelSubscriptions(channels, 'messages', 'forged-private'),
    [],
  );
});

test('views without live chat keep no channel subscriptions', () => {
  assert.deepEqual(desiredChannelSubscriptions(channels, 'history'), []);
});

test('a reconnected session subscribes only to the latest desired set', () => {
  assert.deepEqual(
    subscriptionDelta(['global', 'survival'], [], []),
    { subscribe: ['global', 'survival'], unsubscribe: [] },
  );
});

test('channels removed while subscribe is in flight are unsubscribed', () => {
  assert.deepEqual(
    subscriptionDelta(['global'], ['global', 'old-server'], ['old-private']),
    { subscribe: [], unsubscribe: ['old-server', 'old-private'] },
  );
});
