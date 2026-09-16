#!/usr/bin/env python3
"""Run the installed Markdown checker against a validated MR snapshot."""
import importlib.util
import os
from pathlib import Path
try:
    import pwd
except ImportError:  # Windows unit-test collection only; execution is Linux-only.
    pwd = None
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid


HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


boundary = load("docs_mr_boundary", "ci-docs-mr-boundary.py")
bounded = load("docs_mr_bounded", "ci-bounded-output.py")
INSTALLED_CHECKER = Path("/usr/local/lib/feelm-ci222/gitlab/check-markdown.py")
RUNTIME_ROOT = Path("/run")
ACCOUNT = "feelm-ci-test"


def unit_command(source, unit):
    if not re.fullmatch(r"ci296-owner-[a-f0-9]{12}\.service", unit):
        raise ValueError("Invalid documentation unit identity")
    source = Path(source)
    return [
        "systemd-run", "--unit=" + unit, "--service-type=exec", "--wait", "--pipe", "--collect",
        "--property=User=" + ACCOUNT, "--property=Group=" + ACCOUNT,
        "--property=CPUQuota=100%", "--property=MemoryHigh=256M", "--property=MemoryMax=512M",
        "--property=TasksMax=64", "--property=RuntimeMaxSec=120", "--property=TimeoutStopSec=10",
        "--property=KillMode=control-group", "--property=NoNewPrivileges=yes",
        "--property=PrivateTmp=yes", "--property=PrivateDevices=yes", "--property=PrivateNetwork=yes",
        "--property=ProtectSystem=strict", "--property=ProtectHome=yes",
        "--property=RestrictAddressFamilies=AF_UNIX", "--property=RestrictSUIDSGID=yes",
        "--property=LockPersonality=yes", "--property=MemoryDenyWriteExecute=yes",
        "/usr/bin/python3", "-I", "-B", str(INSTALLED_CHECKER),
        "--root", str(source), "--tracked-paths", str(source / boundary.MANIFEST),
    ]


def stop_unit(unit):
    if not re.fullmatch(r"ci296-owner-[a-f0-9]{12}\.service", unit):
        raise ValueError("Refusing unrelated documentation unit")
    subprocess.run(["systemctl", "stop", unit], capture_output=True, timeout=30)


def start(payload, digest, on_started=None):
    if pwd is None or not sys.platform.startswith("linux"):
        raise RuntimeError("Documentation MR execution requires Linux")
    account = pwd.getpwnam(ACCOUNT)
    if account.pw_uid != 1001 or account.pw_gid != 1001:
        raise RuntimeError("Reviewed documentation runner account identity differs")
    checker = INSTALLED_CHECKER.lstat()
    if (not stat.S_ISREG(checker.st_mode) or checker.st_uid != 0 or checker.st_mode & 0o022
            or checker.st_nlink != 1):
        raise RuntimeError("Installed Markdown checker is not trusted")
    runtime = RUNTIME_ROOT.lstat()
    if not stat.S_ISDIR(runtime.st_mode) or runtime.st_uid != 0 or runtime.st_mode & 0o022:
        raise RuntimeError("Trusted host-visible runtime directory required")
    token = uuid.uuid4().hex[:12]
    unit = "ci296-owner-" + token + ".service"
    attempt = Path(tempfile.mkdtemp(prefix="ci296-docs-", dir=RUNTIME_ROOT))
    source = attempt / "source"
    source.mkdir(mode=0o700)
    log = attempt / "output.log"
    process = None
    try:
        boundary.install_snapshot(payload, digest, source)
        attempt.chmod(0o711)
        if on_started is not None:
            on_started(source, {"owner": unit})
        process = bounded.CappedProcess(unit_command(source, unit), log)
        try:
            code = process.wait(150)
        except (subprocess.TimeoutExpired, RuntimeError):
            stop_unit(unit)
            process.reap(30)
            raise
        print(log.read_text(errors="replace")[-bounded.LIMIT:], end="")
        if code:
            return 2
        print("DOCS_MR_CHECK_PASSED: fixed offline Markdown policy; no artifacts exported")
        return 0
    finally:
        stop_unit(unit)
        if process is not None and process.child.poll() is None:
            process.reap(30)
        shutil.rmtree(attempt, ignore_errors=True)


def main():
    print("PLAN: validated Markdown snapshot, installed checker, private network, bounded resources/output")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
