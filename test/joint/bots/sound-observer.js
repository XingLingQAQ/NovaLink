'use strict';

const MENTION_SOUND = 'minecraft:entity.experience_orb.pickup';
const SOUND_PACKETS = new Set(['sound_effect', 'named_sound_effect', 'entity_sound_effect']);

function resourceName(name) {
  if (typeof name !== 'string' || name.length === 0) return null;
  return name.includes(':') ? name : `minecraft:${name}`;
}

function decodeJavaSoundPacket(packetName, packet, registry) {
  if (!SOUND_PACKETS.has(packetName) || !packet) return null;

  let soundName;
  let soundId;
  if (packetName === 'named_sound_effect') {
    soundName = packet.soundName;
  } else if (packet.sound) {
    soundName = packet.sound.data && packet.sound.data.soundName;
    soundId = packet.sound.soundId;
  } else {
    soundId = packet.soundId;
  }

  const hasId = Number.isInteger(soundId) && soundId >= 0;
  if (!soundName && hasId) {
    // minecraft-protocol already removed the holder's wire offset.
    soundName = registry && registry.sounds && registry.sounds[soundId]?.name;
  }
  const sound = resourceName(soundName);
  if (!sound && !hasId) return null;

  const data = {
    source: packetName,
    soundCategory: packet.soundCategory,
    volume: packet.volume,
    pitch: packet.pitch,
  };
  let location;
  if (packetName === 'entity_sound_effect') {
    data.entityId = packet.entityId;
    location = `entity:${packet.entityId}`;
  } else {
    data.position = `${packet.x / 8},${packet.y / 8},${packet.z / 8}`;
    location = data.position;
  }
  if (hasId) data.soundId = soundId;
  if (sound) data.sound = sound;

  return {
    kind: sound ? 'sound' : 'soundId',
    text: `${sound || `id=${soundId}`} @ ${location} vol=${packet.volume} pitch=${packet.pitch}`,
    data,
  };
}

function attachJavaSoundObserver(bot, recordEffect) {
  const client = bot._client;
  const onPacket = (packet, meta) => {
    const effect = decodeJavaSoundPacket(meta && meta.name, packet, bot.registry);
    if (effect) recordEffect(effect.kind, effect.text, effect.data);
  };
  const onRaw = (buffer, meta) => {
    if (!meta || !/sound/i.test(meta.name || '')) return;
    recordEffect('rawSoundPacket', meta.name, {
      name: meta.name, bytes: buffer ? buffer.length : 0,
    });
  };
  client.on('packet', onPacket);
  client.on('raw', onRaw);
  return () => {
    client.removeListener('packet', onPacket);
    client.removeListener('raw', onRaw);
  };
}

function getMentionSoundSteps({ id = 'expect-mention-sound', timeoutMs = 8000 } = {}) {
  return [{
    id,
    action: 'expectEvent',
    kind: 'sound',
    match: `${MENTION_SOUND} @ `,
    timeoutMs,
  }];
}

module.exports = {
  MENTION_SOUND,
  decodeJavaSoundPacket,
  attachJavaSoundObserver,
  getMentionSoundSteps,
};
