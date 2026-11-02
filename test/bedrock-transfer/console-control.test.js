const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { requestConsole } = require('./console-control');
const { REQUIRED_IDS, until } = require('./transfer-driver');

test('console submission and stale output cannot replace a fresh native receipt', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'novalink-console-contract-'));
  const plan = { runId: crypto.randomUUID().replaceAll('-', ''), controlTransport: 'held-stdin',
    controlDirectory: path.join(root, 'console') };
  fs.mkdirSync(plan.controlDirectory);
  const logFile = path.join(root, 'a/pnx/pnx.stdout.log');
  fs.mkdirSync(path.dirname(logFile), { recursive: true });
  const nonce = `${plan.runId}-source-a-absent`;
  const receipt = `BT_STATE:a:${nonce}:BTTraveler:absent`;
  fs.writeFileSync(logFile, `${receipt}\n`);
  let completed = false;
  const pending = requestConsole(plan, { id: 'a' }, `btstate BTTraveler ${nonce}`)
    .then(result => { completed = true; return result; });
  const request = await until(() => {
    const file = fs.readdirSync(plan.controlDirectory).find(name => name.endsWith('.request.json'));
    return file && JSON.parse(fs.readFileSync(path.join(plan.controlDirectory, file), 'utf8'));
  });
  fs.writeFileSync(path.join(plan.controlDirectory, `${request.id}.reply.json`), JSON.stringify({
    id: request.id, runId: plan.runId, node: 'a', submitted: true,
  }));
  await new Promise(resolve => setTimeout(resolve, 125));
  assert.equal(completed, false);
  fs.appendFileSync(logFile, `\u001b[32m${receipt}\u001b[m\n`);
  const result = await pending;
  assert.equal(result.response, receipt);
  assert.equal(result.requestId, request.id);
  assert.equal(REQUIRED_IDS.length, 22);
});

test('console control rejects an unscoped nonce before submitting a request', async () => {
  await assert.rejects(requestConsole({ runId: 'thisrun', controlTransport: 'held-stdin' },
    { id: 'a' }, 'bttransfer BTTraveler otherrun-outbound-transfer'), /outside this run/);
});
