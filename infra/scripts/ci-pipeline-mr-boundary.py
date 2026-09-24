#!/usr/bin/env python3
"""Validate and install an untrusted pipeline/spark MR snapshot."""
from contextlib import contextmanager
import json
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile
import unicodedata
import urllib.parse

MAX_ARCHIVE = 768 * 1024**2
MAX_SOURCE_FILE = 512 * 1024**2
MAX_SOURCE_FILES = 30000
REQUIRED_FILES = {
    'pipeline/spark/.python-version',
    'pipeline/spark/pyproject.toml',
    'pipeline/spark/uv.lock',
}
ALLOWED_REGISTRIES = {
    'https://pypi.org/simple',
    'https://download.pytorch.org/whl/cpu',
}
ALLOWED_URL_PATHS = {
    'pypi.org': '/',
    'files.pythonhosted.org': '/packages/',
    'download-r2.pytorch.org': '/whl/cpu/',
}
EXPECTED_PROJECT = {
    'project': {
        'name': 'feelm-pipeline-spark',
        'version': '0.1.0',
        'description': 'FEELM PySpark pipeline batch and streaming jobs',
        'requires-python': '>=3.10,<3.11',
        'dependencies': [
            'pandas==2.2.3',
            'psycopg[binary]==3.2.10',
            'pyarrow==19.0.1',
            'pyspark[ml]==4.1.3',
            'scikit-learn==1.6.1',
            'torch==2.6.0',
            'transformers==4.48.3',
            'xgboost-cpu==2.1.4',
        ],
    },
    'dependency-groups': {'dev': ['pytest==8.4.2', 'ruff==0.12.12']},
    'tool.pytest.ini_options': {
        'addopts': '-ra', 'pythonpath': ['.'], 'testpaths': ['tests'],
    },
    'tool.ruff': {'line-length': 100, 'target-version': 'py310'},
    'tool.ruff.lint': {'select': ['E', 'F', 'I', 'UP', 'B']},
    'tool.uv': {'package': False},
    'tool.uv.sources': {'torch': {'index': 'pytorch-cpu'}},
    'tool.uv.index': [{
        'name': 'pytorch-cpu',
        'url': 'https://download.pytorch.org/whl/cpu',
        'explicit': True,
    }],
}


def relative_parts(name):
    if not isinstance(name, str) or not name or len(name.encode('utf-8')) > 1024:
        raise ValueError('Invalid Pipeline path length')
    parts = name.split('/')
    if (name != unicodedata.normalize('NFC', name) or '\\' in name or ':' in name
            or any(ord(char) < 32 or ord(char) == 127 for char in name)
            or any(part in ('', '.', '..') or len(part.encode('utf-8')) > 255 for part in parts)
            or len(parts) > 32 or PurePosixPath(name).is_absolute()):
        raise ValueError('Non-canonical Pipeline path')
    return parts


def strip_comment(line):
    quoted = escaped = False
    for index, char in enumerate(line):
        if escaped:
            escaped = False
        elif char == '\\' and quoted:
            escaped = True
        elif char == '"':
            quoted = not quoted
        elif char == '#' and not quoted:
            return line[:index]
    return line


def parse_reviewed_value(raw):
    normalized = re.sub(r',\s*]', ']', raw)
    if normalized in ('false', 'true'):
        return normalized == 'true'
    if re.fullmatch(r'[0-9]+', normalized):
        return int(normalized)
    if normalized.startswith('{') and normalized.endswith('}'):
        body = normalized[1:-1].strip()
        values = {}
        for entry in body.split(',') if body else ():
            match = re.fullmatch(r'\s*([a-zA-Z0-9_-]+)\s*=\s*("(?:[^"\\]|\\.)*")\s*', entry)
            if match is None or match.group(1) in values:
                raise ValueError('Pipeline manifest inline table is outside the reviewed syntax')
            values[match.group(1)] = json.loads(match.group(2))
        return values
    try:
        return json.loads(normalized)
    except json.JSONDecodeError as exc:
        raise ValueError('Pipeline manifest value is outside the reviewed syntax') from exc


