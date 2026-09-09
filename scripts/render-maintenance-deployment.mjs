// Review candidate only. Does not install, enable lifecycle actions, or contact a server.
export function renderMaintenancePreparation(source, role) {
  if (!['api', 'batch'].includes(role)) throw Error('INVALID_ROLE')
  let text = source.replaceAll('\r\n', '\n')
  if (text.includes('ERASURE_MAINTENANCE_')) throw Error('ALREADY_CONFIGURED')
  for (const flag of ['ACCOUNT_RETENTION_ENABLED', 'ACCOUNT_ERASURE_ENABLED', 'ERASURE_LEDGER_ENABLED',
    'ERASURE_LEDGER_CATALOGUE_ENABLED', 'ERASURE_CHECKPOINT_ENABLED'])
    if (!text.includes(flag + ": 'false'")) throw Error('NOT_PAUSED')
  if (!text.includes("ACCOUNT_LIFECYCLE_MAINTENANCE: 'true'") ||
      !text.includes("ERASURE_LEDGER_PROVIDER: 'LOCAL'")) throw Error('NOT_LOCAL_PAUSED')
  const replace = (before, after) => {
    if (text.split(before).length !== 2) throw Error('DEPLOYMENT_BASE_DRIFT')
    text = text.replace(before, after)
  }
  const name = role === 'api' ? 'toilet-api' : 'toilet-batch'
  const identity = '                container_name: ' + name
  replace(identity, identity + '\n' +
    "                environment:\n" +
    "                  ERASURE_MAINTENANCE_LOCK_ENABLED: 'true'\n" +
    "                  ERASURE_MAINTENANCE_DIRECTORY: '/home/luha/geupddong-maintenance'")
  const mount = '                    source: /home/luha/geupddong-erasure-ledger\n' +
    '                    target: /home/luha/geupddong-erasure-ledger\n' +
    '                    read_only: false\n                    bind:\n                      create_host_path: false'
  replace(mount, mount + '\n                  - type: bind\n' +
    '                    source: /home/luha/geupddong-maintenance\n' +
    '                    target: /home/luha/geupddong-maintenance\n' +
    '                    read_only: false\n                    bind:\n                      create_host_path: false')
  // This separately reviewed checker is deliberately a prerequisite, not auto-installed here.
  const check = '            test -x /home/luha/erasure-tools/local-ledger-preflight'
  replace(check, '            /home/luha/.local/bin/maintenance-preflight ' + role + '\n' + check)
  return '# MAINTENANCE PREPARATION CANDIDATE: not installed; lifecycle remains paused.\n' + text
}
