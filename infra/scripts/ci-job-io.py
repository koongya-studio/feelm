"""Untrusted backend snapshot/report boundary; library, not an enabled executor.

No source hooks, subprocesses, network, or archive.extractall. Filesystem actions
require Linux dirfd + O_NOFOLLOW. Callers own private directories and must stop
and verify the complete workload before collecting reports. XML is job output,
never evidence of isolation or authority to change the executor's state.
"""
from contextlib import contextmanager
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
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
TIMING_FILE = 'work/backend/build/reports/test-timing.json'
MAX_TIMING_FILE = 64 * 1024


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


def junit_root(data):
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
    return root


def junit_summary(data):
    root = junit_root(data)
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


def junit_class_timings(data):
    root = junit_root(data)
    result = {}
    for case in root.findall('testcase'):
        classname = case.get('classname', '')
        elapsed = case.get('time', '')
        if (not re.fullmatch(r'[A-Za-z0-9_.$]{1,512}', classname)
                or not re.fullmatch(r'[0-9]{1,7}(?:\.[0-9]{1,9})?', elapsed)):
            raise ValueError('Invalid JUnit testcase timing')
        try:
            duration = Decimal(elapsed)
        except InvalidOperation as exc:
            raise ValueError('Invalid JUnit testcase timing') from exc
        if duration > Decimal(86400): raise ValueError('JUnit testcase timing exceeds one day')
        current = result.setdefault(classname, {'seconds': Decimal(0), 'tests': 0})
        current['seconds'] += duration
        current['tests'] += 1
    if not result: raise ValueError('Empty test timing refused')
    return result


def junit_lifecycle_timing(data):
    """Split one Gradle per-class XML suite into testcase and class lifecycle time."""
    root = junit_root(data)
    elapsed = root.get('time', '')
    if not re.fullmatch(r'[0-9]{1,7}(?:\.[0-9]{1,9})?', elapsed):
        raise ValueError('Invalid JUnit testsuite timing')
    try:
        suite_seconds = Decimal(elapsed)
    except InvalidOperation as exc:
        raise ValueError('Invalid JUnit testsuite timing') from exc
    if suite_seconds > Decimal(86400):
        raise ValueError('JUnit testsuite timing exceeds one day')
    classes = junit_class_timings(data)
    if len(classes) != 1:
        raise ValueError('Expected one testcase class per Gradle report')
    classname, timing = next(iter(classes.items()))
    case_seconds = timing['seconds']
    # Gradle serializes every testcase independently, so their accumulated
    # rounding error grows with the case count. Normalize only that bounded
    # difference; a larger contradiction still fails closed.
    rounding_tolerance = Decimal('0.001') * timing['tests']
    if suite_seconds + rounding_tolerance < case_seconds:
        raise ValueError('JUnit testsuite duration is shorter than its testcases')
    suite_seconds = max(suite_seconds, case_seconds)
    lifecycle_seconds = max(Decimal(0), suite_seconds - case_seconds)
    return classname, {
        'suite_seconds': suite_seconds,
        'case_seconds': case_seconds,
        'lifecycle_seconds': lifecycle_seconds,
        'tests': timing['tests'],
    }


def seconds_value(value, label, maximum=2400):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError('Invalid ' + label)
    try:
        decimal = Decimal(str(value))
    except InvalidOperation as exc:
        raise ValueError('Invalid ' + label) from exc
    if not decimal.is_finite() or decimal < 0 or decimal > maximum:
        raise ValueError('Invalid ' + label)
    return decimal.quantize(Decimal('0.001'), rounding=ROUND_HALF_UP)


