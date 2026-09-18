#!/usr/bin/env python3
"""Manage atomic per-node state for the two-node Spark code deployment."""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from typing import Any


SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^[^\s@]+@sha256:[0-9a-f]{64}$")
NODES = {"service", "distributed"}


class StateError(RuntimeError):
    pass


@dataclass(frozen=True)
class DeploymentState:
    revision: str
    image: str
    source_revision: str

    @classmethod
    def parse(cls, raw: Any) -> "DeploymentState":
        if not isinstance(raw, dict) or set(raw) != {
            "revision", "image", "source_revision"
        }:
            raise StateError("Spark deployment manifest has invalid fields")
        revision = raw.get("revision")
        image = raw.get("image")
        source_revision = raw.get("source_revision")
        if not isinstance(revision, str) or not SHA.fullmatch(revision):
            raise StateError("Spark deployment revision is invalid")
        if not isinstance(image, str) or not DIGEST.fullmatch(image):
            raise StateError("Spark deployment image is not an immutable digest")
        if not isinstance(source_revision, str) or not SHA.fullmatch(source_revision):
            raise StateError("Spark source revision is invalid")
        return cls(revision, image, source_revision)


@dataclass(frozen=True)
class PendingTransaction:
    previous: DeploymentState
    candidate: DeploymentState

    @classmethod
    def parse(cls, raw: Any) -> "PendingTransaction":
        if not isinstance(raw, dict) or set(raw) != {"previous", "candidate"}:
            raise StateError("Spark pending transaction has invalid fields")
        return cls(
            DeploymentState.parse(raw["previous"]),
            DeploymentState.parse(raw["candidate"]),
        )


class StateStore:
    def __init__(self, root: Path, node: str):
        if node not in NODES:
            raise StateError("Spark deployment node is invalid")
        self.state_dir = root.resolve() / "state"
        self.manifest = self.state_dir / f"spark-{node}-deployment.json"
        self.pending = self.state_dir / f"spark-{node}-pending.json"

    def _read_json(self, path: Path) -> Any:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError as error:
            raise StateError(f"Spark deployment state is missing: {path.name}") from error
        except (OSError, json.JSONDecodeError) as error:
            raise StateError(f"Spark deployment state is invalid: {path.name}") from error

    def _write_json(self, path: Path, value: Any) -> None:
        self.state_dir.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=path.name + ".tmp.", dir=self.state_dir
        )
        temporary = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                json.dump(value, output, sort_keys=True, separators=(",", ":"))
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, 0o600)
            temporary.replace(path)
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise

    def read_manifest(self) -> DeploymentState:
        return DeploymentState.parse(self._read_json(self.manifest))

    def read_pending(self) -> PendingTransaction:
        return PendingTransaction.parse(self._read_json(self.pending))

    def initialize(self, state: DeploymentState) -> None:
        if self.pending.exists():
            raise StateError("Pending Spark transaction must be resolved before initialization")
        self._write_json(self.manifest, asdict(state))

    def begin(self, candidate: DeploymentState) -> None:
        if self.pending.exists():
            raise StateError("Pending Spark transaction must be settled first")
        previous = self.read_manifest()
        self._write_json(
            self.pending,
            {"previous": asdict(previous), "candidate": asdict(candidate)},
        )

    def promote(self, candidate: DeploymentState) -> None:
        if not self.pending.exists():
            if self.read_manifest() == candidate:
                return
            raise StateError("Pending Spark transaction is missing")
        pending = self.read_pending()
        if pending.candidate != candidate:
            raise StateError("Pending Spark transaction does not match candidate")
        self._write_json(self.manifest, asdict(candidate))

    def restore_state(self, candidate: DeploymentState) -> None:
        pending = self.read_pending()
        if pending.candidate != candidate:
            raise StateError("Pending Spark transaction does not match candidate")
        self._write_json(self.manifest, asdict(pending.previous))

    def resolve(self, candidate: DeploymentState, outcome: str) -> str:
        if outcome not in {"success", "failed"}:
            raise StateError("Spark transaction outcome is invalid")
        if not self.pending.exists():
            current = self.read_manifest()
            if outcome == "success" and current == candidate:
                return "success"
            if outcome == "failed":
                return "rolled_back"
            raise StateError("Pending Spark transaction is missing")
        pending = self.read_pending()
        if pending.candidate != candidate:
            raise StateError("Pending Spark transaction does not match candidate")
        expected = candidate if outcome == "success" else pending.previous
        if self.read_manifest() != expected:
            raise StateError("Spark deployment manifest does not match resolved state")
        self.pending.unlink()
        return "success" if outcome == "success" else "rolled_back"

    def field(self, scope: str, name: str) -> str:
        if scope == "manifest":
            state = self.read_manifest()
        else:
            pending = self.read_pending()
            state = pending.previous if scope == "previous" else pending.candidate
        if name not in {"revision", "image", "source_revision"}:
            raise StateError("Spark deployment state field is invalid")
        return str(getattr(state, name))


def state_from_args(args: argparse.Namespace) -> DeploymentState:
    return DeploymentState.parse({
        "revision": args.revision,
        "image": args.image,
        "source_revision": args.source_revision,
    })


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument(
        "action",
        choices=("initialize", "begin", "promote", "restore-state", "resolve", "field"),
    )
    result.add_argument("--root", required=True)
    result.add_argument("--node", required=True, choices=sorted(NODES))
    result.add_argument("--revision")
    result.add_argument("--image")
    result.add_argument("--source-revision")
    result.add_argument("--outcome", choices=("success", "failed"))
    result.add_argument("--scope", choices=("manifest", "previous", "candidate"))
    result.add_argument("--field", choices=("revision", "image", "source_revision"))
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    store = StateStore(Path(args.root), args.node)
    if args.action == "field":
        if not args.scope or not args.field:
            raise StateError("--scope and --field are required")
        print(store.field(args.scope, args.field))
        return 0
    if not args.revision or not args.image or not args.source_revision:
        raise StateError("revision, image, and source revision are required")
    state = state_from_args(args)
    if args.action == "initialize":
        store.initialize(state)
    elif args.action == "begin":
        store.begin(state)
    elif args.action == "promote":
        store.promote(state)
    elif args.action == "restore-state":
        store.restore_state(state)
    else:
        if not args.outcome:
            raise StateError("--outcome is required")
        print(store.resolve(state, args.outcome))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except StateError as error:
        print(f"Spark deployment state refused: {error}", file=sys.stderr)
        raise SystemExit(1)
