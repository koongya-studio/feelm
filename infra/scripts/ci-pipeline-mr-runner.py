#!/usr/bin/env python3
"""Run one validated pipeline/spark MR snapshot through a fixed offline policy."""
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
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


boundary = load('pipeline_mr_boundary', 'ci-pipeline-mr-boundary.py')
bounded = load('pipeline_mr_bounded', 'ci-bounded-output.py')
cache_policy = load('pipeline_cache_policy', 'ci-pipeline-cache-policy.py')
CONFIG = Path('/etc/feelm-ci222/pipeline-mr.json')
INSTALLED = Path('/usr/local/lib/feelm-ci222/gitlab/ci-pipeline-mr-runner.py')
RUNTIME_ROOT = Path('/run')
SETUP_COMMAND = (
    'mkdir -p /uv-cache-work'
    ' && cp --recursive --no-preserve=ownership,mode,timestamps'
    ' /uv-cache-source/. /uv-cache-work/'
    ' && chmod -R u+rwX /uv-cache-work'
    ' && uv lock --check --offline'
    ' && uv sync --frozen --offline'
)
GROUP_COMMANDS = {
    'unit': (
        'uv run --frozen --offline ruff check .'
        ' && uv run --frozen --offline python -m ci_test_groups verify'
        ' && uv run --frozen --offline python -m ci_test_groups unit -vv --durations=20'
        ' && uv run --frozen --offline python -m jobs.batch.als_baseline --help'
        ' && uv run --frozen --offline python -m jobs.batch.parquet_roundtrip --help'
    ),
    'spark-als-a-d': (
        'uv run --frozen --offline python -m ci_test_groups spark-als-a-d'
        ' -vv --durations=20'
    ),
    'spark-als-e-z': (
        'uv run --frozen --offline python -m ci_test_groups spark-als-e-z'
        ' -vv --durations=20'
    ),
    'spark-other': (
        'uv run --frozen --offline python -m ci_test_groups spark-other'
        ' -vv --durations=20'
    ),
}


def fixed_command(group):
    command = GROUP_COMMANDS.get(group)
    if command is None:
        raise ValueError('Unknown Pipeline test group')
    return SETUP_COMMAND + ' && ' + command


def read_config(path=CONFIG):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077
            or info.st_nlink != 1 or info.st_size > 4096):
        raise RuntimeError('Pipeline runner configuration must be root-owned mode 600')
    value = json.loads(path.read_text())
    required = {'runner_user', 'docker_host', 'pipeline_image', 'pipeline_image_id',
                'dependency_cache', 'dependency_cache_sha256', 'project_manifest_sha256',
                'uv_lock_sha256'}
    if set(value) != required:
        raise RuntimeError('Unexpected Pipeline runner configuration fields')
    if value['runner_user'] != 'feelm-ci-test':
        raise RuntimeError('Unexpected Pipeline runner account')
    if value['docker_host'] != 'unix:///run/user/1001/docker.sock':
        raise RuntimeError('Unexpected rootless Docker socket')
    if not re.fullmatch(r'feelm-pipeline-ci:python3\.10-java21-pyspark4\.1\.3',
                        str(value['pipeline_image'])):
        raise RuntimeError('Unreviewed Pipeline image tag')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', str(value['pipeline_image_id'])):
        raise RuntimeError('Reviewed Pipeline image ID is required')
    if not re.fullmatch(r'/var/lib/feelm-ci222/pipeline-cache/[a-zA-Z0-9._-]+',
                        str(value['dependency_cache'])):
        raise RuntimeError('Pipeline dependency cache is outside the approved root')
    for field in ('dependency_cache_sha256', 'project_manifest_sha256', 'uv_lock_sha256'):
        if not re.fullmatch(r'[0-9a-f]{64}', str(value[field])):
            raise ValueError('Pipeline ' + field + ' digest is invalid')
    return value


def verify_source_identity(config, files):
    expected = {
        'pipeline/spark/pyproject.toml': config['project_manifest_sha256'],
        'pipeline/spark/uv.lock': config['uv_lock_sha256'],
    }
    for name, digest in expected.items():
        if name not in files or hashlib.sha256(files[name]).hexdigest() != digest:
            raise ValueError('Pipeline source dependency identity differs from reviewed config')


def verify_cache_identity(config):
    return cache_policy.verify(
        Path(config['dependency_cache']), config['dependency_cache_sha256'])


