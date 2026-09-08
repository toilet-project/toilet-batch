import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {prepare} from './prepare-account-lifecycle.mjs'
const paused = {REDIS_PASSWORD:'synthetic',ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED:'true',
  ACCOUNT_LIFECYCLE_MAINTENANCE:'true',ACCOUNT_RETENTION_ENABLED:'false',ACCOUNT_ERASURE_ENABLED:'false',
  ERASURE_LEDGER_ENABLED:'false',ERASURE_LEDGER_CATALOGUE_ENABLED:'false',ERASURE_CHECKPOINT_ENABLED:'false',
  ERASURE_LEDGER_DEPLOYMENT_PROFILE:'us-runtime',ERASURE_LEDGER_US_DEPLOYMENT_APPROVED:'true',
  ERASURE_LEDGER_ENDPOINT:'https://'+'a'.repeat(32)+'.us.r2.cloudflarestorage.com',
  ERASURE_LEDGER_BUCKET:'geupddong-account-erasure-ledger-us',ERASURE_LEDGER_REALM:'production',
  ERASURE_LEDGER_ACCESS_KEY_ID:'a'.repeat(32),ERASURE_LEDGER_SECRET_ACCESS_KEY:'b'.repeat(64),
  ERASURE_LEDGER_KEYS_JSON:JSON.stringify({k1:Buffer.alloc(32,7).toString('base64')}),
  ERASURE_CHECKPOINT_GITHUB_TOKEN:'github_pat_'+'a'.repeat(40),
  ERASURE_CHECKPOINT_DATABASE_EPOCH:'22222222-2222-2222-2222-222222222222'}
test('US preparation requires separate approval and cannot enable writers',()=>{
  for(const role of ['api','batch']){
    assert.throws(()=>prepare({...paused,ERASURE_LEDGER_US_DEPLOYMENT_APPROVED:''},role))
    assert.throws(()=>prepare({...paused,ERASURE_LEDGER_DEPLOYMENT_PROFILE:'unexpected'},role))
    for(const k of ['ACCOUNT_RETENTION_ENABLED','ACCOUNT_ERASURE_ENABLED','ERASURE_LEDGER_ENABLED','ERASURE_LEDGER_CATALOGUE_ENABLED','ERASURE_CHECKPOINT_ENABLED'])
      assert.throws(()=>prepare({...paused,[k]:'true'},role))
    assert.throws(()=>prepare({...paused,ACCOUNT_LIFECYCLE_MAINTENANCE:'false'},role))
  }
})
test('US destination cannot silently fall back to the old bucket or endpoint',()=>{
  for(const endpoint of ['',paused.ERASURE_LEDGER_ENDPOINT.replace('.us.','.'),paused.ERASURE_LEDGER_ENDPOINT.replace('.us.','.eu.')])
    assert.throws(()=>prepare({...paused,ERASURE_LEDGER_ENDPOINT:endpoint},'api'))
  for(const bucket of ['', 'geupddong-account-erasure-ledger'])
    assert.throws(()=>prepare({...paused,ERASURE_LEDGER_BUCKET:bucket},'batch'))
  assert.throws(()=>prepare({...paused,ERASURE_LEDGER_REALM:'verification'},'batch'))
})
test('both services require all dependencies and retain the same identity',()=>{
  for(const role of ['api','batch']){
    for(const k of ['ERASURE_LEDGER_ACCESS_KEY_ID','ERASURE_LEDGER_SECRET_ACCESS_KEY','ERASURE_LEDGER_KEYS_JSON','ERASURE_CHECKPOINT_GITHUB_TOKEN','ERASURE_CHECKPOINT_DATABASE_EPOCH','REDIS_PASSWORD'])
      assert.throws(()=>prepare({...paused,[k]:''},role))
    const ready=prepare(paused,role), value=Buffer.from(ready.payload,'base64').toString()
    assert.equal(ready.ledgerConfigured,true)
    assert.ok(value.includes("ERASURE_CHECKPOINT_DATABASE_EPOCH='"+paused.ERASURE_CHECKPOINT_DATABASE_EPOCH+"'"))
    assert.ok(value.includes("ACCOUNT_LIFECYCLE_MAINTENANCE='true'"))
    assert.ok(value.includes("ERASURE_CHECKPOINT_ENABLED='false'"))
  }
})
test('historical US fixture uses only the separately stored US runtime key for the ledger',()=>{
  const workflow=readFileSync(new URL('../deploy/us-paused.baseline.yml',import.meta.url),'utf8')
  assert.ok(workflow.includes("ERASURE_LEDGER_DEPLOYMENT_PROFILE: 'us-runtime'"))
  assert.ok(workflow.includes('vars.ERASURE_LEDGER_US_DEPLOYMENT_APPROVED'))
  assert.ok(workflow.includes('vars.ERASURE_LEDGER_US_RUNTIME_ENDPOINT'))
  assert.ok(workflow.includes('secrets.ERASURE_LEDGER_US_RUNTIME_ACCESS_KEY_ID'))
  assert.ok(workflow.includes('secrets.ERASURE_LEDGER_US_RUNTIME_SECRET_ACCESS_KEY'))
  assert.ok(!workflow.includes('secrets.ERASURE_LEDGER_ACCESS_KEY_ID'))
  assert.ok(!workflow.includes('secrets.ERASURE_LEDGER_SECRET_ACCESS_KEY'))
})
