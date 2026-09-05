#!/usr/bin/env python3
"""Prepare reviewed offline inputs only. No Gradle execution or Docker network changes."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import uuid

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('backend_pilot', HERE / 'ci-rootless-backend-check.py')
pilot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pilot)
REVISION = '9631afd9c616e047abbdd0ad7bf8192231f7f35c'
ARCHIVE_SHA = 'b045a76f85d8373dec543a58ec1a72e8aacebe288d0856bbfe678db8d5c4d3b1'
RUNS = Path('/home/feelm-ci-test/backend-checks')
SOURCE_RUN = RUNS / 'ee737a8331104a619b767a40ae996129'
OUTPUT = Path('/home/feelm-ci-test/offline-inputs')
IMAGES = {
    'redis:8-alpine': 'redis@sha256:becdda6c7f4b3fb42e42fd7f120bbf5c54c4caaaf16f26da24e4563d2c1f0576',
    'pgvector/pgvector:0.8.6-pg17-bookworm': 'pgvector/pgvector@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f',
    'testcontainers/ryuk:0.14.0': 'testcontainers/ryuk@sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0',
}
MAX_CACHE = 2 * 1024**3
GRADLE_ARGS = ['./gradlew', 'clean', 'test', 'spotlessCheck', '--offline', '--no-daemon',
               '--max-workers=2', '--console=plain', '--init-script', '../support/pilot.init.gradle']


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        while chunk := source.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def cache_inventory(cache):
    files, size = {}, 0
    if cache.is_symlink() or not cache.is_dir():
        raise ValueError('Expected real Gradle cache directory')
    # Exclude user init scripts/properties, credentials, daemon state and native state.
    for selected in ('caches', 'wrapper/dists'):
        root = cache / selected
        if not root.is_dir() or root.is_symlink() or root.parent.is_symlink():
            raise ValueError('Missing/linked cache subtree')
        for path in root.rglob('*'):
            info = path.lstat()
            if stat.S_ISDIR(info.st_mode):
                continue
            if not stat.S_ISREG(info.st_mode):
                raise ValueError('Cache links/special files are not allowed')
            if path.name.endswith('.lock'):
                continue
            size += info.st_size
            if size > MAX_CACHE or len(files) >= 100000:
                raise ValueError('Cache exceeds preparation limits')
            files[path.relative_to(cache).as_posix()] = {'size': info.st_size, 'sha256': digest(path)}
    if not any(p.endswith('/gradle-9.7.1/bin/gradle') for p in files):
        raise ValueError('Gradle 9.7.1 distribution is not cached; do not download')
    if not any(p.startswith('caches/modules-2/') for p in files):
        raise ValueError('Dependency metadata/artifact cache missing')
    return files, size


def image_inventory(prefix):
    info = json.loads(pilot.network.execute(prefix, 'info', '--format', '{{json .}}').stdout)
    if ('name=rootless' not in info.get('SecurityOptions', [])
            or info.get('DockerRootDir') != '/home/feelm-ci-test/.local/share/docker'):
        raise ValueError('Unexpected Docker endpoint')
    if pilot.network.execute(prefix, 'ps', '-aq').stdout.strip():
        raise ValueError('Test containers must be stopped before preparing inputs')
    images = {}
    for tag, expected in IMAGES.items():
        info = json.loads(pilot.network.execute(prefix, 'image', 'inspect', tag,
                                                '--format', '{{json .}}').stdout)
        if expected not in info.get('RepoDigests', []):
            raise ValueError('Cached image digest changed; manual review required, no pull fallback')
        images[tag] = {'id': info['Id'], 'digest': expected,
                       'os': info['Os'], 'architecture': info['Architecture']}
    return images


def inspect(args):
    prefix = pilot.network.docker_prefix()  # dedicated user + socket permission check; read only
    run_dir = Path(args.source_run)
    if (run_dir.resolve(strict=True).parent != RUNS.resolve(strict=True)
            or not re.fullmatch('[0-9a-f]{32}', run_dir.name) or run_dir.is_symlink()):
        raise ValueError('Expected reviewed backend run directory')
    cache = run_dir / 'home/.gradle'
    if (run_dir / 'home').is_symlink():
        raise ValueError('Unexpected home symlink')
    with Path(args.archive).open('rb') as stream:
        archive = stream.read(pilot.MAX_ARCHIVE + 1)
    if len(archive) > pilot.MAX_ARCHIVE or hashlib.sha256(archive).hexdigest() != ARCHIVE_SHA:
        raise ValueError('Expected the recorded reviewed backend archive')
    validated, _ = pilot.archive_entries(archive)
    validated.close()
    files, size = cache_inventory(cache)
    images = image_inventory(prefix)
    print(f'INPUT_INSPECTION: cache_files={len(files)} bytes={size}; image_digests_match')
    print('NOT_OFFLINE_EXECUTION_PROOF: dependency completeness and Docker boundary remain unverified')
    return archive, cache, files, size, images


def prepare(args):
    archive, cache, files, size, images = inspect(args)
    if OUTPUT.is_symlink():
        raise ValueError('Unexpected output symlink')
    if shutil.disk_usage(OUTPUT.parent).free < size + 5 * 1024**3:
        raise ValueError('Need cache copy space plus 5 GiB free')
    OUTPUT.mkdir(mode=0o700, exist_ok=True)
    destination = OUTPUT / uuid.uuid4().hex
    destination.mkdir(mode=0o700)
    print('PREPARED_DIR: ' + str(destination), flush=True)
    # Partial output is retained if a copy fails, but no complete manifest is written.
    pilot.extract_snapshot(archive, destination)
    target_cache = destination / 'home/.gradle'
    for name, entry in files.items():
        source, target = cache / name, target_cache / name
        if source.is_symlink() or not source.is_file():
            raise ValueError('Cache changed during preparation')
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        shutil.copyfile(source, target)
        target.chmod(0o700 if name.endswith('/bin/gradle') else 0o600)
        if target.stat().st_size != entry['size'] or digest(target) != entry['sha256']:
            raise ValueError('Cache copy changed; keep source idle')
    if cache_inventory(cache)[0] != files:
        raise ValueError('Source cache changed; output is incomplete')
    for name in pilot.SUPPORT_FILES:
        target = destination / 'support' / name
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        shutil.copyfile(HERE / 'rootless-backend' / name, target)
    manifest = {'state': 'WAITING_DOCKER_BOUNDARY_REVIEW', 'revision': REVISION,
                'source_run': str(Path(args.source_run).resolve()),
                'archive_sha256': ARCHIVE_SHA, 'cache': files, 'images': images,
                'planned_gradle_args': GRADLE_ARGS, 'docker_images_exported': False,
                'tests_executed': False}
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print('PREPARATION_COMPLETE: files only; no Gradle, image pull/export, daemon or network changes')
    print('STOP_BEFORE_DOCKER_BOUNDARY: operator review required; no run action provided')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'inspect', 'prepare'], nargs='?', default='plan')
    parser.add_argument('--source-run', default=str(SOURCE_RUN))
    parser.add_argument('--archive', default=f'/home/feelm-ci-test/pilot-222/backend-{REVISION}.tar')
    parser.add_argument('--confirm-cache-idle', action='store_true')
    args = parser.parse_args()
    if args.action == 'plan':
        print('PLAN: recorded source archive + selected cache copy + local image digest inspection; '
              'keep full offline test/spotless tasks; STOP before Docker boundary or test execution')
    elif args.action == 'inspect':
        inspect(args)
    else:
        if not args.confirm_cache_idle:
            parser.error('prepare requires --confirm-cache-idle; no concurrent tests/Gradle allowed')
        prepare(args)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as exc:
        print('OFFLINE_PREPARATION_FAILED: ' + type(exc).__name__, file=sys.stderr)
        sys.exit(2)
