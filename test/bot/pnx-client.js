const crypto = require('node:crypto');

function prepareIdentity(client) {
  const JWT = require('jsonwebtoken');
  client.createClientChain(null, true);
  const hash = crypto.createHash('sha1').update(client.username).digest('hex');
  const leguuid = `${hash.slice(0, 8)}-${hash.slice(8, 12)}-${hash.slice(12, 16)}-${hash.slice(16, 20)}-${hash.slice(20, 32)}`;
  client.multiplayerToken = JWT.sign({
    cpk: client.clientX509, leguuid, xname: client.username,
    mid: crypto.createHash('md5').update(client.username).digest('hex').slice(0, 16), xid: '0',
  }, client.ecdhKeyPair.privateKey, {
    algorithm: 'ES384', notBefore: 0, issuer: 'self', expiresIn: 3600,
    audience: 'api://auth-minecraft-services/multiplayer',
    header: { x5u: client.clientX509, typ: undefined },
  });
}

function attachHttpSignalling(client, request = fetch) {
  const network = client.connection.nethernet;
  const controller = new AbortController();
  client.once('close', () => controller.abort());
  network.signalHandler = signal => {
    if (signal.type !== 'CONNECTREQUEST') return;
    const url = new URL(`/v1/join/${network.networkId}`, `http://${client.options.host}:${client.options.port}`);
    request(url, {
      method: 'POST', headers: { 'Content-Type': 'application/sdp' }, body: signal.data,
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(30000)]),
    }).then(async response => {
      const answer = await response.text();
      if (!response.ok) throw new Error(`PNX signalling HTTP ${response.status}: ${answer}`);
      if (controller.signal.aborted) return;
      await network.handleSignal({
        type: 'CONNECTRESPONSE', connectionId: signal.connectionId,
        networkId: network.serverNetworkId, data: answer,
      });
    }).catch(error => {
      if (!controller.signal.aborted) client.onConnectionError(error);
    });
  };
}

function createPnxClient(options, library = require('bedrock-protocol')) {
  const { Client } = library;
  // HTTP identifies the peer by endpoint; the client still needs a network ID.
  const client = new Client({
    ...options, transport: 'nethernet', delayedInit: true, nethernet: { networkId: 1n },
  });
  client.once('connect_allowed', () => {
    attachHttpSignalling(client);
    client.once('session', () => prepareIdentity(client));
    client.connect();
  });
  client.on('network_stack_latency', packet => {
    if (packet.needs_response) {
      client.queue('network_stack_latency', { timestamp: packet.timestamp, needs_response: false });
    }
  });
  const completePacks = () => client.write('resource_pack_client_response', {
    response_status: 'completed', response_status_name: 'resourcepackstackfinished', resourcepackids: [],
  });
  client.once('resource_packs_info', () => {
    completePacks();
    client.once('resource_pack_stack', completePacks);
    client.queue('client_cache_status', { enabled: false });
    setTimeout(() => {
      if (!client._closed) client.queue('request_chunk_radius', { chunk_radius: 4 });
    }, 500);
  });
  queueMicrotask(() => {
    try { client.init(); } catch (error) { client.onConnectionError(error); }
  });
  return client;
}

module.exports = { createPnxClient, attachHttpSignalling };
