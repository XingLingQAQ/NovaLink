// NovaLink E2E Layer-1 bot: real MC client (mineflayer, offline auth) driving the
// NovaChat /nc command set + chat round-trip against a local Minecraft server.
//
// This is a port of .e2e/bot/run-e2e.js with all paths/ports parameterised via
// environment variables so it runs on any CI runner (not just D:\Project\NovaLink).
//
// Env vars:
//   E2E_MC_HOST       - Minecraft server host (default 127.0.0.1)
//   E2E_MC_PORT       - Minecraft server port (default 25565)
//   E2E_MC_VERSION    - mineflayer protocol version (default 1.21.8)
//   E2E_BOT_USERNAME  - bot username (default E2E_Bot_Alpha)
//   E2E_RESULTS_FILE  - where to write results.json (default ./results.json)
//   E2E_TIMEOUT_MS    - hard timeout (default 360000 = 6min)
const mineflayer = require('mineflayer');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { L1Route } = require('./l1-route');
const { createL1Lifecycle } = require('./l1-lifecycle');

const HOST = process.env.E2E_MC_HOST || '127.0.0.1';
const PORT = parseInt(process.env.E2E_MC_PORT || '25565', 10);
const MC_VERSION = process.env.E2E_MC_VERSION || '1.21.8';
const USERNAME = process.env.E2E_BOT_USERNAME || 'E2E_Bot_Alpha';
const RESULTS = process.env.E2E_RESULTS_FILE || path.join(__dirname, 'results.json');
const TIMEOUT_MS = parseInt(process.env.E2E_TIMEOUT_MS || '360000', 10);
const RUN_ID = process.env.E2E_RUN_ID || crypto.randomUUID();
const route = new L1Route(RUN_ID, USERNAME, RESULTS);

const received = [];
function record(kind, raw, text) {
  const entry = { t: new Date().toISOString(), kind, raw: String(raw).slice(0, 400), text: String(text || '').slice(0, 400) };
  received.push(entry);
  console.log(`[MSG ${entry.t}] ${kind}: ${entry.text}`);
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

const bot = mineflayer.createBot({
  host: HOST,
  port: PORT,
  username: USERNAME,
  auth: 'offline',
  version: MC_VERSION,
  hideErrors: false,
});


bot.on('messagestr', (msg, position, jsonMsg, sender, verified) => record('messagestr', msg, msg));
bot.on('chat', (username, message) => record('chat', `<${username}> ${message}`, `<${username}> ${message}`));
bot.on('systemChat', (msg, n) => record('systemChat', msg, plainText(msg)));
bot.on('kicked', (reason) => { record('kicked', reason, plainText(reason)); failRun('bot was kicked'); });
bot.on('error', (err) => { record('error', err && err.message, err && err.stack); failRun(err); });
bot.on('end', () => {
  record('end', 'connection ended', 'connection ended');
  lifecycle.disconnected('connection ended before the bot quit');
});

function sendCmd(cmd) {
  console.log(`\n>>> ${cmd}`);
  record('sent', cmd, cmd);
  bot.chat(cmd);
}

const lifecycle = createL1Lifecycle({
  snapshot: () => ({
    runId: RUN_ID,
    platform: process.env.E2E_PLATFORM || 'bukkit',
    server: process.env.E2E_SERVER_ID || 'purpur',
    minecraftVersion: MC_VERSION,
    username: USERNAME,
    host: HOST,
    port: PORT,
    received,
    route: route.evidence(),
  }),
  write: payload => fs.writeFileSync(RESULTS, JSON.stringify(payload, null, 2)),
  quit: () => { try { bot.quit('e2e done') } catch {} },
  cleanup: () => route.close(),
  exit: code => process.exit(code),
  timeoutMs: TIMEOUT_MS,
  clock: { setTimeout, clearTimeout },
});
const sleep = lifecycle.sleep;
const failRun = lifecycle.fail;

async function initializeRoute() {
  await route.start((username, recordReceiver) => {
    const receiver = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline', version: MC_VERSION });
    receiver.on('spawn', () => recordReceiver('spawn', 'spawned', 'spawned'));
    receiver.on('messagestr', message => recordReceiver('messagestr', message, message));
    receiver.on('kicked', reason => recordReceiver('kicked', reason, plainText(reason)));
    receiver.on('error', error => recordReceiver('error', error.message, error.message));
    receiver.on('end', () => recordReceiver('end', 'ended', 'ended'));
    return { send: command => receiver.chat(command), close: () => receiver.quit('e2e route done') };
  });
}

async function runSequence() {
  console.log('=== waiting for spawn ===');
  await sleep(15000);
  console.log('=== starting L1 command sequence ===');

  // L1 baseline: help / join / toggle / REPLACE chat / list / who
  sendCmd('/nc help');
  await sleep(2500);
  sendCmd('/nc join global');
  await sleep(3000);
  // ONE toggle reaches REPLACE; the second toggle this used to send returned
  // the player to HYBRID, where ChatInterceptor.onPlayerChat bails out
  // ("allowing vanilla chat") and the phrase below was never forwarded. The
  // line was therefore only ever echoed back by vanilla and could not exercise
  // the round-trip. Toggle back to HYBRID after it so the hybrid section that
  // follows still runs in the mode it names.
  sendCmd('/nc toggle');
  await sleep(2500);
  await route.exchange(sendCmd);
  await sleep(1500);
  sendCmd('hello from e2e bot (replace mode)');
  await sleep(3000);
  sendCmd('/nc toggle');
  await sleep(2500);
  sendCmd('/nc list');
  await sleep(2500);
  sendCmd('/nc who');
  await sleep(2500);

  // HYBRID mode: channel-routed + vanilla preserved
  sendCmd('/nc global hello via hybrid from e2e');
  await sleep(3000);
  sendCmd('vanilla chat preserved e2e');
  await sleep(3000);

  // Private channel lifecycle
  sendCmd('/nc create e2etest hunter2');
  await sleep(3000);
  sendCmd('/nc join e2etest hunter2');
  await sleep(3000);
  sendCmd('private channel hello');
  await sleep(3000);
  sendCmd('/nc leave e2etest');
  await sleep(3000);

  // Error paths
  sendCmd('/nc invite NonExistentTarget e2etest');
  await sleep(3000);
  sendCmd('/nc accept BOGUS1');
  await sleep(3000);
  sendCmd('hey @NonExistentTarget you there');
  await sleep(3000);
  sendCmd('/nc reload');
  await sleep(3000);
  sendCmd('/nc announce global e2e broadcast');
  await sleep(3000);
  sendCmd('/nc title global E2ETitle E2ESubtitle');
  await sleep(3000);
  sendCmd('/nc leave global');
  await sleep(3000);
  sendCmd('/nc leave global');
  await sleep(3000);

  console.log('=== sequence done ===');
}

bot.once('spawn', () => {
  console.log('[SPAWN] bot spawned as Player');
  record('spawn', 'spawned', 'spawned');
  void lifecycle.run(initializeRoute, runSequence);
});

module.exports = lifecycle;