def gradle_timing(data):
    if not isinstance(data, bytes) or len(data) > MAX_TIMING_FILE:
        raise ValueError('Gradle timing exceeds limit')
    try:
        value = json.loads(data.decode('utf-8', errors='strict'))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError('Invalid Gradle timing JSON') from exc
    expected = {'schemaVersion', 'gradleSeconds', 'slowestTasks', 'testCount',
                'slowestClasses', 'informationalOnly'}
    if (not isinstance(value, dict) or set(value) != expected or value['schemaVersion'] != 1
            or value['informationalOnly'] is not True or type(value['testCount']) is not int
            or not 0 <= value['testCount'] <= 1000000
            or not isinstance(value['slowestTasks'], list) or len(value['slowestTasks']) > 20
            or not isinstance(value['slowestClasses'], list) or len(value['slowestClasses']) > 10):
        raise ValueError('Unexpected Gradle timing schema')
    tasks = []
    seen = set()
    for item in value['slowestTasks']:
        if (not isinstance(item, dict) or set(item) != {'path', 'seconds'}
                or not isinstance(item['path'], str) or not re.fullmatch(r':[A-Za-z0-9_.:-]{1,255}', item['path'])
                or item['path'] in seen):
            raise ValueError('Invalid Gradle task timing')
        seen.add(item['path'])
        tasks.append({'path': item['path'],
                      'seconds': float(seconds_value(item['seconds'], 'Gradle task timing'))})
    return {'gradle_seconds': float(seconds_value(value['gradleSeconds'], 'Gradle duration')),
            'slowest_tasks': tasks}


def public_timing(gradle, class_timings, lifecycle_timings):
    if set(class_timings) != set(lifecycle_timings):
        raise ValueError('JUnit class and lifecycle timing sets differ')
    classes = []
    total_tests = 0
    total_seconds = Decimal(0)
    for classname, timing in class_timings.items():
        lifecycle = lifecycle_timings[classname]
        if (timing['seconds'] != lifecycle['case_seconds']
                or timing['tests'] != lifecycle['tests']):
            raise ValueError('JUnit class and lifecycle timing values differ')
        duration = timing['seconds']
        count = timing['tests']
        total_seconds += duration
        total_tests += count
        classes.append({'class_name': classname,
                        'seconds': float(duration.quantize(Decimal('0.001'), rounding=ROUND_HALF_UP)),
                        'tests': count})
    classes.sort(key=lambda item: (-item['seconds'], item['class_name']))
    suite_seconds = sum((item['suite_seconds'] for item in lifecycle_timings.values()), Decimal(0))
    lifecycle_seconds = sum(
        (item['lifecycle_seconds'] for item in lifecycle_timings.values()), Decimal(0))
    lifecycle_classes = [{
        'class_name': classname,
        'seconds': float(item['lifecycle_seconds'].quantize(
            Decimal('0.001'), rounding=ROUND_HALF_UP)),
        'tests': item['tests'],
    } for classname, item in lifecycle_timings.items()]
    lifecycle_classes.sort(key=lambda item: (-item['seconds'], item['class_name']))
    test_tasks = [Decimal(str(item['seconds'])) for item in gradle['slowest_tasks']
                  if item['path'] == ':test']
    if len(test_tasks) != 1:
        raise ValueError('Missing unique Gradle test task timing')
    test_task_seconds = test_tasks[0]
    unattributed_seconds = max(Decimal(0), test_task_seconds - suite_seconds)
    return {
        'schema_version': 2,
        **gradle,
        'test_task_seconds': float(test_task_seconds.quantize(
            Decimal('0.001'), rounding=ROUND_HALF_UP)),
        'test_suite_seconds': float(suite_seconds.quantize(
            Decimal('0.001'), rounding=ROUND_HALF_UP)),
        'test_case_seconds': float(total_seconds.quantize(
            Decimal('0.001'), rounding=ROUND_HALF_UP)),
        'test_class_lifecycle_seconds': float(lifecycle_seconds.quantize(
            Decimal('0.001'), rounding=ROUND_HALF_UP)),
        'test_task_unattributed_seconds': float(unattributed_seconds.quantize(
            Decimal('0.001'), rounding=ROUND_HALF_UP)),
        'test_count': total_tests,
        'slowest_classes': classes[:10],
        'slowest_lifecycle_classes': lifecycle_classes[:10],
        'informational_only': True,
    }


