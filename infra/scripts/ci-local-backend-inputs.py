#!/usr/bin/env python3
"""Audit reviewed inputs for the local manager. Never executes Gradle or Docker."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import stat
import subprocess
import sys

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('local_input_prep', HERE / 'ci-backend-offline-prepare.py')
prep = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prep)
IMAGE_ARCHIVE_SHA = 'f1fdc5c9e4e3c96d1d04f4bdab407e1953764199ad2e2a7b007c50c0810990cb'
REQUIRED = {'backend.tar', 'backend-images.tar', 'jdk.tar.gz', 'cache-manifest.json', 'gradle-cache'}


def real_path(path):
    path = Path(path).absolute()
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError('Linked input paths are not allowed')
    return path.resolve(strict=True)


def checked_file(path, expected, maximum):
    if not re.fullmatch('[0-9a-f]{64}', expected or ''):
        raise ValueError('Reviewed SHA256 is required')
    path = real_path(path)
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= maximum:
        raise ValueError('Input must be a bounded regular file: ' + path.name)
    if prep.digest(path) != expected:
        raise ValueError('SHA256 mismatch: ' + path.name)
    return path


def unique_json(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate manifest key')
        result[key] = value
    return result


def source_bytes(repository):
    # Exact reviewed revision; never use the working tree or current branch.
    result = subprocess.run(['git', '-C', str(repository), 'archive', '--format=tar',
                             prep.REVISION, 'backend'], capture_output=True, check=True, timeout=30)
    data = result.stdout
    if len(data) > prep.pilot.MAX_ARCHIVE or hashlib.sha256(data).hexdigest() != prep.ARCHIVE_SHA:
        raise ValueError('Reviewed source archive mismatch')
    archive, _ = prep.pilot.archive_entries(data)
    archive.close()
    return data


def export_source(repository, destination):
    data = source_bytes(repository)
    destination = Path(destination).absolute()
    real_path(destination.parent)
    # Exclusive create, no overwrite of an existing archive or symlink.
    with destination.open('xb') as stream:
        stream.write(data)
    print('SOURCE_EXPORTED: ' + str(destination))
    print('SOURCE_SHA256=' + prep.ARCHIVE_SHA)
    print('NO_TESTS_EXECUTED')


def inspect_bundle(bundle, manifest_sha, jdk_sha):
    root = real_path(bundle)
    if not root.is_dir() or {p.name for p in root.iterdir()} != REQUIRED:
        raise ValueError('Expected only: ' + ', '.join(sorted(REQUIRED)))
    source = checked_file(root / 'backend.tar', prep.ARCHIVE_SHA, prep.pilot.MAX_ARCHIVE)
    archive, _ = prep.pilot.archive_entries(source.read_bytes())
    archive.close()
    checked_file(root / 'backend-images.tar', IMAGE_ARCHIVE_SHA, 2 * 1024**3)
    checked_file(root / 'jdk.tar.gz', jdk_sha, 512 * 1024**2)
    manifest_path = checked_file(root / 'cache-manifest.json', manifest_sha, 32 * 1024**2)
    manifest = json.loads(manifest_path.read_text(), object_pairs_hook=unique_json)
    if manifest.get('revision') != prep.REVISION or manifest.get('archive_sha256') != prep.ARCHIVE_SHA:
        raise ValueError('Cache manifest is for a different source revision')
    cache = real_path(root / 'gradle-cache')
    inventory, size = prep.cache_inventory(cache)
    # cache_inventory excludes locks and unrelated home state. Refuse those
    # extras instead of accidentally copying them into a future rootfs.
    actual = set()
    for path in cache.rglob('*'):
        mode = path.lstat().st_mode
        if stat.S_ISDIR(mode):
            continue
        if not stat.S_ISREG(mode):
            raise ValueError('Cache links/special files are not allowed')
        actual.add(path.relative_to(cache).as_posix())
    if actual != set(inventory) or manifest.get('cache') != inventory:
        raise ValueError('Cache inventory mismatch or unrecorded files')
    return {'revision': prep.REVISION, 'cache_files': len(inventory), 'cache_bytes': size,
            'source_verified': True, 'image_archive_verified': True,
            'jdk_archive_hash_verified': True, 'java21_runtime_verified': False,
            'tests_executed': False, 'runner_approved': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'export-source', 'inspect'], nargs='?', default='plan')
    parser.add_argument('--output')
    parser.add_argument('--bundle')
    parser.add_argument('--cache-manifest-sha256')
    parser.add_argument('--jdk-sha256')
    args = parser.parse_args()
    if args.action == 'plan':
        print('PLAN: exact reviewed source + cache + saved images + approved Linux JDK21 archive; '
              'file audit only; no extraction/download/Gradle/Docker/server changes')
        return 0
    if args.action == 'export-source':
        if not args.output:
            parser.error('export-source requires --output (new file only)')
        export_source(HERE.parent.parent, args.output)
        return 0
    if not args.bundle or not args.cache_manifest_sha256 or not args.jdk_sha256:
        parser.error('inspect requires bundle and separately reviewed manifest/JDK SHA256 values')
    print('INPUT_AUDIT: ' + json.dumps(inspect_bundle(
        args.bundle, args.cache_manifest_sha256, args.jdk_sha256)))
    print('NO_TESTS_EXECUTED; NOT_RUNTIME_COMPATIBILITY_OR_ISOLATION_APPROVAL')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        print('INPUT_ERROR: ' + str(exc), file=sys.stderr)
        sys.exit(2)
