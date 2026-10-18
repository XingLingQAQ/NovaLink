// ============================================================================
// joint-bot.js -- reusable cross-server E2E bot for the NovaChat joint harness.
//
// ONE script serves every role in a joint run. The orchestrator (run-joint.ps1)
// generates a per-role plan JSON and passes its path via --plan; this script
// executes the plan and writes a result JSON the orchestrator joins offline.
//
// Why a plan file instead of hardcoded sequences:
//   The joint harness must assert that a message sent by a player on server 1
//   is *received* by a player on server 2. A single bot cannot observe that on
//   its own -- the evidence lives in two processes. The plan makes each bot
//   record raw inbound traffic plus its own sends in one results file; the
//   orchestrator then proves delivery by finding the sender's correlation token
//   in the receiver's recorded traffic. Nothing is asserted from the bot's own
//   opinion alone.
//
// Kinds:
//   java    -> mineflayer (offline auth)
//   bedrock -> bedrock-protocol (offline auth; PMMP login shim below)
//
// Env / plan fields (all also accepted as plan JSON keys):
//   kind, username, host, port, mcVersion, resultsFile, handoffDir,
//   timeoutMs, steps[]
//
// Step actions (each step is awaited in order):
//   chat          { text }                          send a chat line
//   cmd           { text }                          send a slash command
//   wait          { ms }                            sleep
//   expect        { id, token, timeoutMs, sender }   assert token arrives
//   expectAbsent  { id, token }                     assert token NEVER arrives (checked at exit)
//   handoffSet    { key, pattern, fromStep }        publish a value (e.g. NC-XXXX) for other roles
//   handoffGet    { key, timeoutMs }                wait for a value published by another role
//   quit          { reason }                        close the connection cleanly
//
// Template substitution: any text may contain {{key}}; keys come from handoffGet
// and from the read-only builtins {{runId}}, {{username}}, {{role}}.
// ============================================================================
'use strict';

const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------------------
// Module resolution
//
// This script lives in test/joint/bots/ while both bot libraries are installed
// once, for the whole harness, in test/bot/node_modules (that is the directory
// test/bot/package-lock.json pins). Node resolves a bare `require('mineflayer')`
// relative to THIS file's directory, so without help it throws
// MODULE_NOT_FOUND before a socket is ever opened.
//
// Resolution order:
//   1. JOINT_BOT_MODULES  -- explicit override (used by the orchestrator, and
//                            by anyone who keeps the deps elsewhere)
//   2. test/bot/node_modules relative to this file
//   3. the normal Node lookup (a checkout that happens to vendor them locally)
// ---------------------------------------------------------------------------
function requireBotLib(name) {
  const candidates = []
  if (process.env.JOINT_BOT_MODULES) {
    candidates.push(path.join(process.env.JOINT_BOT_MODULES, name))
  }
  candidates.push(path.resolve(__dirname, '..', '..', 'bot', 'node_modules', name))
  candidates.push(name)
  let lastError = null
  for (const candidate of candidates) {
    try {
      return require(candidate)
    } catch (e) {
      lastError = e
      // Only a MODULE_NOT_FOUND for THIS candidate should fall through; a
      // library that loads but throws internally must surface immediately.
      if (e && e.code !== 'MODULE_NOT_FOUND') { throw e }
    }
  }
  throw lastError || new Error(`cannot resolve bot library '${name}'`)
}

// ---------------------------------------------------------------------------
// Plan loading
// ---------------------------------------------------------------------------
function loadPlan() {
  const idx = process.argv.indexOf('--plan');
  if (idx < 0 || !process.argv[idx + 1]) {
    console.error('usage: node joint-bot.js --plan <plan.json>');
    process.exit(64);
  }
  const planPath = path.resolve(process.argv[idx + 1]);
  const raw = fs.readFileSync(planPath, 'utf8');
  const plan = JSON.parse(raw);
  plan._planPath = planPath;
  return plan;
}

const plan = loadPlan();
const KIND = (plan.kind || 'java').toLowerCase();
const USERNAME = plan.username;
const HOST = plan.host || '127.0.0.1';
const PORT = parseInt(plan.port, 10);
const MC_VERSION = plan.mcVersion || (KIND === 'bedrock' ? '1.26.30' : '1.21.11');
const RESULTS_FILE = path.resolve(plan.resultsFile);
const HANDOFF_DIR = path.resolve(plan.handoffDir || path.join(path.dirname(RESULTS_FILE), 'handoff'));
const TIMEOUT_MS = parseInt(plan.timeoutMs || '240000', 10);
const ROLE = plan.role || 'X';

