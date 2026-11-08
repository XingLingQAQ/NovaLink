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
//   chat             { text }                       send a chat line
//   cmd              { text }                       send a slash command
//   query            { text, pattern, timeoutMs }   poll read-only who/list for a fresh receipt
//   wait             { ms }                         sleep
//   checkpoint       { key }                       record a local phase boundary
//   signal           { key, value }                publish an orchestration barrier
//   arm              { token, reset }                     track a token without waiting
//   reconnect        { timeoutMs, delayMs }        reconnect Java or Bedrock once
//   expect           { id, token, timeoutMs }       assert token arrives
//   expectText       { id, pattern, sinceCheckpoint } match normalized received chat
//   expectAbsent     { id, token }                  assert token NEVER arrives (checked at exit)
//   expectEvent      { id, kind, match, timeoutMs } assert a recorded effect arrives
//   expectEventAbsent{ id, kind, match, withinMs }  assert no matching effect within the window
//   expectKicked     { id, reason, timeoutMs }      assert THIS bot was disconnected
//   expectState      { id, player, present }        assert bot.players contains/omits a name
//   handoffSet       { key, pattern, fromStep }     publish a value (e.g. NC-XXXX) for other roles
//   handoffGet       { key, timeoutMs }             wait for a value published by another role
//   quit             { reason }                     close the connection cleanly
//
// `expect`/`expectAbsent` look at chat TEXT; the event steps look at the
// recorded effect stream (titles, sounds, kicks, player joins, action bars...).
// Both read the receiving bot's own recording -- neither trusts a sender.
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

// --selftest runs the plan executor against synthetic transports: no socket,
// no server. It is how the new effect assertions are demonstrated on a machine
// with no topology running, and it deliberately includes steps that MUST fail,
// so a broken matcher cannot masquerade as a green run.
const RECOVERY_SELFTEST = process.argv.includes('--selftest-recovery')
const TEXT_SELFTEST = process.argv.includes('--selftest-text')
const COMPLETION_SELFTEST = process.argv.includes('--selftest-completion')
const SELFTEST = process.argv.includes('--selftest') || RECOVERY_SELFTEST || TEXT_SELFTEST || COMPLETION_SELFTEST

const plan = SELFTEST ? { steps: [], runId: 'selftest', role: 'selftest' } : loadPlan();
const KIND = (plan.kind || 'java').toLowerCase();
const PLATFORM = plan.platform || '';
const USERNAME = plan.username || (SELFTEST ? 'SelfTestBot' : undefined);
const HOST = plan.host || '127.0.0.1';
const PORT = parseInt(plan.port, 10);
const MC_VERSION = plan.mcVersion || (KIND === 'bedrock' ? '1.26.30' : '1.21.11');
const RESULTS_FILE = plan.resultsFile ? path.resolve(plan.resultsFile) : null;
const HANDOFF_DIR = path.resolve(plan.handoffDir ||
  (RESULTS_FILE ? path.join(path.dirname(RESULTS_FILE), 'handoff')
    : path.join(require('os').tmpdir(), 'novachat-joint-selftest')));
const TIMEOUT_MS = parseInt(plan.timeoutMs || '240000', 10);
const ROLE = plan.role || 'X';

if (!SELFTEST) {
  if (!USERNAME || !PORT) {
    console.error('plan must set username and port');
    process.exit(64);
  }
  fs.mkdirSync(path.dirname(RESULTS_FILE), { recursive: true });
  fs.mkdirSync(HANDOFF_DIR, { recursive: true });
}

// ---------------------------------------------------------------------------
// Recording
// ---------------------------------------------------------------------------
const received = [];
const sent = [];
const assertions = [];
const handoff = {};

// Every observed effect is recorded through record(), so an entry is one shape
// everywhere: { t, atMs, kind, raw, text }. `raw` keeps the structured payload
// (JSON) and `text` the human-readable projection, which is exactly what the
// chat lines already do. Assertions match `text` unless a step says otherwise.
function record(kind, raw, text, extra) {
  const entry = Object.assign({
    t: new Date().toISOString(),
    atMs: Date.now(),
    kind,
    raw: String(raw == null ? '' : raw).slice(0, 600),
    text: String(text == null ? '' : text).slice(0, 600),
  }, extra || {});
  received.push(entry);
  console.log(`[RECV ${entry.t}] ${kind}: ${entry.text}`);
  return entry;
}

function plainText(jsonMsg) {
  if (jsonMsg == null) return '';
  if (typeof jsonMsg === 'string') return jsonMsg;
  if (typeof jsonMsg === 'number' || typeof jsonMsg === 'boolean') return String(jsonMsg);
  if (jsonMsg.text != null) return String(jsonMsg.text);
  if (jsonMsg.extra && Array.isArray(jsonMsg.extra)) return jsonMsg.extra.map(plainText).join('');
  if (jsonMsg.translate) return (jsonMsg.with || []).map(plainText).join('') || jsonMsg.translate;
  if (jsonMsg[''] != null) return plainText(jsonMsg['']);
  return JSON.stringify(jsonMsg);
}

// prismarine-chat objects expose toString(); JSON-ish objects do not.
function chatText(msg) {
  if (msg == null) return '';
  if (typeof msg === 'string') return msg;
  if (typeof msg.toString === 'function' && msg.constructor && msg.constructor.name !== 'Object') {
    try { return msg.toString(); } catch (e) { /* fall through */ }
  }
  return plainText(msg);
}

