'use strict';

const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const path = require('node:path');
const test = require('node:test');
const {
  MENTION_SOUND,
  decodeJavaSoundPacket,
  attachJavaSoundObserver,
  getMentionSoundSteps,
} = require('./sound-observer');

const modulePaths = [path.resolve(__dirname, '../../bot')];
if (process.env.JOINT_BOT_MODULES) modulePaths.unshift(process.env.JOINT_BOT_MODULES);
const protocol = require(require.resolve('minecraft-protocol', { paths: modulePaths }));
const minecraftData = require(require.resolve('minecraft-data', { paths: modulePaths }));
const registry = minecraftData('1.21.11');
const orbId = registry.soundsByName['entity.experience_orb.pickup'].id;

const entityPacket = {
  sound: { data: { soundName: 'entity.experience_orb.pickup' } },
  soundCategory: 'player',
  entityId: 42,
  volume: 0.5,
  pitch: 1.25,
  seed: [0, 123],
};

function roundTrip(name, params, version = '1.21.11') {
  const serializer = protocol.createSerializer({ state: 'play', isServer: true, version });
  const deserializer = protocol.createDeserializer({ state: 'play', isServer: false, version });
  return deserializer.parsePacketBuffer(serializer.createPacketBuffer({ name, params })).data;
}

function decodeVelocityInlineSound() {
  // minecraft-protocol 1.68.0's inline writer leaves the holder tag uninitialized.
  const wire = Buffer.from(
    '72001c656e746974792e657870657269656e63655f6f72622e7069636b7570' +
    '00072a3f0000003fa00000000000000000007b', 'hex');
  const deserializer = protocol.createDeserializer({ state: 'play', isServer: false, version: '1.21.11' });
  const decoded = deserializer.parsePacketBuffer(wire);
  assert.equal(decoded.metadata.size, wire.length);
  assert.equal(decoded.data.name, 'entity_sound_effect');
  assert.deepEqual(decoded.data.params.sound, {
    data: { soundName: 'entity.experience_orb.pickup', fixedRange: undefined },
  });
  return decoded.data;
}

test('Velocity inline entity sound bytes decode through the real 1.21.11 packet codec', () => {
  const decoded = decodeVelocityInlineSound();
  const effect = decodeJavaSoundPacket(decoded.name, decoded.params, registry);

  assert.equal(effect.kind, 'sound');
  assert.equal(effect.data.sound, MENTION_SOUND);
  assert.equal(effect.data.soundId, undefined);
  assert.equal(effect.data.entityId, 42);
  assert.equal(effect.data.source, 'entity_sound_effect');
  assert.equal(effect.data.soundCategory, 'player');
  assert.equal(effect.data.volume, 0.5);
  assert.equal(effect.data.pitch, 1.25);
});

test('registry holders resolve entity sounds without a second ID adjustment', () => {
  const decoded = roundTrip('entity_sound_effect', {
    ...entityPacket, sound: { soundId: orbId },
  });
  const effect = decodeJavaSoundPacket(decoded.name, decoded.params, registry);

  assert.equal(decoded.params.sound.soundId, orbId);
  assert.equal(effect.kind, 'sound');
  assert.equal(effect.data.sound, MENTION_SOUND);
  assert.equal(effect.data.soundId, orbId);
});

test('coordinate sound packets preserve names and convert fixed point positions', () => {
  const decoded = roundTrip('sound_effect', {
    ...entityPacket, sound: { soundId: orbId }, x: 8, y: 512, z: -24,
  });
  const effect = decodeJavaSoundPacket(decoded.name, decoded.params, registry);

  assert.equal(effect.data.sound, MENTION_SOUND);
  assert.equal(effect.data.position, '1,64,-3');
});

test('legacy named sound packets keep resource dots and namespaces', () => {
  const decoded = roundTrip('named_sound_effect', {
    soundName: MENTION_SOUND, soundCategory: 7,
    x: 8, y: 512, z: -24, volume: 1, pitch: 1,
  }, '1.18.2');
  const effect = decodeJavaSoundPacket(decoded.name, decoded.params, registry);

  assert.equal(effect.data.sound, MENTION_SOUND);
  assert.equal(effect.data.position, '1,64,-3');
});

