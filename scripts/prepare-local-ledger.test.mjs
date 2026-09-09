import {test} from 'node:test'
import assert from 'node:assert/strict'
import {prepareLocalPaused} from './prepare-local-ledger.mjs'
import {prepare} from './prepare-account-lifecycle.mjs'

const base = {
  ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED:'true', ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED:'true',
  ERASURE_LEDGER_DEPLOYMENT_PROFILE:'local-paused', ERASURE_LEDGER_PROVIDER:'LOCAL',
  ACCOUNT_LIFECYCLE_MAINTENANCE:'true', ACCOUNT_RETENTION_ENABLED:'false', ACCOUNT_ERASURE_ENABLED:'false',
  ERASURE_LEDGER_ENABLED:'false', ERASURE_LEDGER_CATALOGUE_ENABLED:'false', ERASURE_CHECKPOINT_ENABLED:'false',
  ERASURE_LEDGER_LOCAL_DIRECTORY:'/home/luha/geupddong-erasure-ledger',
  ERASURE_LEDGER_LOCAL_STORE_ID:'11111111-1111-1111-1111-111111111111',
  ERASURE_CHECKPOINT_DATABASE_EPOCH:'22222222-2222-2222-2222-222222222222',
  LOCAL_LEDGER_RUNTIME_UID:'1000', LOCAL_LEDGER_RUNTIME_GID:'1000', ERASURE_LEDGER_REALM:'production',
  ERASURE_LEDGER_ACTIVE_KEY_ID:'test', ERASURE_LEDGER_KEYS_JSON:JSON.stringify({test:Buffer.alloc(32,7).toString('base64')}),
  ERASURE_CHECKPOINT_GITHUB_TOKEN:'synthetic_'+'a'.repeat(30), REDIS_PASSWORD:'synthetic',
}
test('local preparation is identical for API and batch and stays paused', () => {
  const result = prepareLocalPaused(base,'api')
  assert.deepEqual(result,prepareLocalPaused(base,'batch'))
  assert.deepEqual(result,prepare(base,'api'))
  assert.equal(result.activationAllowed,false)
  assert.equal(result.filesystemVerified,false)
  const text=Buffer.from(result.payload,'base64').toString()
  assert.match(text,/ERASURE_LEDGER_PROVIDER='LOCAL'/)
  assert.match(text,/ACCOUNT_ERASURE_ENABLED='false'/)
  assert.match(text,/ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED='false'/)
})
test('both approvals and exact profile are required', () => {
  for (const k of ['ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED','ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED'])
    assert.throws(()=>prepareLocalPaused({...base,[k]:'false'},'api'))
  for (const k of ['ERASURE_LEDGER_DEPLOYMENT_PROFILE','ERASURE_LEDGER_PROVIDER'])
    assert.throws(()=>prepareLocalPaused({...base,[k]:'R2'},'api'))
  assert.throws(()=>prepareLocalPaused(base,'other'))
})
test('activation and acceptance cannot be smuggled into preparation', () => {
  for (const k of ['ACCOUNT_RETENTION_ENABLED','ACCOUNT_ERASURE_ENABLED','ERASURE_LEDGER_ENABLED',
    'ERASURE_LEDGER_CATALOGUE_ENABLED','ERASURE_CHECKPOINT_ENABLED','ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED'])
    assert.throws(()=>prepareLocalPaused({...base,[k]:'true'},'batch'))
  assert.throws(()=>prepareLocalPaused({...base,ACCOUNT_LIFECYCLE_MAINTENANCE:'false'},'batch'))
})
test('no remote storage settings are even read in local mode', () => {
  const env={...base}
  for (const k of ['ERASURE_LEDGER_BUCKET','ERASURE_LEDGER_ENDPOINT','ERASURE_LEDGER_ACCESS_KEY_ID','ERASURE_LEDGER_SECRET_ACCESS_KEY'])
    Object.defineProperty(env,k,{get(){throw Error('remote secret accessed')}})
  const text=Buffer.from(prepare(env,'api').payload,'base64').toString()
  assert.doesNotMatch(text,/ERASURE_LEDGER_(BUCKET|ENDPOINT|ACCESS_KEY_ID|SECRET_ACCESS_KEY)=/)
})
test('root or mismatched UID and arbitrary paths are rejected', () => {
  for (const k of ['LOCAL_LEDGER_RUNTIME_UID','LOCAL_LEDGER_RUNTIME_GID'])
    for (const value of ['0','1001','','1000:1000']) assert.throws(()=>prepareLocalPaused({...base,[k]:value},'api'))
  for (const p of ['/','/tmp/ledger','/home/luha/geupddong-erasure-ledger/','/var/lib/../tmp/ledger'])
    assert.throws(()=>prepareLocalPaused({...base,ERASURE_LEDGER_LOCAL_DIRECTORY:p},'api'))
})
test('pinned identities and checkpoint credential cannot be omitted', () => {
  for (const k of ['ERASURE_LEDGER_LOCAL_STORE_ID','ERASURE_CHECKPOINT_DATABASE_EPOCH','ERASURE_CHECKPOINT_GITHUB_TOKEN','REDIS_PASSWORD'])
    assert.throws(()=>prepareLocalPaused({...base,[k]:''},'api'))
  assert.throws(()=>prepareLocalPaused({...base,ERASURE_LEDGER_REALM:'other'},'api'))
})
test('safe secret transport and invalid keyring rejection', () => {
  const value='synthetic$VALUE#=\\test'
  const text=Buffer.from(prepareLocalPaused({...base,REDIS_PASSWORD:value},'api').payload,'base64').toString()
  assert.ok(text.includes("REDIS_PASSWORD='"+value+"'"))
  for (const value of ["a'b",'a\nb','a\rb','a\0b'])
    assert.throws(()=>prepareLocalPaused({...base,REDIS_PASSWORD:value},'api'))
  for (const value of ['{}','null','[]','bad','{"test":"short"}'])
    assert.throws(()=>prepareLocalPaused({...base,ERASURE_LEDGER_KEYS_JSON:value},'api'))
})
test('legacy generator cannot silently ignore a LOCAL provider selection', () => {
  assert.throws(()=>prepare({...base,ERASURE_LEDGER_DEPLOYMENT_PROFILE:'legacy-paused'},'api'))
})
