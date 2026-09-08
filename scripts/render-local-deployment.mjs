import {readFileSync} from 'node:fs'
import {pathToFileURL} from 'node:url'

// Candidate only: never writes .github/workflows, contacts a server or deploys.
export function renderLocalDeployment(source, role) {
  if (!['api','batch'].includes(role)) throw Error('INVALID_ROLE')
  let text=source.replaceAll('\r\n','\n')
  const replace=(a,b)=> {
    if (text.split(a).length !== 2) throw Error('DEPLOYMENT_BASE_DRIFT')
    text=text.replace(a,b)
  }
  replace("          ERASURE_LEDGER_DEPLOYMENT_PROFILE: 'us-runtime'",
    `          ERASURE_LEDGER_DEPLOYMENT_PROFILE: 'local-paused'
          ERASURE_LEDGER_PROVIDER: 'LOCAL'
          ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED: \${{ vars.ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED || 'false' }}
          ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED: 'false'
          ERASURE_LEDGER_LOCAL_DIRECTORY: '/var/lib/geupddong-erasure-ledger'
          ERASURE_LEDGER_LOCAL_STORE_ID: \${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}
          LOCAL_LEDGER_RUNTIME_UID: '1000'
          LOCAL_LEDGER_RUNTIME_GID: '1000'`)
  for (const key of ['ERASURE_LEDGER_US_DEPLOYMENT_APPROVED','ERASURE_LEDGER_ENDPOINT','ERASURE_LEDGER_BUCKET',
    'ERASURE_LEDGER_ACCESS_KEY_ID','ERASURE_LEDGER_SECRET_ACCESS_KEY']) {
    const lines=text.split('\n').filter(line=>line.startsWith('          '+key+':'))
    if(lines.length!==1) throw Error('DEPLOYMENT_BASE_DRIFT')
    replace(lines[0]+'\n','')
  }
  replace('            set -eu\n            umask 077', `            set -eu
            umask 077
            # Read-only preflight must finish BEFORE touching operational configuration.
            test -x /home/luha/erasure-tools/local-ledger-preflight
            /home/luha/erasure-tools/local-ledger-preflight ${role} '\${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}'`)
  const service=role==='api' ? 'api' : 'toilet-batch'
  const userLine=`              ${service}:\n                user: "1000:1000"`
  replace(`              ${service}:`,userLine)
  const mount=`                  - type: bind
                    source: /var/lib/geupddong-erasure-ledger
                    target: /var/lib/geupddong-erasure-ledger
                    read_only: false
                    bind:
                      create_host_path: false`
  if(role==='batch') replace('                  - ./region-results:/var/lib/toilet-region',
    '                  - ./region-results:/var/lib/toilet-region\n'+mount)
  else {
    replace('                container_name: toilet-api', '                container_name: toilet-api\n                volumes:\n'+mount)
    // The existing Redis configuration is already deployed: this release must not recreate it.
    replace('            docker compose pull', '            docker compose pull api')
    replace('            docker compose up -d --wait --wait-timeout 120 redis api',
      '            docker compose up -d --no-deps --wait --wait-timeout 120 api')
  }
  return '# REVIEW CANDIDATE ONLY: not installed or approved for production.\n'+text
}

if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
  const role=process.argv[2]
  const source=readFileSync(new URL('../.github/workflows/deploy.yml',import.meta.url),'utf8')
  process.stdout.write(renderLocalDeployment(source,role))
}
