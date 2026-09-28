import copy
import gzip
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from catalog import FIELDS, build, canonical
from monthly import apply, build_monthly, files, read_snapshot, snapshot


class MonthlyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.records = {i: dict.fromkeys(FIELDS) for i in range(1, 7)}
        for i, r in self.records.items():
            r.update(id=i, name=f'시설 {i}', latitude=36.3, longitude=127.3, hasDiaperTable='Y')
        self.latest = self.monthly = self.previous_dir = None
        self.snapshots = {}; self.objects = {}; self.sequence = 0

    def tearDown(self):
        self.temp.cleanup()

    def run_day(self, when, register=False, retire=()):
        self.sequence += 1
        out = self.root / str(self.sequence); out.mkdir()
        source = out / 'source.jsonl'
        rows = [dict(kind='meta', count=len(self.records))]
        rows += [dict(kind='toilet', value=r) for r in self.records.values()]
        rows += [dict(kind='translation', id=1, locale='zh-hk', value={'name':'香港廁所'})]
        rows += [dict(kind='complete', count=len(self.records))]
        source.write_text('\n'.join(canonical(r) for r in rows), encoding='utf-8')
        old_latest = self.latest
        latest = build(source, out / 'legacy',
                       self.previous_dir/'catalog.sqlite' if self.previous_dir else None,
                       self.previous_dir/'latest.json' if self.previous_dir else None, now=when)
        if latest:
            self.latest = latest; self.previous_dir = out / 'legacy'
            self.snapshots[latest['version']] = read_snapshot(self.previous_dir/'catalog.sqlite', latest)
        result = build_monthly(self.snapshots[self.latest['version']], self.latest, self.monthly,
                               lambda s: copy.deepcopy(self.snapshots[s['version']]),
                               lambda p: json.loads(gzip.decompress(self.objects[p['key']])),
                               out, when, register, retire, bootstrap=old_latest)
        if result:
            self.monthly = result
            for path in out.rglob('*.json.gz'):
                self.objects[path.relative_to(out).as_posix()] = path.read_bytes()
        return result

    def patch(self, descriptor):
        return json.loads(gzip.decompress(self.objects[descriptor['key']]))

    def test_month_boundary_and_old_apk_crosses_missing_daily_history(self):
        july = self.run_day('2026-07-01T19:30:00Z')
        original = july['version']
        self.records[1]['name'] = '7월 수정'
        daily = self.run_day('2026-07-15T19:30:00Z')
        self.assertEqual(len(daily['daily']), 1)
        self.records[2]['name'] = '8월 수정'
        august = self.run_day('2026-07-31T19:30:00Z') # August 1 in Korea
        self.assertEqual(august['month'], '2026-08')
        self.assertEqual(august['daily'], [])
        self.assertEqual(august['previousCheckpoint'], july['checkpoint'])
        self.records[3]['name'] = '9월 수정'
        september = self.run_day('2026-08-31T19:30:00Z')
        self.assertEqual(september['previousCheckpoint'], august['checkpoint'])
        cumulative = self.patch(september['monthly'][0])
        self.assertEqual(cumulative['fromVersion'], original)
        restored = apply(self.snapshots[original], cumulative, original)
        self.assertEqual(restored, self.snapshots[september['version']])
        self.assertIn(august['monthly'][0]['key'], self.objects) # archived, not overwritten

    def test_rollup_reversion_deletion_and_translation_survive(self):
        first = self.run_day('2026-09-01T00:00:00Z')
        self.records[1]['name'] = '임시 이름'
        second = self.run_day('2026-09-02T00:00:00Z')
        self.records[1]['name'] = '시설 1'
        del self.records[6]
        third = self.run_day('2026-09-03T00:00:00Z')
        rollup = self.patch(third['intraMonth'])
        self.assertNotIn(1, [r['id'] for r in rollup['upserts']])
        self.assertEqual(rollup['deleted'], [6])
        self.assertEqual(apply(self.snapshots[first['version']], rollup, first['version']), self.snapshots[third['version']])
        corrected = self.patch(third['daily'][-1])
        self.assertIn(1, [r['id'] for r in corrected['upserts']])
        self.assertEqual(corrected['upserts'][0]['translations']['zh-hk']['name'], '香港廁所')
        self.assertTrue(any(r['artifact']['key'] == second['intraMonth']['key'] for r in third['retained']))

    def test_no_change_register_refresh_and_retire_without_resetting_other_clients(self):
        first = self.run_day('2026-09-01T00:00:00Z')
        self.assertIsNone(self.run_day('2026-09-02T00:00:00Z'))
        self.records[1]['name'] = '새 기준'
        latest = self.run_day('2026-09-03T00:00:00Z', register=True)
        self.assertEqual(len(latest['baselines']), 2)
        self.assertEqual(latest['releaseBaselineVersion'], latest['version'])
        retired = self.run_day('2026-09-04T00:00:00Z', retire=[first['version']])
        self.assertEqual(len(retired['baselines']), 1)
        self.assertEqual(retired['checkpoint'], first['checkpoint']) # recovery still works
        self.assertEqual(retired['version'], latest['version'])
        self.assertNotEqual(retired['revision'], latest['revision'])

    def test_unchanged_month_rollover_and_48_hour_reference_grace(self):
        first = self.run_day('2026-08-01T00:00:00Z')
        self.records[1]['name'] = 'midmonth'
        second = self.run_day('2026-08-02T00:00:00Z')
        rollover = self.run_day('2026-09-01T00:00:00Z')
        self.assertEqual(rollover['version'], second['version'])
        self.assertEqual(rollover['checkpoint']['version'], second['version'])
        self.assertEqual(rollover['daily'], [])
        self.assertIn(second['daily'][0]['key'], [f['key'] for f in files(rollover, '2026-09-02T00:00:00Z')])
        self.assertNotIn(second['daily'][0]['key'], [f['key'] for f in files(rollover, '2026-09-04T00:00:00Z')])

    def test_month_boundary_mass_removal_cannot_publish(self):
        self.run_day('2026-08-01T00:00:00Z')
        del self.records[5]; del self.records[6]
        with self.assertRaisesRegex(ValueError, '20%'):
            self.run_day('2026-09-01T00:00:00Z')


if __name__ == '__main__': unittest.main()
