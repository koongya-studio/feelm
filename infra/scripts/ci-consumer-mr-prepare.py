#!/usr/bin/env python3
"""Build and transfer the fixed Consumer MR image from preloaded inputs."""

import argparse
import os
from pathlib import Path
try:
    import pwd
except ImportError:  # Windows unit-test collection only; preparation is Linux-only.
    pwd = None
import re
import subprocess
import sys
import tempfile


RUNNER_USER = 'feelm-ci-test'
DOCKER_HOST = 'unix:///run/user/1001/docker.sock'
BASE_IMAGE = ('python:3.12.14-slim-bookworm@sha256:'
              '782412e85d0f0984994c290652577d4018aff08145c85b262bb63dc0c7522254')
UV_IMAGE = 'ghcr.io/astral-sh/uv:0.12.5'
TARGET_IMAGE = 'feelm-consumer-ci:python3.12-uv0.12.5'


def rootless_command(*arguments):
    return ['runuser', '--user', RUNNER_USER, '--', 'env', '-i',
            'HOME=/home/' + RUNNER_USER, 'PATH=/usr/bin:/bin',
            'DOCKER_HOST=' + DOCKER_HOST, '/usr/bin/docker', *arguments]


def checked(command, **options):
    return subprocess.run(command, check=True, timeout=options.pop('timeout', 300), **options)


def require_host():
    if (pwd is None or not sys.platform.startswith('linux') or not hasattr(os, 'geteuid')
            or os.geteuid() != 0):
        raise RuntimeError('Linux root operator required')
    account = pwd.getpwnam(RUNNER_USER)
    if account.pw_uid != 1001 or account.pw_gid != 1001:
        raise RuntimeError('Reviewed Consumer runner account identity differs')
    guard = subprocess.run(['systemctl', 'is-active', '--quiet', 'feelm-ci-guard.service'],
                           timeout=10)
    if guard.returncode:
        raise RuntimeError('Network guard service is not active')
    for image in (BASE_IMAGE, UV_IMAGE):
        checked(['/usr/bin/docker', 'image', 'inspect', image],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)


def dockerfile():
    return (
        f'FROM {UV_IMAGE} AS uv\n'
        f'FROM {BASE_IMAGE}\n'
        'COPY --from=uv /uv /uvx /bin/\n'
        "RUN python --version 2>&1 | grep -E '^Python 3[.]12[.]' \\\n"
        " && uv --version | grep -E '^uv 0[.]12[.]5([[:space:]]|$)'\n"
    )


def image_identity(command):
    result = subprocess.run(command, capture_output=True, text=True, timeout=30)
    if result.returncode:
        return None
    identity = result.stdout.strip()
    if re.fullmatch(r'sha256:[0-9a-f]{64}', identity) is None:
        raise RuntimeError('Consumer CI image returned an invalid identity')
    return identity


def prepare():
    require_host()
    existing_root = image_identity(
        ['/usr/bin/docker', 'image', 'inspect', TARGET_IMAGE, '--format', '{{.Id}}'])
    existing_rootless = image_identity(
        rootless_command('image', 'inspect', TARGET_IMAGE, '--format', '{{.Id}}'))
    if existing_root is not None or existing_rootless is not None:
        if existing_root is None or existing_rootless is None or existing_root != existing_rootless:
            raise RuntimeError('Partial or mismatched Consumer CI image requires review')
        print('CONSUMER_MR_IMAGE_READY=' + existing_root)
        return existing_root
    with tempfile.TemporaryDirectory(prefix='ci486-consumer-image-') as directory:
        context = Path(directory)
        (context / 'Dockerfile').write_text(dockerfile(), encoding='utf-8')
        checked(['/usr/bin/docker', 'build', '--pull=false', '--network=none',
                 '--tag', TARGET_IMAGE, str(context)], timeout=900)
    root_id = image_identity(
        ['/usr/bin/docker', 'image', 'inspect', TARGET_IMAGE, '--format', '{{.Id}}'])
    if root_id is None:
        raise RuntimeError('Built Consumer CI image is missing')
    checked(['/usr/bin/docker', 'run', '--rm', '--pull=never', '--network=none',
             TARGET_IMAGE, 'sh', '-c',
             "python --version 2>&1 | grep -E '^Python 3[.]12[.]'"
             " && uv --version | grep -E '^uv 0[.]12[.]5([[:space:]]|$)'"], timeout=60)
    saved = subprocess.Popen(['/usr/bin/docker', 'save', TARGET_IMAGE], stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE)
    try:
        loaded = subprocess.run(rootless_command('load'), stdin=saved.stdout,
                                capture_output=True, timeout=600)
        if saved.stdout is not None:
            saved.stdout.close()
        _, save_error = saved.communicate(timeout=60)
    except Exception:
        saved.kill()
        saved.wait()
        raise
    if saved.returncode or loaded.returncode:
        raise RuntimeError('Consumer image transfer to Rootless Docker failed: '
                           + save_error.decode(errors='replace')[-256:])
    rootless_id = image_identity(
        rootless_command('image', 'inspect', TARGET_IMAGE, '--format', '{{.Id}}'))
    if rootless_id != root_id:
        raise RuntimeError('Rootless Consumer image identity differs after transfer')
    print('CONSUMER_MR_IMAGE_READY=' + root_id)
    return root_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-build', action='store_true')
    args = parser.parse_args()
    if not args.confirm_build:
        print('PLAN: build one fixed Consumer CI image from two preloaded inputs; no Runner changes')
        return 0
    prepare()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
