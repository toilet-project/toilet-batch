"""Rotate only the checkpoint credential, under the installed shared maintenance lease.

Receives the new secret via SSH stdin. Never emits credentials, environment dumps,
checkpoint contents or exception text. Images, member flags and ledger data stay fixed.
"""
import copy
from datetime import datetime, timezone
import json
import re
import sys
import traceback
import urllib.request

import account_resume_transition as resume

KEY = 'ERASURE_CHECKPOINT_GITHUB_TOKEN'
REPOSITORY = 'https://api.github.com/repos/toilet-project/operations-checkpoints'
ROLES = ('api', 'batch')


def require(value, code='CHECKPOINT_TOKEN_ROTATION_HELD'):
    if not value:
        raise ValueError(code)


def validate_token(token):
    require(isinstance(token, str) and re.fullmatch(r'github_pat_[A-Za-z0-9_]{20,245}', token),
            'CHECKPOINT_TOKEN_FORMAT_REJECTED')


def github_get(token, suffix=''):
    validate_token(token)
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    request = urllib.request.Request(REPOSITORY + suffix, headers={
        'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'geupddong-checkpoint-rotation'})
    with opener.open(request, timeout=15) as response:
        require(response.status == 200)
        body = response.read(1024 * 1024 + 1)
        require(len(body) <= 1024 * 1024)
        expiry = response.headers.get('GitHub-Authentication-Token-Expiration', '')
    return json.loads(body), expiry


def probe(token):
    repository, expiry = github_get(token)
    require(repository.get('full_name') == 'toilet-project/operations-checkpoints'
            and repository.get('private') is True, 'CHECKPOINT_TOKEN_REPOSITORY_REJECTED')
    require(re.fullmatch(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} UTC', expiry),
            'CHECKPOINT_TOKEN_EXPIRY_UNAVAILABLE')
    parsed = datetime.strptime(expiry, '%Y-%m-%d %H:%M:%S UTC').replace(tzinfo=timezone.utc)
    require((parsed - datetime.now(timezone.utc)).total_seconds() > 86400 * 30,
            'CHECKPOINT_TOKEN_EXPIRY_TOO_SOON')
    head, _ = github_get(token, '/git/ref/heads/main')
    revision = head.get('object', {}).get('sha', '')
    require(re.fullmatch(r'[a-f0-9]{40}', revision), 'CHECKPOINT_TOKEN_HISTORY_UNAVAILABLE')
    return parsed.isoformat(), revision


def replacement(content, runtime, token):
    validate_token(token)
    values = resume.parse_env(content)
    require(KEY in values and all(runtime.get(k) == v for k, v in values.items()),
            'CHECKPOINT_TOKEN_CONFIG_DRIFT')
    pattern = re.compile(rb"(?m)^ERASURE_CHECKPOINT_GITHUB_TOKEN='[^'\r\n]*'$")
    result, count = pattern.subn(lambda _: (KEY + "='" + token + "'").encode(), content)
    require(count == 1 and resume.parse_env(result) == values | {KEY: token})
    return result


def check_render(original, candidate, service, token):
    expected = copy.deepcopy(original)
    expected['services'][service]['environment'][KEY] = token
    require(candidate == expected, 'CHECKPOINT_TOKEN_COMPOSE_DRIFT')


def apply_pair(hosts, originals, candidates, before, after):
    """All config files first, then same-image recreates; restore both on a known failure."""
    before()
    written = []
    try:
        for role in ROLES:
            path = hosts[role].root / '.account-lifecycle.env'
            require(resume.read_owned(path, private=True) == originals[role])
            written.append(role)
            resume.atomic_replace(path, candidates[role])
        for role in ('batch', 'api'):
            hosts[role].restart()
        after()
    except Exception:
        try:
            # Never overwrite an unrelated concurrent edit. Validate ALL files before rollback.
            for role in ROLES:
                actual = resume.read_owned(hosts[role].root / '.account-lifecycle.env', private=True)
                require(actual in (originals[role], candidates[role]), 'CHECKPOINT_TOKEN_EXTERNAL_CHANGE')
            for role in written:
                resume.atomic_replace(hosts[role].root / '.account-lifecycle.env', originals[role])
            for role in ('batch', 'api'):
                hosts[role].restart()
            for role in ROLES:
                require(resume.environment(hosts[role].capture()[role])[KEY]
                        == resume.parse_env(originals[role])[KEY])
        except Exception:
            raise RuntimeError('CHECKPOINT_TOKEN_ROLLBACK_UNVERIFIED') from None
        raise RuntimeError('CHECKPOINT_TOKEN_FAILED_ORIGINAL_RESTORED') from None


def diagnose(token):
    result = {}
    hosts = {role: resume.Host(role) for role in ROLES}
    objects = hosts['batch'].capture()
    for role in ROLES:
        host, obj = hosts[role], objects[role]
        env = resume.environment(obj)
        file_env = resume.parse_env(resume.read_owned(host.root / '.account-lifecycle.env', private=True))
        try:
            host.healthy(obj)
            healthy = True
        except Exception:
            healthy = False
        logs = host.run(['docker', 'logs', '--tail', '250', 'toilet-' + role])
        # Whitelisted error classes/constants only. Raw application output is never forwarded.
        exceptions = sorted(set(re.findall(r'\b([A-Z][A-Za-z]+(?:Exception|Error))\b', logs)))
        codes = sorted(set(re.findall(r'\b(?:ERASURE|ACCOUNT|CHECKPOINT)_[A-Z_]{3,80}\b', logs)))
        placeholders = sorted(set(re.findall(r"Could not resolve placeholder '([A-Z][A-Z0-9_]*)'", logs)))
        result[role] = {
            'running': obj['State']['Running'], 'restarting': obj['State']['Restarting'],
            'oomKilled': obj['State']['OOMKilled'], 'exitCode': obj['State']['ExitCode'],
            'startedAt': obj['State']['StartedAt'], 'healthy': healthy,
            'imageCommit': obj['Config']['Image'].rsplit(':', 1)[-1],
            'candidateMatchesRuntime': env.get(KEY) == token,
            'candidateMatchesFile': file_env.get(KEY) == token,
            'fileMatchesRuntime': all(env.get(k) == v for k, v in file_env.items()),
            'exceptionTypes': exceptions, 'errorCodes': codes, 'missingPlaceholders': placeholders}
    return {'outcome': 'CHECKPOINT_TOKEN_DIAGNOSE', 'services': result}


def run(token, operation, commits):
    require(operation in ('check', 'apply', 'verify', 'diagnose'))
    if operation == 'diagnose':
        return diagnose(token)
    expires, revision = probe(token)
    hosts = {role: resume.Host(role) for role in ROLES}
    host = hosts['batch']
    with host.context.maintenance_lease.acquire():
        initial = host.capture()
        resume.validate_step(initial, 'batch', 'preserve', commits)
        phases = {role: resume.phase_of(resume.environment(initial[role])) for role in ROLES}
        originals, candidates, compose, base, renders = {}, {}, {}, {}, {}
        for role in ROLES:
            item = hosts[role]
            item.healthy(initial[role])
            originals[role] = resume.read_owned(item.root / '.account-lifecycle.env', private=True)
            candidates[role] = replacement(originals[role], resume.environment(initial[role]), token)
            compose[role] = resume.read_owned(item.compose)
            base[role] = resume.read_owned(item.root / '.env', private=True)
            renders[role] = json.loads(item.run(item.compose_command('config', '--format', 'json')))
            service = renders[role]['services'][item.service]
            require(service['image'] == initial[role]['Config']['Image'])
            require(all(resume.environment(initial[role]).get(k) == str(v)
                        for k, v in service['environment'].items()), 'CHECKPOINT_TOKEN_COMPOSE_DRIFT')
            require(item.run(['docker', 'image', 'inspect', '--format', '{{.Id}}', service['image']])
                    == initial[role]['Image'])
            lock = host.context.maintenance_lease.ROOT / '.maintenance.lock'
            info = lock.stat()
            actual = item.run(['docker', 'exec', '--user', '1000:1000', 'toilet-' + role,
                               'stat', '-c', '%d:%i:%a:%u:%h:%s', str(lock)])
            require(actual == f'{info.st_dev}:{info.st_ino}:600:1000:1:0')

        def unchanged_files():
            for role in ROLES:
                require(resume.read_owned(hosts[role].compose) == compose[role])
                require(resume.read_owned(hosts[role].root / '.env', private=True) == base[role])

        def before():
            require(host.capture() == initial, 'CHECKPOINT_TOKEN_RUNTIME_DRIFT')
            unchanged_files()
            require(probe(token) == (expires, revision), 'CHECKPOINT_TOKEN_HISTORY_CHANGED')

        def after():
            current = host.capture()
            unchanged_files()
            for role in ROLES:
                item = hosts[role]
                require(resume.read_owned(item.root / '.account-lifecycle.env', private=True) == candidates[role])
                require(current[role]['Image'] == initial[role]['Image'])
                require(current[role]['Config']['Image'] == initial[role]['Config']['Image'])
                require(current[role]['HostConfig'] == initial[role]['HostConfig'])
                require(current[role]['Mounts'] == initial[role]['Mounts'])
                require(resume.environment(current[role]) == resume.environment(initial[role]) | {KEY: token})
                require(resume.phase_of(resume.environment(current[role])) == phases[role])
                rendered = json.loads(item.run(item.compose_command('config', '--format', 'json')))
                check_render(renders[role], rendered, item.service, token)
                item.healthy(current[role])
            require(probe(token) == (expires, revision), 'CHECKPOINT_TOKEN_HISTORY_CHANGED')
            # Real installed verifier reads both runtime credentials and the LOCAL ledger.
            host.check_dependencies(current, initial_transition=False)

        matched = all(resume.environment(initial[role]).get(KEY) == token for role in ROLES)
        before()
        if operation == 'apply' and not matched:
            # Validate both changed Compose renders before recreating either service.
            original_restart = {}
            for role in ROLES:
                original_restart[role] = hosts[role].restart
                def restart_checked(role=role):
                    unchanged_files()
                    for target in ROLES:
                        rendered = json.loads(hosts[target].run(hosts[target].compose_command('config', '--format', 'json')))
                        # Also permits exact originals during configuration rollback.
                        target_values = resume.parse_env(resume.read_owned(hosts[target].root / '.account-lifecycle.env', private=True))
                        check_render(renders[target], rendered, hosts[target].service, target_values[KEY])
                    original_restart[role]()
                hosts[role].restart = restart_checked
            apply_pair(hosts, originals, candidates, before, after)
            matched = True
        elif operation == 'verify' or (operation == 'apply' and matched):
            require(matched, 'CHECKPOINT_TOKEN_RUNTIME_NOT_UPDATED')
            after()
    return {'outcome': 'CHECKPOINT_TOKEN_' + operation.upper() + '_PASS',
            'expiresAtUtc': expires, 'runtimeMatches': matched,
            'apiCommit': commits['api'], 'batchCommit': commits['batch'],
            'accountPhases': phases, 'credentialOnly': True}


def entry(payload):
    try:
        print(json.dumps(run(payload['token'], payload['operation'], payload['commits'])))
    except Exception as error:
        code = str(error)
        if not re.fullmatch(r'CHECKPOINT_TOKEN_[A-Z_]+', code):
            code = 'CHECKPOINT_TOKEN_ROTATION_HELD'
        print(code + ' detailsSuppressed=true', file=sys.stderr)
        # Source locations only; no exception messages, locals, source text or credentials.
        cause = error
        for _ in range(5):
            for frame in traceback.extract_tb(cause.__traceback__):
                filename = frame.filename.replace('\\', '/').rsplit('/', 1)[-1]
                if filename in ('account_resume_transition.py', 'checkpoint_token_rotation.py'):
                    print('CHECKPOINT_TOKEN_LOCATION ' + json.dumps({
                        'file': filename, 'line': frame.lineno, 'function': frame.name}), file=sys.stderr)
            cause = cause.__context__
            if cause is None:
                break
        raise SystemExit(1)
