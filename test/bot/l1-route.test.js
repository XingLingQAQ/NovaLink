const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { verify, decode } = require('./assert-l1-route');
const { peerEvents } = require('./l1-route');

test('chat decoder agrees with the committed production wire golden', () => {
  const golden = JSON.parse(fs.readFileSync(path.join(__dirname, '../protocol-golden/chat_message_full.json'), 'utf8'));
  const packet = decode(Buffer.from(golden.frameHex, 'hex').subarray(1).toString('base64'));
  for (const field of ['requestId', 'senderId', 'senderName', 'clientId', 'channelId', 'content']) {
    assert.equal(packet[field], golden.fields[field] || golden[field]);
  }
});

const source = process.env.L1_ACCEPTANCE;
test('retained real L1 route receipts pass and missing evidence fails', { skip: !source }, async t => {
  const results = JSON.parse(fs.readFileSync(source, 'utf8'));
  const events = peerEvents(`${source}.route-peer.jsonl`);
  verify(results, events);
  for (const kind of ['authenticated', 'ready', 'chat-received', 'chat-sent', 'complete']) {
    await t.test(`missing backend ${kind}`, () => assert.throws(() => verify(results, events.filter(row => row.kind !== kind))));
  }
  for (const [label, change] of [
    ['synthetic result', r => { r.synthetic = true; }],
    ['invalid synthetic marker', r => { r.synthetic = 'false'; }],
    ['old run', r => { r.runId = 'another-run'; }],
    ['no independent receiver', r => { r.route.receiver.username = r.username; }],
    ['no receiver spawn', r => { r.route.receiver.received = r.route.receiver.received.filter(row => row.kind !== 'spawn'); }],
    ['no receiver reply', r => { r.route.receiver.received = r.route.receiver.received.filter(row => !row.text.includes('-reply-')); }],
    ['only outgoing text', r => { r.route.receiver.received.forEach(row => { if (row.kind !== 'spawn') row.kind = 'sent'; }); }],
    ['duplicate game reply', r => { r.route.receiver.received.push(r.route.receiver.received.find(row => row.kind !== 'sent' && row.text.includes('-reply-'))); }],
    ['no actual game send', r => { r.received = r.received.filter(row => row.kind !== 'sent'); }],
  ]) {
    await t.test(label, () => { const changed = structuredClone(results); change(changed); assert.throws(() => verify(changed, events)); });
  }
});
