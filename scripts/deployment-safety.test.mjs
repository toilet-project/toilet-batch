import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
const workflow = readFileSync(new URL('../.github/workflows/deploy.yml', import.meta.url), 'utf8')
test('preparation deployment preserves rollback config and uses the exact commit image', () => {
  assert.ok(workflow.includes('toilet-batch:${{ github.sha }}'))
  assert.ok(workflow.includes('set -eu'))
  assert.ok(workflow.includes('cp -p -- "$file" "$rollback_dir/"'))
  assert.ok(workflow.indexOf('umask 077') < workflow.indexOf('cat <<EOF > .env'))
  assert.ok(workflow.includes("cat <<'EOF' > docker-compose.yml"))
  assert.ok(workflow.includes('config --quiet'))
  assert.doesNotMatch(workflow, /docker image prune|--remove-orphans|docker volume rm/)
})
test('preparation workflow does not enable account erasure or checkpoint writes', () => {
  for (const flag of ['ACCOUNT_RETENTION_ENABLED', 'ACCOUNT_ERASURE_ENABLED', 'ERASURE_LEDGER_ENABLED',
    'ERASURE_LEDGER_CATALOGUE_ENABLED', 'ERASURE_CHECKPOINT_ENABLED']) {
    assert.ok(workflow.includes(`${flag}: 'false'`))
  }
  assert.ok(workflow.includes("ACCOUNT_LIFECYCLE_MAINTENANCE: 'true'"))
})
