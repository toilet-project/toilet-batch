"""Publish immutable artifacts first, then conditionally replace the manifest."""
import argparse
import datetime as dt
import gzip
import hashlib
import json
import os
import urllib.error
import urllib.request
from pathlib import Path
from catalog import build, canonical
from monthly import build_monthly, files as monthly_files, read_snapshot, snapshot

def request(origin, path, token=None, method='GET', body=None, headers=None):
    request_headers = {'User-Agent':'geupddong-mobile-catalog/1','Accept-Encoding':'identity', **(headers or {})}
    if token: request_headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(origin+'/'+path,data=body,method=method,headers=request_headers)
    with urllib.request.urlopen(req,timeout=180) as response:
        raw = response.read()
        # R2 objects are gzip artifacts; the edge may omit Content-Encoding for identity clients.
        return gzip.decompress(raw) if raw.startswith(b'\x1f\x8b') else raw

def publish(source, output, origin, token, register=False, retire=()):
    if not origin.startswith('https://') or '/' in origin[8:]: raise ValueError('HTTPS origin required')
    output = Path(output); output.mkdir(parents=True,exist_ok=True)
    now = dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    old = old_monthly = None
    try: old = json.loads(request(origin,'admin/latest',token))
    except urllib.error.HTTPError as error:
        if error.code != 404: raise
    try: old_monthly = json.loads(request(origin,'admin/monthly/latest',token))
    except urllib.error.HTTPError as error:
        if error.code != 404: raise
    previous_db = previous_manifest = None
    if old:
        previous_manifest = output/'previous.json'
        previous_manifest.write_text(canonical(old),encoding='utf-8')
        previous_db = output/'previous.sqlite'
        raw = request(origin,old['full']['key'])
        if len(raw) != old['full']['bytes'] or hashlib.sha256(raw).hexdigest() != old['full']['sha256']: raise ValueError('Previous full snapshot integrity failed')
        previous_db.write_bytes(raw)
    manifest = build(source,output,previous_db,previous_manifest,now=now)
    if manifest:
        # Legacy clients still need one current full snapshot. V2 owns checkpoint/rollback retention.
        manifest['previousFull'] = None
        for entry in [manifest['full'], *[d for d in manifest['deltas'] if d['toVersion']==manifest['version']]]:
            body = (output/entry['key']).read_bytes()
            request(origin,'admin/object/'+entry['key'],token,'PUT',body,{'Content-Type':'application/octet-stream','X-Content-SHA256':entry['gzipSha256']})
        request(origin,'admin/commit',token,'POST',canonical(manifest).encode(),{'Content-Type':'application/json','X-Previous-Version':old['version'] if old else ''})
    current = manifest or old
    records = read_snapshot(output/'catalog.sqlite' if manifest else previous_db, current)
    # Keep only one additional snapshot in memory. Registered APK bases can grow
    # over time; holding every decoded full catalog would exhaust the batch host.
    cache = {}
    def load_snapshot(value):
        if value['version'] == current['version']:
            return records
        if value['version'] not in cache:
            f = value['full']; raw = request(origin, f['key'])
            if len(raw) != f['bytes'] or hashlib.sha256(raw).hexdigest() != f['sha256']:
                raise ValueError('Baseline integrity failed')
            path = output / f"baseline-{value['version']}.sqlite"
            path.write_bytes(raw)
            loaded = read_snapshot(path, value)
            cache.clear()
            cache[value['version']] = loaded
        return cache[value['version']]
    def load_patch(value):
        raw = request(origin, value['key'])
        if len(raw) != value['bytes'] or hashlib.sha256(raw).hexdigest() != value['sha256']:
            raise ValueError('Patch integrity failed')
        return json.loads(raw)
    monthly = build_monthly(records, current, old_monthly, load_snapshot, load_patch, output, now,
                            register=register, retire=retire, bootstrap=old,
                            extra_retained=[old['full']] if old else [])
    if monthly:
        for entry in monthly_files(monthly):
            path = output/entry['key']
            if not path.exists(): continue
            request(origin,'admin/object/'+entry['key'],token,'PUT',path.read_bytes(),
                    {'Content-Type':'application/octet-stream','X-Content-SHA256':entry['gzipSha256']})
        request(origin,'admin/monthly/commit',token,'POST',canonical(monthly).encode(),
                {'Content-Type':'application/json','X-Previous-Revision':old_monthly['revision'] if old_monthly else ''})
    # Also prune on unchanged days; grace protects in-progress downloads and stale manifest caches.
    cleanup=json.loads(request(origin,'admin/prune',token,'POST',b''))
    current_monthly=monthly or old_monthly
    report=dict(changed=bool(manifest),version=current['version'],count=current['count'],locales=current['locales'],
                translationCounts=current['translationCounts'],fullBytes=current['full']['bytes'],downloadBytes=current['full']['gzipBytes'],removed=cleanup['removed'],
                monthlyChanged=bool(monthly),month=current_monthly['month'],checkpoint=current_monthly['checkpoint']['version'],
                baselines=len(current_monthly['baselines']),dailyFiles=len(current_monthly['daily']),
                intraMonthBytes=(current_monthly.get('intraMonth') or {}).get('gzipBytes',0))
    (output/'report.json').write_text(canonical(report)+'\n',encoding='utf-8')
    print(canonical(report))

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('source'); parser.add_argument('output')
    parser.add_argument('--register-baseline',action='store_true')
    parser.add_argument('--retire-baseline',action='append',default=[])
    args=parser.parse_args()
    publish(args.source,args.output,os.environ['MOBILE_CATALOG_ORIGIN'].rstrip('/'),os.environ['MOBILE_CATALOG_PUBLISH_TOKEN'],args.register_baseline,args.retire_baseline)
