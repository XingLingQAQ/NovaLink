const test = require('node:test');
const assert = require('node:assert/strict');
const { verify } = require('./assert-l1-route');
const { tokenFor } = require('./l1-route');

const ids = Array.from({ length: 6 }, (_, i) => `00000000-0000-0000-0000-${String(i + 1).padStart(12, '0')}`);
const uuid = value => Buffer.from(value.replaceAll('-', ''), 'hex');
const string = value => {
  const text = Buffer.from(value);
  const length = []; let remaining = text.length;
  do { const part = remaining & 127; remaining >>>= 7; length.push(part | (remaining ? 128 : 0)); } while (remaining);
  return Buffer.concat([Buffer.from(length), text]);
};
const packet = (id, request, ...fields) => Buffer.concat([Buffer.from([id]), uuid(request), ...fields]).toString('base64');

function fixture() {
  const runId = 'offline-fixture';
  const username = 'TestSender';
  const text = tokenFor(runId);
  const reply = `l1-${runId}-reply-${ids[2]}`;
  const kinds = ['authenticated', 'join-sent', 'ready', 'chat-received', 'chat-sent', 'complete'];
  const wire = [packet(2, ids[0], Buffer.from([1])), packet(4, ids[1], Buffer.from([0]), string('global')),
    packet(5, ids[1], Buffer.from([1, 0]), string('global')),
    packet(3, ids[2], uuid(ids[4]), string(username), string('E2E_Client'), string('global'), string(text)),
    packet(3, ids[3], uuid(ids[5]), string('E2E_RoutePeer'), string('E2E_RoutePeer'), string('global'), string(reply)), ''];
  const events = kinds.map((kind, i) => ({ kind, runId, atMs: 1000 + i * 1000, wire: wire[i] }));
  events[0].requestId = ids[0]; events[0].clientId = 'E2E_RoutePeer'; events[2].playerId = ids[5];
  const row = (kind, at, text = '') => ({ kind, t: new Date(at).toISOString(), text });
  const results = { runId, username, sequenceComplete: true, received: [row('spawn', 1000), row('sent', 3500, text)],
    route: { runId, sender: username, peerExitCode: 0, receiver: { username: 'E2ERouteRecv', received: [
      row('spawn', 2000), row('sent', 2500, '/nc join global'),
      row('messagestr', 4500, `[global] ${username}: ${text}`), row('messagestr', 5500, `[global] E2E_RoutePeer: ${reply}`),
    ] } } };
  return { results, events };
}

test('offline fixtures exercise L1 rejection gates without a real recording', async t => {
  const original = fixture();
  verify(original.results, original.events);
  const cases = [
    ['synthetic marker', r => { r.synthetic = true; }],
    ['invalid synthetic marker', r => { r.synthetic = 'false'; }],
    ['stale run', r => { r.runId = 'stale'; }],
    ['unfinished sequence', r => { r.sequenceComplete = false; }],
    ['failed peer', r => { r.route.peerExitCode = null; }],
    ['sender as receiver', r => { r.route.receiver.username = r.username; }],
    ['missing receiver spawn', r => { r.route.receiver.received.shift(); }],
    ['missing actual send', r => { r.received.pop(); }],
    ['missing receiver reply', r => { r.route.receiver.received.pop(); }],
    ['duplicate reply', r => { r.route.receiver.received.push(r.route.receiver.received.at(-1)); }],
    ['outgoing text only', r => { r.route.receiver.received.forEach(row => { row.kind = 'sent'; }); }],
    ['premature reply', r => { r.route.receiver.received.at(-1).t = new Date(4000).toISOString(); }],
  ];
  for (const [name, change] of cases) await t.test(name, () => {
    const { results, events } = fixture(); change(results);
    assert.throws(() => verify(results, events));
  });
  for (const kind of original.events.map(row => row.kind)) await t.test(`missing ${kind}`, () => {
    assert.throws(() => verify(original.results, original.events.filter(row => row.kind !== kind)));
  });
  await t.test('wrong receipt identity', () => {
    const { results, events } = fixture(); events[2].playerId = ids[4];
    assert.throws(() => verify(results, events));
  });
});
