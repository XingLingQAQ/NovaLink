const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createRequire } = require('node:module');
const { REQUIRED_IDS, Session, followTransfer, until } = require('./transfer-driver');
const { requestConsole } = require('./console-control');
const { errorText } = require('./error-text');
const { createPnxClient } = require('../bot/pnx-client');

const stringify = value => JSON.stringify(value, (_, item) => typeof item === 'bigint' ? item.toString() : item);

async function run(planFile) {
  const plan = JSON.parse(fs.readFileSync(planFile, 'utf8').replace(/^\uFEFF/, ''));
  const root = path.dirname(planFile);
  const botRequire = createRequire(path.resolve(__dirname, '../bot/package.json'));
  const protocol = botRequire('bedrock-protocol');
  const pinned = botRequire('./package-lock.json').packages['node_modules/bedrock-protocol'].version;
  if (pinned !== '3.61.0' || botRequire('bedrock-protocol/package.json').version !== pinned) {
    throw new Error('Native transfer requires locked bedrock-protocol 3.61.0');
  }
  const sessions = [];
  const received = [];
  const assertions = [];
  const record = row => {
    received.push(row);
    fs.appendFileSync(path.join(root, 'events.jsonl'), `${stringify(row)}\n`);
  };
  const health = () => sessions.forEach(session => session.health());
  const pass = (id, evidence) => {
    if (assertions.some(item => item.id === id)) throw new Error(`Duplicate assertion ${id}`);
    assertions.push({ id, pass: true, evidence });
    console.log(`PASS ${id}`);
  };
  const create = (node, name) => {
    const session = new Session({ node, name, record, timeoutMs: plan.timeoutMs,
      createClient: options => createPnxClient({ ...options, offline: true, version: plan.minecraftVersion,
        connectTimeout: plan.timeoutMs, skipPing: true }, protocol) });
    sessions.push(session);
    return session;
  };
  const now = Math.floor(Date.now() / 1000);
  const head = Buffer.from(stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const body = Buffer.from(stringify({ sub: crypto.randomUUID(), username: 'bedrock-transfer-observer',
    role: 'SUPER_ADMIN', jti: crypto.randomUUID(), iat: now, exp: now + 1800 })).toString('base64url');
  const signature = crypto.createHmac('sha256', process.env.BT_BACKEND_SECRET)
    .update(`${head}.${body}`).digest('base64url');
  const bearer = `${head}.${body}.${signature}`;
  const api = async resource => {
    health();
    const response = await fetch(`${plan.backendUrl}${resource}`, {
      headers: { Authorization: `Bearer ${bearer}` }, signal: AbortSignal.timeout(10000),
    });
    if (!response.ok) throw new Error(`Backend ${resource}: HTTP ${response.status}`);
    const data = await response.json();
    record({ at: new Date().toISOString(), kind: 'backend-snapshot', resource, data });
    return data;
  };
  const consoleCommand = async (node, command) => {
    health();
    const receipt = await requestConsole(plan, node, command, health);
    record({ at: new Date().toISOString(), kind: 'console', node: node.id, command, ...receipt });
    return receipt.response;
  };
  const membership = async (session, id) => {
    const state = await until(async () => {
      const players = (await api('/api/players')).players;
      const matches = players.filter(player => player.name === session.name);
      const members = (await api('/api/channels/global/members')).members;
      const matching = members.filter(member => member.name === session.name || member.uuid === session.uuid);
      return matches.length === 1 && matches[0].uuid === session.uuid
        && matches[0].clientId === session.node.clientId && matches[0].joinedChannels.includes('global')
        && matches[0].activeChannel === 'global' && matching.length === 1 && matching[0].uuid === session.uuid
        && { player: matches[0], member: matching[0], connectionId: session.id };
    }, { timeoutMs: plan.timeoutMs, intervalMs: 500, health, label: `backend membership ${id}` });
    if (id) pass(id, state);
    return state;
  };
  const probe = async (session, id, recordAssertion = true) => {
    const token = `${plan.runId}-${id}`;
    const marker = `BT_PROBE:${session.node.id}:${token}:${session.name}:`;
    const onlineUuid = await until(async () => {
      const state = await consoleCommand(session.node, `btstate ${session.name} ${token}`);
      return /:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\b/i.exec(state)?.[1];
    }, { timeoutMs: plan.timeoutMs, intervalMs: 500, health, label: `online player ${session.name}` });
    const start = session.rows.length;
    await consoleCommand(session.node, `btprobe ${session.name} ${token}`);
    const row = await session.receive(marker, start);
    const uuid = row.data.message.slice(row.data.message.indexOf(marker) + marker.length).trim();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(uuid)) {
      throw new Error(`Probe has no player UUID: ${uuid}`);
    }
    if (session.uuid && session.uuid !== uuid) throw new Error('Player identity changed after native transfer');
    if (uuid !== onlineUuid) throw new Error('Player probe disagrees with the native online directory');
    session.uuid = uuid;
    if (recordAssertion) pass(id, row);
  };
  const absent = async (node, name, id) => {
    const token = `${plan.runId}-${id}`;
    const response = await until(async () => {
      const text = await consoleCommand(node, `btstate ${name} ${token}`);
      return text.includes(`BT_STATE:${node.id}:${token}:${name}:absent`) && text;
    }, { timeoutMs: plan.timeoutMs, intervalMs: 500, health, label: `source player removal ${node.id}` });
    pass(id, { node: node.id, response });
  };
  const deliver = async (sender, receiver, id) => {
    const token = `${plan.runId}-${id}`;
    const mark = receiver.rows.length;
    sender.chat(token);
    const row = await receiver.receive(token, mark);
    health();
    pass(id, { sender: sender.name, receiver: receiver.name, row });
  };
  let error;
  let sequenceComplete = false;
  try {
    let traveler = create(plan.nodes.a, plan.names.traveler);
    const observerA = create(plan.nodes.a, plan.names.observerA);
    const observerB = create(plan.nodes.b, plan.names.observerB);
    await Promise.all([traveler.open(), observerA.open(), observerB.open()]);
    pass('initial-spawn', traveler.spawn);
    await probe(traveler, 'initial-probe');
    await probe(observerA, 'observer-a-probe', false);
    await probe(observerB, 'observer-b-probe', false);
    if (new Set([traveler.uuid, observerA.uuid, observerB.uuid]).size !== 3) {
      throw new Error('Native transfer players must have distinct server identities');
    }
    for (const session of [traveler, observerA, observerB]) {
      session.command('/nc join global');
      await membership(session, session === traveler ? 'initial-membership' : null);
    }
    pass('backend-clients', { a: await membership(observerA), b: await membership(observerB) });
    await deliver(traveler, observerB, 'initial-send');
    await deliver(observerB, traveler, 'initial-receive');
    for (const stage of [
      { node: plan.nodes.b, prefix: 'destination', transferId: 'outbound-transfer', absentId: 'source-a-absent', observer: observerA },
      { node: plan.nodes.a, prefix: 'return', transferId: 'return-transfer', absentId: 'source-b-absent', observer: observerB },
    ]) {
      const previous = traveler;
      const moved = await followTransfer(previous, stage.node,
        () => consoleCommand(previous.node, `bttransfer ${previous.name} ${plan.runId}-${stage.transferId}`), create);
      traveler = moved.next;
      traveler.uuid = previous.uuid;
      pass(stage.transferId, moved.transfer);
      pass(`${stage.prefix}-spawn`, { ...traveler.spawn, startGame: traveler.game,
        previousConnectionId: previous.id });
      await probe(traveler, `${stage.prefix}-probe`);
      await absent(previous.node, previous.name, stage.absentId);
      await membership(traveler, `${stage.prefix}-membership`);
      await deliver(traveler, stage.observer, `${stage.prefix}-send`);
      await deliver(stage.observer, traveler, `${stage.prefix}-receive`);
    }
    health();
    sequenceComplete = true;
  } catch (failure) {
    error = errorText(failure);
    console.error(error);
  } finally {
    for (const session of sessions) {
      try { session.close(); } catch (failure) { error ||= errorText(failure); }
    }
    const result = { runId: plan.runId, platform: 'pnx-native-transfer', sequenceComplete,
      requiredIds: REQUIRED_IDS, assertions, received, error,
      connections: sessions.map(session => ({ id: session.id, node: session.node.id, name: session.name,
        uuid: session.uuid, spawned: !!session.spawn, startGame: !!session.game })) };
    fs.writeFileSync(path.join(root, 'bot.results.json'), stringify(result));
  }
  return sequenceComplete && !error ? 0 : 1;
}

if (require.main === module) {
  run(path.resolve(process.argv[2])).then(code => process.exit(code)).catch(error => {
    console.error(errorText(error));
    process.exit(1);
  });
}
