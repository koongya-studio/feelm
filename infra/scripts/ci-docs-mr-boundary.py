#!/usr/bin/env python3
"""Validate and install a Markdown-only MR snapshot plus tracked-path manifest."""
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


MAX_ARCHIVE = 64 * 1024**2
MAX_SOURCE_FILE = 8 * 1024**2
MAX_SOURCE_FILES = 10000
MAX_TRACKED_PATHS = 100000
MANIFEST = ".ci/tracked-paths.json"


def relative_parts(name):
    if not isinstance(name, str) or not name or len(name.encode("utf-8")) > 2048:
        raise ValueError("Invalid documentation path length")
    parts = name.split("/")
    if (name != unicodedata.normalize("NFC", name) or "\\" in name or ":" in name
            or any(ord(character) < 32 or ord(character) == 127 for character in name)
            or any(part in ("", ".", "..") or len(part.encode("utf-8")) > 255 for part in parts)
            or len(parts) > 64 or PurePosixPath(name).is_absolute()):
        raise ValueError("Non-canonical documentation path")
    return parts


def tracked_manifest(data):
    try:
        values = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("Invalid tracked-path manifest") from exc
    if (not isinstance(values, list) or not values or len(values) > MAX_TRACKED_PATHS
            or any(not isinstance(value, str) for value in values)):
        raise ValueError("Invalid tracked-path manifest entries")
    normalized = ["/".join(relative_parts(value)) for value in values]
    if normalized != sorted(set(normalized)):
        raise ValueError("Tracked paths must be sorted and unique")
    return normalized


def source_plan(payload, expected_sha):
    if (not isinstance(payload, bytes) or not payload or len(payload) > MAX_ARCHIVE
            or not re.fullmatch("[0-9a-f]{64}", expected_sha)
            or hashlib.sha256(payload).hexdigest() != expected_sha):
        raise ValueError("Documentation snapshot size or identity mismatch")
    files, directories, seen, total = {}, set(), set(), 0
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
        for entry in archive:
            name = entry.name[:-1] if entry.isdir() and entry.name.endswith("/") else entry.name
            parts = relative_parts(name)
            allowed_file = name == MANIFEST or name.casefold().endswith(".md")
            if (name in seen or len(seen) >= MAX_SOURCE_FILES
                    or not (entry.isdir() or entry.isfile()) or entry.sparse is not None
                    or entry.size < 0 or entry.size > MAX_SOURCE_FILE or entry.mode & 0o7000
                    or (entry.isfile() and entry.mode & 0o111)
                    or (entry.isfile() and not allowed_file)):
                raise ValueError("Unsupported or duplicate documentation snapshot member")
            if entry.isdir() and entry.size:
                raise ValueError("Documentation directory payload refused")
            seen.add(name)
            for depth in range(1, len(parts)):
                directories.add("/".join(parts[:depth]))
            if entry.isdir():
                directories.add(name)
                continue
            total += entry.size
            if total > MAX_ARCHIVE:
                raise ValueError("Expanded documentation snapshot exceeds limit")
            stream = archive.extractfile(entry)
            if stream is None:
                raise ValueError("Missing documentation member data")
            data = stream.read(MAX_SOURCE_FILE + 1)
            if len(data) != entry.size:
                raise ValueError("Truncated documentation snapshot")
            files[name] = data
    if MANIFEST not in files or directories.intersection(files):
        raise ValueError("Documentation manifest is missing or paths collide")
    tracked = tracked_manifest(files[MANIFEST])
    expected_markdown = {name for name in tracked if name.casefold().endswith(".md")}
    actual_markdown = {name for name in files if name != MANIFEST}
    if not expected_markdown or actual_markdown != expected_markdown:
        raise ValueError("Markdown snapshot differs from tracked-path manifest")
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
        if os.fstat(root_fd).st_uid != 0 or os.fstat(root_fd).st_mode & 0o077:
            raise ValueError("Documentation staging directory must be root-owned and private")
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
            descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o444)
            with os.fdopen(descriptor, "wb") as output:
                output.write(data)
        for path in [destination, *destination.rglob("*")]:
            path.chmod(0o555 if path.is_dir() else 0o444)
    return {"source_sha256": expected_sha, "files": len(files), "bytes": sum(map(len, files.values()))}
