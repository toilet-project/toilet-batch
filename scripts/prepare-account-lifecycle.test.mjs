import {test} from 'node:test'
import assert from 'node:assert/strict'
import {prepare} from './prepare-account-lifecycle.mjs'
const base = {REDIS_PASSWORD:'synthetic',ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED:'true',ACCOUNT_LIFECYCLE_MAINTENANCE:'true',
  ACCOUNT_RETENTION_ENABLED:'false',ACCOUNT_ERASURE_ENABLED:'false',ERASURE_LEDGER_ENABLED:'false',ERASURE_LEDGER_CATALOGUE_ENABLED:'false'}
test('explicit deployment approval and paused flags are mandatory', () => {
  assert.throws(() => prepare({...base,ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED:''},'api'))
  for (const key of ['ACCOUNT_RETENTION_ENABLED','ACCOUNT_ERASURE_ENABLED','ERASURE_LEDGER_ENABLED','ERASURE_LEDGER_CATALOGUE_ENABLED'])
    assert.throws(() => prepare({...base,[key]:'true'},'batch'))
  assert.throws(() => prepare({...base,ACCOUNT_LIFECYCLE_MAINTENANCE:'false'},'api'))
  assert.throws(() => prepare(base,'other'))
})
test('paused preparation permits missing dependencies without pretending ready', () => {
  const result = prepare({...base,REDIS_PASSWORD:''},'batch')
  assert.equal(result.redisConfigured,false); assert.equal(result.ledgerConfigured,false)
  assert.match(Buffer.from(result.payload,'base64').toString(),/ACCOUNT_ERASURE_ENABLED='false'/)
})
test('valid keyring and special Redis characters survive safe transport', () => {
  const keys = JSON.stringify({k1:Buffer.alloc(32,7).toString('base64')})
  const result = prepare({...base,REDIS_PASSWORD:'synthetic$VALUE#=\\test',
    ERASURE_LEDGER_KEYS_JSON:keys,ERASURE_LEDGER_ACCESS_KEY_ID:'synthetic',
    ERASURE_LEDGER_SECRET_ACCESS_KEY:'synthetic',ERASURE_LEDGER_ENDPOINT:'https://'+'a'.repeat(32)+'.r2.cloudflarestorage.com'},'api')
  assert.equal(result.ledgerConfigured,true); assert.equal(result.redisConfigured,true)
  const decoded = Buffer.from(result.payload,'base64').toString()
  assert.ok(decoded.includes("ERASURE_LEDGER_KEYS_JSON='"+keys+"'"))
  assert.ok(decoded.includes("REDIS_PASSWORD='synthetic$VALUE#=\\test'"))
})
test('reject unsafe dotenv values and malformed encryption keys', () => {
  for (const value of ["x'y",'x\ny','x\ry','x\0y']) assert.throws(() => prepare({...base,REDIS_PASSWORD:value},'api'))
  for (const value of ['null','[]','{}','bad','{"k1":"short"}']) assert.throws(() => prepare({...base,ERASURE_LEDGER_KEYS_JSON:value},'api'))
  assert.throws(() => prepare({...base,ERASURE_LEDGER_ENDPOINT:'https://evil.example'},'api'))
})
