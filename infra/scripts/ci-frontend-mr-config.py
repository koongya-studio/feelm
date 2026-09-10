#!/usr/bin/env python3
"""Create the pinned frontend MR runner configuration from a preloaded image."""
import argparse
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


CONFIG = Path('/etc/feelm-ci222/frontend-mr.json')
RUNNER_USER = 'feelm-ci-test'
DOCKER_HOST = 'unix:///run/user/1001/docker.sock'
IMAGE_PATTERN = r'node:20\.19(?:\.\d+)?-alpine(?:\d+(?:\.\d+)?)?'


def docker_command(*arguments):
    return ['runuser', '--user', RUNNER_USER, '--', 'env', '-i',
            'HOME=/home/' + RUNNER_USER, 'PATH=/usr/bin:/bin',
            'DOCKER_HOST=' + DOCKER_HOST, '/usr/bin/docker', *arguments]


def install(image, path=CONFIG, replace=False):
    if (pwd is None or not sys.platform.startswith('linux') or not hasattr(os, 'geteuid')
            or os.geteuid() != 0):
        raise RuntimeError('Linux root operator required')
    if not re.fullmatch(IMAGE_PATTERN, image):
        raise ValueError('Unreviewed Node image tag')
    account = pwd.getpwnam(RUNNER_USER)
    if account.pw_uid != 1001 or account.pw_gid != 1001:
        raise RuntimeError('Reviewed frontend runner account identity differs')
    guard = subprocess.run(['systemctl', 'is-active', '--quiet', 'feelm-ci-guard.service'],
                           timeout=10)
    if guard.returncode:
        raise RuntimeError('Network guard service is not active')
    inspected = subprocess.run(docker_command('image', 'inspect', image, '--format', '{{.Id}}'),
                               capture_output=True, text=True, check=True, timeout=30)
    image_id = inspected.stdout.strip()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
        raise RuntimeError('Rootless Node image returned an invalid identity')
    parent = path.parent
    info = parent.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise RuntimeError('Frontend configuration directory is not trusted')
    content = json.dumps({'docker_host': DOCKER_HOST, 'node_image': image,
                          'node_image_id': image_id, 'runner_user': RUNNER_USER},
                         sort_keys=True) + '\n'
    if replace:
        current = path.lstat()
        if (not stat.S_ISREG(current.st_mode) or current.st_uid != 0
                or current.st_mode & 0o077 or current.st_nlink != 1):
            raise RuntimeError('Existing frontend configuration is not trusted')
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
    print('FRONTEND_MR_CONFIG_INSTALLED=' + image_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group()
    action.add_argument('--confirm-install', action='store_true')
    action.add_argument('--confirm-replace', action='store_true')
    parser.add_argument('--node-image')
    args = parser.parse_args()
    if not (args.confirm_install or args.confirm_replace):
        print('PLAN: inspect one preloaded rootless Node 20.19 image and create a root-only config')
        return 0
    if args.node_image is None:
        parser.error('--node-image is required with a confirmation option')
    install(args.node_image, replace=args.confirm_replace)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
