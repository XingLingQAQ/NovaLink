const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');

function createHarness(filename) {
  const client = new EventEmitter();
  const timers = [];
  const writes = [];
  const exits = [];
  client.username = 'TestBot';
  client.chat = () => {};
  client.queue = () => {};
  client.quit = () => client.emit('end');
  client.close = () => client.emit('close');
  const modules = {
    mineflayer: { createBot: () => client },
    'bedrock-protocol': { createClient: () => client },
    jsonwebtoken: {},
    fs: { writeFileSync: (_, value) => writes.push(JSON.parse(value)) },
  };
  const context = {
    require: name => modules[name] || require(name),
    process: { env: { E2E_RUN_ID: 'current-run' }, exit: code => exits.push(code) },
    console: { log() {} },
    __dirname,
    Buffer,
    setTimeout: (fn, ms) => { timers.push({ fn, ms }); },
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, filename), 'utf8'), context, { filename });
  return {
    client, timers, writes, exits,
    async step() {
      const index = timers.findIndex(timer => timer.ms < 360000);
      assert.notEqual(index, -1, 'expected a sequence timer');
      timers.splice(index, 1)[0].fn();
      await Promise.resolve();
      await Promise.resolve();
    },
  };
}

for (const filename of ['run-e2e.js', 'run-e2e-bedrock.js']) {
  const closeEvent = filename === 'run-e2e.js' ? 'end' : 'close';
  const kickEvent = filename === 'run-e2e.js' ? 'kicked' : 'kick';

  test(`${filename}: early disconnect fails`, () => {
    const harness = createHarness(filename);
    harness.client.emit(closeEvent);
    assert.equal(harness.exits.at(-1), 1);
    assert.equal(harness.writes.at(-1).sequenceComplete, false);
    assert.ok(harness.writes.at(-1).error);
  });

  test(`${filename}: kick and errors fail`, () => {
    for (const event of [kickEvent, 'error']) {
      const harness = createHarness(filename);
      harness.client.emit(event, event === 'error' ? new Error('network failed') : 'kicked');
      assert.equal(harness.exits.at(-1), 1);
      assert.ok(harness.writes.at(-1).error);
    }
  });

  test(`${filename}: timeout cannot look complete`, () => {
    const harness = createHarness(filename);
    harness.timers.find(timer => timer.ms === 360000).fn();
    assert.equal(harness.exits.at(-1), 2);
    assert.equal(harness.writes.at(-1).timedOut, true);
    assert.equal(harness.writes.at(-1).sequenceComplete, false);
  });

  test(`${filename}: normal quit preserves successful completion`, async () => {
    const harness = createHarness(filename);
    harness.client.emit('spawn');
    for (let i = 0; !harness.exits.length && i < 80; i++) await harness.step();
    assert.deepEqual(harness.exits, [0]);
    const result = harness.writes.at(-1);
    assert.equal(result.runId, 'current-run');
    assert.equal(result.sequenceComplete, true);
    assert.equal(result.error, undefined);
    assert.equal(result.received.at(-1).kind, closeEvent);
  });

  test(`${filename}: disconnect after sequence but before quit still fails`, async () => {
    const harness = createHarness(filename);
    harness.client.emit('spawn');
    for (let i = 0; !harness.writes.length && i < 80; i++) await harness.step();
    assert.equal(harness.writes.at(-1).sequenceComplete, true);
    harness.client.emit(closeEvent);
    assert.equal(harness.exits.at(-1), 1);
    assert.ok(harness.writes.at(-1).error);
  });
}
