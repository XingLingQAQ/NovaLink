const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const jwt = require('jsonwebtoken');
const { createBedrockClient, makeLegacySkinFields } = require('./bedrock-client');

test('OIDC login patch is serialized after the library builds its chain', () => {
  const keys = crypto.generateKeyPairSync('ec', { namedCurve: 'secp384r1',
    publicKeyEncoding: { type: 'spki', format: 'pem' }, privateKeyEncoding: { type: 'pkcs8', format: 'pem' } });
  let sent, options;
  const client = createBedrockClient({ username: 'SharedBot', offline: true }, { jwt,
    bedrock: { createClient: config => {
      options = config;
      return { username: config.username, options: { ...config, protocolVersion: 944 },
        ecdhKeyPair: { privateKey: keys.privateKey }, clientX509: keys.publicKey,
        createClientChain() { this.multiplayerToken = 'library-original'; },
        sendLogin() { this.createClientChain(null, true); sent = this.multiplayerToken; },
      };
    } },
  });
  const original = client.createClientChain;
  for (let i = 0; i < 2; i++) {
    client.sendLogin();
    const claims = jwt.verify(sent, keys.publicKey, { algorithms: ['ES384'], audience: 'api://auth-minecraft-services/multiplayer' });
    assert.equal(claims.xname, 'SharedBot'); assert.equal(claims.xid, '0');
    assert.ok(claims.leguuid); assert.ok(claims.mid); assert.equal(claims.identity, undefined);
    assert.equal(client.createClientChain, original);
  }
  assert.equal(options.skinData.ClientEditorConnectionIntent, 0);
  assert.equal(options.skinData.PersonaSkin, false);
});

test('BOT_LOGIN_PATCH opt-out and legacy protocol retain the library chain', () => {
  for (const [loginPatch, protocolVersion] of [[false, 944], [true, 900]]) {
    let sent;
    const client = createBedrockClient({ username: 'Bot', loginPatch }, { bedrock: {
      createClient: () => ({ options: { protocolVersion }, createClientChain() { this.multiplayerToken = 'original'; },
        sendLogin() { this.createClientChain(null, true); sent = this.multiplayerToken; } }),
    } });
    client.sendLogin();
    assert.equal(sent, 'original');
  }
});

test('shared skin contains valid legacy pixels and matching JSON geometry', () => {
  const skin = makeLegacySkinFields('bote2e');
  assert.equal(skin.SkinId, 'bote2e');
  assert.equal(Buffer.from(skin.SkinData, 'base64').length, 64 * 64 * 4);
  const patch = JSON.parse(Buffer.from(skin.SkinResourcePatch, 'base64'));
  const geometry = JSON.parse(Buffer.from(skin.SkinGeometryData, 'base64'));
  assert.equal(patch.geometry.default, geometry['minecraft:geometry'][0].description.identifier);
});
