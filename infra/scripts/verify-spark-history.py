#!/usr/bin/env python3
"""Verify the Spark event-log directory and History Server API."""

from __future__ import annotations

import argparse
import ipaddress
import json
import subprocess
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


def verify_event_log_directory(
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> None:
    command = [
        "docker", "exec", "feelm-hdfs-namenode", "hdfs", "dfs", "-stat", "%a", "/spark-events"
    ]
    try:
        result = runner(command, text=True, capture_output=True, check=False, timeout=10)
    except Exception as error:
        raise VerificationError(f"HDFS event-log check failed ({type(error).__name__})") from error
    if result.returncode != 0:
        raise VerificationError("HDFS /spark-events directory is unavailable")
    if result.stdout.strip() != "1777":
        raise VerificationError("HDFS /spark-events permission is not 1777")


def verify_history_api(host: str, opener: Callable[..., object] = urlopen) -> None:
    address = validate_host(host)
    url = f"http://{address}:18080/api/v1/applications"
    try:
        with opener(url, timeout=5) as response:
            payload = json.loads(response.read())
    except Exception as error:
        raise VerificationError(
            f"Spark History API request failed ({type(error).__name__})"
        ) from error
    if not isinstance(payload, list):
        raise VerificationError("Spark History API response is not an application list")


def verify(
    host: str,
    attempts: int = 24,
    interval: float = 5,
    opener: Callable[..., object] = urlopen,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    sleeper: Callable[[float], None] = time.sleep,
) -> None:
    if attempts < 1 or interval < 0:
        raise VerificationError("attempts must be positive and interval must be non-negative")
    verify_event_log_directory(runner)
    last_error: VerificationError | None = None
    for attempt in range(attempts):
        try:
            verify_history_api(host, opener)
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
    print("OK: HDFS event-log directory and Spark History Server are healthy")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
