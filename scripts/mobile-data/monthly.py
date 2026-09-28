"""Monthly checkpoints, immutable baseline patches and a replaceable month-to-date patch."""
import datetime as dt
import hashlib
import json
import sqlite3
from contextlib import closing
from pathlib import Path
from catalog import LOCALES, artifact, canonical


def read_snapshot(path, snapshot):
    with closing(sqlite3.connect(f'{Path(path).resolve().as_uri()}?mode=ro', uri=True)) as db:
        if db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError('Snapshot integrity failed')
        if db.execute("SELECT value FROM metadata WHERE key='version'").fetchone()[0] != snapshot['version']:
            raise ValueError('Snapshot version mismatch')
        records = {i: json.loads(payload) for i, payload in db.execute('SELECT id,payload FROM toilets')}
    if len(records) != snapshot['count']:
        raise ValueError('Snapshot count mismatch')
    return records


def snapshot(manifest):
    return {key: manifest[key] for key in ('version', 'count', 'contentSha256', 'full')}


def files(manifest, now=None):
    if not manifest:
        return []
    values = [manifest['checkpoint']['full']]
    if manifest.get('previousCheckpoint'):
        values.append(manifest['previousCheckpoint']['full'])
    values += [b['full'] for b in manifest['baselines']]
    values += manifest['monthly'] + manifest['daily']
    if manifest.get('intraMonth'):
        values.append(manifest['intraMonth'])
    values += [r['artifact'] for r in manifest.get('retained', []) if now is None or r['until'] > now]
    return list({f['key']: f for f in values}.values())


def apply(records, patch, expected_from):
    if patch['fromVersion'] != expected_from:
        raise ValueError('Broken patch chain')
    result = dict(records)
    for identifier in patch['deleted']:
        result.pop(identifier, None)
    for record in patch['upserts']:
        result[record['id']] = record
    if len(result) != patch['count']:
        raise ValueError('Patch count mismatch')
    return result


def make_patch(old, new, start, end, prefix, output, now):
    if start == end:
        return None
    patch = dict(schemaVersion=1, fromVersion=start, toVersion=end, count=len(new),
                 upserts=[new[i] for i in sorted(new) if old.get(i) != new[i]],
                 deleted=sorted(set(old) - set(new)))
    key = f'{prefix}/{end}.json.gz' if prefix == 'daily' else f'{prefix}/{start}/{end}.json.gz'
    result = artifact(output, key, canonical(patch).encode(), now)
    result.update(fromVersion=start, toVersion=end, count=len(new))
    return result


def build_monthly(records, latest, old, load_snapshot, load_patch, output, now,
                  register=False, retire=(), bootstrap=None, extra_retained=()):
    """Loaders only read checksum-verified immutable artifacts. No user-specific generation."""
    output = Path(output)
    instant = dt.datetime.fromisoformat(now.replace('Z', '+00:00'))
    month = instant.astimezone(dt.timezone(dt.timedelta(hours=9))).strftime('%Y-%m')
    retire = set(retire)
    if old and old['version'] > latest['version']:
        raise ValueError('Cannot roll back catalog version')
    rollover = not old or old['month'] != month
    if old and not rollover and old['version'] == latest['version'] and not register and not retire:
        return None

    checkpoint = snapshot(latest) if rollover else old['checkpoint']
    previous = old['checkpoint'] if old and rollover else (old or {}).get('previousCheckpoint')
    baselines = [b for b in (old or {}).get('baselines', []) if b['version'] not in retire]
    if not old:
        baselines.append(snapshot(bootstrap or latest))
    if register and latest['version'] not in {b['version'] for b in baselines}:
        baselines.append(snapshot(latest))
    baselines.sort(key=lambda b: b['version'])
    if not baselines:
        # Keep a useful release baseline even after deliberately retiring every old one.
        baselines = [snapshot(latest)]
    if len(baselines) > 128:
        raise ValueError('Retire unused baselines before registering more than 128')

    checkpoint_records = records if checkpoint['version'] == latest['version'] else load_snapshot(checkpoint)
    previous_records = None
    if old and old['version'] != latest['version']:
        previous_records = load_snapshot(old['checkpoint'])
        if old.get('intraMonth'):
            previous_records = apply(previous_records, load_patch(old['intraMonth']), old['checkpoint']['version'])
        if len(records) < len(previous_records) * .8:
            raise ValueError('More than 20% removed; investigate export before publishing')

    daily = [] if rollover else list(old['daily'])
    if not rollover and previous_records is not None:
        daily.append(make_patch(previous_records, records, old['version'], latest['version'], 'daily', output, now))
    # Manual runs can create more than one version on a day; the month rollup always bridges gaps.
    daily = daily[-100:]
    intra = make_patch(checkpoint_records, records, checkpoint['version'], latest['version'], 'intra', output, now)
    monthly = []
    old_monthly = {p['fromVersion']: p for p in (old or {}).get('monthly', [])}
    for base in baselines:
        if base['version'] >= checkpoint['version']:
            continue
        existing = old_monthly.get(base['version'])
        if existing and existing['toVersion'] == checkpoint['version']:
            monthly.append(existing)
        else:
            monthly.append(make_patch(load_snapshot(base), checkpoint_records, base['version'],
                                      checkpoint['version'], 'monthly', output, now))
    result = dict(schemaVersion=2, storageVersion=1, version=latest['version'], count=len(records),
                  contentSha256=latest['contentSha256'], locales=LOCALES, month=month, publishedAt=now,
                  checkpoint=checkpoint, previousCheckpoint=previous, baselines=baselines,
                  releaseBaselineVersion=baselines[-1]['version'], monthly=monthly, daily=daily, intraMonth=intra,
                  retained=[])
    keep = {f['key'] for f in files(result)}
    retained = {r['artifact']['key']: r for r in (old or {}).get('retained', [])
                if r['until'] > now and r['artifact']['key'] not in keep}
    until = (instant + dt.timedelta(hours=48)).strftime('%Y-%m-%dT%H:%M:%SZ')
    # Monthly cumulative files are archived indefinitely. Others get 48h after losing their reference.
    for f in [*files(old, now), *extra_retained]:
        if f['key'] not in keep and not f['key'].startswith('monthly/'):
            retained.setdefault(f['key'], dict(artifact=f, until=until))
    result['retained'] = list(retained.values())
    result['revision'] = hashlib.sha256(canonical(result).encode()).hexdigest()
    (output / 'monthly.json').write_text(canonical(result) + '\n', encoding='utf-8')
    return result
