import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch, Mock

spec=importlib.util.spec_from_file_location('local_preflight',Path(__file__).with_name('local-ledger-preflight.py'))
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class PreflightTests(unittest.TestCase):
    def test_actual_bind_probe_is_isolated_and_uses_exact_path(self):
        store='11111111-1111-1111-1111-111111111111'
        with patch.object(module.subprocess,'run',return_value=Mock(returncode=0,stdout='LOCAL_DOCKER_DIRECTORY_PASS records=0\n')) as run:
            module.directory_probe('sha256:'+'a'*64,store,Path('/home/luha/erasure-tools/local-cutover-home'))
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
            with self.assertRaises(ValueError): module.directory_probe('sha256:'+'a'*64,store,Path('/safe'))
        with patch.object(module.subprocess,'run') as run:
            with self.assertRaises(ValueError): module.directory_probe('untrusted:latest',store,Path('/safe'))
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
             patch.object(module,'config',return_value=({'ACCOUNT_LIFECYCLE_MAINTENANCE':'false'},'hash')), \
             patch.object(module.subprocess,'run') as run:
            with self.assertRaises(ValueError): module.main()
            run.assert_not_called()

if __name__=='__main__': unittest.main()
