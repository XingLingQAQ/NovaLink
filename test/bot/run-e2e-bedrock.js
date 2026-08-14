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
//                        (default '1.26.30'; BDS/PocketMine 1.20+ all negotiate this)
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

const HOST = process.env.SERVER_HOST || '127.0.0.1';
const PORT = parseInt(process.env.SERVER_PORT || '19132', 10);
const USERNAME = process.env.BOT_NAME || 'E2E_Bot_Bedrock';
const CHAT_PHRASE = process.env.BACKEND_CHAT_PHRASE || 'hello from e2e bot';
const PLATFORM = process.env.PLATFORM || 'bedrock';
const MC_VERSION = process.env.MC_VERSION || '1.26.30';
const RESULTS = process.env.RESULTS_FILE || path.join(__dirname, 'results.json');
const TIMEOUT_MS = parseInt(process.env.TIMEOUT_MS || '360000', 10);

// ---------------------------------------------------------------------------
// Login JWT patch for PMMP 5.44+ (Bedrock protocol 944+ / OIDC offline login).
//
// bedrock-protocol's OIDC offline login (login.js:19-24) signs a JWT body of
// {cpk, xid, xname, identity}. PMMP 5.44+ validates this against its
// SelfSignedJwtBody DTO which REQUIRES {cpk, leguuid, xname, mid} and rejects
// the `identity` field as "Unexpected JSON property". Without this patch PMMP
// kicks the bot at login with:
//   "Required property 'leguuid' of class SelfSignedJwtBody is missing"
//   "Unexpected JSON property for SelfSignedJwtBody: identity"
//
// We monkeypatch client.createClientChain (set by bedrock-protocol's
// src/handshake/login.js) to sign a body PMMP accepts: {cpk, leguuid, xname,
// mid, xid}. BDS/Endstone/LeviLamina/Nukkit accept the same body (they use
// looser login validation), so the patch is safe to always apply. The fields
// map onto PMMP's SelfSignedJwtBody @required set:
//   cpk     <- client.clientX509 (the bot's self-signed x509 public key)
//   leguuid <- a stable UUID for the bot (derived from username so it persists)
//   xname   <- the bot username
//   mid     <- a PlayFab-style Minecraft ID (16 hex chars)
//   xid      <- '0' (offline, no Xbox XUID)
// The JWT options MUST also include audience:'api://auth-minecraft-services/multiplayer'
// (mirroring login.js:23) -- PMMP 5.44 validates the `aud` claim and kicks with
// "Invalid session. Reason: Invalid JWT audience" if it is absent.
// ---------------------------------------------------------------------------
const LOGIN_PATCH = process.env.BOT_LOGIN_PATCH !== '0'; // default ON; set to '0' to disable

function applyLoginPatch(client) {
  if (!LOGIN_PATCH) return;
  if (typeof client.createClientChain !== 'function') return;
  const orig = client.createClientChain.bind(client);
  client.createClientChain = (mojangKey, offline) => {
    // Call the original so clientIdentityChain / clientUserChain get populated
    // for the non-OIDC path and the user chain (skin etc.).
    orig(mojangKey, offline);
    // Re-sign the identity chain (clientIdentityChain / multiplayerToken) with
    // a body PMMP accepts. This mirrors login.js lines 16-24 but swaps the
    // payload fields. We can only do this if the ECDH key pair is set up.
    if (offline && client.ecdhKeyPair && client.ecdhKeyPair.privateKey) {
      const algorithm = 'ES384';
      const botUuid = crypto.createHash('sha1').update(USERNAME).digest('hex');
      const leguuid = `${botUuid.slice(0,8)}-${botUuid.slice(8,12)}-${botUuid.slice(12,16)}-${botUuid.slice(16,20)}-${botUuid.slice(20,32)}`;
      const mid = crypto.createHash('md5').update(USERNAME).digest('hex').slice(0, 16);
      const token = JWT.sign({
        cpk: client.clientX509,
        leguuid,
        xname: USERNAME,
        mid,
        xid: '0',
      }, client.ecdhKeyPair.privateKey, {
        algorithm, notBefore: 0, issuer: 'self', expiresIn: 60 * 60,
        audience: 'api://auth-minecraft-services/multiplayer',
        header: { x5u: client.clientX509, typ: undefined },
      });
      client.clientIdentityChain = token;
      client.multiplayerToken = token;
    }
  };
}

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

