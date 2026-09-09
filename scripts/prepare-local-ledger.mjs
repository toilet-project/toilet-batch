// Configuration generation only: no filesystem provisioning, Docker calls, credentials lookup, or activation.
const fail = code => { throw new Error(code) }
const safe = value => {
  if (typeof value !== 'string' || /[\r\n\0']/.test(value)) fail('LOCAL_LEDGER_UNSAFE_CONFIG')
  return value
}
const required = (e, key) => {
  const value = safe(e[key] ?? '')
  if (!value) fail('LOCAL_LEDGER_MISSING_CONFIG')
  return value
}

export function prepareLocalPaused(e, role) {
  if (!['api', 'batch'].includes(role)) fail('LOCAL_LEDGER_INVALID_ROLE')
  if (e.ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED !== 'true' || e.ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED !== 'true')
    fail('LOCAL_LEDGER_DEPLOYMENT_APPROVAL_REQUIRED')
  if (e.ERASURE_LEDGER_DEPLOYMENT_PROFILE !== 'local-paused' || e.ERASURE_LEDGER_PROVIDER !== 'LOCAL')
    fail('LOCAL_LEDGER_EXPLICIT_PROFILE_REQUIRED')
  if (e.ACCOUNT_LIFECYCLE_MAINTENANCE !== 'true') fail('LOCAL_LEDGER_ACTIVATION_NOT_APPROVED')
  for (const key of ['ACCOUNT_RETENTION_ENABLED', 'ACCOUNT_ERASURE_ENABLED', 'ERASURE_LEDGER_ENABLED',
    'ERASURE_LEDGER_CATALOGUE_ENABLED', 'ERASURE_CHECKPOINT_ENABLED'])
    if (e[key] !== 'false') fail('LOCAL_LEDGER_ACTIVATION_NOT_APPROVED')
  // An acceptance/activation release must be reviewed separately from this preparation-only generator.
  if ((e.ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED ?? 'false') !== 'false') fail('LOCAL_LEDGER_ACTIVATION_NOT_APPROVED')
  if (e.ERASURE_LEDGER_LOCAL_DIRECTORY !== '/home/luha/geupddong-erasure-ledger' ||
      e.LOCAL_LEDGER_RUNTIME_UID !== '1000' || e.LOCAL_LEDGER_RUNTIME_GID !== '1000') fail('LOCAL_LEDGER_RUNTIME_PLAN_MISMATCH')

  const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
  const storeId = required(e, 'ERASURE_LEDGER_LOCAL_STORE_ID')
  const epoch = required(e, 'ERASURE_CHECKPOINT_DATABASE_EPOCH')
  if (!uuid.test(storeId) || !uuid.test(epoch)) fail('LOCAL_LEDGER_INVALID_IDENTITY')
  if (e.ERASURE_LEDGER_REALM !== 'production') fail('LOCAL_LEDGER_INVALID_IDENTITY')
  const activeKey = required(e, 'ERASURE_LEDGER_ACTIVE_KEY_ID')
  if (!/^[a-zA-Z0-9_-]{1,40}$/.test(activeKey)) fail('LOCAL_LEDGER_INVALID_KEYRING')
  let keys
  try { keys = JSON.parse(required(e, 'ERASURE_LEDGER_KEYS_JSON')) } catch { fail('LOCAL_LEDGER_INVALID_KEYRING') }
  if (!keys || typeof keys !== 'object' || Array.isArray(keys) || Object.keys(keys).length > 10 || !Object.hasOwn(keys, activeKey))
    fail('LOCAL_LEDGER_INVALID_KEYRING')
  for (const [id, value] of Object.entries(keys))
    if (!/^[a-zA-Z0-9_-]{1,40}$/.test(id) || typeof value !== 'string' || Buffer.from(value, 'base64').length !== 32 ||
        Buffer.from(value, 'base64').toString('base64') !== value) fail('LOCAL_LEDGER_INVALID_KEYRING')
  const token = required(e, 'ERASURE_CHECKPOINT_GITHUB_TOKEN')
  if (!/^[A-Za-z0-9_]{20,255}$/.test(token)) fail('LOCAL_LEDGER_INVALID_CHECKPOINT_TOKEN')
  const redis = required(e, 'REDIS_PASSWORD')
  const config = {
    ACCOUNT_LIFECYCLE_MAINTENANCE: 'true', ACCOUNT_RETENTION_ENABLED: 'false', ACCOUNT_ERASURE_ENABLED: 'false',
    ERASURE_LEDGER_ENABLED: 'false', ERASURE_LEDGER_CATALOGUE_ENABLED: 'false', ERASURE_CHECKPOINT_ENABLED: 'false',
    ERASURE_LEDGER_PROVIDER: 'LOCAL', ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED: 'false',
    ERASURE_LEDGER_LOCAL_DIRECTORY: '/home/luha/geupddong-erasure-ledger', ERASURE_LEDGER_LOCAL_STORE_ID: storeId,
    ERASURE_LEDGER_REALM: 'production', ERASURE_LEDGER_ACTIVE_KEY_ID: activeKey,
    ERASURE_LEDGER_KEYS_JSON: JSON.stringify(keys), ERASURE_CHECKPOINT_GITHUB_TOKEN: token,
    ERASURE_CHECKPOINT_DATABASE_EPOCH: epoch, REDIS_PASSWORD: redis,
  }
  // Explicit allowlist: never read or serialize the old R2 endpoint, bucket, access key or secret.
  const dotenv = Object.entries(config).map(([key,value]) => key + "='" + safe(value) + "'").join('\n') + '\n'
  return {
    payload: Buffer.from(dotenv).toString('base64'),
    redisPayload: Buffer.from("REDIS_PASSWORD='" + redis + "'\n").toString('base64'),
    redisConfigured: true, ledgerConfigured: true, storageProvider: 'LOCAL',
    runtimeUid: 1000, runtimeGid: 1000, filesystemVerified: false, activationAllowed: false,
  }
}
