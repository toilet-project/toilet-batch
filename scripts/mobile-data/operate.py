"""Pinned batch-only operations; injected resume module/config arrive over SSH stdin.

Never print environment values, credentials, process stderr or database rows.
"""
import json
import re

KEYS = ('MOBILE_CATALOG_ENABLED', 'MOBILE_CATALOG_CRON',
        'MOBILE_CATALOG_ORIGIN', 'MOBILE_CATALOG_PUBLISH_TOKEN')
CLI = ['docker', 'exec', '--user', '1000:1000', 'toilet-batch', 'java',
       '-Dloader.main=com.example.toiletbatch.mobile.MobileCatalogCli',
       '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher']


def settings(token):
    if not re.fullmatch(r'[A-Za-z0-9_-]{48,128}', token or ''):
        raise ValueError('CATALOG_CREDENTIAL_REQUIRED')
    return dict(zip(KEYS, ('true', '0 30 4 * * *', 'https://mobile-data.geupddong.com', token)))


def candidate_env(content, values):
    # Preserve all unrelated bytes/lines. Reject ambiguous overrides instead of guessing.
    text = content.decode('utf-8')
    lines = text.splitlines(keepends=True)
    for line in lines:
        if any(key in line for key in KEYS) and not line.lstrip().startswith('#'):
            if not any(line.startswith(key + '=') for key in KEYS):
                raise ValueError('CATALOG_AMBIGUOUS_ENVIRONMENT')
    retained = ''.join(line for line in lines if not any(line.startswith(key + '=') for key in KEYS))
    if retained and not retained.endswith('\n'):
        retained += '\n'
    return (retained + ''.join(key + "='" + values[key] + "'\n" for key in KEYS)).encode()


def validate_render(before, after, values):
    expected = json.loads(json.dumps(before))
    expected['services']['toilet-batch']['environment'].update(values)
    if after != expected:
        raise ValueError('CATALOG_UNRELATED_CONFIGURATION_CHANGE')


def operate(config, resume):
    host = resume.Host('batch')
    operation = config['operation']
    if operation == 'status':
        objects = host.capture()
        for role in ('api', 'batch'):
            resume.Host(role).healthy(objects[role])
        batch = resume.environment(objects['batch'])
        return dict(images={role: obj['Config']['Image'] for role, obj in objects.items()},
                    healthy=True, enabled=batch.get('MOBILE_CATALOG_ENABLED') == 'true',
                    cron=batch.get('MOBILE_CATALOG_CRON', '-'))
    if operation not in ('configure', 'publish') or config.get('approved') is not True:
        raise ValueError('CATALOG_OPERATION_NOT_APPROVED')
    commits = {role: config.get(role + '_commit', '') for role in ('api', 'batch')}
    with host.context.maintenance_lease.acquire():
        original_objects = host.capture()
        resume.validate_step(original_objects, 'batch', 'preserve', commits)
        host.healthy(original_objects['batch'])
        resume.require(host.run(CLI + ['--check-runtime']) == 'mobile-catalog-cli-ready', 'CATALOG_RUNTIME_MISSING')
        host.run(['docker', 'exec', 'toilet-batch', 'python3', '--version'])
        if operation == 'publish':
            args = list(CLI)
            if config.get('register_baseline') is True:
                args.append('--register-baseline')
            raw = host.run(args, timeout=1250)
            receipt = json.loads(raw)
            resume.require(receipt['count'] > 0 and receipt['month'], 'CATALOG_INVALID_RECEIPT')
            return dict(outcome='CATALOG_PUBLISHED', **receipt)
        values = settings(config.get('publish_token'))
        ledger = host.check_dependencies(original_objects, initial_transition=False)
        env_path = host.root / '.env'
        original = resume.read_owned(env_path, private=True)
        compose = resume.read_owned(host.compose)
        account = resume.read_owned(host.root / '.account-lifecycle.env', private=True)
        rendered = json.loads(host.run(host.compose_command('config', '--format', 'json')))
        original_env = resume.environment(original_objects['batch'])
        replacement = candidate_env(original, values)
        def before():
            resume.require(host.capture() == original_objects)
            resume.require(resume.read_owned(host.compose) == compose)
            resume.require(resume.read_owned(host.root / '.account-lifecycle.env', private=True) == account)
        def restart():
            changed = json.loads(host.run(host.compose_command('config', '--format', 'json')))
            # apply_step also calls this during rollback.
            if resume.read_owned(env_path, private=True) == replacement:
                validate_render(rendered, changed, values)
            else:
                resume.require(changed == rendered)
            host.restart()
        def after():
            current = host.capture()
            resume.require(current['api'] == original_objects['api'])
            resume.require(current['batch']['Image'] == original_objects['batch']['Image'])
            resume.require(current['batch']['Mounts'] == original_objects['batch']['Mounts'])
            resume.require(resume.environment(current['batch']) == dict(original_env, **values))
            resume.require(resume.read_owned(host.compose) == compose)
            resume.require(resume.read_owned(host.root / '.account-lifecycle.env', private=True) == account)
            resume.require(host.check_dependencies(current, initial_transition=False) == ledger)
        resume.apply_step(env_path, original, replacement, before, restart, after)
        return dict(outcome='CATALOG_CONFIGURED', cron=values['MOBILE_CATALOG_CRON'],
                    apiUnchanged=True, accountConfigurationUnchanged=True)


if __name__ == '__main__':
    try:
        print(json.dumps(operate(CONFIG, resume)))
    except Exception as error:
        # Subprocess exception strings can include credential-bearing stdout/stderr.
        code = str(error) if isinstance(error, ValueError) and re.fullmatch(r'[A-Z_]{1,100}', str(error)) else None
        print(json.dumps(dict(outcome='CATALOG_OPERATION_FAILED', errorType=type(error).__name__, code=code)))
        raise SystemExit(1) from None