function writeResults(extra) {
  const payload = Object.assign({
    platform: PLATFORM,
    host: HOST,
    port: PORT,
    minecraftVersion: MC_VERSION,
    username: USERNAME,
    chatPhrase: CHAT_PHRASE,
    received,
  }, extra || {});
  fs.writeFileSync(RESULTS, JSON.stringify(payload, null, 2));
  console.log('results.json written:', RESULTS);
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

console.log(`Connecting to ${PLATFORM} Bedrock server at ${HOST}:${PORT} as ${USERNAME} (mc ${MC_VERSION})...`);

// Bedrock ClientData (user-chain) fields PMMP 5.44+ requires that
// bedrock-protocol 3.58.1's login.js does NOT set (it only builds a partial
// ClientData payload). Without these, PMMP kicks the bot at login with:
//   "Required property 'ClientEditorConnectionIntent' of class ClientData is missing"
//   (and PartyId / IsPartyLeader / FilterProfanity / PersonaSkin / PremiumSkin /
//    PieceTintColors / ClientIsEditorCapable, all @required in PMMP's ClientData DTO)
//
// login.js:87-88 spreads `options.skinData` into the ClientData JWT payload, so we
// inject the missing fields via that documented hook. BDS/Endstone/LeviLamina/
// Nukkit/PNX all accept the same extra fields (they validate ClientData loosely),
// so this is safe to always apply. Values mirror what a vanilla Bedrock 1.26.x
// client sends for a non-editor, non-party, non-persona skin session.
const PMMP_CLIENT_DATA_FIELDS = {
  ClientEditorConnectionIntent: 0, // int: 0 = not joining editor
  ClientIsEditorCapable: false,    // bool
  PartyId: '',                     // string: empty = not in a party
  IsPartyLeader: false,            // bool
  FilterProfanity: false,          // bool
  PersonaSkin: false,              // bool: not a persona skin
  PremiumSkin: false,              // bool: not a premium skin
  PieceTintColors: [],             // array: no persona piece tints
};

// ---------------------------------------------------------------------------
// Valid legacy Bedrock skin.
//
// bedrock-protocol's login.js:7 does `require('minecraft-data')('bedrock_'+ver).defaultSkin`
// and spreads it into the ClientData JWT payload (login.js:51). The installed
// minecraft-data 3.113.1 ships a defaultSkin, but it is a 256x256 PERSONA skin
// (SkinData = 262144 raw RGBA bytes, PersonaSkin:true, PersonaPieces referencing
// marketplace pack IDs). PMMP 5.44's Skin::__construct() only accepts legacy
// sizes {8192, 16384, 65536} (Skin::ACCEPTED_SKIN_SIZES) and rejects 262144 with:
//   "Invalid skin data size 262144 bytes (allowed sizes: 8192, 16384, 65536)"
// (LegacySkinAdapter::fromSkinData also branches on isPersona(): true returns a
// random Standard_Custom skin, but the persona bool + invalid size still trips
// the size check first). BDS/Endstone/LeviLamina/Nukkit validate loosely and
// accept the persona skin, so the kick is PMMP-specific.
//
// Fix: override the skin fields with a minimal VALID legacy 64x64 skin. PMMP's
// LegacySkinAdapter::fromSkinData (non-persona branch) needs:
//   - SkinData: raw RGBA bytes, length in {8192,16384,65536} (we use 16384 = 64x64x4)
//   - SkinImageWidth/SkinImageHeight matching the data (64x64)
//   - SkinResourcePatch: JSON with geometry.default (we use geometry.humanoid.custom,
//     the vanilla legacy humanoid geometry name -- no SkinGeometryData needed)
//   - SkinId: non-empty (<= INT16_MAX)
//   - PersonaSkin: false (take the legacy path, not the persona path)
//   - CapeData: "" (empty; PMMP only validates cape length if non-empty)
//   - SkinGeometryData: "" (empty; PMMP only JSON-validates it if non-empty)
// We generate the 64x64x4 solid-color RGBA buffer in-memory with pure Node
// Buffer (no new deps). SkinData is raw RGBA pixels, NOT a PNG -- PMMP validates
// the raw byte length against ACCEPTED_SKIN_SIZES, not image format.
// ---------------------------------------------------------------------------
function makeLegacySkinFields() {
  const W = 64, H = 64;
  // 4 bytes RGBA per pixel, solid "Steve-ish" tan (#b37b62 from vanilla), full alpha.
  const pixel = Buffer.from([0xb3, 0x7b, 0x62, 0xff]);
  const skinPixels = Buffer.allocUnsafe(W * H * 4);
  for (let i = 0; i < W * H; i++) {
    pixel.copy(skinPixels, i * 4);
  }
  // Bedrock skin string fields in the ClientData JWT (SkinResourcePatch,
  // SkinGeometryData, SkinAnimationData, CapeData, SkinData) are base64-encoded.
  // PMMP base64-decodes SkinResourcePatch before json_decode and rejects a plain
  // JSON string with "SkinResourcePatch: Malformed base64, cannot be decoded".
  // The minecraft-data defaultSkin encodes these the same way (base64-of-JSON).
  // Empty strings are valid for CapeData/SkinAnimationData/SkinGeometryData (PMMP
  // only validates them when non-empty), so they stay "" (empty base64 = no data).
  const resourcePatchJson = JSON.stringify({
    geometry: { default: 'geometry.humanoid.custom' },
  });
  return {
    SkinId: 'bot-skin-e2e',
    SkinData: skinPixels.toString('base64'),
    SkinImageWidth: W,
    SkinImageHeight: H,
    SkinResourcePatch: Buffer.from(resourcePatchJson, 'utf8').toString('base64'),
    SkinGeometryData: '',
    SkinAnimationData: '',
    CapeData: '',
    CapeImageWidth: 0,
    CapeImageHeight: 0,
    CapeOnClassicSkin: false,
    CapeId: '',
    ArmSize: 'wide',
    SkinColor: '#ffb37b62',
    PersonaPieces: [],
    AnimatedImageData: [],
    PersonaSkin: false,
    PremiumSkin: false,
    SkinGeometryDataEngineVersion: '',
  };
}

const SKIN_FIELDS = makeLegacySkinFields();

function makeClient() {
  // skinData is spread into the ClientData JWT payload AFTER minecraft-data's
  // defaultSkin (login.js:51 then :87-88), so it overrides the 256x256 persona
  // defaultSkin that PMMP rejects. Merge the legacy skin fields (SkinData/
  // SkinResourcePatch/etc.) with the PMMP-required ClientData bool/string fields.
  const skinData = Object.assign({}, SKIN_FIELDS, PMMP_CLIENT_DATA_FIELDS);
  const c = bedrock.createClient({
    host: HOST,
    port: PORT,
    username: USERNAME,
    offline: true,
    version: MC_VERSION,
    skipPing: false,
    conLog: console.log,
    skinData,
  });
  // Wrap sendLogin so our JWT patch (leguuid/mid instead of identity) is applied
  // to the identity chain right before bedrock-protocol signs + sends it.
  // sendLogin() calls this.createClientChain() which populates
  // clientIdentityChain + multiplayerToken; our applyLoginPatch re-signs them.
  if (LOGIN_PATCH && typeof c.sendLogin === 'function') {
    const origSendLogin = c.sendLogin.bind(c);
    c.sendLogin = (...args) => {
      try { applyLoginPatch(c); } catch (e) { console.log('[LOGIN_PATCH warn]', e && e.message); }
      return origSendLogin(...args);
    };
  }
  return c;
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
  });
  c.on('error', (err) => {
    record('error', err && err.message, err && err.stack);
    console.log('[ERROR]', err);
  });
  c.on('close', () => {
    record('close', 'connection closed', 'connection closed');
    console.log('[CLOSE] connection closed');
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
// version='52' matches the command protocol version bedrock-protocol negotiates
// for 1.26.x; internal:false means it is a user-typed command (not scripting).
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
    version: '52',
  });
}