def validated_public_timing(value):
    legacy = {'schema_version', 'gradle_seconds', 'slowest_tasks', 'test_case_seconds',
              'test_count', 'slowest_classes', 'informational_only'}
    detailed = legacy | {'test_task_seconds', 'test_suite_seconds',
                         'test_class_lifecycle_seconds', 'test_task_unattributed_seconds',
                         'slowest_lifecycle_classes'}
    if (not isinstance(value, dict) or value.get('schema_version') not in (1, 2)
            or set(value) != (legacy if value.get('schema_version') == 1 else detailed)
            or value['informational_only'] is not True or type(value['test_count']) is not int
            or not 0 <= value['test_count'] <= 1000000
            or not isinstance(value['slowest_tasks'], list) or len(value['slowest_tasks']) > 20
            or not isinstance(value['slowest_classes'], list) or len(value['slowest_classes']) > 10
            or (value.get('schema_version') == 2
                and (not isinstance(value['slowest_lifecycle_classes'], list)
                     or len(value['slowest_lifecycle_classes']) > 10))):
        raise ValueError('Unexpected public timing schema')
    result = {
        'schema_version': value['schema_version'],
        'gradle_seconds': float(seconds_value(value['gradle_seconds'], 'Gradle duration')),
        'slowest_tasks': [],
        'test_case_seconds': float(seconds_value(
            value['test_case_seconds'], 'test case duration', maximum=86400 * 1000000)),
        'test_count': value['test_count'],
        'slowest_classes': [],
        'informational_only': True,
    }
    if value['schema_version'] == 2:
        result.update({
            'test_task_seconds': float(seconds_value(
                value['test_task_seconds'], 'test task duration')),
            'test_suite_seconds': float(seconds_value(
                value['test_suite_seconds'], 'test suite duration', maximum=86400 * 1000000)),
            'test_class_lifecycle_seconds': float(seconds_value(
                value['test_class_lifecycle_seconds'], 'test class lifecycle duration',
                maximum=86400 * 1000000)),
            'test_task_unattributed_seconds': float(seconds_value(
                value['test_task_unattributed_seconds'], 'unattributed test task duration')),
            'slowest_lifecycle_classes': [],
        })
    seen_tasks = set()
    for item in value['slowest_tasks']:
        if (not isinstance(item, dict) or set(item) != {'path', 'seconds'}
                or not isinstance(item['path'], str)
                or not re.fullmatch(r':[A-Za-z0-9_.:-]{1,255}', item['path'])
                or item['path'] in seen_tasks):
            raise ValueError('Invalid public Gradle task timing')
        seen_tasks.add(item['path'])
        result['slowest_tasks'].append({
            'path': item['path'],
            'seconds': float(seconds_value(item['seconds'], 'Gradle task timing')),
        })
    seen_classes = set()
    class_tests = 0
    for item in value['slowest_classes']:
        if (not isinstance(item, dict) or set(item) != {'class_name', 'seconds', 'tests'}
                or not isinstance(item['class_name'], str)
                or not re.fullmatch(r'[A-Za-z0-9_.$]{1,512}', item['class_name'])
                or item['class_name'] in seen_classes or type(item['tests']) is not int
                or not 1 <= item['tests'] <= 1000000):
            raise ValueError('Invalid public test class timing')
        seen_classes.add(item['class_name'])
        class_tests += item['tests']
        result['slowest_classes'].append({
            'class_name': item['class_name'],
            'seconds': float(seconds_value(
                item['seconds'], 'test class duration', maximum=86400 * 1000000)),
            'tests': item['tests'],
        })
    if class_tests > result['test_count']:
        raise ValueError('Slow test class count exceeds total tests')
    if value['schema_version'] == 2:
        seen_lifecycle_classes = set()
        lifecycle_tests = 0
        for item in value['slowest_lifecycle_classes']:
            if (not isinstance(item, dict) or set(item) != {'class_name', 'seconds', 'tests'}
                    or not isinstance(item['class_name'], str)
                    or not re.fullmatch(r'[A-Za-z0-9_.$]{1,512}', item['class_name'])
                    or item['class_name'] in seen_lifecycle_classes
                    or type(item['tests']) is not int or not 1 <= item['tests'] <= 1000000):
                raise ValueError('Invalid public test lifecycle timing')
            seen_lifecycle_classes.add(item['class_name'])
            lifecycle_tests += item['tests']
            result['slowest_lifecycle_classes'].append({
                'class_name': item['class_name'],
                'seconds': float(seconds_value(
                    item['seconds'], 'test lifecycle duration', maximum=86400 * 1000000)),
                'tests': item['tests'],
            })
        if lifecycle_tests > result['test_count']:
            raise ValueError('Slow lifecycle class count exceeds total tests')
        case = Decimal(str(result['test_case_seconds']))
        suite = Decimal(str(result['test_suite_seconds']))
        lifecycle = Decimal(str(result['test_class_lifecycle_seconds']))
        task = Decimal(str(result['test_task_seconds']))
        unattributed = Decimal(str(result['test_task_unattributed_seconds']))
        tolerance = Decimal('0.002')
        if (abs(suite - case - lifecycle) > tolerance
                or abs(max(Decimal(0), task - suite) - unattributed) > tolerance):
            raise ValueError('Inconsistent public test lifecycle totals')
    return result


