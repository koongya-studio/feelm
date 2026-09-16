#!/usr/bin/env python3
"""Send a non-blocking GitLab deployment result to a Mattermost webhook."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


WEBHOOK_ENV = "MATTERMOST_DEPLOY_WEBHOOK_URL"
REQUEST_TIMEOUT_SECONDS = 10
SAFE_VALUE = re.compile(r"[^A-Za-z0-9._/:-]+")


def safe_value(value: str, fallback: str = "unknown") -> str:
    normalized = SAFE_VALUE.sub("-", value.strip()).strip("-")
    return normalized or fallback


def https_webhook_url(value: str) -> str:
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.netloc
        or parsed.username
        or parsed.password
        or parsed.fragment
    ):
        raise ValueError(f"{WEBHOOK_ENV} must be an HTTPS URL without user info or a fragment")
    return value


def build_message(service: str, status: str, environ: dict[str, str]) -> str:
    labels = {
        "success": ("✅", "성공"),
        "failed": ("❌", "실패"),
        "canceled": ("⚠️", "취소"),
    }
    icon, status_label = labels.get(status, ("ℹ️", safe_value(status)))
    service_label = safe_value(service)
    environment = safe_value(environ.get("CI_ENVIRONMENT_NAME", ""))
    commit = safe_value(environ.get("CI_COMMIT_SHORT_SHA", ""))
    job_url = environ.get("CI_JOB_URL", "").strip()
    pipeline_url = environ.get("CI_PIPELINE_URL", "").strip()

    lines = [
        f"### {icon} {service_label} 배포 {status_label}",
        f"- 환경: `{environment}`",
        f"- 커밋: `{commit}`",
    ]
    if job_url.startswith("https://"):
        lines.append(f"- [배포 Job 확인]({job_url})")
    if pipeline_url.startswith("https://"):
        lines.append(f"- [Pipeline 확인]({pipeline_url})")
    return "\n".join(lines)


def post_webhook(webhook_url: str, message: str) -> None:
    payload = json.dumps({"text": message}, ensure_ascii=False).encode("utf-8")
    request = Request(
        webhook_url,
        data=payload,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
        if not 200 <= response.status < 300:
            raise RuntimeError(f"unexpected HTTP status {response.status}")


def notify(service: str, status: str, environ: dict[str, str]) -> bool:
    raw_url = environ.get(WEBHOOK_ENV, "").strip()
    if not raw_url:
        print(f"경고: {WEBHOOK_ENV}이 없어 배포 알림을 건너뜁니다.", file=sys.stderr)
        return False
    try:
        webhook_url = https_webhook_url(raw_url)
        post_webhook(webhook_url, build_message(service, status, environ))
    except (HTTPError, URLError, OSError, RuntimeError, ValueError) as error:
        detail = f"HTTP {error.code}" if isinstance(error, HTTPError) else type(error).__name__
        print(f"경고: Mattermost 배포 알림 전송 실패 ({detail})", file=sys.stderr)
        return False
    print(f"Mattermost 배포 알림 전송 완료: {safe_value(service)} {safe_value(status)}")
    return True


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=("backend", "frontend", "consumer"))
    parser.add_argument("status")
    return parser.parse_args(argv)


def run(argv: list[str] | None = None, environ: dict[str, str] | None = None) -> int:
    args = parse_args(argv)
    notify(args.service, args.status, environ if environ is not None else os.environ)
    # A notification outage must never replace the deployment job's real status.
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
