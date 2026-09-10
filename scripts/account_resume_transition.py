"""Explicit, one-step configuration transition. Default is read-only; no image build/pull or direct DB mutations.

Requires the already installed shared maintenance/restore tools. Never install automatically.
Run only during an approved deployment freeze: existing legacy deploy scripts do not hold
the maintenance lease for their entire rollout. An uncertain exit requires inspection, not retry.
"""
import argparse
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
from datetime import datetime, timezone

TRUE_FLAGS = ('ERASURE_LEDGER_ENABLED', 'ERASURE_LEDGER_CATALOGUE_ENABLED',
              'ERASURE_CHECKPOINT_ENABLED', 'ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED')
OFF_FLAGS = ('ERASURE_RETIREMENT_WRITE_ENABLED', 'ERASURE_HISTORY_COMPATIBILITY_VERIFIED')
STEPS = {
    ('api', 'guarded'): ('paused', 'paused'),
    ('batch', 'guarded'): ('guarded', 'paused'),
    ('batch', 'active'): ('guarded', 'guarded'),
    ('api', 'active'): ('guarded', 'active'),
}
SQL = """SET SESSION time_zone='+09:00';
SET SESSION MAX_EXECUTION_TIME=10000;
START TRANSACTION READ ONLY;
SELECT COUNT(*) FROM account_withdrawal;
ROLLBACK;"""

def require(value, code='ACCOUNT_RESUME_HELD'):
    if not value:
        raise ValueError(code)

def flags(phase):
    require(phase in ('paused', 'guarded', 'active'))
    return {
        'ACCOUNT_LIFECYCLE_MAINTENANCE': 'false' if phase == 'active' else 'true',
        'ACCOUNT_RETENTION_ENABLED': 'true' if phase == 'active' else 'false',
        'ACCOUNT_ERASURE_ENABLED': 'true' if phase == 'active' else 'false',
        **dict.fromkeys(TRUE_FLAGS, 'false' if phase == 'paused' else 'true'),
        **dict.fromkeys(OFF_FLAGS, 'false'),
    }

def phase_of(env):
    for phase in ('paused', 'guarded', 'active'):
        if all(env.get(k, 'false' if k in OFF_FLAGS else None) == v for k, v in flags(phase).items()):
            return phase
    raise ValueError('ACCOUNT_RESUME_UNKNOWN_PHASE')

def environment(obj):
    result = {}
    for line in obj['Config']['Env']:
        key, value = line.split('=', 1)
        require(key not in result)
        result[key] = value
    return result

def parse_env(content):
    result = {}
    for line in content.decode('utf-8').splitlines():
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)='([^'\r\n\x00]*)'", line)
        require(match and match[1] not in result)
        result[match[1]] = match[2]
    return result

def candidate(content, runtime, phase):
    values = parse_env(content)
    require(values and all(runtime.get(k) == v for k, v in values.items()))
    require(not any(k in values for k in ('ERASURE_LEDGER_ENDPOINT', 'ERASURE_LEDGER_BUCKET',
                                        'ERASURE_LEDGER_ACCESS_KEY_ID', 'ERASURE_LEDGER_SECRET_ACCESS_KEY')))
    values.update(flags(phase))
    return ''.join(k + "='" + v + "'\n" for k, v in values.items()).encode('utf-8')

def validate_step(objects, role, phase, commits):
    require((role, phase) in STEPS)
    actual = []
    for item_role in ('api', 'batch'):
        obj = objects[item_role]
        require(obj['State']['Running'] and obj['Config']['User'] == '1000:1000')
        require(re.fullmatch(r'[a-f0-9]{40}', commits[item_role]) is not None)
        require(obj['Config']['Image'].endswith(':' + commits[item_role]))
        env = environment(obj)
        require(env.get('ERASURE_LEDGER_PROVIDER') == 'LOCAL')
        require(env.get('ERASURE_MAINTENANCE_LOCK_ENABLED') == 'true')
        require(env.get('ERASURE_MAINTENANCE_DIRECTORY') == '/home/luha/geupddong-maintenance')
        actual.append(phase_of(env))
    require(tuple(actual) == STEPS[(role, phase)], 'ACCOUNT_RESUME_STEP_ORDER_REJECTED')

def read_owned(path, private=False):
    require(path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 1000 and info.st_nlink == 1)
    require(stat.S_IMODE(info.st_mode) == 0o600 if private else info.st_gid == 1000 and not (info.st_mode & 0o002))
    require(info.st_size <= 1024 * 1024)
    return path.read_bytes()

