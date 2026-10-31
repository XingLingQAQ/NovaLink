const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { attachHttpSignalling } = require('./pnx-client');

function fixture() {
  const client = new EventEmitter();
  client.options = { host: '127.0.0.1', port: 19172 };
  const answers = [];
  const errors = [];
  client.connection = { nethernet: {
    networkId: 42n, serverNetworkId: 1n, handleSignal: async signal => answers.push(signal),
  } };
  client.onConnectionError = error => errors.push(error);
  return { client, network: client.connection.nethernet, answers, errors };
}

const offer = { type: 'CONNECTREQUEST', connectionId: 7n, data: 'offer SDP' };
const settle = () => new Promise(resolve => setImmediate(resolve));

test('PNX HTTP signal sends the offer and routes the answer to the same connection', async () => {
  const f = fixture();
  attachHttpSignalling(f.client, async (url, options) => {
    assert.equal(url.href, 'http://127.0.0.1:19172/v1/join/42');
    assert.equal(options.method, 'POST');
    assert.equal(options.body, offer.data);
    return { ok: true, text: async () => 'answer SDP' };
  });
  f.network.signalHandler(offer);
  await settle();
  assert.deepEqual(f.answers, [{ type: 'CONNECTRESPONSE', connectionId: 7n, networkId: 1n, data: 'answer SDP' }]);
  assert.deepEqual(f.errors, []);
  f.client.emit('close');
});

test('PNX HTTP refusal fails the connection', async () => {
  const f = fixture();
  attachHttpSignalling(f.client, async () => ({ ok: false, status: 403, text: async () => 'identity rejected' }));
  f.network.signalHandler(offer);
  await settle();
  assert.match(f.errors[0].message, /HTTP 403: identity rejected/);
  assert.deepEqual(f.answers, []);
  f.client.emit('close');
});

test('closing a PNX client cancels the HTTP request and drops a late answer', async () => {
  const f = fixture();
  let finish;
  let signal;
  attachHttpSignalling(f.client, (_, options) => {
    signal = options.signal;
    return new Promise(resolve => { finish = resolve; });
  });
  f.network.signalHandler(offer);
  f.client.emit('close');
  assert.equal(signal.aborted, true);
  finish({ ok: true, text: async () => 'late answer' });
  await settle();
  assert.deepEqual(f.answers, []);
  assert.deepEqual(f.errors, []);
});
