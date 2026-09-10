import importlib.util
import json
import os
import re
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock
from datetime import datetime, timezone
from contextlib import nullcontext

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

class InspectionOrderTest(unittest.TestCase):
    def test_image_build_is_pinned_manual_and_has_no_server_access(self):
        source = (Path(__file__).parents[1] / '.github/workflows/account-image-build.yml').read_text()
        for text in ('workflow_dispatch:', "github.ref == 'refs/heads/main'",
                     'vars.ACCOUNT_IMAGE_BUILD_APPROVED_SHA == github.sha',
                     'inputs.expected_commit == github.sha', 'deploymentPerformed', 'accountStateChanged'):
            self.assertIn(text, source)
        self.assertNotRegex(source, r'(?m)^  (push|pull_request|schedule):')
        for prohibited in ('MINI_PC_KEY', 'TUNNEL_', 'REDIS_PASSWORD', 'SPRING_DB_',
                           'ERASURE_LEDGER_KEYS_JSON', ':latest', 'docker compose', '--apply-approved'):
            self.assertNotIn(prohibited, source)

    def sample(self):
        return {'Id': 'synthetic-container', 'Image': 'synthetic-image', 'RestartCount': 0,
                'State': {'Running': True, 'Pid': 123, 'StartedAt': 'synthetic-start'},
                'Config': {'User': '1000:1000', 'Env': ['SYNTHETIC_FLAG=false']},
                'Mounts': [{'Type': 'bind', 'Source': '/synthetic/z', 'Destination': '/synthetic/z', 'RW': True},
                           {'Type': 'bind', 'Source': '/synthetic/a', 'Destination': '/synthetic/a', 'RW': False}]}

    def test_mount_order_is_ignored_without_mutating_input(self):
        original = self.sample()
        other = json.loads(json.dumps(original))
        other['Mounts'].reverse()
        self.assertEqual(resume.normalize_inspection(original), resume.normalize_inspection(other))
        self.assertEqual(original['Mounts'][0]['Source'], '/synthetic/z')
        self.assertEqual(other['Mounts'][0]['Source'], '/synthetic/a')

    def test_real_changes_and_duplicate_mounts_are_not_ignored(self):
        original = self.sample()
        variants = []
        for key, value in (('Source', '/synthetic/changed'), ('Destination', '/synthetic/changed'),
                           ('Type', 'volume'), ('RW', False)):
            changed = self.sample()
            changed['Mounts'][0][key] = value
            variants.append(changed)
        for key, value in (('Id', 'recreated'), ('Image', 'new-image'), ('RestartCount', 1)):
            changed = self.sample()
            changed[key] = value
            variants.append(changed)
        for section, key, value in (('State', 'Pid', 456), ('State', 'Running', False),
                                    ('State', 'StartedAt', 'restarted'),
                                    ('Config', 'Env', ['SYNTHETIC_FLAG=true']),
                                    ('Config', 'User', '0:0')):
            changed = self.sample()
            changed[section][key] = value
            variants.append(changed)
        changed = self.sample()
        changed['Mounts'].append(dict(changed['Mounts'][0]))
        variants.append(changed)
        for changed in variants:
            self.assertNotEqual(resume.normalize_inspection(original), resume.normalize_inspection(changed))

    def test_invalid_mount_list_is_rejected(self):
        for bad in ({}, {'Mounts': None}, {'Mounts': {}}, {'Mounts': ['invalid']}):
            with self.assertRaises(ValueError):
                resume.normalize_inspection(bad)

    def test_capture_normalizes_both_services(self):
        host = object.__new__(resume.Host)
        original = self.sample()
        other = self.sample()
        other['Mounts'].reverse()
        with patch.object(host, 'run', side_effect=[json.dumps([original]), json.dumps([other])]):
            captured = host.capture()
        self.assertEqual(captured['api'], captured['batch'])
        self.assertEqual(captured['api'], resume.normalize_inspection(original))

