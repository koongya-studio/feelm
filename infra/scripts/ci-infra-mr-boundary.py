#!/usr/bin/env python3
"""Validate and install the repository subset used by infra-config-test."""

import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import re
import tarfile
import unicodedata


MAX_ARCHIVE = 64 * 1024**2
MAX_FILE = 8 * 1024**2
MAX_FILES = 2000
ALLOWED_ROOTS = (".gitlab/", "infra/")


def parts(name):
    if not isinstance(name, str) or not name or len(name.encode()) > 1024:
        raise ValueError("Invalid infra path")
    result = name.split("/")
    if (name != unicodedata.normalize("NFC", name) or "\\" in name or ":" in name
            or PurePosixPath(name).is_absolute()
            or any(part in ("", ".", "..") for part in result)):
        raise ValueError("Non-canonical infra path")
    return result


def source_plan(payload, expected_sha):
    if (not isinstance(payload, bytes) or not payload or len(payload) > MAX_ARCHIVE
            or re.fullmatch(r"[0-9a-f]{64}", str(expected_sha)) is None
            or hashlib.sha256(payload).hexdigest() != expected_sha):
        raise ValueError("Infra snapshot size or identity mismatch")
    files, directories, seen, total = {}, set(), set(), 0
    try:
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
            for entry in archive:
                name = entry.name[:-1] if entry.isdir() and entry.name.endswith("/") else entry.name
                path_parts = parts(name)
                allowed = (name in (".gitlab-ci.yml", ".gitlab", "infra")
                           or name.startswith(ALLOWED_ROOTS))
                if (not allowed or name in seen
                        or len(seen) >= MAX_FILES or not (entry.isdir() or entry.isfile())
                        or entry.sparse is not None or entry.size < 0 or entry.size > MAX_FILE
                        or entry.mode & 0o7000
                        or any(part.casefold() in (".git", "__pycache__", ".pytest_cache")
                               for part in path_parts)
                        or any(part.casefold().startswith(".env") and part != ".env.example"
                               for part in path_parts)):
                    raise ValueError("Unsupported, secret, or out-of-scope infra member")
                seen.add(name)
                for depth in range(1, len(path_parts)):
                    directories.add("/".join(path_parts[:depth]))
                if entry.isdir():
                    directories.add(name)
                    continue
                total += entry.size
                if total > MAX_ARCHIVE:
                    raise ValueError("Expanded infra snapshot exceeds limit")
                stream = archive.extractfile(entry)
                data = stream.read(MAX_FILE + 1) if stream is not None else b""
                if len(data) != entry.size:
                    raise ValueError("Truncated infra member")
                files[name] = data
    except tarfile.TarError as exc:
        raise ValueError("Invalid infra snapshot") from exc
    required = {".gitlab-ci.yml", ".gitlab/ci/infra.yml",
                "infra/scripts/run-infra-config-tests.py",
                "infra/scripts/test_ci_infra_mr.py"}
    if not required <= files.keys():
        raise ValueError("Infra CI policy and regression test are required")
    return files, directories


def install_snapshot(payload, expected_sha, destination):
    files, directories = source_plan(payload, expected_sha)
    destination = Path(destination)
    for name in sorted(directories, key=lambda value: (value.count("/"), value)):
        target = destination.joinpath(*parts(name))
        target.mkdir(exist_ok=True)
        target.chmod(0o555)
    for name, data in files.items():
        target = destination.joinpath(*parts(name))
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL
                             | getattr(os, "O_NOFOLLOW", 0), 0o444)
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
    return {"files": len(files), "bytes": sum(map(len, files.values()))}
