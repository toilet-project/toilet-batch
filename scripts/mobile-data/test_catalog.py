import copy
import gzip
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from catalog import FIELDS, LOCALES, build, canonical, read_export

class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.root=Path(self.temp.name)
        self.records=[]
        for i in range(1,7):
            value=dict.fromkeys(FIELDS)
            value.update(id=i,name=f'화장실 {i}',latitude=36.35+i*.001,longitude=127.38,
                         hasDiaperTable='Y',femaleDisabledToiletCount=1,region={'sidoCode':'30','sigunguCode':'30200'})
            self.records.append(value)
    def tearDown(self): self.temp.cleanup()
    def source(self,name='source.jsonl',translations=True):
        rows=[dict(kind='meta',count=len(self.records))]+[dict(kind='toilet',value=r) for r in self.records]
        if translations:
            rows += [dict(kind='translation',id=1,locale=locale,value={'name':locale+' toilet','roadAddress':locale+' address','jibunAddress':None}) for locale in LOCALES[1:]]
        rows.append(dict(kind='complete',count=len(self.records)))
        path=self.root/name; path.write_text('\n'.join(canonical(r) for r in rows),encoding='utf-8'); return path
    def test_all_five_foreign_locales_and_facilities_survive_sqlite(self):
        out=self.root/'v1'; m=build(self.source(),out,now='2026-09-01T00:00:00Z')
        self.assertEqual(m['locales'],LOCALES)
        with closing(sqlite3.connect(out/'catalog.sqlite')) as db:
            r=json.loads(db.execute('SELECT payload FROM toilets WHERE id=1').fetchone()[0])
            self.assertEqual(set(r['translations']),set(LOCALES[1:]))
            self.assertNotEqual(r['translations']['zh-tw'],r['translations']['zh-hk'])
            self.assertEqual(r['hasDiaperTable'],'Y'); self.assertEqual(r['femaleDisabledToiletCount'],1)
        self.assertEqual(gzip.decompress((out/m['full']['key']).read_bytes()),(out/'catalog.sqlite').read_bytes())
    def test_updates_deletion_translation_removal_and_no_change(self):
        out=self.root/'v1'; first=build(self.source(),out,now='2026-09-01T00:00:00Z')
        self.assertIsNone(build(self.source(),self.root/'same',out/'catalog.sqlite',out/'latest.json'))
        self.records.pop(); self.records[0]['name']='수정 화장실'
        second=build(self.source(translations=False),self.root/'v2',out/'catalog.sqlite',out/'latest.json',now='2026-09-02T00:00:00Z')
        delta=json.loads(gzip.decompress((self.root/'v2'/second['deltas'][-1]['key']).read_bytes()))
        self.assertEqual(delta['deleted'],[6]); self.assertEqual([r['id'] for r in delta['upserts']],[1]); self.assertEqual(delta['upserts'][0]['translations'],{})
        self.assertEqual(second['previousFull'],first['full'])
    def test_truncated_or_private_data_is_rejected(self):
        path=self.source(); path.write_text(path.read_text(encoding='utf-8').rsplit('\n',1)[0],encoding='utf-8')
        with self.assertRaises(ValueError): read_export(path)
        self.records[0]['userEmail']='must never be exported'
        with self.assertRaises(ValueError): read_export(self.source())
    def test_expired_deltas_not_retained(self):
        out=self.root/'v1'; build(self.source(),out,now='2026-08-01T00:00:00Z')
        self.records[0]['name']='two'; second=self.root/'v2'
        build(self.source(),second,out/'catalog.sqlite',out/'latest.json',now='2026-08-02T00:00:00Z')
        self.records[0]['name']='three'
        m=build(self.source(),self.root/'v3',second/'catalog.sqlite',second/'latest.json',now='2026-09-27T00:00:00Z')
        self.assertEqual(len(m['deltas']),1)

if __name__=='__main__': unittest.main()
