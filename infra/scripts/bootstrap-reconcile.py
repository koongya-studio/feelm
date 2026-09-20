#!/usr/bin/env python3
"""Read-only safety gate for the infrastructure bootstrap/reconcile pipeline."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Protocol


HDFS_IMAGE = "apache/hadoop:3.4.3"
KAFKA_IMAGE = "apache/kafka:4.3.1"
VOLUME_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")
PIPELINE_STEPS = (
    "preflight",
    "postgresql",
    "redis",
    "hdfs",
    "kafka",
    "kafka-topics",
    "spark",
    "backend",
    "consumer",
    "frontend-nginx",
    "monitoring-auxiliary",
    "verify-all",
)


class PreflightError(RuntimeError):
    """Raised when the target state is unsafe or ambiguous."""


class Runner(Protocol):
    def run(self, command: list[str]) -> str: ...


class SubprocessRunner:
    def run(self, command: list[str]) -> str:
        try:
            completed = subprocess.run(
                command,
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
        except (OSError, subprocess.CalledProcessError) as error:
            detail = getattr(error, "stderr", "") or str(error)
            raise PreflightError(
                f"Preflight command failed before service mutation: {command[0]}: "
                f"{detail.strip()[:300]}"
            ) from error
        return completed.stdout.strip()


@dataclass(frozen=True)
class VolumeDecision:
    component: str
    logical_volume: str
    volume: str | None
    state: str
    action: str


@dataclass(frozen=True)
class PreflightReport:
    mode: str
    node: str
    steps: tuple[str, ...]
    decisions: tuple[VolumeDecision, ...]


def load_env(path: Path | None) -> dict[str, str]:
    result: dict[str, str] = {}
    if path is not None:
        if not path.is_file():
            raise PreflightError(f"Compose environment file does not exist: {path}")
        for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                raise PreflightError(f"Invalid KEY=VALUE entry at {path}:{number}")
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip().strip("'\"")
    result.update(os.environ)
    return result


def require_environment(env: dict[str, str], node: str) -> None:
    required = {"SERVICE_NODE_IP", "DISTRIBUTED_NODE_IP"}
    if node == "distributed":
        required.add("KAFKA_CLUSTER_ID")
    missing = sorted(name for name in required if not env.get(name))
    if missing:
        raise PreflightError("Required environment values are missing: " + ", ".join(missing))


def verify_tools(runner: Runner) -> None:
    runner.run(["docker", "version", "--format", "{{.Server.Version}}"])
    runner.run(["docker", "compose", "version", "--short"])


def find_volume(logical_name: str, runner: Runner) -> str | None:
    output = runner.run([
        "docker",
        "volume",
        "ls",
        "--filter",
        f"label=com.docker.compose.volume={logical_name}",
        "--format",
        "{{.Name}}",
    ])
    volumes = sorted({line.strip() for line in output.splitlines() if line.strip()})
    if len(volumes) > 1:
        raise PreflightError(
            f"Ambiguous Docker volumes for {logical_name}: {', '.join(volumes)}"
        )
    if volumes and not VOLUME_NAME.fullmatch(volumes[0]):
        raise PreflightError(f"Docker returned an invalid volume name for {logical_name}")
    return volumes[0] if volumes else None


def inspect_volume(volume: str, image: str, probe: str, runner: Runner) -> str:
    runner.run(["docker", "image", "inspect", image, "--format", "{{.Id}}"])
    return runner.run([
        "docker",
        "run",
        "--rm",
        "--network",
        "none",
        "--read-only",
        "--mount",
        f"type=volume,src={volume},dst=/state,readonly",
        "--entrypoint",
        "sh",
        image,
        "-eu",
        "-c",
        probe,
    ])


def inspect_hdfs(volume: str, runner: Runner) -> str:
    return inspect_volume(
        volume,
        HDFS_IMAGE,
        "if [ -f /state/current/VERSION ]; then printf formatted; "
        "elif [ -n \"$(find /state -mindepth 1 -print -quit)\" ]; then printf nonempty; "
        "else printf empty; fi",
        runner,
    )


def inspect_kafka(volume: str, runner: Runner) -> str:
    return inspect_volume(
        volume,
        KAFKA_IMAGE,
        "if [ -f /state/meta.properties ]; then "
        "id=$(sed -n 's/^cluster[.]id=//p' /state/meta.properties | head -n 1); "
        "[ -n \"$id\" ] || { printf invalid; exit 0; }; printf 'cluster:%s' \"$id\"; "
        "elif [ -n \"$(find /state -mindepth 1 -print -quit)\" ]; then printf nonempty; "
        "else printf empty; fi",
        runner,
    )


def persistent_volume_decision(
    component: str, logical_name: str, mode: str, runner: Runner
) -> VolumeDecision:
    volume = find_volume(logical_name, runner)
    return VolumeDecision(
        component=component,
        logical_volume=logical_name,
        volume=volume,
        state="present" if volume else "missing",
        action="preserve" if volume else ("create" if mode == "bootstrap" else "refuse"),
    )


def hdfs_decision(mode: str, runner: Runner) -> VolumeDecision:
    logical = "hdfs_namenode"
    volume = find_volume(logical, runner)
    state = "missing" if volume is None else inspect_hdfs(volume, runner)
    if state == "formatted":
        action = "reuse-without-format"
    elif state in {"missing", "empty"} and mode == "bootstrap":
        action = "initialize-once"
    elif state in {"missing", "empty"}:
        raise PreflightError("HDFS is uninitialized; reconcile mode cannot format the NameNode")
    else:
        raise PreflightError(
            "HDFS NameNode volume is non-empty without valid metadata; formatting is refused"
        )
    return VolumeDecision("hdfs", logical, volume, state, action)


def kafka_decision(mode: str, expected_cluster_id: str, runner: Runner) -> VolumeDecision:
    logical = "kafka_data"
    volume = find_volume(logical, runner)
    state = "missing" if volume is None else inspect_kafka(volume, runner)
    if state.startswith("cluster:"):
        stored_cluster_id = state.removeprefix("cluster:")
        if stored_cluster_id != expected_cluster_id:
            raise PreflightError(
                "Kafka volume Cluster ID does not match KAFKA_CLUSTER_ID; regeneration is refused"
            )
        action = "reuse-cluster-id"
    elif state in {"missing", "empty"} and mode == "bootstrap":
        action = "initialize-once"
    elif state in {"missing", "empty"}:
        raise PreflightError("Kafka is uninitialized; reconcile mode cannot create a Cluster ID")
    else:
        raise PreflightError(
            "Kafka volume is non-empty without a valid cluster.id; initialization is refused"
        )
    return VolumeDecision("kafka", logical, volume, state, action)


def preflight(
    mode: str, node: str, env_file: Path | None, runner: Runner
) -> PreflightReport:
    env = load_env(env_file)
    require_environment(env, node)
    verify_tools(runner)
    if node == "distributed":
        decisions = (
            hdfs_decision(mode, runner),
            persistent_volume_decision("hdfs-datanode-2", "hdfs_datanode_2", mode, runner),
            kafka_decision(mode, env["KAFKA_CLUSTER_ID"], runner),
        )
    else:
        decisions = tuple(
            persistent_volume_decision(component, volume, mode, runner)
            for component, volume in (
                ("postgresql", "postgres_data"),
                ("redis", "redis_data"),
                ("hdfs-datanode-1", "hdfs_datanode_1"),
            )
        )
    refused = [item.component for item in decisions if item.action == "refuse"]
    if refused:
        raise PreflightError(
            "Persistent state is missing in reconcile mode: " + ", ".join(refused)
        )
    return PreflightReport(mode, node, PIPELINE_STEPS, decisions)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description=(
            "Inspect persistent state before bootstrap or reconcile without writing volumes "
            "or changing service containers."
        )
    )
    result.add_argument("--mode", required=True, choices=("bootstrap", "reconcile"))
    result.add_argument("--node", required=True, choices=("service", "distributed"))
    result.add_argument(
        "--env-file",
        type=Path,
        help="Optional Compose environment file; process environment values take precedence.",
    )
    result.add_argument("--json", action="store_true")
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        report = preflight(args.mode, args.node, args.env_file, SubprocessRunner())
    except PreflightError as error:
        print(f"Bootstrap/Reconcile preflight refused: {error}", file=sys.stderr)
        return 1
    payload = asdict(report)
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(f"Preflight passed: mode={report.mode} node={report.node}")
        for decision in report.decisions:
            print(
                f"- {decision.component}: state={decision.state} "
                f"action={decision.action} volume={decision.volume or 'not-created'}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
