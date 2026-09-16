#!/usr/bin/env python3
"""Execute infra configuration tests in a bounded, networkless systemd unit."""

import importlib.util
from pathlib import Path
import re
import shutil
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


boundary = load("infra_mr_boundary", "ci-infra-mr-boundary.py")
bounded = load("infra_mr_bounded", "ci-bounded-output.py")
RUNTIME_ROOT = Path("/run")
INSTALLED = Path("/usr/local/lib/feelm-ci222/gitlab/ci-infra-mr-runner.py")
FIXED_COMMAND = "/usr/bin/python3 -I -B infra/scripts/run-infra-config-tests.py"
CONTRACT_COMMAND = "/usr/bin/python3 -I -B infra/scripts/ci-required-variable-preflight.py"


def unit_command(source, unit, group=None):
    if re.fullmatch(r"ci490-infra-[a-f0-9]{12}[.]service", unit) is None:
        raise ValueError("Invalid infra unit")
    commands = {None: FIXED_COMMAND, "variable-contract": CONTRACT_COMMAND}
    if group not in commands:
        raise ValueError("Invalid infra test group")
    return [
        "systemd-run", "--unit=" + unit, "--service-type=exec", "--wait", "--pipe", "--collect",
        "--property=DynamicUser=yes", "--property=PrivateNetwork=yes",
        "--property=NoNewPrivileges=yes", "--property=PrivateTmp=yes",
        "--property=ProtectSystem=strict", "--property=ProtectHome=yes",
        "--property=RestrictSUIDSGID=yes", "--property=MemoryMax=1G",
        "--property=CPUQuota=200%", "--property=TasksMax=256",
        "--property=RuntimeMaxSec=570", "--property=TimeoutStopSec=15",
        "--working-directory=" + str(source), "/bin/sh", "-c", commands[group],
    ]


def start(payload, digest, group=None, on_started=None):
    if not sys.platform.startswith("linux"):
        raise RuntimeError("Infra MR execution requires Linux")
    token = uuid.uuid4().hex[:12]
    unit = "ci490-infra-" + token + ".service"
    attempt = Path(tempfile.mkdtemp(prefix="ci490-infra-", dir=RUNTIME_ROOT))
    source = attempt / "source"
    source.mkdir(mode=0o755)
    log = attempt / "output.log"
    process = None
    try:
        boundary.install_snapshot(payload, digest, source)
        attempt.chmod(0o755)
        if on_started is not None:
            on_started(source, {"owner": unit})
        process = bounded.CappedProcess(unit_command(source, unit, group), log)
        code = process.wait(600)
        print(log.read_text(errors="replace")[-bounded.LIMIT:], end="")
        if code:
            return 2
        print("INFRA_CONFIG_TEST_PASSED: networkless fixed policy")
        return 0
    finally:
        subprocess.run(["systemctl", "stop", unit], capture_output=True, timeout=30)
        if process is not None and process.child.poll() is None:
            process.reap(30)
        shutil.rmtree(attempt, ignore_errors=True)


if __name__ == "__main__":
    print("PLAN: validated infra archive, networkless fixed unittest policy")
