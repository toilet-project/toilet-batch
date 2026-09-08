import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('local_preflight',Path(__file__).with_name('local-ledger-preflight.py'))
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class PreflightTests(unittest.TestCase):
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
