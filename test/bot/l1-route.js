const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const tokenFor = runId => `l1-${runId}-out`;
const peerEvents = file => fs.existsSync(file)
  ? fs.readFileSync(file, 'utf8').trim().split('\n').filter(Boolean).map(row => JSON.parse(row)) : [];

async function waitFor(check, label, milliseconds = 45000) {
  const deadline = Date.now() + milliseconds;
  while (Date.now() < deadline) {
    const value = check();
    if (value) return value;
    await sleep(100);
  }
  throw new Error(`L1 route timed out: ${label}`);
}

class L1Route {
  constructor(runId, sender, resultsFile) {
    this.runId = runId;
    this.sender = sender;
    this.peerFile = `${resultsFile}.route-peer.jsonl`;
    this.receiver = { username: 'E2ERouteRecv', received: [] };
    this.required = process.env.E2E_ROUTE_REQUIRED === '1';
  }

  record(kind, raw, text) {
    this.receiver.received.push({ t: new Date().toISOString(), kind, raw: String(raw).slice(0, 400), text: String(text || '').slice(0, 400) });
  }

  async start(connectReceiver) {
    if (!this.required) return;
    for (const key of ['E2E_ROUTE_PORT', 'E2E_ROUTE_PASSWORD', 'E2E_ROUTE_CLASSPATH']) {
      if (!process.env[key]) throw new Error(`Missing ${key}`);
    }
    fs.rmSync(this.peerFile, { force: true });
    const java = process.env.E2E_ROUTE_JAVA || 'java';
    this.peer = spawn(java, ['-cp', process.env.E2E_ROUTE_CLASSPATH, path.join(__dirname, 'L1RoutePeer.java')], {
      windowsHide: true,
      env: { ...process.env, E2E_ROUTE_PEER_FILE: this.peerFile, E2E_ROUTE_SENDER: this.sender },
      stdio: ['ignore', 'ignore', 'pipe'],
    });
    this.peer.stderr.on('data', data => process.stderr.write(data));
    this.peer.on('error', error => { this.peerError = error; });
    process.once('exit', () => this.close());
    await waitFor(() => {
      this.checkPeer();
      return peerEvents(this.peerFile).some(row => row.kind === 'ready');
    }, 'authenticated backend peer');
    this.connection = await connectReceiver(this.receiver.username, this.record.bind(this));
    await waitFor(() => {
      if (this.receiver.received.some(row => ['error', 'kicked', 'close', 'end'].includes(row.kind))) throw new Error('L1 receiver disconnected');
      return this.receiver.received.some(row => row.kind === 'spawn');
    }, 'independent receiver spawn');
    const join = '/nc join global';
    this.record('sent', join, join);
    this.connection.send(join);
    await sleep(3000);
  }

  checkPeer() {
    if (this.peerError) throw this.peerError;
    if (this.peer.exitCode != null && this.peer.exitCode !== 0) throw new Error(`L1 backend peer exited ${this.peer.exitCode}`);
  }

  async exchange(send) {
    if (!this.required) return;
    send(tokenFor(this.runId));
    await waitFor(() => {
      this.checkPeer();
      return peerEvents(this.peerFile).find(row => row.kind === 'chat-sent');
    }, 'game message routed to the backend peer');
    await waitFor(() => {
      this.checkPeer();
      return this.receiver.received.some(row => row.kind !== 'sent' && row.text.includes(`l1-${this.runId}-reply-`));
    }, 'backend reply delivered to the independent game receiver');
    await waitFor(() => { this.checkPeer(); return this.peer.exitCode === 0; }, 'backend peer completion');
  }

  evidence() {
    return this.required ? { runId: this.runId, sender: this.sender, receiver: this.receiver, peerExitCode: this.peer?.exitCode } : undefined;
  }

  close() {
    const connection = this.connection;
    this.connection = null;
    connection?.close();
    if (this.peer && this.peer.exitCode == null) this.peer.kill();
  }
}

module.exports = { L1Route, tokenFor, peerEvents };
