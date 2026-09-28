"""Public mobile catalog builder. Python stdlib only; never reads account tables."""
import argparse
import datetime as dt
import gzip
import hashlib
import json
import sqlite3
from contextlib import closing
from pathlib import Path

LOCALES = ['ko', 'en', 'ja', 'zh-cn', 'zh-tw', 'zh-hk']
FIELDS = set('id name latitude longitude toiletType roadAddress jibunAddress maleToiletCount maleUrinalCount maleDisabledToiletCount maleDisabledUrinalCount maleChildToiletCount maleChildUrinalCount femaleToiletCount femaleDisabledToiletCount femaleChildToiletCount agencyName phoneNumber openTime openTimeDetail installationDate hasEmergencyBell emergencyBellLocation hasCctv hasDiaperTable diaperTableLocation dataBaseDate dataSource region displayGroupId displayGroupName'.split())

def canonical(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=True, allow_nan=False)

def read_export(path):
    records, expected, completed = {}, None, False
    with Path(path).open(encoding='utf-8-sig') as stream:
        for line in stream:
            row = json.loads(line)
            if completed:
                raise ValueError('Unexpected data after completion')
            kind = row['kind']
            if kind == 'meta':
                if expected is not None or records: raise ValueError('Duplicate header')
                expected = row['count']
            elif kind == 'toilet':
                value = row['value']
                if set(value) != FIELDS: raise ValueError('Unexpected public fields')
                identifier = value['id']
                if type(identifier) is not int or identifier <= 0 or identifier in records or not value['name']:
                    raise ValueError('Invalid or duplicate facility')
                value.update(translations={}, displayGroupTranslations={}, normalizedOpeningHours=None)
                records[identifier] = value
            elif kind == 'complete':
                if expected != row['count'] or expected != len(records) or not records: raise ValueError('Incomplete export')
                completed = True
            else:
                value = records[row['id']]
                if kind in ('translation', 'groupTranslation'):
                    locale = row['locale']
                    if locale not in LOCALES[1:]: raise ValueError('Unknown locale')
                    value['translations' if kind == 'translation' else 'displayGroupTranslations'][locale] = row['value']
                elif kind == 'hours':
                    hours = row['value']
                    for key in ('open24h', 'manualOverride', 'sourceChanged'):
                        if hours[key] is not None: hours[key] = bool(hours[key])
                    hours['schedules'] = []
                    value['normalizedOpeningHours'] = hours
                elif kind == 'schedule':
                    slot = row['value']
                    slot.update(crossesMidnight=bool(slot['crossesMidnight']), closed=bool(slot['closed']))
                    value['normalizedOpeningHours']['schedules'].append(slot)
                else: raise ValueError('Unknown export row')
    if not completed: raise ValueError('Truncated export')
    return records

SCHEMA = '''
PRAGMA page_size=32768;
PRAGMA journal_mode=DELETE;
PRAGMA user_version=1;
CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE toilets(id INTEGER PRIMARY KEY,latitude REAL,longitude REAL,sido TEXT,sigungu TEXT,marker TEXT NOT NULL,payload TEXT NOT NULL);
CREATE INDEX toilets_latlng ON toilets(latitude,longitude);
CREATE INDEX toilets_region ON toilets(sido,sigungu);
'''

def db_row(record):
    region = record.get('region') or {}
    marker = {k: record.get(k) for k in ('id','latitude','longitude','name','displayGroupName','displayGroupTranslations','toiletType')}
    marker['translations'] = {locale:{'name':text.get('name')} for locale,text in record['translations'].items()}
    return (record['id'],record['latitude'],record['longitude'],region.get('sidoCode'),region.get('sigunguCode'),canonical(marker),canonical(record))

