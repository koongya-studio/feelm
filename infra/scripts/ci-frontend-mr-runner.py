#!/usr/bin/env python3
"""Run one validated frontend MR snapshot through a fixed rootless Docker policy."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
try:
    import pwd
except ImportError:  # Windows unit-test collection only; execution is Linux-only.
    pwd = None
import re
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import uuid

HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec); sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


boundary = load('frontend_mr_boundary', 'ci-frontend-mr-boundary.py')
bounded = load('frontend_mr_bounded', 'ci-bounded-output.py')
network = load('frontend_mr_network', 'ci-network-guard.py')
CONFIG = Path('/etc/feelm-ci222/frontend-mr.json')
INSTALLED = Path('/usr/local/lib/feelm-ci222/gitlab/ci-frontend-mr-runner.py')
RUNTIME_ROOT = Path('/run')
FIXED_COMMAND = (
    'npm ci --ignore-scripts'
    ' && node ./node_modules/eslint/bin/eslint.js .'
    ' && node ./node_modules/prettier/bin/prettier.cjs --check .'
    ' && node ./node_modules/typescript/bin/tsc -b'
    ' && node ./node_modules/vite/bin/vite.js build'
)


def read_config(path=CONFIG):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077
            or info.st_nlink != 1 or info.st_size > 4096):
        raise RuntimeError('Frontend runner configuration must be root-owned mode 600')
    value = json.loads(path.read_text())
    if set(value) != {'runner_user', 'docker_host', 'node_image', 'node_image_id'}:
        raise RuntimeError('Unexpected frontend runner configuration fields')
    if value['runner_user'] != network.ACCOUNT:
        raise RuntimeError('Unexpected frontend runner account')
    if value['docker_host'] != 'unix:///run/user/1001/docker.sock':
        raise RuntimeError('Unexpected rootless Docker socket')
    if not re.fullmatch(r'node:20\.19(?:\.\d+)?-alpine(?:\d+(?:\.\d+)?)?', value['node_image']):
        raise RuntimeError('Unreviewed Node image tag')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', value['node_image_id']):
        raise RuntimeError('Reviewed Node image ID is required')
    return value


def guard_ready():
    result = subprocess.run(['systemctl', 'is-active', '--quiet', 'feelm-ci-guard.service'],
                            timeout=10)
    if result.returncode:
        raise RuntimeError('Network guard service is not active')
    network.owned_table()


def docker_command(config, *arguments):
    return ['runuser', '--user', config['runner_user'], '--', 'env', '-i',
            'HOME=/home/' + config['runner_user'], 'PATH=/usr/bin:/bin',
            'DOCKER_HOST=' + config['docker_host'], '/usr/bin/docker', *arguments]


def cleanup_container(config, container):
    if not re.fullmatch(r'ci330-frontend-[a-f0-9]{12}', container):
        raise ValueError('Invalid frontend container identity')
    removed = subprocess.run(docker_command(config, 'rm', '--force', container),
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             timeout=30)
    if removed.returncode == 0:
        return 0
    remaining = subprocess.run(docker_command(config, 'container', 'inspect', container),
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               timeout=30)
    daemon = subprocess.run(docker_command(config, 'info', '--format', '{{.ServerVersion}}'),
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            timeout=30)
    if remaining.returncode == 0 or daemon.returncode != 0:
        raise RuntimeError('Frontend container cleanup could not be verified')
    return 0


def write_container_archive(source, output):
    root = Path(source) / 'frontend'

    def normalized(info):
        if info.isdir():
            info.mode = 0o755
        elif info.isfile():
            info.mode = 0o644
        else:
            raise RuntimeError('Unexpected frontend staging entry')
        info.uid = 0; info.gid = 0; info.uname = ''; info.gname = ''; info.mtime = 0
        return info

    with tarfile.open(fileobj=output, mode='w') as archive:
        for child in sorted(root.iterdir(), key=lambda path: path.name):
            archive.add(child, arcname=child.name, recursive=True, filter=normalized)


def workload(config, source, container):
    source = Path(source).resolve(strict=True)
    if not re.fullmatch(r'ci330-frontend-[a-f0-9]{12}', container):
        raise ValueError('Invalid frontend container identity')
    inspect = subprocess.run(docker_command(config, 'image', 'inspect', config['node_image'],
                             '--format', '{{.Id}}'), capture_output=True, text=True,
                             timeout=30, check=True)
    if inspect.stdout.strip() != config['node_image_id']:
        raise RuntimeError('Rootless Node image identity differs from reviewed configuration')
    command = docker_command(
        config, 'run', '--rm', '--interactive', '--name', container, '--pull=never',
        '--network=bridge',
        '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
        '--memory=2g', '--cpus=2', '--pids-limit=512',
        '--tmpfs', '/work:rw,exec,nodev,nosuid,size=1536m',
        '--tmpfs', '/tmp:rw,nodev,nosuid,size=256m',
        '--workdir', '/work', '--env', 'CI=true', '--env', 'HOME=/tmp',
        config['node_image_id'], 'sh', '-c',
        'tar -xf - -C /work && ' + FIXED_COMMAND)
    try:
        # Stream the already validated, immutable snapshot through the client.
        # A host bind path is not reliable across the rootless daemon mount namespace.
        with tempfile.TemporaryFile() as packed:
            write_container_archive(source, packed)
            packed.seek(0)
            return subprocess.run(command, stdin=packed, timeout=850).returncode
    finally:
        cleanup_container(config, container)


def unit_command(config, source, unit, container):
    if not re.fullmatch(r'ci222-owner-[a-f0-9]{12}\.service', unit):
        raise ValueError('Invalid frontend unit identity')
    return [
        'systemd-run', '--unit=' + unit, '--service-type=exec', '--wait', '--pipe', '--collect',
        '--property=CPUQuota=200%',
        '--property=MemoryHigh=2G', '--property=MemoryMax=3G', '--property=TasksMax=768',
        '--property=RuntimeMaxSec=900', '--property=TimeoutStopSec=15',
        '--property=KillMode=control-group', '--property=NoNewPrivileges=yes',
        '--property=PrivateTmp=yes', '--property=ProtectSystem=strict',
        '--property=ProtectHome=read-only', '--property=RestrictSUIDSGID=yes',
        '--property=ExecStopPost=/usr/bin/python3 -I -B ' + str(INSTALLED)
        + ' cleanup --container=' + container,
        '/usr/bin/python3', '-I', '-B', str(INSTALLED), 'workload',
        '--source=' + str(source), '--container=' + container,
    ]


def stop_unit(unit):
    if not re.fullmatch(r'ci222-owner-[a-f0-9]{12}\.service', unit):
        raise ValueError('Refusing unrelated frontend unit')
    subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=30)


def start(payload, digest, on_started=None, config_path=CONFIG):
    if pwd is None or not sys.platform.startswith('linux'):
        raise RuntimeError('Frontend MR execution requires Linux')
    config = read_config(config_path)
    guard_ready()
    account = pwd.getpwnam(config['runner_user'])
    if account.pw_uid != 1001 or account.pw_gid != 1001:
        raise RuntimeError('Reviewed frontend runner account identity differs')
    runtime = RUNTIME_ROOT.lstat()
    if not stat.S_ISDIR(runtime.st_mode) or runtime.st_uid != 0 or runtime.st_mode & 0o022:
        raise RuntimeError('Trusted host-visible runtime directory required')
    token = uuid.uuid4().hex[:12]
    unit = 'ci222-owner-' + token + '.service'
    container = 'ci330-frontend-' + token
    # PrivateTmp hides host /tmp and /var/tmp from the transient owner unit.
    # /run remains visible while the random root-created directory bounds access.
    attempt = Path(tempfile.mkdtemp(prefix='ci330-frontend-', dir=RUNTIME_ROOT))
    staging = attempt / 'source'
    staging.mkdir(mode=0o700)
    log = attempt / 'output.log'
    process = None
    try:
        boundary.install_snapshot(payload, digest, staging)
        attempt.chmod(0o711)
        if on_started is not None:
            on_started(staging, {'owner': unit})
        process = bounded.CappedProcess(unit_command(config, staging, unit, container), log)
        try:
            code = process.wait(930)
        except (subprocess.TimeoutExpired, RuntimeError):
            stop_unit(unit)
            process.reap(30)
            raise
        output = log.read_text(errors='replace')
        print(output[-bounded.LIMIT:], end='')
        if code:
            return 2
        print('FRONTEND_MR_TEST_PASSED: fixed Node policy; no artifacts exported')
        return 0
    finally:
        stop_unit(unit)
        if process is not None and process.child.poll() is None:
            process.reap(30)
        shutil.rmtree(attempt, ignore_errors=True)


def main():
    if sys.argv[1:] and sys.argv[1] in ('workload', 'cleanup'):
        action = sys.argv[1]
        source = next((arg.split('=', 1)[1] for arg in sys.argv[2:] if arg.startswith('--source=')), None)
        container = next((arg.split('=', 1)[1] for arg in sys.argv[2:] if arg.startswith('--container=')), None)
        if container is None or (action == 'workload' and source is None):
            return 64
        config = read_config()
        if action == 'cleanup':
            return cleanup_container(config, container)
        return workload(config, source, container)
    print('PLAN: validated frontend-only archive, fixed rootless Node container, bounded resources/output')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
