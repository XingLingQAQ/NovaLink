const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { REQUIRED_IDS, plain } = require('./transfer-driver');
const { stripConsole } = require('./console-control');
const { errorText } = require('./error-text');

const PNX_SHA256 = '10822d818c1f2c7d423092a235f3f04bd0eb915b2298b204ed1697d7a0dd9539';

function verify(root, serverJar) {
  const read = name => JSON.parse(fs.readFileSync(path.join(root, name), 'utf8').replace(/^\uFEFF/, ''));
  const plan = read('plan.json');
  const result = read('bot.results.json');
  for (const value of [plan, result]) {
    if (Object.hasOwn(value, 'synthetic')) assert.equal(value.synthetic, false, 'Synthetic or invalid marker cannot satisfy native transfer acceptance');
  }
  assert.equal(plan.kind, 'pnx-native-transfer');
  assert.equal(plan.serverVersion, '3.0.6');
  assert.equal(plan.minecraftVersion, '1.26.60');
  assert.equal(plan.controlTransport, 'held-stdin');
  assert.match(plan.runId, /^[0-9a-f]{32}$/);
  assert.equal(plan.serverSha256.toLowerCase(), PNX_SHA256);
  if (serverJar) assert.equal(crypto.createHash('sha256').update(fs.readFileSync(serverJar)).digest('hex'), PNX_SHA256);
  assert.equal(result.runId, plan.runId);
  assert.equal(result.sequenceComplete, true);
  assert.ok(!result.error, result.error);
  assert.deepEqual(result.requiredIds, REQUIRED_IDS);
  assert.notEqual(plan.nodes.a.clientId, plan.nodes.b.clientId);
  assert.equal(new Set(Object.values(plan.nodes).flatMap(node => [node.port, node.rconPort])).size, 4);
  const events = fs.readFileSync(path.join(root, 'events.jsonl'), 'utf8').trim().split('\n').map(line => JSON.parse(line));
  assert.deepEqual(result.received, events);
  assert.ok(!events.some(row => ['error', 'kick'].includes(row.kind)), 'Client error or kick was recorded');
  const claims = new Map();
  const pass = (id, evidence) => { assert.ok(!claims.has(id), `Duplicate ${id}`); claims.set(id, evidence); };
  const find = (label, predicate, from = 0, to = events.length) => {
    const index = events.findIndex((row, i) => i >= from && i < to && predicate(row));
    assert.ok(index >= 0, `Missing ${label}`);
    return index;
  };
  const logs = {};
  for (const node of Object.values(plan.nodes)) {
    const launch = read(`${node.id}/launch-spec.json`);
    assert.equal(launch.platform, 'pnx');
    assert.equal(launch.serverSha256.toLowerCase(), PNX_SHA256);
    assert.equal(launch.arguments[launch.arguments.indexOf('-jar') + 1], launch.serverJar);
    const log = logs[node.id] = stripConsole(fs.readFileSync(path.join(root, node.id, 'pnx/pnx.stdout.log'), 'utf8'));
    const version = log.split(/\r?\n/).find(line => /This server is running PowerNukkitX version 3\.0\.6\b/.test(line));
    assert.ok(version, `Missing native PNX banner on ${node.id}`);
    assert.match(log, /NovaChat-PNX enabled successfully!/);
    assert.match(log, /Enabling BedrockTransferFixture v1\.0\b/);
    assert.match(log, /Done \([0-9.]+s\)! For help, type "help" or "\?"/);
    assert.ok(log.includes(`NetherNet signalling listening on tcp/${node.port}`));
    assert.equal(node.rconEnabled, false);
    pass(`native-pnx-${node.id}`, { version, serverSha256: PNX_SHA256, clientId: node.clientId });
  }
  const connectRows = events.filter(row => row.kind === 'connect');
  assert.equal(connectRows.length, 5);
  assert.equal(new Set(connectRows.map(row => row.connectionId)).size, 5);
  const traveler = connectRows.filter(row => row.name === plan.names.traveler);
  assert.deepEqual(traveler.map(row => row.node), ['a', 'b', 'a']);
  const witnessA = connectRows.find(row => row.name === plan.names.observerA && row.node === 'a');
  const witnessB = connectRows.find(row => row.name === plan.names.observerB && row.node === 'b');
  assert.ok(witnessA && witnessB);
  assert.equal(new Set(Object.values(plan.names)).size, 3);
  for (const row of connectRows) {
    const node = plan.nodes[row.node];
    assert.ok(node);
    assert.deepEqual(row.data, { host: node.host, port: node.port });
    assert.ok(events.filter(e => e.connectionId === row.connectionId).every(e => e.node === row.node && e.name === row.name));
  }
  const eventFor = (connection, kind, from, to) => find(`${kind} ${connection.name}/${connection.node}`,
    e => e.connectionId === connection.connectionId && e.kind === kind, from, to);
  const startup = (connection, from, to) => {
    const c = eventFor(connection, 'connect', from, to);
    const g = eventFor(connection, 'start_game', c + 1, to);
    const s = eventFor(connection, 'spawn', g + 1, to);
    return { c, g, s };
  };
  const transferRows = events.map((e, i) => ({ e, i })).filter(({ e }) => e.kind === 'transfer');
  assert.equal(transferRows.length, 2);
  const bounds = [0, transferRows[0].i + 1, transferRows[1].i + 1, events.length];
  const starts = traveler.map((c, i) => startup(c, bounds[i], bounds[i + 1]));
  startup(witnessA, 0, bounds[1]);
  startup(witnessB, 0, bounds[1]);
  pass('initial-spawn', { spawn: events[starts[0].s], startGame: events[starts[0].g] });
  const consoleRow = (node, action, name, id, tag, suffix, from, to) => {
    const token = `${plan.runId}-${id}`;
    const command = `${action} ${name} ${token}`;
    const marker = `${tag}:${node}:${token}:${name}:${suffix}`;
    const i = find(command, e => {
      if (e.kind !== 'console' || e.node !== node || e.command !== command || typeof e.response !== 'string') return false;
      const start = e.response.indexOf(marker);
      return start >= 0 && !/[A-Za-z0-9_-]/.test(e.response[start + marker.length] || '');
    }, from, to);
    const row = events[i];
    assert.match(row.requestId, /^[0-9a-f-]{36}$/);
    assert.deepEqual(read(`console/${row.requestId}.request.json`), { id: row.requestId, runId: plan.runId, node, command });
    const reply = read(`console/${row.requestId}.reply.json`);
    assert.equal(reply.id, row.requestId); assert.equal(reply.runId, plan.runId);
    assert.equal(reply.node, node); assert.equal(reply.submitted, true);
    assert.ok(logs[node].split(/\r?\n/).includes(row.response), `Missing native console log: ${command}`);
    return i;
  };
  const probe = (connection, id, from, to) => {
    const prefix = `BT_PROBE:${connection.node}:${plan.runId}-${id}:${connection.name}:`;
    const i = find(id, e => e.kind === 'text' && e.connectionId === connection.connectionId &&
      typeof e.data.message === 'string' && plain(e.data.message).startsWith(prefix), from, to);
    const uuid = plain(events[i].data.message).slice(prefix.length).trim();
    assert.match(uuid, /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i);
    const state = consoleRow(connection.node, 'btstate', connection.name, id, 'BT_STATE', uuid, from, to);
    const issued = consoleRow(connection.node, 'btprobe', connection.name, id, 'BT_PROBE_ISSUED', '', state + 1, to);
    assert.ok(state < i && state < issued);
    return { uuid, i, issued };
  };
  const p = traveler.map((c, i) => probe(c, `${['initial', 'destination', 'return'][i]}-probe`, starts[i].s + 1, bounds[i + 1]));
  assert.equal(new Set(p.map(x => x.uuid)).size, 1);
  const wa = probe(witnessA, 'observer-a-probe', starts[0].s + 1, bounds[1]);
  const wb = probe(witnessB, 'observer-b-probe', starts[0].s + 1, bounds[1]);
  assert.equal(new Set([p[0].uuid, wa.uuid, wb.uuid]).size, 3);
  const membership = (connection, uuid, from, to) => {
    for (let i = from; i < to - 1; i++) {
      const a = events[i], b = events[i + 1];
      if (a.kind !== 'backend-snapshot' || a.resource !== '/api/players' || b.kind !== 'backend-snapshot' || b.resource !== '/api/channels/global/members') continue;
      const players = a.data.players.filter(x => x.name === connection.name);
      const members = b.data.members.filter(x => x.name === connection.name || x.uuid === uuid);
      if (players.length === 1 && players[0].uuid === uuid && players[0].clientId === plan.nodes[connection.node].clientId &&
          players[0].joinedChannels.includes('global') && players[0].activeChannel === 'global' && members.length === 1 && members[0].uuid === uuid) {
        return { player: players[0], member: members[0], connectionId: connection.connectionId, index: i };
      }
    }
    assert.fail(`Missing backend membership ${connection.name}/${connection.node}`);
  };
  const backendA = membership(witnessA, wa.uuid, wa.i + 1, bounds[1]);
  const backendB = membership(witnessB, wb.uuid, wb.i + 1, bounds[1]);
  pass('backend-clients', { a: backendA, b: backendB });
  for (let i = 0; i < 3; i++) {
    const prefix = ['initial', 'destination', 'return'][i];
    const c = traveler[i], witness = i === 1 ? witnessA : witnessB;
    pass(`${prefix}-probe`, events[p[i].i]);
    let after = Math.max(p[i].i, p[i].issued) + 1;
    if (i) {
      const previous = traveler[i - 1];
      const tid = i === 1 ? 'outbound-transfer' : 'return-transfer';
      const packet = transferRows[i - 1];
      assert.equal(packet.e.connectionId, previous.connectionId);
      assert.equal(packet.e.data.server_address, plan.nodes[c.node].host);
      assert.equal(packet.e.data.port, plan.nodes[c.node].port);
      const issued = consoleRow(previous.node, 'bttransfer', previous.name, tid, 'BT_TRANSFER_CALLED',
        `/${plan.nodes[c.node].host}:${plan.nodes[c.node].port}`, starts[i - 1].s + 1, starts[i].c);
      assert.ok(issued < starts[i].c && packet.i < starts[i].c);
      pass(tid, packet.e);
      pass(`${prefix}-spawn`, { spawn: events[starts[i].s], startGame: events[starts[i].g], previousConnectionId: previous.connectionId });
      const id = `source-${previous.node}-absent`;
      const absent = consoleRow(previous.node, 'btstate', previous.name, id, 'BT_STATE', 'absent', after, bounds[i + 1]);
      pass(id, events[absent]); after = absent + 1;
    }
    const m = membership(c, p[i].uuid, after, bounds[i + 1]);
    pass(`${prefix}-membership`, m);
    after = m.index + 2;
    for (const direction of ['send', 'receive']) {
      const id = `${prefix}-${direction}`;
      const sender = direction === 'send' ? c : witness;
      const receiver = direction === 'send' ? witness : c;
      const token = `${plan.runId}-${id}`;
      const sent = find(id, e => e.kind === 'sent-chat' && e.connectionId === sender.connectionId && e.data.message === token, after, bounds[i + 1]);
      const received = find(id, e => e.kind === 'text' && e.connectionId === receiver.connectionId &&
        plain(e.data.message).includes(`${sender.name}: ${token}`), sent + 1, bounds[i + 1]);
      pass(id, { sender: sender.name, receiver: receiver.name, sent: events[sent], row: events[received] });
      after = received + 1;
    }
  }
  assert.deepEqual(new Set(claims.keys()), new Set(REQUIRED_IDS));
  const assertions = REQUIRED_IDS.map(id => ({ id, pass: true, evidence: claims.get(id) }));
  const provenance = plan.buildSource || { kind: 'historical-unrecorded' };
  if (provenance.kind === 'isolated-local-build') {
    assert.match(provenance.revision, /^[0-9a-f]{40}$/);
  } else if (provenance.kind === 'externally-supplied') {
    assert.ok(typeof provenance.description === 'string' && provenance.description.trim());
  } else {
    assert.equal(provenance.kind, 'historical-unrecorded');
  }
  const summary = { runId: plan.runId, kind: plan.kind, pass: true, requiredIds: REQUIRED_IDS, assertions,
    provenance, runtimePin: PNX_SHA256, serverJarVerified: !!serverJar };
  fs.writeFileSync(path.join(root, 'summary.json'), JSON.stringify(summary, null, 2));
  console.log(`PASS ${REQUIRED_IDS.length}/${REQUIRED_IDS.length} native Bedrock transfer IDs (${plan.runId}); build: ${provenance.kind}; server jar verified: ${!!serverJar}`);
}

if (require.main === module) {
  try { verify(path.resolve(process.argv[2]), process.argv[3]); } catch (error) {
    console.error(errorText(error));
    process.exitCode = 1;
  }
}
module.exports = { verify };
