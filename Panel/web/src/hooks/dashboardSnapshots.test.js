import '../../_test/dom.mjs';
import test, { afterEach, mock } from 'node:test';
import assert from 'node:assert/strict';
import React, { useEffect, useState } from 'react';
import { render, act, cleanup } from '@testing-library/react';
import { api } from '../services/api.js';
import auth from '../services/auth.js';
import ws, { MessageType } from '../services/websocket.js';
import { useDashboardData } from './useDashboardData.js';
import { useWsOrchestration } from './useWsOrchestration.js';

afterEach(() => { cleanup(); mock.restoreAll(); });
const noop = () => {};
const user = { username: 'admin', role: 'SUPER_ADMIN' };
const deferred = () => {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
};

function mountDashboard() {
  let observed;
  function Harness() {
    const [, setServers] = useState([]);
    const [, setChatMessages] = useState([]);
    const data = useDashboardData({ addToast: noop, currentUser: user });
    useWsOrchestration({ channels: data.channels, activeTab: 'dashboard', selectedChannelId: null,
      setServers, setChatMessages, setChannels: data.setChannels, setPlayers: data.setPlayers,
      onSettingsUpdate: data.handleWsSettingsUpdate, addToast: noop });
    useEffect(() => { observed = data; });
    return null;
  }
  mock.method(api, 'status', async () => ({}));
  mock.method(api, 'getMutes', async () => ({ mutes: [] }));
  mock.method(api, 'getBans', async () => []);
  mock.method(api, 'getNotifications', async () => ({ unreadCount: 0 }));
  mock.method(api, 'getSettings', async () => ({ revision: 1 }));
  mock.method(auth, 'getToken', () => 'synthetic-token');
  mock.method(ws, 'connect', async () => true);
  mock.method(ws, 'setSubscriptions', noop);
  render(React.createElement(Harness));
  return () => observed;
}

test('initial REST cannot replace newer WS channels or players', async () => {
  const channels = deferred(), players = deferred();
  mock.method(api, 'getChannels', () => channels.promise);
  mock.method(api, 'getPlayers', () => players.promise);
  const data = mountDashboard();
  await act(async () => {
    ws._notifyListeners(MessageType.CHANNEL_UPDATE, { channels: [{ id: 'new-channel', scope: 'GLOBAL' }], revision: 100 });
    ws._notifyListeners(MessageType.PLAYER_UPDATE, { players: [{ uuid: 'new-player', channels: [] }], revision: 101 });
  });
  await act(async () => {
    channels.resolve({ channels: [{ id: 'old-channel', scope: 'GLOBAL' }] });
    players.resolve({ players: [{ uuid: 'old-player' }] });
  });
  assert.deepEqual(data().channels.map((entry) => entry.id), ['new-channel']);
  assert.deepEqual(data().players.map((entry) => entry.uuid), ['new-player']);
});

test('refreshes after CRUD and player actions also respect intervening WS state', async () => {
  let channelRead = () => Promise.resolve({ channels: [] });
  let playerRead = () => Promise.resolve({ players: [] });
  mock.method(api, 'getChannels', () => channelRead());
  mock.method(api, 'getPlayers', () => playerRead());
  mock.method(api, 'createChannel', async () => ({}));
  mock.method(api, 'kickPlayer', async () => ({}));
  const data = mountDashboard();
  await act(async () => {});
  const channels = deferred(), players = deferred();
  channelRead = () => channels.promise;
  playerRead = () => players.promise;
  let create, kick;
  await act(async () => {
    create = data().handleCreateChannel({ id: 'created' });
    kick = data().handleKickPlayer({ uuid: 'player-1' });
  });
  await act(async () => {
    ws._notifyListeners(MessageType.CHANNEL_UPDATE, { channels: [{ id: 'ws-channel', scope: 'GLOBAL' }] });
    ws._notifyListeners(MessageType.PLAYER_UPDATE, { players: [{ uuid: 'ws-player', channels: [] }] });
    channels.resolve({ channels: [{ id: 'stale-channel', scope: 'GLOBAL' }] });
    players.resolve({ players: [{ uuid: 'stale-player' }] });
    await Promise.all([create, kick]);
  });
  assert.deepEqual(data().channels.map((entry) => entry.id), ['ws-channel']);
  assert.deepEqual(data().players.map((entry) => entry.uuid), ['ws-player']);
});

test('a later manual REST refresh supersedes an earlier one', async () => {
  const old = deferred(), current = deferred();
  let reads = 0;
  mock.method(api, 'getChannels', () => ++reads === 1 ? old.promise : current.promise);
  mock.method(api, 'getPlayers', async () => ({ players: [] }));
  const data = mountDashboard();
  let refresh;
  await act(async () => { refresh = data().fetchAllData(); });
  await act(async () => { current.resolve({ channels: [{ id: 'current', scope: 'GLOBAL' }] }); await refresh; });
  await act(async () => old.resolve({ channels: [{ id: 'old', scope: 'GLOBAL' }] }));
  assert.deepEqual(data().channels.map((entry) => entry.id), ['current']);
});