test('legacy numeric sounds use the supplied version registry', () => {
  const oldRegistry = minecraftData('1.18.2');
  const oldId = oldRegistry.soundsByName['entity.experience_orb.pickup'].id;
  const decoded = roundTrip('sound_effect', {
    soundId: oldId, soundCategory: 7,
    x: 0, y: 0, z: 0, volume: 1, pitch: 1, seed: [0, 123],
  }, '1.18.2');

  assert.equal(decodeJavaSoundPacket(decoded.name, decoded.params, oldRegistry).data.sound, MENTION_SOUND);
});

test('decoded registry ID zero is a reference rather than an inline holder', () => {
  const effect = decodeJavaSoundPacket('entity_sound_effect', {
    ...entityPacket, sound: { soundId: 0 },
  }, { sounds: [{ name: 'entity.experience_orb.pickup' }] });

  assert.equal(effect.data.sound, MENTION_SOUND);
  assert.equal(effect.data.soundId, 0);
});

test('unresolved IDs remain diagnostics and cannot satisfy a sound expectation', () => {
  const effect = decodeJavaSoundPacket('entity_sound_effect', {
    ...entityPacket, sound: { soundId: 1234567 },
  }, registry);

  assert.equal(effect.kind, 'soundId');
  assert.equal(effect.data.soundId, 1234567);
  assert.equal(effect.data.sound, undefined);
  assert.ok(!effect.text.includes('experience_orb'));
});

test('unrelated, stopped and malformed packets never become sounds', () => {
  assert.equal(decodeJavaSoundPacket('stop_sound', { sound: MENTION_SOUND }, registry), null);
  assert.equal(decodeJavaSoundPacket('keep_alive', entityPacket, registry), null);
  assert.equal(decodeJavaSoundPacket('entity_sound_effect', {}, registry), null);
  assert.equal(decodeJavaSoundPacket('entity_sound_effect', null, registry), null);
});

test('one decoded packet produces one sound row and retains its raw probe', () => {
  const client = new EventEmitter();
  const bot = new EventEmitter();
  bot._client = client;
  bot.registry = registry;
  const recorded = [];
  const detach = attachJavaSoundObserver(bot, (kind, text, data) => recorded.push({ kind, text, data }));

  const decoded = decodeVelocityInlineSound();
  client.emit('packet', decoded.params, { name: decoded.name });
  client.emit(decoded.name, decoded.params);
  bot.emit('soundEffectHeard', MENTION_SOUND);
  client.emit('raw', Buffer.from([1, 2, 3]), { name: decoded.name });

  assert.deepEqual(recorded.map(row => row.kind), ['sound', 'rawSoundPacket']);
  assert.equal(recorded[0].data.sound, MENTION_SOUND);
  assert.equal(recorded[1].data.bytes, 3);
  detach();
  assert.equal(client.listenerCount('packet'), 0);
  assert.equal(client.listenerCount('raw'), 0);
});

test('the listener reads the bot registry at receipt time', () => {
  const bot = { _client: new EventEmitter(), registry: {} };
  const recorded = [];
  attachJavaSoundObserver(bot, (kind, text, data) => recorded.push({ kind, text, data }));
  bot.registry = registry;
  bot._client.emit('packet', { ...entityPacket, sound: { soundId: orbId } }, { name: 'entity_sound_effect' });

  assert.equal(recorded[0].data.sound, MENTION_SOUND);
});

test('the core mention step gates on the exact sound rather than raw packets or other orb sounds', () => {
  const [step] = getMentionSoundSteps();
  assert.equal(step.id, 'expect-mention-sound');
  assert.equal(step.action, 'expectEvent');
  assert.equal(step.kind, 'sound');
  assert.notEqual(step.diagnostic, true);
  const expected = decodeJavaSoundPacket('entity_sound_effect', entityPacket, registry);
  const other = decodeJavaSoundPacket('entity_sound_effect', {
    ...entityPacket, sound: { data: { soundName: 'entity.experience_orb.pickup_extra' } },
  }, registry);
  assert.ok(expected.text.includes(step.match));
  assert.ok(!other.text.includes(step.match));
  assert.deepEqual(getMentionSoundSteps({ id: 'hear-orb', timeoutMs: 12000 }), [{
    ...step, id: 'hear-orb', timeoutMs: 12000,
  }]);
});
