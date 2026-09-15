#!/usr/bin/env python3
"""Create the pinned Pipeline MR runner configuration from prepared inputs."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
try:
    import pwd
except ImportError:  # Windows unit-test collection only; installation is Linux-only.
    pwd = None
import re
import stat
import subprocess
import sys


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parent / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

CONFIG = Path('/etc/feelm-ci222/pipeline-mr.json')
RUNNER_USER = 'feelm-ci-test'
DOCKER_HOST = 'unix:///run/user/1001/docker.sock'
IMAGE = 'feelm-pipeline-ci:python3.10-java21-pyspark4.1.3'
CACHE_ROOT = Path('/var/lib/feelm-ci222/pipeline-cache')
cache_policy = load('pipeline_config_cache_policy', 'ci-pipeline-cache-policy.py')


def docker_command(*arguments):
    return ['runuser', '--user', RUNNER_USER, '--', 'env', '-i',
            'HOME=/home/' + RUNNER_USER, 'PATH=/usr/bin:/bin',
            'DOCKER_HOST=' + DOCKER_HOST, '/usr/bin/docker', *arguments]


def trusted_cache(path, digest):
    path = path.resolve(strict=True)
    try:
        path.relative_to(CACHE_ROOT)
    except ValueError as exc:
        raise RuntimeError('Pipeline dependency cache is outside the approved root') from exc
    info = path.lstat()
    if (not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022
            or info.st_mode & 0o555 != 0o555
            or not re.fullmatch(r'[0-9a-f]{64}', digest)):
        raise RuntimeError('Pipeline dependency cache or digest is not trusted')
    cache_policy.verify(path, digest)
    return path


def install(dependency_cache, dependency_cache_sha256, project_manifest_sha256,
            uv_lock_sha256, path=CONFIG, replace=False):
    if (pwd is None or not sys.platform.startswith('linux') or not hasattr(os, 'geteuid')
            or os.geteuid() != 0):
        raise RuntimeError('Linux root operator required')
    account = pwd.getpwnam(RUNNER_USER)
    if account.pw_uid != 1001 or account.pw_gid != 1001:
        raise RuntimeError('Reviewed Pipeline runner account identity differs')
    cache = trusted_cache(Path(dependency_cache), dependency_cache_sha256)
    for value in (project_manifest_sha256, uv_lock_sha256):
        if re.fullmatch(r'[0-9a-f]{64}', value or '') is None:
            raise ValueError('Reviewed Pipeline manifest and lock digests are required')
    inspected = subprocess.run(docker_command('image', 'inspect', IMAGE, '--format', '{{.Id}}'),
                               capture_output=True, text=True, check=True, timeout=30)
    image_id = inspected.stdout.strip()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
        raise RuntimeError('Rootless Pipeline image returned an invalid identity')
    parent = path.parent
    info = parent.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise RuntimeError('Pipeline configuration directory is not trusted')
    content = json.dumps({
        'dependency_cache': str(cache),
        'dependency_cache_sha256': dependency_cache_sha256,
        'docker_host': DOCKER_HOST,
        'pipeline_image': IMAGE,
        'pipeline_image_id': image_id,
        'project_manifest_sha256': project_manifest_sha256,
        'runner_user': RUNNER_USER,
        'uv_lock_sha256': uv_lock_sha256,
    }, sort_keys=True) + '\n'
    if replace:
        current = path.lstat()
        if (not stat.S_ISREG(current.st_mode) or current.st_uid != 0
                or current.st_mode & 0o077 or current.st_nlink != 1):
            raise RuntimeError('Existing Pipeline configuration is not trusted')
        destination = path.with_name('.' + path.name + '.' + str(os.getpid()))
    else:
        destination = path
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                         0o600)
    with os.fdopen(descriptor, 'w') as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())
    if replace:
        os.replace(destination, path)
        parent_descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
        try:
            os.fsync(parent_descriptor)
        finally:
            os.close(parent_descriptor)
    print('PIPELINE_MR_CONFIG_INSTALLED=' + image_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group()
    action.add_argument('--confirm-install', action='store_true')
    action.add_argument('--confirm-replace', action='store_true')
    parser.add_argument('--dependency-cache')
    parser.add_argument('--dependency-cache-sha256')
    parser.add_argument('--project-manifest-sha256')
    parser.add_argument('--uv-lock-sha256')
    args = parser.parse_args()
    if not (args.confirm_install or args.confirm_replace):
        print('PLAN: inspect one preloaded Pipeline image and pin one reviewed offline cache')
        return 0
    if any(value is None for value in (
            args.dependency_cache, args.dependency_cache_sha256,
            args.project_manifest_sha256, args.uv_lock_sha256)):
        parser.error('cache, manifest, and lock identities are required with confirmation')
    install(args.dependency_cache, args.dependency_cache_sha256,
            args.project_manifest_sha256, args.uv_lock_sha256,
            replace=args.confirm_replace)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
