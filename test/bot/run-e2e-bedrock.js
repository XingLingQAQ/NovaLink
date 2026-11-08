// NovaLink E2E Layer-1 bot for Bedrock servers (bedrock-protocol, offline auth).
//
// This is the Bedrock counterpart to run-e2e.js (which uses mineflayer for Java
// editions). It drives the NovaChat /nc command set + a chat round-trip against
// any Bedrock server (BDS+Endstone, BDS+LeviLamina, PocketMine-MP, Nukkit, PNX).
//
// All paths/ports/identity are parameterised via env vars so this single script
// serves every Bedrock platform in the committed test/ harness.
//
// Env vars:
//   BOT_NAME           - bot username (default E2E_Bot_Bedrock)
//   SERVER_HOST        - bedrock server host (default 127.0.0.1)
//   SERVER_PORT        - bedrock server port (default 19132)
//   BACKEND_CHAT_PHRASE - the chat message the bot sends for the round-trip
//                        assertion (default 'hello from e2e bot')
//   PLATFORM           - platform label written into results.json (default 'bedrock')
//   MC_VERSION         - bedrock protocol version string passed to bedrock-protocol
//                        (default '1.26.40', the version the pinned Nukkit build
//                        serves; Nukkit rejects a one-revision-older client with
//                        disconnectionScreen.outdatedClient instead of negotiating)
//   RESULTS_FILE       - where to write results.json (default ./results.json)
//   TIMEOUT_MS         - hard timeout (default 360000 = 6min)
//
// Bedrock chat uses `client.queue('text', { type:'chat', category:'authored', ... })`.
// The `category:'authored'` field is CRITICAL: without it, Nukkit (and other
// Bedrock servers that read source_name then message) read the player name
// instead of the message text, so PlayerChatEvent.getMessage() returns the
// name, not the real text. Commands use `client.queue('command_request', ...)`
// (NOT text packets) -- sending `/nc help` as a text packet is treated as chat.
const bedrock = require('bedrock-protocol');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const JWT = require('jsonwebtoken');
const { createBedrockClient } = require('./bedrock-client');
const { L1Route } = require('./l1-route');
const { createL1Lifecycle } = require('./l1-lifecycle');

const HOST = process.env.SERVER_HOST || '127.0.0.1';
const PORT = parseInt(process.env.SERVER_PORT || '19132', 10);
const USERNAME = process.env.BOT_NAME || 'E2E_Bot_Bedrock';
const CHAT_PHRASE = process.env.BACKEND_CHAT_PHRASE || 'hello from e2e bot';
const PLATFORM = process.env.PLATFORM || 'bedrock';
const MC_VERSION = process.env.MC_VERSION || '1.26.40';
const RESULTS = process.env.RESULTS_FILE || path.join(__dirname, 'results.json');
const TIMEOUT_MS = parseInt(process.env.TIMEOUT_MS || '360000', 10);
const RUN_ID = process.env.E2E_RUN_ID || crypto.randomUUID();
const route = new L1Route(RUN_ID, USERNAME, RESULTS);

const received = [];
function record(kind, raw, text) {
  const entry = {
    t: new Date().toISOString(),
    kind,
    raw: String(raw || '').slice(0, 400),
    text: String(text || '').slice(0, 400),
  };
  received.push(entry);
  console.log(`[MSG ${entry.t}] ${kind}: ${entry.text}`);
}

const lifecycle = createL1Lifecycle({
  snapshot: () => ({
    runId: RUN_ID,
    platform: PLATFORM,
    host: HOST,
    port: PORT,
    minecraftVersion: MC_VERSION,
    username: USERNAME,
    chatPhrase: CHAT_PHRASE,
    received,
    route: route.evidence(),
  }),
  write: payload => fs.writeFileSync(RESULTS, JSON.stringify(payload, null, 2)),
  quit: () => { try { client.close() } catch {} },
  cleanup: () => route.close(),
  exit: code => process.exit(code),
  timeoutMs: TIMEOUT_MS,
  clock: { setTimeout, clearTimeout },
});
const sleep = lifecycle.sleep;
const failRun = lifecycle.fail;

console.log(`Connecting to ${PLATFORM} Bedrock server at ${HOST}:${PORT} as ${USERNAME} (mc ${MC_VERSION})...`);

function makeClient(username = USERNAME) {
  return createBedrockClient({
    platform: PLATFORM, loginPatch: process.env.BOT_LOGIN_PATCH !== '0',
    host: HOST, port: PORT, username, offline: true, version: MC_VERSION,
    skipPing: false, conLog: console.log,
  }, { bedrock, jwt: JWT });
}

let client = makeClient();

