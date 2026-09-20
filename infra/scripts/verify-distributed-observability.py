#!/usr/bin/env python3
"""Verify distributed-node observability endpoints after deployment."""

from __future__ import annotations

import argparse
import ipaddress
import json
import time
from collections.abc import Callable
from urllib.request import urlopen


class VerificationError(RuntimeError):
    pass


def validate_host(value: str) -> str:
    try:
        ipaddress.ip_address(value)
    except ValueError as error:
        raise VerificationError("distributed host must be an IPv4 or IPv6 address") from error
    return f"[{value}]" if ":" in value else value


def read_url(url: str, opener: Callable[..., object] = urlopen) -> bytes:
    try:
        with opener(url, timeout=5) as response:
            return response.read()
    except Exception as error:
        raise VerificationError(f"request failed: {url} ({type(error).__name__})") from error


def verify_once(host: str, opener: Callable[..., object] = urlopen) -> None:
    address = validate_host(host)
    kafka_metrics = read_url(f"http://{address}:9308/metrics", opener)
    if b"kafka_brokers" not in kafka_metrics:
        raise VerificationError("Kafka Exporter metrics do not contain kafka_brokers")

    node_metrics = read_url(f"http://{address}:9100/metrics", opener)
    if b"node_exporter_build_info" not in node_metrics:
        raise VerificationError("Node Exporter metrics do not contain node_exporter_build_info")

    try:
        kafka_ui = json.loads(read_url(f"http://{address}:8082/actuator/health", opener))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError("Kafka UI health response is not valid JSON") from error
    if kafka_ui.get("status") != "UP":
        raise VerificationError("Kafka UI health status is not UP")


def verify(
    host: str,
    attempts: int = 24,
    interval: float = 5,
    opener: Callable[..., object] = urlopen,
    sleeper: Callable[[float], None] = time.sleep,
) -> None:
    if attempts < 1 or interval < 0:
        raise VerificationError("attempts must be positive and interval must be non-negative")
    last_error: VerificationError | None = None
    for attempt in range(attempts):
        try:
            verify_once(host, opener)
            return
        except VerificationError as error:
            last_error = error
            if attempt + 1 < attempts:
                sleeper(interval)
    assert last_error is not None
    raise last_error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--attempts", type=int, default=24)
    parser.add_argument("--interval", type=float, default=5)
    args = parser.parse_args()
    try:
        verify(args.host, args.attempts, args.interval)
    except VerificationError as error:
        print(f"FAIL: {error}")
        return 1
    print("OK: Kafka Exporter, Kafka UI and distributed Node Exporter are healthy")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
