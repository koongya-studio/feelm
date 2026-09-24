#!/usr/bin/env python3
"""Validate and install an untrusted pipeline/consumer MR snapshot."""

from contextlib import contextmanager
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile
import unicodedata


MAX_ARCHIVE = 256 * 1024**2
MAX_SOURCE_FILE = 64 * 1024**2
MAX_SOURCE_FILES = 10000
REQUIRED_FILES = {
    "pipeline/consumer/.python-version",
    "pipeline/consumer/pyproject.toml",
    "pipeline/consumer/uv.lock",
    "pipeline/consumer/Dockerfile",
    "pipeline/consumer/.dockerignore",
}
REVIEWED_IDENTITIES = {
    "pipeline/consumer/pyproject.toml":
        "8eabc9b4488534ae3a1624f1258dbcd604f74b384bdf67df196bf85fb26096c5",
    "pipeline/consumer/uv.lock":
        "fe8e1c9780111c00358bfac342eec03292fb50774ccffa9cc27a413a340ba35c",
    "pipeline/consumer/Dockerfile":
        "0f83cb22b5189a453d163c5c49761467758bd41eda36fa486d2d177527ad2113",
}


def relative_parts(name):
    if not isinstance(name, str) or not name or len(name.encode("utf-8")) > 1024:
        raise ValueError("Invalid Consumer path length")
    parts = name.split("/")
    if (
        name != unicodedata.normalize("NFC", name)
        or "\\" in name
        or ":" in name
        or any(ord(char) < 32 or ord(char) == 127 for char in name)
        or any(part in ("", ".", "..") or len(part.encode("utf-8")) > 255 for part in parts)
        or len(parts) > 32
        or PurePosixPath(name).is_absolute()
    ):
        raise ValueError("Non-canonical Consumer path")
    return parts


def validate_project_policy(files):
    if not REQUIRED_FILES <= files.keys():
        raise ValueError("Consumer runtime, manifest, lock, and container policy are required")
    try:
        python_version = files["pipeline/consumer/.python-version"].decode("ascii").strip()
        lock = files["pipeline/consumer/uv.lock"].decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("Invalid Consumer runtime, manifest, or lock encoding") from exc
    if python_version != "3.12":
        raise ValueError("Unreviewed Consumer runtime")
    for name, expected in REVIEWED_IDENTITIES.items():
        actual = hashlib.sha256(files[name]).hexdigest()
        if actual != expected:
            kind = "lock source" if name.endswith("uv.lock") else "manifest runtime policy"
            if name.endswith("Dockerfile"):
                kind = "container policy"
            raise ValueError("Consumer " + kind + " differs from reviewed identity")
    sources = re.findall(r'(?m)^source = \{ ([^{}]+) \}$', lock)
    allowed = {'registry = "https://pypi.org/simple"', 'editable = "."'}
    if not sources or any(source not in allowed for source in sources):
        raise ValueError("Consumer lock uses an unapproved source")
    registries = re.findall(r'registry\s*=\s*"([^"]+)"', lock)
    urls = re.findall(r'\burl\s*=\s*"([^"]+)"', lock)
    hashes = re.findall(r'\bhash\s*=\s*"([^"]+)"', lock)
    if not registries or any(value != "https://pypi.org/simple" for value in registries):
        raise ValueError("Consumer lock uses an unapproved registry")
    if any(not value.startswith("https://files.pythonhosted.org/") for value in urls):
        raise ValueError("Consumer lock uses an unapproved package source")
    if not urls or len(hashes) < len(urls) or any(
        re.fullmatch(r"sha256:[0-9a-f]{64}", value) is None for value in hashes
    ):
        raise ValueError("Consumer lock package hash is missing or invalid")


