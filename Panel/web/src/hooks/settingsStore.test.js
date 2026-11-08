import test from 'node:test';
import assert from 'node:assert/strict';
import { createSettingsStore } from './settingsStore.js';

const snapshot = (revision, filterEnabled = true) => ({ revision, filterEnabled, messageLogEnabled: true, crossServerChatEnabled: true });
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

test('successful saves consume returned revisions without WebSocket', async () => {
  const bases = [];
  const store = createSettingsStore({
    getSettings: async () => snapshot(10),
    updateSettings: async (body, base) => { bases.push(base); return snapshot(base + 1, body.filterEnabled); },
  }, () => {});
  await store.fetchSettings();
  assert.equal(await store.change('enableFilter', false), true);
  assert.equal(await store.change('enableFilter', true), true);
  assert.deepEqual(bases, [10, 11]);
  assert.equal(store.getSnapshot().settings.revision, 12);
});

test('409 adopts the current server snapshot and the next edit uses its revision', async () => {
  const bases = [];
  const notifications = [];
  const store = createSettingsStore({
    getSettings: () => snapshot(10),
    updateSettings: (body, base) => {
      bases.push(base);
      if (bases.length === 1) return Promise.reject(Object.assign(new Error('Conflict'), { status: 409, data: snapshot(12, true) }));
      return snapshot(13, body.filterEnabled);
    },
  }, key => notifications.push(key));
  await store.fetchSettings();
  assert.equal(await store.change('enableFilter', false), false);
  assert.equal(store.getSnapshot().settings.revision, 12);
  assert.equal(store.getSnapshot().settings.enableFilter, true);
  assert.ok(notifications.includes('common.settings_conflict'));
  assert.equal(await store.change('enableFilter', false), true);
  assert.deepEqual(bases, [10, 12]);
  assert.equal(store.getSnapshot().settings.revision, 13);
});

test('old REST, PUT and conflict snapshots cannot replace a newer WebSocket snapshot', async (t) => {
  for (const kind of ['get', 'put', 'conflict', 'failure']) {
    await t.test(kind, async () => {
      const pending = deferred();
      let first = true;
      const store = createSettingsStore({
        getSettings: () => { if (first) { first = false; return snapshot(10); } return pending.promise; },
        updateSettings: () => pending.promise,
      }, () => {});
      await store.fetchSettings();
      const task = kind === 'get' ? store.fetchSettings() : store.change('enableFilter', false);
      store.receive({ settingsRevision: 12, settings: snapshot(12, true) });
      if (kind === 'conflict') pending.reject(Object.assign(new Error('Conflict'), { status: 409, data: snapshot(11, false) }));
      else if (kind === 'failure') pending.reject(new Error('Write failed'));
      else pending.resolve(snapshot(11, false));
      await task;
      assert.equal(store.getSnapshot().settings.revision, 12);
      assert.equal(store.getSnapshot().settings.enableFilter, true);
    });
  }
});

test('duplicate submission is blocked and a rejected write restores the last committed value', async () => {
  const pending = deferred();
  let calls = 0;
  const store = createSettingsStore({ getSettings: () => snapshot(1), updateSettings: () => { calls++; return pending.promise; } }, () => {});
  await store.fetchSettings();
  const saving = store.change('enableFilter', false);
  assert.equal(await store.change('enableFilter', true), false);
  pending.reject(new Error('Disk full'));
  assert.equal(await saving, false);
  assert.equal(calls, 1);
  assert.equal(store.getSnapshot().settings.enableFilter, true);
  assert.equal(store.getSnapshot().settings.revision, 1);
});
