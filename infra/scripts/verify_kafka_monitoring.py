#!/usr/bin/env python3
"""Verify the Kafka exporter target and a service consumer group's lag."""

from __future__ import annotations

import argparse
import json
import re
from typing import Callable
from urllib.parse import urlencode, urlsplit
from urllib.request import urlopen


GROUP_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")


class VerificationError(RuntimeError):
    pass


def validate_base_url(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise VerificationError("Prometheus URL은 http 또는 https 주소여야 합니다.")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise VerificationError("Prometheus URL에 인증정보, query 또는 fragment를 넣지 않습니다.")
    return value.rstrip("/")


def instant_query(
    base_url: str,
    expression: str,
    opener: Callable[..., object] = urlopen,
) -> list[dict]:
    url = f"{validate_base_url(base_url)}/api/v1/query?{urlencode({'query': expression})}"
    try:
        with opener(url, timeout=5) as response:
            payload = json.loads(response.read())
    except Exception as error:
        raise VerificationError(f"Prometheus query 실패: {type(error).__name__}") from error

    if payload.get("status") != "success":
        raise VerificationError("Prometheus query가 성공 상태를 반환하지 않았습니다.")
    result = payload.get("data", {}).get("result")
    if not isinstance(result, list):
        raise VerificationError("Prometheus vector 응답 형식이 올바르지 않습니다.")
    return result


def scalar(result: list[dict], label: str) -> float:
    if len(result) != 1:
        raise VerificationError(f"{label} 시계열이 정확히 하나여야 합니다.")
    try:
        return float(result[0]["value"][1])
    except (KeyError, IndexError, TypeError, ValueError) as error:
        raise VerificationError(f"{label} 값 형식이 올바르지 않습니다.") from error


def verify(
    base_url: str,
    consumer_group: str,
    min_lag: float | None = None,
    max_lag: float | None = None,
    opener: Callable[..., object] = urlopen,
) -> dict[str, float]:
    if not GROUP_PATTERN.fullmatch(consumer_group):
        raise VerificationError("Consumer Group 이름 형식이 올바르지 않습니다.")
    if min_lag is not None and max_lag is not None and min_lag > max_lag:
        raise VerificationError("min-lag는 max-lag보다 클 수 없습니다.")

    target_up = scalar(instant_query(base_url, 'up{job="kafka"}', opener), "Kafka target")
    if target_up != 1:
        raise VerificationError("Kafka exporter target이 DOWN입니다.")

    brokers = scalar(
        instant_query(base_url, 'kafka_brokers{job="kafka"}', opener),
        "Kafka broker",
    )
    if brokers < 1:
        raise VerificationError("Kafka broker가 발견되지 않았습니다.")

    lag_query = (
        'sum(kafka_consumergroup_lag{job="kafka",'
        f'consumergroup="{consumer_group}"}})'
    )
    lag = scalar(instant_query(base_url, lag_query, opener), "Consumer lag")
    if min_lag is not None and lag < min_lag:
        raise VerificationError(f"Consumer lag {lag:g}가 최소 기대값 {min_lag:g}보다 작습니다.")
    if max_lag is not None and lag > max_lag:
        raise VerificationError(f"Consumer lag {lag:g}가 허용값 {max_lag:g}보다 큽니다.")
    return {"target_up": target_up, "brokers": brokers, "lag": lag}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prometheus-url", default="http://127.0.0.1:9090")
    parser.add_argument("--consumer-group", default="feelm-foldin-consumer-v1")
    parser.add_argument("--min-lag", type=float)
    parser.add_argument("--max-lag", type=float)
    args = parser.parse_args()
    try:
        result = verify(
            args.prometheus_url,
            args.consumer_group,
            min_lag=args.min_lag,
            max_lag=args.max_lag,
        )
    except VerificationError as error:
        print(f"FAIL: {error}")
        return 1
    print(
        "OK: "
        f"target_up={result['target_up']:g} "
        f"brokers={result['brokers']:g} lag={result['lag']:g}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
