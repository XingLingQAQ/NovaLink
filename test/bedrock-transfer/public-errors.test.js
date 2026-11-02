const test = require('node:test');
const assert = require('node:assert/strict');
const { errorText, redactText } = require('./error-text');
const { Session } = require('./transfer-driver');

test('command failure stacks redact both exact secrets without interpreting their characters', () => {
  const environment = { BT_BACKEND_SECRET: 'backend.$[1]"secret', BT_RCON_PASSWORD: "client'\\secret" };
  const error = new Error(`Command failed: powershell -Password "${environment.BT_RCON_PASSWORD}"\nbackend=${environment.BT_BACKEND_SECRET}`);
  const publicText = errorText(error, environment);
  for (const secret of Object.values(environment)) assert.equal(publicText.includes(secret), false);
  assert.match(publicText, /Command failed: powershell -Password "\[REDACTED\]"/);
  assert.match(publicText, /backend=\[REDACTED\]/);
  assert.equal(redactText('abcdef abc', { BT_BACKEND_SECRET: 'abc', BT_RCON_PASSWORD: 'abcdef' }), '[REDACTED] [REDACTED]');
  assert.equal(redactText('normal evidence', { BT_BACKEND_SECRET: '', BT_RCON_PASSWORD: undefined }), 'normal evidence');
});

test('recorded errors and kicks are redacted while transfer and command evidence stay intact', () => {
  const saved = { backend: process.env.BT_BACKEND_SECRET, client: process.env.BT_RCON_PASSWORD };
  process.env.BT_BACKEND_SECRET = 'test-backend-secret';
  process.env.BT_RCON_PASSWORD = 'test-client-secret';
  try {
    const rows = [];
    const session = new Session({ node: { id: 'a' }, name: 'BTTraveler', record: row => rows.push(row) });
    session.add('error', { message: `failure ${process.env.BT_BACKEND_SECRET}` });
    session.add('kick', { message: `disconnect ${process.env.BT_RCON_PASSWORD}`, details: [process.env.BT_BACKEND_SECRET] });
    const serialized = JSON.stringify(rows);
    assert.equal(serialized.includes(process.env.BT_BACKEND_SECRET), false);
    assert.equal(serialized.includes(process.env.BT_RCON_PASSWORD), false);
    const transfer = { server_address: '127.0.0.1', port: 19363, reload_world: true };
    const command = { command: 'bttransfer BTTraveler run-outbound-transfer' };
    assert.equal(session.add('transfer', transfer).data, transfer);
    assert.equal(session.add('console', command).data, command);
  } finally {
    for (const [name, value] of [['BT_BACKEND_SECRET', saved.backend], ['BT_RCON_PASSWORD', saved.client]]) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  }
});
