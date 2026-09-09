#!/usr/bin/env python3
"""Install only after approval as /home/luha/erasure-tools/local-ledger-preflight.
Reads running config in memory; calls a pinned locally installed Java tool. No config writes.
R2 initial cutover requires zero history. Paused LOCAL redeployment authenticates its inventory.
Candidate configuration arrives on stdin, never command arguments or public output.
"""
import hashlib
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT='/home/luha/geupddong-erasure-ledger'
FLAGS=('ACCOUNT_RETENTION_ENABLED','ACCOUNT_ERASURE_ENABLED','ERASURE_LEDGER_ENABLED',
       'ERASURE_LEDGER_CATALOGUE_ENABLED','ERASURE_CHECKPOINT_ENABLED')
IDENTITY=('ERASURE_LEDGER_KEYS_JSON','ERASURE_LEDGER_ACTIVE_KEY_ID','ERASURE_CHECKPOINT_DATABASE_EPOCH',
          'ERASURE_LEDGER_REALM')

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
    if values.get('ERASURE_LEDGER_PROVIDER')=='LOCAL':
        require(item['Config'].get('User')=='1000:1000')
        mounts=[m for m in item['Mounts'] if m['Destination']==ROOT]
        require(len(mounts)==1 and mounts[0]['Type']=='bind' and mounts[0]['Source']==ROOT and mounts[0]['RW'] is True)
    stable={k:item[k] for k in ('Id','Image','Config','RestartCount','Mounts','HostConfig')}
    # Docker may enumerate the same mounts in a different order on each inspect.
    # Canonicalize only enumeration order; retain every mount field and duplicate.
    stable['Mounts']=sorted(item['Mounts'],key=lambda mount:json.dumps(mount,sort_keys=True))
    stable['started']=item['State']['StartedAt']
    return values,hashlib.sha256(json.dumps(stable,sort_keys=True).encode()).hexdigest()

def directory_probe(image, store_id, tools, mode):
    require(re.fullmatch(r'sha256:[a-f0-9]{64}',image))
    require(mode in ('R2','LOCAL'))
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
          '-cp','/verification/lib/*','com.geupddong.account.LocalLedgerDirectoryProbe',store_id,mode]
    result=subprocess.run(args,capture_output=True,text=True,timeout=40)
    require(result.returncode==0 and result.stdout.strip()=='LOCAL_DOCKER_DIRECTORY_PASS readable=true')

def paused(values):
    require(values.get('ACCOUNT_LIFECYCLE_MAINTENANCE')=='true')
    for flag in FLAGS: require(values.get(flag)=='false')
    require(values.get('ERASURE_LEDGER_REALM')=='production')

def local_identity(values, store_id):
    require(values.get('ERASURE_LEDGER_PROVIDER')=='LOCAL')
    require(values.get('ERASURE_LEDGER_LOCAL_DIRECTORY')==ROOT)
    require(values.get('ERASURE_LEDGER_LOCAL_STORE_ID')==store_id)
    require(values.get('ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED')=='false')
    for key in ('ENDPOINT','BUCKET','ACCESS_KEY_ID','SECRET_ACCESS_KEY'):
        require(not values.get('ERASURE_LEDGER_'+key))

def candidate_payload(encoded):
    require(0<len(encoded)<=32768)
    content=base64.b64decode(encoded,validate=True).decode('utf-8')
    values={}
    for line in content.splitlines():
        match=re.fullmatch(r"([A-Z_]+)='([^'\r\n\x00]*)'",line)
        require(match is not None and match[1] not in values)
        values[match[1]]=match[2]
    expected=set(FLAGS+IDENTITY+('ACCOUNT_LIFECYCLE_MAINTENANCE','ERASURE_LEDGER_PROVIDER',
        'ERASURE_LEDGER_LOCAL_DIRECTORY','ERASURE_LEDGER_LOCAL_STORE_ID','ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED',
        'ERASURE_CHECKPOINT_GITHUB_TOKEN','REDIS_PASSWORD'))
    require(set(values)==expected)
    return values

