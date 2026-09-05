"""Untrusted backend snapshot/report boundary; library, not an enabled executor.

No source hooks, subprocesses, network, or archive.extractall. Filesystem actions
require Linux dirfd + O_NOFOLLOW. Callers own private directories and must stop
and verify the complete workload before collecting reports. XML is job output,
never evidence of isolation or authority to change the executor's state.
"""
from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tarfile
import unicodedata
import xml.etree.ElementTree as ET

MAX_ARCHIVE = 64 * 1024**2
MAX_SOURCE_FILE = 16 * 1024**2
MAX_SOURCE_FILES = 10000
MAX_REPORT_FILE = 4 * 1024**2
MAX_REPORT_TOTAL = 16 * 1024**2
MAX_REPORTS = 256
REPORT_DIRECTORY = 'work/backend/build/test-results/test'


def relative_parts(name):
    if not isinstance(name, str) or not name or len(name.encode('utf-8')) > 1024:
        raise ValueError('Invalid relative path length')
    parts = name.split('/')
    if (name != unicodedata.normalize('NFC', name) or '\\' in name or ':' in name
            or any(ord(c) < 32 or ord(c) == 127 for c in name)
            or any(p in ('', '.', '..') or len(p.encode('utf-8')) > 255 for p in parts)
            or len(parts) > 32 or PurePosixPath(name).is_absolute()):
        raise ValueError('Non-canonical relative path')
    return parts


def source_plan(payload, expected_sha):
    if (not isinstance(payload, bytes) or len(payload) > MAX_ARCHIVE
            or not re.fullmatch('[0-9a-f]{64}', expected_sha)
            or hashlib.sha256(payload).hexdigest() != expected_sha):
        raise ValueError('Snapshot size or identity mismatch')
    seen, files, directories = set(), {}, set()
    total = 0
    # r: deliberately excludes compressed archives and decompression bombs.
    with tarfile.open(fileobj=io.BytesIO(payload), mode='r:') as archive:
        for entry in archive:
            name = entry.name[:-1] if entry.isdir() and entry.name.endswith('/') else entry.name
            parts = relative_parts(name)
            if (parts[0] != 'backend' or name in seen or len(seen) >= MAX_SOURCE_FILES
                    or not (entry.isdir() or entry.isfile()) or entry.sparse is not None
                    or entry.size < 0 or entry.size > MAX_SOURCE_FILE
                    or any(p.casefold() in ('.git', '.gradle')
                           or (p.casefold().startswith('.env') and p != '.env.example')
                           for p in parts)
                    or entry.mode & 0o7000):
                raise ValueError('Unsupported or duplicate snapshot member')
            if entry.isdir() and entry.size:
                raise ValueError('Directory payload refused')
            seen.add(name)
            for depth in range(1, len(parts)):
                directories.add('/'.join(parts[:depth]))
            if entry.isdir():
                directories.add(name)
                continue
            total += entry.size
            if total > MAX_ARCHIVE:
                raise ValueError('Expanded snapshot exceeds limit')
            stream = archive.extractfile(entry)
            if stream is None: raise ValueError('Missing regular member data')
            data = stream.read(MAX_SOURCE_FILE + 1)
            if len(data) != entry.size: raise ValueError('Truncated snapshot')
            # No archive ownership, setuid, capabilities, xattrs, or link state.
            files[name] = (data, 0o700 if entry.mode & 0o111 else 0o600)
    if not files or directories.intersection(files):
        raise ValueError('Empty snapshot or file/directory collision')
    return files, directories


def require_linux():
    if not sys.platform.startswith('linux') or not hasattr(os, 'O_NOFOLLOW'):
        raise RuntimeError('Linux descriptor-relative filesystem operations required')


@contextmanager
def directory_fd(path):
    require_linux()
    path = os.fspath(path)
    if not path.startswith('/'):
        raise ValueError('Trusted absolute directory required')
    parts = [] if path == '/' else relative_parts(path[1:])
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    try:
        for part in parts:
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                              dir_fd=fd)
            os.close(fd); fd = next_fd
        yield fd
    finally:
        os.close(fd)


@contextmanager
def child_directory(root_fd, parts, create=False):
    fd = os.dup(root_fd)
    device = os.fstat(root_fd).st_dev
    try:
        for part in parts:
            if create:
                try: os.mkdir(part, 0o700, dir_fd=fd)
                except FileExistsError: pass
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                              dir_fd=fd)
            os.close(fd); fd = next_fd
            if os.fstat(fd).st_dev != device:
                raise ValueError('Cross-device path refused')
        yield fd
    finally:
        os.close(fd)


def private_empty(fd):
    info = os.fstat(fd)
    if info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise ValueError('Destination must be private and owned by this executor')
    with os.scandir(fd) as entries:
        if next(entries, None) is not None: raise ValueError('Destination must be new and empty')


