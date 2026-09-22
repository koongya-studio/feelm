#!/usr/bin/env python3
"""Post-deployment smoke test across the service and distributed nodes."""

from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable
from urllib.parse import urlsplit
from urllib.request import urlopen
from uuid import uuid4


RECOVERY = {
    "nginx_https": "frontend: bash infra/scripts/rollback-frontend.sh",
    "frontend": "frontend: bash infra/scripts/rollback-frontend.sh",
    "backend_health": "backend: bash infra/scripts/rollback-backend.sh",
    "postgresql": "postgresql: bash infra/scripts/deploy-postgresql.sh --recover",
    "redis": "redis: bash infra/scripts/deploy-redis.sh --recover",
    "kafka_roundtrip": "distributed-runtime: bash infra/scripts/deploy-distributed.sh --recover",
    "consumer_group": "consumer: bash infra/scripts/rollback-consumer.sh",
    "hdfs": "distributed-runtime: bash infra/scripts/deploy-distributed.sh --recover",
    "spark_workers": "distributed-runtime: bash infra/scripts/deploy-distributed.sh --recover",
    "prometheus_targets": "monitoring: bash infra/scripts/deploy-monitoring.sh --recover",
}
SECRET = re.compile(r"(?i)(password|token|secret|authorization)(=|:)[^\s,]+")


class SmokeError(RuntimeError):
    pass


@dataclass
class Result:
    name: str
    status: str
    duration_ms: int
    detail: str


def redact(value: object) -> str:
    text = str(value).replace("\r", " ").replace("\n", " ")[:1000]
    return SECRET.sub(r"\1\2<redacted>", text)


