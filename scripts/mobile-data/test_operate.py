import unittest
from operate import candidate_env, settings, validate_render


class OperationTests(unittest.TestCase):
    def test_only_catalog_lines_change_and_repeated_configuration_is_idempotent(self):
        old = b"DB_PASSWORD='a$b'\nACCOUNT_RETENTION_ENABLED=true\nMOBILE_CATALOG_ENABLED=false\n"
        values = settings('a' * 64)
        new = candidate_env(old, values)
        self.assertTrue(new.startswith(b"DB_PASSWORD='a$b'\nACCOUNT_RETENTION_ENABLED=true\n"))
        self.assertEqual(new.count(b'MOBILE_CATALOG_ENABLED='), 1)
        self.assertEqual(candidate_env(new, values), new)

    def test_invalid_secret_or_ambiguous_lines_rejected(self):
        for token in ('', 'x\ny', "'" * 64):
            with self.assertRaises(ValueError): settings(token)
        with self.assertRaises(ValueError):
            candidate_env(b' export MOBILE_CATALOG_ENABLED=true\n', settings('a' * 64))

    def test_render_must_preserve_every_other_service_setting(self):
        before = {'services': {'toilet-batch': {'image': 'batch:old', 'environment': {'ACCOUNT': 'true'}}, 'api': {'image': 'api:old'}}}
        import copy
        values = settings('a' * 64)
        after = copy.deepcopy(before)
        after['services']['toilet-batch']['environment'].update(values)
        validate_render(before, after, values)
        after['services']['api']['image'] = 'api:new'
        with self.assertRaises(ValueError): validate_render(before, after, values)
