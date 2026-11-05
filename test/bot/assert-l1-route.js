const assert = require('node:assert/strict');
const fs = require('node:fs');
const { tokenFor, peerEvents } = require('./l1-route');

function decode(body) {
  const bytes = Buffer.from(body, 'base64');
  let offset = 0;
  const take = size => { assert.ok(size >= 0 && offset + size <= bytes.length, 'Incomplete peer packet'); const value = bytes.subarray(offset, offset + size); offset += size; return value; };
  const uuid = () => { const h = take(16).toString('hex'); return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`; };
  const string = () => {
    let size = 0;
    let shift = 0;
    while (true) { assert.ok(shift < 28); const next = take(1)[0]; size |= (next & 127) << shift; if (!(next & 128)) break; shift += 7; }
    return take(size).toString('utf8');
  };
  const id = take(1)[0];
  const requestId = uuid();
  if (id === 3) return { id, requestId, senderId: uuid(), senderName: string(), clientId: string(), channelId: string(), content: string() };
  if (id === 2) return { id, requestId, success: take(1)[0] === 1 };
  if (id === 5) return { id, requestId, success: take(1)[0] === 1, action: take(1)[0], channelId: string() };
  if (id === 4) return { id, requestId, action: take(1)[0], channelId: string() };
  throw new Error(`Unexpected peer packet ${id}`);
}

function verify(results, events) {
  for (const value of [results, ...events]) {
    if (Object.hasOwn(value, 'synthetic')) assert.equal(value.synthetic, false, 'Synthetic recordings are not real L1 evidence');
  }
  assert.equal(results.sequenceComplete, true, 'Game sequence did not complete');
  assert.ok(!results.error && !results.timedOut, 'Game sender failed');
  assert.ok(results.received.some(row => row.kind === 'spawn'), 'Game sender never spawned');
  assert.ok(!results.received.some(row => ['error', 'kicked'].includes(row.kind)), 'Game sender failed');
  const route = results.route;
  assert.ok(route && route.runId === results.runId && route.sender === results.username, 'Missing current-run route recording');
  assert.equal(route.peerExitCode, 0, 'Backend peer did not finish successfully');
  const kinds = ['authenticated', 'join-sent', 'ready', 'chat-received', 'chat-sent', 'complete'];
  assert.deepEqual(events.map(row => row.kind), kinds, 'Missing, duplicate or reordered backend receipts');
  assert.ok(events.every(row => row.runId === results.runId && Number.isFinite(row.atMs)));
  assert.ok(events.every((row, i) => i === 0 || row.atMs >= events[i - 1].atMs));
  const [auth, join, ready, incoming, outgoing] = events.slice(0, 5).map(row => decode(row.wire));
  assert.equal(auth.id, 2); assert.equal(auth.success, true);
  assert.equal(auth.requestId, events[0].requestId);
  assert.equal(events[0].clientId, 'E2E_RoutePeer');
  assert.equal(join.id, 4); assert.equal(join.action, 0); assert.equal(join.channelId, 'global');
  assert.equal(ready.id, 5); assert.equal(ready.action, 0); assert.equal(ready.success, true);
  assert.equal(ready.channelId, 'global'); assert.equal(ready.requestId, join.requestId);
  assert.equal(incoming.id, 3); assert.equal(incoming.senderName, results.username);
  assert.equal(incoming.clientId, 'E2E_Client'); assert.equal(incoming.channelId, 'global');
  assert.equal(incoming.content, tokenFor(results.runId));
  const sent = results.received.filter(row => row.kind === 'sent' && row.text === incoming.content);
  assert.equal(sent.length, 1, 'Missing or duplicate actual game send');
  assert.ok(Date.parse(sent[0].t) >= events[2].atMs, 'Game send preceded peer readiness');
  assert.ok(Date.parse(sent[0].t) <= events[3].atMs);
  assert.equal(outgoing.id, 3); assert.equal(outgoing.senderName, 'E2E_RoutePeer');
  assert.equal(outgoing.clientId, 'E2E_RoutePeer'); assert.equal(outgoing.channelId, 'global');
  assert.equal(outgoing.senderId, events[2].playerId);
  assert.equal(outgoing.content, `l1-${results.runId}-reply-${incoming.requestId}`);
  assert.notEqual(outgoing.requestId, incoming.requestId);
  assert.notEqual(outgoing.senderId, incoming.senderId);
  assert.equal(route.receiver.username, 'E2ERouteRecv');
  assert.notEqual(route.receiver.username, results.username);
  const rows = route.receiver.received;
  assert.ok(rows.some(row => row.kind === 'spawn'), 'Receiver never spawned');
  assert.ok(rows.some(row => row.kind === 'sent' && row.text === '/nc join global'), 'Receiver did not join');
  assert.ok(rows.some(row => row.kind === 'spawn' && Date.parse(row.t) <= Date.parse(sent[0].t)), 'Receiver was not live at send time');
  assert.ok(!rows.some(row => ['error', 'kicked'].includes(row.kind)), 'Receiver failed');
  const plain = value => value.replace(/§.|&[0-9a-fk-or]/gi, '');
  const received = rows.filter(row => ['messagestr', 'text:raw', 'text:translation', 'text:system', 'text:announcement'].includes(row.kind));
  for (const packet of [incoming, outgoing]) {
    const expected = new RegExp(`\\[[^\\]]+\\]\\s*${packet.senderName}\\s*:\\s*${packet.content.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?:$|\\s)`);
    const matches = received.filter(row => expected.test(plain(row.text)));
    assert.equal(matches.length, 1, 'Independent game receiver must receive each routed message exactly once');
    const receipt = Date.parse(matches[0].t);
    assert.ok(Number.isFinite(receipt) && receipt >= Date.parse(sent[0].t));
    if (packet === outgoing) {
      assert.ok(receipt >= events[4].atMs, 'Reply preceded the actual backend send');
      assert.ok(!rows.some(row => ['end', 'close'].includes(row.kind) && Date.parse(row.t) < receipt), 'Receiver disconnected during the route');
    }
  }
  assert.ok(!results.received.some(row => row.kind === 'sent' && row.text.includes(outgoing.content)), 'Game sender supplied the peer reply');
  return { runId: results.runId, receiver: route.receiver.username, incomingRequestId: incoming.requestId, outgoingRequestId: outgoing.requestId };
}

if (require.main === module) {
  try {
    const file = process.argv[2];
    const result = verify(JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')), peerEvents(`${file}.route-peer.jsonl`));
    console.log(`PASS real L1 backend route and independent receiver (${result.runId})`);
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { verify, decode };
