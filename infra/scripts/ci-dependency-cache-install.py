#!/usr/bin/env python3
"""Atomically update reviewed cache automation in an existing CI 222 install."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
TARGET = Path('/usr/local/lib/feelm-ci222/gitlab')
SUDOERS = Path('/etc/sudoers.d/feelm-ci222-gitlab')
CONFIG = Path('/etc/feelm-ci222/gitlab-adapter.json')
SERVICE = 'feelm-ci222-gitlab-runner.service'
OPERATOR_FILE = 'cache-operators.json'
SUDOERS_TEXT = (
    'feelm-ci-test ALL=(root) NOPASSWD: /usr/bin/python3 -I -B '
    '/usr/local/lib/feelm-ci222/gitlab/ci-gitlab-job-adapter.py run\n'
    'gitlab-runner ALL=(root) NOPASSWD: /usr/bin/python3 -I -B '
    '/usr/local/lib/feelm-ci222/gitlab/ci-dependency-cache.py\n'
)


def pilot():
    spec = importlib.util.spec_from_file_location('ci271_install_pilot',
                                                  HERE / 'ci-server-manager-pilot.py')
    value = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = value
    spec.loader.exec_module(value)
    return value


def runtime_contract():
    spec = importlib.util.spec_from_file_location(
        'ci414_runtime_contract', HERE / 'ci-backend-runtime-contract.py')
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def trusted_tree(root):
    for item in (root, *root.rglob('*')):
        info = item.lstat()
        if (not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode))
                or info.st_uid != 0 or info.st_mode & 0o022):
            raise ValueError('Update source must be a root-owned regular tree')


def normalized_operators(values):
    result = sorted(set(values or []))
    if not result or any(not re.fullmatch(r'[A-Za-z0-9_.-]{1,64}', name) for name in result):
        raise ValueError('At least one valid cache operator username is required')
    return result


def copy_source(destination, operators):
    destination.mkdir(mode=0o755)
    for item in sorted(HERE.rglob('*')):
        relative = item.relative_to(HERE)
        target = destination / relative
        if item.is_dir():
            target.mkdir(mode=0o755)
        else:
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            with item.open('rb') as source, target.open('xb') as output:
                shutil.copyfileobj(source, output)
            target.chmod(0o644)
    policy = destination / OPERATOR_FILE
    policy.write_text(json.dumps({'usernames': operators}, sort_keys=True) + '\n')
    policy.chmod(0o600)


def private_config(path):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077
            or info.st_nlink != 1 or info.st_size > 4096):
        raise ValueError('Adapter configuration must be root-owned mode 600')
    value = json.loads(path.read_text())
    if set(value) != {'gitlab_url', 'project_path', 'prepared_base'}:
        raise ValueError('Unexpected adapter configuration fields')
    return value


def write_new(path, data, mode):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'w') as output:
        output.write(data)


def update(operators, prepared_base):
    if not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise ValueError('Linux root operator required')
    manager = pilot()
    manager.check_base(HERE.parent)
    manager.check_base(prepared_base)
    trusted_tree(HERE)
    runtime_contract().require_same(HERE, prepared_base / 'scripts')
    if (TARGET.is_symlink() or not TARGET.is_dir() or SUDOERS.is_symlink()
            or not SUDOERS.is_file() or CONFIG.is_symlink() or not CONFIG.is_file()):
        raise ValueError('Existing CI 222 installation is required')
    trusted_tree(TARGET)
    old_config = private_config(CONFIG)
    new_config = dict(old_config, prepared_base=str(prepared_base))
    active = subprocess.run(
        ['systemctl', 'list-units', '--state=running', '--no-legend',
         'ci222-owner-*.service', 'ci222-server-*.service'],
        capture_output=True, text=True, check=True, timeout=30).stdout.strip()
    if active:
        raise ValueError('MR test units are still running; keep the existing install')
    replacement = Path(tempfile.mkdtemp(prefix='.gitlab-ci271-', dir=TARGET.parent))
    replacement.rmdir()
    copy_source(replacement, operators)
    sudoers_new = SUDOERS.with_name(SUDOERS.name + '.ci271-' + uuid.uuid4().hex)
    write_new(sudoers_new, SUDOERS_TEXT, 0o440)
    config_new = CONFIG.with_name(CONFIG.name + '.ci414-' + uuid.uuid4().hex)
    write_new(config_new, json.dumps(new_config, sort_keys=True) + '\n', 0o600)
    subprocess.run(['/usr/sbin/visudo', '-cf', str(sudoers_new)], check=True, timeout=30)
    backup = TARGET.with_name(TARGET.name + '.previous-' + uuid.uuid4().hex)
    sudoers_backup = SUDOERS.with_name(SUDOERS.name + '.previous-' + uuid.uuid4().hex)
    config_backup = CONFIG.with_name(CONFIG.name + '.previous-' + uuid.uuid4().hex)
    subprocess.run(['systemctl', 'stop', SERVICE], check=True, timeout=120)
    try:
        os.replace(TARGET, backup)
        os.replace(replacement, TARGET)
        os.replace(SUDOERS, sudoers_backup)
        os.replace(sudoers_new, SUDOERS)
        os.replace(CONFIG, config_backup)
        os.replace(config_new, CONFIG)
        subprocess.run(['systemctl', 'start', SERVICE], check=True, timeout=120)
        subprocess.run(['systemctl', 'is-active', '--quiet', SERVICE], check=True, timeout=30)
    except Exception:
        if backup.exists():
            if TARGET.exists():
                failed = TARGET.with_name(TARGET.name + '.failed-' + uuid.uuid4().hex)
                os.replace(TARGET, failed)
            os.replace(backup, TARGET)
        if sudoers_backup.exists():
            if SUDOERS.exists():
                failed = SUDOERS.with_name(SUDOERS.name + '.failed-' + uuid.uuid4().hex)
                os.replace(SUDOERS, failed)
            os.replace(sudoers_backup, SUDOERS)
        if config_backup.exists():
            if CONFIG.exists():
                failed = CONFIG.with_name(CONFIG.name + '.failed-' + uuid.uuid4().hex)
                os.replace(CONFIG, failed)
            os.replace(config_backup, CONFIG)
        subprocess.run(['systemctl', 'start', SERVICE], check=False, timeout=120)
        raise
    print('CACHE_AUTOMATION_INSTALL: PASS')
    print('PREVIOUS_INSTALL=' + str(backup))
    print('PREVIOUS_SUDOERS=' + str(sudoers_backup))
    print('PREVIOUS_CONFIG=' + str(config_backup))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-update', action='store_true')
    parser.add_argument('--operator-username', action='append', default=[])
    parser.add_argument('--prepared-base', type=Path)
    args = parser.parse_args()
    if not args.confirm_update:
        print('PLAN: atomically update matching trusted CI scripts and prepared_base, root-owned cache operator policy, config and sudoers; restart only the MR Runner')
        return 0
    if args.prepared_base is None:
        raise ValueError('Confirmed update requires the matching prepared base')
    update(normalized_operators(args.operator_username), args.prepared_base)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        print('CACHE_AUTOMATION_INSTALL_FAILED: ' + type(exc).__name__ + ': ' + str(exc),
              file=sys.stderr)
        raise SystemExit(2)
