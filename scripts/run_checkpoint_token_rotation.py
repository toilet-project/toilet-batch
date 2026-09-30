"""Runner transport. The credential travels only in the SSH stdin pipe."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import checkpoint_token_rotation as rotation


BOOTSTRAP = "import json,sys,types;p=json.load(sys.stdin);m=types.ModuleType('account_resume_transition');sys.modules[m.__name__]=m;exec(compile(p['helper'],'account_resume_transition.py','exec'),m.__dict__);n={'__name__':'checkpoint_token_rotation'};exec(compile(p['program'],'checkpoint_token_rotation.py','exec'),n);n['entry'](p)"


def main():
    operation = os.environ['ROTATION_OPERATION']
    rotation.require(operation in ('check', 'apply', 'verify', 'probe', 'diagnose', 'recover'))
    token = os.environ[rotation.KEY]
    rotation.validate_token(token)
    if operation == 'probe':
        expiry, _ = rotation.probe(token)
        print(json.dumps({'outcome': 'CHECKPOINT_TOKEN_SECRET_VALID', 'expiresAtUtc': expiry}))
        return
    rotation.require(os.environ['TUNNEL_SSH_HOST'] == 'ssh-deploy.geupddong.com')
    user = os.environ['TUNNEL_SSH_USER']
    rotation.require(re.fullmatch(r'[a-z_][a-z0-9_-]*', user))
    commits = {role: os.environ[role.upper() + '_COMMIT'] for role in rotation.ROLES}
    rotation.require(all(re.fullmatch(r'[a-f0-9]{40}', value) for value in commits.values()))
    scripts = Path(__file__).parent
    payload = {'token': token, 'operation': operation, 'commits': commits,
               'helper': (scripts / 'account_resume_transition.py').read_text(encoding='utf-8'),
               'program': (scripts / 'checkpoint_token_rotation.py').read_text(encoding='utf-8')}
    temporary = Path(os.environ['RUNNER_TEMP'])
    key = temporary / 'checkpoint-rotation-key'
    hosts = temporary / 'checkpoint-rotation-hosts'
    try:
        for path, variable in ((key, 'DEPLOY_SSH_KEY'), (hosts, 'TUNNEL_KNOWN_HOSTS')):
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as stream:
                stream.write(os.environ[variable] + '\n')
        # Do not inherit the GitHub PAT into ssh/cloudflared; only stdin carries it.
        transport_env = {k: v for k, v in os.environ.items()
                         if k not in (rotation.KEY, 'DEPLOY_SSH_KEY', 'TUNNEL_KNOWN_HOSTS')}
        args = ['ssh', '-F', '/dev/null', '-T', '-i', str(key),
                '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes', '-o', 'PasswordAuthentication=no',
                '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=' + str(hosts),
                '-o', 'HostKeyAlias=geupddong-mini-pc', '-o', 'HostKeyAlgorithms=ssh-ed25519',
                '-o', 'ForwardAgent=no', '-o', 'ClearAllForwardings=yes',
                '-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
                '-o', 'ProxyCommand=cloudflared access ssh --hostname %h',
                user + '@ssh-deploy.geupddong.com', 'python3 -B -c ' + "'" + BOOTSTRAP.replace("'", "'\"'\"'") + "'"]
        result = subprocess.run(args, input=json.dumps(payload), text=True, capture_output=True,
                                env=transport_env, timeout=600)
        # Only accept the deliberately minimal JSON result. Never forward raw SSH output.
        if result.returncode:
            safe = re.findall(r'CHECKPOINT_TOKEN_[A-Z_]+', result.stderr)
            safe = [value for value in safe if value != 'CHECKPOINT_TOKEN_LOCATION']
            print((safe[0] if safe else 'CHECKPOINT_TOKEN_TRANSPORT_OR_SERVER_FAILED')
                  + ' detailsSuppressed=true', file=sys.stderr)
            for line in result.stderr.splitlines():
                if line.startswith('CHECKPOINT_TOKEN_LOCATION '):
                    location = json.loads(line.removeprefix('CHECKPOINT_TOKEN_LOCATION '))
                    if (set(location) == {'file', 'line', 'function'}
                            and location['file'] in ('account_resume_transition.py', 'checkpoint_token_rotation.py')
                            and isinstance(location['line'], int)
                            and re.fullmatch(r'[A-Za-z_][A-Za-z_0-9]*', location['function'])):
                        print(json.dumps(location), file=sys.stderr)
            raise SystemExit(1)
        summary = json.loads(result.stdout)
        allowed = ({'outcome', 'services'} if operation == 'diagnose' else
                   {'outcome', 'expiresAtUtc', 'runtimeMatches', 'apiCommit', 'batchCommit',
                    'accountPhases', 'credentialOnly'})
        rotation.require(set(summary) == allowed)
        print(json.dumps(summary))
    finally:
        key.unlink(missing_ok=True)
        hosts.unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('CHECKPOINT_TOKEN_RUNNER_HELD detailsSuppressed=true', file=sys.stderr)
        raise SystemExit(1)