def run_command(argv: list[str], timeout: int = 30) -> str:
    try:
        completed = subprocess.run(
            argv, capture_output=True, text=True, timeout=timeout, check=False
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise SmokeError(type(exc).__name__) from exc
    if completed.returncode:
        raise SmokeError(redact(completed.stderr or completed.stdout or "command failed"))
    return completed.stdout.strip()


def read_json(url: str, opener: Callable[..., object] = urlopen) -> object:
    try:
        with opener(url, timeout=5) as response:
            return json.loads(response.read())
    except Exception as exc:
        raise SmokeError(f"HTTP {type(exc).__name__}") from exc


def remote(host: str, *command: str) -> list[str]:
    if not re.fullmatch(r"[A-Za-z0-9_.@:-]+", host):
        raise SmokeError("invalid distributed SSH host")
    return ["ssh", "-o", "BatchMode=yes", "--", host, shlex.join(command)]


def public_base_url(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise SmokeError("public URL must be an HTTPS origin without credentials")
    return value.rstrip("/")


def checks(args: argparse.Namespace, run_id: str):
    base = public_base_url(args.public_url)
    curl = ["curl", "--fail", "--silent", "--show-error", "--output", "/dev/null", "--write-out", "HTTP %{http_code}"]
    yield "nginx_https", lambda: run_command([*curl, f"{base}/"])
    yield "frontend", lambda: run_command([*curl, f"{base}/app/"])
    yield "backend_health", lambda: _backend_health()
    yield "postgresql", lambda: _postgresql()
    yield "redis", lambda: _redis(run_id)
    yield "kafka_roundtrip", lambda: _kafka(args.distributed_host, run_id)
    yield "consumer_group", lambda: _consumer_group(args.distributed_host, args.consumer_group)
    yield "hdfs", lambda: _hdfs(args.distributed_host, run_id)
    yield "spark_workers", lambda: _spark(args.spark_master_url)
    yield "prometheus_targets", lambda: _prometheus(args.prometheus_url)


def _backend_health() -> str:
    payload = json.loads(run_command(["docker", "exec", "feelm-backend", "curl", "--fail", "--silent", "http://127.0.0.1:8080/actuator/health"]))
    if payload.get("status") != "UP":
        raise SmokeError("backend status is not UP")
    return "status=UP"


def _postgresql() -> str:
    output = run_command(["docker", "exec", "feelm-postgres", "sh", "-c", 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT 1"'])
    if output != "1":
        raise SmokeError("unexpected SELECT result")
    return "SELECT 1"


def _redis(run_id: str) -> str:
    key = f"feelm:smoke:{run_id}"
    script = f'export REDISCLI_AUTH="$REDIS_PASSWORD"; redis-cli --no-auth-warning SET "{key}" ok EX 60 >/dev/null; test "$(redis-cli --no-auth-warning GET "{key}")" = ok; redis-cli --no-auth-warning DEL "{key}" >/dev/null'
    run_command(["docker", "exec", "feelm-redis", "sh", "-c", script])
    return "temporary key round-trip and cleanup"


def _kafka(host: str, run_id: str) -> str:
    topic, group, marker = f"feelm.smoke.{run_id}", f"feelm-smoke-{run_id}", f"smoke-{run_id}"
    script = f'''set -eu; K=/opt/kafka/bin; B=127.0.0.1:9092; "$K/kafka-topics.sh" --bootstrap-server "$B" --create --if-not-exists --topic {topic} --partitions 1 --replication-factor 1 >/dev/null; trap '"$K/kafka-topics.sh" --bootstrap-server "$B" --delete --topic {topic} >/dev/null 2>&1 || true; "$K/kafka-consumer-groups.sh" --bootstrap-server "$B" --delete --group {group} >/dev/null 2>&1 || true' EXIT; printf '%s\n' {marker} | "$K/kafka-console-producer.sh" --bootstrap-server "$B" --topic {topic}; out=$("$K/kafka-console-consumer.sh" --bootstrap-server "$B" --topic {topic} --group {group} --from-beginning --max-messages 1 --timeout-ms 15000); test "$out" = {marker}'''
    run_command(remote(host, "docker", "exec", "feelm-kafka", "sh", "-c", script), 45)
    return "isolated produce/consume matched"


def _consumer_group(host: str, group: str) -> str:
    output = run_command(remote(host, "docker", "exec", "feelm-kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "127.0.0.1:9092", "--describe", "--group", group, "--members", "--verbose"))
    if group not in output or "CONSUMER-ID" not in output or len(output.splitlines()) < 2:
        raise SmokeError("consumer group has no active partition assignment")
    return "active member assignment present"


def _hdfs(host: str, run_id: str) -> str:
    path, marker = f"/tmp/feelm-smoke/{run_id}.txt", f"hdfs-{run_id}"
    script = f'''set -eu; trap 'hdfs dfs -rm -f {path} >/dev/null 2>&1 || true; rm -f /tmp/{run_id}.txt' EXIT; printf '%s\n' {marker} >/tmp/{run_id}.txt; hdfs dfs -mkdir -p /tmp/feelm-smoke; hdfs dfs -put -f /tmp/{run_id}.txt {path}; test "$(hdfs dfs -cat {path})" = {marker}'''
    run_command(remote(host, "docker", "exec", "feelm-hdfs-namenode", "sh", "-c", script))
    return "temporary file round-trip and cleanup"


def _spark(url: str, opener: Callable[..., object] = urlopen) -> str:
    payload = read_json(url.rstrip("/") + "/json/", opener)
    workers = payload.get("workers", []) if isinstance(payload, dict) else []
    alive = [worker for worker in workers if worker.get("state") == "ALIVE"]
    if len(alive) != 2 or len({worker.get("host") for worker in alive}) != 2:
        raise SmokeError(f"expected 2 ALIVE workers on distinct hosts, got {len(alive)}")
    return "2 ALIVE workers on distinct hosts"


def _prometheus(url: str) -> str:
    payload = read_json(url.rstrip("/") + "/api/v1/targets")
    targets = payload.get("data", {}).get("activeTargets", []) if isinstance(payload, dict) else []
    required = {"backend": 1, "node": 2, "kafka": 1, "postgres": 1, "redis": 1}
    healthy = {name: 0 for name in required}
    total = {name: 0 for name in required}
    for target in targets:
        job = target.get("labels", {}).get("job")
        if job in healthy:
            total[job] += 1
            if target.get("health") == "up":
                healthy[job] += 1
    bad = [f"{job}={healthy[job]}/{count}" for job, count in required.items() if total[job] != count or healthy[job] != count]
    if bad:
        raise SmokeError("required targets unhealthy or missing: " + ", ".join(bad))
    return "all required targets UP"


def execute(args: argparse.Namespace, factory=checks) -> tuple[list[Result], str | None]:
    results, first_failure = [], None
    run_id = uuid4().hex[:12]
    for name, check in factory(args, run_id):
        started = time.monotonic()
        try:
            detail, status = redact(check()), "PASS"
        except Exception as exc:
            detail, status = redact(exc), "FAIL"
            first_failure = first_failure or name
        results.append(Result(name, status, int((time.monotonic() - started) * 1000), detail))
    return results, first_failure


def write_report(path: Path, results: list[Result], failure: str | None) -> None:
    payload = {"executed_at": datetime.now(timezone.utc).isoformat(), "status": "FAIL" if failure else "PASS", "first_failure": failure, "recovery": RECOVERY.get(failure) if failure else None, "results": [asdict(item) for item in results]}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public-url", required=True)
    parser.add_argument("--distributed-host", required=True)
    parser.add_argument("--spark-master-url", required=True)
    parser.add_argument("--prometheus-url", default="http://127.0.0.1:9090")
    parser.add_argument("--consumer-group", default="feelm-foldin-consumer-v1")
    parser.add_argument("--report", type=Path, default=Path("smoke-report.json"))
    args = parser.parse_args(argv)
    results, failure = execute(args)
    write_report(args.report, results, failure)
    print(f"{'FAIL' if failure else 'PASS'}: report={args.report} recovery={RECOVERY.get(failure, 'none')}")
    return 1 if failure else 0


if __name__ == "__main__":
    raise SystemExit(main())