function attachListeners(c) {
  // Bedrock TextPacket: type can be 'chat' | 'raw' | 'translated' | 'popup' | 'jukebox_popup' | 'tip' | 'system' | 'whisper' | 'announcement'.
  c.on('text', (packet) => {
    const type = packet.type || 'unknown';
    const source = packet.source_name || '';
    const msg = packet.message || '';
    const rawStr = source ? `<${source}> ${msg}` : msg;
    record(`text:${type}`, rawStr, msg);
  });
  c.on('kick', (reason) => {
    record('kicked', JSON.stringify(reason), (reason && reason.message) || JSON.stringify(reason));
    failRun('bot was kicked');
  });
  c.on('error', (err) => {
    record('error', err && err.message, err && err.stack);
    console.log('[ERROR]', err);
    failRun(err);
  });
  c.on('close', () => {
    record('close', 'connection closed', 'connection closed');
    console.log('[CLOSE] connection closed');
    lifecycle.disconnected('connection closed before the bot quit');
  });
}

attachListeners(client);

// Bedrock TextPacket layout. `category:'authored'` makes the server read
// source_name then message so the real text reaches the chat event. Without it
// Nukkit reads the player name as the message (see memory: Nukkit/PNX).
function sendChat(message) {
  console.log(`\n>>> ${message}`);
  record('sent', message, message);
  client.queue('text', {
    type: 'chat',
    needs_translation: false,
    category: 'authored',
    source_name: client.username || USERNAME,
    xuid: '',
    platform_chat_id: '',
    filtered_message: '',
    message: message,
  });
}

// Bedrock CommandRequest. origin.type='player' for a player-issued command.
// version must be the literal 'latest'. On 1.26.x a numeric-looking value ('52')
// is rejected with a TERMINATING packet_violation_warning whose reason blames
// the command length (boilerplate); BDS enforces it, PMMP/Nukkit/PNX do not.
// player_entity_id is an unconditional li64 from 1.21.130 on, so it must stay.
function sendCommand(command) {
  const cmd = command.startsWith('/') ? command : `/${command}`;
  console.log(`\n>>> ${cmd}`);
  record('sent', cmd, cmd);
  client.queue('command_request', {
    command: cmd,
    origin: {
      type: 'player',
      uuid: crypto.randomUUID(),
      request_id: crypto.randomUUID(),
      player_entity_id: BigInt(0),
    },
    internal: false,
    version: 'latest',
  });
}

async function initializeRoute() {
  await route.start((username, recordReceiver) => {
    const receiver = makeClient(username);
    receiver.on('spawn', () => recordReceiver('spawn', 'spawned', 'spawned'));
    receiver.on('text', packet => recordReceiver(`text:${packet.type}`, packet.message, packet.message));
    receiver.on('kick', reason => recordReceiver('kicked', JSON.stringify(reason), JSON.stringify(reason)));
    receiver.on('error', error => recordReceiver('error', error.message, error.message));
    receiver.on('close', () => recordReceiver('close', 'closed', 'closed'));
    return {
      send: command => receiver.queue('command_request', {
        command, origin: { type: 'player', uuid: crypto.randomUUID(), request_id: crypto.randomUUID(), player_entity_id: 0n }, internal: false, version: 'latest',
      }),
      close: () => receiver.close(),
    };
  });
}

async function runSequence() {
  const toggleControlsMode = PLATFORM !== 'pmmp';
  console.log('=== waiting for spawn ===');
  await sleep(15000);
  console.log('=== starting L1 command sequence ===');

  // L1 baseline: help / join / toggle / chat round-trip / list.
  sendCommand('/nc help');
  await sleep(3000);
  sendCommand('/nc join global');
  await sleep(3000);
  // PMMP's toggle disables forwarding; its harness configures REPLACE directly.
  if (toggleControlsMode) sendCommand('/nc toggle');
  await sleep(2500);
  await route.exchange(sendChat);
  await sleep(1500);
  // The round-trip assertion target: bot sends CHAT_PHRASE -> plugin intercepts
  // -> backend routes -> plugin receives -> bot renders the formatted message
  // containing CHAT_PHRASE.
  sendChat(CHAT_PHRASE);
  await sleep(4000);
  if (toggleControlsMode) {
    sendCommand('/nc toggle');
  } else {
    sendCommand('/nc toggle');
    await sleep(500);
    sendCommand('/nc toggle');
  }
  await sleep(2500);
  sendCommand('/nc list');
  await sleep(2500);
  sendCommand('/nc who');
  await sleep(2500);

  // Mode-switch platforms are back in HYBRID; PMMP forwarding is enabled again.
  sendChat('vanilla chat preserved e2e');
  await sleep(3000);

  // Error paths (these exercise command surface; responses are recorded).
  sendCommand('/nc leave global');
  await sleep(2500);
  sendCommand('/nc leave global');
  await sleep(2500);
  sendCommand('/nc reload');
  await sleep(2500);

  console.log('=== sequence done ===');
}

client.once('spawn', () => {
  console.log('[SPAWN] bot spawned as Player');
  record('spawn', 'spawned', 'spawned');
  void lifecycle.run(initializeRoute, runSequence);
});

module.exports = lifecycle;
