#!/usr/bin/env python3
"""Install only after approval as /home/luha/erasure-tools/local-ledger-preflight.
Reads running config in memory; calls a pinned locally installed Java tool. No config writes.
This zero-history cutover refuses populated ledgers and is not an activation check.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

def require(ok):
    if not ok: raise ValueError('PREFLIGHT_REJECTED')

def parse_args(args):
    require(len(args)==2 and args[0] in ('api','batch'))
    require(re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}',args[1]))
    return args

def config(name):
    result=subprocess.run(['docker','inspect',name],capture_output=True,text=True,timeout=15)
    require(result.returncode==0)
    item=json.loads(result.stdout)[0]; require(item['State']['Running'])
    values={}
    for pair in item['Config']['Env']:
        key,sep,value=pair.partition('='); require(sep and key not in values); values[key]=value
    stable={k:item[k] for k in ('Id','Image','Config','RestartCount')}
    stable['started']=item['State']['StartedAt']
    return values,hashlib.sha256(json.dumps(stable,sort_keys=True).encode()).hexdigest()

def directory_probe(image, store_id, tools):
    require(re.fullmatch(r'sha256:[a-f0-9]{64}',image))
    parse_args(['api',store_id])
    root='/home/luha/geupddong-erasure-ledger'
    # --mount never creates an absent host path. The actual service image and exact
    # production directory are tested; no app entrypoint, secrets, DB or network.
    args=['docker','run','--rm','--pull=never','--network','none','--read-only',
          '--user','1000:1000','--cap-drop','ALL','--security-opt','apparmor=docker-default',
          '--memory','128m','--cpus','0.5','--pids-limit','64',
          '--label','geupddong.local-ledger-probe=true',
          '--mount','type=bind,source='+root+',target='+root,
          '--mount','type=bind,source='+str(tools/'lib')+',target=/verification/lib,readonly',
          '--entrypoint','java',image,'-Xmx64m','-XX:ActiveProcessorCount=1',
          '-cp','/verification/lib/*','com.geupddong.account.LocalLedgerDirectoryProbe',store_id]
    result=subprocess.run(args,capture_output=True,text=True,timeout=40)
    require(result.returncode==0 and result.stdout.strip()=='LOCAL_DOCKER_DIRECTORY_PASS records=0')

def main():
    role,store_id=parse_args(sys.argv[1:]); require(os.getuid()==1000 and os.getgid()==1000)
    configs={r:config('toilet-'+r) for r in ('api','batch')}
    for values,_ in configs.values():
        require(values.get('ACCOUNT_LIFECYCLE_MAINTENANCE')=='true')
        for flag in ('ACCOUNT_RETENTION_ENABLED','ACCOUNT_ERASURE_ENABLED','ERASURE_LEDGER_ENABLED',
                     'ERASURE_LEDGER_CATALOGUE_ENABLED','ERASURE_CHECKPOINT_ENABLED'):
            require(values.get(flag)=='false')
    a,b=configs['api'][0],configs['batch'][0]
    for key in ('ERASURE_LEDGER_KEYS_JSON','ERASURE_LEDGER_ACTIVE_KEY_ID','ERASURE_CHECKPOINT_DATABASE_EPOCH'):
        require(a.get(key) and a.get(key)==b.get(key))
    source=configs[role][0] # API first, then batch: this service must still be on paused US R2.
    require(source.get('ERASURE_LEDGER_BUCKET')=='geupddong-account-erasure-ledger-us')
    require(source.get('ERASURE_LEDGER_PROVIDER','R2')=='R2')
    child={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','LOCAL_PREFLIGHT_READONLY':'approved','LOCAL_STORE_ID':store_id,
           'SOURCE_ENDPOINT':source.get('ERASURE_LEDGER_ENDPOINT',''),
           'SOURCE_ID':source.get('ERASURE_LEDGER_ACCESS_KEY_ID',''),
           'SOURCE_SECRET':source.get('ERASURE_LEDGER_SECRET_ACCESS_KEY',''),
           'CHECKPOINT_TOKEN':source.get('ERASURE_CHECKPOINT_GITHUB_TOKEN',''),
           'DATABASE_EPOCH':source.get('ERASURE_CHECKPOINT_DATABASE_EPOCH','')}
    require(all(child.values()))
    tools=Path('/home/luha/erasure-tools/local-cutover-home')
    require(tools.resolve()==tools and not tools.is_symlink())
    # New library directory is installed separately; never silently use the old restore-tool jar.
    result=subprocess.run(['java','-Xmx128m','-XX:ActiveProcessorCount=1','-cp',str(tools/'lib')+'/*',
                           'com.geupddong.account.LocalLedgerEmptyPreflight'],env=child,capture_output=True,text=True,timeout=150)
    require(all(before==config('toilet-'+r)[1] for r,(_,before) in configs.items()))
    require(result.returncode==0 and result.stdout.strip()=='LOCAL_EMPTY_PREFLIGHT_PASS records=0 checkpointMatched=true directoryVerified=true activationAllowed=false')
    image=subprocess.run(['docker','inspect','--format','{{.Image}}','toilet-'+role],capture_output=True,text=True,timeout=15)
    require(image.returncode==0)
    directory_probe(image.stdout.strip(),store_id,tools)
    require(all(before==config('toilet-'+r)[1] for r,(_,before) in configs.items()))
    print(result.stdout.strip())

if __name__=='__main__':
    try: main()
    except Exception:
        print('LOCAL_DEPLOYMENT_PREFLIGHT_FAILED detailsSuppressed=true',file=sys.stderr); sys.exit(1)
