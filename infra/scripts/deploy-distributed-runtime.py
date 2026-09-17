#!/usr/bin/env python3
"""Safely deploy the stateful two-node Spark, Kafka, and HDFS runtime."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen


COMPONENT_SERVICES = {
    "distributed": {
        "hdfs": ("hdfs-namenode", "hdfs-datanode-2"),
        "kafka": ("kafka",),
        "spark": ("spark-master", "spark-worker-2"),
    },
    "service": {
        "hdfs": ("hdfs-datanode-1",),
        "spark": ("spark-worker-1",),
    },
}

CONTAINERS = {
    "hdfs-namenode": "feelm-hdfs-namenode",
    "hdfs-datanode-1": "feelm-hdfs-datanode-1",
    "hdfs-datanode-2": "feelm-hdfs-datanode-2",
    "kafka": "feelm-kafka",
    "spark-master": "feelm-spark-master",
    "spark-worker-1": "feelm-spark-worker-1",
    "spark-worker-2": "feelm-spark-worker-2",
}


class DeploymentError(RuntimeError):
    pass


class Runner:
    def run(self, command: list[str], *, env: dict[str, str] | None = None,
            capture: bool = False, combined: bool = False) -> str:
        result = subprocess.run(
            command,
            env=env,
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE if capture else None,
            stderr=(subprocess.STDOUT if combined else subprocess.PIPE) if capture else None,
            check=False,
        )
        if result.returncode != 0:
            detail = (result.stderr or "").strip() if capture else ""
            suffix = f": {detail}" if detail else ""
            raise DeploymentError(f"Command failed ({result.returncode}): {command[0]}{suffix}")
        return (result.stdout or "").strip()


def project_root() -> Path:
    return Path(__file__).resolve().parents[2]


def compose_file(root: Path, node: str) -> Path:
    name = "distributed.yml" if node == "distributed" else "service.yml"
    return root / "infra" / "compose" / name


def parse_components(node: str, raw: str) -> tuple[str, ...]:
    requested = tuple(dict.fromkeys(item.strip() for item in raw.split(",") if item.strip()))
    if not requested:
        raise DeploymentError("At least one component is required")
    unsupported = sorted(set(requested) - set(COMPONENT_SERVICES[node]))
    if unsupported:
        raise DeploymentError(
            f"Unsupported component for {node} node: {', '.join(unsupported)}"
        )
    return requested


def selected_services(node: str, components: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(
        service
        for component in components
        for service in COMPONENT_SERVICES[node][component]
    )


def deployment_batches(node: str, components: tuple[str, ...],
                       services: tuple[str, ...]) -> tuple[tuple[str, ...], ...]:
    if components == ("hdfs",) and node == "distributed":
        return (("hdfs-namenode",), ("hdfs-datanode-2",))
    return (services,)


def load_env_value(path: Path, name: str) -> str | None:
    if not path.is_file():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        if key.strip() == name:
            return value.strip().strip("'\"")
    return None


def compose_command(root: Path, env_file: Path, node: str) -> list[str]:
    return [
        "docker", "compose", "--env-file", str(env_file),
        "-f", str(compose_file(root, node)),
    ]


def resolve_commit(root: Path, revision: str, runner: Runner) -> str:
    if not re.fullmatch(r"[0-9A-Fa-f]{7,40}", revision):
        raise DeploymentError("--expected-commit must be a 7-40 character Git SHA")
    release_marker = root / ".feelm-release-revision"
    if release_marker.is_file() and not (root / ".git").exists():
        actual = release_marker.read_text(encoding="utf-8").strip()
        if not re.fullmatch(r"[0-9a-f]{40}", actual):
            raise DeploymentError("Release revision marker is invalid")
        if not actual.startswith(revision.lower()):
            raise DeploymentError(f"Release does not match expected commit {revision}")
        return actual
    expected = runner.run(
        ["git", "-C", str(root), "rev-parse", f"{revision}^{{commit}}"], capture=True
    )
    head = runner.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture=True)
    if expected != head:
        raise DeploymentError(f"Checkout HEAD does not match expected commit {revision}")
    runner.run(["git", "-C", str(root), "diff", "--quiet"])
    runner.run(["git", "-C", str(root), "diff", "--cached", "--quiet"])
    return head


def inspect_image(container: str, runner: Runner) -> str | None:
    try:
        return runner.run(
            ["docker", "inspect", "--format", "{{.Config.Image}}", container],
            capture=True,
        ) or None
    except DeploymentError:
        return None


def spark_status_url(node: str, env_file: Path) -> str:
    if node == "distributed":
        return "http://127.0.0.1:8080/json/"
    host = load_env_value(env_file, "DISTRIBUTED_NODE_IP")
    if not host:
        raise DeploymentError("DISTRIBUTED_NODE_IP is missing from the environment file")
    try:
        ipaddress.ip_address(host)
    except ValueError as error:
        raise DeploymentError("DISTRIBUTED_NODE_IP must be an IPv4 or IPv6 address") from error
    if ":" in host:
        host = f"[{host}]"
    return f"http://{host}:8080/json/"


def read_spark_status(url: str) -> dict[str, object]:
    try:
        with urlopen(url, timeout=5) as response:  # nosec: private host supplied by operator
            status = json.load(response)
    except (OSError, URLError, ValueError) as error:
        raise DeploymentError(f"Could not read Spark Master status: {error}") from error
    if not isinstance(status, dict):
        raise DeploymentError("Spark Master returned an invalid status document")
    return status


def ensure_spark_idle(node: str, env_file: Path) -> None:
    status = read_spark_status(spark_status_url(node, env_file))
    active_apps = status.get("activeapps", [])
    active_drivers = status.get("activedrivers", [])
    if active_apps or active_drivers:
        raise DeploymentError("Spark has active applications or drivers; deployment refused")


def check_compose(root: Path, env_file: Path, node: str,
                  services: tuple[str, ...], runner: Runner,
                  environment: dict[str, str]) -> None:
    if not compose_file(root, node).is_file():
        raise DeploymentError(f"Compose file is missing under {root}")
    configured = runner.run(
        compose_command(root, env_file, node) + ["config", "--services"],
        env=environment,
        capture=True,
    ).splitlines()
    if "hdfs-namenode-init" in services:
        raise DeploymentError("NameNode initialization is forbidden during deployment")
    missing = sorted(set(services) - set(configured))
    if missing:
        raise DeploymentError(f"Compose services are missing: {', '.join(missing)}")


def wait_for_containers(services: tuple[str, ...], runner: Runner,
                        attempts: int) -> None:
    for _ in range(attempts):
        pending = []
        for service in services:
            container = CONTAINERS[service]
            try:
                state = runner.run(
                    [
                        "docker", "inspect", "--format",
                        "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
                        container,
                    ],
                    capture=True,
                )
            except DeploymentError:
                state = "missing"
            if state not in {"running healthy", "running none"}:
                if "unhealthy" in state or state in {"exited none", "dead none"}:
                    raise DeploymentError(f"Container is not healthy: {container} ({state})")
                pending.append(container)
        if not pending:
            return
        time.sleep(5)
    raise DeploymentError(f"Containers did not become ready: {', '.join(pending)}")


def wait_for_hdfs(node: str, runner: Runner, attempts: int) -> None:
    container = (
        "feelm-hdfs-namenode"
        if node == "distributed"
        else "feelm-hdfs-datanode-1"
    )
    last_error: DeploymentError | None = None
    for attempt in range(attempts):
        try:
            report = runner.run(
                ["docker", "exec", container, "hdfs", "dfsadmin", "-report"],
                capture=True,
                combined=True,
            )
            if "Live datanodes (2):" in report:
                return
            last_error = DeploymentError("HDFS does not report two live DataNodes")
        except DeploymentError as error:
            last_error = error
        if attempt + 1 < attempts:
            time.sleep(5)
    detail = f": {last_error}" if last_error else ""
    raise DeploymentError(f"HDFS did not become ready{detail}")


def verify_hdfs_read_write(node: str, runner: Runner) -> None:
    container = "feelm-hdfs-namenode" if node == "distributed" else "feelm-hdfs-datanode-1"
    probe = f"/verification/runtime-config/probe-{node}-{os.getpid()}"
    script = (
        "set -eu; path=$1; "
        "trap 'hdfs dfs -rm -f \"$path\" >/dev/null 2>&1 || true' EXIT; "
        "printf 'feelm-runtime-config-probe' | hdfs dfs -put - \"$path\"; "
        "test \"$(hdfs dfs -cat \"$path\")\" = 'feelm-runtime-config-probe'"
    )
    runner.run(["docker", "exec", container, "sh", "-c", script, "probe", probe])


def verify_runtime(node: str, components: tuple[str, ...], env_file: Path,
                   services: tuple[str, ...], runner: Runner, attempts: int) -> None:
    wait_for_containers(services, runner, attempts)
    if "kafka" in components:
        runner.run([
            "docker", "exec", "feelm-kafka",
            "/opt/kafka/bin/kafka-metadata-quorum.sh",
            "--bootstrap-server", "localhost:9092", "describe", "--status",
        ])
    if "hdfs" in components:
        wait_for_hdfs(node, runner, attempts)
    if "spark" in components:
        if node == "service":
            for _ in range(attempts):
                logs = runner.run(
                    ["docker", "logs", "--tail", "200", "feelm-spark-worker-1"],
                    capture=True,
                    combined=True,
                )
                if "Successfully registered with master" in logs:
                    return
                time.sleep(5)
            raise DeploymentError("Spark Worker 1 did not register with the Master")
        last_error: DeploymentError | None = None
        for _ in range(attempts):
            try:
                status = read_spark_status(spark_status_url(node, env_file))
                workers = status.get("workers", [])
                if not isinstance(workers, list):
                    raise DeploymentError("Spark Master returned an invalid worker list")
                alive = [
                    worker for worker in workers
                    if isinstance(worker, dict) and worker.get("state") == "ALIVE"
                ]
                if len(alive) >= 2:
                    return
            except DeploymentError as error:
                last_error = error
            time.sleep(5)
        detail = f": {last_error}" if last_error else ""
        raise DeploymentError(f"Spark does not report two ALIVE workers{detail}")


def verify_spark_image(services: tuple[str, ...], expected: str, runner: Runner) -> None:
    mismatches = []
    for service in services:
        if not service.startswith("spark-"):
            continue
        actual = inspect_image(CONTAINERS[service], runner)
        if actual != expected:
            mismatches.append(f"{service}={actual or 'missing'}")
    if mismatches:
        raise DeploymentError(
            f"Running Spark image does not match {expected}: {', '.join(mismatches)}"
        )


def deploy(args: argparse.Namespace, runner: Runner) -> int:
    root = Path(args.source_root).resolve()
    env_file = Path(args.env_file).resolve()
    rollback_root = Path(args.rollback_root).resolve() if args.rollback_root else None
    components = parse_components(args.node, args.components)
    services = selected_services(args.node, components)
    environment = os.environ.copy()
    if args.spark_image:
        environment["SPARK_IMAGE"] = args.spark_image

    print(f"Node: {args.node}")
    print(f"Components: {','.join(components)}")
    print(f"Services: {','.join(services)}")
    print(f"Candidate root: {root}")
    print(f"Rollback root: {rollback_root or 'required when applying'}")
    if not args.confirm_deploy:
        print(
            "PLAN ONLY: add --confirm-deploy, --expected-commit, and "
            "--expected-rollback-commit to apply"
        )
        return 0

    if not env_file.is_file():
        raise DeploymentError("The deployment environment file does not exist")
    if not root.is_dir():
        raise DeploymentError("--source-root must be an existing candidate checkout")
    if rollback_root is None or not rollback_root.is_dir() or rollback_root == root:
        raise DeploymentError("--rollback-root must be a different existing checkout")
    if not args.expected_commit:
        raise DeploymentError("--expected-commit is required with --confirm-deploy")
    if not args.expected_rollback_commit:
        raise DeploymentError(
            "--expected-rollback-commit is required with --confirm-deploy"
        )
    if "spark" in components and not args.spark_image:
        raise DeploymentError("--spark-image is required when deploying Spark")

    commit = resolve_commit(root, args.expected_commit, runner)
    rollback_commit = resolve_commit(
        rollback_root, args.expected_rollback_commit, runner
    )
    if args.spark_image:
        runner.run(["docker", "image", "inspect", args.spark_image])
    previous_images = {
        service: inspect_image(CONTAINERS[service], runner) for service in services
    }
    previous_spark_images = {
        image for service, image in previous_images.items()
        if service.startswith("spark-") and image
    }
    if len(previous_spark_images) > 1:
        raise DeploymentError("Running Spark containers do not use one consistent image")

    check_compose(root, env_file, args.node, services, runner, environment)
    rollback_environment = environment.copy()
    if previous_spark_images:
        rollback_environment["SPARK_IMAGE"] = previous_spark_images.pop()
    check_compose(
        rollback_root, env_file, args.node, services, runner, rollback_environment
    )
    if "spark" in components:
        if args.node == "service" and not args.spark_idle_checked_on_master:
            raise DeploymentError(
                "Service-node Spark deployment requires "
                "--spark-idle-checked-on-master after checking the distributed node"
            )
        if args.node == "distributed" and not args.allow_active_spark_workloads:
            ensure_spark_idle(args.node, env_file)

    print(f"Deploying reviewed commit: {commit}")
    print(f"Verified rollback commit: {rollback_commit}")
    try:
        for batch in deployment_batches(args.node, components, services):
            runner.run(
                compose_command(root, env_file, args.node)
                + ["up", "-d", "--no-deps", "--force-recreate", *batch],
                env=environment,
            )
            verify_runtime(
                args.node, components, env_file, services, runner, args.health_attempts
            )
        if "hdfs" in components:
            verify_hdfs_read_write(args.node, runner)
        if args.spark_image:
            verify_spark_image(services, args.spark_image, runner)
    except DeploymentError as candidate_error:
        print(f"Candidate deployment failed: {candidate_error}", file=sys.stderr)
        print("Restoring services from the previous checkout", file=sys.stderr)
        try:
            for batch in deployment_batches(args.node, components, services):
                runner.run(
                    compose_command(rollback_root, env_file, args.node)
                    + ["up", "-d", "--no-deps", "--force-recreate", *batch],
                    env=rollback_environment,
                )
                verify_runtime(
                    args.node, components, env_file, services, runner, args.health_attempts
                )
            if "hdfs" in components:
                verify_hdfs_read_write(args.node, runner)
            previous_spark_image = rollback_environment.get("SPARK_IMAGE")
            if previous_spark_image:
                verify_spark_image(services, previous_spark_image, runner)
        except DeploymentError as rollback_error:
            raise DeploymentError(
                f"Rollback failed; manual recovery is required: {rollback_error}"
            ) from rollback_error
        raise DeploymentError("Candidate failed; previous runtime was restored") from candidate_error

    running = ", ".join(
        f"{service}={inspect_image(CONTAINERS[service], runner) or 'unknown'}"
        for service in services
    )
    print(f"Deployment completed: {running}")
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Deploy selected Spark, Kafka, or HDFS services without deleting volumes."
    )
    result.add_argument("--node", required=True, choices=sorted(COMPONENT_SERVICES))
    result.add_argument(
        "--components", required=True,
        help="Comma-separated component list (spark,kafka,hdfs as supported by the node)",
    )
    result.add_argument("--env-file", default=str(project_root() / "infra" / ".env"))
    result.add_argument(
        "--source-root", default=str(project_root()),
        help="Reviewed Git checkout whose Compose configuration will be deployed",
    )
    result.add_argument("--spark-image")
    result.add_argument("--expected-commit")
    result.add_argument("--expected-rollback-commit")
    result.add_argument("--rollback-root")
    result.add_argument("--health-attempts", type=int, default=24)
    result.add_argument("--allow-active-spark-workloads", action="store_true")
    result.add_argument("--spark-idle-checked-on-master", action="store_true")
    result.add_argument("--confirm-deploy", action="store_true")
    return result


def main() -> int:
    args = parser().parse_args()
    if args.confirm_deploy and not args.rollback_root:
        raise DeploymentError("--rollback-root is required with --confirm-deploy")
    if args.health_attempts < 1 or args.health_attempts > 120:
        raise DeploymentError("--health-attempts must be between 1 and 120")
    return deploy(args, Runner())


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DeploymentError as error:
        print(f"Deployment refused: {error}", file=sys.stderr)
        raise SystemExit(1)
