const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { verify } = require('./assert-evidence');

const source = process.env.BT_ACCEPTANCE;
test('retained transfer evidence survives replay, not missing or altered raw evidence', { skip: !source }, async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'novalink-transfer-evidence-'));
  assert.equal(path.dirname(root), path.resolve(os.tmpdir()));
  assert.ok(path.basename(root).startsWith('novalink-transfer-evidence-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const file of ['plan.json', 'bot.results.json', 'events.jsonl']) fs.copyFileSync(path.join(source, file), path.join(root, file));
  fs.cpSync(path.join(source, 'console'), path.join(root, 'console'), { recursive: true });
  for (const node of ['a', 'b']) {
    fs.mkdirSync(path.join(root, node, 'pnx'), { recursive: true });
    for (const file of ['launch-spec.json', 'pnx/pnx.stdout.log']) fs.copyFileSync(path.join(source, node, file), path.join(root, node, file));
  }
  const result = JSON.parse(fs.readFileSync(path.join(root, 'bot.results.json'), 'utf8'));
  const events = result.received;
  const save = rows => {
    fs.writeFileSync(path.join(root, 'events.jsonl'), rows.map(row => JSON.stringify(row)).join('\n') + '\n');
    fs.writeFileSync(path.join(root, 'bot.results.json'), JSON.stringify({ ...result, received: rows }));
  };
  verify(root);
  for (const [file, marker] of ['plan.json', 'bot.results.json'].flatMap(file => [[file, true], [file, 'true']])) {
    await t.test(`${file} synthetic marker ${typeof marker} is rejected`, () => {
      const target = path.join(root, file);
      const original = fs.readFileSync(target, 'utf8');
      try {
        fs.writeFileSync(target, JSON.stringify({ ...JSON.parse(original), synthetic: marker }));
        assert.throws(() => verify(root), /Synthetic or invalid marker cannot satisfy/);
      } finally {
        fs.writeFileSync(target, original);
      }
    });
  }
  for (const [label, predicate, replacement] of [
    ['initial start_game', e => e.kind === 'start_game' && e.name === 'BTTraveler' && e.node === 'a'],
    ['destination spawn', e => e.kind === 'spawn' && e.name === 'BTTraveler' && e.node === 'b'],
    ['outbound TransferPacket', e => e.kind === 'transfer' && e.node === 'a'],
    ['probe receipt', e => e.kind === 'text' && e.data.message?.includes('-destination-probe:BTTraveler:')],
    ['receiver chat', e => e.kind === 'text' && e.name === 'BTWitnessA' && e.data.message?.includes('-destination-send')],
    ['chat send', e => e.kind === 'sent-chat' && e.data.message?.endsWith('-return-receive')],
    ['source absence', e => e.kind === 'console' && e.command.endsWith('-source-b-absent')],
    ['backend snapshots', e => e.kind === 'backend-snapshot'],
    ['wrong target', e => e.kind === 'transfer' && e.node === 'a', e => ({ ...e, data: { ...e.data, port: 1 } })],
    ['wrong receiver connection', e => e.kind === 'text' && e.name === 'BTWitnessA' && e.data.message?.includes('-destination-send'),
      e => ({ ...e, connectionId: 'other' })],
  ]) {
    await t.test(label, () => {
      let changed = false;
      const rows = events.flatMap(e => {
        if (!predicate(e)) return [e];
        if (label !== 'backend snapshots' && changed) return [e];
        changed = true;
        return replacement ? [replacement(e)] : [];
      });
      assert.ok(changed);
      save(rows);
      assert.throws(() => verify(root));
    });
  }
  save(events);
  await t.test('console response must exist in native log', () => {
    const file = path.join(root, 'b/pnx/pnx.stdout.log');
    const original = fs.readFileSync(file, 'utf8');
    const row = events.find(e => e.kind === 'console' && e.command.endsWith('-source-b-absent'));
    const marker = row.response.slice(row.response.indexOf('BT_STATE:'));
    assert.ok(original.includes(marker));
    fs.writeFileSync(file, original.replace(marker, 'removed'));
    assert.throws(() => verify(root));
    fs.writeFileSync(file, original);
  });
  await t.test('console request and acknowledgement are required', () => {
    const row = events.find(e => e.kind === 'console' && e.command.endsWith('-source-b-absent'));
    fs.unlinkSync(path.join(root, 'console', `${row.requestId}.reply.json`));
    assert.throws(() => verify(root));
  });
});