def docker_command(config, *arguments):
    return ['runuser', '--user', config['runner_user'], '--', 'env', '-i',
            'HOME=/home/' + config['runner_user'], 'PATH=/usr/bin:/bin',
            'DOCKER_HOST=' + config['docker_host'], '/usr/bin/docker', *arguments]


def cleanup_container(config, container):
    if not re.fullmatch(r'ci352-pipeline-[a-f0-9]{12}', container):
        raise ValueError('Invalid Pipeline container identity')
    removed = subprocess.run(docker_command(config, 'rm', '--force', container),
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    if removed.returncode == 0:
        return 0
    remaining = subprocess.run(docker_command(config, 'container', 'inspect', container),
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    daemon = subprocess.run(docker_command(config, 'info', '--format', '{{.ServerVersion}}'),
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    if remaining.returncode == 0 or daemon.returncode != 0:
        raise RuntimeError('Pipeline container cleanup could not be verified')
    return 0


def write_container_archive(source, output):
    root = Path(source) / 'pipeline' / 'spark'

    def normalized(info):
        if info.isdir():
            info.mode = 0o755
        elif info.isfile():
            info.mode = 0o644
        else:
            raise RuntimeError('Unexpected Pipeline staging entry')
        info.uid = 0
        info.gid = 0
        info.uname = ''
        info.gname = ''
        info.mtime = 0
        return info

    with tarfile.open(fileobj=output, mode='w') as archive:
        for child in sorted(root.iterdir(), key=lambda path: path.name):
            archive.add(child, arcname=child.name, recursive=True, filter=normalized)


def workload(config, source, group, container):
    source = Path(source).resolve(strict=True)
    command_policy = fixed_command(group)
    if not re.fullmatch(r'ci352-pipeline-[a-f0-9]{12}', container):
        raise ValueError('Invalid Pipeline container identity')
    inspect = subprocess.run(docker_command(config, 'image', 'inspect', config['pipeline_image'],
                             '--format', '{{.Id}}'), capture_output=True, text=True,
                             timeout=30, check=True)
    if inspect.stdout.strip() != config['pipeline_image_id']:
        raise RuntimeError('Rootless Pipeline image identity differs from reviewed configuration')
    command = docker_command(
        config, 'run', '--rm', '--interactive', '--name', container, '--pull=never',
        '--user', '0:0',
        '--network=none', '--read-only', '--cap-drop=ALL',
        '--security-opt=no-new-privileges', '--memory', '6g', '--cpus', '4',
        # The full Spark suite peaks above 1,000 JVM/Python tasks before old
        # sessions finish releasing their workers. Keep headroom below the
        # owner unit's TasksMax=2048 while preventing native-thread failure.
        '--pids-limit', '1536',
        '--mount', 'type=volume,destination=/work,volume-nocopy',
        '--mount', 'type=volume,destination=/uv-cache-work,volume-nocopy',
        '--tmpfs', '/tmp:rw,exec,nodev,nosuid,size=1g',
        '--volume', config['dependency_cache'] + ':/uv-cache-source:ro',
        '--workdir', '/work', '--env', 'CI=true', '--env', 'HOME=/tmp',
        '--env', 'UV_CACHE_DIR=/uv-cache-work', '--env', 'UV_LINK_MODE=copy',
        '--env', 'PYSPARK_PYTHON=python',
        '--env', 'SPARK_LOCAL_IP=127.0.0.1', '--env', 'SPARK_LOCAL_DIRS=/work/.spark-local',
        config['pipeline_image_id'], 'sh', '-c', 'tar -xf - -C /work && ' + command_policy)
    try:
        with tempfile.TemporaryFile() as packed:
            write_container_archive(source, packed)
            packed.seek(0)
            return subprocess.run(command, stdin=packed, timeout=3500).returncode
    finally:
        cleanup_container(config, container)


def unit_command(config, source, group, unit, container):
    fixed_command(group)
    if not re.fullmatch(r'ci352-owner-[a-f0-9]{12}\.service', unit):
        raise ValueError('Invalid Pipeline unit identity')
    return [
        'systemd-run', '--unit=' + unit, '--service-type=exec', '--wait', '--pipe', '--collect',
        '--property=CPUQuota=400%', '--property=MemoryHigh=6G', '--property=MemoryMax=7G',
        '--property=TasksMax=1280', '--property=RuntimeMaxSec=3600',
        '--property=TimeoutStopSec=20', '--property=KillMode=control-group',
        '--property=NoNewPrivileges=yes', '--property=PrivateTmp=yes',
        '--property=ProtectSystem=strict', '--property=ProtectHome=read-only',
        '--property=RestrictSUIDSGID=yes',
        '--property=ExecStopPost=/usr/bin/python3 -I -B ' + str(INSTALLED)
        + ' cleanup --container=' + container,
        '/usr/bin/python3', '-I', '-B', str(INSTALLED), 'workload',
        '--source=' + str(source), '--group=' + group, '--container=' + container,
    ]


def stop_unit(unit):
    if not re.fullmatch(r'ci352-owner-[a-f0-9]{12}\.service', unit):
        raise ValueError('Refusing unrelated Pipeline unit')
    subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=30)


def start(payload, digest, group, on_started=None, config_path=CONFIG):
    if pwd is None or not sys.platform.startswith('linux'):
        raise RuntimeError('Pipeline MR execution requires Linux')
    fixed_command(group)
    config = read_config(config_path)
    files, _ = boundary.source_plan(payload, digest)
    verify_source_identity(config, files)
    verify_cache_identity(config)
    account = pwd.getpwnam(config['runner_user'])
    if account.pw_uid != 1001 or account.pw_gid != 1001:
        raise RuntimeError('Reviewed Pipeline runner account identity differs')
    runtime = RUNTIME_ROOT.lstat()
    if not stat.S_ISDIR(runtime.st_mode) or runtime.st_uid != 0 or runtime.st_mode & 0o022:
        raise RuntimeError('Trusted host-visible runtime directory required')
    token = uuid.uuid4().hex[:12]
    unit = 'ci352-owner-' + token + '.service'
    container = 'ci352-pipeline-' + token
    attempt = Path(tempfile.mkdtemp(prefix='ci352-pipeline-', dir=RUNTIME_ROOT))
    staging = attempt / 'source'
    staging.mkdir(mode=0o700)
    log = attempt / 'output.log'
    process = None
    try:
        boundary.install_snapshot(payload, digest, staging)
        attempt.chmod(0o711)
        if on_started is not None:
            on_started(staging, {'owner': unit})
        process = bounded.CappedProcess(
            unit_command(config, staging, group, unit, container), log)
        try:
            code = process.wait(3630)
        except (subprocess.TimeoutExpired, RuntimeError) as exc:
            stop_unit(unit)
            cleanup_error = None
            try:
                process.reap(30)
            except (subprocess.SubprocessError, RuntimeError) as error:
                cleanup_error = error
            if log.exists():
                print(log.read_text(errors='replace')[-bounded.LIMIT:], end='')
            print('PIPELINE_MR_SUPERVISOR_ERROR: ' + type(exc).__name__ + ': ' + str(exc),
                  file=sys.stderr)
            if cleanup_error is not None:
                print('PIPELINE_MR_SUPERVISOR_CLEANUP_ERROR: ' + type(cleanup_error).__name__,
                      file=sys.stderr)
                raise cleanup_error from exc
            raise
        output = log.read_text(errors='replace')
        print(output[-bounded.LIMIT:], end='')
        if code:
            return 2
        print('PIPELINE_MR_TEST_PASSED: group=' + group
              + ' fixed offline Spark policy; no artifacts exported')
        return 0
    finally:
        stop_unit(unit)
        if process is not None and process.child.poll() is None:
            process.reap(30)
        shutil.rmtree(attempt, ignore_errors=True)


def main():
    if sys.argv[1:] and sys.argv[1] in ('workload', 'cleanup'):
        action = sys.argv[1]
        source = next((arg.split('=', 1)[1] for arg in sys.argv[2:]
                       if arg.startswith('--source=')), None)
        group = next((arg.split('=', 1)[1] for arg in sys.argv[2:]
                      if arg.startswith('--group=')), None)
        container = next((arg.split('=', 1)[1] for arg in sys.argv[2:]
                          if arg.startswith('--container=')), None)
        if container is None or (action == 'workload' and (source is None or group is None)):
            return 64
        config = read_config()
        if action == 'cleanup':
            return cleanup_container(config, container)
        return workload(config, source, group, container)
    print('PLAN: validated pipeline/spark archive, fixed offline container, bounded resources/output')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
