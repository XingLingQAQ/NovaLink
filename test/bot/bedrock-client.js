const crypto = require('crypto');

const PMMP_CLIENT_DATA_FIELDS = {
  ClientEditorConnectionIntent: 0,
  ClientIsEditorCapable: false,
  PartyId: '',
  IsPartyLeader: false,
  FilterProfanity: false,
  PersonaSkin: false,
  PremiumSkin: false,
  PieceTintColors: [],
};

function makeLegacySkinFields(skinId = 'bot-skin-e2e') {
  const W = 64, H = 64;
  const pixel = Buffer.from([0xb3, 0x7b, 0x62, 0xff]);
  const skinPixels = Buffer.allocUnsafe(W * H * 4);
  for (let i = 0; i < W * H; i++) {
    pixel.copy(skinPixels, i * 4);
  }
  const resourcePatchJson = JSON.stringify({
    geometry: { default: 'geometry.humanoid.custom' },
  });
  const geometryJson = JSON.stringify({
    format_version: '1.14.0',
    'minecraft:geometry': [{
      description: {
        identifier: 'geometry.humanoid.custom',
        texture_width: W, texture_height: H,
        visible_bounds_width: 2, visible_bounds_height: 2.5,
        visible_bounds_offset: [0, 0.75, 0],
      },
      bones: [],
    }],
  });
  return {
    SkinId: skinId,
    SkinData: skinPixels.toString('base64'),
    SkinImageWidth: W,
    SkinImageHeight: H,
    SkinResourcePatch: Buffer.from(resourcePatchJson, 'utf8').toString('base64'),
    SkinGeometryData: Buffer.from(geometryJson, 'utf8').toString('base64'),
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

function createBedrockClient(options, dependencies = {}) {
  const bedrock = dependencies.bedrock || require('bedrock-protocol');
  const { platform, loginPatch = true, skinId, ...connection } = options;
  const configured = { ...connection,
    skinData: { ...makeLegacySkinFields(skinId), ...PMMP_CLIENT_DATA_FIELDS, ...connection.skinData },
  };
  if (platform === 'pnx') return require('./pnx-client').createPnxClient(configured, bedrock);
  const client = bedrock.createClient(configured);
  if (loginPatch && typeof client.sendLogin === 'function') {
    const sendLogin = client.sendLogin.bind(client);
    client.sendLogin = (...args) => {
      const createChain = client.createClientChain;
      if (typeof createChain !== 'function') throw new Error('Bedrock login chain is not initialized');
      client.createClientChain = function (...chainArgs) {
        createChain.apply(this, chainArgs);
        if (!chainArgs[1] || this.options.protocolVersion < 944) return;
        const jwt = dependencies.jwt || require('jsonwebtoken');
        const name = this.username || this.options.username;
        const identity = crypto.createHash('sha1').update(name).digest('hex');
        const leguuid = `${identity.slice(0,8)}-${identity.slice(8,12)}-${identity.slice(12,16)}-${identity.slice(16,20)}-${identity.slice(20,32)}`;
        const token = jwt.sign({ cpk: this.clientX509, leguuid, xname: name,
          mid: crypto.createHash('md5').update(name).digest('hex').slice(0, 16), xid: '0',
        }, this.ecdhKeyPair.privateKey, {
          algorithm: 'ES384', notBefore: 0, issuer: 'self', expiresIn: 3600,
          audience: 'api://auth-minecraft-services/multiplayer',
          header: { x5u: this.clientX509, typ: undefined },
        });
        this.clientIdentityChain = token;
        this.multiplayerToken = token;
      };
      // sendLogin constructs the chain before serializing it.
      try { return sendLogin(...args); }
      finally { client.createClientChain = createChain; }
    };
  }
  return client;
}

module.exports = { createBedrockClient, makeLegacySkinFields };
