#!/usr/bin/env python3
"""Atomically update reviewed MR adapter scripts without changing prepared inputs."""
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid
from contextlib import contextmanager
from pathlib import Path

try:
    import fcntl
except ImportError:  # Windows unit-test collection only; execution is Linux-only.
    fcntl = None

TARGET = Path('/usr/local/lib/feelm-ci222/gitlab')
CONFIG = Path('/etc/feelm-ci222/gitlab-adapter.json')
SUDOERS = Path('/etc/sudoers.d/feelm-ci222-gitlab')
OPERATORS = TARGET / 'cache-operators.json'
LOCK = Path('/run/lock/feelm-ci271.lock')
SERVICE = 'feelm-ci222-gitlab-runner.service'
STAGE_PATTERN = re.compile(r'/var/tmp/ci222-server-[a-z0-9_]+')
MAX_MANIFEST = 4 * 1024**2


def trusted_tree(root):
    for item in (root, *root.rglob('*')):
        info = item.lstat()
        if (not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode))
                or info.st_uid != 0 or info.st_mode & 0o022):
            raise ValueError('Adapter update source must be a root-owned immutable tree')


def trusted_file(path, maximum, private=False):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022
            or info.st_nlink != 1 or info.st_size > maximum
            or (private and info.st_mode & 0o077)):
        raise ValueError('Existing adapter state is not trusted: ' + str(path))