async function runSequence() {
  console.log('=== waiting for spawn ===');
  await sleep(15000);
  console.log('=== starting L1 command sequence ===');

  // L1 baseline: help / join / toggle / chat round-trip / list.
  sendCommand('/nc help');
  await sleep(3000);
  sendCommand('/nc join global');
  await sleep(3000);
  sendCommand('/nc toggle');
  await sleep(2500);
  sendCommand('/nc toggle');
  await sleep(2500);
  // The round-trip assertion target: bot sends CHAT_PHRASE -> plugin intercepts
  // -> backend routes -> plugin receives -> bot renders the formatted message
  // containing CHAT_PHRASE.
  sendChat(CHAT_PHRASE);
  await sleep(4000);
  sendCommand('/nc list');
  await sleep(2500);
  sendCommand('/nc who');
  await sleep(2500);

  // HYBRID mode: channel-routed + vanilla preserved.
  sendCommand('/nc toggle');
  await sleep(2500);
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
  writeResults();
  await sleep(1500);
  try { client.close(); } catch {}
  await sleep(1000);
  process.exit(0);
}

client.once('spawn', () => {
  console.log('[SPAWN] bot spawned as Player');
  record('spawn', 'spawned', 'spawned');
  runSequence().catch(e => {
    console.log('[SEQERR]', e);
    writeResults({ error: String(e) });
    process.exit(1);
  });
});

setTimeout(() => {
  console.log('[TIMEOUT] reached, flushing results and exiting');
  writeResults({ timedOut: true });
  try { client.close(); } catch {}
  process.exit(2);
}, TIMEOUT_MS);
