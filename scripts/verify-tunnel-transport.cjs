// Offline only: compare the reviewed baseline and validate shell syntax. Never execute deployment.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {execFileSync, spawnSync} = require('node:child_process');
const yaml = require(process.env.TUNNEL_YAML_MODULE || 'yaml');
const root = path.resolve(__dirname, '..').replaceAll('\\', '/');
const baselineCommit = 'f174f5d98864c44605fd97bb1f42565a8a0261b4';
const baseline = yaml.parse(execFileSync('git', ['-c', 'safe.directory='+root, '-C', root, 'show', baselineCommit+':.github/workflows/deploy.yml'], {encoding:'utf8'}));
const candidate = yaml.parse(fs.readFileSync(path.join(root,'.github/workflows/deploy.yml'),'utf8'));
const {jobs:oldJobs,...oldWorkflow}=baseline;
const {jobs:newJobs,...newWorkflow}=candidate;
assert.deepEqual(oldWorkflow,newWorkflow,'Trigger/permissions/concurrency must not change');
assert.deepEqual(Object.keys(oldJobs),Object.keys(newJobs));
const key=Object.keys(oldJobs)[0];
const {steps:oldSteps,...oldJob}=oldJobs[key];
const {steps:newSteps,...newJob}=newJobs[key];
assert.deepEqual(oldJob,newJob);
const i=oldSteps.findIndex(s=>s.uses?.startsWith('appleboy/ssh-action@'));
assert.equal(i,oldSteps.length-1);
// Permit only the reviewed LOCAL preparation delta; all unrelated fields stay pinned.
const lifecycleBaseline = oldSteps.find(s => s.id === 'lifecycle');
assert.ok(lifecycleBaseline, 'Pinned lifecycle preparation step required');
Object.assign(lifecycleBaseline.env, {
 ERASURE_LEDGER_DEPLOYMENT_PROFILE: 'local-paused',
 ERASURE_LEDGER_PROVIDER: 'LOCAL',
 ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED: "${{ vars.ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED || 'false' }}",
 ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED: 'false',
 ERASURE_LEDGER_LOCAL_DIRECTORY: '/var/lib/geupddong-erasure-ledger',
 ERASURE_LEDGER_LOCAL_STORE_ID: '${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}',
 LOCAL_LEDGER_RUNTIME_UID: '1000',
 LOCAL_LEDGER_RUNTIME_GID: '1000',
});
for (const field of ['ERASURE_LEDGER_ENDPOINT','ERASURE_LEDGER_BUCKET',
 'ERASURE_LEDGER_ACCESS_KEY_ID','ERASURE_LEDGER_SECRET_ACCESS_KEY']) delete lifecycleBaseline.env[field];
assert.deepEqual(oldSteps.slice(0,i),newSteps.slice(0,i),'Build steps must not change');
assert.equal(newSteps.length,oldSteps.length+2);
const [prepare,deploy,cleanup]=newSteps.slice(i);
// Exact allowlisted edits to the old script, NOT a blanket exemption for remote commands.
const role = 'batch';
let expectedScript=oldSteps[i].with.script;
function replaceOnce(before,after) {
 assert.equal(expectedScript.split(before).length,2,'Pinned remote baseline drift');
 expectedScript=expectedScript.replace(before,after);
}
replaceOnce('set -eu\numask 077', 'set -eu\numask 077\n'
 +'# Read-only preflight must finish BEFORE touching operational configuration.\n'
 +'test -x /home/luha/erasure-tools/local-ledger-preflight\n'
 +"/home/luha/erasure-tools/local-ledger-preflight "+role+" '${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}'");
const service=role==='api'?'api':'toilet-batch';
replaceOnce('  '+service+':','  '+service+':\n    user: "1000:1000"');
const mount='      - type: bind\n'
 +'        source: /var/lib/geupddong-erasure-ledger\n'
 +'        target: /var/lib/geupddong-erasure-ledger\n'
 +'        read_only: false\n'
 +'        bind:\n'
 +'          create_host_path: false';
if(role==='api'){
 replaceOnce('    container_name: toilet-api','    container_name: toilet-api\n    volumes:\n'+mount);
 replaceOnce('docker compose pull','docker compose pull api');
 replaceOnce('docker compose up -d --wait --wait-timeout 120 redis api',
  'docker compose up -d --no-deps --wait --wait-timeout 120 api');
} else {
 replaceOnce('      - ./region-results:/var/lib/toilet-region',
  '      - ./region-results:/var/lib/toilet-region\n'+mount);
}
assert.equal(deploy.env.DEPLOY_SCRIPT,expectedScript,'Remote commands must match only the allowlisted LOCAL delta');
assert.equal(cleanup.if,'always()');
assert.equal(deploy.env.TUNNEL_SERVICE_TOKEN_ID,'${{ secrets.TUNNEL_DEPLOY_ACCESS_CLIENT_ID }}');
assert.equal(deploy.env.TUNNEL_SERVICE_TOKEN_SECRET,'${{ secrets.TUNNEL_DEPLOY_ACCESS_CLIENT_SECRET }}');
assert.equal(deploy.env.DEPLOY_SSH_KEY,'${{ secrets.MINI_PC_KEY }}');
assert.equal(deploy.env.TUNNEL_KNOWN_HOSTS,'${{ secrets.TUNNEL_DEPLOY_SSH_KNOWN_HOSTS }}');
assert.equal(deploy.env.TUNNEL_SSH_HOST,'${{ vars.TUNNEL_DEPLOY_SSH_HOST }}');
assert.equal(deploy.env.TUNNEL_SSH_USER,'${{ secrets.MINI_PC_USERNAME }}');
assert.equal(Object.keys(deploy.env).length,7);
assert.match(prepare.run,/660b348d473bba81997445b534e7eaefaf4c4e16331866922326c338a7013dd9/);
assert.match(prepare.run,/sha256sum -c -/);
assert.match(prepare.run,/--proto-redir '=https'/);
for(const guard of ['StrictHostKeyChecking=yes','BatchMode=yes','IdentitiesOnly=yes','HostKeyAlgorithms=ssh-ed25519','ForwardAgent=no','ClearAllForwardings=yes','timeout 10m ssh','bash -n','ssh-deploy.geupddong.com','umask 077']) assert.ok(deploy.run.includes(guard),'Missing guard '+guard);
assert.ok(!/set -x|ssh-keyscan|StrictHostKeyChecking=no|--retry/.test(deploy.run));
for(const script of [...newSteps.filter(s=>s.run).map(s=>s.run),deploy.env.DEPLOY_SCRIPT]){
 const check=spawnSync(process.env.TUNNEL_BASH || 'bash',['-n'],{input:script,encoding:'utf8',timeout:10000});
 assert.equal(check.status,0,check.stderr || String(check.error));
}
console.log('PASS: baseline '+baselineCommit+' plus exact LOCAL preparation delta; remaining build/remote/transport invariants and shell syntax verified.');
console.log('No credentials, SSH, image push, or deployment executed.');
