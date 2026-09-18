#!/usr/bin/env python3
"""Reviewed, single-job server pilot. No Runner registration or host Docker writes."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid

HERE = Path(__file__).resolve().parent


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


manager = module('server_manager', 'ci-local-manager-check.py')
backend = module('server_backend', 'ci-local-backend-runtime.py')
transfer = module('server_transfer', 'ci-local-backend-transfer.py')
job_io = module('server_job_io', 'ci-job-io.py')
lifecycle = module('server_job_lifecycle', 'ci-job-lifecycle.py')
bounded_output = module('server_bounded_output', 'ci-bounded-output.py')
DOCKER_SHA = '803d433f226db4776e1768fd319fc6c6e4935a456acf84fcc0080818b854bc8f'
DOCKER_URL = 'https://download.docker.com/linux/static/stable/x86_64/docker-29.7.2.tgz'
IMAGES = backend.inputs.IMAGE_ARCHIVE_PATH
BASE_PATTERN = r'/var/tmp/ci222-server-[a-z0-9_]+'
SYSFS_WAIT = '''#!/bin/sh
set -eu
mkdir -p /run/ci222-sysfs
echo WAITING > /sysfs-waiting
for wait_step in $(seq 1 60); do
  test -r /run/ci222-sysfs-ready && break
  sleep 1
done
test -r /run/ci222-sysfs-ready
echo PRIVATE_SYSFS_GATE_RELEASED
'''


def run(command, **kwargs):
    return subprocess.run(command, check=True, timeout=kwargs.pop('timeout', 120), **kwargs)


def check_base(base):
    if not re.fullmatch(BASE_PATTERN, str(base)) or base.is_symlink():
        raise ValueError('Dedicated server pilot directory required')
    info = base.stat()
    if info.st_uid != 0 or info.st_mode & 0o077:
        raise ValueError('Pilot parent must be root-owned mode 700')
    if os.geteuid() != 0 or 'VERSION_ID="24.04"' not in Path('/etc/os-release').read_text():
        raise ValueError('Reviewed Ubuntu 24.04 root operator only')
    if 'microsoft' in Path('/proc/sys/kernel/osrelease').read_text().lower():
        raise ValueError('Server pilot is separate from WSL experiment')
    if not Path('/sys/fs/cgroup/cgroup.controllers').is_file():
        raise ValueError('Unified cgroup v2 required')
    manifest = json.loads((base / 'upload-manifest.json').read_text())
    for name, expected in manifest.items():
        relative = PurePosixPath(name)
        if relative.is_absolute() or '..' in relative.parts or '\\' in name:
            raise ValueError('Unsafe staged source')
        path = base / name
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('Untrusted staged source')
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Staged source changed: ' + name)


def copy_checked(source, target, expected, maximum):
    source = backend.inputs.real_path(source)
    info = source.stat()
    if not stat.S_ISREG(info.st_mode) or not 0 <= info.st_size <= maximum:
        raise ValueError('Invalid selected file size')
    if backend.inputs.prep.digest(source) != expected:
        raise ValueError('Selected source hash mismatch')
    target.parent.mkdir(parents=True, exist_ok=True)
    # Rehash the copied bytes too; a source changing during copying is rejected.
    with source.open('rb') as src, target.open('xb') as dst:
        shutil.copyfileobj(src, dst)
    target.chmod(0o600)
    if target.stat().st_size != info.st_size or backend.inputs.prep.digest(target) != expected:
        raise ValueError('Copied file changed')


def download(url, target, expected):
    if target.exists():
        raise ValueError('Download destination already exists')
    run(['curl', '--fail', '--silent', '--show-error', '--location', '--proto', '=https',
         '--proto-redir', '=https', '--connect-timeout', '10', '--max-time', '240',
         url, '--output', str(target)], timeout=250)
    backend.inputs.checked_file(target, expected, 512 * 1024**2)


def prepare(base, cache_manifest, cache_root, manifest_sha):
    if shutil.disk_usage(base).free < 12 * 1024**3:
        raise ValueError('At least 12 GiB free required')
    bundle = base / 'bundle'; bundle.mkdir()
    copy_checked(cache_manifest, bundle / 'cache-manifest.json', manifest_sha, 32 * 1024**2)
    metadata = json.loads((bundle / 'cache-manifest.json').read_text())
    revision = metadata.get('revision')
    source_sha = metadata.get('archive_sha256')
    if not re.fullmatch(r'[0-9a-f]{40}', revision or ''):
        raise ValueError('Cache manifest lacks a full reviewed revision')
    copy_checked(base / 'backend.tar', bundle / 'backend.tar', source_sha, 64 * 1024**2)
    entries = metadata['cache']
    if not 0 < len(entries) <= 100000 or sum(x['size'] for x in entries.values()) > 2 * 1024**3:
        raise ValueError('Cache manifest exceeds limits')
    for name, entry in entries.items():
        path = PurePosixPath(name)
        if (path.is_absolute() or '..' in path.parts or '\\' in name or str(path) != name
                or not (name.startswith('caches/') or name.startswith('wrapper/dists/'))
                or name.endswith('.lock')):
            raise ValueError('Unsafe selected cache path')
        copy_checked(cache_root / name, bundle / 'gradle-cache' / name, entry['sha256'], entry['size'])
    copy_checked(IMAGES, bundle / 'backend-images.tar', backend.inputs.IMAGE_ARCHIVE_SHA, 2 * 1024**3)
    download(transfer.JDK_URL, bundle / 'jdk.tar.gz', backend.JDK_SHA)
    download(DOCKER_URL, base / 'docker.tgz', DOCKER_SHA)
    package = base / 'package'; package.mkdir()
    version = run(['dpkg-query', '-W', '-f=${Version}', 'systemd'], capture_output=True, text=True).stdout
    if not re.fullmatch(r'255\.4-1ubuntu8\.[0-9]+', version):
        raise ValueError('Unexpected server systemd version')
    run(['apt-get', 'download', 'systemd-container=' + version], cwd=package)
    debs = list(package.glob('*.deb'))
    if len(debs) != 1: raise ValueError('Expected one matching nspawn package')
    run(['dpkg-deb', '-x', str(debs[0]), str(package / 'extracted')])
    nspawn = package / 'extracted/usr/bin/systemd-nspawn'
    version_text = run([str(nspawn), '--version'], capture_output=True, text=True).stdout
    backend.inputs.inspect_bundle(bundle, manifest_sha, backend.JDK_SHA,
                                  expected_revision=revision, source_sha=source_sha)
    tools = {'systemd': version, 'nspawn_version': version_text.splitlines()[0],
             'nspawn_sha': backend.inputs.prep.digest(nspawn),
             'docker_sha': DOCKER_SHA, 'source_revision': revision,
             'source_archive_sha256': source_sha,
             'dynamic_identity': metadata.get('dynamic_identity') is True,
             'cache_manifest_sha256': manifest_sha}
    (base / 'prepared.json').write_text(json.dumps(tools, indent=2))
    print('SERVER_PREPARATION: PASS; files only, no host packages installed', flush=True)


def service_context():
    group = Path('/proc/self/cgroup').read_text().strip()
    match = re.fullmatch(r'0::(/system.slice/(ci222-server-[a-f0-9]{12}\.service))/controller', group)
    if not match or not os.environ.get('INVOCATION_ID'):
        raise ValueError('Dedicated bounded transient service required')
    unit = match[2]
    properties_text = run(['systemctl', 'show', unit,
                                '-p', 'MemoryMax', '-p', 'TasksMax', '-p', 'CPUQuotaPerSecUSec',
                                '-p', 'RuntimeMaxUSec', '-p', 'Delegate', '-p', 'DelegateSubgroup', '-p', 'KillMode'],
                               capture_output=True, text=True).stdout
    properties = dict(line.split('=', 1) for line in properties_text.splitlines() if '=' in line)
    if (properties.get('MemoryMax') != str(6 * 1024**3) or properties.get('TasksMax') != '2048'
            or properties.get('CPUQuotaPerSecUSec') != '2s'
            or properties.get('RuntimeMaxUSec') != '40min'
            or properties.get('Delegate') != 'yes' or properties.get('DelegateSubgroup') != 'controller'
            or properties.get('KillMode') != 'control-group'):
        raise ValueError('Transient service limits differ: ' + str(properties))
    return match[1], unit


def build_root(base, attempt, action):
    root = attempt / 'root'
    for name in ('bin', 'usr/bin', 'etc', 'run', 'tmp', 'var', 'data', 'input', 'dev', 'proc', 'sys'):
        (root / name).mkdir(parents=True, exist_ok=True)
    backend.inputs.checked_file(base / 'docker.tgz', DOCKER_SHA, 512 * 1024**2)
    with tarfile.open(base / 'docker.tgz') as stream:
        for member in stream:
            if member.isdir(): continue
            if not member.isfile() or not re.fullmatch(r'docker/[a-zA-Z0-9-]+', member.name):
                raise ValueError('Unexpected static Docker entry')
            with stream.extractfile(member) as src, (root / 'bin' / Path(member.name).name).open('xb') as dst:
                shutil.copyfileobj(src, dst)
            (root / 'bin' / Path(member.name).name).chmod(0o755)
    manager.copy_binary('/usr/bin/busybox', root)
    shutil.copyfile('/usr/bin/busybox', root / 'bin/busybox')
    (root / 'bin/busybox').chmod(0o755)
    for name in ('sh', 'mkdir', 'seq', 'sleep', 'tail', 'cat', 'mount', 'umount', 'ip', 'modprobe'):
        (root / 'bin' / name).symlink_to('busybox')
    for name in ('/usr/sbin/iptables', '/usr/sbin/ip6tables'):
        manager.copy_binary(name, root)
    for source in Path('/usr/lib/x86_64-linux-gnu/xtables').glob('*.so'):
        manager.copy_binary(source, root)
    (root / 'etc/os-release').write_text('ID=ci222-fixture\nNAME=ci222-fixture\n')
    (root / 'etc/passwd').write_text('root:x:0:0:root:/root:/bin/sh\n')
    (root / 'etc/group').write_text('root:x:0:\ndocker:x:999:\n')
    script = SYSFS_WAIT + server_probe_script()
    if action in ('backend', 'backend-cancel', 'backend-timeout'):
        source_payload = source_sha = None
        source_archive = attempt / 'source.tar'
        if source_archive.exists():
            source_payload = source_archive.read_bytes()
            source_sha = (attempt / 'source.sha256').read_text().strip()
        prepared = json.loads((base / 'prepared.json').read_text())
        backend.prepare(base / 'bundle', root, manager.copy_binary,
                        source_installer=job_io.install_snapshot,
                        source_payload=source_payload, source_sha=source_sha,
                        manifest_sha=prepared['cache_manifest_sha256'],
                        expected_revision=prepared['source_revision'],
                        bundle_source_sha=prepared['source_archive_sha256'])
        if action != 'backend':
            (root / 'backend-check.sh').write_text(backend_lifecycle_payload())
        script += '\n/bin/sh /backend-check.sh\n'
    elif action in ('cancel', 'timeout'):
        script += '''
docker run -d --pull=never --network none --memory 64m --cpus 0.5 --pids-limit 32 \\
  --name ci222-lifecycle ci222-local-probe /bin/busybox sleep 120
echo READY > /lifecycle-ready
sleep 120
'''
    elif action == 'disk-full':
        script += '''
if /bin/busybox dd if=/dev/zero of=/data/disk-fill bs=1048576 2>&1 | /bin/busybox grep 'No space left on device'; then
  echo DISK_LIMIT_ENOSPC
else
  echo DISK_LIMIT_FAILED; exit 3
fi
'''
    elif action == 'log-full':
        script += '\n/bin/busybox yes CI222_LOG_LIMIT_FIXTURE\n'
    elif action == 'artifact-link':
        script += '''
mkdir -p /work/backend/build/test-results/test
/bin/busybox ln -s "$HOST_CANARY" /work/backend/build/test-results/test/TEST-Link.xml
echo ARTIFACT_LINK_FIXTURE_READY
'''
    (root / 'probe.sh').write_text(script)
    with tarfile.open(root / 'input/probe.tar', 'w') as archive:
        archive.add('/usr/bin/busybox', arcname='bin/busybox')
    return root


def backend_lifecycle_payload():
    original = '''/bin/sh ./gradlew clean test spotlessCheck --offline --no-daemon --max-workers=2 \\
  --console=plain --init-script /work/support/pilot.init.gradle || code=$?'''
    if backend.PAYLOAD.count(original) != 1: raise ValueError('Reviewed Gradle invocation changed')
    replacement = '''/bin/sh ./gradlew clean test spotlessCheck --offline --no-daemon --max-workers=2 \\
  --console=plain --init-script /work/support/pilot.init.gradle >/work/gradle.log 2>&1 &
gradle_pid=$!
while kill -0 "$gradle_pid" 2>/dev/null; do
  database=$(docker ps -q --filter ancestor=pgvector/pgvector:0.8.6-pg17-bookworm)
  if test -n "$database"; then
    printf '%s\\n' "$database" > /work/lifecycle-ready
  fi
  sleep 1
done
wait "$gradle_pid" || code=$?
cat /work/gradle.log'''
    return backend.PAYLOAD.replace(original, replacement)


def server_probe_script():
    # With readonly full sysfs, default privileged Docker asks for a writable
    # sysfs and must be refused. Also exercise actual privileged code with an
    # explicit readonly sysfs bind, rather than treating startup failure alone
    # as proof that host PID/network/filesystem boundaries hold.
    marker = 'docker run --rm --pull=never --privileged --pid host --network host --cgroupns host'
    if manager.SCRIPT.count(marker) != 1:
        raise ValueError('Reviewed privileged probe marker changed')
    denied = r'''
if docker run --rm --pull=never --privileged --network host ci222-local-probe \
  /bin/busybox true >/run/privileged-denied.log 2>&1; then
  echo WRITABLE_SYSFS_UNEXPECTED; exit 3
else
  test "$?" = 125
fi
/bin/busybox grep -F 'error mounting "sysfs"' /run/privileged-denied.log
/bin/busybox grep -F 'operation not permitted' /run/privileged-denied.log
echo DEFAULT_PRIVILEGED_WRITABLE_SYSFS_REJECTED
'''
    replacement = denied + marker + ' \\\n  --mount type=bind,src=/run/ci222-sysfs,dst=/sys,readonly'
    script = manager.SCRIPT.replace(marker, replacement)
    check = '    test "$(/bin/busybox readlink /proc/self/ns/pid)" != "$HOST_PID_NAMESPACE"'
    if script.count(check) != 1: raise ValueError('Reviewed namespace assertion changed')
    readonly = '''    if /bin/busybox mount -o remount,rw /sys; then
      echo SYSFS_REMOUNT_WRITABLE_UNEXPECTED; exit 3
    fi
'''
    return script.replace(check, readonly + check)


def command(nspawn, root, canary, host_socket, diagnose=False):
    result = [str(nspawn), '--quiet', '--register=no', '--keep-unit', '--settings=no', '--link-journal=no',
              '--private-users=pick', '--private-users-chown', '--private-network',
              '--system-call-filter=keyctl', '--capability=CAP_NET_ADMIN', '--directory=' + str(root),
              '--setenv=HOST_CANARY=' + str(canary), '--setenv=HOST_SOCKET=' + str(host_socket),
              '--setenv=DIAGNOSE_ONLY=' + ('1' if diagnose else '0')]
    for name, kind in (('PID', 'pid'), ('NET', 'net'), ('CGROUP', 'cgroup')):
        result.append('--setenv=HOST_' + name + '_NAMESPACE=' + os.readlink('/proc/self/ns/' + kind))
    return result + ['/bin/sh', '/probe.sh']


def validate_unit(unit):
    if not re.fullmatch(r'ci222-server-[a-f0-9]{12}\.service', unit):
        raise ValueError('Refusing unrelated unit')


def stop_unit(unit):
    validate_unit(unit)
    subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=30)
    verify_unit_stopped(unit)


def verify_unit_stopped(unit):
    validate_unit(unit)
    state = subprocess.run(['systemctl', 'is-active', unit], capture_output=True, text=True, timeout=10)
    if state.stdout.strip() not in ('inactive', 'unknown', 'failed'):
        raise RuntimeError('Pilot service still active')
    if Path('/sys/fs/cgroup/system.slice', unit).exists():
        raise RuntimeError('Pilot service cgroup remains')


def filesystem_usage(root):
    space = os.statvfs(root)
    block_size = space.f_frsize or space.f_bsize
    return {
        'bytes': {'total': space.f_blocks * block_size,
                  'used': (space.f_blocks - space.f_bfree) * block_size,
                  'free': space.f_bfree * block_size,
                  'available': space.f_bavail * block_size},
        'inodes': {'total': space.f_files, 'used': space.f_files - space.f_ffree,
                   'free': space.f_ffree, 'available': space.f_favail},
    }


def write_private_json(path, payload, **dump_options):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(payload, output, **dump_options)
        output.flush(); os.fsync(output.fileno())
    path.chmod(0o600)


def persist_filesystem_usage(attempt, peak, samples, sample_errors):
    final = filesystem_usage(attempt / 'root')
    if peak is None: peak = final
    usage = {'schema_version': 2, 'final': final, 'peak': peak,
             'samples': samples, 'sample_errors': sample_errors}
    path = attempt / 'filesystem-usage.json'
    write_private_json(path, usage, sort_keys=True)
    print('FILESYSTEM_USAGE=' + json.dumps(usage, sort_keys=True), flush=True)
    return usage


class BackendJobDriver:
    """Real server adapter for the fixed reviewed backend; NOT arbitrary MR input."""
    REQUIRED_CLASSES = frozenset({
        'com.feelm.backend.domain.auth.AuthContractTest',
        'com.feelm.backend.migration.AccountMigrationTest',
        'com.feelm.backend.migration.ContentWatchMigrationTest',
        'com.feelm.backend.migration.TasteRecommendationPartyMigrationTest',
        'com.feelm.backend.migration.V1MigrationTest',
    })

    def __init__(self, base, prepared, attempt, mode='backend', safety_lease=None):
        self.base, self.prepared, self.attempt = base, prepared, attempt
        self.mode = mode
        self.safety_lease = safety_lease
        self.ready_at = None
        self.build_unit = 'ci222-server-' + uuid.uuid4().hex[:12] + '.service'
        self.unit = 'ci222-server-' + uuid.uuid4().hex[:12] + '.service'
        self.owner = None
        if safety_lease is not None:
            self.build_unit = safety_lease['build']; self.unit = safety_lease['execution']
            self.owner = safety_lease['owner']
        self.child = None
        self.cleanup_confirmed = False
        self.filesystem_peak = None
        self.filesystem_samples = 0
        self.filesystem_sample_errors = 0

    def sample_filesystem_usage(self):
        if self.safety_lease is None: return
        try:
            current = filesystem_usage(self.attempt / 'root')
        except OSError:
            self.filesystem_sample_errors += 1
            return
        self.filesystem_samples += 1
        if self.filesystem_peak is None:
            self.filesystem_peak = current
            return
        for category in ('bytes', 'inodes'):
            peak = self.filesystem_peak[category]
            sample = current[category]
            peak['total'] = max(peak['total'], sample['total'])
            peak['used'] = max(peak['used'], sample['used'])
            peak['free'] = min(peak['free'], sample['free'])
            peak['available'] = min(peak['available'], sample['available'])

    def prepare(self, deadline):
        process = bounded_output.CappedProcess(
            service_command(self.build_unit, self.base, 'internal-build', self.attempt,
                            self.mode, self.prepared, self.owner), self.attempt / 'preparation.log')
        try:
            code = process.wait(timeout=min(600, max(0.1, deadline - time.monotonic())))
            if code != 0: raise RuntimeError('Preparation failed')
        finally:
            try: stop_unit(self.build_unit)
            finally: process.reap(timeout=10)

    def start(self, deadline):
        self.child = bounded_output.CappedProcess(
            service_command(self.unit, self.base, 'internal-launch', self.attempt,
                            self.mode, self.prepared, self.owner), self.attempt / 'execution.log')
        bootstrap_sysfs(self.base, self.attempt, self.unit, self.child)
        self.sample_filesystem_usage()

    def poll(self, deadline):
        self.sample_filesystem_usage()
        # Fixed fixture only: request timeout after an actual test DB is running.
        if self.mode == 'backend-timeout' and self.workload_ready():
            if self.ready_at is None: self.ready_at = time.monotonic()
            if time.monotonic() - self.ready_at >= 1: raise lifecycle.Abort('timeout')
        return self.child.poll()

    def workload_ready(self):
        # Root-owned fixed fixture writes this. Not a general MR cleanup proof.
        return (self.attempt / 'root/work/lifecycle-ready').is_file()

    def cancelled(self):
        return self.mode == 'backend-cancel' and self.workload_ready()

    def stop(self, deadline):
        # A failed preparation cleanup must not skip the execution unit.
        try:
            stop_unit(self.build_unit)
        finally:
            try:
                stop_unit(self.unit)
            finally:
                if self.child is not None:
                    self.child.reap(timeout=max(0.1, deadline - time.monotonic()))

    def verify_stopped(self, deadline):
        verify_unit_stopped(self.build_unit)
        verify_unit_stopped(self.unit)
        self.cleanup_confirmed = True

    def collect(self, process_exit, deadline):
        if not self.cleanup_confirmed: raise RuntimeError('Cleanup proof required before reports')
        # Recheck immediately before touching any workload-controlled path.
        verify_unit_stopped(self.unit)
        if self.safety_lease is not None:
            persist_filesystem_usage(self.attempt, self.filesystem_peak,
                                     self.filesystem_samples, self.filesystem_sample_errors)
        if self.mode in ('probe', 'disk-full'):
            # Reviewed fixtures only; not a report policy for general MR jobs.
            contents = (self.attempt / 'execution.log').read_bytes()
            marker = b'DISK_LIMIT_ENOSPC' if self.mode == 'disk-full' else b'PRIVILEGED_HOST_NAMESPACE_PROBE_PASSED'
            return {'passed': process_exit == 0 and marker in contents}
        destination = self.attempt / 'evidence'; destination.mkdir(mode=0o700)
        expected_tests = 90
        dynamic_source = self.safety_lease is not None and self.safety_lease.get('source_sha') is not None
        if not dynamic_source:
            dynamic_source = json.loads((self.prepared / 'prepared.json').read_text()).get(
                'dynamic_identity') is True
        if dynamic_source:
            expected_tests = None
        try:
            result = job_io.collect_reports(self.attempt / 'root', destination, process_exit,
                                            self.REQUIRED_CLASSES, expected_tests=expected_tests)
        except Exception as exc:
            # Bounded diagnostics, never arbitrary exception text from job data.
            (self.attempt / 'report-rejection.json').write_text(json.dumps(
                {'type': type(exc).__name__, 'errno': getattr(exc, 'errno', None)}))
            raise
        print('BOUNDED_REPORT_SUMMARY=' + json.dumps(result['summary']), flush=True)
        return result


def execute_backend_job(base, prepared, mode='backend', safety_attempt=None, safety_lease=None):
    check_base(prepared)
    if shutil.disk_usage(base).free < 8 * 1024**3: raise ValueError('Insufficient pilot disk headroom')
    lock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        lock.bind('\0feelm-ci222-server-manager-pilot')
        # A dead coordinator must not allow a new attempt beside its old unit.
        if list(Path('/sys/fs/cgroup/system.slice').glob('ci222-server-*.service')):
            raise RuntimeError('Previous pilot cgroup remains; operator recovery required')
        attempt = safety_attempt or Path(tempfile.mkdtemp(prefix='run-', dir=base))
        canary = attempt / 'host-canary'; canary.write_text('synthetic host canary')
        listener.bind(str(attempt / 'host-socket')); listener.listen(1)
        driver = BackendJobDriver(base, prepared, attempt, mode, safety_lease)
        print('SERVER_RUN_DIR=' + str(attempt), flush=True)
        print('BUILD_UNIT=' + driver.build_unit + '\nEXECUTION_UNIT=' + driver.unit, flush=True)
        state = lifecycle.Attempt()
        result = lifecycle.supervise(driver, state, 1900, cancelled=driver.cancelled)
        if mode in ('backend-cancel', 'backend-timeout'):
            expected = 'cancelled' if mode == 'backend-cancel' else 'timeout'
            result['fixture_passed'] = (driver.workload_ready() and result['phase'] == 'finished'
                                        and result['outcome'] == expected and driver.cleanup_confirmed)
        elif mode == 'log-full':
            result['fixture_passed'] = (result['phase'] == 'finished' and driver.cleanup_confirmed
                                        and driver.child is not None and driver.child.overflow.is_set()
                                        and (attempt / 'execution.log').stat().st_size == bounded_output.LIMIT)
        elif mode == 'artifact-link':
            result['fixture_passed'] = (result['phase'] == 'quarantined'
                                        and result['outcome'] == 'report_failure'
                                        and result['process_exit'] == 0 and driver.cleanup_confirmed
                                        and not (attempt / 'evidence/manifest.json').exists()
                                        and (attempt / 'report-rejection.json').is_file()
                                        and json.loads((attempt / 'report-rejection.json').read_text()).get('errno') == 40)
        if canary.read_text() != 'synthetic host canary':
            result['passed'] = False; result['outcome'] = 'host_canary_changed'
            if 'fixture_passed' in result: result['fixture_passed'] = False
        write_private_json(attempt / 'lifecycle.json', result, indent=2)
        print('SERVER_JOB_LIFECYCLE=' + json.dumps(result), flush=True)
        if safety_lease is not None and safety_lease.get('source_sha') is not None:
            print('VALIDATED_MR_SOURCE: dynamic test count; required suites and zero failures/skips enforced', flush=True)
        else:
            print('FIXED_SOURCE_ONLY: no GitLab registration or untrusted MR execution', flush=True)
        return 0 if result.get('fixture_passed', result['passed']) else 2
    finally:
        listener.close(); lock.close()


def service_command(unit, base, action, attempt, mode, prepared_base, owner=None):
    validate_unit(unit)
    dependency = []
    if owner is not None:
        if not re.fullmatch('ci222-owner-[a-f0-9]{12}\\.service', owner): raise ValueError('Invalid owner unit')
        dependency = ['--property=BindsTo=' + owner, '--property=After=' + owner]
    return ['systemd-run', '--unit=' + unit, '--service-type=exec', '--wait', '--pipe', *dependency,
            '--property=Delegate=yes', '--property=DelegateSubgroup=controller',
            '--property=CPUQuota=200%', '--property=MemoryHigh=5G', '--property=MemoryMax=6G',
            '--property=TasksMax=2048', '--property=RuntimeMaxSec=2400',
            '--property=TimeoutStopSec=15', '--property=KillMode=control-group',
            '/usr/bin/python3', '-I', '-B', str(base / 'scripts/ci-server-manager-pilot.py'),
            action, '--attempt=' + str(attempt), '--mode=' + mode,
            '--prepared-base=' + str(prepared_base), '--confirm-server-pilot']


def bootstrap_sysfs(base, attempt, unit, child):
    deadline = time.monotonic() + 45
    payload = Path('/sys/fs/cgroup/system.slice', unit, 'controller/payload')
    while not (attempt / 'root/sysfs-waiting').exists():
        if child.poll() is not None or time.monotonic() > deadline:
            raise RuntimeError('Pilot did not reach pre-workload sysfs gate')
        time.sleep(0.1)
    candidates = []
    for pid in (payload / 'cgroup.procs').read_text().split():
        try:
            status = (Path('/proc') / pid / 'status').read_text().splitlines()
            value = next(line.split()[1:] for line in status if line.startswith('NSpid:'))
            if value == [pid, '1']: candidates.append(pid)
        except FileNotFoundError:
            pass
    if len(candidates) != 1: raise ValueError('Expected one waiting pilot init')
    pid = candidates[0]
    result = subprocess.run(['/usr/bin/python3', '-I', '-B', str(base / 'scripts/ci-server-sysfs-bootstrap.py'),
                  '--attempt', str(attempt), '--unit', unit, '--pid', pid],
                 capture_output=True, text=True, timeout=15)
    (attempt / 'sysfs-bootstrap.log').write_text(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError('Sysfs bootstrap refused: ' + result.stderr[-4000:])
    if not result.stdout.startswith('PRIVATE_SYSFS_BOOTSTRAP: '):
        raise ValueError('Sysfs bootstrap did not verify its mount')
    # Only after the root helper has exited, release the fixed waiting shell.
    proc = Path('/proc') / pid
    if (proc / 'cgroup').read_text().strip() != '0::/system.slice/' + unit + '/controller/payload':
        raise ValueError('Pilot moved before gate release')
    fd_root = os.open(proc / 'root', os.O_RDONLY | os.O_DIRECTORY)
    try:
        if not os.path.samestat(os.fstat(fd_root), (attempt / 'root').stat()):
            raise ValueError('Pilot root changed before gate release')
        fd_ready = os.open('run/ci222-sysfs-ready', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                           0o444, dir_fd=fd_root)
        with os.fdopen(fd_ready, 'w') as output: output.write('READY\n')
    finally:
        os.close(fd_root)
    print(result.stdout.strip(), flush=True)


def internal(base, action, attempt, mode, prepared_base):
    group, _ = service_context()
    if (attempt is None or attempt.parent != base or not re.fullmatch(r'run-[a-z0-9_]+', attempt.name)
            or attempt.is_symlink() or attempt.stat().st_uid != 0 or attempt.stat().st_mode & 0o077):
        raise ValueError('Expected private attempt directory')
    check_base(prepared_base)
    if action == 'internal-build':
        build_root(prepared_base, attempt, mode)
        return 0
    prepared = json.loads((prepared_base / 'prepared.json').read_text())
    nspawn = prepared_base / 'package/extracted/usr/bin/systemd-nspawn'
    backend.inputs.checked_file(nspawn, prepared['nspawn_sha'], 16 * 1024**2)
    # Only this checked transient unit is delegated to us. Its main process is
    # already in /controller. Enable controllers at the EMPTY unit root, then
    # replace Python with nspawn so no orchestration process remains in its root.
    parent = Path('/sys/fs/cgroup' + group)
    if (parent / 'cgroup.procs').read_text().strip():
        raise ValueError('Delegated service parent must be empty')
    available = set((parent / 'cgroup.controllers').read_text().split())
    if not {'cpu', 'memory', 'pids'} <= available:
        raise ValueError('Required resource controllers unavailable')
    (parent / 'cgroup.subtree_control').write_text('+cpu +memory +pids')
    env = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C', 'SYSTEMD_NSPAWN_UNIFIED_HIERARCHY': '1'}
    args = command(nspawn, attempt / 'root', attempt / 'host-canary', attempt / 'host-socket', mode == 'diagnose')
    os.execve(nspawn, args, env)


def execute(base, action, prepared_base=None):
    # The small operator coordinator stays outside the payload service. Setup
    # and execution use sequential, independently bounded transient services.
    prepared_base = prepared_base or base
    check_base(prepared_base)
    if shutil.disk_usage(base).free < 8 * 1024**3: raise ValueError('Insufficient pilot disk headroom')
    lock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    lock.bind('\0feelm-ci222-server-manager-pilot')
    attempt = Path(tempfile.mkdtemp(prefix='run-', dir=base))
    print('SERVER_RUN_DIR=' + str(attempt), flush=True)
    root = attempt / 'root'
    canary = attempt / 'host-canary'; canary.write_text('synthetic host canary')
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    host_socket = attempt / 'host-socket'; listener.bind(str(host_socket)); listener.listen(1)
    log = attempt / 'execution.log'
    ready = False
    timed_out = False
    started = time.monotonic()
    build_unit = 'ci222-server-' + uuid.uuid4().hex[:12] + '.service'
    unit = 'ci222-server-' + uuid.uuid4().hex[:12] + '.service'
    print('BUILD_UNIT=' + build_unit + '\nEXECUTION_UNIT=' + unit, flush=True)
    try:
        with (attempt / 'preparation.log').open('w') as output:
            try:
                run(service_command(build_unit, base, 'internal-build', attempt, action, prepared_base),
                    stdout=output, stderr=subprocess.STDOUT, timeout=600)
            finally:
                stop_unit(build_unit)
        with log.open('w') as output:
            child = subprocess.Popen(service_command(unit, base, 'internal-launch', attempt, action, prepared_base),
                                     stdout=output, stderr=subprocess.STDOUT)
            bootstrap_sysfs(base, attempt, unit, child)
            if action == 'diagnose':
                time.sleep(2)
                parent = Path('/sys/fs/cgroup/system.slice', unit)
                for directory in [parent, *parent.rglob('*')]:
                    if not directory.is_dir(): continue
                    state = {'group': str(directory.relative_to(parent))}
                    for name in ('cgroup.controllers', 'cgroup.subtree_control', 'cgroup.type', 'cgroup.procs'):
                        if (directory / name).exists(): state[name] = (directory / name).read_text().strip()
                    print('HOST_CGROUP_DIAGNOSTIC=' + json.dumps(state), flush=True)
                    if directory.name == 'payload':
                        for pid in state.get('cgroup.procs', '').split():
                            proc = Path('/proc') / pid
                            try:
                                print('PAYLOAD_SECURITY_DIAGNOSTIC=' + json.dumps({
                                    'pid': pid, 'uid_map': (proc / 'uid_map').read_text().strip(),
                                    'apparmor': (proc / 'attr/current').read_text().strip(),
                                    'sysfs_mounts': [line for line in (proc / 'mountinfo').read_text().splitlines()
                                                     if ' - sysfs ' in line or ' /sys ' in line],
                                }), flush=True)
                            except FileNotFoundError:
                                pass
                child.wait(timeout=30)
            elif action in ('cancel', 'timeout'):
                deadline = time.monotonic() + 90
                while not (root / 'lifecycle-ready').exists():
                    if child.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError('Lifecycle workload not ready')
                    time.sleep(0.1)
                ready = True
                if action == 'timeout':
                    try: child.wait(timeout=1)
                    except subprocess.TimeoutExpired: timed_out = True
                    else: raise RuntimeError('Expected running workload timeout')
                stop_unit(unit)
                child.wait(timeout=15)
            else:
                try: child.wait(timeout=1900 if action == 'backend' else 120)
                except subprocess.TimeoutExpired:
                    timed_out = True
                    stop_unit(unit)
                    child.wait(timeout=15)
    finally:
        stop_unit(unit)
        listener.close(); lock.close()
    contents = log.read_text()
    print('\n'.join(contents.splitlines()[-90:]), flush=True)
    if canary.read_text() != 'synthetic host canary': raise RuntimeError('Host canary changed')
    passed = child.returncode == 0 and not timed_out
    if action in ('cancel', 'timeout'):
        passed = ready and (action != 'timeout' or timed_out)
    result = {'action': action, 'exit': child.returncode, 'timed_out': timed_out,
              'unit': unit, 'elapsed_seconds': round(time.monotonic() - started, 2),
              'host_canary_unchanged': True, 'payload_stopped': True, 'stdout': contents, 'stderr': ''}
    (attempt / 'result.json').write_text(json.dumps(result, indent=2))
    if action == 'backend':
        passed = backend.report(root, child.returncode) and passed
        backend.persist_evidence(root, attempt, attempt / 'evidence')
    result['passed'] = passed
    (attempt / 'summary.json').write_text(json.dumps({k: v for k, v in result.items() if k not in ('stdout', 'stderr')}, indent=2))
    print('SERVER_PILOT_' + action.upper() + ': ' + ('PASS' if passed else 'FAIL') + '; NOT_MR_APPROVAL', flush=True)
    return 0 if passed else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'prepare', 'diagnose', 'probe', 'backend', 'cancel', 'timeout',
                                         'backend-cancel', 'backend-timeout',
                                         'internal-build', 'internal-launch'])
    parser.add_argument('--confirm-server-pilot', action='store_true')
    parser.add_argument('--prepared-base', type=Path)
    parser.add_argument('--cache-manifest', type=Path)
    parser.add_argument('--cache-root', type=Path)
    parser.add_argument('--cache-manifest-sha256')
    parser.add_argument('--attempt', type=Path)
    parser.add_argument('--mode', choices=['diagnose', 'probe', 'backend', 'cancel', 'timeout',
                                          'backend-cancel', 'backend-timeout', 'disk-full', 'log-full', 'artifact-link'])
    args = parser.parse_args()
    if args.action == 'plan' or not args.confirm_server_pilot:
        print('PLAN: separate root-owned files and bounded transient service; reviewed source only; no Runner/CI/host Docker changes')
        return 0
    base = HERE.parent
    check_base(base)
    if args.action.startswith('internal-'):
        if not args.mode or not args.prepared_base: parser.error('Internal pilot arguments required')
        return internal(base, args.action, args.attempt, args.mode, args.prepared_base)
    if args.action == 'prepare':
        if args.prepared_base: parser.error('prepare cannot reuse existing inputs')
        if not args.cache_manifest or not args.cache_root or not re.fullmatch(
                r'[0-9a-f]{64}', args.cache_manifest_sha256 or ''):
            parser.error('prepare requires cache manifest, cache root and reviewed manifest SHA256')
        prepare(base, args.cache_manifest, args.cache_root, args.cache_manifest_sha256)
        return 0
    if args.action in ('backend', 'backend-cancel', 'backend-timeout'):
        return execute_backend_job(base, args.prepared_base or base, args.action)
    return execute(base, args.action, args.prepared_base)


if __name__ == '__main__':
    raise SystemExit(main())