def select_mode(configs, role, store_id, candidate):
    for values,_ in configs.values():
        paused(values)
        mode=values.get('ERASURE_LEDGER_PROVIDER','R2')
        require(mode in ('R2','LOCAL'))
        if mode=='LOCAL': local_identity(values,store_id)
        else: require(values.get('ERASURE_LEDGER_BUCKET')=='geupddong-account-erasure-ledger-us')
    a,b=configs['api'][0],configs['batch'][0]
    for key in IDENTITY: require(a.get(key) and a.get(key)==b.get(key))
    paused(candidate); local_identity(candidate,store_id)
    source=configs[role][0]
    for key in IDENTITY+('ERASURE_CHECKPOINT_GITHUB_TOKEN',):
        require(source.get(key) and source.get(key)==candidate.get(key))
    mode=source.get('ERASURE_LEDGER_PROVIDER','R2')
    if mode=='LOCAL':
        # Mixed providers are allowed only while cutting the remaining R2 role over.
        require(all(v.get('ERASURE_LEDGER_PROVIDER')=='LOCAL' for v,_ in configs.values()))
    return mode

def main():
    role,store_id=parse_args(sys.argv[1:]); require(os.getuid()==1000 and os.getgid()==1000)
    configs={r:config('toilet-'+r) for r in ('api','batch')}
    mode=select_mode(configs,role,store_id,candidate_payload(sys.stdin.read(32769)))
    source=configs[role][0]
    child={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','LOCAL_PREFLIGHT_READONLY':'approved','LOCAL_STORE_ID':store_id,
           'CHECKPOINT_TOKEN':source.get('ERASURE_CHECKPOINT_GITHUB_TOKEN',''),
           'DATABASE_EPOCH':source.get('ERASURE_CHECKPOINT_DATABASE_EPOCH','')}
    if mode=='R2':
        child.update(SOURCE_ENDPOINT=source.get('ERASURE_LEDGER_ENDPOINT',''),
                     SOURCE_ID=source.get('ERASURE_LEDGER_ACCESS_KEY_ID',''),
                     SOURCE_SECRET=source.get('ERASURE_LEDGER_SECRET_ACCESS_KEY',''))
        entry='LocalLedgerEmptyPreflight'
        expected='LOCAL_EMPTY_PREFLIGHT_PASS records=0 checkpointMatched=true directoryVerified=true activationAllowed=false'
    else:
        child.update(LEDGER_KEYS_JSON=source['ERASURE_LEDGER_KEYS_JSON'],LEDGER_ACTIVE_KEY_ID=source['ERASURE_LEDGER_ACTIVE_KEY_ID'])
        entry='LocalLedgerRedeploymentPreflight'
        expected='LOCAL_REDEPLOYMENT_PREFLIGHT_PASS inventoryVerified=true activationAllowed=false'
    require(all(child.values()))
    tools=Path('/home/luha/erasure-tools/local-redeployment-v1')
    require(tools.resolve()==tools and not tools.is_symlink())
    # New library directory is installed separately; never silently use the old restore-tool jar.
    result=subprocess.run(['java','-Xmx128m','-XX:ActiveProcessorCount=1','-cp',str(tools/'lib')+'/*',
                           'com.geupddong.account.'+entry],env=child,capture_output=True,text=True,timeout=150)
    require(all(before==config('toilet-'+r)[1] for r,(_,before) in configs.items()))
    require(result.returncode==0 and result.stdout.strip()==expected)
    image=subprocess.run(['docker','inspect','--format','{{.Image}}','toilet-'+role],capture_output=True,text=True,timeout=15)
    require(image.returncode==0)
    directory_probe(image.stdout.strip(),store_id,tools,mode)
    require(all(before==config('toilet-'+r)[1] for r,(_,before) in configs.items()))
    print(result.stdout.strip())

if __name__=='__main__':
    try: main()
    except Exception:
        print('LOCAL_DEPLOYMENT_PREFLIGHT_FAILED detailsSuppressed=true',file=sys.stderr); sys.exit(1)
