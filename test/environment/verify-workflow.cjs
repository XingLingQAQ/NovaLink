'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(process.argv[2] || path.join(__dirname, '..', '..'));
const yaml = require(require.resolve('js-yaml', {
  paths: [path.join(root, 'Panel', 'web'), path.join(root, 'test', 'bot'), root],
}));
const source = fs.readFileSync(path.join(root, '.github/workflows/e2e-environment.yml'), 'utf8');
const template = fs.readFileSync(path.join(__dirname, 'manual-environment.workflow.yml'), 'utf8');
assert.equal(source, template, 'manual template must match the installed workflow');
const workflow = yaml.load(source);
assert.deepEqual(Object.keys(workflow.on), ['workflow_dispatch']);
assert.equal(workflow.on.workflow_dispatch.inputs.cases.required, true);
assert.deepEqual(workflow.on.workflow_dispatch.inputs.cases.default.split(','), [
  'tls-trusted', 'tls-untrusted', 'mysql', 'postgresql',
]);
assert.deepEqual(workflow.permissions, { contents: 'read' });
const job = workflow.jobs.environment;
assert.equal(job['runs-on'], 'ubuntu-24.04');
assert.equal(job.if, "github.event_name == 'workflow_dispatch'");
assert.equal(job.defaults.run.shell, 'pwsh');
const nodeSetup = job.steps.find((step) => step.uses?.startsWith('actions/setup-node@'));
assert.equal(nodeSetup.with['node-version'], '24');
const javaSetup = job.steps.find((step) => step.uses?.startsWith('actions/setup-java@'));
assert.equal(javaSetup.with['java-version'], '21');
const upload = job.steps.find((step) => step.uses?.startsWith('actions/upload-artifact@'));
assert.equal(upload.with.path, '.e2e-artifacts/environment/**/public/**');
assert.equal(upload.with['include-hidden-files'], true);
assert.equal(upload.if, 'always()');
const build = job.steps.find((step) => step.name === 'Build required runtime artifacts');
assert.equal(build.shell, 'bash');
for (const task of [':StarLink:core:shadowJar', ':NovaChat:Plugin:bukkit:shadowJar', ':NovaChat:MOD:fabric:fabricE2eJar']) {
  assert.ok(build.run.includes(task), `missing build task ${task}`);
}
assert.ok(!build.run.includes('writeRuntimeClasspath'), 'standalone workflow must build the backend fat jar');
assert.ok(build.run.includes('--init-script test/bin/fabric-fatjar.init.gradle'));
for (const module of ['StarLink/core', 'NovaChat/Plugin/bukkit']) {
  assert.match(fs.readFileSync(path.join(root, module, 'build.gradle'), 'utf8'), /shadowJar\s*\{/);
}
assert.match(fs.readFileSync(path.join(root, 'test/bin/fabric-fatjar.init.gradle'), 'utf8'), /tasks\.register\('fabricE2eJar'/);
const run = job.steps.find((step) => step.name === 'Run actual game TLS and SQL paths');
assert.ok(run.run.includes('-ProvisionDatabases'));
assert.ok(run.run.includes('-Jdk21 $env:JAVA_HOME'));
assert.ok(job.steps.some((step) => step.run?.includes('PSVersionTable.PSVersion') && step.run.includes("[version]'7.4'")));
const scenarios = fs.readFileSync(path.join(__dirname, 'scenarios.ps1'), 'utf8');
assert.ok(scenarios.includes('insecure-allow-plaintext: false'));
assert.ok(!scenarios.includes('insecure-allow-plaintext: true'));
assert.ok(scenarios.includes("{ 'REQUIRED' } else { 'require' }"));
assert.ok(scenarios.includes('--require-secure-transport=ON'));
assert.ok(scenarios.includes('hostssl all all'));
process.stdout.write(JSON.stringify({
  powershell: job.steps.filter((step) => step.run && (step.shell || job.defaults.run.shell) === 'pwsh')
    .map((step) => ({ name: step.name, script: step.run })),
}));
