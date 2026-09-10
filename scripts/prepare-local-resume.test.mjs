import test from 'node:test'
import assert from 'node:assert/strict'
import { prepareLocalResume } from './prepare-local-resume.mjs'
import { prepare } from './prepare-account-lifecycle.mjs'
const commit = 'a'.repeat(40)
const base = {
  ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED:'true', ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED:'true',
  ERASURE_LEDGER_DEPLOYMENT_PROFILE:'local-resume', ERASURE_LEDGER_PROVIDER:'LOCAL',
  ACCOUNT_RESUME_APPROVED_COMMIT:commit, ACCOUNT_RESUME_RELEASE_APPROVED:'true',
  ACCOUNT_RESUME_LOCAL_STORAGE_VERIFIED:'true', ACCOUNT_RESUME_RESTORE_VERIFIED:'true',
  ACCOUNT_RESUME_RUNTIME_MATCH_VERIFIED:'true', ACCOUNT_RESUME_POLICY_PUBLISHED:'true',
  ACCOUNT_RESUME_WRITERS_GUARDED_VERIFIED:'true', ACCOUNT_RESUME_ACCOUNT_ACTIONS_APPROVED:'true',
  ACCOUNT_RESUME_DUE_ACCOUNTS:'0', ACCOUNT_RESUME_INVALID_WITHDRAWALS:'0',
  ERASURE_LEDGER_LOCAL_DIRECTORY:'/home/luha/geupddong-erasure-ledger',
  ERASURE_LEDGER_LOCAL_STORE_ID:'11111111-1111-1111-1111-111111111111',
  ERASURE_CHECKPOINT_DATABASE_EPOCH:'22222222-2222-2222-2222-222222222222',
  LOCAL_LEDGER_RUNTIME_UID:'1000', LOCAL_LEDGER_RUNTIME_GID:'1000', ERASURE_LEDGER_REALM:'production',
  ERASURE_LEDGER_ACTIVE_KEY_ID:'test', ERASURE_LEDGER_KEYS_JSON:JSON.stringify({test:Buffer.alloc(32,7).toString('base64')}),
  ERASURE_CHECKPOINT_GITHUB_TOKEN:'synthetic_'+'a'.repeat(30), REDIS_PASSWORD:'synthetic',
}
const decoded = value => Buffer.from(value.payload, 'base64').toString()
for (const role of ['api', 'batch']) {
  test(role + ': guarded stage enables protection, not member actions or ledger retirement', () => {
    const result = prepareLocalResume(base, role, 'guarded', commit)
    assert.equal(result.candidateOnly, true)
    assert.equal(result.operationalChangesApplied, false)
    const payload = decoded(result)
    for (const key of ['ERASURE_LEDGER_ENABLED','ERASURE_LEDGER_CATALOGUE_ENABLED','ERASURE_CHECKPOINT_ENABLED','ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED','ERASURE_MAINTENANCE_LOCK_ENABLED'])
      assert.ok(payload.includes(key + "='true'"))
    assert.ok(payload.includes("ACCOUNT_LIFECYCLE_MAINTENANCE='true'"))
    for (const key of ['ACCOUNT_RETENTION_ENABLED','ACCOUNT_ERASURE_ENABLED','ERASURE_RETIREMENT_WRITE_ENABLED','ERASURE_HISTORY_COMPATIBILITY_VERIFIED'])
      assert.ok(payload.includes(key + "='false'"))
  })
  test(role + ': active phase requires publication, both guarded writers and empty reviewed queue', () => {
    const result = prepareLocalResume(base, role, 'active', commit)
    assert.ok(decoded(result).includes("ACCOUNT_LIFECYCLE_MAINTENANCE='false'"))
    assert.ok(decoded(result).includes("ACCOUNT_ERASURE_ENABLED='true'"))
    assert.equal(result.retirementEnabledInCandidate, false)
    for (const key of ['ACCOUNT_RESUME_POLICY_PUBLISHED','ACCOUNT_RESUME_WRITERS_GUARDED_VERIFIED','ACCOUNT_RESUME_ACCOUNT_ACTIONS_APPROVED'])
      assert.throws(() => prepareLocalResume({...base,[key]:'false'},role,'active',commit))
    for (const key of ['ACCOUNT_RESUME_DUE_ACCOUNTS','ACCOUNT_RESUME_INVALID_WITHDRAWALS'])
      for (const value of [undefined, '1', '-1', '00', '']) assert.throws(() => prepareLocalResume({...base,[key]:value},role,'active',commit))
  })
}
test('missing review evidence, wrong release and retirement expansion are rejected', () => {
  for (const key of ['ACCOUNT_RESUME_RELEASE_APPROVED','ACCOUNT_RESUME_LOCAL_STORAGE_VERIFIED','ACCOUNT_RESUME_RESTORE_VERIFIED','ACCOUNT_RESUME_RUNTIME_MATCH_VERIFIED'])
    assert.throws(() => prepareLocalResume({...base,[key]:'false'},'api','guarded',commit))
  assert.throws(() => prepareLocalResume(base,'api','active','b'.repeat(40)))
  assert.throws(() => prepareLocalResume(base,'admin','active',commit))
  assert.throws(() => prepareLocalResume(base,'api','other',commit))
  for (const key of ['ERASURE_RETIREMENT_WRITE_ENABLED','ERASURE_HISTORY_COMPATIBILITY_VERIFIED'])
    assert.throws(() => prepareLocalResume({...base,[key]:'true'},'batch','active',commit))
})
test('existing production preparation still rejects resume profile', () => {
  assert.throws(() => prepare(base,'api'))
  assert.throws(() => prepare(base,'batch'))
})
test('no remote storage, system log policy or history expiry is generated', () => {
  const result = prepareLocalResume({...base, ERASURE_LEDGER_ENDPOINT:'must-not-transfer', GENERAL_LOG_RETENTION_DAYS:'90'},'api','active',commit)
  assert.doesNotMatch(decoded(result), /must-not-transfer|GENERAL_LOG|RETENTION_DAYS|ERASURE_LEDGER_ENDPOINT/)
  const keys = decoded(result).trim().split('\n').map(line => line.split('=')[0])
  assert.equal(new Set(keys).size, keys.length)
})
test('API and batch share the same protection and identity configuration', () => {
  assert.equal(prepareLocalResume(base,'api','active',commit).payload, prepareLocalResume(base,'batch','active',commit).payload)
  assert.throws(() => prepareLocalResume({...base,ERASURE_LEDGER_KEYS_JSON:'{}'},'api','active',commit))
})