function normalizeRecordedComponent(value) {
  if (value == null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map(normalizeRecordedComponent);
  // Compact NBT text uses an empty key; PS 5.1 cannot deserialize that JSON.
  return Object.fromEntries(Object.entries(value)
    .filter(([key]) => key !== '' || !Object.hasOwn(value, 'text'))
    .map(([key, child]) => [key === '' ? 'text' : key, normalizeRecordedComponent(child)]));
}

// Is this a decoded network-NBT chat component (1.20.3+ wire format)?
function isNbtComponent(value) {
  return value != null && typeof value === 'object' && typeof value.type === 'string'
    && value.value !== undefined;
}

// 1.20.3+ sends every chat component (system chat, title, action bar, kick
// reason) as network NBT, which the protocol layer hands over as a plain
// { type, value } tree. minecraft-protocol normalises it only on the
// system/player chat path; the title, action-bar and disconnect packets reach
// mineflayer raw. Normalising here is what turns those packets into text.
let processNbtMessage = null;
let nbtChatMessage = null;
function componentText(value) {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (!isNbtComponent(value)) return chatText(value);
  // A root STRING tag is the common case for a text-only component: Adventure's
  // NBT serializer writes `TAG_String("...")` rather than a compound. Its
  // normalised form is a bare JSON string, so it must not go through plainText
  // (which expects an object and would return '').
  if (value.type === 'string') return typeof value.value === 'string' ? value.value : '';
  try {
    if (!processNbtMessage) {
      processNbtMessage = requireBotLib('prismarine-chat').processNbtMessage;
    }
    const json = processNbtMessage(value);
    if (json == null) return '';
    const normalized = JSON.parse(json);
    if (typeof normalized === 'string') return normalized;
    // Render through prismarine-chat, not plainText: a component's visible text
    // is text + extra, and plainText stops at `text` when it is present.
    if (!nbtChatMessage) {
      nbtChatMessage = requireBotLib('prismarine-chat')(requireBotLib('prismarine-registry')(MC_VERSION));
    }
    return new nbtChatMessage(normalized).toString();
  } catch (e) {
    console.log('[NBT warn] could not normalise a component:', e && e.message);
    return chatText(value);
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Date.now();

// ---------------------------------------------------------------------------
// Effect stream
//
// `effects[]` mirrors `received[]` but is the *assertion* surface for non-chat
// observations, and `state` is the live view `expectState` reads. Effects are
// pushed by the transports the moment the library reports them; nothing is
// inferred from what this bot sent.
// ---------------------------------------------------------------------------
const effects = [];
const state = {
  players: new Set(),   // tab-list names currently visible to this bot
  self: USERNAME || null,
  kicked: null,         // { reason, atMs, kind }
  spawned: false,
};

function recordEffect(kind, text, data) {
  const entry = record(kind, JSON.stringify(Object.assign({ kind }, data || {})), text, {
    effect: true,
    data: Object.assign({ kind }, data || {}),
  });
  effects.push(entry);
  return entry;
}

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

function normalizedChatText(text) {
  return String(text || '').replace(/(?:\u00a7|&)[0-9a-fk-orx]/gi, '').trim();
}

function checkpointIndex(key) {
  if (!key) return -1;
  const index = received.map((row) => row.kind === 'checkpoint' && row.key === key).lastIndexOf(true);
  if (index < 0) throw new Error(`missing checkpoint: ${key}`);
  return index;
}

async function expectText(step, query) {
  const pattern = new RegExp(step.pattern);
  const sinceIndex = checkpointIndex(step.sinceCheckpoint);
  const started = now();
  const timeoutMs = Number(step.timeoutMs == null ? 15000 : step.timeoutMs);
  if (!Number.isFinite(timeoutMs) || timeoutMs < 1 || timeoutMs > 60000) throw new Error('expectText timeout must be in 1..60000');
  const intervalMs = Number(step.intervalMs == null ? 1000 : step.intervalMs);
  if (query && (!Number.isFinite(intervalMs) || intervalMs < 1 || intervalMs > 5000)) throw new Error('query interval must be in 1..5000');
  let nextQuery = started;
  let hit = null;
  do {
    if (query && now() >= nextQuery) { query(); nextQuery = now() + intervalMs; }
    hit = received.slice(sinceIndex + 1).find((row) =>
      (row.kind === 'messagestr' || /^text:(chat|system|raw|json|translation|announcement|whisper)$/.test(row.kind)) &&
      pattern.test(normalizedChatText(row.text)));
    if (hit || now() - started >= timeoutMs) break;
    await sleep(Math.min(50, timeoutMs - (now() - started)));
  } while (true);
  assertions.push({ id: step.id || step.pattern, token: step.pattern, pass: !!hit,
    waitedMs: now() - started, evidence: hit ? hit.text : '', kind: 'chat-pattern' });
  return !!hit;
}

// ---------------------------------------------------------------------------
// Effect assertions
//
// An "effect" is anything this bot observed that is not chat text: a title, a
// sound, an action bar, a kick, a player entering/leaving the tab list. They are
// recorded into `effects[]` (and into `received[]`, so the orchestrator can join
// them offline) with a kind tag.
//
// Matching is deliberately simple and explicit, and its semantics match `expect`:
//   kind   exact effect kind ('title' | 'sound' | 'actionBar' | 'kicked' |
//          'playerJoined' | 'playerLeft' | 'titleTimes' | 'respawn' | ...)
//   match  substring of `text` (default: any effect of that kind)
//   where  optional dot path inside the effect's `data` that must be a substring
//
// `expectEvent` fails the plan immediately (like `expect`); `expectEventAbsent`
// waits out its window and records a verdict. Both verdicts are also written
// into results.assertions so the orchestrator joins them like any other.
// ---------------------------------------------------------------------------
function effectMatches(effect, kind, match, where, whereValue) {
  if (kind && effect.kind !== kind) return false;
  if (match != null && match !== '' && !String(effect.text).includes(match)) return false;
  if (where) {
    const actual = where.split('.').reduce((acc, key) => (acc == null ? acc : acc[key]), effect.data);
    if (actual == null) return false;
    if (whereValue != null && whereValue !== '' && !String(actual).includes(whereValue)) return false;
  }
  return true;
}

function findEffect(kind, match, where, whereValue, sinceMs, sinceIndex) {
  for (let i = effects.length - 1; i >= 0; i--) {
    const e = effects[i];
    if (sinceMs != null && e.atMs < sinceMs) continue;
    if (sinceIndex != null && received.indexOf(e) <= sinceIndex) continue;
    if (effectMatches(e, kind, match, where, whereValue)) return e;
  }
  return null;
}

function expectEffect(step) {
  const id = step.id || `${step.kind}:${step.match || '*'}`;
  const started = now();
  const timeoutMs = parseInt(step.timeoutMs || 15000, 10);
  // An effect that already arrived before the step ran still counts: the plan
  // author cannot know the exact packet ordering, and re-waiting for something
  // already recorded would turn a real observation into a timeout.
  let sinceIndex = null;
  if (step.sinceCheckpoint) {
    sinceIndex = received.map((row) => row.kind === 'checkpoint' && row.key === step.sinceCheckpoint).lastIndexOf(true);
    if (sinceIndex < 0) throw new Error(`missing checkpoint: ${step.sinceCheckpoint}`);
  }
  const immediate = findEffect(step.kind, step.match, step.where, step.whereValue, undefined, sinceIndex);
  if (immediate) {
    assertions.push({
      id, token: id, pass: true, waitedMs: 0, evidence: immediate.text, kind: 'effect',
      diagnostic: step.diagnostic === true,
    });
    console.log(`[EXPECT-EVENT PASS] ${id} (already recorded: ${immediate.text})`);
    return Promise.resolve(true);
  }
  return new Promise((resolve) => {
    let done = false;
    const tick = () => {
      if (done) return;
      const hit = findEffect(step.kind, step.match, step.where, step.whereValue, started, sinceIndex);
      if (hit) { done = true; clearInterval(poll); clearTimeout(timer); finish(true, hit); return; }
    };
    const finish = (ok, hit) => {
      const waited = now() - started;
      assertions.push({
        id, token: id, pass: ok, waitedMs: waited,
        evidence: hit ? hit.text : '', kind: 'effect',
        // `diagnostic: true` marks an assertion that reports a known gap but
        // must not gate the round (harness convention: see Assert-RelayLinksCreated).
        diagnostic: step.diagnostic === true,
      });
      if (ok) console.log(`[EXPECT-EVENT PASS] ${id} after ${waited}ms: ${hit.text}`);
      else console.log(`[EXPECT-EVENT FAIL] ${id} kind=${step.kind} match=${step.match || '*'} not seen in ${timeoutMs}ms`);
      resolve(ok);
    };
    const poll = setInterval(tick, 100);
    const timer = setTimeout(() => { if (!done) { done = true; clearInterval(poll); finish(false, null); } }, timeoutMs);
  });
}

// Absence is scoped to the WINDOW, not to the whole recording: a plan asserts
// "after this point, the muted player's line never arrives", and effects that
// predate the step (an earlier title, a player who was present before a kick)
// are irrelevant by design. Effects that arrived earlier are still reported in
// the evidence, so an author who scoped a negative assertion wrongly can see it.
async function expectEffectAbsent(step) {
  const id = step.id || `${step.kind}-absent:${step.match || '*'}`;
  const withinMs = parseInt(step.withinMs || step.timeoutMs || 5000, 10);
  const started = now();
  await sleep(withinMs);
  const hit = findEffect(step.kind, step.match, step.where, step.whereValue, started);
  const earlier = findEffect(step.kind, step.match, step.where, step.whereValue);
  const priorNote = earlier && !hit ? ` (an earlier match predates the window: ${earlier.text})` : '';
  assertions.push({
    id, token: id, pass: !hit, waitedMs: now() - started,
    evidence: hit
      ? hit.text
      : `no '${step.kind}' effect matching '${step.match || '*'}${step.where ? `.${step.where}` : ''}' in ${withinMs}ms${priorNote}`,
    kind: 'effect-absent',
  });
  console.log(hit
    ? `[EXPECT-EVENT-ABSENT FAIL] ${id}: ${hit.text}`
    : `[EXPECT-EVENT-ABSENT PASS] ${id} in ${withinMs}ms`);
  return !hit;
}

function expectKicked(step) {
  const id = step.id || 'kicked';
  const reasonMatch = step.reason;
  return new Promise((resolve) => {
    const started = now();
    const timeoutMs = parseInt(step.timeoutMs || 15000, 10);
    const check = () => {
      if (state.kicked && (reasonMatch == null || reasonMatch === ''
          || String(state.kicked.reason).includes(reasonMatch))) {
        return state.kicked;
      }
      return null;
    };
    const immediate = check();
    if (immediate) {
      assertions.push({
        id, token: id, pass: true, waitedMs: 0, evidence: immediate.reason, kind: 'kicked',
      });
      console.log(`[EXPECT-KICKED PASS] ${id}: ${immediate.reason}`);
      return resolve(true);
    }
    const poll = setInterval(() => {
      const hit = check();
      if (!hit) return;
      clearInterval(poll); clearTimeout(timer);
      assertions.push({
        id, token: id, pass: true, waitedMs: now() - started, evidence: hit.reason, kind: 'kicked',
      });
      console.log(`[EXPECT-KICKED PASS] ${id}: ${hit.reason}`);
      resolve(true);
    }, 100);
    const timer = setTimeout(() => {
      clearInterval(poll);
      const sawAny = state.kicked;
      assertions.push({
        id, token: id, pass: false, waitedMs: now() - started,
        evidence: sawAny ? `kicked with a different reason: ${sawAny.reason}` : 'bot was never disconnected',
        kind: 'kicked',
      });
      console.log(`[EXPECT-KICKED FAIL] ${id}: ${sawAny ? sawAny.reason : 'never kicked'}`);
      resolve(false);
    }, timeoutMs);
  });
}

function expectState(step) {
  const id = step.id || `state:${step.player}`;
  const name = render(step.player);
  const present = step.present !== false; // default: must be present
  if (name == null || name === '') {
    assertions.push({ id, token: id, pass: false, waitedMs: 0, evidence: 'no player name given', kind: 'state' });
    return Promise.resolve(false);
  }
  const timeoutMs = parseInt(step.timeoutMs || 15000, 10);
  return new Promise((resolve) => {
    const started = now();
    const ok = () => state.players.has(name) === present;
    const settle = (hit) => {
      const evidence = `${present ? 'contains' : 'omits'} '${name}'; visible players: ` +
        `[${Array.from(state.players).join(', ')}]`;
      assertions.push({ id, token: id, pass: hit, waitedMs: now() - started, evidence, kind: 'state' });
      console.log(`[EXPECT-STATE ${hit ? 'PASS' : 'FAIL'}] ${id}: ${evidence}`);
      resolve(hit);
    };
    if (ok()) return settle(true);
    const poll = setInterval(() => {
      if (!ok()) return;
      clearInterval(poll); clearTimeout(timer);
      settle(true);
    }, 100);
    const timer = setTimeout(() => { clearInterval(poll); settle(false); }, timeoutMs);
  });
}

// ---------------------------------------------------------------------------
// Transport: Java (mineflayer)
// ---------------------------------------------------------------------------
function completionMatches(step, candidates) {
  return candidates.length >= (step.minCandidates || 0)
    && (step.mustInclude || []).every((value) => candidates.includes(render(value)))
    && (step.mustExclude || []).every((value) => !candidates.includes(render(value)));
}

function createJavaCompletionObserver(bot) {
  const custom = new Set();
  const packetIndices = [];
  let lastPushIndex = -1;
  let transactionId = 1000000;
  let pending = null;
  let ended = false;
  const capture = (packetName, packet) => {
    const payload = JSON.parse(JSON.stringify(packet));
    record('completionPacket', JSON.stringify(payload), packetName, {
      packetName, direction: 'clientbound', packet: payload, localTime: new Date().toString(),
      synthetic: SELFTEST,
    });
    return received.length - 1;
  };
  const onPush = (packet) => {
    const index = capture('chat_suggestions', packet);
    if (![0, 1, 2].includes(packet.action) || !Array.isArray(packet.entries)) return;
    if (packet.action === 2) custom.clear();
    for (const entry of packet.entries) {
      if (packet.action === 1) custom.delete(entry);
      else custom.add(entry);
    }
    packetIndices.push(index);
    lastPushIndex = index;
  };
  const onReply = (packet) => {
    const index = capture('tab_complete', packet);
    if (pending && packet.transactionId === pending.id) pending.resolve({ packet, index });
  };
  const onEnd = () => {
    ended = true;
    if (pending) pending.reject(new Error('connection ended during completion'));
    bot._client.removeListener('chat_suggestions', onPush);
    bot._client.removeListener('tab_complete', onReply);
  };
  bot._client.on('chat_suggestions', onPush);
  bot._client.on('tab_complete', onReply);
  bot.once('end', onEnd);

  return async (step) => {
    const text = render(step.text);
    const prefix = text.substring(text.lastIndexOf(' ') + 1);
    if (!prefix.startsWith('@') || text.startsWith('/')) throw new Error('complete requires a chat mention prefix');
    if (!['chat_suggestions', 'tab_complete'].includes(step.protocolMode)) throw new Error('unknown completion protocolMode');
    const timeoutMs = Number(step.timeoutMs || 10000);
    if (!Number.isFinite(timeoutMs) || timeoutMs < 1 || timeoutMs > 30000) throw new Error('invalid completion timeout');
    const startedAtMs = now();
    const input = record('completionInput', text, text, {
      stepId: step.id, protocolMode: step.protocolMode, prefix, localTime: new Date().toString(),
    });
    let candidates;
    let sources;
    let response = null;
    if (step.protocolMode === 'tab_complete') {
      const id = ++transactionId;
      let timer;
      const reply = new Promise((resolve, reject) => {
        pending = { id, resolve, reject };
        timer = setTimeout(() => reject(new Error('tab_complete response timed out')), timeoutMs);
      });
      try {
        if (ended) throw new Error('completion connection already ended');
        const request = { transactionId: id, text, assumeCommand: false };
        sent.push({ atMs: now(), localTime: new Date().toString(), action: 'complete', stepId: step.id,
          packetName: 'tab_complete', text, packet: request });
        bot._client.write('tab_complete', request);
        response = await reply;
      } finally {
        clearTimeout(timer);
        pending = null;
      }
      candidates = response.packet.matches.map((entry) => typeof entry === 'string' ? entry : entry.match);
      sources = [response.index];
    } else {
      const checkpoint = step.sinceCheckpoint ? checkpointIndex(render(step.sinceCheckpoint)) : -1;
      if (step.sinceCheckpoint && checkpoint < 0) throw new Error('completion checkpoint missing');
      const settleMs = Number(step.settleMs == null ? 1000 : step.settleMs);
      do {
        if (ended) throw new Error('connection ended during completion');
        candidates = Array.from(custom).filter((entry) => entry.toLowerCase().startsWith(prefix.toLowerCase()));
        if (lastPushIndex > checkpoint && now() - startedAtMs >= settleMs && completionMatches(step, candidates)) break;
        await sleep(20);
      } while (now() - startedAtMs < timeoutMs);
      if (lastPushIndex <= checkpoint) throw new Error('no fresh chat_suggestions packet for completion');
      sources = packetIndices.slice();
    }
    return record('completion', JSON.stringify(candidates), candidates.join(', '), {
      stepId: step.id, inputAtMs: input.atMs, inputText: text, prefix, protocolMode: step.protocolMode,
      candidates, packetIndices: sources, transactionId: response ? response.packet.transactionId : null,
      replacementStart: response ? response.packet.start : text.lastIndexOf(' ') + 1,
      replacementLength: response ? response.packet.length : prefix.length,
      localTime: new Date().toString(), synthetic: SELFTEST,
    });
  };
}

function createJavaTransport(onChatLine) {
  const mineflayer = requireBotLib('mineflayer');
  let closed = false;
  const bot = mineflayer.createBot({
    host: HOST,
    port: PORT,
    username: USERNAME,
    auth: 'offline',
    version: MC_VERSION,
    hideErrors: false,
  });
  const complete = createJavaCompletionObserver(bot);

  // -- capture channel -------------------------------------------------------
  // The kind stays `messagestr` (the orchestrator's duplicate-render guard counts
  // exactly that kind); the position mineflayer passes as the second argument
  // ('chat' | 'system' | 'game_info') is added as a field, so a line delivered as
  // a system message is now distinguishable from a normal chat line.
  //
  // Note: mineflayer does NOT emit a `systemChat` event on the bot. The old
  // `bot.on('systemChat', ...)` handler here never fired, so its
  // `record('systemChat', ...)` rows never existed in any results file.
  if (plan.captureMessageComponents === true) {
    bot.on('message', (message, position) => {
      recordEffect('messageComponent', chatText(message), {
        component: normalizeRecordedComponent(message.json),
        position: position || 'unknown',
      });
    });
  }
  bot.on('messagestr', (msg, position) => {
    const pos = position || 'unknown';
    const txt = chatText(msg);
    record('messagestr', txt, txt, { position: pos });
    onChatLine(txt);
  });
  bot.on('chat', (username, message) => {
    const line = `<${username}> ${message}`;
    record('chat', line, line);
    onChatLine(message);
  });
  // Action bar. Two wire shapes exist and only one of them is wired by the
  // libraries:
  //   * legacy / Bukkit: system_chat with isActionBar -> minecraft-protocol
  //     rewrites it to systemChat{positionId:2} -> mineflayer emits `actionBar`.
  //   * vanilla 1.17+: a dedicated `action_bar` packet. minecraft-protocol does
  //     not register a handler for it, so no library event is ever emitted.
  // The raw listener below is the only way to see the modern packet; without it
  // a Velocity-hosted player's action bar is invisible to this bot.
  // (Verified by round-tripping 1.21.11 packets through the same
  // serializer/deserializer mineflayer uses.)
  bot.on('actionBar', (msg) => {
    const txt = chatText(msg);
    recordEffect('actionBar', txt, { text: txt, source: 'systemChat' });
    onChatLine(txt);
  });
  bot._client.on('action_bar', (packet) => {
    const txt = componentText(packet && packet.text);
    recordEffect('actionBar', txt, { text: txt, source: 'action_bar' });
    onChatLine(txt);
  });

  // Timings the server announced most recently; attached to the next title /
  // subtitle record so one row carries text + duration.
  let pendingTimes = null;

  // Raw title packets. title.js emits `title`/`title_times`, but it calls
  // JSON.parse on the packet text, which throws for the 1.20.3+ NBT form and
  // falls back to a JSON.stringify of the { type, value } tree. Listening on the
  // raw packets gives the real text; the decoded event is kept below as well so
  // both shapes are visible in the recording.
  bot._client.on('set_title_text', (packet) => {
    const txt = componentText(packet && packet.text);
    recordEffect('title', `title: ${txt}`, { type: 'title', text: txt, source: 'raw', times: pendingTimes });
  });
  bot._client.on('set_title_subtitle', (packet) => {
    const txt = componentText(packet && packet.text);
    recordEffect('title', `subtitle: ${txt}`, { type: 'subtitle', text: txt, source: 'raw', times: pendingTimes });
  });

  // -- title / subtitle / timings -------------------------------------------
  // title.js emits ('title', text, 'title'|'subtitle') and
  // ('title_times', fadeIn, stay, fadeOut). The emit order Velocity uses is
  // times -> text -> subtitle, so the pending timings are attached to the next
  // text events rather than to a single combined record.
  //
  // The decoded `title` event is deliberately NOT recorded: title.js runs
  // JSON.parse over the packet text, so for the 1.20.3+ NBT form it yields
  // String(object) === "[object Object]" and would add a junk row next to the
  // correct raw-packet row. Only `titleTimes` is taken from the decoded path,
  // because that state is assembled there (fadeIn/stay/fadeOut).
  bot.on('title_times', (fadeIn, stay, fadeOut) => {
    pendingTimes = { fadeIn, stay, fadeOut };
    recordEffect('titleTimes', `fadeIn=${fadeIn} stay=${stay} fadeOut=${fadeOut}`, {
      fadeIn, stay, fadeOut,
    });
  });
  bot.on('title_clear', () => recordEffect('titleClear', 'titles cleared', {}));

  const detachSounds = require('./sound-observer').attachJavaSoundObserver(bot, recordEffect);
  bot.once('end', detachSounds);

  // -- connection lifecycle -------------------------------------------------
  bot.on('kicked', (reason, loggedIn) => {
    // kick_disconnect carries a chat component; on 1.20.3+ that is network NBT,
    // which the protocol layer does not normalise for this packet.
    const txt = componentText(reason);
    state.kicked = { reason: txt, atMs: now(), loggedIn: !!loggedIn, raw: String(reason).slice(0, 300) };
    recordEffect('kicked', txt, state.kicked);
    console.log('[KICKED]', txt);
  });
  bot.on('error', (err) => record('error', err && err.message, err && err.stack));
  bot.on('end', () => {
    closed = true;
    state.kicked = state.kicked || null;
    record('end', 'connection ended', 'connection ended');
  });
  bot.on('death', () => recordEffect('death', 'bot died', {}));
  bot.on('respawn', () => { state.spawned = true; recordEffect('respawn', 'respawned', {}); });

  // -- other players (tab list) ---------------------------------------------
  // entities.js emits playerJoined/playerLeft from the player_info /
  // player_remove packets. Keep a name set in step with them: it is what
  // `expectState` reads, and it is derived from the library's events rather
  // than from a second decode of the same packets.
  bot.on('playerJoined', (player) => {
    if (!player || !player.username) return;
    state.players.add(player.username);
    recordEffect('playerJoined', player.username, { player: player.username, uuid: player.uuid });
  });
  bot.on('playerLeft', (player) => {
    if (!player || !player.username) return;
    state.players.delete(player.username);
    recordEffect('playerLeft', player.username, { player: player.username, uuid: player.uuid });
  });

  return {
    kind: 'java',
    spawnEvent: 'spawn',
    closeEvent: 'end',
    rejectEvent: 'kicked',
    isClosed: () => closed,
    bot,
    complete,
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
  let closed = false;
  const bedrock = requireBotLib('bedrock-protocol');
  const { createBedrockClient } = require('../../bot/bedrock-client');
  const client = createBedrockClient({
    platform: PLATFORM, skinId: 'bote2e', host: HOST, port: PORT,
    username: USERNAME, offline: true, version: MC_VERSION,
    skipPing: false, conLog: console.log,
  }, { bedrock, jwt: requireBotLib('jsonwebtoken') });

  client.on('text', (packet) => {
    const source = packet.source_name || '';
    const msg = packet.message || '';
    const type = packet.type || 'unknown';
    // `type` is the Bedrock position: chat | system | announcement | whisper |
    // popup | tip | json... A system line is therefore distinguishable from a
    // chat line here the same way `messagestr.position` does it on Java.
    const line = source ? `<${source}> ${msg}` : msg;
    record(`text:${type}`, line, msg, { position: type });
    onChatLine(msg);
    if (source) onChatLine(line);
  });

  // -- title / subtitle / action bar ---------------------------------------
  // bedrock-protocol emits every packet by name, so `set_title` is raw wire data.
  // The packet carries the type and the three durations in one payload; the
  // durations are only meaningful on `set_durations`, so they are recorded with
  // every title for the plan author to inspect.
  client.on('set_title', (packet) => {
    const type = packet.type;
    const text = packet.text || '';
    const data = {
      type, text,
      fadeIn: packet.fade_in_time, stay: packet.stay_time, fadeOut: packet.fade_out_time,
    };
    if (type === 'action_bar_message' || type === 'action_bar_message_json') {
      recordEffect('actionBar', text, data);
      onChatLine(text);
    } else if (type === 'set_durations') {
      recordEffect('titleTimes', `fadeIn=${data.fadeIn} stay=${data.stay} fadeOut=${data.fadeOut}`, data);
    } else if (type === 'set_title' || type === 'set_title_json') {
      recordEffect('title', `title: ${text}`, data);
    } else if (type === 'set_subtitle' || type === 'set_subtitle_json') {
      recordEffect('title', `subtitle: ${text}`, data);
    } else {
      recordEffect('titleClear', String(type), data);
    }
  });

  // -- sound ----------------------------------------------------------------
  // `play_sound` carries the sound name as a string, so it is always resolvable
  // (unlike the Java numeric-id path).
  client.on('play_sound', (packet) => {
    const pos = packet.coordinates
      ? `${packet.coordinates.x},${packet.coordinates.y},${packet.coordinates.z}` : '';
    recordEffect('sound', `${packet.name} @ ${pos} vol=${packet.volume} pitch=${packet.pitch}`, {
      sound: packet.name, position: pos, volume: packet.volume, pitch: packet.pitch,
    });
  });

  // -- tab list -------------------------------------------------------------
  client.on('player_list', (packet) => {
    const records = (packet && packet.records) || {};
    const kind = records.type;
    const list = records.records || [];
    for (const record of list) {
      if (kind === 'add' && record.username) {
        state.players.add(record.username);
        recordEffect('playerJoined', record.username, { player: record.username, uuid: record.uuid });
      } else if (kind === 'remove' && record.uuid) {
        // A remove record carries only the uuid; map it back through the same
        // data the add record produced.
        const known = effects.find((e) => e.data && e.data.uuid === record.uuid && e.kind === 'playerJoined');
        const name = known ? known.data.player : String(record.uuid);
        state.players.delete(name);
        recordEffect('playerLeft', name, { player: name, uuid: record.uuid });
      }
    }
  });

  client.on('kick', (reason) => {
    const txt = (reason && reason.message) || JSON.stringify(reason);
    state.kicked = { reason: txt, atMs: now() };
    recordEffect('kicked', txt, state.kicked);
  });
  client.on('error', (err) => record('error', err && err.message, err && err.stack));
  client.on('close', () => {
    closed = true;
    record('close', 'connection closed', 'connection closed');
  });

  return {
    kind: 'bedrock',
    spawnEvent: 'spawn',
    closeEvent: 'close',
    rejectEvent: 'kick',
    isClosed: () => closed,
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
        version: 'latest',
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
    // Effects are a filtered view of `received` (the same entries, same shape).
    // They are written out separately so the orchestrator and any offline
    // analysis can read the non-chat observation stream without re-deriving it.
    effects,
    // Final observable state at the moment results were flushed.
    state: {
      players: Array.from(state.players),
      self: state.self,
      kicked: state.kicked,
    },
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
function waitTransportEvent(current, event, timeoutMs, start) {
  const closeEvent = current.closeEvent || 'end';
  const rejectEvent = current.rejectEvent || 'kicked';
  return new Promise((resolve, reject) => {
    let settled = false;
    const cleanup = () => {
      clearTimeout(timer);
      current.bot.removeListener(event, success);
      current.bot.removeListener('error', fail);
      current.bot.removeListener(closeEvent, ended);
      current.bot.removeListener(rejectEvent, kicked);
    };
    const success = () => { if (settled) return; settled = true; cleanup(); resolve(); };
    const fail = (error) => { if (settled) return; settled = true; cleanup(); reject(error); };
    const ended = () => fail(new Error(`connection closed before ${event}`));
    const kicked = (reason) => fail(new Error(`connection rejected: ${componentText(reason)}`));
    const timer = setTimeout(() => fail(new Error(`timed out waiting for ${event}`)), timeoutMs);
    current.bot.once(event, success);
    current.bot.once('error', fail);
    if (event !== closeEvent) {
      current.bot.once(closeEvent, ended);
      current.bot.once(rejectEvent, kicked);
    }
    try {
      if (event === closeEvent && current.isClosed && current.isClosed()) {
        if (start) start();
        success();
      } else if (event !== closeEvent && current.isClosed && current.isClosed()) {
        ended();
      } else if (start) start();
    } catch (error) { fail(error); }
  });
}

async function reconnectTransport(current, step, factory) {
  const timeoutMs = Number(step.timeoutMs == null ? 30000 : step.timeoutMs);
  const delayMs = Number(step.delayMs == null ? 1000 : step.delayMs);
  const kind = current.kind || KIND;
  if (!['java', 'bedrock'].includes(kind)) throw new Error(`unsupported reconnect kind: ${kind}`);
  if (!Number.isFinite(timeoutMs) || timeoutMs < 1 || timeoutMs > 60000 ||
      !Number.isFinite(delayMs) || delayMs < 0 || delayMs > 5000 || delayMs >= timeoutMs) {
    throw new Error('reconnect requires timeoutMs in 1..60000 and delayMs in 0..5000 below timeoutMs');
  }
  const deadline = now() + timeoutMs;
  const remaining = () => {
    const ms = deadline - now();
    if (ms <= 0) throw new Error('reconnect deadline exceeded');
    return ms;
  };
  let next = null;
  try {
    await waitTransportEvent(current, current.closeEvent || 'end', remaining(), () => current.close());
    record('reconnect', 'disconnected', 'disconnected', { phase: 'disconnected', protocol: kind });
    await sleep(Math.min(delayMs, remaining()));
    remaining();
    state.players.clear();
    state.kicked = null;
    state.spawned = false;
    next = factory();
    if (!SELFTEST) transport = next;
    await waitTransportEvent(next, next.spawnEvent, remaining());
    state.spawned = true;
    record('reconnect', 'spawned', 'spawned', { phase: 'spawned', protocol: kind, attempts: 1 });
    assertions.push({ id: step.id || 'reconnect', token: USERNAME, pass: true,
      evidence: `old transport closed; same ${kind} username received spawn on one new connection`, kind: 'reconnect' });
    return next;
  } catch (error) {
    if (next) next.close();
    assertions.push({ id: step.id || 'reconnect', token: USERNAME, pass: false,
      evidence: String(error.message || error), kind: 'reconnect' });
    throw error;
  }
}

async function runPlan(activeTransport, factory = buildTransport) {
  for (const step of plan.steps) {
    const label = step.id ? `[${step.id}]` : '';
    console.log(`\n=== STEP ${label} ${step.action}`);
    switch (step.action) {
      case 'chat':
        activeTransport.sendChat(render(step.text));
        break;
      case 'cmd':
        activeTransport.sendCommand(render(step.text));
        break;
      case 'query': {
        const text = render(step.text);
        if (!/^\/nc (?:who|list)$/.test(text)) throw new Error('query requires read-only /nc who or /nc list');
        const ok = await expectText({ ...step, pattern: render(step.pattern),
          sinceCheckpoint: render(step.sinceCheckpoint) }, () => activeTransport.sendCommand(text));
        if (!ok && step.required === true) throw new Error(`required query failed: ${step.id}`);
        break;
      }
      case 'complete': {
        let row = null;
        let error = null;
        try {
          if (!activeTransport.complete) throw new Error('transport does not support Java chat completion');
          row = await activeTransport.complete(step);
        } catch (e) {
          error = e;
        }
        const pass = row != null && completionMatches(step, row.candidates);
        assertions.push({ id: step.id, kind: 'completion', pass, token: render(step.text),
          evidence: error ? error.message : JSON.stringify(row), waitedMs: row ? row.atMs - row.inputAtMs : 0 });
        if (!pass && step.required === true) throw new Error(`required completion failed: ${step.id}: ${error ? error.message : row.text}`);
        break;
      }
      case 'wait':
        await sleep(parseInt(step.ms || 1000, 10));
        break;
      case 'reconnect':
        activeTransport = await reconnectTransport(activeTransport, step, factory);
        break;
      case 'checkpoint':
        record('checkpoint', step.key, step.key, { key: step.key });
        break;
      case 'signal':
        publishHandoff(step.key, step.value == null ? 'ready' : render(step.value));
        break;
      case 'arm': {
        const token = render(step.token);
        if (step.reset === true) seenTokens.delete(token);
        if (!token) throw new Error('arm requires a non-empty token');
        if (!expectedTokens.includes(token)) expectedTokens.push(token);
        break;
      }
      case 'expect': {
        const token = render(step.token);
        if (!expectedTokens.includes(token)) expectedTokens.push(token);
        const ok = await expectToken(step.id || token, token, step.timeoutMs || 30000);
        if (!ok && step.required === true) throw new Error(`required receipt failed: ${step.id || token}`);
        break;
      }
      case 'expectText': {
        const ok = await expectText(Object.assign({}, step, {
          pattern: render(step.pattern), sinceCheckpoint: render(step.sinceCheckpoint),
        }));
        if (!ok && step.required === true) throw new Error(`required chat pattern failed: ${step.id || step.pattern}`);
        break;
      }
      case 'expectAbsent': {
        const token = render(step.token);
        absentChecks.push({ id: step.id || token, token });
        if (!expectedTokens.includes(token)) expectedTokens.push(token);
        break;
      }
      case 'expectEvent':
        // Recorded, never thrown: the verdict is already in `assertions` and the
        // orchestrator fails the round on it. Aborting here would skip every
        // later step (including other bots' cross-server tokens), turning one
        // missing effect into a pile of unrelated failures.
        await expectEffect(Object.assign({}, step, {
          sinceCheckpoint: step.sinceCheckpoint != null ? render(step.sinceCheckpoint) : undefined,
          match: step.match != null ? render(step.match) : undefined,
          whereValue: step.whereValue != null ? render(step.whereValue) : undefined,
        }));
        break;
      case 'expectEventAbsent':
        // Same contract as expectEvent: the verdict is recorded and gates the
        // round, but it does not abort the remaining steps.
        await expectEffectAbsent(Object.assign({}, step, {
          match: step.match != null ? render(step.match) : undefined,
          whereValue: step.whereValue != null ? render(step.whereValue) : undefined,
        }));
        break;
      case 'expectKicked':
        await expectKicked(Object.assign({}, step, {
          reason: step.reason != null ? render(step.reason) : undefined,
        }));
        break;
      case 'expectState': {
        await expectState(Object.assign({}, step, { player: render(step.player) }));
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
        activeTransport.close();
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

// ---------------------------------------------------------------------------
// Self-test
//
// `--selftest` executes the REAL plan runner against a synthetic transport that
// emits the same recorded effect rows the live transports emit. No socket, no
// server: this proves the assertion machinery (positive, negative, kicked,
// state) behaves, and it is the only way to demonstrate the new steps on a host
// with no topology up.
//
// The suite deliberately contains MUST-FAIL cases. Every case is then compared
// against its expected verdict, so a matcher that silently matches everything
// fails the self-test instead of passing it.
// ---------------------------------------------------------------------------
const SELFTEST_CASES = [
  {
    name: 'title observed with matching subtitle',
    steps: [{ id: 'see-title', action: 'expectEvent', kind: 'title', match: 'subtitle: Hello Bob', timeoutMs: 2000 }],
    emit: [{ kind: 'title', text: 'title: Hello Alice', data: { type: 'title', text: 'Hello Alice' } },
           { kind: 'title', text: 'subtitle: Hello Bob', data: { type: 'subtitle', text: 'Hello Bob' } }],
    expectPass: true,
  },
  {
    name: 'title timings observed (fadeIn/stay/fadeOut)',
    steps: [{ id: 'see-times', action: 'expectEvent', kind: 'titleTimes', match: 'fadeIn=10 stay=40 fadeOut=10', timeoutMs: 2000 }],
    emit: [{ kind: 'titleTimes', text: 'fadeIn=10 stay=40 fadeOut=10', data: { fadeIn: 10, stay: 40, fadeOut: 10 } }],
    expectPass: true,
  },
  {
    name: 'title timings matched through the data path, not the text',
    steps: [{ id: 'see-times-where', action: 'expectEvent', kind: 'titleTimes', where: 'stay', whereValue: '40', timeoutMs: 2000 }],
    emit: [{ kind: 'titleTimes', text: 'anything', data: { fadeIn: 10, stay: 40, fadeOut: 10 } }],
    expectPass: true,
  },
  {
    name: 'mention sound observed on the mentioned player',
    steps: [{ id: 'hear-sound', action: 'expectEvent', kind: 'sound', match: 'entity.experience_orb.pickup', timeoutMs: 2000 }],
    emit: [{ kind: 'sound', text: 'entity.experience_orb.pickup @ 1,2,3 vol=1 pitch=1', data: { sound: 'entity.experience_orb.pickup' } }],
    expectPass: true,
  },
  {
    name: 'mention sound ABSENT for a player who was not mentioned',
    steps: [{ id: 'no-sound', action: 'expectEventAbsent', kind: 'sound', match: 'entity.experience_orb.pickup', withinMs: 300 }],
    emit: [],
    expectPass: true,
  },
  {
    // The sound arrives DURING the window: this is the muted/mentioned-player
    // case that must fail. (A sound that predates the window is correctly
    // ignored -- absence is scoped to the window.)
    name: 'NEGATIVE CONTROL: sound must fail to be absent when it arrives during the window',
    steps: [{ id: 'must-catch-sound', action: 'expectEventAbsent', kind: 'sound', match: 'entity.experience_orb.pickup', withinMs: 500 }],
    emit: [],
    emitDuring: [
      { delayMs: 150, item: { kind: 'sound', text: 'entity.experience_orb.pickup @ 1,2,3', data: { sound: 'entity.experience_orb.pickup' } } },
    ],
    expectPass: false,
  },
  {
    name: 'action bar observed',
    steps: [{ id: 'see-bar', action: 'expectEvent', kind: 'actionBar', match: 'Current channel: global', timeoutMs: 2000 }],
    emit: [{ kind: 'actionBar', text: 'Current channel: global (ALL)', data: { text: 'Current channel: global (ALL)' } }],
    expectPass: true,
  },
  {
    name: 'bot disconnects with a matching reason',
    steps: [{ id: 'got-kicked', action: 'expectKicked', reason: 'banned', timeoutMs: 2000 }],
    emit: [{ kind: 'kicked', text: 'You are banned from this server', data: { reason: 'You are banned from this server' }, setsKicked: true }],
    expectPass: true,
  },
  {
    name: 'NEGATIVE CONTROL: a kick with the wrong reason must not satisfy expectKicked',
    steps: [{ id: 'wrong-reason', action: 'expectKicked', reason: 'banned', timeoutMs: 400 }],
    emit: [{ kind: 'kicked', text: 'Server restarting', data: { reason: 'Server restarting' }, setsKicked: true }],
    expectPass: false,
  },
  {
    name: 'tab-list state: a player is present',
    steps: [{ id: 'bob-present', action: 'expectState', player: 'E2ELiteBob', present: true, timeoutMs: 2000 }],
    emit: [{ kind: 'playerJoined', text: 'E2ELiteBob', data: { player: 'E2ELiteBob' }, addPlayer: 'E2ELiteBob' }],
    expectPass: true,
  },
  {
    // A player leaving the SERVER disappears from the tab list. (A channel KICK
    // does not do this: it removes channel membership, not the player. This case
    // proves the mechanism, not the product behaviour.)
    name: 'tab-list state: a player who leaves disappears from the tab list',
    steps: [
      { id: 'bob-present', action: 'expectState', player: 'E2ELiteBob', present: true, timeoutMs: 2000 },
      { id: 'bob-gone', action: 'expectState', player: 'E2ELiteBob', present: false, timeoutMs: 2000 },
    ],
    emit: [
      { kind: 'playerJoined', text: 'E2ELiteBob', data: { player: 'E2ELiteBob' }, addPlayer: 'E2ELiteBob' },
    ],
    emitDuring: [
      { delayMs: 400, item: { kind: 'playerLeft', text: 'E2ELiteBob', data: { player: 'E2ELiteBob' }, removePlayer: 'E2ELiteBob' } },
    ],
    expectPass: true,
  },
  {
    name: 'NEGATIVE CONTROL: expectState must fail when the player never leaves',
    steps: [
      { id: 'bob-present', action: 'expectState', player: 'E2ELiteBob', present: true, timeoutMs: 500 },
      { id: 'bob-gone', action: 'expectState', player: 'E2ELiteBob', present: false, timeoutMs: 300 },
    ],
    emit: [
      { kind: 'playerJoined', text: 'E2ELiteBob', data: { player: 'E2ELiteBob' }, addPlayer: 'E2ELiteBob' },
    ],
    expectPass: false,
  },
  {
    name: 'NEGATIVE CONTROL: expectEvent must fail when nothing arrives',
    steps: [{ id: 'no-title', action: 'expectEvent', kind: 'title', match: 'never emitted', timeoutMs: 300 }],
    emit: [],
    expectPass: false,
  },
  {
    name: 'system vs chat position is recorded',
    steps: [{ id: 'wants-system', action: 'expectEvent', kind: 'messagestr', where: 'position', whereValue: 'system', match: 'joined the game', timeoutMs: 2000 }],
    emit: [{ kind: 'messagestr', text: 'E2EJointBedrock joined the game', data: { position: 'system' } }],
    expectPass: true,
  },
];

async function runCompletionSelftest() {
  const assert = require('assert');
  const { EventEmitter } = require('events');
  const protocol = requireBotLib('minecraft-protocol');
  const nativePacketsIndex = process.argv.indexOf('--completion-native-packets');
  if (nativePacketsIndex >= 0) {
    const fixture = JSON.parse(fs.readFileSync(process.argv[nativePacketsIndex + 1], 'utf8'));
    assert.strictEqual(fixture.synthetic, true);
    assert.strictEqual(fixture.packets.length, 2);
    const decoder = protocol.createDeserializer({ state: 'play', isServer: false, version: MC_VERSION });
    for (const packet of fixture.packets) {
      const decoded = decoder.parsePacketBuffer(Buffer.from(packet.packetHex, 'hex'));
      assert.deepStrictEqual(decoded.data, { name: 'chat_suggestions', params: { action: packet.action, entries: packet.entries } });
    }
    console.log('[OFFLINE PASS] Minecraft protocol decoder accepts the actual Waterfall adapter ADD/REMOVE packets');
  }
  const serializer = protocol.createSerializer({ state: 'play', isServer: false, version: MC_VERSION });
  const client = new EventEmitter();
  const bot = new EventEmitter();
  bot._client = client;
  const complete = createJavaCompletionObserver(bot);
  let writes = 0;
  client.write = (name, packet) => {
    writes++;
    const bytes = serializer.createPacketBuffer({ name, params: packet });
    assert(bytes.length > 0, 'actual protocol schema accepts the request');
    client.emit('tab_complete', { transactionId: packet.transactionId - 1, start: 0, length: 4,
      matches: [{ match: '@Stale' }] });
    client.emit('tab_complete', { transactionId: packet.transactionId, start: 0, length: packet.text.length,
      matches: [{ match: '@E2ETarget' }] });
  };
  const base = { id: 'wire', action: 'complete', text: '@E2E', minCandidates: 1,
    mustInclude: ['@E2ETarget'], mustExclude: ['@SelfTestBot'], timeoutMs: 50, required: true };
  plan.steps = [Object.assign({}, base, { protocolMode: 'tab_complete' })];
  await runPlan({ complete });
  assert.strictEqual(assertions.at(-1).pass, true);
  assert.strictEqual(writes, 1);
  assert.deepStrictEqual(received.at(-1).candidates, ['@E2ETarget']);
  client.emit('chat_suggestions', { action: 2, entries: ['@E2ETarget', '@Other'] });
  const push = Object.assign({}, base, { protocolMode: 'chat_suggestions', settleMs: 0 });
  plan.steps = [push, Object.assign({}, push, { id: 'inline', text: 'hello @E2E' })];
  await runPlan({ complete });
  assert.strictEqual(assertions.at(-1).pass, true);
  assert.strictEqual(received.at(-1).prefix, '@E2E');
  assert.strictEqual(writes, 1, 'ordinary chat must not send a request');
  record('checkpoint', 'removal', 'removal', { key: 'removal' });
  const absent = Object.assign({}, push, { id: 'absent', minCandidates: 0, mustInclude: [],
    mustExclude: ['@E2ETarget'], sinceCheckpoint: 'removal', required: false });
  plan.steps = [absent];
  await runPlan({ complete });
  assert.strictEqual(assertions.at(-1).pass, false, 'stale cache cannot prove exclusion');
  client.emit('chat_suggestions', { action: 1, entries: ['@E2ETarget'] });
  await runPlan({ complete });
  assert.strictEqual(assertions.at(-1).pass, true);
  client.emit('chat_suggestions', { action: 0, entries: ['@E2ETarget'] });
  plan.steps = [push];
  await runPlan({ complete });
  assert.strictEqual(assertions.at(-1).pass, true);
  client.emit('chat_suggestions', { action: 2, entries: ['@SelfTestBot'] });
  await assert.rejects(runPlan({ complete }), /required completion failed/);
  client.write = () => {};
  plan.steps = [Object.assign({}, base, { protocolMode: 'tab_complete' })];
  await assert.rejects(runPlan({ complete }), /response timed out/);
  plan.steps = [Object.assign({}, base, { protocolMode: 'tab_complete', required: false })];
  await runPlan({});
  assert.strictEqual(assertions.at(-1).pass, false, 'unsupported transport is a failure');
  bot.emit('end');
  assert.strictEqual(client.listenerCount('chat_suggestions'), 0);
  assert.strictEqual(client.listenerCount('tab_complete'), 0);
  console.log(`[SYNTHETIC] completion: ${MC_VERSION} request encoding, transaction correlation, push ADD/REMOVE/SET, inline prefix, stale exclusion, self-only, timeout and unsupported transport checked; no server evidence`);
  return true;
}

async function runTextSelftest() {
  const assert = require('assert');
  const compact = { extra: [{ '': 'Type ' }, { text: 'name', hover_event: {
    action: 'show_text', value: { '': 'minecraft:diamond_sword Count: 1' },
  } }] };
  assert.deepStrictEqual(normalizeRecordedComponent(compact), { extra: [{ text: 'Type ' }, { text: 'name', hover_event: {
    action: 'show_text', value: { text: 'minecraft:diamond_sword Count: 1' },
  } }] });
  assert.strictEqual(compact.extra[0][''], 'Type ', 'recording must not mutate received components');
  assert.deepStrictEqual(normalizeRecordedComponent({ text: 'visible', '': 'ignored' }), { text: 'visible' });
  console.log('[REGRESSION PASS] compact component text canonicalized without losing hover or mutating the receipt');
  const cases = [
    { text: '[NovaChat] \u00a7aJoined channel \u00a7eglobal', pattern: '(?:Joined channel|\\u5df2\\u52a0\\u5165\\u9891\\u9053)\\s+global$', ok: true },
    { text: '[NovaChat] \u5df2\u52a0\u5165\u9891\u9053 \u00a7eglobal', pattern: '(?:Joined channel|\\u5df2\\u52a0\\u5165\\u9891\\u9053)\\s+global$', ok: true },
    { text: 'Channel Global online members \u00a77(2):', pattern: 'online members\\s*\\(2\\):$', ok: true },
    { text: '\u00a76\u9891\u9053 \u00a7eGlobal \u00a76\u5728\u7ebf\u6210\u5458 \u00a77\uff082 \u4eba\uff09\uff1a', pattern: '\\u5728\\u7ebf\\u6210\\u5458\\s*\\uff082\\s*\\u4eba\\uff09\\uff1a$', ok: true },
    { text: 'Joined channel wrong', pattern: 'Joined channel global$', ok: false },
    { text: 'unrelated', raw: 'Joined channel global', pattern: 'Joined channel global$', ok: false },
    { text: 'Joined channel global', pattern: 'Joined channel global$', stale: true, ok: false },
    { text: 'Joined channel global', pattern: 'Joined channel global$', kind: 'actionBar', ok: false },
  ];
  for (const test of cases) {
    received.length = 0;
    assertions.length = 0;
    if (test.stale) record('text:raw', test.text, test.text);
    record('checkpoint', 'text-test', 'text-test', { key: 'text-test' });
    if (!test.stale) record(test.kind || 'text:raw', test.raw || test.text, test.text);
    const actual = await expectText({ id: 'localized-receipt', pattern: test.pattern, sinceCheckpoint: 'text-test', timeoutMs: 5 });
    assert.strictEqual(actual, test.ok, JSON.stringify(test));
  }
  plan.steps = [{ action: 'checkpoint', key: 'required' },
    { id: 'required-pattern', action: 'expectText', pattern: '^never$', sinceCheckpoint: 'required', timeoutMs: 5, required: true },
    { action: 'cmd', text: '/should-not-send' }];
  let commands = 0;
  await assert.rejects(runPlan({ sendCommand() { commands++; } }), /required chat pattern failed/);
  assert.strictEqual(commands, 0);
  received.length = 0;
  assertions.length = 0;
  plan.steps = [{ action: 'checkpoint', key: 'query-start' },
    { id: 'current-channel', action: 'query', text: '/nc who', pattern: '^Channel Local online members',
      sinceCheckpoint: 'query-start', timeoutMs: 100, intervalMs: 1, required: true }];
  let queries = 0;
  await runPlan({ sendCommand(text) {
    assert.strictEqual(text, '/nc who');
    record('messagestr', '', ++queries === 1 ? 'You are not in any channel' : 'Channel Local online members (2):');
  } });
  assert.strictEqual(queries, 2);
  plan.steps = [{ action: 'query', text: '/nc join local', pattern: 'Joined', timeoutMs: 1 }];
  await assert.rejects(runPlan({ sendCommand() { assert.fail('query must not repair membership'); } }), /read-only/);
  console.log('[SYNTHETIC] localized chat, phase boundaries, raw-only rejection and required-step abort checked');
  return true;
}

async function runLifecycleSelftest() {
  const assert = require('assert');
  const { EventEmitter } = require('events');
  const checks = [
    { name: 'reconnect routes later commands to new transport', outcome: 'spawn', ok: true },
    { name: 'reconnect disconnect timeout never dials', outcome: 'disconnect-timeout', ok: false },
    { name: 'reconnect spawn timeout closes new transport', outcome: 'spawn-timeout', ok: false },
    { name: 'reconnect rejection never retries', outcome: 'kicked', ok: false },
    { name: 'reconnect early end never passes', outcome: 'end', ok: false },
    { name: 'reconnect transport error never passes', outcome: 'error', ok: false },
    { name: 'reconnect rejects unbounded timeout', outcome: 'invalid', ok: false },
  ];
  const bedrockChecks = checks.map((check) => Object.assign({}, check, { kind: 'bedrock' }));
  bedrockChecks.push({ name: 'already closed Bedrock transport reconnects once', kind: 'bedrock', outcome: 'already-closed', ok: true });
  bedrockChecks.push({ name: 'closed replacement cannot fake Bedrock spawn', kind: 'bedrock', outcome: 'closed-new', ok: false });
  for (const check of checks.concat(bedrockChecks)) {
    assertions.length = 0;
    const kind = check.kind || 'java';
    const closeEvent = kind === 'bedrock' ? 'close' : 'end';
    const rejectEvent = kind === 'bedrock' ? 'kick' : 'kicked';
    let dials = 0;
    let closes = 0;
    let oldCloses = 0;
    const commands = [];
    const old = { kind, closeEvent, rejectEvent, bot: new EventEmitter(),
      isClosed: () => check.outcome === 'already-closed',
      close() {
        oldCloses++;
        if (!['disconnect-timeout', 'already-closed'].includes(check.outcome)) this.bot.emit(closeEvent);
      }, sendCommand() { throw new Error('used old connection'); } };
    const factory = () => {
      dials++;
      assert.strictEqual(oldCloses, 1, 'old transport closed before dialing');
      assert.strictEqual(state.players.size, 0);
      assert.strictEqual(state.kicked, null);
      const next = { kind, closeEvent, rejectEvent, bot: new EventEmitter(), spawnEvent: 'spawn',
        isClosed: () => check.outcome === 'closed-new',
        close() { closes++; }, sendCommand(cmd) { commands.push(cmd); } };
      setTimeout(() => {
        if (['spawn', 'already-closed'].includes(check.outcome)) next.bot.emit('spawn');
        if (check.outcome === 'kicked') next.bot.emit(rejectEvent, 'refused');
        if (check.outcome === 'end') next.bot.emit(closeEvent);
        if (check.outcome === 'error') next.bot.emit('error', new Error('refused'));
      }, 5);
      return next;
    };
    state.players.add('stale');
    state.kicked = { reason: 'stale' };
    plan.steps = [
      { id: 'reconnect', action: 'reconnect', timeoutMs: check.outcome === 'invalid' ? 60001 : 80, delayMs: 0 },
      { action: 'cmd', text: '/nc who global' },
    ];
    let error = null;
    try { await runPlan(old, factory); } catch (e) { error = e; }
    assert.strictEqual(error == null, check.ok, check.name);
    assert.strictEqual(dials, ['disconnect-timeout', 'invalid'].includes(check.outcome) ? 0 : 1, check.name);
    assert.strictEqual(closes, check.ok || dials === 0 ? 0 : 1, check.name);
    assert.deepStrictEqual(commands, check.ok ? ['/nc who global'] : [], check.name);
    assert.strictEqual(old.bot.listenerCount(closeEvent), 0, check.name);
    console.log(`[SYNTHETIC PASS] ${kind}: ${check.name}`);
  }

  assertions.length = 0;
  expectedTokens.length = 0;
  seenTokens.clear();
  received.length = 0;
  plan.steps = [{ action: 'arm', token: 'synchronous-receipt' },
    { action: 'cmd', text: '/test' },
    { id: 'receipt', action: 'expect', token: 'synchronous-receipt', timeoutMs: 20 }];
  await runPlan({ sendCommand() { noteInbound('synchronous-receipt'); } });
  assert.strictEqual(assertions[0].pass, true);
  console.log('[SELFTEST PASS] arm captures receipt before expect');

  assertions.length = 0;
  effects.length = 0;
  recordEffect('title', 'diagnostic title', {});
  await expectEffect({ id: 'diagnostic-fast', kind: 'title', match: 'diagnostic title', diagnostic: true });
  assert.strictEqual(assertions[0].diagnostic, true);
  console.log('[SELFTEST PASS] immediate effect preserves diagnostic');
  assertions.length = 0;
  effects.length = 0;
  received.length = 0;
  recordEffect('respawn', 'stale respawn', {});
  record('checkpoint', 'switch', 'switch', { key: 'switch' });
  await expectEffect({ id: 'stale-respawn', kind: 'respawn', sinceCheckpoint: 'switch', timeoutMs: 10 });
  assert.strictEqual(assertions[0].pass, false);
  console.log('[SYNTHETIC PASS] pre-checkpoint respawn cannot prove switch');
  recordEffect('respawn', 'actual replacement respawn', {});
  await expectEffect({ id: 'fresh-respawn', kind: 'respawn', sinceCheckpoint: 'switch', timeoutMs: 10 });
  assert.strictEqual(assertions[1].pass, true);
  console.log('[SYNTHETIC PASS] post-checkpoint respawn can prove switch');
  plan.steps = [];
  return checks.length + bedrockChecks.length + 4;
}

async function runSelftest() {
  const failures = [];
  for (const testCase of SELFTEST_CASES) {
    // Reset the shared stores: each case is an independent bot session.
    effects.length = 0;
    received.length = 0;
    assertions.length = 0;
    state.players.clear();
    state.kicked = null;

    // Emit the synthetic effects the "server" would have sent.
    for (const item of testCase.emit) {
      if (item.delayMs) await sleep(item.delayMs);
      if (item.addPlayer) state.players.add(item.addPlayer);
      if (item.removePlayer) state.players.delete(item.removePlayer);
      if (item.setsKicked) state.kicked = { reason: item.data.reason, atMs: now() };
      const entry = recordEffect(item.kind, item.text, item.data || {});
      // The position field the Java transport adds to `messagestr` rows is data
      // already; nothing else to copy.
      if (entry.data && entry.data.position) entry.position = entry.data.position;
    }

    // Effects that must arrive WHILE the plan is running (a player leaving
    // after the plan has already checked they were present). Scheduled before
    // the run so they interleave with it, the way a live server would.
    const timers = (testCase.emitDuring || []).map((scheduled) => setTimeout(() => {
      const item = scheduled.item;
      if (item.addPlayer) state.players.add(item.addPlayer);
      if (item.removePlayer) state.players.delete(item.removePlayer);
      if (item.setsKicked) state.kicked = { reason: item.data.reason, atMs: now() };
      recordEffect(item.kind, item.text, item.data || {});
    }, scheduled.delayMs));

    const savedSteps = plan.steps;
    plan.steps = testCase.steps;
    let threw = null;
    try {
      await runPlan({ sendChat() {}, sendCommand() {}, close() {} });
    } catch (e) {
      threw = e;
    } finally {
      plan.steps = savedSteps;
      for (const timer of timers) clearTimeout(timer);
    }

    // A case "passes" when every assertion it produced is a PASS. Effect
    // verdicts are recorded rather than thrown (so one miss does not abort the
    // remaining steps), so the assertions array -- not the absence of an
    // exception -- is what decides. `expectPass: false` cases are the negative
    // controls: they must produce at least one failing verdict.
    const failedVerdicts = assertions.filter((a) => !a.pass);
    const passed = threw == null && failedVerdicts.length === 0;
    const ok = passed === testCase.expectPass;
    console.log(`[SELFTEST ${ok ? 'PASS' : 'FAIL'}] ${testCase.name} -> ` +
      `verdicts ${passed ? 'all pass' : `${failedVerdicts.length} failing`}` +
      `${threw ? ', plan threw' : ''}, expected ${testCase.expectPass ? 'pass' : 'a failure'}`);
    if (!ok) failures.push(testCase.name);
  }

  // Record every case's verdict in the same assertions array the orchestrator
  // reads, so a self-test run leaves evidence in the standard shape.
  for (const f of failures) {
    assertions.push({ id: `selftest/${f}`, token: f, pass: false, waitedMs: 0, evidence: 'self-test case did not behave', kind: 'selftest' });
  }
  const lifecycleCases = await runLifecycleSelftest();
  const total = SELFTEST_CASES.length + lifecycleCases;
  console.log(`\n[SELFTEST] ${total - failures.length}/${total} cases behaved as expected`);
  return failures.length === 0;
}

const hardTimer = setTimeout(() => {
  if (finished) return;
  console.log('[TIMEOUT] plan did not finish in time; flushing results');
  try { writeResults({ timedOut: true }); } catch (e) { /* best effort */ }
  if (transport) transport.close();
  process.exit(2);
}, TIMEOUT_MS);

if (SELFTEST) {
  clearTimeout(hardTimer);
  (COMPLETION_SELFTEST ? runCompletionSelftest() : TEXT_SELFTEST ? runTextSelftest() : RECOVERY_SELFTEST ? runLifecycleSelftest().then(() => true) : runSelftest()).then((ok) => {
    finished = true;
    console.log(ok ? '[SELFTEST] OK' : '[SELFTEST] FAILED');
    process.exit(ok ? 0 : 1);
  }).catch((e) => {
    console.error('[SELFTEST] threw:', e && e.stack);
    process.exit(1);
  });
} else {
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
}
