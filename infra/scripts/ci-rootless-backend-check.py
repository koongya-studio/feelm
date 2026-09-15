#!/usr/bin/env python3
"""Manual, reviewed backend compatibility pilot. Never attach this to untrusted MR jobs."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import time
import uuid
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('network_probe', HERE / 'ci-rootless-network-check.py')
network = importlib.util.module_from_spec(spec)
spec.loader.exec_module(network)
REQUIRED_SUITES = {'AuthContractTest', 'V1MigrationTest', 'AccountMigrationTest',
                   'ContentWatchMigrationTest', 'TasteRecommendationPartyMigrationTest'}
REQUIRED_CLASSES = {'com.feelm.backend.domain.auth.AuthContractTest'} | {
    'com.feelm.backend.migration.' + name for name in REQUIRED_SUITES - {'AuthContractTest'}}
SUPPORT_FILES = ('LoopbackContainers.java', 'pilot.init.gradle',
                 'META-INF/services/org.testcontainers.core.CreateContainerCmdModifier')
MAX_ARCHIVE = 64 * 1024 * 1024


def archive_entries(data):
    archive = tarfile.open(fileobj=io.BytesIO(data), mode='r:')
    entries, total, seen = [], 0, set()
    try:
        for item in archive:
            path = PurePosixPath(item.name)
            if (path.is_absolute() or '..' in path.parts or '\\' in item.name
                    or not path.parts or path.parts[0] != 'backend'
                    or not (item.isdir() or item.isfile()) or path in seen):
                raise ValueError('Unsafe or duplicate archive entry')
            if any(p in {'.git', '.gradle', 'build', '.ssh', '.docker'} for p in path.parts):
                raise ValueError('Archive includes local state')
            if any(p.startswith('.env') and p != '.env.example' for p in path.parts):
                raise ValueError('Archive includes environment files')
            total += item.size
            if total > 128 * 1024 * 1024 or len(entries) >= 10000:
                raise ValueError('Archive exceeds pilot limits')
            entries.append(item)
            seen.add(path)
        for required in ('backend/gradlew', 'backend/build.gradle', 'backend/settings.gradle',
                         'backend/gradle/wrapper/gradle-wrapper.jar'):
            if PurePosixPath(required) not in seen:
                raise ValueError('Incomplete backend archive')
        return archive, entries
    except BaseException:
        archive.close()
        raise


def extract_snapshot(data, destination):
    archive, entries = archive_entries(data)
    with archive:
        for item in entries:
            path = destination.joinpath(*PurePosixPath(item.name).parts)
            if item.isdir():
                path.mkdir(parents=True, exist_ok=True, mode=0o700)
            else:
                path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                with archive.extractfile(item) as source, path.open('xb') as target:
                    shutil.copyfileobj(source, target)
                path.chmod(0o700 if item.name == 'backend/gradlew' else 0o600)


def require_resource_slice(uid):
    prefix = f'/user.slice/user-{uid}.slice/'
    if not any(line.startswith('0::' + prefix) for line in Path('/proc/self/cgroup').read_text().splitlines()):
        raise ValueError('Launch with systemd-run --user --scope under the test user slice')
    base = Path(f'/sys/fs/cgroup/user.slice/user-{uid}.slice')
    for name, maximum in [('memory.max', 6 * 1024**3), ('memory.high', 5 * 1024**3), ('pids.max', 2048)]:
        value = (base / name).read_text().strip()
        if not value.isdigit() or not 0 < int(value) <= maximum:
            raise ValueError('Required test-user resource limits are absent')
    quota, period = (base / 'cpu.max').read_text().split()
    if not quota.isdigit() or not 0 < int(quota) <= 2 * int(period):
        raise ValueError('Required CPU quota is absent')


def report_summary(backend):
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    observed, rejected = set(), set()
    consistent = True
    for path in (backend / 'build/test-results/test').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        values = {key: int(suite.get(key, '0')) for key in totals}
        cases = suite.findall('testcase')
        if suite.tag != 'testsuite' or any(v < 0 for v in values.values()) or values['tests'] != len(cases):
            consistent = False
        for key, value in values.items():
            totals[key] += value
        for case in cases:
            name = case.get('classname', '')
            observed.add(name)
            if (any(values[k] for k in ('failures', 'errors', 'skipped'))
                    or any(case.find(k) is not None for k in ('failure', 'error', 'skipped'))):
                rejected.add(name)
            if any(case.find(k) is not None for k in ('failure', 'error')):
                consistent = False
    print('TEST_REPORT_SUMMARY: ' + json.dumps(totals))
    missing = sorted(REQUIRED_CLASSES - (observed - rejected))
    print('REQUIRED_CONTAINER_SUITES_NOT_PASSED: ' + json.dumps(missing))
    print('REPORT_STRUCTURE_VALID: ' + str(consistent))
    return consistent and totals['tests'] > 0 and not totals['failures'] and not totals['errors'] and not missing


def wait_for_container_cleanup(prefix, timeout=30):
    deadline = time.monotonic() + timeout
    while True:
        remaining = network.execute(prefix, 'ps', '-aq').stdout.split()
        delay = deadline - time.monotonic()
        if not remaining or delay <= 0:
            return remaining
        time.sleep(min(2, delay))


def recheck_report(run_dir):
    root = Path('/home/feelm-ci-test/backend-checks').resolve(strict=True)
    path = Path(run_dir).resolve(strict=True)
    if path.parent != root or not re.fullmatch('[0-9a-f]{32}', path.name):
        raise ValueError('Expected an existing UUID backend run directory')
    print('REPORT_RUN_DIR: ' + str(path))
    result = 0 if report_summary(path / 'backend') else 2
    print(f'REPORT_RECHECK_EXIT={result}; EXISTING_XML_ONLY; NOT_RUNNER_ISOLATION_APPROVAL')
    print('NO_TESTS_EXECUTED: original Gradle exit, cleanup and guard evidence remain separate')
    return result


def environment(run_dir, uid, identities):
    home = str(run_dir / 'home')
    socket = f'/run/user/{uid}/docker.sock'
    env = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'HOME': home,
           'XDG_RUNTIME_DIR': f'/run/user/{uid}', 'DOCKER_HOST': 'unix://' + socket,
           'DOCKER_CONFIG': home + '/.docker', 'GRADLE_USER_HOME': home + '/.gradle',
           'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE': socket,
           'TESTCONTAINERS_HOST_OVERRIDE': '127.0.0.1', 'TESTCONTAINERS_RYUK_DISABLED': 'false',
           'TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED': 'false', 'TESTCONTAINERS_REUSE_ENABLE': 'false',
           'JAVA_TOOL_OPTIONS': '-Duser.home=' + home + ' -XX:ActiveProcessorCount=2',
           'CI222_SUPPORT': str(run_dir / 'support'), 'CI222_BACKEND': str(run_dir / 'backend'),
           'PROBE_OUTER_UID': str(uid)}
    env.update({'PROBE_NS_' + key: str(value) for key, value in identities.items()})
    return env


BACKEND_CHILD = network.NAMESPACE_SETUP + r'''
import json, subprocess, sys
verify_namespace_identity()
with private_dns():
    prefix = ['/usr/bin/docker', '--host', os.environ['DOCKER_HOST']]
    info = json.loads(subprocess.check_output(prefix + ['info', '--format', '{{json .}}'], text=True))
    if ('name=rootless' not in info.get('SecurityOptions', [])
            or info.get('DockerRootDir') != '/home/feelm-ci-test/.local/share/docker'):
        raise RuntimeError('Unexpected Docker endpoint inside namespace')
    print('BACKEND_DOCKER_TARGET: rootless verified', flush=True)
    code = subprocess.call(['/bin/sh', './gradlew', 'clean', 'test', 'spotlessCheck', '--no-daemon',
                            '--max-workers=2', '--console=plain', '--init-script',
                            os.environ['CI222_SUPPORT'] + '/pilot.init.gradle'],
                           cwd=os.environ['CI222_BACKEND'])
raise SystemExit(code)
'''


def run(args):
    prefix = network.docker_prefix()
    uid = os.geteuid()
    require_resource_slice(uid)
    if network.execute(prefix, 'ps', '-aq').stdout.strip():
        raise ValueError('Test Docker must have no containers before this exclusive pilot')
    info = json.loads(network.execute(prefix, 'info', '--format', '{{json .}}').stdout)
    if ('name=rootless' not in info.get('SecurityOptions', [])
            or info.get('DockerRootDir') != '/home/feelm-ci-test/.local/share/docker'):
        raise ValueError('Unexpected Docker endpoint')
    archive_path = Path(args.archive)
    if archive_path.stat().st_size > MAX_ARCHIVE:
        raise ValueError('Archive too large')
    with archive_path.open('rb') as stream:
        data = stream.read(MAX_ARCHIVE + 1)
    if len(data) > MAX_ARCHIVE:
        raise ValueError('Archive grew beyond pilot limit')
    if hashlib.sha256(data).hexdigest() != args.sha256:
        raise ValueError('Archive SHA256 mismatch')
    archive, _ = archive_entries(data)
    archive.close()
    root = Path('/home/feelm-ci-test/backend-checks')
    if root.is_symlink():
        raise ValueError('Unexpected run root symlink')
    if shutil.disk_usage(root.parent).free < 5 * 1024**3:
        raise ValueError('Less than 5 GiB free; do not download dependencies')
    root.mkdir(mode=0o700, exist_ok=True)
    run_dir = root / uuid.uuid4().hex
    run_dir.mkdir(mode=0o700)
    extract_snapshot(data, run_dir)
    (run_dir / 'home/.docker').mkdir(parents=True, mode=0o700)
    for relative in SUPPORT_FILES:
        target = run_dir / 'support' / relative
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        shutil.copyfile(HERE / 'rootless-backend' / relative, target)
    print('BACKEND_RUN_DIR: ' + str(run_dir), flush=True)
    print('OPERATOR_REVIEWED_REVISION: ' + args.revision, flush=True)
    print('SOURCE_ARCHIVE_SHA256: ' + args.sha256, flush=True)
    code = 2
    with network.namespace_target() as target:
        command, fds, identities, _ = target
        # stdout remains live. Do not retain all Gradle output in memory.
        child = subprocess.Popen(command + ['--', '/usr/bin/python3', '-I', '-B', '-u', '-'],
                                 stdin=subprocess.PIPE, text=True, cwd='/', start_new_session=True,
                                 env=environment(run_dir, uid, identities), pass_fds=tuple(fds.values()))
        try:
            child.communicate(BACKEND_CHILD, timeout=1800)
            code = child.returncode
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            print('BACKEND_ABORTED: inspect remaining test resources; no automatic prune')
            code = 2
    passed = report_summary(run_dir / 'backend')
    remaining = wait_for_container_cleanup(prefix)
    print('REMAINING_TEST_DOCKER_CONTAINER_IDS: ' + json.dumps(remaining))
    print('RETAINED: source snapshot, dependency cache and test reports in BACKEND_RUN_DIR')
    return 0 if code == 0 and passed and not remaining else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'run', 'report'], nargs='?', default='plan')
    parser.add_argument('--run-dir')
    parser.add_argument('--archive')
    parser.add_argument('--sha256')
    parser.add_argument('--revision')
    parser.add_argument('--confirm-reviewed-code', action='store_true')
    parser.add_argument('--confirm-guard-active', action='store_true')
    args = parser.parse_args()
    if args.action == 'plan':
        print('PLAN: reviewed archive only; rootless Docker + private DNS; full backend tests + spotlessCheck; '
              'loopback-only Testcontainers; fresh home/cache; 30 minute timeout; retain reports; no CI changes')
        return 0
    if args.action == 'report':
        if not args.run_dir:
            parser.error('report requires --run-dir; reads saved XML only')
        return recheck_report(args.run_dir)
    if (not args.archive or not re.fullmatch('[0-9a-f]{64}', args.sha256 or '')
            or not re.fullmatch('[0-9a-f]{40}', args.revision or '')
            or not args.confirm_reviewed_code or not args.confirm_guard_active):
        parser.error('run requires archive, SHA256, full reviewed revision and both confirmations')
    result = run(args)
    print(f'BACKEND_PILOT_EXIT={result}; NOT_RUNNER_ISOLATION_APPROVAL')
    return result


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError, tarfile.TarError, ET.ParseError) as exc:
        print('BACKEND_PILOT_ERROR: ' + type(exc).__name__, file=sys.stderr)
        sys.exit(2)
