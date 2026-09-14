#!/usr/bin/env python3
"""Bounded backend-job safety supervisor. Installation and Runner setup are separate."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import time
import uuid

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('safety_pilot', HERE / 'ci-server-manager-pilot.py')
pilot = importlib.util.module_from_spec(spec); sys.modules[spec.name] = pilot; spec.loader.exec_module(pilot)
DISK_BYTES = 8 * 1024**3
POOL_BYTES = 48 * 1024**3
MODES = ('backend', 'backend-cancel', 'backend-timeout', 'probe', 'cancel', 'disk-full', 'log-full', 'artifact-link')


def atomic_json(path, value):
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex)
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(value, output, sort_keys=True); output.flush(); os.fsync(output.fileno())
    os.replace(temporary, path)
    parent = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(parent)
    finally: os.close(parent)


def validate_attempt(attempt):
    pilot.check_base(attempt.parent)
    if (not re.fullmatch(r'run-[a-z0-9_]+', attempt.name) or attempt.is_symlink()
            or attempt.stat().st_uid != 0 or attempt.stat().st_mode & 0o077):
        raise ValueError('Private safety attempt required')
    path = attempt / 'lease.json'
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_size > 4096:
        raise ValueError('Untrusted lease')
    lease = json.loads(path.read_text())
    legacy_fields = {'id', 'owner', 'build', 'execution', 'bytes', 'mode', 'prepared'}
    current_fields = legacy_fields | {'source_sha'}
    if set(lease) == legacy_fields:
        # Retained fixed-source evidence from the already validated pilot.
        lease['source_sha'] = None
    elif set(lease) != current_fields:
        raise ValueError('Unexpected lease fields')
    token = lease['id']
    if not re.fullmatch('[a-f0-9]{12}', token): raise ValueError('Invalid lease identity')
    if lease['owner'] != 'ci222-owner-' + token + '.service': raise ValueError('Wrong owner unit')
    if lease['build'] != 'ci222-server-' + token + '.service': raise ValueError('Wrong build unit')
    if lease['execution'] != 'ci222-server-' + hashlib.sha256(token.encode()).hexdigest()[:12] + '.service':
        raise ValueError('Wrong execution unit')
    if lease['bytes'] != DISK_BYTES or lease['mode'] not in MODES: raise ValueError('Invalid fixed policy')
    pilot.check_base(Path(lease['prepared']))
    source_sha = lease['source_sha']
    if source_sha is not None:
        if lease['mode'] != 'backend' or not re.fullmatch('[0-9a-f]{64}', source_sha):
            raise ValueError('Invalid dynamic source policy')
        source = attempt / 'source.tar'
        source_info = source.lstat()
        if (not stat.S_ISREG(source_info.st_mode) or source_info.st_uid != 0
                or source_info.st_mode & 0o077 or source_info.st_nlink != 1
                or source_info.st_size > pilot.job_io.MAX_ARCHIVE):
            raise ValueError('Untrusted dynamic source')
        pilot.job_io.source_plan(source.read_bytes(), source_sha)
        digest = attempt / 'source.sha256'
        digest_info = digest.lstat()
        if (not stat.S_ISREG(digest_info.st_mode) or digest_info.st_uid != 0
                or digest_info.st_mode & 0o077 or digest_info.st_nlink != 1
                or digest.read_text().strip() != source_sha):
            raise ValueError('Untrusted dynamic source digest')
    return lease


def inventory():
    leases = list(Path('/var/tmp').glob('ci222-server-*/run-*/lease.json'))
    if len(leases) > 1000: raise ValueError('Review retained safety attempts')
    total = 0
    for path in leases:
        lease = validate_attempt(path.parent)
        proof = path.parent / 'cleanup.json'
        if not proof.is_file() or proof.is_symlink(): raise RuntimeError('Unresolved previous safety attempt')
        data = json.loads(proof.read_text())
        if data != {'id': lease['id'], 'clean': True}: raise RuntimeError('Previous cleanup not proven')
        if os.path.ismount(path.parent / 'root'): raise RuntimeError('Previous filesystem still mounted')
        for name in ('owner', 'build', 'execution'):
            if (Path('/sys/fs/cgroup/system.slice') / lease[name]).exists():
                raise RuntimeError('Previous safety cgroup remains')
        image = path.parent / 'disk.img'
        if image.exists(): total += image.stat().st_size
    if total + DISK_BYTES > POOL_BYTES: raise RuntimeError('Retained disk budget reached; operator review required')


def create_disk(attempt, lease):
    image = attempt / 'disk.img'
    root = attempt / 'root'; root.mkdir(mode=0o700)
    fd = os.open(image, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        os.posix_fallocate(fd, 0, lease['bytes'])
        os.fsync(fd)
    finally: os.close(fd)
    # Only our newly created regular file, never a disk device or existing image.
    info = image.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size != DISK_BYTES:
        raise ValueError('Invalid fresh backing image')
    pilot.run(['mkfs.ext4', '-q', '-F', '-m', '0', '-N', '131072',
               '-E', 'lazy_itable_init=0,lazy_journal_init=0', str(image)], timeout=120)
    pilot.run(['mount', '-o', 'loop,nodev,nosuid', str(image), str(root)])
    pilot.run(['mount', '--make-private', str(root)])
    if not os.path.ismount(root) or root.stat().st_dev == attempt.stat().st_dev:
        raise RuntimeError('Dedicated disk mount not established')
    # nspawn must never be handed a view of the host rootfs if mounting failed.
    space = os.statvfs(root)
    if space.f_blocks * space.f_frsize > DISK_BYTES: raise RuntimeError('Filesystem capacity exceeds policy')


def loop_devices(image):
    response = pilot.run(['losetup', '--json', '--list', '--output', 'NAME,BACK-FILE'],
                         capture_output=True, text=True).stdout
    return [item['name'] for item in json.loads(response).get('loopdevices', [])
            if item.get('back-file') == str(image)]


def cleanup(attempt):
    lease = validate_attempt(attempt)
    # The ledger is outside the workload root and exists BEFORE any job unit.
    errors = []
    for key in ('build', 'execution'):
        try: pilot.stop_unit(lease[key])
        except Exception as exc: errors.append(type(exc).__name__)
    if errors: raise RuntimeError('Owned cgroup cleanup failed; lease remains blocked')
    root = attempt / 'root'
    if root.is_symlink(): raise RuntimeError('Unexpected root link')
    if os.path.ismount(root):
        # Verify the mount source belongs to our image before unmounting. Never force/lazy unmount.
        devices = loop_devices(attempt / 'disk.img')
        source = pilot.run(['findmnt', '--noheadings', '--raw', '--mountpoint', str(root),
                            '--output', 'SOURCE'], capture_output=True, text=True).stdout.strip()
        if source not in devices: raise RuntimeError('Mount source ownership mismatch')
        pilot.run(['umount', str(root)], timeout=30)
    deadline = time.monotonic() + 5
    while loop_devices(attempt / 'disk.img'):
        if time.monotonic() >= deadline: raise RuntimeError('Backing loop remains; manual review required')
        time.sleep(0.1)
    if os.path.ismount(root): raise RuntimeError('Filesystem remains mounted')
    image = attempt / 'disk.img'
    info = image.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077
            or info.st_nlink != 1 or info.st_size != DISK_BYTES):
        raise RuntimeError('Refusing unexpected backing image cleanup')
    image.unlink()
    parent = os.open(attempt, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(parent)
    finally: os.close(parent)
    atomic_json(attempt / 'cleanup.json', {'id': lease['id'], 'clean': True})
    print('SAFETY_CLEANUP: PASS; temporary job filesystem removed; bounded evidence retained', flush=True)


def owner_command(base, attempt, lease):
    script = base / 'scripts/ci-job-safety.py'
    return ['systemd-run', '--unit=' + lease['owner'], '--service-type=exec', '--wait', '--collect',
            '--property=StandardOutput=journal', '--property=StandardError=journal',
            '--property=RuntimeMaxSec=2100', '--property=TimeoutStopSec=90',
            '--property=KillMode=control-group', '--property=MemoryMax=512M',
            '--property=TasksMax=128', '--property=CPUQuota=100%',
            '--property=ExecStopPost=/usr/bin/python3 -I -B ' + str(script) + ' cleanup --attempt=' + str(attempt),
            '/usr/bin/python3', '-I', '-B', str(script), 'internal-run', '--attempt=' + str(attempt)]


def owner_context(lease):
    expected = '0::/system.slice/' + lease['owner']
    if Path('/proc/self/cgroup').read_text().strip() != expected or not os.environ.get('INVOCATION_ID'):
        raise RuntimeError('Root-owned supervisor service required')


def internal_run(attempt):
    lease = validate_attempt(attempt); owner_context(lease)
    create_disk(attempt, lease)
    return pilot.execute_backend_job(attempt.parent, Path(lease['prepared']), lease['mode'],
                                     safety_attempt=attempt, safety_lease=lease)


def write_private(path, payload):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as output:
        output.write(payload); output.flush(); os.fsync(output.fileno())


def publish_backend_timing(attempt, mode, required):
    if mode != 'backend': return
    path = attempt / 'evidence/manifest.json'
    try:
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077
                or info.st_nlink != 1 or info.st_size > 512 * 1024):
            raise ValueError('Untrusted timing evidence')
        manifest = json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(manifest, dict): raise ValueError('Invalid timing evidence manifest')
        timing = pilot.job_io.validated_public_timing(manifest.get('timing'))
    except (OSError, ValueError, UnicodeError, json.JSONDecodeError, TypeError):
        if required: raise RuntimeError('Validated backend timing evidence missing')
        return
    print('BACKEND_TEST_TIMING=' + json.dumps(
        timing, sort_keys=True, separators=(',', ':')), flush=True)


def start(base, prepared, mode, source_payload=None, source_sha=None, on_started=None):
    pilot.check_base(base); pilot.check_base(prepared)
    if (source_payload is None) != (source_sha is None):
        raise ValueError('Dynamic source payload and digest must be supplied together')
    if source_payload is not None:
        if mode != 'backend': raise ValueError('Dynamic source is backend-only')
        pilot.job_io.source_plan(source_payload, source_sha)
    # This admission lock lives in the front end until owner+ExecStopPost exit.
    lock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        lock.bind('\0feelm-ci222-safety-admission')
        inventory()
        if list(Path('/sys/fs/cgroup/system.slice').glob('ci222-server-*.service')):
            raise RuntimeError('Existing pilot cgroup; do not overlap')
        if shutil.disk_usage(base).free < DISK_BYTES + 20 * 1024**3:
            raise RuntimeError('Need 8 GiB reservation plus 20 GiB free headroom')
        attempt = Path(tempfile.mkdtemp(prefix='run-', dir=base))
        token = uuid.uuid4().hex[:12]
        lease = {'id': token, 'owner': 'ci222-owner-' + token + '.service',
                 'build': 'ci222-server-' + token + '.service',
                 'execution': 'ci222-server-' + hashlib.sha256(token.encode()).hexdigest()[:12] + '.service',
                 'bytes': DISK_BYTES, 'mode': mode, 'prepared': str(prepared),
                 'source_sha': source_sha}
        if source_payload is not None:
            write_private(attempt / 'source.tar', source_payload)
            write_private(attempt / 'source.sha256', (source_sha + '\n').encode())
        atomic_json(attempt / 'lease.json', lease)
        print('SAFETY_ATTEMPT=' + str(attempt) + '\nSAFETY_OWNER=' + lease['owner'], flush=True)
        if on_started is not None: on_started(attempt, lease)
        result = subprocess.run(owner_command(base, attempt, lease), timeout=2250)
        proof = attempt / 'cleanup.json'
        if not proof.is_file() or json.loads(proof.read_text()) != {'id': token, 'clean': True}:
            raise RuntimeError('Missing cleanup proof; new jobs blocked')
        publish_backend_timing(attempt, mode, required=result.returncode == 0)
        return result.returncode
    finally: lock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'run', 'internal-run', 'cleanup'], nargs='?', default='plan')
    parser.add_argument('--attempt', type=Path)
    parser.add_argument('--prepared-base', type=Path)
    parser.add_argument('--mode', choices=MODES, default='backend')
    parser.add_argument('--confirm-server-pilot', action='store_true')
    args = parser.parse_args()
    if args.action == 'plan' or (args.action == 'run' and not args.confirm_server_pilot):
        print('PLAN: fixed source; 8 GiB temporary image; 16 MiB logs; systemd owner cleanup; no Runner changes')
        return 0
    if args.action == 'run':
        if args.prepared_base is None: parser.error('prepared-base required')
        return start(HERE.parent, args.prepared_base, args.mode)
    if args.attempt is None: parser.error('attempt required')
    if args.action == 'cleanup': cleanup(args.attempt); return 0
    return internal_run(args.attempt)


if __name__ == '__main__': raise SystemExit(main())
