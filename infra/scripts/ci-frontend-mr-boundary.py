#!/usr/bin/env python3
"""Validate and install an untrusted frontend-only MR snapshot."""
from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile
import unicodedata
import urllib.parse

MAX_ARCHIVE = 256 * 1024**2
MAX_SOURCE_FILE = 32 * 1024**2
MAX_SOURCE_FILES = 20000
EXPECTED_SCRIPTS = {
    'build': 'tsc -b && vite build',
    'lint': 'eslint .',
    'format:check': 'prettier --check .',
    'test:run': 'vitest run',
}
FORBIDDEN_LIFECYCLE_SCRIPTS = {
    prefix + name
    for name in EXPECTED_SCRIPTS
    for prefix in ('pre', 'post')
}


def relative_parts(name):
    if not isinstance(name, str) or not name or len(name.encode('utf-8')) > 1024:
        raise ValueError('Invalid frontend path length')
    parts = name.split('/')
    if (name != unicodedata.normalize('NFC', name) or '\\' in name or ':' in name
            or any(ord(char) < 32 or ord(char) == 127 for char in name)
            or any(part in ('', '.', '..') or len(part.encode('utf-8')) > 255 for part in parts)
            or len(parts) > 32 or PurePosixPath(name).is_absolute()):
        raise ValueError('Non-canonical frontend path')
    return parts


def source_plan(payload, expected_sha):
    if (not isinstance(payload, bytes) or not payload or len(payload) > MAX_ARCHIVE
            or not re.fullmatch('[0-9a-f]{64}', expected_sha)
            or hashlib.sha256(payload).hexdigest() != expected_sha):
        raise ValueError('Frontend snapshot size or identity mismatch')
    files, directories, seen, total = {}, set(), set(), 0
    with tarfile.open(fileobj=io.BytesIO(payload), mode='r:') as archive:
        for entry in archive:
            name = entry.name[:-1] if entry.isdir() and entry.name.endswith('/') else entry.name
            parts = relative_parts(name)
            if (parts[0] != 'frontend' or name in seen or len(seen) >= MAX_SOURCE_FILES
                    or not (entry.isdir() or entry.isfile()) or entry.sparse is not None
                    or entry.size < 0 or entry.size > MAX_SOURCE_FILE or entry.mode & 0o7000
                    or (entry.isfile() and entry.mode & 0o111)
                    or any(part.casefold() in ('.git', 'node_modules', 'dist')
                           or (part.casefold().startswith('.env') and part != '.env.example')
                           for part in parts)):
                raise ValueError('Unsupported or duplicate frontend snapshot member')
            if entry.isdir() and entry.size:
                raise ValueError('Frontend directory payload refused')
            seen.add(name)
            for depth in range(1, len(parts)):
                directories.add('/'.join(parts[:depth]))
            if entry.isdir():
                directories.add(name)
                continue
            total += entry.size
            if total > MAX_ARCHIVE:
                raise ValueError('Expanded frontend snapshot exceeds limit')
            stream = archive.extractfile(entry)
            if stream is None:
                raise ValueError('Missing frontend member data')
            data = stream.read(MAX_SOURCE_FILE + 1)
            if len(data) != entry.size:
                raise ValueError('Truncated frontend snapshot')
            files[name] = data
    if not files or directories.intersection(files):
        raise ValueError('Empty frontend snapshot or path collision')
    validate_package_policy(files)
    return files, directories


def json_object(data, name):
    try:
        value = json.loads(data.decode('utf-8'))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError('Invalid ' + name) from exc
    if not isinstance(value, dict):
        raise ValueError(name + ' must be an object')
    return value


def validate_package_policy(files):
    required = {'frontend/package.json', 'frontend/package-lock.json'}
    if not required <= files.keys():
        raise ValueError('Frontend package manifest and lock are required')
    package = json_object(files['frontend/package.json'], 'package.json')
    scripts = package.get('scripts')
    if not isinstance(scripts, dict) or any(scripts.get(key) != value for key, value in EXPECTED_SCRIPTS.items()):
        raise ValueError('Frontend npm scripts differ from fixed policy')
    if FORBIDDEN_LIFECYCLE_SCRIPTS.intersection(scripts):
        raise ValueError('Frontend npm lifecycle hooks for fixed scripts are not allowed')
    lock = json_object(files['frontend/package-lock.json'], 'package-lock.json')
    if lock.get('lockfileVersion') not in (2, 3) or not isinstance(lock.get('packages'), dict):
        raise ValueError('Unsupported frontend lockfile')
    for entry in lock['packages'].values():
        if not isinstance(entry, dict):
            raise ValueError('Invalid frontend lock package')
        resolved = entry.get('resolved')
        if resolved is not None:
            parsed = urllib.parse.urlsplit(resolved)
            if parsed.scheme != 'https' or parsed.hostname != 'registry.npmjs.org' or parsed.port is not None:
                raise ValueError('Frontend lock resolves outside registry.npmjs.org')
        integrity = entry.get('integrity')
        if resolved is not None and (not isinstance(integrity, str) or not integrity.startswith('sha512-')):
            raise ValueError('Frontend lock package integrity is missing')


@contextmanager
def directory_fd(path):
    path = os.fspath(path)
    if not path.startswith('/') or not hasattr(os, 'O_NOFOLLOW'):
        raise RuntimeError('Linux absolute destination required')
    parts = [] if path == '/' else relative_parts(path[1:])
    descriptor = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    try:
        for part in parts:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                            dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        yield descriptor
    finally:
        os.close(descriptor)


def install_snapshot(payload, expected_sha, destination):
    files, directories = source_plan(payload, expected_sha)
    destination = Path(destination)
    with directory_fd(destination) as root_fd:
        if os.fstat(root_fd).st_uid != 0 or os.fstat(root_fd).st_mode & 0o077:
            raise ValueError('Frontend staging directory must be root-owned and private')
        for name in sorted(directories, key=lambda value: (value.count('/'), value)):
            current = root_fd
            descriptors = []
            try:
                for part in relative_parts(name):
                    try:
                        os.mkdir(part, 0o755, dir_fd=current)
                    except FileExistsError:
                        pass
                    child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                                    dir_fd=current)
                    descriptors.append(child)
                    current = child
            finally:
                for descriptor in reversed(descriptors):
                    os.close(descriptor)
        for name, data in files.items():
            target = destination.joinpath(*relative_parts(name))
            descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o444)
            with os.fdopen(descriptor, 'wb') as output:
                output.write(data)
        for path in [destination, *destination.rglob('*')]:
            path.chmod(0o555 if path.is_dir() else 0o444)
    return {'source_sha256': expected_sha, 'files': len(files),
            'bytes': sum(len(data) for data in files.values())}
