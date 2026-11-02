const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { REQUIRED_IDS } = require('./transfer-driver');
const { stripConsole } = require('./console-control');
const { errorText } = require('./error-text');

function verify(root) {
  const read = name => JSON.parse(fs.readFileSync(path.join(root, name), 'utf8').replace(/^\uFEFF/, ''));
  const plan = read('plan.json');
  const result = read('bot.results.json');
  assert.equal(plan.kind, 'pnx-native-transfer');
  assert.equal(plan.serverVersion, '3.0.6');
  assert.equal(plan.minecraftVersion, '1.26.60');
  assert.equal(plan.controlTransport, 'held-stdin');
  assert.equal(result.runId, plan.runId);
  assert.equal(result.sequenceComplete, true);
  assert.ok(!result.error, result.error);
  assert.deepEqual(result.requiredIds, REQUIRED_IDS);
  const serverHash = crypto.createHash('sha256').update(fs.readFileSync(plan.serverJar)).digest('hex');
  assert.equal(serverHash.toLowerCase(), plan.serverSha256.toLowerCase());
  assert.notEqual(plan.nodes.a.clientId, plan.nodes.b.clientId);
  assert.equal(new Set(Object.values(plan.nodes).flatMap(node => [node.port, node.rconPort])).size, 4);
  const assertions = [...result.assertions];
  for (const node of Object.values(plan.nodes)) {
    const logFile = path.join(root, node.id, 'pnx/pnx.stdout.log');
    const log = stripConsole(fs.readFileSync(logFile, 'utf8'));
    const version = log.split(/\r?\n/).find(line => /This server is running PowerNukkitX version 3\.0\.6\b/.test(line));
    assert.ok(version, `Missing native PNX 3.0.6 banner on ${node.id}`);
    assert.match(log, /NovaChat-PNX enabled successfully!/);
    assert.match(log, /Enabling BedrockTransferFixture v1\.0\b/);
    assert.match(log, /Done \([0-9.]+s\)! For help, type "help" or "\?"/);
    assert.ok(log.includes(`NetherNet signalling listening on tcp/${node.port}`));
    assert.equal(node.rconEnabled, false);
    assertions.push({ id: `native-pnx-${node.id}`, pass: true,
      evidence: { logFile, version, serverHash, clientId: node.clientId } });
  }
  assert.equal(assertions.length, REQUIRED_IDS.length);
  for (const id of REQUIRED_IDS) {
    const matches = assertions.filter(item => item.id === id);
    assert.equal(matches.length, 1, `Missing or duplicate ID ${id}`);
    assert.equal(matches[0].pass, true, id);
  }
  const byId = Object.fromEntries(assertions.map(item => [item.id, item.evidence]));
  const events = fs.readFileSync(path.join(root, 'events.jsonl'), 'utf8').trim().split('\n').map(line => JSON.parse(line));
  assert.deepEqual(result.received, events);
  assert.ok(!events.some(row => ['error', 'kick'].includes(row.kind)), 'Client error or kick was recorded');
  const initialConnections = result.connections.slice(0, 3);
  assert.equal(new Set(initialConnections.map(connection => connection.name)).size, 3);
  assert.equal(new Set(initialConnections.map(connection => connection.uuid)).size, 3);
  const travelerConnections = result.connections.filter(connection => connection.name === plan.names.traveler);
  assert.deepEqual(travelerConnections.map(connection => connection.node), ['a', 'b', 'a']);
  assert.equal(new Set(travelerConnections.map(connection => connection.id)).size, 3);
  assert.equal(new Set(travelerConnections.map(connection => connection.uuid)).size, 1);
  assert.ok(travelerConnections.every(connection => connection.spawned && connection.startGame));
  const transfers = events.filter(row => row.kind === 'transfer');
  assert.equal(transfers.length, 2);
  for (const [index, prefix, transferId, destination] of [
    [1, 'destination', 'outbound-transfer', plan.nodes.b],
    [2, 'return', 'return-transfer', plan.nodes.a],
  ]) {
    const source = travelerConnections[index - 1];
    const next = travelerConnections[index];
    const packet = byId[transferId];
    assert.deepEqual(packet, transfers[index - 1]);
    assert.equal(packet.connectionId, source.id);
    assert.equal(packet.data.server_address, destination.host);
    assert.equal(packet.data.port, destination.port);
    const connected = events.find(row => row.kind === 'connect' && row.connectionId === next.id);
    assert.ok(events.indexOf(connected) > events.findIndex(row => row.kind === 'transfer' && row.connectionId === source.id));
    assert.equal(byId[`${prefix}-spawn`].connectionId, next.id);
    assert.equal(byId[`${prefix}-spawn`].kind, 'spawn');
    assert.equal(byId[`${prefix}-spawn`].startGame.connectionId, next.id);
    assert.equal(byId[`${prefix}-spawn`].startGame.kind, 'start_game');
    assert.equal(byId[`${prefix}-spawn`].previousConnectionId, source.id);
  }
  for (const [prefix, node, connection] of [
    ['initial', plan.nodes.a, travelerConnections[0]],
    ['destination', plan.nodes.b, travelerConnections[1]],
    ['return', plan.nodes.a, travelerConnections[2]],
  ]) {
    const probe = byId[`${prefix}-probe`];
    assert.equal(probe.kind, 'text');
    assert.equal(probe.connectionId, connection.id);
    assert.ok(probe.data.message.includes(`BT_PROBE:${node.id}:${plan.runId}-${prefix}-probe:${plan.names.traveler}:${connection.uuid}`));
    const state = byId[`${prefix}-membership`];
    assert.equal(state.connectionId, connection.id);
    assert.equal(state.player.uuid, connection.uuid);
    assert.equal(state.player.clientId, node.clientId);
    assert.ok(state.player.joinedChannels.includes('global'));
    assert.equal(state.player.activeChannel, 'global');
    assert.equal(state.member.uuid, connection.uuid);
    for (const direction of ['send', 'receive']) {
      const id = `${prefix}-${direction}`;
      const evidence = byId[id];
      assert.notEqual(evidence.sender, evidence.receiver);
      assert.equal(evidence.row.kind, 'text');
      assert.equal(evidence.row.name, evidence.receiver);
      assert.ok(evidence.row.data.message.includes(`${plan.runId}-${id}`));
      assert.equal(evidence.row.name === plan.names.traveler, direction === 'receive');
    }
  }
  for (const node of ['a', 'b']) {
    const id = `source-${node}-absent`;
    assert.ok(byId[id].response.includes(`BT_STATE:${node}:${plan.runId}-${id}:${plan.names.traveler}:absent`));
  }
  const summary = { runId: plan.runId, kind: plan.kind, pass: true, requiredIds: REQUIRED_IDS,
    assertions, connections: result.connections };
  fs.writeFileSync(path.join(root, 'summary.json'), JSON.stringify(summary, null, 2));
  console.log(`PASS ${REQUIRED_IDS.length}/${REQUIRED_IDS.length} native Bedrock transfer IDs (${plan.runId})`);
}

if (require.main === module) {
  try { verify(path.resolve(process.argv[2])); } catch (error) {
    console.error(errorText(error));
    process.exitCode = 1;
  }
}

module.exports = { verify };