class TransitionTest(unittest.TestCase):
    def test_preserving_check_keeps_nonempty_account_queue_and_never_pulls(self):
        from types import SimpleNamespace
        objects = {'api': object_for('active'), 'batch': object_for('active')}
        objects['api']['Image'] = 'old-image-id'
        old = 'synthetic:' + COMMIT
        source = ('services:\n  api:\n    image: ' + old + '\n').encode()
        new_image = 'synthetic:' + 'b' * 40
        rendered = {'services': {'api': {'image': old, 'user': '1000:1000'}}}
        replacement = {'services': {'api': {'image': new_image, 'user': '1000:1000'}}}
        host = Mock(root=Path('/synthetic'), compose=Path('/synthetic/compose.yml'), service='api')
        host.compose_command.side_effect = lambda *args: ['docker', 'compose', *args]
        host.context.maintenance_lease.acquire.return_value = nullcontext()
        host.capture.return_value = objects
        host.check_dependencies.return_value = {'records': 3}
        host.run.side_effect = [json.dumps(rendered), json.dumps(replacement),
                               json.dumps([{'RepoDigests': ['synthetic@sha256:' + 'c' * 64], 'Id': 'image-id'}]), 'image-id', 'old-image-id']
        args = SimpleNamespace(role='api', api_commit=COMMIT, batch_commit=COMMIT, next_commit='b' * 40,
                               next_image_digest='sha256:' + 'c' * 64, apply_approved=False)
        with patch.object(resume, 'Host', return_value=host), patch.object(resume, 'read_owned', return_value=source), \
             patch.object(resume, 'apply_step') as apply, patch('builtins.print'):
            resume.preserve_rollout(args)
        host.check_dependencies.assert_called_once_with(objects, initial_transition=False)
        apply.assert_not_called()
        host.restart.assert_not_called()
        self.assertFalse(any(call.args[0][:2] == ['docker', 'pull'] for call in host.run.call_args_list))

    def test_preserve_rollout_accepts_only_matching_existing_phases(self):
        for phase in ('paused', 'guarded', 'active'):
            objects = dict.fromkeys(('api', 'batch'), object_for(phase))
            resume.validate_step(objects, 'api', 'preserve', dict.fromkeys(('api', 'batch'), COMMIT))
        with self.assertRaisesRegex(ValueError, 'MIXED_ROLLOUT'):
            resume.validate_step({'api': object_for('guarded'), 'batch': object_for('active')},
                                 'api', 'preserve', dict.fromkeys(('api', 'batch'), COMMIT))

    def test_preserving_compose_changes_one_image_only(self):
        old = 'synthetic/toilet-api:' + COMMIT
        source = ('services:\n  api:\n    image: ' + old +
                  '\n    env_file: [.env, .account-lifecycle.env]\n  redis:\n    image: redis:7\n').encode()
        changed, image = resume.image_only_compose(source, old, 'b' * 40)
        self.assertEqual(changed, source.replace(old.encode(), image.encode()))
        original = {'services': {'api': {'image': old, 'environment': resume.flags('active')},
                                 'redis': {'image': 'redis:7'}}}
        expected = json.loads(json.dumps(original))
        expected['services']['api']['image'] = image
        resume.validate_image_only_render(original, expected, 'api', image)
        expected['services']['api']['environment']['ACCOUNT_RETENTION_ENABLED'] = 'false'
        with self.assertRaises(ValueError):
            resume.validate_image_only_render(original, expected, 'api', image)
        for content, commit in ((source + source, 'b' * 40), (b'services: {}', 'b' * 40),
                                (source, COMMIT), (source, 'invalid;')):
            with self.assertRaises(ValueError): resume.image_only_compose(content, old, commit)

    def test_rollout_requires_digest_and_schema_approval_before_host_access(self):
        from types import SimpleNamespace
        args = SimpleNamespace(next_image_digest='invalid', apply_approved=False,
                               deployment_freeze_confirmed=False, schema_compatible_confirmed=False)
        with patch.object(resume, 'Host') as host:
            with self.assertRaises(ValueError): resume.preserve_rollout(args)
            args.next_image_digest = 'sha256:' + 'a' * 64
            args.apply_approved = True
            args.deployment_freeze_confirmed = True
            with self.assertRaises(ValueError): resume.preserve_rollout(args)
            host.assert_not_called()

    def test_preserving_workflow_pins_artifact_without_enabling_accounts(self):
        source = (Path(__file__).parents[1] / '.github/workflows/account-preserving-rollout.yml').read_text()
        for text in ('workflow_dispatch:', "github.ref == 'refs/heads/main'",
                     'vars.ACCOUNT_ROLLOUT_APPROVED_SHA == github.sha', 'default: check',
                     '--phase preserve', '--next-image-digest', '--schema-compatible-confirmed'):
            self.assertIn(text, source)
        self.assertNotRegex(source, r'(?m)^  (push|pull_request|schedule):')
        self.assertNotIn('--phase active', source)
        self.assertNotIn('ACCOUNT_ERASURE_ENABLED:', source)

    def test_active_check_validates_policy_without_writing_or_restarting(self):
        for role, states in (('batch', ('guarded', 'guarded')), ('api', ('guarded', 'active'))):
            objects = dict(zip(('api', 'batch'), map(object_for, states)))
            for obj in objects.values(): obj['Image'] = 'sha256:synthetic'
            host = Mock()
            host.root = Path('/synthetic')
            host.compose = host.root / 'compose.yml'
            host.service = role
            host.context.maintenance_lease.acquire.return_value = nullcontext()
            host.capture.return_value = objects
            host.run.side_effect = [json.dumps({'services': {role: {
                'image': 'synthetic:' + COMMIT, 'user': '1000:1000',
                'environment': resume.environment(objects[role])}}}), 'sha256:synthetic']
            content = ''.join(k + "='" + v + "'\n" for k, v in resume.environment(objects[role]).items()).encode()
            argv = ['resume', '--role', role, '--phase', 'active', '--api-commit', COMMIT,
                    '--batch-commit', COMMIT, '--policy-version', 'synthetic-v1',
                    '--policy-announced-at', '2020-01-01T00:00:00Z', '--policy-effective-at', '2020-01-02T00:00:00Z']
            with patch.object(resume.sys, 'argv', argv), patch.object(resume.sys, 'platform', 'linux'), \
                 patch.object(resume.os, 'geteuid', return_value=1000, create=True), \
                 patch.object(resume, 'Host', return_value=host), patch.object(resume, 'read_owned', return_value=content), \
                 patch.object(resume, 'check_published_policy') as policy, patch.object(resume, 'apply_step') as apply, \
                 patch('builtins.print'):
                resume.main()
            self.assertEqual(policy.call_count, 2)
            apply.assert_not_called()
            host.restart.assert_not_called()

    def test_policy_rejection_prevents_even_host_access(self):
        argv = ['resume', '--role', 'batch', '--phase', 'active', '--api-commit', COMMIT,
                '--batch-commit', COMMIT, '--policy-version', 'synthetic-v1',
                '--policy-announced-at', '2020-01-01T00:00:00Z', '--policy-effective-at', '2020-01-02T00:00:00Z',
                '--apply-approved', '--deployment-freeze-confirmed']
        with patch.object(resume.sys, 'argv', argv), patch.object(resume.sys, 'platform', 'linux'), \
             patch.object(resume.os, 'geteuid', return_value=1000, create=True), patch.object(resume, 'Host') as host, \
             patch.object(resume, 'check_published_policy', side_effect=ValueError('ACCOUNT_RESUME_POLICY_STILL_DRAFT')):
            with self.assertRaisesRegex(ValueError, 'POLICY_STILL_DRAFT'): resume.main()
            host.assert_not_called()

    def test_policy_release_rejects_missing_future_invalid_and_reversed_dates(self):
        now = datetime(2026, 9, 10, tzinfo=timezone.utc)
        valid = ('synthetic-v1', '2026-09-01T00:00:00Z', '2026-09-08T00:00:00Z')
        self.assertEqual(resume.policy_expectation(*valid, now)['data-account-policy-status'], 'published')
        for values in ((None, None, None), ('unsafe;', *valid[1:]),
                       (valid[0], valid[1], '2026-09-11T00:00:00Z'),
                       (valid[0], '2026-09-09T00:00:00Z', valid[2]),
                       (valid[0], valid[1], '2026-02-30T00:00:00Z'),
                       (valid[0], valid[1], '2026-09-08')):
            with self.assertRaises(ValueError): resume.policy_expectation(*values, now)

    def test_published_html_requires_one_matching_marker_and_visible_notice(self):
        expected = resume.policy_expectation('synthetic-v1', '2026-09-01T00:00:00Z', '2026-09-08T00:00:00Z',
                                             datetime(2026, 9, 10, tzinfo=timezone.utc))
        attributes = ' '.join(k + '="' + v + '"' for k, v in expected.items())
        page = '<article ' + attributes + '>공지: 9월 1일 · 시행: 9월 8일</article>'
        resume.validate_policy_html(page, expected)
        for bad in ('<h1>Login</h1>', page.replace('published', 'draft'),
                    page.replace('synthetic-v1', 'other-v1'), page + page,
                    page.replace('공지:', '알림'), page + '<p>공개 전 확인</p>',
                    '<script>' + page + '</script>',
                    page.replace('<article ', '<article data-account-policy-status="published" ')):
            with self.assertRaises(ValueError): resume.validate_policy_html(bad, expected)

    def test_policy_fetch_fixed_origin_and_both_pages_without_credentials(self):
        response = Mock(status=200)
        response.headers.get_content_type.return_value = 'text/html'
        response.read.return_value = b'synthetic'
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        with patch.object(resume.urllib.request, 'build_opener') as opener, \
             patch.object(resume, 'validate_policy_html') as validate:
            opener.return_value.open.return_value = response
            resume.check_published_policy({'synthetic': True})
            urls = [call.args[0].full_url for call in opener.return_value.open.call_args_list]
            self.assertEqual(urls, ['https://geupddong.com/policies/terms', 'https://geupddong.com/policies/privacy'])
            self.assertEqual(validate.call_count, 2)
            for call in opener.return_value.open.call_args_list:
                self.assertNotIn('Authorization', call.args[0].headers)
                self.assertNotIn('Cookie', call.args[0].headers)
            response.status = 302
            with self.assertRaises(ValueError): resume.check_published_policy({})

    def test_active_workflow_requires_pinned_release_and_policy_parameters(self):
        source = (Path(__file__).parents[1] / '.github/workflows/account-active-transition.yml').read_text()
        self.assertIn('  workflow_dispatch:', source)
        self.assertNotRegex(source, r'(?m)^  (push|pull_request|schedule):')
        for text in ("github.ref == 'refs/heads/main'", 'vars.ACCOUNT_ACTIVE_APPROVED_SHA == github.sha',
                     '--phase active', '--policy-version', '--policy-announced-at', '--policy-effective-at',
                     'StrictHostKeyChecking=yes', '--deployment-freeze-confirmed', 'default: check'):
            self.assertIn(text, source)
        self.assertNotRegex(source, r'continue-on-error|StrictHostKeyChecking=no|set -x')

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
        for filename in ('account-guarded-transition.yml', 'account-active-transition.yml', 'account-preserving-rollout.yml'):
            source = (Path(__file__).parents[1] / '.github/workflows' / filename).read_text()
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
    def test_external_change_is_not_overwritten_by_rollback(self):
        restart = Mock()
        def drift():
            self.target.write_bytes(b'external-change')
            raise ValueError('synthetic drift')
        with self.assertRaisesRegex(RuntimeError, 'ROLLBACK_UNVERIFIED'):
            resume.apply_step(self.target, self.original, self.changed, lambda: None, restart, drift)
        self.assertEqual(self.target.read_bytes(), b'external-change')
        self.assertEqual(restart.call_count, 1)

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
