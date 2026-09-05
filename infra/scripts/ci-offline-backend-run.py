#!/usr/bin/env python3
"""Run the reviewed prepared snapshot once against offline Docker. Not an MR runner."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prep = load('offline_prep', 'ci-backend-offline-prepare.py')
port = load('offline_port', 'ci-offline-port-check.py')
pilot = prep.pilot
network = pilot.network


def regular_files(root):
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Expected real input directory')
    files = {}
    for path in root.rglob('*'):
        mode = path.lstat().st_mode
        if stat.S_ISDIR(mode):
            continue
        if not stat.S_ISREG(mode):
            raise ValueError('Input symlinks/special files refused')
        files[path.relative_to(root).as_posix()] = path
        if len(files) > 110000:
            raise ValueError('Too many input files')
    return files


def validate_source(run_dir, data):
    if hashlib.sha256(data).hexdigest() != prep.ARCHIVE_SHA:
        raise ValueError('Reviewed source archive hash mismatch')
    archive, entries = pilot.archive_entries(data)
    actual = regular_files(run_dir / 'backend')
    with archive:
        expected = {item.name.removeprefix('backend/'): item for item in entries if item.isfile()}
        if actual.keys() != expected.keys():
            raise ValueError('Prepared backend has missing/extra files or previous build output')
        for name, item in expected.items():
            with archive.extractfile(item) as stream:
                if actual[name].read_bytes() != stream.read():
                    raise ValueError('Prepared source differs from reviewed archive')


def inspect_inputs(run_dir):
    if (run_dir.is_symlink() or run_dir.resolve(strict=True) != run_dir
            or run_dir.parent != prep.OUTPUT or not re.fullmatch('[0-9a-f]{32}', run_dir.name)):
        raise ValueError('Expected prepared UUID directory under offline-inputs')
    if {p.name for p in run_dir.iterdir()} != {'backend', 'home', 'support', 'manifest.json'}:
        raise ValueError('Unexpected files or previous run; prepare a fresh input directory')
    regular_files(run_dir)  # Reject links before opening manifest or descendants.
    manifest = json.loads((run_dir / 'manifest.json').read_text())
    if (manifest.get('revision') != prep.REVISION or manifest.get('archive_sha256') != prep.ARCHIVE_SHA
            or manifest.get('tests_executed') is not False):
        raise ValueError('Unexpected preparation manifest')
    archive = Path(f'/home/feelm-ci-test/pilot-222/backend-{prep.REVISION}.tar')
    with archive.open('rb') as stream:
        data = stream.read(pilot.MAX_ARCHIVE + 1)
    if len(data) > pilot.MAX_ARCHIVE:
        raise ValueError('Archive too large')
    validate_source(run_dir, data)
    support = regular_files(run_dir / 'support')
    if support.keys() != set(pilot.SUPPORT_FILES):
        raise ValueError('Unexpected support files')
    for name, path in support.items():
        if path.read_bytes() != (HERE / 'rootless-backend' / name).read_bytes():
            raise ValueError('Prepared support differs from installed reviewed helper')
    cache = run_dir / 'home/.gradle'
    files, _ = prep.cache_inventory(cache)
    if files != manifest.get('cache'):
        raise ValueError('Cache hash mismatch')
    # Reject unrecorded home/init scripts, credentials, or excluded lock files.
    if regular_files(run_dir / 'home').keys() != {'.gradle/' + name for name in files}:
        raise ValueError('Unexpected home/cache file')
    if shutil.disk_usage(run_dir).free < 5 * 1024**3:
        raise ValueError('Need 5 GiB free')
    return manifest


def inspect_images(prefix, manifest):
    port.validate_info(json.loads(network.execute(prefix, 'info', '--format', '{{json .}}').stdout))
    if network.execute(prefix, 'ps', '-aq').stdout.strip():
        raise ValueError('Offline Docker must be empty; no concurrent tests')
    for tag, digest in prep.IMAGES.items():
        expected = manifest['images'][tag]
        actual = json.loads(network.execute(prefix, 'image', 'inspect', tag,
                                            '--format', '{{json .}}').stdout)
        if expected['digest'] != digest or any(actual[k] != expected[v]
                for k, v in [('Id', 'id'), ('Os', 'os'), ('Architecture', 'architecture')]):
            raise ValueError('Imported image mismatch; no pull fallback')


def environment(run_dir, identities):
    env = pilot.environment(run_dir, 1001, identities)
    socket = '/run/feelm-ci222-offline/docker.sock'
    env['DOCKER_HOST'] = 'unix://' + socket
    env['TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE'] = socket
    env['XDG_RUNTIME_DIR'] = '/run/feelm-ci222-offline'
    return env


CHILD = network.NAMESPACE_SETUP + r'''
import json, subprocess
verify_namespace_identity()
info = json.loads(subprocess.check_output(
    ['/usr/bin/docker', '--host', os.environ['DOCKER_HOST'], 'info', '--format', '{{json .}}'],
    text=True, timeout=30))
if ('name=rootless' not in info.get('SecurityOptions', []) or
        info.get('DockerRootDir') != '/home/feelm-ci-test/offline-docker-222/data/docker'):
    raise RuntimeError('Wrong Docker endpoint inside namespace')
print('OFFLINE_BACKEND_TARGET: PASS; no private DNS mount', flush=True)
raise SystemExit(subprocess.call(
    ['/bin/sh', './gradlew', 'clean', 'test', 'spotlessCheck', '--offline', '--no-daemon',
     '--max-workers=2', '--console=plain', '--init-script',
     os.environ['CI222_SUPPORT'] + '/pilot.init.gradle'], cwd=os.environ['CI222_BACKEND']))
'''


def run(run_dir):
    prefix = port.prefix()
    pilot.require_resource_slice(1001)
    manifest = inspect_inputs(run_dir)
    inspect_images(prefix, manifest)
    code = 2
    with network.namespace_target(offline=True) as target:
        command, fds, identities, _ = target
        # Exclusive single-use marker: never reuse old XML to claim a new successful run.
        with (run_dir / 'execution-started.json').open('x') as stream:
            json.dump({'revision': prep.REVISION, 'docker_root': port.ROOT}, stream)
        (run_dir / 'home/.docker').mkdir(mode=0o700)
        print('OFFLINE_RUN_DIR: ' + str(run_dir), flush=True)
        child = subprocess.Popen(command + ['--', '/usr/bin/python3', '-I', '-B', '-u', '-'],
                                 stdin=subprocess.PIPE, text=True, cwd='/', start_new_session=True,
                                 env=environment(run_dir, identities), pass_fds=tuple(fds.values()))
        try:
            child.communicate(CHILD, timeout=1800)
            code = child.returncode
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            print('OFFLINE_ABORTED: retained evidence; inspect Docker resources')
    print('GRADLE_EXIT=' + str(code))
    passed = pilot.report_summary(run_dir / 'backend')
    suites = [ET.parse(p).getroot() for p in (run_dir / 'backend/build/test-results/test').glob('TEST-*.xml')]
    complete = sum(int(s.get('tests', '0')) for s in suites) == 82 and all(
        int(s.get('skipped', '0')) == 0 for s in suites)
    remaining = pilot.wait_for_container_cleanup(prefix)
    result = 0 if code == 0 and passed and complete and not remaining else 2
    evidence = {'gradle_exit': code, 'report_pass': passed, 'all_82_no_skips': complete,
                'remaining_containers': remaining, 'exit': result}
    (run_dir / 'execution-result.json').write_text(json.dumps(evidence, indent=2))
    print('OFFLINE_RESULT: ' + json.dumps(evidence))
    print(f'OFFLINE_BACKEND_EXIT={result}; NOT_RUNNER_ISOLATION_APPROVAL')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'run'], nargs='?', default='plan')
    parser.add_argument('--prepared-dir')
    parser.add_argument('--confirm-reviewed-offline-pilot', action='store_true')
    args = parser.parse_args()
    if args.action == 'plan':
        print('PLAN: verify prepared source/cache/images; offline Docker only; full test + spotlessCheck '
              '--offline; 30 min timeout; 82 tests/no skips; single use; retain reports; no CI changes')
        return 0
    if not args.prepared_dir or not args.confirm_reviewed_offline_pilot:
        parser.error('run requires prepared-dir and confirm-reviewed-offline-pilot')
    return run(Path(args.prepared_dir))


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, subprocess.SubprocessError, ET.ParseError) as exc:
        print('OFFLINE_BACKEND_ERROR: ' + str(exc), file=sys.stderr)
        sys.exit(2)
