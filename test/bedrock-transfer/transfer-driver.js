const crypto = require('node:crypto');
const { errorText, redactFailureData } = require('./error-text');

const REQUIRED_IDS = [
  'native-pnx-a', 'native-pnx-b', 'backend-clients',
  'initial-spawn', 'initial-probe', 'initial-membership', 'initial-send', 'initial-receive',
  'outbound-transfer', 'destination-spawn', 'destination-probe', 'destination-membership',
  'source-a-absent', 'destination-send', 'destination-receive',
  'return-transfer', 'return-spawn', 'return-probe', 'return-membership',
  'source-b-absent', 'return-send', 'return-receive',
];

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const plain = text => String(text).replace(/\u00a7./g, '');

async function until(read, { timeoutMs = 45000, intervalMs = 25, health = () => {}, label = 'evidence' } = {}) {
  const deadline = Date.now() + timeoutMs;
  do {
    health();
    const value = await read();
    if (value) return value;
    await delay(intervalMs);
  } while (Date.now() < deadline);
  throw new Error(`Timeout waiting for ${label}`);
}

class Session {
  constructor({ node, name, createClient, record, timeoutMs = 45000 }) {
    Object.assign(this, { node, name, createClient, record, timeoutMs });
    this.id = crypto.randomUUID();
    this.rows = [];
    this.retiring = false;
    this.closed = false;
  }

  add(kind, data) {
    if (kind === 'error' || kind === 'kick') data = redactFailureData(data);
    const row = { at: new Date().toISOString(), connectionId: this.id, node: this.node.id,
      name: this.name, kind, data };
    this.rows.push(row);
    this.record(row);
    return row;
  }

  health() {
    if (this.error) throw this.error;
    if (this.closed && !this.retiring) throw new Error(`Unexpected close: ${this.node.id}/${this.name}`);
  }

  async open() {
    this.add('connect', { host: this.node.host, port: this.node.port });
    this.client = this.createClient({ host: this.node.host, port: this.node.port, username: this.name });
    this.client.on('start_game', packet => { this.game = this.add('start_game', packet); });
    this.client.on('spawn', () => { this.spawn = this.add('spawn', {}); });
    this.client.on('text', packet => this.add('text', packet));
    this.client.on('transfer', packet => {
      const row = this.add('transfer', packet);
      if (!this.expected || this.transfer) {
        this.error = new Error('Unarmed or duplicate transfer packet');
        return;
      }
      if (packet.server_address !== this.expected.host || packet.port !== this.expected.port) {
        this.error = new Error(`Wrong transfer target: ${packet.server_address}:${packet.port}`);
        return;
      }
      this.transfer = row;
    });
    this.client.on('kick', packet => {
      this.add('kick', packet);
      this.error = new Error(`Server kicked ${this.name}: ${JSON.stringify(packet)}`);
    });
    this.client.on('error', error => {
      this.add('error', { message: errorText(error) });
      this.error = error;
    });
    this.client.on('close', () => { this.closed = true; this.add('close', { expected: this.retiring }); });
    await until(() => this.spawn && this.game, {
      timeoutMs: this.timeoutMs, health: () => this.health(), label: `new spawn on ${this.node.id}`,
    });
    return this;
  }

  command(command) {
    this.health();
    this.add('sent-command', { command });
    this.client.queue('command_request', {
      command, origin: { type: 'player', uuid: crypto.randomUUID(), request_id: crypto.randomUUID(),
        player_entity_id: 0n }, internal: false, version: 'latest',
    });
  }

  chat(message) {
    this.health();
    this.add('sent-chat', { message });
    this.client.queue('text', { type: 'chat', category: 'authored', needs_translation: false,
      source_name: this.name, xuid: '', platform_chat_id: '', filtered_message: '', message });
  }

  receive(text, since = 0) {
    return until(() => this.rows.slice(since).find(row => row.kind === 'text'
      && plain(row.data.message).includes(text)), {
      timeoutMs: this.timeoutMs, health: () => this.health(), label: `received ${text}`,
    });
  }

  close() {
    this.retiring = true;
    if (this.client && !this.closed) this.client.disconnect('Native transfer harness leaving');
  }
}

async function followTransfer(source, target, issue, createSession) {
  source.health();
  if (source.expected) throw new Error('Session already transferred');
  source.expected = { host: target.host, port: target.port };
  await issue();
  const transfer = await until(() => source.transfer, {
    timeoutMs: source.timeoutMs, health: () => source.health(), label: 'native transfer packet',
  });
  source.health();
  source.close();
  const next = createSession(target, source.name);
  await next.open();
  next.health();
  return { next, transfer };
}

module.exports = { REQUIRED_IDS, Session, followTransfer, until, plain };
