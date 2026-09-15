#!/usr/bin/env python3
"""Shared file contract for one compatible isolated backend runtime."""
import hashlib
import os
from pathlib import Path, PurePosixPath
import stat


FILES = (
    'ci-backend-runtime-contract.py',
    'ci-job-safety.py', 'ci-bounded-output.py', 'ci-job-io.py', 'ci-job-lifecycle.py',
    'ci-server-manager-pilot.py', 'ci-server-sysfs-bootstrap.py', 'ci-local-manager-check.py',
    'ci-local-backend-runtime.py', 'ci-local-backend-inputs.py', 'ci-local-backend-transfer.py',
    'ci-backend-offline-prepare.py', 'ci-rootless-backend-check.py', 'ci-rootless-network-check.py',
    'rootless-backend/LoopbackContainers.java', 'rootless-backend/pilot.init.gradle',
    'rootless-backend/META-INF/services/org.testcontainers.core.CreateContainerCmdModifier',
)


def fingerprint(root):
    root = Path(root)
    result = hashlib.sha256()
    for name in FILES:
        relative = PurePosixPath(name)
        if relative.is_absolute() or '..' in relative.parts or '\\' in name:
            raise ValueError('Unsafe backend runtime contract path')
        path = root.joinpath(*relative.parts)
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or path.is_symlink() or info.st_mode & 0o022
                or (hasattr(os, 'geteuid') and info.st_uid != 0)):
            raise ValueError('Untrusted backend runtime contract file')
        data = path.read_bytes()
        result.update(name.encode('utf-8') + b'\0')
        result.update(hashlib.sha256(data).digest())
    return result.hexdigest()


def require_same(installed, prepared_scripts):
    if fingerprint(installed) != fingerprint(prepared_scripts):
        raise RuntimeError('Installed and prepared backend runtimes differ')

