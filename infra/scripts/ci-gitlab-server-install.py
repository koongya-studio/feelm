#!/usr/bin/env python3
"""Install the reviewed GitLab adapter files, without registering or starting a Runner."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
try:
    import pwd
except ImportError:  # Windows unit-test collection only; install is Linux-only.
    pwd = None
import shutil
import stat
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
TARGET = Path('/usr/local/lib/feelm-ci222/gitlab')
CONFIG = Path('/etc/feelm-ci222/gitlab-adapter.json')
RUNNER_HOME = Path('/home/feelm-ci-test/gitlab-runner')
UNIT = Path('/etc/systemd/system/feelm-ci222-gitlab-runner.service')
SUDOERS = Path('/etc/sudoers.d/feelm-ci222-gitlab')
SUDOERS_LINE = ('feelm-ci-test ALL=(root) NOPASSWD: /usr/bin/python3 -I -B '
                 '/usr/local/lib/feelm-ci222/gitlab/ci-gitlab-job-adapter.py run\n')


def load_pilot():
    spec = importlib.util.spec_from_file_location('gitlab_install_pilot', HERE / 'ci-server-manager-pilot.py')
    module = importlib.util.module_from_spec(spec); sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def preflight():
    if pwd is None or not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise ValueError('Linux root operator required')
    base = HERE.parent
    load_pilot().check_base(base)
    if not (base / 'prepared.json').is_file(): raise ValueError('Prepared server stage required')
    account = pwd.getpwnam('feelm-ci-test')
    if account.pw_uid != 1001 or account.pw_gid != 1001 or account.pw_dir != '/home/feelm-ci-test':
        raise ValueError('Reviewed test account identity differs')
    for path in (TARGET, CONFIG, RUNNER_HOME, UNIT, SUDOERS):
        if path.exists() or path.is_symlink(): raise FileExistsError(str(path))
    for item in HERE.rglob('*'):
        info = item.lstat()
        if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('Untrusted staged installer input')
    unit_source = HERE / 'systemd/feelm-ci222-gitlab-runner.service'
    subprocess.run(['systemd-analyze', 'verify', str(unit_source)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    return base, account


def copy_scripts():
    temporary = Path(tempfile.mkdtemp(prefix='.gitlab-install-', dir=TARGET.parent))
    temporary.chmod(0o755)
    for item in sorted(HERE.rglob('*')):
        relative = item.relative_to(HERE)
        destination = temporary / relative
        if item.is_dir():
            destination.mkdir(mode=0o755)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            with item.open('rb') as source, destination.open('xb') as output:
                shutil.copyfileobj(source, output)
            destination.chmod(0o644)
    os.replace(temporary, TARGET)


def write_new(path, content, mode):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'w') as output:
        output.write(content); output.flush(); os.fsync(output.fileno())


def install():
    base, account = preflight()
    copy_scripts()
    config = {'gitlab_url': 'https://lab.ssafy.com',
              'project_path': 's15-bigdata-dist-sub1/S15P21E106',
              'prepared_base': str(base)}
    write_new(CONFIG, json.dumps(config, sort_keys=True) + '\n', 0o600)
    RUNNER_HOME.mkdir(mode=0o700)
    for name in ('builds', 'cache'):
        (RUNNER_HOME / name).mkdir(mode=0o700)
    for path in (RUNNER_HOME, RUNNER_HOME / 'builds', RUNNER_HOME / 'cache'):
        os.chown(path, account.pw_uid, account.pw_gid)
    unit_source = HERE / 'systemd/feelm-ci222-gitlab-runner.service'
    write_new(UNIT, unit_source.read_text(), 0o644)
    write_new(SUDOERS, SUDOERS_LINE, 0o440)
    subprocess.run(['/usr/sbin/visudo', '-cf', str(SUDOERS)], check=True, timeout=30)
    subprocess.run(['systemctl', 'daemon-reload'], check=True, timeout=30)
    print('GITLAB_ADAPTER_INSTALL: PASS')
    print('RUNNER_REGISTERED=no')
    print('RUNNER_SERVICE_STARTED=no')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-install', action='store_true')
    args = parser.parse_args()
    if not args.confirm_install:
        print('PLAN: create new GitLab adapter/config/Runner paths; no registration, token, start or enable')
        return 0
    install()
    return 0


if __name__ == '__main__': raise SystemExit(main())
