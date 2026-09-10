import importlib.util
import json
import os
import re
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location('resume', Path(__file__).with_name('account_resume_transition.py'))
resume = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resume)
COMMIT = 'a' * 40

def object_for(phase):
    env = resume.flags(phase) | {
        'ERASURE_LEDGER_PROVIDER': 'LOCAL', 'ERASURE_MAINTENANCE_LOCK_ENABLED': 'true',
        'ERASURE_MAINTENANCE_DIRECTORY': '/home/luha/geupddong-maintenance',
    }
    return {'State': {'Running': True}, 'Config': {'User': '1000:1000',
            'Image': 'synthetic:' + COMMIT, 'Env': [k + '=' + v for k, v in env.items()]}}

class TransitionTest(unittest.TestCase):
    def test_manual_workflow_stays_guarded_and_requires_exact_release(self):
        source = (Path(__file__).parents[1] / '.github/workflows/account-guarded-transition.yml').read_text()
        self.assertIn('  workflow_dispatch:', source)
        self.assertNotRegex(source, r'(?m)^  (push|pull_request|schedule):')
        self.assertIn("github.ref == 'refs/heads/main'", source)
        self.assertIn('vars.ACCOUNT_GUARDED_APPROVED_SHA == github.sha', source)
        self.assertIn('--phase guarded', source)
        self.assertNotIn('--phase active', source)
        self.assertIn('StrictHostKeyChecking=yes', source)
        self.assertIn('PasswordAuthentication=no', source)
        self.assertIn('--deployment-freeze-confirmed', source)
        self.assertNotRegex(source, r'continue-on-error|StrictHostKeyChecking=no|set -x')

    @unittest.skipUnless(os.name == 'posix', 'bash syntax checked on Linux CI')
    def test_every_workflow_shell_block_parses(self):
        source = (Path(__file__).parents[1] / '.github/workflows/account-guarded-transition.yml').read_text()
        blocks = re.findall(r'        run: \|\n((?:          [^\n]*\n|\n)+)', source + '\n')
        self.assertEqual(len(blocks), 4)
        for block in blocks:
            script = '\n'.join(line[10:] for line in block.splitlines())
            result = subprocess.run(['bash', '-n'], input=script, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_restart_does_not_pull_build_or_recreate_other_services(self):
        for role in ('api', 'batch'):
            host = object.__new__(resume.Host)
            host.role = role
            host.root = Path('/synthetic')
            host.compose = host.root / 'compose.yml'
            host.service = 'api' if role == 'api' else 'toilet-batch'
            host.run = Mock(return_value=json.dumps({'services': {host.service: {'environment': resume.flags('guarded')}}}))
            host.capture = Mock(return_value={role: object_for('guarded')})
            host.healthy = Mock()
            content = ''.join(k + "='" + v + "'\n" for k, v in resume.flags('guarded').items()).encode()
            with patch.object(resume, 'read_owned', return_value=content):
                host.restart()
            args = host.run.call_args_list[1].args[0]
            self.assertEqual(args[-1], host.service)
            self.assertIn('--no-deps', args)
            self.assertIn('--no-build', args)
            self.assertEqual(args[args.index('--pull') + 1], 'never')
            self.assertNotIn('redis', args)
            self.assertNotIn('toilet-mysql', args)

    def test_compose_override_rejected_before_restart(self):
        host = object.__new__(resume.Host)
        host.root = Path('/synthetic')
        host.compose = host.root / 'compose.yml'
        host.service = 'api'
        host.run = Mock(return_value=json.dumps({'services': {'api': {'environment': {'ACCOUNT_ERASURE_ENABLED': 'true'}}}}))
        with patch.object(resume, 'read_owned', return_value=b"ACCOUNT_ERASURE_ENABLED='false'\n"):
            with self.assertRaisesRegex(ValueError, 'COMPOSE_OVERRIDE_REJECTED'):
                host.restart()
        self.assertEqual(host.run.call_count, 1)

    def test_exact_four_step_order(self):
        for (role, phase), states in resume.STEPS.items():
            objects = dict(zip(('api', 'batch'), map(object_for, states)))
            resume.validate_step(objects, role, phase, dict.fromkeys(('api', 'batch'), COMMIT))
        for states in (('paused', 'paused'), ('active', 'active'), ('paused', 'guarded')):
            with self.assertRaises(ValueError):
                resume.validate_step(dict(zip(('api', 'batch'), map(object_for, states))),
                                     'api', 'active', dict.fromkeys(('api', 'batch'), COMMIT))

    def test_bad_image_commit_user_and_missing_guard_fail(self):
        for change in ('commit', 'user', 'guard', 'stopped', 'retirement'):
            objects = {'api': object_for('paused'), 'batch': object_for('paused')}
            if change == 'commit': objects['api']['Config']['Image'] = 'synthetic:' + 'b' * 40
            if change == 'user': objects['api']['Config']['User'] = '0'
            if change == 'guard': objects['api']['Config']['Env'].remove('ERASURE_MAINTENANCE_LOCK_ENABLED=true')
            if change == 'stopped': objects['api']['State']['Running'] = False
            if change == 'retirement': objects['api']['Config']['Env'].append('ERASURE_RETIREMENT_WRITE_ENABLED=true')
            with self.assertRaises(ValueError):
                resume.validate_step(objects, 'api', 'guarded', dict.fromkeys(('api', 'batch'), COMMIT))

    def test_guarded_flags_never_open_member_operations(self):
        values = resume.flags('guarded')
        self.assertEqual(values['ACCOUNT_LIFECYCLE_MAINTENANCE'], 'true')
        self.assertEqual(values['ACCOUNT_ERASURE_ENABLED'], 'false')
        self.assertEqual(values['ACCOUNT_RETENTION_ENABLED'], 'false')
        self.assertTrue(all(values[k] == 'true' for k in resume.TRUE_FLAGS))
        self.assertTrue(all(values[k] == 'false' for k in resume.OFF_FLAGS))

    def test_candidate_preserves_literals_and_changes_only_flags(self):
        runtime = resume.environment(object_for('paused')) | {'REDIS_PASSWORD': 'synthetic$#=secret'}
        content = ''.join(k + "='" + v + "'\n" for k, v in runtime.items()).encode()
        result = resume.parse_env(resume.candidate(content, runtime, 'guarded'))
        self.assertEqual(result, runtime | resume.flags('guarded'))
        self.assertEqual(result['REDIS_PASSWORD'], 'synthetic$#=secret')

    def test_invalid_or_runtime_mismatched_dotenv_is_rejected(self):
        for content in (b"A='x'\nA='y'\n", b'A=x', b"A='x\x00'", b"A='$(secret)'\n"):
            with self.assertRaises(ValueError):
                resume.candidate(content, {'A': 'other'}, 'guarded')
        with self.assertRaises(ValueError):
            resume.candidate(b"ERASURE_LEDGER_ENDPOINT='old'\n", {'ERASURE_LEDGER_ENDPOINT': 'old'}, 'guarded')

    def test_unknown_mixed_phase_rejected(self):
        values = resume.flags('guarded') | {'ACCOUNT_ERASURE_ENABLED': 'true'}
        with self.assertRaises(ValueError): resume.phase_of(values)

    def test_active_cli_cannot_be_unlocked_with_an_approval_boolean(self):
        argv = ['resume', '--role', 'api', '--phase', 'active', '--api-commit', COMMIT,
                '--batch-commit', COMMIT, '--apply-approved', '--deployment-freeze-confirmed']
        with patch.object(resume.sys, 'argv', argv), patch.object(resume.sys, 'platform', 'linux'), \
             patch.object(resume.os, 'geteuid', return_value=1000, create=True), patch.object(resume, 'Host') as host:
            with self.assertRaisesRegex(ValueError, 'ACCOUNT_RESUME_POLICY_RELEASE_REQUIRED'):
                resume.main()
            host.assert_not_called()

    def test_query_only_counts_existing_requests_without_touching_history(self):
        self.assertIn('START TRANSACTION READ ONLY', resume.SQL)
        self.assertNotRegex(resume.SQL.upper(), r'\b(DELETE|UPDATE|INSERT|DROP|TRUNCATE|ALTER)\b')
        self.assertNotRegex(resume.SQL, r'toilet_report|audit_log|email|user_id')

    def test_health_does_not_treat_database_error_http_200_as_success(self):
        obj = object_for('guarded')
        obj['Config']['Env'].append('API_PORT=8080')
        obj['NetworkSettings'] = {'Networks': {'toilet-network': {'IPAddress': '172.22.0.2'}}}
        host = object.__new__(resume.Host)
        host.role = 'api'
        response = Mock(status=200)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b'API database connection failed: synthetic'
        with patch.object(resume.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value = response
            with self.assertRaises(ValueError): host.healthy(obj)
            response.read.return_value = b'API server is running (DB: toilet_db)'
            host.healthy(obj)

@unittest.skipUnless(os.name == 'posix', 'real atomic replace/fsync fixture runs on Linux CI')
class AtomicTransitionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.target = self.root / '.account-lifecycle.env'
        self.original = b"ACCOUNT_ERASURE_ENABLED='false'\n"
        self.changed = b"ERASURE_LEDGER_ENABLED='true'\n"
        self.target.write_bytes(self.original)
        self.reader = patch.object(resume, 'read_owned', side_effect=lambda path, private=False: path.read_bytes())
        self.reader.start()

    def tearDown(self):
        self.reader.stop()
        self.temp.cleanup()

    def test_success_preserves_backup_and_only_restarts_target(self):
        restart = Mock()
        resume.apply_step(self.target, self.original, self.changed, lambda: None, restart, lambda: None)
        self.assertEqual(self.target.read_bytes(), self.changed)
        backup = list(self.root.glob('.account-resume-before-*.env'))
        self.assertEqual(len(backup), 1)
        self.assertEqual(backup[0].read_bytes(), self.original)
        self.assertEqual(backup[0].stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.target.stat().st_mode & 0o777, 0o600)
        restart.assert_called_once()

    def test_failed_postcheck_restores_config_without_claiming_data_rollback(self):
        restart = Mock()
        with self.assertRaisesRegex(RuntimeError, 'CONFIG_RESTORED_RECHECK_REQUIRED'):
            resume.apply_step(self.target, self.original, self.changed, lambda: None, restart,
                              Mock(side_effect=ValueError('synthetic')))
        self.assertEqual(self.target.read_bytes(), self.original)
        self.assertEqual(restart.call_count, 2)

    def test_rollback_failure_is_explicit_and_no_automatic_third_retry(self):
        restart = Mock(side_effect=ValueError('synthetic'))
        with self.assertRaisesRegex(RuntimeError, 'ROLLBACK_UNVERIFIED'):
            resume.apply_step(self.target, self.original, self.changed, lambda: None, restart, lambda: None)
        self.assertEqual(restart.call_count, 2)

    def test_drift_rejected_before_backup_or_restart(self):
        restart = Mock()
        self.target.write_bytes(b'drift')
        with self.assertRaises(ValueError):
            resume.apply_step(self.target, self.original, self.changed, lambda: None, restart, lambda: None)
        self.assertEqual(list(self.root.glob('.account-resume-before-*.env')), [])
        restart.assert_not_called()

if __name__ == '__main__':
    unittest.main()
