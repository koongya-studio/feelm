#!/usr/bin/env python3
"""Verify a root-owned, immutable Pipeline uv cache by canonical tree hash."""
import hashlib
from pathlib import Path
import re
import stat

MARKER = '.feelm-cache-sha256'


def _entry_identity(root, path, digest):
    relative = path.relative_to(root).as_posix()
    info = path.lstat()
    if info.st_uid != 0 or info.st_mode & 0o022:
        raise RuntimeError('Pipeline cache entries must be root-owned and immutable')
    if stat.S_ISDIR(info.st_mode):
        if info.st_mode & 0o555 != 0o555:
            raise RuntimeError('Pipeline cache directory must be readable and searchable')
        digest.update(b'D\0' + relative.encode('utf-8') + b'\0')
        for child in sorted(path.iterdir(), key=lambda item: item.name):
            if path == root and child.name == MARKER:
                continue
            _entry_identity(root, child, digest)
        return
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_mode & 0o444 != 0o444:
        raise RuntimeError('Pipeline cache contains an unsupported or unreadable entry')
    content_hash = hashlib.sha256()
    size = 0
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            size += len(chunk)
            content_hash.update(chunk)
    if size != info.st_size:
        raise RuntimeError('Pipeline cache entry changed during verification')
    digest.update(
        b'F\0' + relative.encode('utf-8') + b'\0' + str(size).encode('ascii') + b'\0'
        + content_hash.hexdigest().encode('ascii') + b'\0')


def identity(path):
    root = Path(path).resolve(strict=True)
    digest = hashlib.sha256(b'feelm-pipeline-cache-v1\0')
    _entry_identity(root, root, digest)
    return digest.hexdigest()


def verify(path, expected):
    if re.fullmatch(r'[0-9a-f]{64}', str(expected)) is None:
        raise ValueError('Pipeline dependency cache digest is invalid')
    root = Path(path).resolve(strict=True)
    marker = root / MARKER
    info = marker.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022
            or info.st_nlink != 1 or info.st_size > 65
            or marker.read_text(encoding='ascii').strip() != expected):
        raise RuntimeError('Pipeline dependency cache marker is not trusted')
    actual = identity(root)
    if actual != expected:
        raise RuntimeError('Pipeline dependency cache content identity differs')
    return actual