def collect_reports(stopped_root, destination, process_exit, required_classes=frozenset(), expected_tests=None):
    """Call ONLY after verified full cgroup teardown, never on a live workload.

    process_exit and required_classes are trusted supervisor inputs, not parsed
    from an MR-generated result file. No CLI accepts a caller's 'stopped=true'.
    Output manifest is written last; absent manifest means incomplete export.
    """
    if expected_tests is not None and (type(expected_tests) is not int or not 1 <= expected_tests <= 1000000):
        raise ValueError('Invalid trusted test-count policy')
    reports, classes, class_timings, lifecycle_timings = {}, set(), {}, {}
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
                timings = junit_class_timings(data)
                lifecycle_class, lifecycle = junit_lifecycle_timing(data)
                if classes.intersection(found): raise ValueError('Duplicate testcase class across reports')
                classes.update(found)
                for key, value in counts.items(): totals[key] += value
                for classname, timing in timings.items():
                    current = class_timings.setdefault(
                        classname, {'seconds': Decimal(0), 'tests': 0})
                    current['seconds'] += timing['seconds']
                    current['tests'] += timing['tests']
                if lifecycle_class in lifecycle_timings:
                    raise ValueError('Duplicate test lifecycle class across reports')
                lifecycle_timings[lifecycle_class] = lifecycle
                reports[name] = data
    if not reports: raise ValueError('No test reports')
    missing = sorted(set(required_classes) - classes)
    success = (type(process_exit) is int and process_exit == 0 and not missing
               and (expected_tests is None or totals['tests'] == expected_tests)
               and not any(totals[k] for k in ('failures', 'errors', 'skipped')))
    with directory_fd(stopped_root) as root_fd:
        parts = relative_parts(TIMING_FILE)
        with child_directory(root_fd, parts[:-1]) as timing_fd:
            timing_data = read_regular(timing_fd, parts[-1], MAX_TIMING_FILE)
    timing = public_timing(gradle_timing(timing_data), class_timings, lifecycle_timings)
    if timing['test_count'] != totals['tests']:
        raise ValueError('Timing test count does not match JUnit summary')
    result = {'process_exit': process_exit, 'summary': totals, 'missing_classes': missing,
              'expected_tests': expected_tests,
              'passed': success, 'not_isolation_approval': True,
              'timing': timing,
              'files': {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                        for name, data in reports.items()}}
    with directory_fd(destination) as output_fd:
        private_empty(output_fd)
        for name, data in reports.items(): write_new(output_fd, name, data)
        write_new(output_fd, 'manifest.json', json.dumps(result, sort_keys=True).encode())
    return result
