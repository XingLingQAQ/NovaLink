const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { until } = require('./transfer-driver');

const stripConsole = text => String(text).replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '');

async function requestConsole(plan, node, command, health = () => {}) {
  if (plan.controlTransport !== 'held-stdin') throw new Error('Native PNX control requires held stdin');
  const match = /^(bttransfer|btprobe|btstate) ([A-Za-z0-9_]+) ([A-Za-z0-9_-]+)$/.exec(command);
  if (!match || !match[3].startsWith(`${plan.runId}-`)) throw new Error('Console command is outside this run');
  const [, action, name, nonce] = match;
  const id = crypto.randomUUID();
  const logFile = path.join(path.dirname(plan.controlDirectory), node.id, 'pnx/pnx.stdout.log');
  const offset = fs.statSync(logFile).size;
  const requestFile = path.join(plan.controlDirectory, `${id}.request.json`);
  const temporary = `${requestFile}.new`;
  fs.writeFileSync(temporary, JSON.stringify({ id, runId: plan.runId, node: node.id, command }));
  fs.renameSync(temporary, requestFile);
  const replyFile = path.join(plan.controlDirectory, `${id}.reply.json`);
  const options = { timeoutMs: 15000, intervalMs: 50, health, label: `console submission ${action}` };
  const reply = await until(() => fs.existsSync(replyFile) && JSON.parse(fs.readFileSync(replyFile, 'utf8')), options);
  if (reply.id !== id || reply.runId !== plan.runId || reply.node !== node.id || reply.submitted !== true) {
    throw new Error('Invalid console submission acknowledgement');
  }
  const tag = { bttransfer: 'BT_TRANSFER_CALLED', btprobe: 'BT_PROBE_ISSUED', btstate: 'BT_STATE' }[action];
  const suffix = action === 'btstate' ? '(?:absent|[0-9a-fA-F-]{36})\\b' : '';
  const expected = new RegExp(`${tag}:${node.id}:${nonce}:${name}:${suffix}`);
  const missing = `BT_MISSING:${node.id}:${nonce}:${name}:`;
  const response = await until(() => {
    const text = stripConsole(fs.readFileSync(logFile).subarray(offset).toString('utf8'));
    if (text.includes(missing)) throw new Error(`Native console could not find ${name} on ${node.id}`);
    return text.split(/\r?\n/).find(line => expected.test(line));
  }, { ...options, label: `native console receipt ${action}` });
  return { response, requestId: id };
}

module.exports = { requestConsole, stripConsole };