def write_new(parent_fd, name, data, mode=0o600):
    if len(relative_parts(name)) != 1: raise ValueError('One filename required')
    fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                 mode, dir_fd=parent_fd)
    with os.fdopen(fd, 'wb') as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def install_snapshot(payload, expected_sha, destination):
    files, directories = source_plan(payload, expected_sha)
    with directory_fd(destination) as root_fd:
        private_empty(root_fd)
        for name in sorted(directories, key=lambda p: (p.count('/'), p)):
            with child_directory(root_fd, relative_parts(name), create=True): pass
        for name, (data, mode) in files.items():
            parts = relative_parts(name)
            with child_directory(root_fd, parts[:-1]) as parent:
                write_new(parent, parts[-1], data, mode)
    return {'source_sha256': expected_sha, 'files': len(files),
            'bytes': sum(len(data) for data, _ in files.values())}


def identity(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns,
            info.st_mode, info.st_nlink)


def read_regular(parent_fd, name, maximum):
    if len(relative_parts(name)) != 1: raise ValueError('One filename required')
    # O_NONBLOCK prevents a FIFO substitution from blocking privileged collection.
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=parent_fd)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size > maximum:
            raise ValueError('Linked, special, or oversized report refused')
        chunks, size = [], 0
        while True:
            chunk = os.read(fd, min(65536, maximum + 1 - size))
            if not chunk: break
            chunks.append(chunk); size += len(chunk)
            if size > maximum: raise ValueError('Report grew beyond limit')
        after = os.fstat(fd)
        current = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if identity(before) != identity(after) or identity(after) != identity(current) or size != before.st_size:
            raise ValueError('Report changed during collection')
        return b''.join(chunks)
    finally:
        os.close(fd)


def junit_summary(data):
    if len(data) > MAX_REPORT_FILE: raise ValueError('XML exceeds limit')
    text = data.decode('utf-8', errors='strict')
    if '\x00' in text or re.search(r'<!\s*(DOCTYPE|ENTITY)', text, re.IGNORECASE):
        raise ValueError('DTD/entities/non-UTF8 XML refused')
    root = ET.fromstring(text)
    if root.tag != 'testsuite': raise ValueError('Expected Gradle testsuite XML')
    nodes, pending = 0, [(root, 0)]
    while pending:
        node, depth = pending.pop(); nodes += 1
        if nodes > 100000 or depth > 32: raise ValueError('XML structure exceeds limit')
        pending.extend((child, depth + 1) for child in node)
    cases = root.findall('testcase')
    actual = {'tests': len(cases), 'failures': 0, 'errors': 0, 'skipped': 0}
    classes = set()
    for case in cases:
        classname = case.get('classname', '')
        if not classname or len(classname) > 512: raise ValueError('Missing testcase identity')
        classes.add(classname)
        for tag, key in (('failure', 'failures'), ('error', 'errors'), ('skipped', 'skipped')):
            actual[key] += int(case.find(tag) is not None)
    for key, value in actual.items():
        if not re.fullmatch('[0-9]{1,7}', root.get(key, '')) or int(root.get(key)) != value:
            raise ValueError('JUnit summary does not match testcase records')
    if not cases: raise ValueError('Empty test suite refused')
    return actual, classes


def collect_reports(stopped_root, destination, process_exit, required_classes=frozenset(), expected_tests=None):
    """Call ONLY after verified full cgroup teardown, never on a live workload.

    process_exit and required_classes are trusted supervisor inputs, not parsed
    from an MR-generated result file. No CLI accepts a caller's 'stopped=true'.
    Output manifest is written last; absent manifest means incomplete export.
    """
    if expected_tests is not None and (type(expected_tests) is not int or not 1 <= expected_tests <= 1000000):
        raise ValueError('Invalid trusted test-count policy')
    reports, classes = {}, set()
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    with directory_fd(stopped_root) as root_fd:
        with child_directory(root_fd, relative_parts(REPORT_DIRECTORY)) as report_fd:
            names = []
            with os.scandir(report_fd) as entries:
                for entry in entries:
                    names.append(entry.name)
                    if len(names) > MAX_REPORTS: raise ValueError('Too many directory entries')
            total_bytes = 0
            for name in sorted(names):
                if not re.fullmatch(r'TEST-[A-Za-z0-9_.$-]+\.xml', name): continue
                data = read_regular(report_fd, name, MAX_REPORT_FILE)
                total_bytes += len(data)
                if total_bytes > MAX_REPORT_TOTAL: raise ValueError('Report bundle exceeds limit')
                counts, found = junit_summary(data)
                if classes.intersection(found): raise ValueError('Duplicate testcase class across reports')
                classes.update(found)
                for key, value in counts.items(): totals[key] += value
                reports[name] = data
    if not reports: raise ValueError('No test reports')
    missing = sorted(set(required_classes) - classes)
    success = (type(process_exit) is int and process_exit == 0 and not missing
               and (expected_tests is None or totals['tests'] == expected_tests)
               and not any(totals[k] for k in ('failures', 'errors', 'skipped')))
    result = {'process_exit': process_exit, 'summary': totals, 'missing_classes': missing,
              'expected_tests': expected_tests,
              'passed': success, 'not_isolation_approval': True,
              'files': {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                        for name, data in reports.items()}}
    with directory_fd(destination) as output_fd:
        private_empty(output_fd)
        for name, data in reports.items(): write_new(output_fd, name, data)
        write_new(output_fd, 'manifest.json', json.dumps(result, sort_keys=True).encode())
    return result
