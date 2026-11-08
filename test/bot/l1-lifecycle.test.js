const test = require('node:test');
const assert = require('node:assert/strict');
const { createL1Lifecycle } = require('./l1-lifecycle');

function harness(cleanupError) {
  const writes = [], exits = [], timers = new Set();
  let cleanups = 0;
  const lifecycle = createL1Lifecycle({ snapshot: () => ({ runId: 'fixture' }),
    write: result => writes.push(result), cleanup: () => { cleanups++; if (cleanupError) throw cleanupError; },
    quit: () => {}, exit: code => exits.push(code), timeoutMs: 100,
    clock: { setTimeout: (fn, ms) => { const timer = { fn, ms }; timers.add(timer); return timer; }, clearTimeout: timer => timers.delete(timer) },
  });
  return { lifecycle, writes, exits, timers, cleanups: () => cleanups };
}

test('initialization failure completes both signals and closes resources once', async () => {
  const h = harness();
  await h.lifecycle.run(async () => { throw new Error('Receiver initialization failed'); }, () => assert.fail('Sequence must not run'));
  assert.equal(await h.lifecycle.initialized, false);
  assert.match((await h.lifecycle.completed).error, /initialization failed/);
  h.lifecycle.fail('later error');
  h.lifecycle.disconnected('later disconnect');
  assert.deepEqual(h.exits, [1]); assert.equal(h.cleanups(), 1); assert.equal(h.writes.length, 1);
});

test('timeout while initialization is pending cannot later become a success', async () => {
  const h = harness();
  let initialize;
  const pending = new Promise(resolve => { initialize = resolve; });
  const running = h.lifecycle.run(() => pending, () => assert.fail('Sequence must not run'));
  h.timers.values().next().value.fn();
  initialize();
  await running;
  assert.deepEqual(h.exits, [2]);
  assert.equal((await h.lifecycle.completed).timedOut, true);
  assert.equal(h.cleanups(), 1);
});

test('cleanup error still writes the original failure and finishes once', async () => {
  const h = harness(new Error('Receiver already closed'));
  h.lifecycle.fail('Connection failed');
  h.lifecycle.fail('Duplicate end');
  assert.equal((await h.lifecycle.completed).error, 'Connection failed');
  assert.deepEqual(h.exits, [1]);
  assert.equal(h.writes.length, 1);
});