def atomic_replace(path, content):
    # Only the already validated account dotenv, never the shared lock or any data file.
    descriptor, temporary = tempfile.mkstemp(prefix='.account-resume-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

def apply_step(path, original, replacement, verify_before, restart, verify_after):
    verify_before()
    require(read_owned(path, private=True) == original)
    backup = path.with_name('.account-resume-before-' + str(time.time_ns()) + '.env')
    fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(original)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        atomic_replace(path, replacement)
        restart()
        verify_after()
    except Exception:
        try:
            # Configuration rollback only. It cannot restore erased personal information.
            atomic_replace(path, original)
            restart()
        except Exception:
            raise RuntimeError('ACCOUNT_RESUME_ROLLBACK_UNVERIFIED') from None
        raise RuntimeError('ACCOUNT_RESUME_FAILED_CONFIG_RESTORED_RECHECK_REQUIRED') from None

class Host:
    def __init__(self, role):
        self.role = role
        self.root = Path('/home/luha/toilet-' + role)
        require(self.root.resolve(strict=True) == self.root)
        info = self.root.stat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == 1000 and info.st_gid == 1000 and not (info.st_mode & 0o002))
        self.compose = self.root / ('compose.yaml' if role == 'api' else 'docker-compose.yml')
        self.service = 'api' if role == 'api' else 'toilet-batch'
        spec = importlib.util.spec_from_file_location('resume_context', '/home/luha/.local/bin/restore-ledger-context.py')
        self.context = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.context)

    def run(self, args, env=None, timeout=30):
        return subprocess.run(args, env=env, capture_output=True, text=True, check=True,
                              timeout=timeout).stdout.strip()

    def capture(self):
        return {role: json.loads(self.run(['docker', 'inspect', 'toilet-' + role]))[0] for role in ('api', 'batch')}

    def compose_command(self, *args):
        return ['docker', 'compose', '--project-directory', str(self.root), '-f', str(self.compose), *args]

    def check_dependencies(self, objects):
        values = {role: environment(item) for role, item in objects.items()}
        epoch = values['api']['ERASURE_CHECKPOINT_DATABASE_EPOCH']
        self.context.select({'toilet-' + k: v for k, v in objects.items()}, epoch)
        require(values['api'].get('REDIS_PASSWORD') and values['api']['REDIS_PASSWORD'] == values['batch'].get('REDIS_PASSWORD'))
        for role in ('api', 'batch'):
            info = self.context.maintenance_lease.ROOT.joinpath('.maintenance.lock').stat()
            actual = self.run(['docker', 'exec', '--user', '1000:1000', 'toilet-' + role, 'stat',
                               '-c', '%d:%i:%a:%u:%h:%s', str(self.context.maintenance_lease.ROOT / '.maintenance.lock')])
            require(actual == f'{info.st_dev}:{info.st_ino}:600:1000:1:0')
        api = values['api']
        raw = self.run(['docker', 'exec', '-e', 'MYSQL_PWD', 'toilet-mysql', 'mysql', '-u',
                        api['SPRING_DB_USERNAME'], '--database=toilet_db', '--batch', '--skip-column-names', '-e', SQL],
                       dict(os.environ, MYSQL_PWD=api['SPRING_DB_PASSWORD']))
        require(raw == '0', 'ACCOUNT_RESUME_EXISTING_QUEUE_REQUIRES_REVIEW')
        require(self.run(['docker', 'exec', '-e', 'REDISCLI_AUTH', 'toilet-redis', 'redis-cli', 'PING'],
                         dict(os.environ, REDISCLI_AUTH=api['REDIS_PASSWORD'])) == 'PONG')
        records = []
        for path in Path('/home/luha/.local/state/geupddong-backup-restore/receipts').glob('checked-*.json'):
            records.append(json.loads(read_owned(path)))
        latest = max(records, key=lambda item: item['checkedAt'])
        checked = datetime.fromisoformat(latest['checkedAt'].replace('Z', '+00:00'))
        require(checked.tzinfo is not None and 0 <= (datetime.now(timezone.utc) - checked).total_seconds() <= 86400)
        snapshot, _ = self.context.snapshot(epoch)
        require(all(snapshot.get(k) == v for k, v in latest['ledgerSnapshot'].items()))
        require(snapshot.get('stableSnapshot') is True and snapshot.get('ledgerWrites') is False
                and snapshot.get('checkpointWrites') is False)

    def healthy(self, obj):
        env = environment(obj)
        port = env.get('API_PORT' if self.role == 'api' else 'BATCH_PORT', '')
        require(port.isascii() and port.isdigit() and 1 <= int(port) <= 65535)
        address = obj['NetworkSettings']['Networks']['toilet-network']['IPAddress']
        require(ipaddress.ip_address(address).is_private)
        route = '/api/health' if self.role == 'api' else '/actuator/health'
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open('http://' + address + ':' + port + route, timeout=4) as response:
            require(response.status == 200)
            body = response.read(8192).decode()
        require(body == 'API server is running (DB: toilet_db)' if self.role == 'api'
                else json.loads(body).get('status') == 'UP')

    def restart(self):
        intended = parse_env(read_owned(self.root / '.account-lifecycle.env', private=True))
        rendered = json.loads(self.run(self.compose_command('config', '--format', 'json')))
        effective = rendered['services'][self.service]['environment']
        require(all(effective.get(k) == v for k, v in intended.items()),
                'ACCOUNT_RESUME_COMPOSE_OVERRIDE_REJECTED')
        self.run(self.compose_command('up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                                      '--wait', '--wait-timeout', '90', self.service), timeout=110)
        deadline = time.monotonic() + 60
        while True:
            try:
                self.healthy(self.capture()[self.role])
                return
            except Exception:
                if time.monotonic() >= deadline:
                    raise ValueError('ACCOUNT_RESUME_HEALTH_UNVERIFIED') from None
                time.sleep(2)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--role', required=True, choices=('api', 'batch'))
    parser.add_argument('--phase', required=True, choices=('guarded', 'active'))
    parser.add_argument('--api-commit', required=True)
    parser.add_argument('--batch-commit', required=True)
    parser.add_argument('--apply-approved', action='store_true')
    parser.add_argument('--deployment-freeze-confirmed', action='store_true')
    args = parser.parse_args()
    require(os.geteuid() == 1000 and sys.platform.startswith('linux'))
    # Active transition requires a later policy-publication release, not a boolean bypass here.
    require(args.phase == 'guarded', 'ACCOUNT_RESUME_POLICY_RELEASE_REQUIRED')
    require(not args.apply_approved or args.deployment_freeze_confirmed)
    host = Host(args.role)
    commits = {'api': args.api_commit, 'batch': args.batch_commit}
    with host.context.maintenance_lease.acquire():
        original_objects = host.capture()
        validate_step(original_objects, args.role, args.phase, commits)
        host.check_dependencies(original_objects)
        env_path = host.root / '.account-lifecycle.env'
        original = read_owned(env_path, private=True)
        compose = read_owned(host.compose)
        base_env = read_owned(host.root / '.env', private=True)
        replacement = candidate(original, environment(original_objects[args.role]), args.phase)
        rendered = json.loads(host.run(host.compose_command('config', '--format', 'json')))
        service = rendered['services'][host.service]
        require(service['image'] == original_objects[args.role]['Config']['Image'])
        require(host.run(['docker', 'image', 'inspect', '--format', '{{.Id}}', service['image']])
                == original_objects[args.role]['Image'])
        require(service.get('user') == '1000:1000')
        # Ensure dotenv flags are not silently overridden by the Compose environment section.
        require(all(service['environment'].get(k, 'false' if k in OFF_FLAGS else None) == v
                    for k, v in flags('paused').items()))
        def before():
            require(host.capture() == original_objects)
            require(read_owned(host.compose) == compose and read_owned(host.root / '.env', private=True) == base_env)
        def after():
            current = host.capture()
            peer = 'batch' if args.role == 'api' else 'api'
            require(current[peer] == original_objects[peer])
            require(current[args.role]['Image'] == original_objects[args.role]['Image'])
            expected = environment(original_objects[args.role]) | flags(args.phase)
            require(environment(current[args.role]) == expected)
            require(phase_of(environment(current[args.role])) == args.phase)
            require(read_owned(host.compose) == compose and read_owned(host.root / '.env', private=True) == base_env)
            host.check_dependencies(current)
        before()
        if args.apply_approved:
            apply_step(env_path, original, replacement, before, host.restart, after)
    print(json.dumps({'outcome': 'GUARDED_CONFIGURATION_APPLIED' if args.apply_approved else 'GUARDED_PREFLIGHT_PASS',
                      'role': args.role, 'accountActionsEnabled': False, 'retirementEnabled': False,
                      'directDatabaseWrites': False, 'applied': args.apply_approved}))

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        code = str(error) if str(error).startswith('ACCOUNT_RESUME_') and re.fullmatch(r'[A-Z_]+', str(error)) else 'ACCOUNT_RESUME_HELD'
        print(code + ' detailsSuppressed=true', file=sys.stderr)
        raise SystemExit(1)
