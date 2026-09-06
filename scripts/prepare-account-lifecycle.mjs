import {appendFileSync} from 'node:fs'
import {pathToFileURL} from 'node:url'

const fail = code => { throw new Error(code) }
const safe = value => {
  const s = value ?? ''
  if (typeof s !== 'string' || /[\r\n\0']/.test(s)) fail('LIFECYCLE_UNSAFE_CONFIG_VALUE')
  return s
}
export function prepare(e, role) {
  if (!['api','batch'].includes(role)) fail('LIFECYCLE_INVALID_ROLE')
  if (e.ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED !== 'true') fail('LIFECYCLE_DEPLOYMENT_APPROVAL_REQUIRED')
  // This deployment stage is intentionally preparation-only. Activation needs a separately reviewed release.
  if (e.ACCOUNT_LIFECYCLE_MAINTENANCE !== 'true' || e.ACCOUNT_RETENTION_ENABLED !== 'false' ||
      e.ACCOUNT_ERASURE_ENABLED !== 'false' || e.ERASURE_LEDGER_ENABLED !== 'false' ||
      e.ERASURE_LEDGER_CATALOGUE_ENABLED !== 'false') fail('LIFECYCLE_ACTIVATION_NOT_APPROVED')
  const config = Object.fromEntries(['ACCOUNT_LIFECYCLE_MAINTENANCE','ACCOUNT_RETENTION_ENABLED','ACCOUNT_ERASURE_ENABLED',
    'ERASURE_LEDGER_ENABLED','ERASURE_LEDGER_CATALOGUE_ENABLED','ERASURE_LEDGER_ENDPOINT',
    'ERASURE_LEDGER_ACCESS_KEY_ID','ERASURE_LEDGER_SECRET_ACCESS_KEY','REDIS_PASSWORD'].map(k => [k,safe(e[k])]))
  config.ERASURE_LEDGER_REALM = safe(e.ERASURE_LEDGER_REALM || 'production')
  config.ERASURE_LEDGER_BUCKET = safe(e.ERASURE_LEDGER_BUCKET || 'geupddong-account-erasure-ledger')
  config.ERASURE_LEDGER_ACTIVE_KEY_ID = safe(e.ERASURE_LEDGER_ACTIVE_KEY_ID || 'k1')
  if (!/^[a-z0-9-]{3,40}$/.test(config.ERASURE_LEDGER_REALM) ||
      !/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(config.ERASURE_LEDGER_BUCKET) ||
      !/^[a-zA-Z0-9_-]{1,40}$/.test(config.ERASURE_LEDGER_ACTIVE_KEY_ID)) fail('LIFECYCLE_INVALID_LEDGER_IDENTITY')
  if (config.ERASURE_LEDGER_ENDPOINT) {
    let u
    try { u = new URL(config.ERASURE_LEDGER_ENDPOINT) } catch { fail('LIFECYCLE_INVALID_ENDPOINT') }
    if (u.protocol !== 'https:' || !/^[a-f0-9]{32}(\.(eu|us))?\.r2\.cloudflarestorage\.com$/.test(u.hostname) ||
        u.username || u.password || u.port || u.search || u.hash || u.pathname !== '/') fail('LIFECYCLE_INVALID_ENDPOINT')
  }
  let keys
  if (e.ERASURE_LEDGER_KEYS_JSON) {
    try { keys = JSON.parse(e.ERASURE_LEDGER_KEYS_JSON) } catch { fail('LIFECYCLE_INVALID_KEYRING') }
    if (!keys || Array.isArray(keys) || typeof keys !== 'object' || Object.keys(keys).length > 10 ||
        !Object.hasOwn(keys,config.ERASURE_LEDGER_ACTIVE_KEY_ID)) fail('LIFECYCLE_INVALID_KEYRING')
    for (const [id,value] of Object.entries(keys)) {
      if (!/^[a-zA-Z0-9_-]{1,40}$/.test(id) || typeof value !== 'string' ||
          Buffer.from(value,'base64').length !== 32 || Buffer.from(value,'base64').toString('base64') !== value) fail('LIFECYCLE_INVALID_KEYRING')
    }
  }
  config.ERASURE_LEDGER_KEYS_JSON = keys ? JSON.stringify(keys) : ''
  if (role === 'api' && !config.REDIS_PASSWORD) fail('LIFECYCLE_API_REDIS_REQUIRED')
  // Single-quoted Compose dotenv prevents interpolation; transport is base64, never shell-interpreted JSON.
  const dotenv = Object.entries(config).map(([k,v]) => k + "='" + safe(v) + "'").join('\n') + '\n'
  return {payload:Buffer.from(dotenv).toString('base64'),
    redisPayload:Buffer.from("REDIS_PASSWORD='" + safe(config.REDIS_PASSWORD) + "'\n").toString('base64'),
    redisConfigured:!!config.REDIS_PASSWORD,
    ledgerConfigured:!!(keys && config.ERASURE_LEDGER_ENDPOINT && config.ERASURE_LEDGER_ACCESS_KEY_ID && config.ERASURE_LEDGER_SECRET_ACCESS_KEY)}
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const result = prepare(process.env,process.argv[2])
    if (!process.env.GITHUB_OUTPUT) fail('LIFECYCLE_GITHUB_OUTPUT_REQUIRED')
    console.log('::add-mask::' + result.payload)
    console.log('::add-mask::' + result.redisPayload)
    appendFileSync(process.env.GITHUB_OUTPUT,'payload=' + result.payload + '\n')
    appendFileSync(process.env.GITHUB_OUTPUT,'redisPayload=' + result.redisPayload + '\n')
    console.log(JSON.stringify({lifecyclePreparationOnly:true,redisConfigured:result.redisConfigured,ledgerConfigured:result.ledgerConfigured}))
  } catch { console.error('LIFECYCLE_DEPLOYMENT_PREFLIGHT_FAILED'); process.exitCode=1 }
}
