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
//   wait             { ms }                         sleep
//   expect           { id, token, timeoutMs }       assert token arrives
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
const SELFTEST = process.argv.includes('--selftest')

const plan = SELFTEST ? { steps: [], runId: 'selftest', role: 'selftest' } : loadPlan();
const KIND = (plan.kind || 'java').toLowerCase();
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

function findEffect(kind, match, where, whereValue, sinceMs) {
  for (let i = effects.length - 1; i >= 0; i--) {
    const e = effects[i];
    if (sinceMs != null && e.atMs < sinceMs) continue;
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
  const immediate = findEffect(step.kind, step.match, step.where, step.whereValue);
  if (immediate) {
    assertions.push({
      id, token: id, pass: true, waitedMs: 0, evidence: immediate.text, kind: 'effect',
    });
    console.log(`[EXPECT-EVENT PASS] ${id} (already recorded: ${immediate.text})`);
    return Promise.resolve(true);
  }
  return new Promise((resolve) => {
    let done = false;
    const tick = () => {
      if (done) return;
      const hit = findEffect(step.kind, step.match, step.where, step.whereValue, started);
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

  // -- capture channel -------------------------------------------------------
  // The kind stays `messagestr` (the orchestrator's duplicate-render guard counts
  // exactly that kind); the position mineflayer passes as the second argument
  // ('chat' | 'system' | 'game_info') is added as a field, so a line delivered as
  // a system message is now distinguishable from a normal chat line.
  //
  // Note: mineflayer does NOT emit a `systemChat` event on the bot. The old
  // `bot.on('systemChat', ...)` handler here never fired, so its
  // `record('systemChat', ...)` rows never existed in any results file.
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

  // -- sound ----------------------------------------------------------------
  bot.on('soundEffectHeard', (soundName, position, volume, pitch) => {
    const pos = position ? `${position.x},${position.y},${position.z}` : '';
    recordEffect('sound', `${soundName} @ ${pos} vol=${volume} pitch=${pitch}`, {
      sound: soundName, position: pos, volume, pitch,
    });
  });
  bot.on('hardcodedSoundEffectHeard', (soundId, category, position, volume, pitch) => {
    // Only reached when the id cannot be resolved to a name; recording it keeps
    // "unresolvable" visible instead of silently dropping the sound.
    const pos = position ? `${position.x},${position.y},${position.z}` : '';
    recordEffect('soundId', `id=${soundId} cat=${category} @ ${pos}`, {
      soundId, category, position: pos, volume, pitch,
    });
  });
  // mineflayer's sound plugin listens to `sound_effect` and `named_sound_effect`
  // only. A 1.19+ server may send `entity_sound_effect` instead -- Velocity's
  // ConnectedPlayer#playSound ALWAYS does, because it routes the sound through
  // the player's backend server entity -- and no library event fires for it.
  // Without this listener a proxy-rendered mention sound is invisible.
  bot._client.on('entity_sound_effect', (packet) => {
    const sound = (packet && packet.sound) || {};
    const name = (sound.data && sound.data.soundName) || (sound.soundId != null ? `id:${sound.soundId}` : 'unknown');
    recordEffect('sound', `${name} @ entity:${packet.entityId} vol=${packet.volume} pitch=${packet.pitch}`, {
      sound: name, entityId: packet.entityId, volume: packet.volume, pitch: packet.pitch,
      source: 'entity_sound_effect',
    });
  });
  // Diagnostic: name every sound-carrying packet that arrives, whatever the
  // decoder made of it. This is what tells "the server never sent a sound" apart
  // from "the server sent one the libraries do not surface".
  bot._client.on('raw', (buffer, meta) => {
    if (!meta || !/sound/i.test(meta.name || '')) return;
    recordEffect('rawSoundPacket', meta.name, { name: meta.name, bytes: buffer ? buffer.length : 0 });
  });

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
    // 4 bytes RGBA per pixel, solid "Steve-ish" tan, full alpha.
    const pixel = Buffer.from([0xb3, 0x7b, 0x62, 0xff]);
    const skinPixels = Buffer.allocUnsafe(W * H * 4);
    for (let i = 0; i < W * H; i++) pixel.copy(skinPixels, i * 4);

    // Both the resource patch AND the geometry blob must be present and
    // base64-encoded JSON: Nukkit validates the geometry JSON and kicks with
    // `disconnectionScreen.invalidSkin` when SkinGeometryData is empty while
    // the patch names a custom geometry (verified 2026-10-19 -- the bot died at
    // login with Patch/SkinGeometryData present but GeometryData blank).
    // BDS/PMMP accept the pair as well, so one shape serves every engine.
    const patch = JSON.stringify({ geometry: { default: 'geometry.humanoid.custom' } });
    const geometry = JSON.stringify({
      format_version: '1.12.0',
      'minecraft:geometry': [],
    });

    return {
      // SkinId must survive the server's int16 conversion; a stable ASCII id is
      // safest. Do NOT include ':' or characters an engine may re-encode.
      SkinId: 'bote2e',
      SkinData: skinPixels.toString('base64'),
      SkinImageWidth: W,
      SkinImageHeight: H,
      SkinResourcePatch: Buffer.from(patch, 'utf8').toString('base64'),
      // SkinGeometryData: the geometry payload, base64 of a JSON document.
      SkinGeometryData: Buffer.from(geometry, 'utf8').toString('base64'),
      SkinAnimationData: '', CapeData: '',
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
      case 'expectEvent':
        // Recorded, never thrown: the verdict is already in `assertions` and the
        // orchestrator fails the round on it. Aborting here would skip every
        // later step (including other bots' cross-server tokens), turning one
        // missing effect into a pile of unrelated failures.
        await expectEffect(Object.assign({}, step, {
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
  const total = SELFTEST_CASES.length;
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
  runSelftest().then((ok) => {
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