def file_digest(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def verify_upload(stage):
    trusted_tree(stage)
    manifest_path = stage / 'upload-manifest.json'
    trusted_file(manifest_path, MAX_MANIFEST)
    try:
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError('Invalid adapter upload manifest') from exc
    if (not isinstance(manifest, dict) or not manifest
            or any(not isinstance(name, str) or not re.fullmatch(r'[0-9a-f]{64}', digest or '')
                   for name, digest in manifest.items())):
        raise ValueError('Invalid adapter upload manifest entries')
    scripts = stage / 'scripts'
    if not scripts.is_dir() or scripts.is_symlink():
        raise ValueError('Adapter upload scripts directory is missing')
    actual = {}
    for item in sorted(scripts.rglob('*')):
        if item.is_dir():
            continue
        relative = item.relative_to(stage).as_posix()
        actual[relative] = file_digest(item)
    expected = {name: digest for name, digest in manifest.items()
                if name.startswith('scripts/')}
    if not actual or actual != expected:
        raise ValueError('Adapter upload manifest differs from staged scripts')
    if 'scripts/cache-operators.json' in actual:
        raise ValueError('Staged adapter must not replace the operator policy')
    return scripts


def active_units():
    result = subprocess.run(
        ['systemctl', 'list-units', '--state=running', '--no-legend',
         'ci222-owner-*.service', 'ci222-server-*.service', 'ci352-owner-*.service',
         'ci486-owner-*.service', 'ci490-infra-*.service'],
        capture_output=True, text=True, check=True, timeout=30)
    return result.stdout.strip()


@contextmanager
def exclusive_lock():
    if fcntl is None:
        raise ValueError('Adapter update lock requires Linux')
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    with LOCK.open('a+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def service_active():
    result = subprocess.run(
        ['systemctl', 'is-active', '--quiet', SERVICE],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    return result.returncode == 0


def stop_service():
    subprocess.run(['systemctl', 'stop', SERVICE], check=True, timeout=120)


def start_service(check=True):
    result = subprocess.run(['systemctl', 'start', SERVICE], check=check, timeout=120)
    if result.returncode != 0:
        raise RuntimeError('MR Runner start command failed')
    if not service_active():
        raise RuntimeError('MR Runner did not become active')


def adapter_smoke(path):
    result = subprocess.run(
        ['/usr/bin/python3', '-I', '-B', str(path)],
        capture_output=True, text=True, timeout=30)
    if result.returncode != 64 or not result.stderr.startswith('USAGE:'):
        raise ValueError('Staged MR adapter import smoke failed')


def preflight(stage):
    if not sys.platform.startswith('linux') or not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise ValueError('Linux root operator required')
    stage = stage.resolve(strict=True)
    if not STAGE_PATTERN.fullmatch(stage.as_posix()):
        raise ValueError('Adapter update requires a private server stage')
    scripts = verify_upload(stage)
    if TARGET.is_symlink() or not TARGET.is_dir():
        raise ValueError('Existing adapter installation is required')
    trusted_tree(TARGET)
    trusted_file(CONFIG, 4096, private=True)
    trusted_file(SUDOERS, 4096)
    trusted_file(OPERATORS, 4096, private=True)
    if active_units():
        raise ValueError('MR test units are still running; keep the existing install')
    return scripts


def copy_scripts(scripts, destination):
    destination.mkdir(mode=0o755)
    for item in sorted(scripts.rglob('*')):
        relative = item.relative_to(scripts)
        target = destination / relative
        if item.is_dir():
            target.mkdir(mode=0o755)
        else:
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            with item.open('rb') as source, target.open('xb') as output:
                shutil.copyfileobj(source, output)
            target.chmod(0o644)
    with OPERATORS.open('rb') as source, (destination / OPERATORS.name).open('xb') as output:
        shutil.copyfileobj(source, output)
    (destination / OPERATORS.name).chmod(0o600)


def replace_install(replacement):
    if not service_active():
        raise ValueError('MR Runner must be active before adapter update')
    backup = TARGET.with_name(TARGET.name + '.previous-' + uuid.uuid4().hex)
    stop_attempted = False
    restart_attempted = False
    try:
        stop_attempted = True
        stop_service()
        if service_active():
            raise RuntimeError('MR Runner remained active after stop')
        if active_units():
            raise ValueError('MR test units started during adapter update')
        os.replace(TARGET, backup)
        os.replace(replacement, TARGET)
        adapter_smoke(TARGET / 'ci-gitlab-job-adapter.py')
        restart_attempted = True
        start_service(check=True)
    except Exception as failure:
        try:
            if restart_attempted:
                stop_error = None
                try:
                    stop_service()
                except Exception as exc:
                    stop_error = exc
                if service_active():
                    raise RuntimeError(
                        'MR Runner could not be stopped before adapter rollback') from stop_error
            if TARGET.exists() and backup.exists():
                failed = TARGET.with_name(TARGET.name + '.failed-' + uuid.uuid4().hex)
                os.replace(TARGET, failed)
                os.replace(backup, TARGET)
            elif not TARGET.exists() and backup.exists():
                os.replace(backup, TARGET)
            if stop_attempted:
                start_service(check=False)
        except Exception as recovery:
            raise RuntimeError('MR adapter recovery failed: ' + str(recovery)) from failure
        raise
    print('GITLAB_ADAPTER_UPDATE: PASS')
    print('PREVIOUS_INSTALL=' + str(backup))


def update(stage):
    with exclusive_lock():
        scripts = preflight(Path(stage))
        adapter_smoke(scripts / 'ci-gitlab-job-adapter.py')
        replacement = Path(
            tempfile.mkdtemp(prefix='.gitlab-adapter-update-', dir=TARGET.parent))
        replacement.rmdir()
        copy_scripts(scripts, replacement)
        replace_install(replacement)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage')
    parser.add_argument('--confirm-update', action='store_true')
    args = parser.parse_args()
    if not args.confirm_update:
        print('PLAN: verify one uploaded adapter stage; preserve config, sudoers and '
              'prepared inputs; '
              'atomically replace scripts; restart only the MR Runner')
        return 0
    if args.stage is None:
        parser.error('--stage is required with --confirm-update')
    update(args.stage)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as exc:
        print('GITLAB_ADAPTER_UPDATE_FAILED: ' + type(exc).__name__ + ': ' + str(exc),
              file=sys.stderr)
        raise SystemExit(2) from None
