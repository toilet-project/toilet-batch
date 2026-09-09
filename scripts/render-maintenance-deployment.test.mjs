import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {renderMaintenancePreparation} from './render-maintenance-deployment.mjs'
import {renderLocalDeployment} from './render-local-deployment.mjs'
const baseline = readFileSync(new URL('../deploy/us-paused.baseline.yml', import.meta.url), 'utf8')
const role = 'batch'
const source = renderLocalDeployment(baseline, role)
test('candidate keeps actions paused and mounts a pre-existing common lock directory', () => {
  const out = renderMaintenancePreparation(source, role)
  assert.ok(out.includes("ACCOUNT_LIFECYCLE_MAINTENANCE: 'true'"))
  assert.ok(out.includes("ACCOUNT_ERASURE_ENABLED: 'false'"))
  assert.ok(out.includes("ERASURE_MAINTENANCE_LOCK_ENABLED: 'true'"))
  assert.ok(out.includes('source: /home/luha/geupddong-maintenance'))
  assert.equal((out.match(/create_host_path: false/g) || []).length, 2)
  assert.ok(out.indexOf('/maintenance-preflight ') < out.indexOf('mkdir -p ~/toilet-'))
  assert.doesNotMatch(out, /chmod 777|docker.*prune|docker.*volume rm/)
})
test('unknown role, active input, repeated rendering and drift fail closed', () => {
  assert.throws(() => renderMaintenancePreparation(source, 'admin'))
  assert.throws(() => renderMaintenancePreparation(source.replace("ACCOUNT_ERASURE_ENABLED: 'false'", "ACCOUNT_ERASURE_ENABLED: 'true'"), role))
  assert.throws(() => renderMaintenancePreparation(source + source, role))
  assert.throws(() => renderMaintenancePreparation(renderMaintenancePreparation(source, role), role))
})
