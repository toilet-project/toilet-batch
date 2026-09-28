import gzip
import datetime as dt
import hashlib
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch
from catalog import FIELDS, canonical
import publish


class PublishTests(unittest.TestCase):
    def test_real_builder_uploads_before_both_indexes_and_idempotent_no_change(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); objects={}; events=[]
            records=[]
            for i in range(1,7):
                r=dict.fromkeys(FIELDS);r.update(id=i,name=f'시설 {i}',latitude=36.3,longitude=127.3);records.append(r)
            def source():
                rows=[dict(kind='meta',count=6)]+[dict(kind='toilet',value=r) for r in records]+[dict(kind='complete',count=6)]
                p=root/'source.jsonl';p.write_text('\n'.join(canonical(r) for r in rows),encoding='utf-8');return p
            def request(origin,path,token=None,method='GET',body=None,headers=None):
                events.append((method,path))
                if path=='admin/prune':return b'{"removed":0}'
                if path.startswith('admin/object/'):
                    self.assertEqual(hashlib.sha256(body).hexdigest(),headers['X-Content-SHA256'])
                    objects[path.removeprefix('admin/object/')]=body;return b'{}'
                if path in ('admin/commit','admin/monthly/commit'):
                    m=json.loads(body)
                    entries=[m['full'],*m['deltas']] if m['schemaVersion']==1 else publish.monthly_files(m)
                    for entry in entries:self.assertIn(entry['key'],objects)
                    objects['latest.json' if m['schemaVersion']==1 else 'v2/latest.json']=body;return b'{}'
                key={'admin/latest':'latest.json','admin/monthly/latest':'v2/latest.json'}.get(path,path)
                if key not in objects:raise urllib.error.HTTPError(path,404,'missing',None,None)
                raw=objects[key];return gzip.decompress(raw) if raw.startswith(b'\x1f\x8b') else raw
            class Clock(dt.datetime):
                day=0
                @classmethod
                def now(cls,tz=None):
                    cls.day+=1
                    return cls(2026,9,20+cls.day,tzinfo=dt.timezone.utc)
            with patch.object(publish,'request',request),patch('builtins.print'),patch.object(publish.dt,'datetime',Clock):
                publish.publish(source(),root/'first','https://example.com','secret',register=True)
                first=json.loads(objects['v2/latest.json']);baseline=first['releaseBaselineVersion']
                events.clear()
                publish.publish(source(),root/'second','https://example.com','secret')
                self.assertFalse(any(method in ('PUT',) or path.endswith('/commit') for method,path in events))
                self.assertIn(('POST','admin/prune'),events)
                # A changed record retains the registered base and yields the same-month rollup.
                records[0]['name']='수정된 시설'
                publish.publish(source(),root/'third','https://example.com','secret')
                current=json.loads(objects['v2/latest.json'])
                self.assertEqual(current['releaseBaselineVersion'],baseline)
                self.assertEqual(len(current['daily']),1)
                self.assertEqual(current['intraMonth']['fromVersion'],first['version'])
                self.assertIsNone(json.loads(objects['latest.json'])['previousFull'])


if __name__=='__main__':unittest.main()
