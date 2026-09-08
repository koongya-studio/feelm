#!/usr/bin/env python3
"""Atomically update reviewed cache automation in an existing CI 222 install."""
import argparse
import importlib.util
import os
from pathlib import Path
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
SERVICE = 'feelm-ci222-gitlab-runner.service'
SUDOERS_TEXT = (
    'feelm-ci-test ALL=(root) NOPASSWD: /usr/bin/python3 -I -B '
    '/usr/local/lib/feelm-ci222/gitlab/ci-gitlab-job-adapter.py run\n'
    'ubuntu ALL=(root) NOPASSWD: /usr/bin/python3 -I -B '
    '/usr/local/lib/feelm-ci222/gitlab/ci-dependency-cache.py\n'
)


def pilot():
    spec = importlib.util.spec_from_file_location('ci271_install_pilot',
                                                  HERE / 'ci-server-manager-pilot.py')
    value = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = value
    spec.loader.exec_module(value)
    return value


def trusted_tree(root):
    for item in (root, *root.rglob('*')):
        info = item.lstat()
        if (not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode))
                or info.st_uid != 0 or info.st_mode & 0o022):
            raise ValueError('Update source must be a root-owned regular tree')


def copy_source(destination):
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


def update():
    if not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise ValueError('Linux root operator required')
    pilot().check_base(HERE.parent)
    trusted_tree(HERE)
    if TARGET.is_symlink() or not TARGET.is_dir() or SUDOERS.is_symlink() or not SUDOERS.is_file():
        raise ValueError('Existing CI 222 installation is required')
    trusted_tree(TARGET)
    replacement = Path(tempfile.mkdtemp(prefix='.gitlab-ci271-', dir=TARGET.parent))
    replacement.rmdir()
    copy_source(replacement)
    sudoers_new = SUDOERS.with_name(SUDOERS.name + '.ci271-' + uuid.uuid4().hex)
    fd = os.open(sudoers_new, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o440)
    with os.fdopen(fd, 'w') as output:
        output.write(SUDOERS_TEXT)
    subprocess.run(['/usr/sbin/visudo', '-cf', str(sudoers_new)], check=True, timeout=30)
    active = subprocess.run(
        ['systemctl', 'list-units', '--state=running', '--no-legend',
         'ci222-owner-*.service', 'ci222-server-*.service'],
        capture_output=True, text=True, check=True, timeout=30).stdout.strip()
    if active:
        raise ValueError('MR test units are still running; keep the existing install')
    backup = TARGET.with_name(TARGET.name + '.previous-' + uuid.uuid4().hex)
    sudoers_backup = SUDOERS.with_name(SUDOERS.name + '.previous-' + uuid.uuid4().hex)
    subprocess.run(['systemctl', 'stop', SERVICE], check=True, timeout=120)
    try:
        os.replace(TARGET, backup)
        os.replace(replacement, TARGET)
        os.replace(SUDOERS, sudoers_backup)
        os.replace(sudoers_new, SUDOERS)
        subprocess.run(['systemctl', 'start', SERVICE], check=True, timeout=120)
        subprocess.run(['systemctl', 'is-active', '--quiet', SERVICE], check=True, timeout=30)
    except Exception:
        if TARGET.exists() and backup.exists():
            failed = TARGET.with_name(TARGET.name + '.failed-' + uuid.uuid4().hex)
            os.replace(TARGET, failed)
            os.replace(backup, TARGET)
        if SUDOERS.exists() and sudoers_backup.exists():
            failed = SUDOERS.with_name(SUDOERS.name + '.failed-' + uuid.uuid4().hex)
            os.replace(SUDOERS, failed)
            os.replace(sudoers_backup, SUDOERS)
        subprocess.run(['systemctl', 'start', SERVICE], check=False, timeout=120)
        raise
    print('CACHE_AUTOMATION_INSTALL: PASS')
    print('PREVIOUS_INSTALL=' + str(backup))
    print('PREVIOUS_SUDOERS=' + str(sudoers_backup))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-update', action='store_true')
    args = parser.parse_args()
    if not args.confirm_update:
        print('PLAN: atomically update trusted CI scripts and sudoers; restart only the MR Runner')
        return 0
    update()
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        print('CACHE_AUTOMATION_INSTALL_FAILED: ' + type(exc).__name__, file=sys.stderr)
        raise SystemExit(2)