def parse_reviewed_project(value):
    sections = {}
    section = None
    current = None
    lines = value.splitlines()
    index = 0
    while index < len(lines):
        line = strip_comment(lines[index]).strip()
        index += 1
        if not line:
            continue
        match = re.fullmatch(r'(\[\[|\[)([a-zA-Z0-9_.-]+)(\]\]|\])', line)
        if match:
            opening, section, closing = match.groups()
            is_array = opening == '[['
            if is_array != (closing == ']]'):
                raise ValueError('Pipeline manifest contains an invalid section header')
            expected = EXPECTED_PROJECT.get(section)
            if expected is None or is_array != isinstance(expected, list):
                raise ValueError('Pipeline manifest contains an unreviewed or duplicate section')
            if is_array:
                sections.setdefault(section, [])
                current = {}
                sections[section].append(current)
            else:
                if section in sections:
                    raise ValueError('Pipeline manifest contains an unreviewed or duplicate section')
                current = {}
                sections[section] = current
            continue
        match = re.fullmatch(r'([a-zA-Z0-9_-]+)\s*=\s*(.*)', line)
        if section is None or current is None or match is None or match.group(1) in current:
            raise ValueError('Pipeline manifest contains an invalid or duplicate key')
        key, raw = match.groups()
        balance = raw.count('[') - raw.count(']')
        while balance > 0 and index < len(lines):
            continuation = strip_comment(lines[index]).strip()
            index += 1
            raw += continuation
            balance += continuation.count('[') - continuation.count(']')
        if balance != 0:
            raise ValueError('Pipeline manifest contains an unterminated array')
        current[key] = parse_reviewed_value(raw)
    if sections != EXPECTED_PROJECT:
        raise ValueError('Pipeline manifest differs from the reviewed runtime policy')


def validate_project_policy(files):
    if not REQUIRED_FILES <= files.keys():
        raise ValueError('Pipeline Python version, manifest, and lock are required')
    try:
        python_version = files['pipeline/spark/.python-version'].decode('ascii').strip()
        project = files['pipeline/spark/pyproject.toml'].decode('utf-8')
        lock = files['pipeline/spark/uv.lock'].decode('utf-8')
    except UnicodeDecodeError as exc:
        raise ValueError('Invalid Pipeline manifest or lock encoding') from exc
    if python_version != '3.10.12':
        raise ValueError('Unreviewed Pipeline Python version')
    parse_reviewed_project(project)
    source_lines = re.findall(r'(?m)^source = \{ ([^{}]+) \}$', lock)
    allowed_sources = {
        'registry = "https://pypi.org/simple"',
        'registry = "https://download.pytorch.org/whl/cpu"',
        'virtual = "."',
    }
    if not source_lines or any(source not in allowed_sources for source in source_lines):
        raise ValueError('Pipeline lock uses an unapproved source type')
    if re.search(r'(?m)^source = ', lock) and len(source_lines) != len(
            re.findall(r'(?m)^source = ', lock)):
        raise ValueError('Pipeline lock source syntax is not reviewed')
    registries = re.findall(r'registry\s*=\s*"([^"]+)"', lock)
    urls = re.findall(r'\burl\s*=\s*"([^"]+)"', lock)
    hashes = re.findall(r'\bhash\s*=\s*"([^"]+)"', lock)
    if not registries or any(value not in ALLOWED_REGISTRIES for value in registries):
        raise ValueError('Pipeline lock uses an unapproved registry source')
    for value in urls:
        parsed = urllib.parse.urlsplit(value)
        allowed_path = ALLOWED_URL_PATHS.get(parsed.hostname)
        if (parsed.scheme != 'https' or allowed_path is None
                or not parsed.path.startswith(allowed_path)
                or parsed.port is not None or parsed.username is not None
                or parsed.password is not None or parsed.query or parsed.fragment):
            raise ValueError('Pipeline lock uses an unapproved package source')
    url_lines = [line for line in lock.splitlines() if re.search(r'\burl\s*=', line)]
    if (not urls or len(hashes) < len(urls) or any(
            re.fullmatch(r'sha256:[0-9a-f]{64}', value) is None for value in hashes)):
        raise ValueError('Pipeline lock package hash is missing or invalid')
    for line in url_lines:
        if (re.search(r'\burl\s*=\s*"[^"]+"', line) is None
                or re.search(r'\bhash\s*=\s*"sha256:[0-9a-f]{64}"', line) is None):
            raise ValueError('Pipeline lock URL is missing its package hash')


