const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { createRequire } = require('node:module');
const path = require('node:path');
const { Session, followTransfer } = require('./transfer-driver');

const botRequire = createRequire(path.resolve(__dirname, '../bot/package.json'));
const { createProtocol } = botRequire('bedrock-protocol/src/transforms/serializer');
const protocol = createProtocol('1.26.60');
const a = { id: 'a', host: '127.0.0.1', port: 19362 };
const b = { id: 'b', host: '127.0.0.1', port: 19363 };

function wireTransfer(port = b.port, host = b.host) {
  const buffer = protocol.createPacketBuffer('mcpe_packet', { name: 'transfer', params: {
    server_address: host, port, reload_world: true, gatherings_configuration: undefined,
  } });
  const packet = protocol.parsePacketBuffer('mcpe_packet', buffer).data;
  assert.equal(packet.name, 'transfer');
  return packet.params;
}

function fixture(mode = 'spawn') {
  const events = [];
  const sessions = [];
  const create = (node, name) => {
    const session = new Session({ node, name, timeoutMs: 50, record: row => events.push(row),
      createClient: () => {
        const client = new EventEmitter();
        client.disconnect = () => client.emit('close');
        queueMicrotask(() => {
          if (node === b && mode === 'close') client.emit('close');
          else if (node !== b || mode === 'spawn') {
            client.emit('start_game', { runtime_entity_id: 17n });
            client.emit('spawn');
          }
        });
        return client;
      } });
    sessions.push(session);
    return session;
  };
  return { events, sessions, create };
}

test('locked native TransferPacket contract (synthetic; no server evidence)', async t => {
  assert.equal(botRequire('bedrock-protocol/package.json').version, '3.61.0');
  assert.equal(botRequire('./package-lock.json').packages['node_modules/bedrock-protocol'].version, '3.61.0');
  await t.test('only a decoded matching transfer authorizes one new connection and fresh spawn', async () => {
    const f = fixture();
    const source = await f.create(a, 'Traveler').open();
    const moved = await followTransfer(source, b, async () => source.client.emit('transfer', wireTransfer()), f.create);
    assert.equal(f.sessions.length, 2);
    assert.notEqual(moved.next.id, source.id);
    assert.equal(moved.transfer.kind, 'transfer');
    assert.equal(moved.transfer.data.reload_world, true);
    assert.equal(moved.next.spawn.connectionId, moved.next.id);
    assert.ok(f.events.indexOf(moved.transfer) < f.events.indexOf(moved.next.spawn));
    moved.next.close();
  });
  for (const [name, issue, message] of [
    ['missing packet', () => {}, /native transfer packet/],
    ['wrong port', source => source.client.emit('transfer', wireTransfer(19364)), /Wrong transfer target/],
    ['wrong address', source => source.client.emit('transfer', wireTransfer(b.port, '127.0.0.2')), /Wrong transfer target/],
    ['close before packet', source => source.client.emit('close'), /Unexpected close/],
    ['duplicate packet', source => {
      source.client.emit('transfer', wireTransfer());
      source.client.emit('transfer', wireTransfer());
    }, /duplicate transfer/],
  ]) {
    await t.test(`${name} cannot fall back to reconnect`, async () => {
      const f = fixture();
      const source = await f.create(a, 'Traveler').open();
      await assert.rejects(followTransfer(source, b, async () => issue(source), f.create), message);
      assert.equal(f.sessions.length, 1);
      source.close();
    });
  }
  for (const mode of ['no-spawn', 'close']) {
    await t.test(`destination ${mode} cannot reuse the source spawn or retry`, async () => {
      const f = fixture(mode);
      const source = await f.create(a, 'Traveler').open();
      await assert.rejects(followTransfer(source, b,
        async () => source.client.emit('transfer', wireTransfer()), f.create), /new spawn|Unexpected close/);
      assert.equal(f.sessions.length, 2);
      assert.equal(f.sessions[1].spawn, undefined);
      f.sessions[1].close();
    });
  }
});
