// Review-only candidate generator. Not imported by the production deployment workflow.
// Approval/evidence inputs must be checked again on the host; flags alone are not proof.
import { prepareLocalPaused } from './prepare-local-ledger.mjs'

const fail = () => { throw new Error('ACCOUNT_RESUME_CANDIDATE_REJECTED') }
const requireTrue = (env, key) => { if (env[key] !== 'true') fail() }

export function prepareLocalResume(env, role, phase, commit) {
  if (!['api', 'batch'].includes(role) || !['guarded', 'active'].includes(phase)) fail()
  if (!/^[a-f0-9]{40}$/.test(commit ?? '') || env.ACCOUNT_RESUME_APPROVED_COMMIT !== commit) fail()
  if (env.ERASURE_LEDGER_DEPLOYMENT_PROFILE !== 'local-resume') fail()
  for (const key of ['ACCOUNT_RESUME_RELEASE_APPROVED', 'ACCOUNT_RESUME_LOCAL_STORAGE_VERIFIED',
    'ACCOUNT_RESUME_RESTORE_VERIFIED', 'ACCOUNT_RESUME_RUNTIME_MATCH_VERIFIED']) requireTrue(env, key)
  if ((env.ERASURE_RETIREMENT_WRITE_ENABLED ?? 'false') !== 'false' ||
      (env.ERASURE_HISTORY_COMPATIBILITY_VERIFIED ?? 'false') !== 'false') fail()
  if (phase === 'active') {
    for (const key of ['ACCOUNT_RESUME_POLICY_PUBLISHED', 'ACCOUNT_RESUME_WRITERS_GUARDED_VERIFIED',
      'ACCOUNT_RESUME_ACCOUNT_ACTIONS_APPROVED']) requireTrue(env, key)
    // A changed or nonzero backlog needs its own reviewed plan; never silently consume legacy users.
    if (env.ACCOUNT_RESUME_DUE_ACCOUNTS !== '0' || env.ACCOUNT_RESUME_INVALID_WITHDRAWALS !== '0') fail()
  }

  // Reuse identity/keyring/transport validation without relaxing the paused-only entry point.
  const paused = prepareLocalPaused({
    ...env,
    ERASURE_LEDGER_DEPLOYMENT_PROFILE: 'local-paused',
    ACCOUNT_LIFECYCLE_MAINTENANCE: 'true',
    ACCOUNT_RETENTION_ENABLED: 'false', ACCOUNT_ERASURE_ENABLED: 'false',
    ERASURE_LEDGER_ENABLED: 'false', ERASURE_LEDGER_CATALOGUE_ENABLED: 'false',
    ERASURE_CHECKPOINT_ENABLED: 'false', ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED: 'false',
  }, role)
  const flags = {
    ACCOUNT_LIFECYCLE_MAINTENANCE: phase === 'guarded' ? 'true' : 'false',
    ACCOUNT_RETENTION_ENABLED: phase === 'active' ? 'true' : 'false',
    ACCOUNT_ERASURE_ENABLED: phase === 'active' ? 'true' : 'false',
    ERASURE_LEDGER_ENABLED: 'true', ERASURE_LEDGER_CATALOGUE_ENABLED: 'true',
    ERASURE_CHECKPOINT_ENABLED: 'true', ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED: 'true',
    ERASURE_MAINTENANCE_LOCK_ENABLED: 'true',
    ERASURE_MAINTENANCE_DIRECTORY: '/home/luha/geupddong-maintenance',
    ERASURE_RETIREMENT_WRITE_ENABLED: 'false',
    ERASURE_HISTORY_COMPATIBILITY_VERIFIED: 'false',
  }
  const replacements = new Set(Object.keys(flags))
  const retained = Buffer.from(paused.payload, 'base64').toString('utf8').trimEnd().split('\n')
    .filter(line => !replacements.has(line.slice(0, line.indexOf('='))))
  const lines = Object.entries(flags).map(([key, value]) => key + "='" + value + "'")
  return {
    payload: Buffer.from([...retained, ...lines].join('\n') + '\n').toString('base64'),
    phase, role, commit, candidateOnly: true, operationalChangesApplied: false,
    accountActionsEnabledInCandidate: phase === 'active', retirementEnabledInCandidate: false,
  }
}