def write_db(path, records, version):
    if path.exists(): raise ValueError('Output file already exists')
    with closing(sqlite3.connect(path)) as db:
        db.executescript(SCHEMA)
        db.executemany('INSERT INTO toilets VALUES(?,?,?,?,?,?,?)', (db_row(records[i]) for i in sorted(records)))
        db.executemany('INSERT INTO metadata VALUES(?,?)', [('version',version),('count',str(len(records))),('locales',canonical(LOCALES))])
        db.commit()
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'

def artifact(output, key, raw, now):
    path = output / key
    path.parent.mkdir(parents=True,exist_ok=True)
    compressed = gzip.compress(raw, compresslevel=9, mtime=0)
    path.write_bytes(compressed)
    return dict(key=key,bytes=len(raw),gzipBytes=len(compressed),md5=hashlib.md5(raw).hexdigest(),sha256=hashlib.sha256(raw).hexdigest(),gzipSha256=hashlib.sha256(compressed).hexdigest(),createdAt=now)

def build(source, output, previous_db=None, previous_manifest=None, now=None):
    now = now or dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    records = read_export(source)
    serialized = {i:canonical(r) for i,r in records.items()}
    digest = hashlib.sha256(('mobile-catalog-storage-1\n'+'\n'.join(serialized[i] for i in sorted(serialized))).encode()).hexdigest()
    old_manifest = json.loads(Path(previous_manifest).read_text()) if previous_manifest else None
    if old_manifest and old_manifest.get('contentSha256') == digest:
        return None
    version = now.replace('-','').replace(':','') + '-' + digest[:16]
    output = Path(output)
    output.mkdir(parents=True,exist_ok=True)
    path = output / 'catalog.sqlite'
    write_db(path, records, version)
    full = artifact(output, f'full/{version}.sqlite.gz', path.read_bytes(), now)
    manifest = dict(schemaVersion=1,storageVersion=1,version=version,createdAt=now,count=len(records),locales=LOCALES,
                    contentSha256=digest,full=full,previousFull=old_manifest['full'] if old_manifest else None,deltas=[])
    if old_manifest and previous_db and old_manifest.get('storageVersion') == 1:
        with closing(sqlite3.connect(f'{Path(previous_db).resolve().as_uri()}?mode=ro',uri=True)) as db:
            if db.execute("SELECT value FROM metadata WHERE key='version'").fetchone()[0] != old_manifest['version']:
                raise ValueError('Previous snapshot does not match manifest')
            old = dict(db.execute('SELECT id,payload FROM toilets'))
        # A large removal deserves investigation rather than accidentally publishing an empty DB.
        if len(records) < len(old)*0.8: raise ValueError('More than 20% removed; investigate export before publishing')
        delta = dict(schemaVersion=1,fromVersion=old_manifest['version'],toVersion=version,count=len(records),
                     upserts=[records[i] for i in sorted(records) if old.get(i) != serialized[i]],deleted=sorted(set(old)-set(records)))
        entry = artifact(output,f'delta/{version}.json.gz',canonical(delta).encode(),now)
        entry.update(fromVersion=delta['fromVersion'],toVersion=version,count=len(records))
        cutoff = dt.datetime.fromisoformat(now.replace('Z','+00:00')) - dt.timedelta(days=30)
        manifest['deltas'] = [d for d in old_manifest.get('deltas',[]) if dt.datetime.fromisoformat(d['createdAt'].replace('Z','+00:00')) >= cutoff] + [entry]
    manifest['translationCounts'] = {locale:sum(locale in r['translations'] for r in records.values()) for locale in LOCALES[1:]}
    (output/'latest.json').write_text(canonical(manifest)+'\n',encoding='utf-8')
    return manifest

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('source'); parser.add_argument('output'); parser.add_argument('--previous-db'); parser.add_argument('--previous-manifest')
    args=parser.parse_args()
    result=build(args.source,args.output,args.previous_db,args.previous_manifest)
    print(canonical(dict(changed=bool(result),version=result['version'] if result else None,count=result['count'] if result else None)))
