import copy
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import checkpoint_token_rotation as rotation

OLD = 'github_pat_' + 'a' * 40
NEW = 'github_pat_' + 'b' * 40


class RotationTest(unittest.TestCase):
    def test_replaces_only_token_preserving_flags_and_bytes(self):
        original = ("FLAG='true'\n" + rotation.KEY + "='" + OLD + "'\nOTHER='no change'\n").encode()
        runtime = {'FLAG': 'true', rotation.KEY: OLD, 'OTHER': 'no change'}
        result = rotation.replacement(original, runtime, NEW)
        self.assertEqual(result, original.replace(OLD.encode(), NEW.encode()))
        self.assertEqual(runtime[rotation.KEY], OLD)

    def test_rejects_secret_injection_and_config_drift(self):
        original = (rotation.KEY + "='" + OLD + "'\n").encode()
        for token in ('', NEW + "'\nEVIL='true", NEW + '\n', 'synthetic'):
            with self.assertRaises(ValueError):
                rotation.replacement(original, {rotation.KEY: OLD}, token)
        with self.assertRaises(ValueError):
            rotation.replacement(original, {rotation.KEY: NEW}, NEW)

    def test_compose_changes_must_be_credential_only(self):
        before = {'services': {'api': {'image': 'fixed', 'environment': {rotation.KEY: OLD, 'FLAG': 'true'}}}}
        after = copy.deepcopy(before)
        after['services']['api']['environment'][rotation.KEY] = NEW
        rotation.check_render(before, after, 'api', NEW)
        after['services']['api']['environment']['FLAG'] = 'false'
        with self.assertRaises(ValueError):
            rotation.check_render(before, after, 'api', NEW)

    def pair(self, fail_restart=False, external_edit=False):
        originals = {r: (rotation.KEY + "='" + OLD + "'\n").encode() for r in rotation.ROLES}
        candidates = {r: v.replace(OLD.encode(), NEW.encode()) for r, v in originals.items()}
        files = {Path('/synthetic/' + r + '/.account-lifecycle.env'): v for r, v in originals.items()}
        events = []
        failed = False
        def restart(role):
            nonlocal failed
            events.append(role)
            if fail_restart and role == 'api' and not failed:
                failed = True
                if external_edit:
                    files[Path('/synthetic/api/.account-lifecycle.env')] = b'external edit'
                raise RuntimeError('synthetic failure')
        def capture():
            return {r: {'Config': {'Env': [rotation.KEY + '=' + rotation.resume.parse_env(files[Path('/synthetic/' + r + '/.account-lifecycle.env')])[rotation.KEY]]}}
                    for r in rotation.ROLES}
        hosts = {r: SimpleNamespace(root=Path('/synthetic/' + r), restart=lambda r=r: restart(r), capture=capture)
                 for r in rotation.ROLES}
        return hosts, originals, candidates, files, events

    def test_pair_success_and_order(self):
        hosts, old, new, files, events = self.pair()
        def after():
            self.assertEqual(events, ['batch', 'api'])
            for role in rotation.ROLES:
                self.assertEqual(files[hosts[role].root / '.account-lifecycle.env'], new[role])
        with patch.object(rotation.resume, 'read_owned', side_effect=lambda p, **kw: files[p]), \
             patch.object(rotation.resume, 'atomic_replace', side_effect=lambda p, v: files.__setitem__(p, v)):
            rotation.apply_pair(hosts, old, new, lambda: None, after)

    def test_second_restart_failure_restores_both_configs(self):
        hosts, old, new, files, events = self.pair(fail_restart=True)
        with patch.object(rotation.resume, 'read_owned', side_effect=lambda p, **kw: files[p]), \
             patch.object(rotation.resume, 'atomic_replace', side_effect=lambda p, v: files.__setitem__(p, v)):
            with self.assertRaisesRegex(RuntimeError, '^CHECKPOINT_TOKEN_FAILED_ORIGINAL_RESTORED$'):
                rotation.apply_pair(hosts, old, new, lambda: None, lambda: None)
        for role in rotation.ROLES:
            self.assertEqual(files[hosts[role].root / '.account-lifecycle.env'], old[role])
        self.assertEqual(events, ['batch', 'api', 'batch', 'api'])

    def test_external_edit_is_never_overwritten_during_rollback(self):
        hosts, old, new, files, events = self.pair(fail_restart=True, external_edit=True)
        with patch.object(rotation.resume, 'read_owned', side_effect=lambda p, **kw: files[p]), \
             patch.object(rotation.resume, 'atomic_replace', side_effect=lambda p, v: files.__setitem__(p, v)):
            with self.assertRaisesRegex(RuntimeError, '^CHECKPOINT_TOKEN_ROLLBACK_UNVERIFIED$'):
                rotation.apply_pair(hosts, old, new, lambda: None, lambda: None)
        self.assertEqual(files[hosts['api'].root / '.account-lifecycle.env'], b'external edit')

    def test_failure_output_does_not_expose_secret_or_exception(self):
        from contextlib import redirect_stderr
        from io import StringIO
        output = StringIO()
        with patch.object(rotation, 'run', side_effect=RuntimeError(NEW)), redirect_stderr(output):
            with self.assertRaises(SystemExit):
                rotation.entry({'token': NEW, 'operation': 'check', 'commits': {}})
        self.assertNotIn(NEW, output.getvalue())
        self.assertIn('detailsSuppressed=true', output.getvalue())


if __name__ == '__main__':
    unittest.main()
