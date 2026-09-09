import importlib.util
import base64
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch, Mock

spec=importlib.util.spec_from_file_location('local_preflight',Path(__file__).with_name('local-ledger-preflight.py'))
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class PreflightTests(unittest.TestCase):
    store='11111111-1111-1111-1111-111111111111'

    def values(self, mode='LOCAL'):
        v={flag:'false' for flag in module.FLAGS}
        v.update(ACCOUNT_LIFECYCLE_MAINTENANCE='true',ERASURE_LEDGER_REALM='production',
                 ERASURE_LEDGER_PROVIDER=mode,ERASURE_LEDGER_KEYS_JSON='{"k1":"synthetic"}',
                 ERASURE_LEDGER_ACTIVE_KEY_ID='k1',ERASURE_CHECKPOINT_DATABASE_EPOCH=self.store,
                 ERASURE_CHECKPOINT_GITHUB_TOKEN='synthetic_token_not_live',REDIS_PASSWORD='synthetic')
        if mode=='LOCAL':
            v.update(ERASURE_LEDGER_LOCAL_DIRECTORY=module.ROOT,ERASURE_LEDGER_LOCAL_STORE_ID=self.store,
                     ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED='false')
        else:
            v.update(ERASURE_LEDGER_BUCKET='geupddong-account-erasure-ledger-us',
                     ERASURE_LEDGER_ENDPOINT='synthetic-endpoint',ERASURE_LEDGER_ACCESS_KEY_ID='synthetic-id',
                     ERASURE_LEDGER_SECRET_ACCESS_KEY='synthetic-secret')
        return v

    def payload(self, values=None):
        return base64.b64encode(''.join(k+"='"+v+"'\n" for k,v in (values or self.values()).items()).encode()).decode()

    def test_actual_bind_probe_is_isolated_and_uses_exact_path(self):
        store='11111111-1111-1111-1111-111111111111'
        with patch.object(module.subprocess,'run',return_value=Mock(returncode=0,stdout='LOCAL_DOCKER_DIRECTORY_PASS readable=true\n')) as run:
            module.directory_probe('sha256:'+'a'*64,store,Path('/home/luha/erasure-tools/local-redeployment-v1'),'LOCAL')
        args=run.call_args.args[0]
        self.assertIn('--pull=never',args); self.assertIn('--rm',args)
        self.assertEqual(args[args.index('--network')+1],'none')
        self.assertEqual(args[args.index('--user')+1],'1000:1000')
        self.assertIn('type=bind,source=/home/luha/geupddong-erasure-ledger,target=/home/luha/geupddong-erasure-ledger',args)
        self.assertNotIn('--privileged',args); self.assertNotIn('--env-file',args)
        self.assertIn('apparmor=docker-default',args)

    def test_probe_rejects_mount_failure_and_wrong_image(self):
        store='11111111-1111-1111-1111-111111111111'
        with patch.object(module.subprocess,'run',return_value=Mock(returncode=1,stdout='')):
            with self.assertRaises(ValueError): module.directory_probe('sha256:'+'a'*64,store,Path('/safe'),'LOCAL')
        with patch.object(module.subprocess,'run') as run:
            with self.assertRaises(ValueError): module.directory_probe('untrusted:latest',store,Path('/safe'),'LOCAL')
            run.assert_not_called()

    def test_roles_and_store_identity_required(self):
        store='11111111-1111-1111-1111-111111111111'
        for role in ('api','batch'): self.assertEqual(module.parse_args([role,store]),[role,store])
        for args in ([],['api'],['admin',store],['api',''],['api',"';bad"],['api',store,'extra']):
            with self.assertRaises(ValueError): module.parse_args(args)

    def test_invalid_identity_never_inspects_production(self):
        with patch.object(module.sys,'argv',['preflight','api','invalid']), patch.object(module,'config') as lookup:
            with self.assertRaises(ValueError): module.main()
            lookup.assert_not_called()

    def test_live_action_flags_fail_before_java(self):
        store='11111111-1111-1111-1111-111111111111'
        with patch.object(module.sys,'argv',['preflight','api',store]), \
             patch.object(module.os,'getuid',return_value=1000,create=True), \
             patch.object(module.os,'getgid',return_value=1000,create=True), \
             patch.object(module.sys,'stdin',io.StringIO(self.payload())), \
             patch.object(module,'config',return_value=({'ACCOUNT_LIFECYCLE_MAINTENANCE':'false'},'hash')), \
             patch.object(module.subprocess,'run') as run:
            with self.assertRaises(ValueError): module.main()
            run.assert_not_called()

    def test_initial_cutover_and_local_redeployment_are_separate(self):
        for role in ('api','batch'):
            for providers,expected in [(('R2','R2'),'R2'),(('LOCAL','LOCAL'),'LOCAL')]:
                configs={r:(self.values(p),'hash') for r,p in zip(('api','batch'),providers)}
                self.assertEqual(module.select_mode(configs,role,self.store,self.values()),expected)
        mixed={'api':(self.values(),'a'),'batch':(self.values('R2'),'b')}
        self.assertEqual(module.select_mode(mixed,'batch',self.store,self.values()),'R2')
        with self.assertRaises(ValueError): module.select_mode(mixed,'api',self.store,self.values())

    def test_candidate_changes_are_rejected(self):
        configs={r:(self.values(),'hash') for r in ('api','batch')}
        changes={key:'changed' for key in module.IDENTITY+('ERASURE_CHECKPOINT_GITHUB_TOKEN',)}
        changes.update(ERASURE_LEDGER_LOCAL_STORE_ID='changed',ERASURE_LEDGER_LOCAL_DIRECTORY='/other',
                       ERASURE_LEDGER_PROVIDER='R2',ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED='true',
                       ACCOUNT_LIFECYCLE_MAINTENANCE='false',ERASURE_LEDGER_ENDPOINT='old-r2')
        changes.update({f:'true' for f in module.FLAGS})
        for key,value in changes.items():
            with self.subTest(key=key):
                candidate=self.values(); candidate[key]=value
                with self.assertRaises(ValueError): module.select_mode(configs,'api',self.store,candidate)

    def test_live_identity_key_provider_and_action_drift_are_rejected(self):
        for key,value in [('ERASURE_LEDGER_PROVIDER','UNKNOWN'),('ERASURE_LEDGER_LOCAL_STORE_ID','other'),
                          ('ERASURE_LEDGER_KEYS_JSON','different'),('ERASURE_LEDGER_LOCAL_DIRECTORY','/wrong'),
                          ('ERASURE_LEDGER_SECRET_ACCESS_KEY','stale'),('ACCOUNT_ERASURE_ENABLED','true')]:
            with self.subTest(key=key):
                configs={r:(self.values(),'hash') for r in ('api','batch')}
                configs['batch'][0][key]=value
                with self.assertRaises(ValueError): module.select_mode(configs,'api',self.store,self.values())

    def test_candidate_parser_never_evaluates_shell_and_requires_exact_fields(self):
        self.assertEqual(module.candidate_payload(self.payload()),self.values())
        for value in ['', 'invalid-base64', 'a'*32769,
                      base64.b64encode(b"X='a'\nX='b'\n").decode(),
                      base64.b64encode(b'ERASURE_LEDGER_PROVIDER=$(whoami)\n').decode(),
                      self.payload({**self.values(),'UNEXPECTED':'x'})]:
            with self.assertRaises(Exception): module.candidate_payload(value)

    def inspect_item(self):
        return dict(State={'Running':True,'StartedAt':'time'},Id='id',Image='sha256:'+'a'*64,RestartCount=0,
                    HostConfig={'Privileged':False},Config={'User':'1000:1000','Env':[k+'='+v for k,v in self.values().items()]},
                    Mounts=[{'Destination':module.ROOT,'Source':module.ROOT,'Type':'bind','RW':True}])

    def test_local_runtime_user_mount_and_fingerprint(self):
        original=self.inspect_item()
        def inspect(item):
            with patch.object(module.subprocess,'run',return_value=Mock(returncode=0,stdout=json.dumps([item]))):
                return module.config('toilet-api')
        before=inspect(original)[1]
        changed=self.inspect_item(); changed['HostConfig']['Privileged']=True
        self.assertNotEqual(before,inspect(changed)[1])
        for field,value in [('Source','/wrong'),('RW',False),('Type','volume')]:
            item=self.inspect_item(); item['Mounts'][0][field]=value
            with self.assertRaises(ValueError): inspect(item)
        item=self.inspect_item(); item['Config']['User']='0:0'
        with self.assertRaises(ValueError): inspect(item)
        item=self.inspect_item(); item['Mounts']=[]
        with self.assertRaises(ValueError): inspect(item)

    def run_main(self, mode, java_ok=True, drift=False):
        values=self.values(mode)
        expected=('LOCAL_REDEPLOYMENT_PREFLIGHT_PASS inventoryVerified=true activationAllowed=false' if mode=='LOCAL'
                  else 'LOCAL_EMPTY_PREFLIGHT_PASS records=0 checkpointMatched=true directoryVerified=true activationAllowed=false')
        configs=[(values,'baseline'),(values,'baseline')]+[(values,'changed' if drift else 'baseline')]*4
        with patch.object(module.sys,'argv',['preflight','api',self.store]), \
             patch.object(module.sys,'stdin',io.StringIO(self.payload())), \
             patch.object(module.sys,'stdout',io.StringIO()), \
             patch.object(module.os,'getuid',return_value=1000,create=True), \
             patch.object(module.os,'getgid',return_value=1000,create=True), \
             patch.object(module,'config',side_effect=configs), \
             patch.object(module.Path,'resolve',lambda p:p), \
             patch.object(module.Path,'is_symlink',return_value=False), \
             patch.object(module,'directory_probe') as probe, \
             patch.object(module.subprocess,'run',side_effect=[Mock(returncode=0 if java_ok else 1,stdout=expected),
                 Mock(returncode=0,stdout='sha256:'+'a'*64)]) as run:
            if not java_ok or drift:
                with self.assertRaises(ValueError): module.main()
                probe.assert_not_called()
            else:
                module.main(); probe.assert_called_once()
                child=run.call_args_list[0].kwargs['env']
                self.assertEqual('SOURCE_SECRET' in child,mode=='R2')
                self.assertEqual('LEDGER_KEYS_JSON' in child,mode=='LOCAL')
                self.assertNotIn('REDIS_PASSWORD',child)
                self.assertNotIn(values['ERASURE_LEDGER_KEYS_JSON'],run.call_args_list[0].args[0])

    def test_both_modes_pass_with_minimal_child_environment(self):
        for mode in ('R2','LOCAL'): self.run_main(mode)

    def test_java_failure_and_runtime_drift_prevent_probe(self):
        self.run_main('LOCAL',java_ok=False); self.run_main('LOCAL',drift=True)

if __name__=='__main__': unittest.main()