def source_plan(payload, expected_sha):
    if (not isinstance(payload, bytes) or not payload or len(payload) > MAX_ARCHIVE
            or not re.fullmatch(r'[0-9a-f]{64}', str(expected_sha))
            or hashlib.sha256(payload).hexdigest() != expected_sha):
        raise ValueError('Pipeline snapshot size or identity hash mismatch')
    files, directories, seen, identities, total = {}, set(), set(), {}, 0
    try:
        with tarfile.open(fileobj=io.BytesIO(payload), mode='r:') as archive:
            for entry in archive:
                name = entry.name[:-1] if entry.isdir() and entry.name.endswith('/') else entry.name
                parts = relative_parts(name)
                in_scope = name == 'pipeline' or name == 'pipeline/spark' or name.startswith(
                    'pipeline/spark/')
                for depth in range(1, len(parts) + 1):
                    prefix = '/'.join(parts[:depth])
                    identity = prefix.casefold()
                    if identity in identities and identities[identity] != prefix:
                        raise ValueError('Pipeline snapshot path collision')
                    identities[identity] = prefix
                if (not in_scope or name in seen or len(seen) >= MAX_SOURCE_FILES
                        or not (entry.isdir() or entry.isfile()) or entry.sparse is not None
                        or entry.size < 0 or entry.size > MAX_SOURCE_FILE or entry.mode & 0o7000
                        or (entry.isfile() and entry.mode & 0o111)
                        or any(part.casefold() in ('.git', '.venv', '__pycache__')
                               or part.casefold().startswith('.env') for part in parts)):
                    raise ValueError('Unsupported, secret, or out-of-scope Pipeline snapshot member')
                if entry.isdir() and entry.size:
                    raise ValueError('Pipeline directory payload refused')
                seen.add(name)
                for depth in range(1, len(parts)):
                    directories.add('/'.join(parts[:depth]))
                if entry.isdir():
                    directories.add(name)
                    continue
                total += entry.size
                if total > MAX_ARCHIVE:
                    raise ValueError('Expanded Pipeline snapshot exceeds limit')
                stream = archive.extractfile(entry)
                if stream is None:
                    raise ValueError('Missing Pipeline member data')
                data = stream.read(MAX_SOURCE_FILE + 1)
                if len(data) != entry.size:
                    raise ValueError('Truncated Pipeline snapshot')
                files[name] = data
    except tarfile.TarError as exc:
        raise ValueError('Invalid Pipeline snapshot archive') from exc
    if not files or directories.intersection(files):
        raise ValueError('Empty Pipeline snapshot or path collision')
    validate_project_policy(files)
    return files, directories


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
        root = os.fstat(root_fd)
        if root.st_uid != 0 or root.st_mode & 0o077:
            raise ValueError('Pipeline staging directory must be root-owned and private')
        for name in sorted(directories, key=lambda value: (value.count('/'), value)):
            current = root_fd
            descriptors = []
            try:
                for part in relative_parts(name):
                    try:
                        os.mkdir(part, 0o755, dir_fd=current)
                    except FileExistsError:
                        pass
                    child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
                                    | os.O_CLOEXEC, dir_fd=current)
                    descriptors.append(child)
                    current = child
            finally:
                for descriptor in reversed(descriptors):
                    os.close(descriptor)
        for name, data in files.items():
            target = destination.joinpath(*relative_parts(name))
            descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                 0o444)
            with os.fdopen(descriptor, 'wb') as output:
                output.write(data)
        for path in [destination, *destination.rglob('*')]:
            path.chmod(0o555 if path.is_dir() else 0o444)
    return {'source_sha256': expected_sha, 'files': len(files),
            'bytes': sum(len(data) for data in files.values())}