def source_plan(payload, expected_sha):
    if (
        not isinstance(payload, bytes)
        or not payload
        or len(payload) > MAX_ARCHIVE
        or re.fullmatch(r"[0-9a-f]{64}", str(expected_sha)) is None
        or hashlib.sha256(payload).hexdigest() != expected_sha
    ):
        raise ValueError("Consumer snapshot size or identity hash mismatch")
    files, directories, seen, identities, total = {}, set(), set(), {}, 0
    try:
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
            for entry in archive:
                name = entry.name[:-1] if entry.isdir() and entry.name.endswith("/") else entry.name
                parts = relative_parts(name)
                in_scope = name == "pipeline" or name == "pipeline/consumer" or name.startswith(
                    "pipeline/consumer/"
                )
                for depth in range(1, len(parts) + 1):
                    prefix = "/".join(parts[:depth])
                    identity = prefix.casefold()
                    if identity in identities and identities[identity] != prefix:
                        raise ValueError("Consumer snapshot path collision")
                    identities[identity] = prefix
                forbidden = any(
                    part.casefold() in (".git", ".venv", "__pycache__", ".pytest_cache", ".ruff_cache")
                    or (part.casefold().startswith(".env") and part != ".env.example")
                    for part in parts
                )
                if (
                    not in_scope
                    or name in seen
                    or len(seen) >= MAX_SOURCE_FILES
                    or not (entry.isdir() or entry.isfile())
                    or entry.sparse is not None
                    or entry.size < 0
                    or entry.size > MAX_SOURCE_FILE
                    or entry.mode & 0o7000
                    or (entry.isfile() and entry.mode & 0o111)
                    or forbidden
                ):
                    raise ValueError("Unsupported, secret, or out-of-scope Consumer snapshot member")
                if entry.isdir() and entry.size:
                    raise ValueError("Consumer directory payload refused")
                seen.add(name)
                for depth in range(1, len(parts)):
                    directories.add("/".join(parts[:depth]))
                if entry.isdir():
                    directories.add(name)
                    continue
                total += entry.size
                if total > MAX_ARCHIVE:
                    raise ValueError("Expanded Consumer snapshot exceeds limit")
                stream = archive.extractfile(entry)
                if stream is None:
                    raise ValueError("Missing Consumer member data")
                data = stream.read(MAX_SOURCE_FILE + 1)
                if len(data) != entry.size:
                    raise ValueError("Truncated Consumer snapshot")
                files[name] = data
    except tarfile.TarError as exc:
        raise ValueError("Invalid Consumer snapshot archive") from exc
    if not files or directories.intersection(files):
        raise ValueError("Empty Consumer snapshot or path collision")
    validate_project_policy(files)
    return files, directories


@contextmanager
def directory_fd(path):
    path = os.fspath(path)
    if not path.startswith("/") or not hasattr(os, "O_NOFOLLOW"):
        raise RuntimeError("Linux absolute destination required")
    parts = [] if path == "/" else relative_parts(path[1:])
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    try:
        for part in parts:
            child = os.open(
                part,
                os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                dir_fd=descriptor,
            )
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
            raise ValueError("Consumer staging directory must be root-owned and private")
        for name in sorted(directories, key=lambda value: (value.count("/"), value)):
            current = root_fd
            descriptors = []
            try:
                for part in relative_parts(name):
                    try:
                        os.mkdir(part, 0o755, dir_fd=current)
                    except FileExistsError:
                        pass
                    child = os.open(
                        part,
                        os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                        dir_fd=current,
                    )
                    descriptors.append(child)
                    current = child
            finally:
                for descriptor in reversed(descriptors):
                    os.close(descriptor)
        for name, data in files.items():
            target = destination.joinpath(*relative_parts(name))
            descriptor = os.open(
                target,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                0o444,
            )
            with os.fdopen(descriptor, "wb") as output:
                output.write(data)
        for path in (destination, *destination.rglob("*")):
            path.chmod(0o555 if path.is_dir() else 0o444)
    return {
        "source_sha256": expected_sha,
        "files": len(files),
        "bytes": sum(len(data) for data in files.values()),
    }
