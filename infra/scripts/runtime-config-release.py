#!/usr/bin/env python3
"""Manage immutable distributed-runtime source releases and last-known-good state."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


SHA = re.compile(r"^[0-9a-f]{40}$")
COMPONENTS = {"hdfs", "kafka"}
NODES = {"service", "distributed"}


class ReleaseError(RuntimeError):
    pass


def validate_sha(value: str) -> str:
    value = value.lower()
    if not SHA.fullmatch(value):
        raise ReleaseError("revision must be a full lowercase commit SHA")
    return value


class ReleaseStore:
    def __init__(self, root: Path, repository: Path):
        self.root = root.resolve()
        self.repository = repository.resolve()

    def require_root(self) -> None:
        if not self.root.is_dir() or not os.access(self.root, os.W_OK):
            raise ReleaseError("runtime release root is unavailable")
        (self.root / "releases").mkdir(exist_ok=True)
        (self.root / "state").mkdir(exist_ok=True)

    def release(self, revision: str) -> Path:
        return self.root / "releases" / revision

    def state_file(self, component: str, node: str) -> Path:
        if component not in COMPONENTS or node not in NODES:
            raise ReleaseError("unsupported component or node")
        return self.root / "state" / f"{component}-{node}-last-known-good"

    def install(self, revision: str) -> Path:
        revision = validate_sha(revision)
        self.require_root()
        destination = self.release(revision)
        marker = destination / ".feelm-release-revision"
        if destination.exists():
            if marker.is_file() and marker.read_text(encoding="utf-8").strip() == revision:
                return destination
            raise ReleaseError("candidate release already exists with invalid content")
        temporary = Path(tempfile.mkdtemp(prefix=f".{revision}-", dir=self.root / "releases"))
        try:
            archive = subprocess.Popen(
                ["git", "-C", str(self.repository), "archive", revision],
                stdout=subprocess.PIPE,
            )
            extract = subprocess.run(
                ["tar", "-xf", "-", "-C", str(temporary)],
                stdin=archive.stdout,
                check=False,
            )
            if archive.stdout:
                archive.stdout.close()
            if archive.wait() or extract.returncode:
                raise ReleaseError("could not create immutable runtime release")
            (temporary / ".feelm-release-revision").write_text(revision + "\n", encoding="utf-8")
            temporary.rename(destination)
        except BaseException:
            shutil.rmtree(temporary, ignore_errors=True)
            raise
        return destination

    def read_current(self, component: str, node: str) -> str:
        path = self.state_file(component, node)
        if not path.is_file():
            raise ReleaseError("runtime deployment state is not initialized")
        return validate_sha(path.read_text(encoding="utf-8").strip())

    def write_current(self, component: str, node: str, revision: str) -> None:
        revision = validate_sha(revision)
        self.require_root()
        if not self.release(revision).is_dir():
            raise ReleaseError("verified release does not exist")
        path = self.state_file(component, node)
        temporary = path.with_name(path.name + f".tmp.{os.getpid()}")
        temporary.write_text(revision + "\n", encoding="utf-8")
        os.chmod(temporary, 0o600)
        temporary.replace(path)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("action", choices=("install", "read", "write"))
    result.add_argument("--root", required=True)
    result.add_argument("--repository", default=str(Path(__file__).resolve().parents[2]))
    result.add_argument("--component", choices=sorted(COMPONENTS))
    result.add_argument("--node", choices=sorted(NODES))
    result.add_argument("--revision")
    return result


def main() -> int:
    args = parser().parse_args()
    store = ReleaseStore(Path(args.root), Path(args.repository))
    if args.action == "install":
        if not args.revision:
            raise ReleaseError("--revision is required")
        print(store.install(args.revision))
    elif args.action == "read":
        if not args.component or not args.node:
            raise ReleaseError("--component and --node are required")
        print(store.read_current(args.component, args.node))
    else:
        if not args.component or not args.node or not args.revision:
            raise ReleaseError("--component, --node and --revision are required")
        store.write_current(args.component, args.node, args.revision)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ReleaseError as error:
        print(f"Runtime release refused: {error}", file=sys.stderr)
        raise SystemExit(1)
