#!/usr/bin/env python3
"""Verify monitoring health and the required Prometheus scrape targets."""

from __future__ import annotations

import argparse
import json
import time
from typing import Callable
from urllib.parse import urlencode, urlsplit
from urllib.request import urlopen


EXPECTED_TARGETS = {"backend": 1, "node": 2, "kafka": 1, "postgres": 1, "redis": 1}


class VerificationError(RuntimeError):
    pass


def validate_base_url(value: str, label: str) -> str:
    parsed = urlsplit(value)
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise VerificationError(f"{label} URL은 인증정보가 없는 로컬 HTTP 주소여야 합니다.")
    return value.rstrip("/")


def read_json(url: str, label: str, opener: Callable[..., object]) -> object:
    try:
        with opener(url, timeout=5) as response:
            return json.loads(response.read())
    except Exception as error:
        raise VerificationError(f"{label} 요청 실패: {type(error).__name__}") from error


def verify(
    prometheus_url: str = "http://127.0.0.1:9090",
    grafana_url: str = "http://127.0.0.1:3000",
    opener: Callable[..., object] = urlopen,
) -> dict[str, int]:
    prometheus = validate_base_url(prometheus_url, "Prometheus")
    grafana = validate_base_url(grafana_url, "Grafana")

    try:
        with opener(f"{prometheus}/-/ready", timeout=5) as response:
            response.read(4096)
    except Exception as error:
        raise VerificationError(f"Prometheus readiness 실패: {type(error).__name__}") from error

    grafana_health = read_json(f"{grafana}/api/health", "Grafana health", opener)
    if not isinstance(grafana_health, dict) or grafana_health.get("database") != "ok":
        raise VerificationError("Grafana database health가 정상이 아닙니다.")

    expression = 'up{job=~"backend|node|kafka|postgres|redis"}'
    query_url = f"{prometheus}/api/v1/query?{urlencode({'query': expression})}"
    payload = read_json(query_url, "Prometheus target", opener)
    if not isinstance(payload, dict) or payload.get("status") != "success":
        raise VerificationError("Prometheus target 조회가 성공 상태를 반환하지 않았습니다.")
    result = payload.get("data", {}).get("result")
    if not isinstance(result, list):
        raise VerificationError("Prometheus target 응답 형식이 올바르지 않습니다.")

    healthy = {job: 0 for job in EXPECTED_TARGETS}
    total = {job: 0 for job in EXPECTED_TARGETS}
    for series in result:
        try:
            job = series["metric"]["job"]
            value = float(series["value"][1])
        except (KeyError, IndexError, TypeError, ValueError) as error:
            raise VerificationError("Prometheus target 시계열 형식이 올바르지 않습니다.") from error
        if job in total:
            total[job] += 1
            if value == 1:
                healthy[job] += 1

    mismatches = [
        f"{job}={healthy[job]}/{expected}"
        for job, expected in EXPECTED_TARGETS.items()
        if total[job] != expected or healthy[job] != expected
    ]
    if mismatches:
        raise VerificationError("필수 scrape target이 정상이 아닙니다: " + ", ".join(mismatches))
    return healthy


def verify_with_retries(
    attempts: int,
    interval: float,
    prometheus_url: str,
    grafana_url: str,
    verifier: Callable[..., dict[str, int]] = verify,
    sleeper: Callable[[float], None] = time.sleep,
) -> dict[str, int]:
    if attempts < 1 or interval < 0:
        raise VerificationError("재시도 설정이 올바르지 않습니다.")
    last_error: VerificationError | None = None
    for attempt in range(1, attempts + 1):
        try:
            return verifier(prometheus_url, grafana_url)
        except VerificationError as error:
            last_error = error
            if attempt < attempts:
                print(f"Monitoring is not ready; retrying ({attempt}/{attempts})")
                sleeper(interval)
    assert last_error is not None
    raise last_error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prometheus-url", default="http://127.0.0.1:9090")
    parser.add_argument("--grafana-url", default="http://127.0.0.1:3000")
    parser.add_argument("--attempts", type=int, default=24)
    parser.add_argument("--interval", type=float, default=5)
    args = parser.parse_args()
    try:
        result = verify_with_retries(
            args.attempts,
            args.interval,
            args.prometheus_url,
            args.grafana_url,
        )
    except VerificationError as error:
        print(f"FAIL: {error}")
        return 1
    summary = " ".join(f"{job}={count}" for job, count in sorted(result.items()))
    print(f"OK: monitoring health and scrape targets verified ({summary})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