if (!USERNAME || !PORT) {
  console.error('plan must set username and port');
  process.exit(64);
}
fs.mkdirSync(path.dirname(RESULTS_FILE), { recursive: true });
fs.mkdirSync(HANDOFF_DIR, { recursive: true });

// ---------------------------------------------------------------------------
// Recording
// ---------------------------------------------------------------------------
const received = [];
const sent = [];
const assertions = [];
const handoff = {};

function record(kind, raw, text) {
  const entry = {
    t: new Date().toISOString(),
    atMs: Date.now(),
    kind,
    raw: String(raw == null ? '' : raw).slice(0, 600),
    text: String(text == null ? '' : text).slice(0, 600),
  };
  received.push(entry);
  console.log(`[RECV ${entry.t}] ${kind}: ${entry.text}`);
  return entry;
}

function plainText(jsonMsg) {
  if (jsonMsg == null) return '';
  if (typeof jsonMsg === 'string') return jsonMsg;
  if (jsonMsg.text != null) return String(jsonMsg.text);
  if (jsonMsg.extra && Array.isArray(jsonMsg.extra)) return jsonMsg.extra.map(plainText).join('');
  if (jsonMsg.translate) return (jsonMsg.with || []).map(plainText).join('') || jsonMsg.translate;
  if (jsonMsg[''] != null) return plainText(jsonMsg['']);
  return JSON.stringify(jsonMsg);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Date.now();

// ---------------------------------------------------------------------------
// Template substitution + handoff rendezvous
// ---------------------------------------------------------------------------
function render(text) {
  if (typeof text !== 'string') return text;
  return text.replace(/\{\{([A-Za-z0-9_]+)\}\}/g, (m, key) => {
    if (key === 'runId') return plan.runId || '';
    if (key === 'username') return USERNAME;
    if (key === 'role') return ROLE;
    if (Object.prototype.hasOwnProperty.call(handoff, key)) return handoff[key];
    throw new Error(`template {{${key}}} has no value (handoff keys: ${Object.keys(handoff).join(', ')})`);
  });
}

function handoffPath(key) {
  // Keyed by role so two roles can never clobber each other's values, and by
  // runId so a stale file from a previous round is never mistaken for this one.
  return path.join(HANDOFF_DIR, `${plan.runId || 'run'}-${ROLE}-${key}.json`);
}

function publishHandoff(key, value) {
  const payload = { key, value, role: ROLE, runId: plan.runId || '', t: new Date().toISOString() };
  fs.writeFileSync(handoffPath(key), JSON.stringify(payload, null, 2), 'utf8');
  handoff[key] = value;
  console.log(`[HANDOFF SET] ${key} = ${value}`);
}

async function awaitHandoff(key, timeoutMs) {
  const candidates = [];
  // The publisher is not necessarily a known role: scan the directory for any
  // <runId>-*-<key>.json. Scanning (rather than assuming a peer name) is what
  // lets the same primitive serve any role pairing without a config matrix.
  const deadline = now() + (timeoutMs || 30000);
  while (now() < deadline) {
    let files = [];
    try {
      files = fs.readdirSync(HANDOFF_DIR).filter((f) => f.startsWith(`${plan.runId || 'run'}-`) && f.endsWith(`-${key}.json`));
    } catch (e) {
      files = [];
    }
    for (const f of files) {
      try {
        const payload = JSON.parse(fs.readFileSync(path.join(HANDOFF_DIR, f), 'utf8'));
        if (payload && payload.value != null) {
          if (!candidates.includes(f)) candidates.push(f);
          handoff[key] = payload.value;
          console.log(`[HANDOFF GET] ${key} = ${payload.value} (from ${f})`);
          return payload.value;
        }
      } catch (e) {
        // A peer may still be mid-write; retry on the next poll.
      }
    }
    await sleep(500);
  }
  throw new Error(`handoff '${key}' not published within ${timeoutMs || 30000}ms`);
}

// ---------------------------------------------------------------------------
// Expect machinery: every inbound line is matched against pending expectations
// ---------------------------------------------------------------------------
const pending = [];   // { id, token, resolve, timer, matchedAt }
const seenTokens = new Map(); // token -> first matching entry

function noteInbound(text) {
  for (const token of expectedTokens) {
    if (!seenTokens.has(token) && text.includes(token)) {
      seenTokens.set(token, { t: new Date().toISOString(), atMs: now(), text });
    }
  }
  for (const p of pending.slice()) {
    if (text.includes(p.token)) {
      p.matchedAt = now();
      clearTimeout(p.timer);
      const i = pending.indexOf(p);
      if (i >= 0) pending.splice(i, 1);
      p.resolve(true);
    }
  }
}

const expectedTokens = [];

function expectToken(id, token, timeoutMs) {
  if (seenTokens.has(token)) {
    assertions.push({ id, token, pass: true, waitedMs: 0, evidence: seenTokens.get(token).text });
    return Promise.resolve(true);
  }
  expectedTokens.push(token);
  return new Promise((resolve) => {
    const started = now();
    const p = {
      id,
      token,
      resolve: (ok) => {
        const waited = now() - started;
        const evidence = seenTokens.get(token);
        assertions.push({
          id,
          token,
          pass: ok,
          waitedMs: waited,
          evidence: evidence ? evidence.text : '',
        });
        if (ok) console.log(`[EXPECT PASS] ${id} token=${token} after ${waited}ms`);
        else console.log(`[EXPECT FAIL] ${id} token=${token} not seen in ${timeoutMs}ms`);
        resolve(ok);
      },
    };
    p.timer = setTimeout(() => {
      const i = pending.indexOf(p);
      if (i >= 0) pending.splice(i, 1);
      p.resolve(false);
    }, timeoutMs || 30000);
    pending.push(p);
  });
}

// ---------------------------------------------------------------------------
// Transport: Java (mineflayer)
// ---------------------------------------------------------------------------
function createJavaTransport(onChatLine) {
  const mineflayer = requireBotLib('mineflayer');
  const bot = mineflayer.createBot({
    host: HOST,
    port: PORT,
    username: USERNAME,
    auth: 'offline',
    version: MC_VERSION,
    hideErrors: false,
  });

  bot.on('messagestr', (msg) => { record('messagestr', msg, msg); onChatLine(msg); });
  bot.on('chat', (username, message) => {
    const line = `<${username}> ${message}`;
    record('chat', line, line);
    onChatLine(message);
  });
  bot.on('systemChat', (msg) => {
    const txt = plainText(msg);
    record('systemChat', txt, txt);
    onChatLine(txt);
  });
  bot.on('kicked', (reason) => {
    const txt = plainText(reason);
    record('kicked', txt, txt);
    console.log('[KICKED]', txt);
  });
  bot.on('error', (err) => record('error', err && err.message, err && err.stack));
  bot.on('end', () => record('end', 'connection ended', 'connection ended'));

  return {
    spawnEvent: 'spawn',
    bot,
    sendChat: (text) => { sent.push({ atMs: now(), text }); console.log(`\n>>> ${text}`); bot.chat(text); },
    sendCommand: (text) => {
      const cmd = text.startsWith('/') ? text : `/${text}`;
      sent.push({ atMs: now(), text: cmd });
      console.log(`\n>>> ${cmd}`);
      bot.chat(cmd);
    },
    close: () => { try { bot.quit('joint done'); } catch (e) { /* already gone */ } },
  };
}

// ---------------------------------------------------------------------------
// Transport: Bedrock (bedrock-protocol)
//
// The login shims below are carried over verbatim-in-behaviour from
// test/bot/run-e2e-bedrock.js (that file owns the canonical copy for the
// per-platform harness; joint owns its own so the two harnesses can evolve
// independently without one breaking the other):
//   * PMMP 5.44+ SelfSignedJwtBody requires {cpk, leguuid, xname, mid} and
//     rejects the `identity` field, and validates the `aud` claim.
//   * PMMP 5.44+ ClientData requires a set of fields 3.59.0 does not send.
//   * The minecraft-data default skin is a 256x256 persona skin; PMMP only
//     accepts legacy sizes {8192,16384,65536}. We synthesise a 64x64 one.
// BDS/Nukkit/PNX/Endstone accept the same payloads, so the shims are always on.
// ---------------------------------------------------------------------------
function createBedrockTransport(onChatLine) {
  const bedrock = requireBotLib('bedrock-protocol');
  const crypto = require('crypto');
  const JWT = requireBotLib('jsonwebtoken');

  const botUuid = crypto.createHash('sha1').update(USERNAME).digest('hex');
  const leguuid = `${botUuid.slice(0, 8)}-${botUuid.slice(8, 12)}-${botUuid.slice(12, 16)}-${botUuid.slice(16, 20)}-${botUuid.slice(20, 32)}`;
  const mid = crypto.createHash('md5').update(USERNAME).digest('hex').slice(0, 16);

  function makeLegacySkinFields() {
    const W = 64, H = 64;
    const pixel = Buffer.from([0xb3, 0x7b, 0x62, 0xff]);
    const skinPixels = Buffer.allocUnsafe(W * H * 4);
    for (let i = 0; i < W * H; i++) pixel.copy(skinPixels, i * 4);
    const patch = JSON.stringify({ geometry: { default: 'geometry.humanoid.custom' } });
    return {
      SkinId: 'joint-bot-skin',
      SkinData: skinPixels.toString('base64'),
      SkinImageWidth: W,
      SkinImageHeight: H,
      SkinResourcePatch: Buffer.from(patch, 'utf8').toString('base64'),
      SkinGeometryData: '', SkinAnimationData: '', CapeData: '',
      CapeImageWidth: 0, CapeImageHeight: 0, CapeOnClassicSkin: false, CapeId: '',
      ArmSize: 'wide', SkinColor: '#ffb37b62',
      PersonaPieces: [], AnimatedImageData: [],
      PersonaSkin: false, PremiumSkin: false, SkinGeometryDataEngineVersion: '',
      // ClientData fields PMMP 5.44+ marks @required.
      ClientEditorConnectionIntent: 0, ClientIsEditorCapable: false,
      PartyId: '', IsPartyLeader: false, FilterProfanity: false, PieceTintColors: [],
    };
  }

  const client = bedrock.createClient({
    host: HOST,
    port: PORT,
    username: USERNAME,
    offline: true,
    version: MC_VERSION,
    skipPing: false,
    conLog: console.log,
    skinData: makeLegacySkinFields(),
  });

  if (typeof client.sendLogin === 'function') {
    const origSendLogin = client.sendLogin.bind(client);
    client.sendLogin = (...args) => {
      try {
        if (client.ecdhKeyPair && client.ecdhKeyPair.privateKey) {
          const token = JWT.sign(
            { cpk: client.clientX509, leguuid, xname: USERNAME, mid, xid: '0' },
            client.ecdhKeyPair.privateKey,
            {
              algorithm: 'ES384', notBefore: 0, issuer: 'self', expiresIn: 3600,
              audience: 'api://auth-minecraft-services/multiplayer',
              header: { x5u: client.clientX509, typ: undefined },
            });
          client.clientIdentityChain = token;
          client.multiplayerToken = token;
        }
      } catch (e) {
        console.log('[LOGIN_PATCH warn]', e && e.message);
      }
      return origSendLogin(...args);
    };
  }

  client.on('text', (packet) => {
    const source = packet.source_name || '';
    const msg = packet.message || '';
    const type = packet.type || 'unknown';
    const line = source ? `<${source}> ${msg}` : msg;
    record(`text:${type}`, line, msg);
    onChatLine(msg);
    if (source) onChatLine(line);
  });
  client.on('kick', (reason) => {
    const txt = (reason && reason.message) || JSON.stringify(reason);
    record('kicked', txt, txt);
  });
  client.on('error', (err) => record('error', err && err.message, err && err.stack));
  client.on('close', () => record('close', 'connection closed', 'connection closed'));

  return {
    spawnEvent: 'spawn',
    bot: client,
    sendChat: (text) => {
      sent.push({ atMs: now(), text });
      console.log(`\n>>> ${text}`);
      client.queue('text', {
        type: 'chat', needs_translation: false, category: 'authored',
        source_name: client.username || USERNAME, xuid: '', platform_chat_id: '',
        filtered_message: '', message: text,
      });
    },
    sendCommand: (text) => {
      const cmd = text.startsWith('/') ? text : `/${text}`;
      sent.push({ atMs: now(), text: cmd });
      console.log(`\n>>> ${cmd}`);
      client.queue('command_request', {
        command: cmd,
        origin: {
          type: 'player', uuid: crypto.randomUUID(), request_id: crypto.randomUUID(),
          player_entity_id: BigInt(0),
        },
        internal: false,
        version: '52',
      });
    },
    close: () => { try { client.close(); } catch (e) { /* already gone */ } },
  };
}

// ---------------------------------------------------------------------------
// Result writing
// ---------------------------------------------------------------------------
function writeResults(extra) {
  const payload = Object.assign({
    role: ROLE,
    runId: plan.runId || '',
    kind: KIND,
    username: USERNAME,
    host: HOST,
    port: PORT,
    minecraftVersion: MC_VERSION,
    sent,
    received,
    assertions,
    handoff,
    expectedTokens,
    // expectAbsent verdicts are computed at exit from the full recording.
    absentChecks: absentChecks.map((c) => ({
      id: c.id,
      token: c.token,
      pass: !seenTokens.has(c.token),
      evidence: seenTokens.has(c.token) ? seenTokens.get(c.token).text : '',
    })),
    timedOut: false,
  }, extra || {});
  fs.writeFileSync(RESULTS_FILE, JSON.stringify(payload, null, 2), 'utf8');
  console.log('results written:', RESULTS_FILE);
}

const absentChecks = [];

// ---------------------------------------------------------------------------
// Step execution
// ---------------------------------------------------------------------------
async function runPlan(transport) {
  for (const step of plan.steps) {
    const label = step.id ? `[${step.id}]` : '';
    console.log(`\n=== STEP ${label} ${step.action}`);
    switch (step.action) {
      case 'chat':
        transport.sendChat(render(step.text));
        break;
      case 'cmd':
        transport.sendCommand(render(step.text));
        break;
      case 'wait':
        await sleep(parseInt(step.ms || 1000, 10));
        break;
      case 'expect': {
        const token = render(step.token);
        if (!expectedTokens.includes(token)) expectedTokens.push(token);
        await expectToken(step.id || token, token, step.timeoutMs || 30000);
        break;
      }
      case 'expectAbsent': {
        const token = render(step.token);
        absentChecks.push({ id: step.id || token, token });
        if (!expectedTokens.includes(token)) expectedTokens.push(token);
        break;
      }
      case 'handoffSet': {
        const re = new RegExp(step.pattern);
        let value = null;
        for (let i = received.length - 1; i >= 0; i--) {
          const m = re.exec(received[i].raw || '');
          if (m) { value = m[1] || m[0]; break; }
        }
        if (value == null) throw new Error(`handoffSet '${step.key}': pattern ${step.pattern} never matched inbound traffic`);
        publishHandoff(step.key, value);
        break;
      }
      case 'handoffGet':
        // awaitHandoff stores the value under the key itself, which is what
        // {{key}} lookups read; no separate echo file is written.
        await awaitHandoff(step.key, step.timeoutMs || 30000);
        break;
      case 'quit':
        transport.close();
        await sleep(1500);
        break;
      default:
        throw new Error(`unknown step action '${step.action}'`);
    }
  }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
let transport = null;
let finished = false;

function attach(text) {
  noteInbound(String(text || ''));
}

function buildTransport() {
  const onLine = (line) => attach(line);
  return KIND === 'bedrock' ? createBedrockTransport(onLine) : createJavaTransport(onLine);
}

const hardTimer = setTimeout(() => {
  if (finished) return;
  console.log('[TIMEOUT] plan did not finish in time; flushing results');
  try { writeResults({ timedOut: true }); } catch (e) { /* best effort */ }
  if (transport) transport.close();
  process.exit(2);
}, TIMEOUT_MS);

transport = buildTransport();

transport.bot.once(transport.spawnEvent, async () => {
  record('spawn', 'spawned', 'spawned');
  try {
    await runPlan(transport);
    finished = true;
    clearTimeout(hardTimer);
    writeResults();
    await sleep(500);
    process.exit(0);
  } catch (e) {
    finished = true;
    clearTimeout(hardTimer);
    console.log('[PLAN ERROR]', e && e.stack);
    record('planError', String(e && e.message), String(e && e.stack));
    writeResults({ error: String(e && e.message) });
    try { transport.close(); } catch (err) { /* already gone */ }
    process.exit(1);
  }
});

// A bot that never spawns must still leave evidence behind: the orchestrator
// reads results, and an absent file is indistinguishable from a crashed role.
setTimeout(() => {
  if (finished) return;
  if (!received.some((r) => r.kind === 'spawn')) {
    console.log('[NOSPAWN] bot never spawned; flushing diagnostic results');
    try { writeResults({ error: 'never-spawned' }); } catch (e) { /* best effort */ }
    process.exit(3);
  }
}, Math.min(TIMEOUT_MS, 60000));
